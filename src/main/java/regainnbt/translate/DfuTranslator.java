package regainnbt.translate;

import net.minecraft.nbt.CompoundTag;
import regainnbt.core.TranslationReport;

/**
 * 原版 DataFixerUpper 接线层（不要自己写组件映射表）。
 * 实测（报告 2.2）：输入形状不对会「静默原样返回」，所以规范化 + 结果断言是必须的。
 */
public final class DfuTranslator {

	/** 1.20.4 的数据版本。 */
	public static final int SOURCE_DATA_VERSION = 3700;

	private DfuTranslator() {
	}

	/** 目标版本数据版本（SharedConstants.WORLD_VERSION）。 */
	public static int targetDataVersion() {
		// TODO(T1)
		return 0;
	}

	/** {@code {id, Count, tag:{...}}} -> components。 */
	public static CompoundTag fixItem(CompoundTag legacyStack, TranslationReport report) {
		// TODO(T1)
		return legacyStack;
	}

	/** 注入 id 的实体标签 -> equipment / active_effects 等。 */
	public static CompoundTag fixEntity(CompoundTag legacyEntity, TranslationReport report) {
		// TODO(T1)
		return legacyEntity;
	}

	/** 注入 id 的方块实体标签。 */
	public static CompoundTag fixBlockEntity(CompoundTag legacyBlockEntity, TranslationReport report) {
		// TODO(T1)
		return legacyBlockEntity;
	}

	/** 用 DFU 的 ITEM_NAME 引用改名；返回 null 表示没有改名（原样）。 */
	public static String fixItemName(String id) {
		// TODO(T1)
		return null;
	}

	/** 用 DFU 的 BLOCK_NAME 引用改名；返回 null 表示没有改名（原样）。 */
	public static String fixBlockName(String id) {
		// TODO(T1)
		return null;
	}
}
