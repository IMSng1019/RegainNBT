package regainnbt.detect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ComponentArgument;
import net.minecraft.commands.arguments.CompoundTagArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.NbtPathArgument;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import regainnbt.core.TranslationReport;
import regainnbt.detect.LegacySignatures.Ctx;
import regainnbt.detect.LegacySignatures.KeyRule;
import regainnbt.detect.NbtFragments.Fragment;

/**
 * 类型感知的旧版意图检测（报告 §2.4 / §2.12）。
 *
 * <p>关键点：不能只按键名判断，必须看值的类型/形状。
 * <pre>
 *   {CustomName:'{"text":"Bob"}'}  -> LEGACY（值是字符串，JSON 文本组件）
 *   {CustomName:{text:"Bob"}}      -> MODERN（值是复合标签）
 *   {HandItems:[{}]}               -> LEGACY（旧装备键）
 *   {equipment:{mainhand:{...}}}   -> MODERN（新装备键）
 * </pre>
 *
 * <p>必须覆盖的参数类型（报告 §2.12，携带 NBT/组件/物品的 6 个参数）：
 * ItemArgument / CompoundTagArgument / BlockStateArgument / NbtPathArgument /
 * EntityArgument 的 <code>nbt=</code> / ComponentArgument。
 *
 * <p>性能：Router 每条命令都会调用一次。先做 O(n) 字符预筛（有没有 '{' / '[' / 引号 / nbt=），
 * 没有就直接 MODERN 返回；有才做片段扫描。
 */
public final class IntentDetector {

	/** 复合标签递归最大深度。 */
	private static final int MAX_DEPTH = 4;
	/** 单次检测最多访问的键数（防御性上限）。 */
	private static final int MAX_TAGS = 4096;
	private static final int SNIPPET_LIMIT = 48;

	private IntentDetector() {
	}

	/** 文本级检测（PRE_PARSE 阶段，没有 ParseResults 可用）。 */
	public static IntentVerdict detect(String command, TranslationReport report) {
		if (!mayContainLegacy(command)) {
			step(report, "意图检测[文本级]: 快速扫描未发现 NBT/引号/路径迹象 -> MODERN");
			return IntentVerdict.MODERN;
		}
		List<String> hits = new ArrayList<>();
		scanText(command, hits);
		return finish(hits, report, "文本级");
	}

	/** 节点驱动检测（POST_PARSE 阶段）：用 Brigadier 节点类型定位 NBT 片段，再按值类型判定。 */
	public static IntentVerdict detect(String command, ParseResults<CommandSourceStack> parse, TranslationReport report) {
		List<ParsedCommandNode<CommandSourceStack>> nodes = nodesOf(parse);
		if (nodes.isEmpty() && !mayContainLegacy(command)) {
			step(report, "意图检测[节点驱动]: 无相关节点 + 快速扫描无迹象 -> MODERN");
			return IntentVerdict.MODERN;
		}
		List<String> hits = new ArrayList<>();
		boolean hasNodeInfo = scanNodes(command, parse, hits, report);
		// 节点信息不可用 / 解析在参数中途失败时，文本级扫描作为兜底（永远不会漏掉整段 NBT）
		scanText(command, hits);
		return finish(hits, report, hasNodeInfo ? "节点驱动+文本兜底" : "文本级（节点信息不可用）");
	}

	// ------------------------------------------------------------------
	// 节点驱动：用 ParsedCommandNode 的 ArgumentType + range 切片
	// ------------------------------------------------------------------

	private static boolean scanNodes(String command, ParseResults<CommandSourceStack> parse,
			List<String> hits, TranslationReport report) {
		if (parse == null) return false;
		List<ParsedCommandNode<CommandSourceStack>> nodes = nodesOf(parse);
		if (nodes.isEmpty()) return false;

		int relevant = 0;
		for (ParsedCommandNode<CommandSourceStack> parsed : nodes) {
			ArgumentType<?> type = argumentType(parsed.getNode());
			if (type == null) continue;
			String slice = slice(command, parsed.getRange());
			if (slice == null || slice.isEmpty()) continue;

			if (type instanceof ItemArgument) {
				relevant++;
				scanItemArgument(slice, hits);
			} else if (type instanceof CompoundTagArgument) {
				relevant++;
				analyzeSlice(slice, hits);
			} else if (type instanceof BlockStateArgument) {
				relevant++;
				analyzeSlice(slice, hits);
			} else if (type instanceof NbtPathArgument) {
				relevant++;
				scanPath(slice, hits);
			} else if (type instanceof EntityArgument) {
				relevant++;
				scanSelectorSlice(slice, hits);
			} else if (type instanceof ComponentArgument) {
				relevant++;
				scanComponentSlice(slice, hits);
			}
		}

		if (!parse.getExceptions().isEmpty()) {
			step(report, "意图检测: 解析残留 " + parse.getExceptions().size() + " 个失败节点（参数中途失败），文本级兜底仍生效");
		}
		if (relevant == 0) {
			step(report, "意图检测: 节点里没有 NBT/组件/物品类参数 -> 回退文本级");
			return false;
		}
		return true;
	}

	private static List<ParsedCommandNode<CommandSourceStack>> nodesOf(ParseResults<CommandSourceStack> parse) {
		if (parse == null) return List.of();
		CommandContextBuilder<CommandSourceStack> context = parse.getContext();
		if (context == null) return List.of();
		List<ParsedCommandNode<CommandSourceStack>> nodes = context.getNodes();
		return nodes == null ? List.of() : nodes;
	}

	/**
	 * 极轻量门控（Router 每条命令都会调用）：有 NBT/引号迹象，或者可能带 NbtPathArgument 的指令。
	 * 别的命令直接 MODERN 返回，不做任何分配。
	 */
	private static boolean mayContainLegacy(String command) {
		if (NbtFragments.hasPossibleNbt(command)) return true;
		String head = headToken(command);
		return head.equals("data") || head.equals("execute");
	}

	/** 取第一个空白分隔的 token（去掉前导斜杠与空白），不分配列表。 */
	private static String headToken(String command) {
		if (command == null) return "";
		int i = 0;
		int n = command.length();
		while (i < n && Character.isWhitespace(command.charAt(i))) i++;
		if (i < n && command.charAt(i) == '/') i++;
		int start = i;
		while (i < n && !Character.isWhitespace(command.charAt(i))) i++;
		return command.substring(start, i).toLowerCase(Locale.ROOT);
	}

	private static ArgumentType<?> argumentType(CommandNode<CommandSourceStack> node) {
		if (node instanceof ArgumentCommandNode<?, ?> argument) {
			return argument.getType();
		}
		return null;
	}

	private static String slice(String command, StringRange range) {
		try {
			return range.get(command);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** ItemArgument：现代是 <code>物品[组件=值]</code>，旧版是 <code>物品{...}</code>（报告 §2.2）。 */
	private static void scanItemArgument(String slice, List<String> hits) {
		int brace = slice.indexOf('{');
		int bracket = slice.indexOf('[');
		if (brace >= 0 && (bracket < 0 || brace < bracket)) {
			hits.add("ItemArgument 使用旧版写法 '" + shorten(slice) + "'（物品 token 后直接跟 {...}；现代为 物品[组件=值,...]）");
		}
		analyzeSlice(slice, hits);
	}

	/** EntityArgument：只看选择器里的 <code>nbt={...}</code>。 */
	private static void scanSelectorSlice(String slice, List<String> hits) {
		String compound = NbtFragments.compoundValueAfterKey(slice, "nbt");
		if (compound != null) {
			analyzeFragment(compound, hits);
		}
	}

	/** ComponentArgument：加引号的 JSON 文本组件是旧写法（报告 §2.12）。 */
	private static void scanComponentSlice(String slice, List<String> hits) {
		String s = slice.trim();
		if (s.isEmpty()) return;
		char first = s.charAt(0);
		if (first != '\'' && first != '"') return; // 未加引号的 {"text":"hi"} 是合法现代写法
		String inner = unquote(s);
		if (LegacySignatures.looksLikeJsonComponent(inner)) {
			hits.add("ComponentArgument 使用引号 JSON 文本组件 '" + shorten(inner)
					+ "'（现代会当字面量显示；报告 §2.12 静默失败）");
		}
	}

	/** NbtPathArgument：路径段里出现旧版键（如 Items[0].tag.display.Name）。 */
	private static void scanPath(String path, List<String> hits) {
		for (String segment : NbtFragments.pathSegments(path)) {
			if (!LegacySignatures.isLegacyPathSegment(segment)) continue;
			KeyRule rule = LegacySignatures.ruleFor(segment);
			hits.add("NBT 路径含旧版键 '" + segment + "'：" + rule.reason());
		}
	}

	// ------------------------------------------------------------------
	// 文本级：片段扫描 + 复合标签分析
	// ------------------------------------------------------------------

	private static void scanText(String command, List<String> hits) {
		List<Fragment> fragments = NbtFragments.scan(command);
		String commandName = effectiveCommand(command);

		for (Fragment fragment : fragments) {
			if (!fragment.compound()) continue;
			// give / item 这类 ItemArgument 指令：顶层 {...} 就是旧版物品 NBT
			if (fragment.squareDepth() == 0 && LegacySignatures.ITEM_ARG_COMMANDS.contains(commandName)) {
				hits.add("旧版物品参数写法（" + commandName
						+ " 的物品 token 后直接跟 {...}；现代为 物品[组件=值,...]；报告 §2.2）");
			}
			analyzeFragment(fragment.text(), hits);
		}

		// 文本组件写成引号 JSON（tellraw/title/bossbar/... 的 ComponentArgument 位置）
		if (LegacySignatures.COMPONENT_COMMANDS.contains(commandName)) {
			for (Fragment fragment : fragments) {
				if (!fragment.quoted()) continue;
				if (LegacySignatures.looksLikeJsonComponent(fragment.text())) {
					hits.add("ComponentArgument 使用引号 JSON 文本组件 '" + shorten(fragment.text())
							+ "'（现代会当字面量显示；报告 §2.12 静默失败）");
				}
			}
		}

		// NbtPathArgument（data / execute ... data）的文本级定位
		for (String path : textPathCandidates(command)) {
			scanPath(path, hits);
		}
	}

	private static void analyzeFragment(String text, List<String> hits) {
		try {
			CompoundTag tag = TagParser.parseCompoundFully(text);
			analyze(tag, Ctx.TOP, hits, 0, new int[] {MAX_TAGS});
		} catch (Exception ignored) {
			// 不是合法 SNBT（例如被引号包住的 JSON 片段）——交给别的规则
		}
	}

	private static void analyzeSlice(String slice, List<String> hits) {
		for (Fragment fragment : NbtFragments.scan(slice)) {
			if (fragment.compound()) {
				analyzeFragment(fragment.text(), hits);
			}
		}
	}

	/** 递归遍历复合标签，按键 + 值形状命中旧版特征。 */
	private static void analyze(CompoundTag tag, Ctx ctx, List<String> hits, int depth, int[] budget) {
		if (tag == null || depth > MAX_DEPTH || budget[0] <= 0) return;
		for (Map.Entry<String, Tag> entry : tag.entrySet()) {
			if (--budget[0] <= 0) return;
			String key = entry.getKey();
			Tag value = entry.getValue();

			KeyRule rule = LegacySignatures.ruleFor(key);
			if (rule != null && rule.appliesTo(ctx) && rule.matches(tag, value)) {
				hits.add(rule.reason());
			}

			// 告示牌：front_text/back_text.messages 的元素是 JSON 字符串 -> 旧写法（报告 §2.9）
			if (value instanceof CompoundTag textHolder && (key.equals("front_text") || key.equals("back_text"))) {
				scanMessages(textHolder, hits);
			}

			Ctx childCtx = LegacySignatures.childContext(key, ctx);
			if (childCtx != null) {
				recurse(value, childCtx, hits, depth + 1, budget);
			}
		}
	}

	private static void scanMessages(CompoundTag textHolder, List<String> hits) {
		Tag messages = textHolder.get("messages");
		if (!(messages instanceof ListTag list)) return;
		for (Tag element : list) {
			if (element instanceof StringTag string && LegacySignatures.looksLikeJsonComponent(string.value())) {
				hits.add("旧版告示牌 messages 元素是 JSON 字符串 '" + shorten(string.value())
						+ "'（现代应为复合标签文本组件；报告 §2.9）");
				return;
			}
		}
	}

	private static void recurse(Tag value, Ctx childCtx, List<String> hits, int depth, int[] budget) {
		if (value instanceof CompoundTag compound) {
			analyze(compound, childCtx, hits, depth, budget);
		} else if (value instanceof ListTag list) {
			for (Tag element : list) {
				if (budget[0] <= 0) return;
				if (element instanceof CompoundTag compound) {
					analyze(compound, childCtx, hits, depth, budget);
				}
			}
		}
	}

	// ------------------------------------------------------------------
	// 文本级辅助
	// ------------------------------------------------------------------

	/** 取「真正执行」的指令名（跳过 execute ... run 前缀，去掉前导斜杠）。 */
	public static String effectiveCommand(String command) {
		List<String> words = NbtFragments.words(command);
		if (!words.isEmpty() && words.get(0).startsWith("/")) {
			words.set(0, words.get(0).substring(1));
			if (words.get(0).isEmpty()) words.remove(0);
		}
		if (words.isEmpty()) return "";
		String head = words.get(0).toLowerCase(Locale.ROOT);
		if (!head.equals("execute")) return head;
		for (int i = words.size() - 1; i >= 1; i--) {
			if (words.get(i).equals("run") && i + 1 < words.size()) {
				return words.get(i + 1).toLowerCase(Locale.ROOT);
			}
		}
		return head;
	}

	/**
	 * 文本级猜 NbtPathArgument 的位置（节点驱动时不需要这个猜测）。
	 * <pre>
	 *   data get|merge|modify|remove &lt;kind&gt; &lt;target&gt; &lt;path&gt;   // kind = entity|block|storage
	 *   execute ... if data &lt;kind&gt; &lt;target&gt; &lt;path&gt;
	 *   execute store &lt;result|success&gt; &lt;kind&gt; &lt;target&gt; &lt;path&gt; &lt;type&gt; [scale]
	 * </pre>
	 * 注意 block 目标占 3 个 token（<code>~ ~ ~</code>）。
	 */
	private static List<String> textPathCandidates(String command) {
		List<String> words = NbtFragments.words(command);
		List<String> out = new ArrayList<>();
		if (words.isEmpty()) return out;
		String head = words.get(0).toLowerCase(Locale.ROOT);
		if (head.equals("data")) {
			addDataPath(words, 0, out);
		} else if (head.equals("execute")) {
			for (int i = 1; i < words.size(); i++) {
				String word = words.get(i);
				if (word.equals("data")) {
					addDataPath(words, i, out);
				}
				// execute store <result|success> <block|entity|storage> <target> <path> <type> [scale]
				if (word.equals("store")) {
					addStorePath(words, i, out);
				}
			}
		}
		return out;
	}

	/** data / execute ... if data 之后的路径 token。 */
	private static void addDataPath(List<String> words, int dataIndex, List<String> out) {
		int k = dataIndex + 1;
		if (k < words.size() && isDataVerb(words.get(k))) k++; // bare data <verb> ... 才有动词
		if (k >= words.size()) return;
		String kind = words.get(k);
		int pathIndex;
		if (kind.equals("block")) {
			pathIndex = k + 4; // block + ~ ~ ~ + path
		} else if (kind.equals("entity") || kind.equals("storage")) {
			pathIndex = k + 2; // kind + target + path
		} else {
			return;
		}
		if (pathIndex < words.size()) out.add(words.get(pathIndex));
	}

	/** execute store <result|success> <kind> <target> <path> <type> [scale]。 */
	private static void addStorePath(List<String> words, int storeIndex, List<String> out) {
		if (storeIndex + 2 >= words.size()) return;
		String kind = words.get(storeIndex + 2);
		int pathIndex;
		if (kind.equals("block")) {
			pathIndex = storeIndex + 6; // store result block ~ ~ ~ path
		} else if (kind.equals("entity") || kind.equals("storage")) {
			pathIndex = storeIndex + 4; // store result entity @s path
		} else {
			return;
		}
		if (pathIndex < words.size()) out.add(words.get(pathIndex));
	}

	private static boolean isDataVerb(String word) {
		return word.equals("get") || word.equals("merge") || word.equals("modify") || word.equals("remove");
	}

	private static String unquote(String s) {
		if (s.length() < 2) return s;
		char quote = s.charAt(0);
		if ((quote != '\'' && quote != '"') || s.charAt(s.length() - 1) != quote) return s;
		String body = s.substring(1, s.length() - 1);
		if (body.indexOf('\\') < 0) return body;
		return body.replace("\\" + quote, String.valueOf(quote)).replace("\\\\", "\\");
	}

	private static String shorten(String s) {
		if (s == null) return "";
		String flat = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
		return flat.length() <= SNIPPET_LIMIT ? flat : flat.substring(0, SNIPPET_LIMIT) + "...";
	}

	private static IntentVerdict finish(List<String> hits, TranslationReport report, String mode) {
		IntentVerdict verdict = IntentVerdict.of(hits);
		if (verdict.legacy()) {
			step(report, "意图检测[" + mode + "]: LEGACY -> " + verdict.reason());
		} else {
			step(report, "意图检测[" + mode + "]: MODERN（未命中旧版特征）");
		}
		return verdict;
	}

	private static void step(TranslationReport report, String message) {
		if (report != null) report.step(message);
	}
}
