package regainnbt.patch;

import java.util.List;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;

/**
 * 旧 / 新 mob effect 实例 NBT 的读取与转换（物品 CustomPotionEffects 与实体 ActiveEffects 共用）。
 *
 * <p>现代形状（26.3 {@code MobEffectInstance.CODEC} + {@code MobEffectInstance$Details.MAP_CODEC} 实测）：
 * <pre>{id:"minecraft:speed", amplifier:1, duration:200, ambient:0b,
 *  show_particles:1b, show_icon:1b, hidden_effect:{...}}</pre>
 * 其中 {@code id} 必填（holderByNameCodec，字符串），{@code amplifier}/{@code duration}/{@code ambient}
 * 缺省 0/false，{@code show_particles} 缺省 true，{@code show_icon}/{@code hidden_effect} 可选。
 *
 * <p>旧形状（1.20.2 之前，数字 id）：
 * <pre>{Id:1b, Amplifier:1b, Duration:200, Ambient:0b, ShowParticles:1b, ShowIcon:1b, HiddenEffect:{...}}</pre>
 *
 * <p>注意：1.20.4 本身写的就是现代形状（{@code active_effects} / {@code custom_potion_effects} +
 * 字符串 id，已用原版 1.20.4 服务端 jar 的字节码确认），所以本类同时承担「数字化转换」与
 * 「形状修复」两个职责：已经现代、且不含旧字段的条目原样保留。
 */
final class EffectTags {

	static final String ID = "id";
	static final String AMPLIFIER = "amplifier";
	static final String DURATION = "duration";
	static final String AMBIENT = "ambient";
	static final String SHOW_PARTICLES = "show_particles";
	static final String SHOW_ICON = "show_icon";
	static final String HIDDEN_EFFECT = "hidden_effect";

	private static final String LEGACY_ID = "Id";
	private static final String LEGACY_AMPLIFIER = "Amplifier";
	private static final String LEGACY_DURATION = "Duration";
	private static final String LEGACY_AMBIENT = "Ambient";
	private static final String LEGACY_SHOW_PARTICLES = "ShowParticles";
	private static final String LEGACY_SHOW_ICON = "ShowIcon";
	private static final String LEGACY_HIDDEN_EFFECT = "HiddenEffect";

	private static final List<String> LEGACY_KEYS = List.of(
		LEGACY_ID, LEGACY_AMPLIFIER, LEGACY_DURATION, LEGACY_AMBIENT,
		LEGACY_SHOW_PARTICLES, LEGACY_SHOW_ICON, LEGACY_HIDDEN_EFFECT);

	/** 1.20.4 里存在、26.3 已经删掉的字段（转换时丢弃并记一笔）。 */
	private static final List<String> DROPPED_KEYS = List.of("factor_calculation_data", "factorCalculationData");

	private static final int MAX_DEPTH = 8;

	private EffectTags() {
	}

	/** 按顺序取第一个存在的键；顶层没有时回退到物品包装的 {@code tag} 子标签。 */
	static Tag lookup(CompoundTag root, List<String> keys) {
		Tag found = lookupDirect(root, keys);
		if (found != null) {
			return found;
		}
		if (root != null) {
			CompoundTag wrapped = root.getCompound("tag").orElse(null);
			if (wrapped != null) {
				return lookupDirect(wrapped, keys);
			}
		}
		return null;
	}

	private static Tag lookupDirect(CompoundTag root, List<String> keys) {
		if (root == null) {
			return null;
		}
		for (String key : keys) {
			Tag value = root.get(key);
			if (value != null) {
				return value;
			}
		}
		return null;
	}

	/** 按顺序取第一个存在的列表键；不是列表返回 null。 */
	static ListTag lookupList(CompoundTag root, List<String> keys) {
		Tag value = lookup(root, keys);
		return value instanceof ListTag list ? list : null;
	}

	/** 已经是现代形状（字符串 id + 没有旧字段）时返回 true —— 这种条目由 DFU 产出，原样保留。 */
	static boolean isModern(Tag tag) {
		if (!(tag instanceof CompoundTag compound)) {
			return false;
		}
		String id = compound.getString(ID).orElse(null);
		if (id == null || !isRegistered(normalize(id))) {
			return false;
		}
		for (String legacyKey : LEGACY_KEYS) {
			if (compound.contains(legacyKey)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * 把一条效果实例转成 26.3 形状；转换不了（未知数字 id / 字段类型不对）时记录 warn 并返回 null。
	 */
	static CompoundTag toModern(CompoundTag legacy, PatchContext ctx, String where, int depth) {
		String id = effectId(legacy, ctx, where);
		if (id == null) {
			return null;
		}
		CompoundTag out = new CompoundTag();
		out.putString(ID, id);

		Tag amplifier = lookupDirect(legacy, List.of(AMPLIFIER, LEGACY_AMPLIFIER));
		if (amplifier != null) {
			Integer value = unsignedAmplifier(amplifier, ctx, where);
			if (value != null) {
				out.putInt(AMPLIFIER, value);
			}
		}
		Tag duration = lookupDirect(legacy, List.of(DURATION, LEGACY_DURATION));
		if (duration instanceof NumericTag numericDuration) {
			out.putInt(DURATION, numericDuration.intValue());
		}
		copyBoolean(legacy, out, List.of(AMBIENT, LEGACY_AMBIENT), AMBIENT);
		copyBoolean(legacy, out, List.of(SHOW_PARTICLES, LEGACY_SHOW_PARTICLES), SHOW_PARTICLES);
		copyBoolean(legacy, out, List.of(SHOW_ICON, LEGACY_SHOW_ICON), SHOW_ICON);

		Tag hidden = lookupDirect(legacy, List.of(HIDDEN_EFFECT, LEGACY_HIDDEN_EFFECT));
		if (hidden instanceof CompoundTag hiddenCompound) {
			if (depth >= MAX_DEPTH) {
				ctx.warn(where + ".hidden_effect 嵌套超过 " + MAX_DEPTH + " 层，已丢弃");
			} else {
				CompoundTag modernHidden = toModern(hiddenCompound, ctx, where + ".hidden_effect", depth + 1);
				if (modernHidden != null) {
					out.put(HIDDEN_EFFECT, modernHidden);
				}
			}
		}
		for (String dropped : DROPPED_KEYS) {
			if (legacy.contains(dropped)) {
				ctx.step(where + " 丢弃 " + dropped + "（26.3 的 MobEffectInstance 已无此字段）");
			}
		}
		return out;
	}

	/** merge 的结果：目标列表 + 本规则真正转换的条数 + 沿用 DFU 结果的条数。 */
	record Merged(ListTag tag, int converted, int kept) {

		/** 是否有条目是本规则转换出来的（false = DFU 已经处理好了）。 */
		boolean patched() {
			return converted > 0;
		}
	}

	/**
	 * 把旧列表合并成目标列表：已经现代的前缀（DFU 转换结果）原样保留，其余按索引用旧列表转换补上。
	 * 转换不了的条目记录 warn 并跳过。
	 */
	static Merged merge(ListTag legacyList, ListTag existing, PatchContext ctx, String where) {
		int legacySize = legacyList == null ? 0 : legacyList.size();
		int existingSize = existing == null ? 0 : existing.size();
		int count = Math.max(legacySize, existingSize);
		ListTag out = new ListTag();
		int converted = 0;
		int kept = 0;
		for (int i = 0; i < count; i++) {
			Tag current = i < existingSize ? existing.get(i) : null;
			if (isModern(current)) {
				out.add(current);
				kept++;
				continue;
			}
			Tag source = i < legacySize ? legacyList.get(i) : current;
			if (!(source instanceof CompoundTag legacy)) {
				ctx.warn(where + "[" + i + "] 不是复合标签，已跳过：" + source);
				continue;
			}
			CompoundTag modern = toModern(legacy, ctx, where + "[" + i + "]", 0);
			if (modern != null) {
				out.add(modern);
				converted++;
			}
		}
		return new Merged(out, converted, kept);
	}

	/** 效果名是否在 26.3 的 mob effect 注册表里。 */
	static boolean isRegistered(String name) {
		if (name == null) {
			return false;
		}
		Identifier identifier = Identifier.tryParse(name);
		if (identifier == null) {
			return false;
		}
		try {
			return BuiltInRegistries.MOB_EFFECT.containsKey(identifier);
		} catch (Throwable t) {
			// 注册表不可用（极端测试环境）时不做否定判断
			return true;
		}
	}

	/** NBT 字符串标签 -> String；其它类型返回 null。 */
	static String asString(Tag tag) {
		return tag instanceof StringTag stringTag ? stringTag.value() : null;
	}

	/** 归一化成 namespace:path；非法返回 null。 */
	static String normalize(String raw) {
		return MobEffectIds.normalize(raw);
	}

	private static String effectId(CompoundTag legacy, PatchContext ctx, String where) {
		Tag legacyId = legacy.get(LEGACY_ID);
		if (legacyId instanceof NumericTag numeric) {
			return fromNumeric(numeric.intValue(), ctx, where);
		}
		Tag modernId = legacy.get(ID);
		if (modernId instanceof NumericTag numeric) {
			return fromNumeric(numeric.intValue(), ctx, where);
		}
		String raw = asString(modernId);
		if (raw == null) {
			raw = asString(legacyId);
		}
		if (raw == null) {
			ctx.warn(where + " 缺少可用的 id/Id，已跳过该效果");
			return null;
		}
		String name = MobEffectIds.normalize(raw);
		if (name == null) {
			ctx.warn(where + " 的 effect id 不是合法标识符：" + raw + "，已跳过该效果");
			return null;
		}
		if (!isRegistered(name)) {
			ctx.warn(where + " 的 mob effect " + name + " 在 26.3 注册表里不存在，已跳过该效果");
			return null;
		}
		return name;
	}

	private static String fromNumeric(int value, PatchContext ctx, String where) {
		String name = MobEffectIds.name(value);
		if (name == null) {
			ctx.warn(where + " 是未知的数字化 mob effect id=" + value
				+ "（表只覆盖 " + MobEffectIds.MIN_ID + ".." + MobEffectIds.MAX_ID + "），已跳过该效果");
			return null;
		}
		ctx.step(where + " 数字化 id " + value + " -> " + name);
		return name;
	}

	/** amplifier 在现代 codec 里是 UNSIGNED_BYTE，旧字节按无符号解释；越界就夹紧并 warn。 */
	private static Integer unsignedAmplifier(Tag tag, PatchContext ctx, String where) {
		if (!(tag instanceof NumericTag numeric)) {
			ctx.warn(where + ".amplifier 不是数字，已忽略：" + tag);
			return null;
		}
		int value = tag.getId() == Tag.TAG_BYTE ? numeric.byteValue() & 0xFF : numeric.intValue();
		if (value < 0 || value > 255) {
			int clamped = Math.max(0, Math.min(255, value));
			ctx.warn(where + ".amplifier=" + value + " 超出 UNSIGNED_BYTE 范围，夹紧为 " + clamped);
			return clamped;
		}
		return value;
	}

	private static void copyBoolean(CompoundTag from, CompoundTag to, List<String> keys, String targetKey) {
		Tag value = lookupDirect(from, keys);
		if (value instanceof NumericTag numeric) {
			to.putBoolean(targetKey, numeric.intValue() != 0);
		}
	}
}
