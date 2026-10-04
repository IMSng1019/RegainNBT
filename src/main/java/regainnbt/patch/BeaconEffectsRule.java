package regainnbt.patch;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import regainnbt.translate.PayloadKind;

/**
 * 第 2 层规则：信标的 {@code Primary} / {@code Secondary} -> {@code primary_effect} / {@code secondary_effect}。
 *
 * <p>26.3 实测（javap）：{@code BeaconBlockEntity.loadAdditional} 读 {@code primary_effect} /
 * {@code secondary_effect}，值是 <b>字符串</b>（{@code Identifier.toString()}，经
 * {@code BuiltInRegistries.MOB_EFFECT.holderByNameCodec()} 解析），并且再用
 * {@code BEACON_EFFECTS} 过滤一次；{@code Levels} 仍然照旧读。旧的 {@code Primary}/{@code Secondary}
 * 已经没有任何读取方，DFU 的 {@code MobEffectIdFix}（schema 3568）在源版本 3700 下也不会执行。
 *
 * <p>1.20.4 里 0 表示「无效果」，本规则把 0 当作不写键处理。
 */
public final class BeaconEffectsRule implements PatchRule {

	public static final String ID = "beacon_effects";

	private static final List<String> PRIMARY_KEYS = List.of("Primary", "primary_effect");
	private static final List<String> SECONDARY_KEYS = List.of("Secondary", "secondary_effect");
	private static final String MODERN_PRIMARY = "primary_effect";
	private static final String MODERN_SECONDARY = "secondary_effect";

	private static volatile Set<String> beaconEffects;

	@Override
	public String id() {
		return ID;
	}

	@Override
	public void apply(PatchContext ctx) {
		if (ctx.kind() != PayloadKind.BLOCK_ENTITY && ctx.kind() != PayloadKind.BLOCK_STATE) {
			return;
		}
		if (!isBeacon(ctx)) {
			return;
		}
		Tag primary = EffectTags.lookup(ctx.legacy(), PRIMARY_KEYS);
		Tag secondary = EffectTags.lookup(ctx.legacy(), SECONDARY_KEYS);
		if (primary == null && secondary == null) {
			return;
		}
		CompoundTag fixed = ctx.target();
		applyOne(fixed, MODERN_PRIMARY, primary, "Primary", ctx);
		applyOne(fixed, MODERN_SECONDARY, secondary, "Secondary", ctx);
	}

	/** 只认信标：id 明确是 minecraft:beacon；id 缺失时要求同时出现 Levels 与 Primary/Secondary。 */
	private static boolean isBeacon(PatchContext ctx) {
		String id = ctx.id();
		if (id != null && !id.isEmpty()) {
			String normalized = id.indexOf(':') < 0 ? "minecraft:" + id : id;
			return "minecraft:beacon".equals(normalized);
		}
		CompoundTag legacy = ctx.legacy();
		return legacy != null && legacy.contains("Levels")
			&& (legacy.contains("Primary") || legacy.contains("Secondary"));
	}

	private void applyOne(CompoundTag fixed, String modernKey, Tag legacyValue, String legacyKey, PatchContext ctx) {
		String resolved = null;
		if (legacyValue instanceof NumericTag numeric) {
			int value = numeric.intValue();
			if (value == 0) {
				if (fixed.remove(modernKey) != null) {
					ctx.step("信标 " + legacyKey + "=0（无效果），移除已存在的 " + modernKey);
				} else {
					ctx.step("信标 " + legacyKey + "=0（无效果），不写 " + modernKey);
				}
				return;
			}
			resolved = MobEffectIds.name(value);
			if (resolved == null) {
				ctx.warn("信标 " + legacyKey + "=" + value + " 是未知的数字化 mob effect id"
					+ "（表只覆盖 " + MobEffectIds.MIN_ID + ".." + MobEffectIds.MAX_ID + "）");
			} else {
				ctx.step("信标 " + legacyKey + " " + value + " -> " + resolved);
			}
		} else if (legacyValue != null) {
			String raw = EffectTags.asString(legacyValue);
			resolved = MobEffectIds.normalize(raw);
			if (resolved == null) {
				ctx.warn("信标 " + legacyKey + " 不是合法的效果 id：" + raw);
			}
		}

		String existing = MobEffectIds.normalize(EffectTags.asString(fixed.get(modernKey)));
		if (resolved == null) {
			if (existing != null) {
				ctx.step("信标 " + modernKey + " 已由 DFU 写好（" + existing + "），保持不动");
			}
			return;
		}
		if (resolved.equals(existing)) {
			ctx.step("信标 " + modernKey + " 已由 DFU 写好（" + existing + "），保持不动");
			return;
		}
		fixed.putString(modernKey, resolved);
		ctx.step("信标 " + legacyKey + " -> " + modernKey + "=\"" + resolved + "\"");
		if (!EffectTags.isRegistered(resolved)) {
			ctx.warn("信标 " + resolved + " 在 26.3 注册表里不存在，游戏会忽略它");
		} else if (!isBeaconEffect(resolved)) {
			ctx.warn("信标 " + resolved + " 不在 BEACON_EFFECTS 里，26.3 读取时会过滤掉它");
		}
	}

	/** 26.3 {@code BeaconBlockEntity.BEACON_EFFECTS} 里的合法效果；取不到就不做这个校验。 */
	private static boolean isBeaconEffect(String name) {
		Set<String> valid = beaconEffects;
		if (valid == null) {
			Set<String> collected = new HashSet<>();
			try {
				for (List<Holder<MobEffect>> row : BeaconBlockEntity.BEACON_EFFECTS) {
					for (Holder<MobEffect> holder : row) {
						holder.unwrapKey().ifPresent(key -> collected.add(key.identifier().toString()));
					}
				}
			} catch (Throwable t) {
				return true;
			}
			if (collected.isEmpty()) {
				// 注册表还没就绪（理论上不会发生）：不做肯定判断，也不缓存空集合
				return true;
			}
			beaconEffects = collected;
			valid = collected;
		}
		return valid.contains(name);
	}
}
