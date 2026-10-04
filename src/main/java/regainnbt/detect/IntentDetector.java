package regainnbt.detect;

import com.mojang.brigadier.ParseResults;

import net.minecraft.commands.CommandSourceStack;
import regainnbt.core.TranslationReport;

/**
 * 类型感知的旧版意图检测。
 *
 * 关键点（报告 2.4）：不能只按键名判断，必须看值的类型/形状。
 *   {CustomName:'{"text":"Bob"}'}  -> LEGACY（值是字符串，JSON 文本组件）
 *   {CustomName:{text:"Bob"}}      -> MODERN（值是复合标签）
 *   {HandItems:[{}]}               -> LEGACY（旧装备键）
 *   {equipment:{mainhand:{...}}}   -> MODERN（新装备键）
 *
 * 必须覆盖的参数类型（报告 2.12）：ItemArgument / CompoundTagArgument / BlockStateArgument /
 * NbtPathArgument / EntityArgument 的 nbt= / ComponentArgument。
 */
public final class IntentDetector {

	private IntentDetector() {
	}

	/** 文本级检测（PRE_PARSE 阶段，没有 ParseResults 可用）。 */
	public static IntentVerdict detect(String command, TranslationReport report) {
		// TODO(T2)
		return IntentVerdict.MODERN;
	}

	/** 节点驱动检测（POST_PARSE 阶段）：用 Brigadier 节点类型定位 NBT 片段，再按值类型判定。 */
	public static IntentVerdict detect(String command, ParseResults<CommandSourceStack> parse, TranslationReport report) {
		// TODO(T2)
		return detect(command, report);
	}
}
