package regainnbt.ids;

import regainnbt.core.TranslationReport;

/**
 * ID 改名表。
 *
 * 报告 2.6：DFU 只覆盖一半 —— 命令参数里的 ID 完全不处理（Unknown item），NBT 内部的 id 也漏了 grass。
 * 做法：先用 BuiltInRegistries 校验，不合法才查改名表（表只需维护原版漏掉的那部分）。
 */
public final class IdRenames {

	private IdRenames() {
	}

	/** 读取模组内置的 regainnbt/id_renames.json。 */
	public static void load() {
		// TODO(T4)
	}

	/**
	 * @param rawId 命令 token 里的物品 ID（可能没有命名空间）
	 * @return 目标版本里合法的物品 ID；无法解析时返回 null（调用方应报错并写诊断）
	 */
	public static String resolveItemId(String rawId, TranslationReport report) {
		// TODO(T4)
		return rawId;
	}

	/** @return 目标版本里合法的方块 ID；无法解析时返回 null。 */
	public static String resolveBlockId(String rawId, TranslationReport report) {
		// TODO(T4)
		return rawId;
	}

	public static int itemTableSize() {
		// TODO(T4)
		return 0;
	}

	public static int blockTableSize() {
		// TODO(T4)
		return 0;
	}
}
