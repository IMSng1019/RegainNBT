package regainnbt.translate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.MinecraftServer;
import regainnbt.core.TranslationReport;
import regainnbt.ids.IdRenames;
import regainnbt.patch.PatchContext;
import regainnbt.patch.PatchRegistry;

/**
 * 旧路（1.20.4 -> 目标版本）的完整翻译链：
 *
 * <pre>
 *   规范化（id / Count / tag 包装 或 注入 id）
 *     -> 原版 DataFixers.update(...)        第 1 层：零维护
 *     -> 我们自己的补丁规则                  第 2 层：覆盖原版的洞
 *     -> 序列化回目标版本语法 -> 原版解析器 reparse -> 验证后采用
 * </pre>
 *
 * <p>硬性约束：只有「reparse 通过 + 关键产物（components / equipment / active_effects）真的出现」
 * 才 {@code report.adopted(true)} 并返回；否则返回 empty 并写诊断，让调用方回落原版报错路径。
 */
public final class LegacyTranslator {

	private static final int MAX_DEPTH = 4;

	private LegacyTranslator() {
	}

	/**
	 * @return 翻译后的命令；无法翻译时返回 empty（调用方应回落到原版报错路径）
	 */
	public static Optional<String> translate(String command, CommandSourceStack source,
			CommandDispatcher<CommandSourceStack> dispatcher, TranslationReport report) {
		if (command == null || command.isBlank() || report == null) {
			return Optional.empty();
		}
		String text = command.charAt(0) == '/' ? command.substring(1) : command;
		// 防御性处理显式标记：路由层（Router.stripMark）本应先剥掉，
		// 但数据包函数 / 指令诊断入口也可能直接把带标记的文本送进来。翻译结果不再带标记。
		if (text.startsWith("old.")) {
			text = text.substring(4);
			report.step("已剥离 old. 显式标记");
		}

		List<Expectation> expectations = new ArrayList<>();
		String out;
		try {
			out = translateText(text, source, dispatcher, report, expectations, 0);
		} catch (Throwable t) {
			report.warn("翻译过程异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
			return Optional.empty();
		}
		if (out == null || out.equals(text)) {
			report.step("没有需要翻译的旧版载荷");
			return Optional.empty();
		}
		if (dispatcher == null) {
			report.warn("没有 dispatcher，无法 reparse 验证，不采用");
			return Optional.empty();
		}
		try {
			ParseResults<CommandSourceStack> reparsed = dispatcher.parse(out, source);
			Commands.validateParseResults(reparsed);
			if (!Verifier.verify(out, reparsed, dispatcher, source, expectations, report)) {
				return Optional.empty();
			}
			report.translated(out);
			report.adopted(true);
			report.step("reparse 通过 + 关键产物断言通过");
			return Optional.of(out);
		} catch (CommandSyntaxException e) {
			report.warn("翻译结果 reparse 失败: " + e.getMessage());
			return Optional.empty();
		} catch (Throwable t) {
			report.warn("翻译结果 reparse 异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
			return Optional.empty();
		}
	}

	/** 预热：DFU 首次调用有初始化成本，起服时先跑一次空转换。 */
	public static void warmUp(MinecraftServer server) {
		DfuTranslator.warmUp(server);
	}

	// ------------------------------------------------------------------ 内部

	/**
	 * 一条载荷的验证期望。
	 *
	 * @param kind         载荷类型
	 * @param argumentName Brigadier 参数名（reparse 后据此取回解析值）
	 * @param start        在<b>替换后文本</b>里的起始下标（合成时计算）
	 * @param end          在替换后文本里的结束下标
	 * @param keys         ITEM=必须出现的组件 id；ENTITY/BLOCK_ENTITY=必须出现的键
	 * @param blockId      BLOCK_STATE 期望的方块 id
	 * @param itemId       ITEM 纯 ID 改名时期望的物品 id
	 * @param legacySnbt   翻译前的旧 NBT 文本（用于断言「确实变了」）
	 * @param textComponent 文本组件是否要求「不再是 JSON 字面量」
	 */
	public record Expectation(PayloadKind kind, String argumentName, int start, int end, Set<String> keys,
			String blockId, String itemId, String legacySnbt, boolean textComponent) {

		Expectation shift(int delta) {
			return new Expectation(kind, argumentName, start + delta, end + delta, keys, blockId, itemId, legacySnbt,
				textComponent);
		}

		static Expectation item(String argumentName, Set<String> componentIds) {
			return new Expectation(PayloadKind.ITEM, argumentName, 0, 0, componentIds, null, null, null, false);
		}

		/** 只有 ID、没有 NBT 的物品参数：断言 reparse 后确实是改名后的物品。 */
		static Expectation itemId(String argumentName, String itemId) {
			return new Expectation(PayloadKind.ITEM, argumentName, 0, 0, Set.of(), null, itemId, null, false);
		}

		/**
		 * 物品谓词（/clear、/execute if items）的纯 ID 改名：26.3 没有公开的取值口，
		 * 用「原命令解析失败（legacySnbt 存原文）+ 译文 reparse 通过」的 A/B 断言改名确有必要且有效。
		 */
		static Expectation itemPredicateId(String argumentName, String itemId, String originalCommand) {
			return new Expectation(PayloadKind.ITEM_PREDICATE, argumentName, 0, 0, Set.of(), null, itemId,
				originalCommand, false);
		}

		static Expectation compound(PayloadKind kind, String argumentName, Set<String> keys, String legacySnbt) {
			return new Expectation(kind, argumentName, 0, 0, keys, null, null, legacySnbt, false);
		}

		static Expectation block(String argumentName, String blockId, String legacySnbt) {
			return new Expectation(PayloadKind.BLOCK_STATE, argumentName, 0, 0, Set.of(), blockId, null, legacySnbt,
				false);
		}

		static Expectation text(String argumentName) {
			return new Expectation(PayloadKind.TEXT_COMPONENT, argumentName, 0, 0, Set.of(), null, null, null, true);
		}
	}

	record Replacement(int start, int end, String text, List<Expectation> expectations) {
	}

	private static String translateText(String text, CommandSourceStack source,
			CommandDispatcher<CommandSourceStack> dispatcher, TranslationReport report,
			List<Expectation> outExpectations, int depth) {
		if (depth > MAX_DEPTH) {
			report.warn("execute ... run 嵌套超过 " + MAX_DEPTH + " 层，放弃翻译");
			return null;
		}
		List<int[]> tokens = SnbtScanner.tokens(text, 0, text.length());
		if (tokens.isEmpty()) {
			return null;
		}
		ParseResults<CommandSourceStack> parse = null;
		if (dispatcher != null) {
			try {
				parse = dispatcher.parse(text, source);
			} catch (Throwable t) {
				report.warn("dispatcher.parse 异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
			}
		}

		// /execute ... run <子命令>：递归翻译 run 之后的整段
		int[] runToken = null;
		String root = text.substring(tokens.get(0)[0], tokens.get(0)[1]);
		if ("execute".equals(root)) {
			runToken = SnbtScanner.findTopLevelToken(text, "run", 0, text.length());
		}
		int prefixLimit = runToken == null ? text.length() : runToken[0];

		List<Replacement> replacements = new ArrayList<>();
		for (CommandShape.Segment segment : CommandShape.locate(text, parse)) {
			if (segment.start() >= prefixLimit) {
				continue;
			}
			try {
				translateSegment(text, segment, parse, source, report, replacements);
			} catch (Throwable t) {
				report.warn("片段翻译异常(" + segment.kind() + "): " + t.getClass().getSimpleName() + ": " + t.getMessage());
			}
		}

		if (runToken != null) {
			int tailStart = runToken[1];
			while (tailStart < text.length() && Character.isWhitespace(text.charAt(tailStart))) {
				tailStart++;
			}
			String tail = text.substring(tailStart);
			List<Expectation> tailExpectations = new ArrayList<>();
			String translatedTail = translateText(tail, source, dispatcher, report, tailExpectations, depth + 1);
			if (translatedTail != null && !translatedTail.equals(tail)) {
				replacements.add(new Replacement(tailStart, text.length(), translatedTail, tailExpectations));
			}
		}
		if (replacements.isEmpty()) {
			return null;
		}
		return compose(text, replacements, outExpectations, report);
	}

	private static String compose(String text, List<Replacement> replacements, List<Expectation> outExpectations,
			TranslationReport report) {
		replacements.sort(Comparator.comparingInt(Replacement::start));
		StringBuilder sb = new StringBuilder(text.length() + 64);
		int position = 0;
		int delta = 0;
		for (Replacement r : replacements) {
			if (r.start() < position) {
				report.warn("载荷片段重叠，已跳过一段");
				continue;
			}
			sb.append(text, position, r.start());
			int newStart = r.start() + delta;
			sb.append(r.text());
			for (Expectation e : r.expectations()) {
				outExpectations.add(e.shift(newStart));
			}
			delta += r.text().length() - (r.end() - r.start());
			position = r.end();
		}
		sb.append(text, position, text.length());
		return sb.toString();
	}

	// ------------------------------------------------------------------ 片段翻译

	private static void translateSegment(String command, CommandShape.Segment segment,
			ParseResults<CommandSourceStack> parse, CommandSourceStack source, TranslationReport report,
			List<Replacement> replacements) throws CommandSyntaxException {
		String payload = segment.text(command);
		switch (segment.kind()) {
			case ITEM -> translateItem(segment, payload, report, replacements);
			case ITEM_PREDICATE -> translateItemPredicate(segment, payload, command, report, replacements);
			case ENTITY -> translateEntity(segment, payload, report, replacements);
			case BLOCK_ENTITY -> translateBlockEntity(command, segment, payload, parse, source, report, replacements);
			case BLOCK_STATE -> translateBlockState(segment, payload, report, replacements);
			case TEXT_COMPONENT -> translateTextComponent(segment, payload, report, replacements);
			case NBT_PATH -> warnPath(segment, payload, report);
		}
	}

	/** /give、/item ... with（ITEM）：{id, Count:1b, tag:{...}} -> components -> id[comp=value,...]。 */
	private static void translateItem(CommandShape.Segment segment, String payload, TranslationReport report,
			List<Replacement> replacements) throws CommandSyntaxException {
		Normalizer.ItemPayload item = Normalizer.splitItem(payload);
		if (item == null) {
			return; // 现代 id[组件=...] 语法
		}
		String resolved = IdRenames.resolveItemId(item.itemToken(), report);
		if (resolved == null || resolved.isEmpty()) {
			report.warn("物品 ID 无法解析，放弃翻译: " + item.itemToken());
			return;
		}
		resolved = Normalizer.namespace(resolved);
		if (!BuiltInRegistriesBridge.itemExists(resolved)) {
			report.warn("物品 ID 在目标版本不存在，放弃翻译: " + resolved + "（原 token " + item.itemToken() + "）");
			return;
		}
		if (item.nbtText() == null) {
			// 只有 ID、没有 NBT：只有真的发生改名才动它（现代命令因此不会被改写）
			if (resolved.equals(Normalizer.namespace(item.itemToken()))) {
				return;
			}
			report.step("ITEM(" + segment.argumentName() + ") 仅 ID 改名 " + item.itemToken() + " -> " + resolved);
			replacements.add(new Replacement(segment.start(), segment.end(), resolved,
				List.of(Expectation.itemId(segment.argumentName(), resolved))));
			return;
		}
		CompoundTag legacyTag = TagParser.parseCompoundFully(item.nbtText());
		if (legacyTag.isEmpty()) {
			return;
		}
		CompoundTag legacyStack = new CompoundTag();
		legacyStack.putString("id", resolved);
		legacyStack.putByte("Count", (byte) 1);
		legacyStack.put("tag", legacyTag);

		CompoundTag fixed = DfuTranslator.fixItem(legacyStack, report);
		applyPatches(PayloadKind.ITEM, legacyTag, fixed, resolved, report);

		CompoundTag components = fixed.getCompound("components").orElse(null);
		if (components == null || components.isEmpty()) {
			report.warn("DFU 对物品 " + resolved + " 没有产出 components（静默原样返回），不采用");
			return;
		}
		String rendered = Serializer.renderItem(fixed);
		if (rendered == null) {
			report.warn("物品序列化失败: " + resolved);
			return;
		}
		report.step("ITEM(" + segment.argumentName() + ") " + item.itemToken() + " 旧键 " + legacyTag.keySet()
			+ " -> components " + components.keySet());
		Set<String> expectedComponents = new LinkedHashSet<>();
		for (String key : components.keySet()) {
			expectedComponents.add(Normalizer.namespace(key));
		}
		replacements.add(new Replacement(segment.start(), segment.end(), rendered,
			List.of(Expectation.item(segment.argumentName(), expectedComponents))));
	}

	/**
	 * /clear、/execute if items 的物品谓词（ITEM_PREDICATE）：只有 ID token，走 IdRenames 改名。
	 * 只在真的改名时才替换（现代命令因此不会被无意义改写），改名后仍要 reparse 通过才采用。
	 */
	private static void translateItemPredicate(CommandShape.Segment segment, String payload, String command,
			TranslationReport report, List<Replacement> replacements) {
		String token = payload.trim();
		if (token.isEmpty() || token.indexOf('[') >= 0 || token.indexOf('#') >= 0 || token.indexOf('{') >= 0) {
			return; // 现代组件谓词 / 标签谓词，不动
		}
		String resolved = IdRenames.resolveItemId(token, report);
		if (resolved == null || resolved.isEmpty()) {
			report.warn("物品谓词 ID 无法解析，放弃翻译: " + token);
			return;
		}
		resolved = Normalizer.namespace(resolved);
		if (!BuiltInRegistriesBridge.itemExists(resolved)) {
			report.warn("物品谓词 ID 在目标版本不存在，放弃翻译: " + resolved + "（原 token " + token + "）");
			return;
		}
		if (resolved.equals(Normalizer.namespace(token))) {
			return; // 本来就合法：不改写
		}
		report.step("ITEM_PREDICATE(" + segment.argumentName() + ") 仅 ID 改名 " + token + " -> " + resolved);
		replacements.add(new Replacement(segment.start(), segment.end(), resolved,
			List.of(Expectation.itemPredicateId(segment.argumentName(), resolved, command))));
	}

	/** /summon、选择器 nbt=、/data merge entity（ENTITY）：注入 id -> equipment / active_effects。 */
	private static void translateEntity(CommandShape.Segment segment, String payload, TranslationReport report,
			List<Replacement> replacements) throws CommandSyntaxException {
		CompoundTag before = TagParser.parseCompoundFully(payload);
		if (before.isEmpty()) {
			return;
		}
		String reason = PayloadSignals.legacyReason(before);
		if (reason == null) {
			return; // 已经现代语义，不改写
		}
		boolean synthetic = segment.entityId() == null || segment.entityId().isEmpty();
		String id = Normalizer.resolveEntityId(segment.entityId());
		CompoundTag work = before.copy();
		boolean injected = Normalizer.injectId(work, id);
		CompoundTag fixed = DfuTranslator.fixEntity(work, report);
		if (injected) {
			fixed.remove("id");
		}
		applyPatches(PayloadKind.ENTITY, before, fixed, id, report);
		if (fixed.equals(before)) {
			report.warn("ENTITY 载荷翻译后无变化（DFU 与补丁都未覆盖，" + reason + "），不采用");
			return;
		}
		report.step("ENTITY(" + segment.argumentName() + ") " + reason + "，id=" + id
			+ (synthetic ? "(合成，已从输出移除)" : ""));
		if (synthetic) {
			report.warn("选择器 nbt= 无法确定实体类型，已用合成 id " + id + " 触发 DFU，输出里不带 id");
		}
		replacements.add(new Replacement(segment.start(), segment.end(), fixed.toString(),
			List.of(Expectation.compound(PayloadKind.ENTITY, segment.argumentName(), entityKeys(before), before.toString()))));
	}

	/** /data merge block（BLOCK_ENTITY）。 */
	private static void translateBlockEntity(String command, CommandShape.Segment segment, String payload,
			ParseResults<CommandSourceStack> parse, CommandSourceStack source, TranslationReport report,
			List<Replacement> replacements) throws CommandSyntaxException {
		CompoundTag before = TagParser.parseCompoundFully(payload);
		if (before.isEmpty()) {
			return;
		}
		String reason = PayloadSignals.legacyReason(before);
		if (reason == null) {
			return;
		}
		String id = Normalizer.resolveBlockEntityType(command, parse, source, before, report);
		if (id == null) {
			report.warn("BLOCK_ENTITY 无法确定方块实体类型（" + reason + "），不采用");
			return;
		}
		CompoundTag work = before.copy();
		boolean injected = Normalizer.injectId(work, id);
		CompoundTag fixed = DfuTranslator.fixBlockEntity(work, report);
		if (injected) {
			fixed.remove("id");
		}
		applyPatches(PayloadKind.BLOCK_ENTITY, before, fixed, id, report);
		if (fixed.equals(before)) {
			report.warn("BLOCK_ENTITY 载荷翻译后无变化（DFU 与补丁都未覆盖，" + reason + "），不采用");
			return;
		}
		report.step("BLOCK_ENTITY(" + segment.argumentName() + ") " + reason + "，方块实体类型=" + id);
		replacements.add(new Replacement(segment.start(), segment.end(), fixed.toString(),
			List.of(Expectation.compound(PayloadKind.BLOCK_ENTITY, segment.argumentName(), entityKeys(before),
				before.toString()))));
	}

	/** /setblock、/fill（BLOCK_STATE -> 注入方块 id 走 BLOCK_ENTITY）。 */
	private static void translateBlockState(CommandShape.Segment segment, String payload, TranslationReport report,
			List<Replacement> replacements) throws CommandSyntaxException {
		Normalizer.BlockPayload block = Normalizer.splitBlockState(payload);
		if (block == null) {
			return;
		}
		String resolvedBlock = IdRenames.resolveBlockId(block.blockToken(), report);
		if (resolvedBlock == null || resolvedBlock.isEmpty()) {
			report.warn("方块 ID 无法解析，放弃翻译: " + block.blockToken());
			return;
		}
		resolvedBlock = Normalizer.namespace(resolvedBlock);
		if (!BuiltInRegistriesBridge.blockExists(resolvedBlock)) {
			report.warn("方块 ID 在目标版本不存在，放弃翻译: " + resolvedBlock + "（原 token " + block.blockToken() + "）");
			return;
		}
		if (block.nbtText() == null) {
			// 只有方块 ID、没有方块实体 NBT：只有真的改名才动它
			if (resolvedBlock.equals(Normalizer.namespace(block.blockToken()))) {
				return;
			}
			String renamed = resolvedBlock + (block.properties() == null ? "" : block.properties());
			report.step("BLOCK_STATE(" + segment.argumentName() + ") 仅 ID 改名 " + block.blockToken() + " -> "
				+ resolvedBlock);
			replacements.add(new Replacement(segment.start(), segment.end(), renamed,
				List.of(Expectation.block(segment.argumentName(), resolvedBlock, null))));
			return;
		}
		CompoundTag before = TagParser.parseCompoundFully(block.nbtText());
		if (before.isEmpty()) {
			return;
		}
		String reason = PayloadSignals.legacyReason(before);
		if (reason == null) {
			return;
		}
		String blockEntityId = Normalizer.blockEntityTypeOf(resolvedBlock);
		if (blockEntityId == null) {
			blockEntityId = resolvedBlock;
			report.warn("方块 " + resolvedBlock + " 找不到方块实体类型，暂用方块 id 试转换");
		}
		CompoundTag work = before.copy();
		boolean injected = Normalizer.injectId(work, blockEntityId);
		CompoundTag fixed = DfuTranslator.fixBlockEntity(work, report);
		if (injected) {
			fixed.remove("id");
		}
		applyPatches(PayloadKind.BLOCK_ENTITY, before, fixed, blockEntityId, report);
		if (fixed.equals(before)) {
			report.warn("BLOCK_STATE 载荷翻译后无变化（DFU 与补丁都未覆盖，" + reason + "，方块实体类型=" + blockEntityId
				+ "），不采用");
			return;
		}
		String rendered = Serializer.renderBlockState(resolvedBlock, block.properties(), fixed);
		report.step("BLOCK_STATE(" + segment.argumentName() + ") " + block.blockToken() + " -> " + resolvedBlock
			+ "，方块实体类型=" + blockEntityId + "，" + reason);
		replacements.add(new Replacement(segment.start(), segment.end(), rendered,
			List.of(Expectation.block(segment.argumentName(), resolvedBlock, before.toString()))));
	}

	/** /tellraw、/title 等（TEXT_COMPONENT）：引号 JSON -> SNBT 组件。 */
	private static void translateTextComponent(CommandShape.Segment segment, String payload, TranslationReport report,
			List<Replacement> replacements) {
		if (!SnbtScanner.isQuoted(payload)) {
			return; // 裸 JSON / 裸字符串就是现代写法
		}
		String json = SnbtScanner.unquote(payload).trim();
		if (!json.startsWith("{") && !json.startsWith("[")) {
			return;
		}
		Tag fixed = DfuTranslator.fixTextComponent(json, report);
		if (fixed instanceof StringTag unchanged && unchanged.value().equals(json)) {
			report.warn("DFU 未转换文本组件（原样返回），不采用: " + json);
			return;
		}
		report.step("TEXT_COMPONENT(" + segment.argumentName() + ") 引号 JSON -> " + fixed);
		replacements.add(new Replacement(segment.start(), segment.end(), fixed.toString(),
			List.of(Expectation.text(segment.argumentName()))));
	}

	private static void warnPath(CommandShape.Segment segment, String payload, TranslationReport report) {
		String p = payload == null ? "" : payload;
		for (String marker : new String[] { "tag.", "display", "HandItems", "ArmorItems", "CustomName", "Enchantments",
				"Attributes", "ActiveEffects", "BlockEntityTag", "CustomPotionEffects" }) {
			if (p.contains(marker)) {
				report.warn("NBT 路径可能是旧版语义，无法自动迁移: " + p);
				return;
			}
		}
	}

	private static Set<String> entityKeys(CompoundTag before) {
		Set<String> keys = new LinkedHashSet<>();
		if (before.contains("HandItems") || before.contains("ArmorItems")) {
			keys.add("equipment");
		}
		if (before.contains("ActiveEffects")) {
			keys.add("active_effects");
		}
		if (before.contains("Attributes")) {
			keys.add("attributes");
		}
		return keys;
	}

	private static void applyPatches(PayloadKind kind, CompoundTag legacy, CompoundTag fixed, String id,
			TranslationReport report) {
		try {
			PatchRegistry.apply(new PatchContext(kind, legacy, fixed, id, report));
		} catch (Throwable t) {
			report.warn("补丁规则异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
		}
	}
}
