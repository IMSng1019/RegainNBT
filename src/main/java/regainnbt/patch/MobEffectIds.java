package regainnbt.patch;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import net.minecraft.resources.Identifier;

/**
 * 数字化 mob effect id（{@code {Id:1b}}，1.20.2 之前的 NBT 写法）<-> 名空间 id。
 *
 * <p>表的来源与证据（26.3 实测，见 docs / 简报）：
 * <ul>
 *   <li>26.3 原版 fixer {@code net.minecraft.util.datafix.fixes.MobEffectIdFix} 的 {@code ID_MAP}
 *       （javap 常量池：{@code minecraft:speed} .. {@code minecraft:darkness}，共 33 项）；
 *   <li>该 fixer 注册在 schema <b>3568</b>（1.20.2），所以把 1.20.4（3700）当作源版本喂给 DFU 时
 *       <b>不会</b>执行它 —— 这正是第 2 层必须自己补的地方；
 *   <li>1.20.4 的 {@code StatusEffects} 字段声明顺序（javap）与 1..33 完全一致。
 * </ul>
 *
 * <p>未知数字一律由调用方 warn 并丢弃（不允许静默丢失）。
 */
public final class MobEffectIds {

	/** 表里的最小数字 id（0 = 无效果，不在此表）。 */
	public static final int MIN_ID = 1;

	/** 表里的最大数字 id（1.20.2 之后新增的效果从来没有数字 id）。 */
	public static final int MAX_ID = 33;

	private static final String[] BY_ID = {
		null,
		"minecraft:speed",
		"minecraft:slowness",
		"minecraft:haste",
		"minecraft:mining_fatigue",
		"minecraft:strength",
		"minecraft:instant_health",
		"minecraft:instant_damage",
		"minecraft:jump_boost",
		"minecraft:nausea",
		"minecraft:regeneration",
		"minecraft:resistance",
		"minecraft:fire_resistance",
		"minecraft:water_breathing",
		"minecraft:invisibility",
		"minecraft:blindness",
		"minecraft:night_vision",
		"minecraft:hunger",
		"minecraft:weakness",
		"minecraft:poison",
		"minecraft:wither",
		"minecraft:health_boost",
		"minecraft:absorption",
		"minecraft:saturation",
		"minecraft:glowing",
		"minecraft:levitation",
		"minecraft:luck",
		"minecraft:unluck",
		"minecraft:slow_falling",
		"minecraft:conduit_power",
		"minecraft:dolphins_grace",
		"minecraft:bad_omen",
		"minecraft:hero_of_the_village",
		"minecraft:darkness"
	};

	private static final Map<String, Integer> BY_NAME = new HashMap<>();

	static {
		for (int id = MIN_ID; id <= MAX_ID; id++) {
			BY_NAME.put(BY_ID[id], id);
		}
	}

	private MobEffectIds() {
	}

	/** 数字 id -> 名空间 id；不在表里返回 null。 */
	public static String name(int id) {
		return id >= MIN_ID && id <= MAX_ID ? BY_ID[id] : null;
	}

	/** 名空间 id -> 数字 id；没有数字 id 返回 -1。 */
	public static int numericId(String name) {
		String normalized = normalize(name);
		Integer id = normalized == null ? null : BY_NAME.get(normalized);
		return id == null ? -1 : id;
	}

	/**
	 * 归一化成 {@code namespace:path}（缺 namespace 补 minecraft:，大写降级重试）。
	 * 不是合法 Identifier 时返回 null。
	 */
	public static String normalize(String raw) {
		if (raw == null) {
			return null;
		}
		String trimmed = raw.trim();
		if (trimmed.isEmpty()) {
			return null;
		}
		Identifier parsed = Identifier.tryParse(trimmed);
		if (parsed == null) {
			parsed = Identifier.tryParse(trimmed.toLowerCase(Locale.ROOT));
		}
		return parsed == null ? null : parsed.toString();
	}

	/** 表里是否有这个数字 id。 */
	public static boolean hasNumeric(int id) {
		return name(id) != null;
	}
}
