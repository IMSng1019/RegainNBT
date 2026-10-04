package regainnbt.translate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.ParsedArgument;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ComponentArgument;
import net.minecraft.commands.arguments.CompoundTagArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.NbtPathArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemPredicateArgument;
import net.minecraft.core.Holder;

/**
 * 参数类型分派 + 片段定位。
 *
 * <p>优先级（任务要求）：<b>Brigadier 节点驱动优先</b>；节点信息拿不到（解析失败且节点树无法恢复、
 * 或调用方没有 dispatcher）时，退到「命令根名 + 参数位置」表。
 * 所有 span 都用 {@link SnbtScanner} 的配对括号扫描得到，不做正则。
 */
public final class CommandShape {

	/**
	 * 一个待翻译载荷的片段。
	 *
	 * @param kind         载荷类型
	 * @param start        片段在命令字符串里的起始下标（含）
	 * @param end          片段结束下标（不含）
	 * @param argumentName Brigadier 参数名（节点驱动时可用，用于 reparse 后取回解析值）
	 * @param entityId     实体类型 id（/summon 的 entity 参数、选择器 type= 提示；可能为 null）
	 * @param note         定位依据（诊断用）
	 */
	public record Segment(PayloadKind kind, int start, int end, String argumentName, String entityId, String note) {
		public String text(String command) {
			return command.substring(start, Math.min(end, command.length()));
		}
	}

	private CommandShape() {
	}

	/** 定位所有载荷片段（节点驱动优先，失败时用位置表）。 */
	public static List<Segment> locate(String command, ParseResults<CommandSourceStack> parse) {
		List<Segment> out = new ArrayList<>();
		if (parse != null) {
			Map<String, Object> args = allArguments(parse);
			String entityHint = null;
			// 逐个上下文链扫描：execute ... run 的子命令节点在 child 上下文里，range 仍是绝对下标
			for (CommandContextBuilder<CommandSourceStack> builder = parse.getContext(); builder != null;
					builder = builder.getChild()) {
				List<ParsedCommandNode<CommandSourceStack>> contextNodes = builder.getNodes();
				for (ParsedCommandNode<CommandSourceStack> parsed : contextNodes) {
					if (!(parsed.getNode() instanceof ArgumentCommandNode<?, ?> node)) {
						continue;
					}
					// 注意：Brigadier 的 StringRange#getEnd() 是开区间（get() == substring(start, end)）
					int start = parsed.getRange().getStart();
					int end = Math.min(parsed.getRange().getEnd(), command.length());
					String name = node.getName();
					Object value = args.get(name);
					if (node.getType() instanceof ResourceArgument<?>) {
						if (value instanceof Holder.Reference<?> ref && ref.key() != null) {
							entityHint = ref.key().identifier().toString();
						}
					} else if (node.getType() instanceof ItemArgument) {
						out.add(new Segment(PayloadKind.ITEM, start, end, name, null, "节点:ItemArgument"));
					} else if (node.getType() instanceof ItemPredicateArgument) {
						out.add(new Segment(PayloadKind.ITEM_PREDICATE, start, end, name, null,
							"节点:ItemPredicateArgument"));
					} else if (node.getType() instanceof CompoundTagArgument) {
						PayloadKind kind = compoundKind(contextNodes, command, start);
						if (kind != null) {
							out.add(new Segment(kind, start, end, name, kind == PayloadKind.ENTITY ? entityHint : null,
								"节点:CompoundTagArgument"));
						}
					} else if (node.getType() instanceof BlockStateArgument) {
						out.add(new Segment(PayloadKind.BLOCK_STATE, start, end, name, null, "节点:BlockStateArgument"));
					} else if (node.getType() instanceof ComponentArgument) {
						out.add(new Segment(PayloadKind.TEXT_COMPONENT, start, end, name, null, "节点:ComponentArgument"));
					} else if (node.getType() instanceof EntityArgument) {
						String selectorText = command.substring(start, end);
						selectorSegments(out, selectorText, start, name);
						String hint = selectorTypeHint(selectorText);
						if (hint != null) {
							entityHint = hint; // 供同一上下文里紧随其后的 CompoundTagArgument（/data merge entity）使用
						}
					} else if (node.getType() instanceof NbtPathArgument) {
						out.add(new Segment(PayloadKind.NBT_PATH, start, end, name, null, "节点:NbtPathArgument"));
					}
				}
			}
			if (out.isEmpty()) {
				Segment recovered = recoverFailedArgument(command, parse);
				if (recovered != null) {
					out.add(recovered);
				}
			}
		}
		if (out.isEmpty()) {
			out.addAll(fromTable(command));
		}
		return dedupe(out);
	}

	/** 从选择器原文里取 type= 的实体类型提示（只认简单的、非标签、非取反形式）。 */
	private static String selectorTypeHint(String selector) {
		int open = selector.indexOf('[');
		if (open < 0) {
			return null;
		}
		int close = SnbtScanner.matchBracket(selector, open);
		if (close < 0) {
			close = selector.length() - 1;
		}
		int i = open + 1;
		while (i < close) {
			char c = selector.charAt(i);
			if (c == '\'' || c == '"') {
				int e = SnbtScanner.skipQuoted(selector, i);
				if (e < 0) {
					return null;
				}
				i = e + 1;
				continue;
			}
			if (c == ',') {
				i++;
				continue;
			}
			int nameStart = i;
			while (i < close && selector.charAt(i) != '=' && selector.charAt(i) != ',') {
				i++;
			}
			if (i >= close || selector.charAt(i) != '=') {
				continue;
			}
			String name = selector.substring(nameStart, i).trim();
			int valueStart = i + 1;
			int valueEnd = valueStart;
			while (valueEnd < close && selector.charAt(valueEnd) != ',') {
				valueEnd++;
			}
			if ("type".equals(name)) {
				String v = selector.substring(valueStart, valueEnd).trim();
				return v.isEmpty() || v.startsWith("#") || v.startsWith("!") ? null : v;
			}
			i = valueEnd;
		}
		return null;
	}

	/** 合并 ParseResults 上下文链（execute 的重定向会产生子上下文）里的全部参数值。 */
	public static Map<String, Object> allArguments(ParseResults<CommandSourceStack> parse) {
		Map<String, Object> out = new LinkedHashMap<>();
		for (CommandContextBuilder<CommandSourceStack> builder = parse.getContext(); builder != null;
				builder = builder.getChild()) {
			for (Map.Entry<String, ParsedArgument<CommandSourceStack, ?>> e : builder.getArguments().entrySet()) {
				out.put(e.getKey(), e.getValue().getResult());
			}
		}
		return out;
	}

	/** 从参数里取出 summon 的 entity 类型 id。 */
	public static String entityHintOf(Map<String, Object> args) {
		Object v = args.get("entity");
		if (v instanceof Holder.Reference<?> ref && ref.key() != null) {
			return ref.key().identifier().toString();
		}
		return null;
	}

	// ---------------------------------------------------------------- 节点驱动

	/**
	 * CompoundTagArgument 到底是实体还是方块实体：只看<b>本上下文</b>（execute ... run 的嵌套子命令
	 * 在 child 上下文里，根 token 会是 execute，不能用来判断）。
	 */
	private static PayloadKind compoundKind(List<ParsedCommandNode<CommandSourceStack>> contextNodes, String command,
			int start) {
		boolean entity = false;
		boolean block = false;
		boolean storage = false;
		boolean entityResource = false;
		String head = null;
		for (ParsedCommandNode<CommandSourceStack> parsed : contextNodes) {
			if (parsed.getRange().getStart() > start) {
				break;
			}
			if (parsed.getNode() instanceof LiteralCommandNode<?>) {
				String literal = parsed.getNode().getName();
				if (head == null && !literal.isEmpty()) {
					head = literal;
				}
				switch (literal) {
					case "entity" -> entity = true;
					case "block" -> block = true;
					case "storage" -> storage = true;
					default -> {
					}
				}
			} else if (parsed.getNode() instanceof ArgumentCommandNode<?, ?> argument
					&& argument.getType() instanceof ResourceArgument<?> && "entity".equals(argument.getName())) {
				entityResource = true;
			}
		}
		if (entity || entityResource) {
			return PayloadKind.ENTITY;
		}
		if (block) {
			return PayloadKind.BLOCK_ENTITY;
		}
		if (storage) {
			return null; // /data merge storage：既不是实体也不是方块实体，不处理
		}
		if ("summon".equals(head)) {
			return PayloadKind.ENTITY;
		}
		if ("data".equals(head)) {
			List<int[]> toks = SnbtScanner.tokens(command, 0, command.length());
			if (toks.size() >= 3) {
				String verb = command.substring(toks.get(1)[0], toks.get(1)[1]);
				String what = command.substring(toks.get(2)[0], toks.get(2)[1]);
				if ("merge".equals(verb)) {
					if ("entity".equals(what)) {
						return PayloadKind.ENTITY;
					}
					if ("block".equals(what)) {
						return PayloadKind.BLOCK_ENTITY;
					}
				}
			}
		}
		return null;
	}

	/**
	 * 解析失败时的恢复：Brigadier 在失败时会把 reader 回退到失败参数的起点，
	 * 于是「最深的已解析节点 + 它的参数子节点」就能告诉我们下一个参数是什么类型。
	 */
	private static Segment recoverFailedArgument(String command, ParseResults<CommandSourceStack> parse) {
		if (parse.getReader() == null) {
			return null;
		}
		// 用「最深的、有节点的上下文」：execute ... run 的失败发生在子上下文里
		CommandContextBuilder<CommandSourceStack> deepestContext = null;
		for (CommandContextBuilder<CommandSourceStack> builder = parse.getContext(); builder != null;
				builder = builder.getChild()) {
			if (!builder.getNodes().isEmpty()) {
				deepestContext = builder;
			}
		}
		if (deepestContext == null || deepestContext.getNodes().isEmpty()) {
			return null;
		}
		List<ParsedCommandNode<CommandSourceStack>> nodes = deepestContext.getNodes();
		int cursor = parse.getReader().getCursor();
		if (cursor <= 0 || cursor >= command.length()) {
			return null;
		}
		CommandNode<CommandSourceStack> deepest = nodes.get(nodes.size() - 1).getNode();
		Map<String, Object> args = allArguments(parse);
		String entityHint = entityHintOf(args);
		for (CommandNode<CommandSourceStack> child : deepest.getChildren()) {
			if (!(child instanceof ArgumentCommandNode<?, ?> node)) {
				continue;
			}
			PayloadKind kind = kindOf(node.getType());
			if (kind == null || kind == PayloadKind.NBT_PATH) {
				continue;
			}
			if (kind == PayloadKind.ENTITY) {
				kind = compoundKind(nodes, command, cursor);
				if (kind == null) {
					continue;
				}
			}
			int end = SnbtScanner.scanTokenEnd(command, cursor);
			if (end <= cursor) {
				continue;
			}
			String payload = command.substring(cursor, end);
			// 物品参数两种形态都要接：旧 {..} 写法，以及「只有 ID」的写法（ID 改名，例如 grass）
			if (kind == PayloadKind.ITEM && !isItemToken(payload)) {
				continue;
			}
			if (kind == PayloadKind.ITEM_PREDICATE && !isPredicateToken(payload)) {
				continue;
			}
			if (kind == PayloadKind.TEXT_COMPONENT && !SnbtScanner.isQuoted(payload)) {
				continue;
			}
			return new Segment(kind, cursor, end, node.getName(),
				kind == PayloadKind.ENTITY ? entityHint : null, "解析失败后按节点恢复");
		}
		return null;
	}

	/**
	 * 物品 token 的两种合法形态：
	 * <ul>
	 *   <li>旧写法 {@code id{...}} —— 顶层有 { }，NBT 内部的 [ ] 列表/数组不算现代语法</li>
	 *   <li>纯 ID（可能省略命名空间）—— 没有顶层 { }，也没有现代的组件方括号</li>
	 * </ul>
	 */
	private static boolean isItemToken(String payload) {
		String t = payload.trim();
		if (t.isEmpty()) {
			return false;
		}
		if (SnbtScanner.firstTopLevelChar(t, '{') >= 0) {
			return true;
		}
		if (t.indexOf('[') >= 0) {
			return false; // 现代 id[组件=...]
		}
		for (int i = 0; i < t.length(); i++) {
			char c = t.charAt(i);
			boolean ok = c == ':' || c == '_' || c == '.' || c == '-' || c == '/' || (c >= 'a' && c <= 'z')
				|| (c >= '0' && c <= '9');
			if (!ok) {
				return false;
			}
		}
		return true;
	}

	/** 物品谓词 token 只认纯 ID：没有现代的 [ 组件谓词，也没有 # 标签。 */
	private static boolean isPredicateToken(String payload) {
		String t = payload.trim();
		return !t.isEmpty() && t.indexOf('[') < 0 && t.indexOf('#') < 0 && t.indexOf('{') < 0 && isItemToken(t);
	}

	private static PayloadKind kindOf(com.mojang.brigadier.arguments.ArgumentType<?> type) {
		if (type instanceof ItemArgument) {
			return PayloadKind.ITEM;
		}
		if (type instanceof ItemPredicateArgument) {
			return PayloadKind.ITEM_PREDICATE;
		}
		if (type instanceof CompoundTagArgument) {
			return PayloadKind.ENTITY;
		}
		if (type instanceof BlockStateArgument) {
			return PayloadKind.BLOCK_STATE;
		}
		if (type instanceof ComponentArgument) {
			return PayloadKind.TEXT_COMPONENT;
		}
		if (type instanceof NbtPathArgument) {
			return PayloadKind.NBT_PATH;
		}
		return null;
	}

	/** 在 EntityArgument 的原文里找 nbt= 与 type=。 */
	private static void selectorSegments(List<Segment> out, String selector, int absStart, String argumentName) {
		int open = selector.indexOf('[');
		if (open < 0) {
			return;
		}
		int close = SnbtScanner.matchBracket(selector, open);
		if (close < 0) {
			close = selector.length() - 1;
		}
		String typeHint = null;
		int nbtStart = -1;
		int nbtEnd = -1;
		boolean negated = false;
		int i = open + 1;
		while (i < close) {
			char c = selector.charAt(i);
			if (c == '\'' || c == '"') {
				int e = SnbtScanner.skipQuoted(selector, i);
				if (e < 0) {
					break;
				}
				i = e + 1;
				continue;
			}
			if (c == ',') {
				i++;
				continue;
			}
			int nameStart = i;
			while (i < close && selector.charAt(i) != '=' && selector.charAt(i) != ',') {
				i++;
			}
			if (i >= close || selector.charAt(i) != '=') {
				continue;
			}
			String name = selector.substring(nameStart, i).trim();
			int valueStart = i + 1;
			boolean not = name.startsWith("!");
			String bare = not ? name.substring(1) : name;
			if (valueStart < close && selector.charAt(valueStart) == '!') {
				valueStart++;
				not = true;
			}
			int valueEnd;
			if (valueStart < close && (selector.charAt(valueStart) == '{' || selector.charAt(valueStart) == '[')) {
				int m = SnbtScanner.matchBracket(selector, valueStart);
				valueEnd = m < 0 ? close : m + 1;
			} else if (valueStart < close && (selector.charAt(valueStart) == '\'' || selector.charAt(valueStart) == '"')) {
				int m = SnbtScanner.skipQuoted(selector, valueStart);
				valueEnd = m < 0 ? close : m + 1;
			} else {
				int j = valueStart;
				while (j < close && selector.charAt(j) != ',') {
					j++;
				}
				valueEnd = j;
			}
			if ("type".equals(bare) && !not) {
				String v = selector.substring(valueStart, valueEnd).trim();
				if (!v.isEmpty() && !v.startsWith("#")) {
					typeHint = v;
				}
			} else if ("nbt".equals(bare)) {
				negated = not;
				nbtStart = valueStart;
				nbtEnd = valueEnd;
			}
			i = valueEnd;
		}
		if (nbtStart >= 0 && nbtEnd > nbtStart && selector.charAt(nbtStart) == '{') {
			out.add(new Segment(PayloadKind.ENTITY, absStart + nbtStart, absStart + nbtEnd, argumentName, typeHint,
				"选择器 nbt=" + (negated ? "(取反)" : "")));
		}
	}

	// ---------------------------------------------------------------- 位置表

	/**
	 * 拿不到节点信息时的兜底：命令根名 + 参数位置。
	 *
	 * <p>位置只是「提示」：位置上的 token 形状不对（例如 1.20.4 的 {@code /summon} 位置参数占 3 个 token、
	 * {@code /fill} 的方块在第 8 个 token）时，退化为「按形状找第一个带顶层 { } 的 token」。
	 */
	private static List<Segment> fromTable(String command) {
		List<Segment> out = new ArrayList<>();
		List<int[]> toks = SnbtScanner.tokens(command, 0, command.length());
		if (toks.isEmpty()) {
			return out;
		}
		String root = command.substring(toks.get(0)[0], toks.get(0)[1]);
		switch (root) {
			case "give" -> {
				int idx = braceTokenIndex(command, toks, 2);
				if (idx < 0 && toks.size() > 2) {
					idx = 2; // 只有 ID、没有 NBT：仍然可能是旧 ID（grass -> short_grass）
				}
				if (idx >= 0) {
					addItem(out, command, toks.get(idx), null);
				}
			}
			case "summon" -> {
				String entity = toks.size() >= 2 ? command.substring(toks.get(1)[0], toks.get(1)[1]) : null;
				int idx = braceTokenIndex(command, toks, 2);
				if (idx >= 0) {
					addCompound(out, command, toks.get(idx), PayloadKind.ENTITY, entity);
				}
			}
			case "setblock" -> {
				int idx = braceTokenIndex(command, toks, 1);
				if (idx < 0 && toks.size() > 4) {
					idx = 4; // setblock <pos x3> <block>：只有方块 ID 时也要接（grass）
				}
				if (idx >= 0) {
					addBlock(out, command, toks.get(idx), null);
				}
			}
			case "fill" -> {
				int idx = braceTokenIndex(command, toks, 1);
				if (idx < 0 && toks.size() > 7) {
					idx = 7; // fill <from x3> <to x3> <block>
				}
				if (idx >= 0) {
					addBlock(out, command, toks.get(idx), null);
				}
			}
			case "data" -> {
				if (toks.size() >= 3) {
					String verb = command.substring(toks.get(1)[0], toks.get(1)[1]);
					String what = command.substring(toks.get(2)[0], toks.get(2)[1]);
					if ("merge".equals(verb)) {
						int idx = braceTokenIndex(command, toks, 3);
						if (idx >= 0 && "entity".equals(what)) {
							addCompound(out, command, toks.get(idx), PayloadKind.ENTITY, null);
						} else if (idx >= 0 && "block".equals(what)) {
							addCompound(out, command, toks.get(idx), PayloadKind.BLOCK_ENTITY, null);
						}
					} else if ("get".equals(verb) || "remove".equals(verb) || "modify".equals(verb)) {
						int idx = braceTokenIndex(command, toks, 3);
						int pathIdx = idx >= 0 ? idx - 1 : toks.size() - 1;
						if (pathIdx >= 3) {
							out.add(new Segment(PayloadKind.NBT_PATH, toks.get(pathIdx)[0], toks.get(pathIdx)[1],
								null, null, "位置表:NbtPath"));
						}
					}
				}
			}
			case "item" -> {
				for (int i = 0; i < toks.size() - 1; i++) {
					String t = command.substring(toks.get(i)[0], toks.get(i)[1]);
					if ("with".equals(t) || "from".equals(t)) {
						addItem(out, command, toks.get(i + 1), null);
						break;
					}
				}
			}
			case "clear" -> {
				if (toks.size() > 2) {
					addPredicate(out, command, toks.get(2), null);
				}
			}
			case "tellraw" -> {
				int idx = quotedTokenIndex(command, toks, 2);
				if (idx >= 0) {
					addText(out, command, toks.get(idx), null);
				}
			}
			case "title" -> {
				int idx = quotedTokenIndex(command, toks, 3);
				if (idx >= 0) {
					addText(out, command, toks.get(idx), null);
				}
			}
			default -> {
			}
		}
		// 通用：任何 token 里出现选择器 nbt=
		for (int[] t : toks) {
			String s = command.substring(t[0], t[1]);
			if (s.startsWith("@") && s.contains("nbt=")) {
				selectorSegments(out, s, t[0], null);
			}
		}
		return out;
	}

	/** 从 {@code from} 起找第一个「带顶层 { }」的 token（选择器不算）。找不到返回 -1。 */
	private static int braceTokenIndex(String command, List<int[]> toks, int from) {
		for (int i = Math.max(0, from); i < toks.size(); i++) {
			String t = command.substring(toks.get(i)[0], toks.get(i)[1]);
			if (!t.startsWith("@") && SnbtScanner.firstTopLevelChar(t, '{') >= 0) {
				return i;
			}
		}
		return -1;
	}

	/** 从 {@code from} 起找第一个引号包起来的 token。找不到返回 -1。 */
	private static int quotedTokenIndex(String command, List<int[]> toks, int from) {
		for (int i = Math.max(0, from); i < toks.size(); i++) {
			String t = command.substring(toks.get(i)[0], toks.get(i)[1]);
			if (SnbtScanner.isQuoted(t)) {
				return i;
			}
		}
		return -1;
	}

	private static void addItem(List<Segment> out, String command, int[] tok, String argumentName) {
		String text = command.substring(tok[0], tok[1]);
		if (!isItemToken(text)) {
			return;
		}
		out.add(new Segment(PayloadKind.ITEM, tok[0], tok[1], argumentName, null, "位置表:ITEM"));
	}

	private static void addCompound(List<Segment> out, String command, int[] tok, PayloadKind kind, String entityId) {
		String text = command.substring(tok[0], tok[1]);
		if (text.isEmpty() || text.charAt(0) != '{') {
			return;
		}
		out.add(new Segment(kind, tok[0], tok[1], null, entityId, "位置表:" + kind));
	}

	private static void addPredicate(List<Segment> out, String command, int[] tok, String argumentName) {
		String text = command.substring(tok[0], tok[1]);
		if (!isPredicateToken(text)) {
			return;
		}
		out.add(new Segment(PayloadKind.ITEM_PREDICATE, tok[0], tok[1], argumentName, null, "位置表:ITEM_PREDICATE"));
	}

	private static void addBlock(List<Segment> out, String command, int[] tok, String argumentName) {
		String text = command.substring(tok[0], tok[1]);
		if (!isItemToken(text)) {
			return; // 方块 token 与物品 token 同形：id[props]{nbt} 或纯 id
		}
		out.add(new Segment(PayloadKind.BLOCK_STATE, tok[0], tok[1], argumentName, null, "位置表:BLOCK_STATE"));
	}

	private static void addText(List<Segment> out, String command, int[] tok, String argumentName) {
		String text = command.substring(tok[0], tok[1]);
		if (!SnbtScanner.isQuoted(text)) {
			return;
		}
		out.add(new Segment(PayloadKind.TEXT_COMPONENT, tok[0], tok[1], argumentName, null, "位置表:TEXT_COMPONENT"));
	}

	private static List<Segment> dedupe(List<Segment> in) {
		List<Segment> sorted = new ArrayList<>(in);
		sorted.sort((a, b) -> Integer.compare(a.start(), b.start()));
		List<Segment> out = new ArrayList<>();
		int lastEnd = -1;
		for (Segment s : sorted) {
			if (s.start() < lastEnd || s.end() <= s.start()) {
				continue;
			}
			out.add(s);
			lastEnd = s.end();
		}
		return out;
	}
}


