package regainnbt.translate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.brigadier.ParseResults;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.commands.arguments.item.ItemPredicateArgument;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import regainnbt.core.TranslationReport;
import regainnbt.translate.LegacyTranslator.Expectation;

/**
 * 「验证后采用」的验证器（硬性约束 5）：
 * 翻译结果用<b>真实 dispatcher</b> reparse 通过之后，还要从 reparse 结果里把参数值取回来，
 * 断言关键产物（components / equipment / active_effects / 文本组件）确实出现。
 */
final class Verifier {

	private Verifier() {
	}

	static boolean verify(String translated, ParseResults<CommandSourceStack> parse,
			com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher, CommandSourceStack source,
			List<Expectation> expectations, TranslationReport report) {
		if (expectations.isEmpty()) {
			return true;
		}
		List<CommandShape.Segment> segments = CommandShape.locate(translated, parse);
		Map<String, Object> args = CommandShape.allArguments(parse);
		for (Expectation expectation : expectations) {
			CommandShape.Segment segment = find(segments, expectation);
			if (segment == null) {
				report.warn("验证失败：翻译结果里找不到 " + expectation.kind() + " 片段（offset " + expectation.start() + "）");
				return false;
			}
			String name = segment.argumentName() != null ? segment.argumentName() : expectation.argumentName();
			Object value = name == null ? null : args.get(name);
			if (!check(expectation, segment, translated, dispatcher, source, value, report)) {
				return false;
			}
		}
		return true;
	}

	private static boolean check(Expectation expectation, CommandShape.Segment segment, String translated,
			com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher, CommandSourceStack source,
			Object value, TranslationReport report) {
		switch (expectation.kind()) {
			case ITEM_PREDICATE -> {
				// 物品谓词在 26.3 只暴露 Predicate<ItemStack>，读不回物品 ID：
				// 1) 断言译文里该参数就是预期的现代 token；2) 用 A/B 断言 —— 原命令必须解析失败 +
				//    译文 reparse 通过（两者合起来证明改名/转换确有必要且有效）。
				if (!(value instanceof ItemPredicateArgument.Result)) {
					report.warn("验证失败：物品谓词参数解析成了 "
						+ (value == null ? "null" : value.getClass().getSimpleName()));
					return false;
				}
				String actualToken = segment.text(translated).trim();
				if (expectation.itemId() != null && !actualToken.equals(expectation.itemId())) {
					report.warn("验证失败：物品谓词译文与预期不符，实际 '" + actualToken + "'，预期 '"
						+ expectation.itemId() + "'");
					return false;
				}
				if (dispatcher != null && expectation.legacySnbt() != null) {
					try {
						ParseResults<CommandSourceStack> original = dispatcher.parse(expectation.legacySnbt(), source);
						if (Commands.getParseException(original) == null) {
							report.warn("验证失败：原命令在目标版本本就能解析，改名没有必要（不采用）: "
								+ expectation.legacySnbt());
							return false;
						}
					} catch (Throwable t) {
						report.warn("验证失败：原命令 A/B 解析异常: " + t);
						return false;
					}
				}
				report.step("验证 ITEM_PREDICATE：原文解析失败 + 译文= " + expectation.itemId() + " 解析通过");
				return true;
			}
			case ITEM -> {
				if (!(value instanceof ItemInput input)) {
					report.warn("验证失败：物品参数解析成了 " + value.getClass().getSimpleName());
					return false;
				}
				if (expectation.itemId() != null) {
					// 纯 ID 改名：断言 reparse 后确实是改名后的那个物品
					Identifier expectedId = Identifier.tryParse(expectation.itemId());
					Item expectedItem = expectedId != null && BuiltInRegistries.ITEM.containsKey(expectedId)
						? BuiltInRegistries.ITEM.getValue(expectedId) : null;
					if (expectedItem == null || input.item().value() != expectedItem) {
						report.warn("验证失败：期望物品 " + expectation.itemId() + "，reparse 后是 "
							+ BuiltInRegistries.ITEM.getKey(input.item().value()));
						return false;
					}
					report.step("验证 ITEM(ID)：reparse 后物品=" + expectation.itemId());
					return true;
				}
				DataComponentPatch patch = input.components();
				if (patch.isEmpty()) {
					report.warn("验证失败：物品 reparse 后 components 为空（DFU 没生效）");
					return false;
				}
				Set<String> actual = new LinkedHashSet<>();
				for (DataComponentType<?> type : patch.split().added().keySet()) {
					Identifier id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type);
					if (id != null) {
						actual.add(id.toString());
					}
				}
				for (String required : expectation.keys()) {
					if (!actual.contains(required)) {
						report.warn("验证失败：components 缺少 " + required + "（实际 " + actual + "）");
						return false;
					}
				}
				report.step("验证 ITEM：components=" + actual + "，物品=" + input.item().value());
				return true;
			}
			case ENTITY, BLOCK_ENTITY -> {
				CompoundTag tag = null;
				if (value instanceof CompoundTag compound) {
					tag = compound;
				} else {
					// 选择器 nbt= 的解析值是 EntitySelector（没有公开的取值口），
					// 但片段仍是从 reparse 出来的节点 range 上定位的 —— 直接解析该片段文本。
					String text = translated.substring(segment.start(), Math.min(segment.end(), translated.length()));
					if (text.startsWith("{")) {
						try {
							tag = net.minecraft.nbt.TagParser.parseCompoundFully(text);
						} catch (Throwable t) {
							report.warn("验证失败：选择器 nbt= 片段无法解析: " + text);
							return false;
						}
					}
				}
				if (tag == null) {
					report.warn("验证失败：" + expectation.kind() + " 取不到复合标签（参数值="
						+ (value == null ? "null" : value.getClass().getSimpleName()) + "）");
					return false;
				}
				if (expectation.legacySnbt() != null && tag.toString().equals(expectation.legacySnbt())) {
					report.warn("验证失败：reparse 结果与旧 NBT 完全相同（没有真的转换）: " + tag);
					return false;
				}
				for (String required : expectation.keys()) {
					if (!tag.contains(required)) {
						report.warn("验证失败：" + expectation.kind() + " 输出缺少关键产物 " + required + " -> " + tag);
						return false;
					}
				}
				for (String absent : expectation.absentKeys()) {
					if (tag.contains(absent)) {
						report.warn("验证失败：谓词载荷里旧键 " + absent + " 没有被删掉，nbt= 会永远不命中 -> " + tag);
						return false;
					}
				}
				report.step("验证 " + expectation.kind() + "：" + tag.keySet() + "，产物键=" + expectation.keys()
					+ (expectation.absentKeys().isEmpty() ? "" : "，已删除旧键=" + expectation.absentKeys()));
				return true;
			}
			case BLOCK_STATE -> {
				if (!(value instanceof BlockInput input)) {
					report.warn("验证失败：方块参数解析成了 " + value.getClass().getSimpleName());
					return false;
				}
				Identifier expected = Identifier.tryParse(expectation.blockId());
				Block block = expected != null && BuiltInRegistries.BLOCK.containsKey(expected)
					? BuiltInRegistries.BLOCK.getValue(expected) : null;
				if (block == null || input.getState().getBlock() != block) {
					report.warn("验证失败：方块 " + expectation.blockId() + " reparse 后是 "
						+ (input.getState().getBlock() == null ? "null" : BuiltInRegistries.BLOCK.getKey(input.getState().getBlock())));
					return false;
				}
				report.step("验证 BLOCK_STATE：方块=" + expectation.blockId() + "，reparse 后 state=" + input.getState());
				return true;
			}
			case TEXT_COMPONENT -> {
				if (!(value instanceof Component component)) {
					report.warn("验证失败：文本组件参数解析成了 " + value.getClass().getSimpleName());
					return false;
				}
				String plain = component.getString();
				if (plain.trim().startsWith("{") || plain.contains("\"text\"")) {
					report.warn("验证失败：文本组件仍是原始 JSON 字面量: " + plain);
					return false;
				}
				report.step("验证 TEXT_COMPONENT：显示文本=" + plain);
				return true;
			}
			default -> {
				return true;
			}
		}
	}

	private static CommandShape.Segment find(List<CommandShape.Segment> segments, Expectation expectation) {
		for (CommandShape.Segment segment : segments) {
			if (segment.start() == expectation.start()) {
				return segment;
			}
		}
		for (CommandShape.Segment segment : segments) {
			if (segment.kind() == expectation.kind()
					&& (expectation.argumentName() == null || expectation.argumentName().equals(segment.argumentName()))) {
				return segment;
			}
		}
		return null;
	}
}
