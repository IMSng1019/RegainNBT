package regainnbt.translate;

import java.util.Set;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * 「这个载荷需不需要翻译」的类型感知信号判定。
 *
 * <p>分工：路由级的整条命令判定在 {@code regainnbt.detect.IntentDetector}（T2）；
 * 本类是翻译器内部对<b>单个载荷</b>的兜底判定：只有命中旧版形状才动它，
 * 从而保证「现代语法命令不被改写」（报告 2.4：不能只按键名，必须看值类型）。
 *
 * <p>1.20.4 键表来自报告 2.13 的实测死键清单 + 2.1/2.9 的物品与文本组件用例。
 */
final class PayloadSignals {

	/** 只在 1.20.4 语义下存在、或与当代同名键类型不同的键。 */
	private static final Set<String> LEGACY_KEYS = Set.of(
		// 实体
		"HandItems", "ArmorItems", "AttributeModifiers", "Attributes", "ActiveEffects", "SaddleItem", "ArmorItem",
		// 物品
		"display", "Enchantments", "StoredEnchantments", "Unbreakable", "HideFlags", "CanDestroy", "CanPlaceOn",
		"BlockEntityTag", "BlockStateTag", "EntityTag", "ChargedProjectiles", "Fireworks", "Explosion", "Trim",
		"Recipes", "pages", "resolved", "Potion", "CustomPotionEffects", "Lore",
		// 方块实体
		"Text1", "Text2", "Text3", "Text4", "Patterns", "RecordItem", "IsPlaying", "Primary", "Secondary",
		"BurnTime", "CookTime", "CookTimeTotal", "BrewTime", "Lock", "SkullOwner",
		// 通用旧键
		"CustomModelData", "Damage", "RepairCost", "map", "generation");

	private static final int MAX_DEPTH = 6;

	private PayloadSignals() {
	}

	/** @return 命中的旧版形状说明；返回 null 表示「没有旧版特征，不要动它」。 */
	static String legacyReason(CompoundTag tag) {
		if (tag == null) {
			return null;
		}
		return scan(tag, 0);
	}

	private static String scan(CompoundTag tag, int depth) {
		if (depth > MAX_DEPTH) {
			return null;
		}
		for (String key : tag.keySet()) {
			Tag value = tag.get(key);
			if (value == null) {
				continue;
			}
			if ("CustomName".equals(key)) {
				// 报告 2.4：字符串 = JSON 文本组件（旧），复合 = SNBT 组件（新）
				if (value.getId() == Tag.TAG_STRING) {
					return "CustomName 是字符串(JSON 文本组件)";
				}
				continue;
			}
			if ("messages".equals(key)) {
				if (value instanceof ListTag list && !list.isEmpty() && list.get(0).getId() == Tag.TAG_STRING) {
					return "messages 是 JSON 字符串列表(旧告示牌)";
				}
				continue;
			}
			if ("Items".equals(key) || "HandItems".equals(key) || "ArmorItems".equals(key)
					|| "ChargedProjectiles".equals(key)) {
				String r = itemListReason(value, depth);
				if (r != null) {
					return r;
				}
				// Items 两代都有（现代容器内容也是 Items），只有元素形状能判定；
				// HandItems / ArmorItems / ChargedProjectiles 只在 1.20.4 语义下存在，键名本身就够。
				if (!"Items".equals(key)) {
					return "旧键 " + key;
				}
				continue;
			}
			if (LEGACY_KEYS.contains(key)) {
				return "旧键 " + key;
			}
			String nested = nestedReason(key, value, depth);
			if (nested != null) {
				return nested;
			}
		}
		return null;
	}

	private static String itemListReason(Tag value, int depth) {
		if (!(value instanceof ListTag list)) {
			return null;
		}
		for (Tag element : list) {
			if (!(element instanceof CompoundTag item)) {
				continue;
			}
			if (!item.contains("components") && (item.contains("tag") || item.contains("Count"))) {
				return "物品元素是旧形状 {id,Count,tag}";
			}
			String r = scan(item, depth + 1);
			if (r != null) {
				return r;
			}
		}
		return null;
	}

	private static String nestedReason(String key, Tag value, int depth) {
		if (value instanceof CompoundTag c) {
			if (!c.contains("components") && c.contains("id") && (c.contains("tag") || c.contains("Count"))) {
				return "嵌套物品 " + key + " 是旧形状 {id,Count,tag}";
			}
			return scan(c, depth + 1);
		}
		if (value instanceof ListTag list) {
			for (Tag element : list) {
				if (element instanceof CompoundTag c) {
					String r = scan(c, depth + 1);
					if (r != null) {
						return r;
					}
				}
			}
		}
		return null;
	}
}
