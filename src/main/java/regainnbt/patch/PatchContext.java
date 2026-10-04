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
