package regainnbt.translate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import regainnbt.core.TranslationReport;

/**
 * 谓词载荷（选择器 {@code nbt=}）的旧键清理。
 *
 * <p>报告 §2.5：DFU 只新增不改写，{@code HandItems} / {@code ArmorItems} / {@code Attributes} /
 * {@code ActiveEffects} 等旧键会留在输出里 —— 对「写数据」的载荷无害（已无读取方），
 * 但 {@code nbt=} 是<b>谓词</b>：所有键都要匹配，残留的旧键会让条件<b>永远不成立</b>，
 * 旧版语义「手上有这把剑」就静默失效了。
 *
 * <p>安全规则：只有当代等价键确实存在时才删旧键 —— 否则会把「永不命中」变成「匹配所有实体」。
 */
final class PredicateCleanup {

	private static final int MAX_DEPTH = 8;

	private PredicateCleanup() {
	}

	/**
	 * 就地删除旧键；删除内容写进 report.step（/regainnbt why 可解释）。
	 *
	 * @return 实际删掉的键（供 Verifier 断言「译文里确实不再有这些键」）
	 */
	static Set<String> strip(CompoundTag tag, TranslationReport report) {
		Set<String> removed = new LinkedHashSet<>();
		stripRecursive(tag, removed, 0);
		if (!removed.isEmpty() && report != null) {
			report.step("谓词载荷：删除目标版本已无读取方的旧键 " + removed + "（否则 nbt= 永远不命中）");
		}
		return removed;
	}

	private static void stripRecursive(CompoundTag tag, Set<String> removed, int depth) {
		if (depth > MAX_DEPTH) {
			return;
		}
		// 实体级：现代等价键在，才删旧键
		if (tag.contains("equipment")) {
			remove(tag, removed, "HandItems");
			remove(tag, removed, "ArmorItems");
		}
		if (tag.contains("attributes")) {
			remove(tag, removed, "Attributes");
			remove(tag, removed, "AttributeModifiers");
		}
		if (tag.contains("active_effects")) {
			remove(tag, removed, "ActiveEffects");
		}
		// 物品级：已经转成现代形状（components / 小写 count）的条目，残留 tag / Count 会让谓词失配
		if (tag.contains("id") && (tag.contains("components") || tag.contains("count"))) {
			remove(tag, removed, "tag");
			remove(tag, removed, "Count");
		}
		for (String key : new ArrayList<>(tag.keySet())) {
			Tag value = tag.get(key);
			if (value instanceof CompoundTag compound) {
				stripRecursive(compound, removed, depth + 1);
			} else if (value instanceof ListTag list) {
				for (Tag element : list) {
					if (element instanceof CompoundTag compound) {
						stripRecursive(compound, removed, depth + 1);
					}
				}
			}
		}
	}

	private static void remove(CompoundTag tag, Set<String> removed, String key) {
		if (tag.contains(key)) {
			tag.remove(key);
			removed.add(key);
		}
	}
}
