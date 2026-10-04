package regainnbt.patch;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import regainnbt.core.TranslationReport;
import regainnbt.translate.PayloadKind;

/**
 * 一条补丁规则的输入：原始 1.20.4 标签 + DFU 输出（可原地修改）+ 上下文 id。
 *
 * @param kind   载荷类型
 * @param legacy 命令里的原始 1.20.4 NBT（只读）
 * @param fixed  DFU 的输出（补丁就地修改它）
 * @param id     物品/实体/方块的命名空间 id（规范化之后）
 * @param report 诊断
 */
public record PatchContext(PayloadKind kind, CompoundTag legacy, CompoundTag fixed, String id, TranslationReport report) {

	public boolean hasLegacy(String key) {
		return legacy != null && legacy.contains(key);
	}

	public Tag legacy(String key) {
		return legacy == null ? null : legacy.get(key);
	}

	/** 记录一步成功的关键过程（规则内部用）。 */
	public void step(String message) {
		if (report != null) {
			report.step(message);
		}
	}

	/** 记录一条需要管理员注意的问题（未知数字 id、无法转换的条目等）。 */
	public void warn(String message) {
		if (report != null) {
			report.warn(message);
		}
	}

	/** 命令里的原始 1.20.4 NBT；没有就是空复合标签（规则可以安全地读）。 */
	public CompoundTag legacyOrEmpty() {
		return legacy == null ? new CompoundTag() : legacy;
	}

	/** 物品规则要写进 components 子标签；实体/方块实体规则直接写 fixed 本身。 */
	public CompoundTag target() {
		if (kind == PayloadKind.ITEM) {
			return fixed.getCompound("components").orElseGet(() -> {
				CompoundTag components = new CompoundTag();
				fixed.put("components", components);
				return components;
			});
		}
		return fixed;
	}
}
