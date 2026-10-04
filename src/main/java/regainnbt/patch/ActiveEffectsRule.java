package regainnbt.patch;

import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import regainnbt.translate.PayloadKind;

/**
 * 第 2 层规则：实体的 {@code ActiveEffects} / {@code active_effects} -> {@code active_effects}（26.3 读取键）。
 *
 * <p>26.3 实测：{@code LivingEntity} 用 {@code MobEffectInstance.CODEC.listOf()} 读写
 * {@code active_effects}；数字化 id 的旧写法（{@code {Id:1b,...}}）在 26.3 下不再有任何读取方，
 * 而 DFU 的数字 id 修复（{@code MobEffectIdFix}，schema 3568）在源版本 3700 时不会被触发。
 */
public final class ActiveEffectsRule implements PatchRule {

	public static final String ID = "active_effects";

	private static final List<String> SOURCE_KEYS = List.of("ActiveEffects", "active_effects");
	private static final String TARGET_KEY = "active_effects";

	@Override
	public String id() {
		return ID;
	}

	@Override
	public void apply(PatchContext ctx) {
		if (ctx.kind() != PayloadKind.ENTITY) {
			return;
		}
		ListTag legacyEffects = EffectTags.lookupList(ctx.legacy(), SOURCE_KEYS);
		if (legacyEffects == null || legacyEffects.isEmpty()) {
			return;
		}
		CompoundTag fixed = ctx.target();
		ListTag existing = fixed.getList(TARGET_KEY).orElse(null);
		EffectTags.Merged merged = EffectTags.merge(legacyEffects, existing, ctx, "实体 " + ctx.id() + "." + TARGET_KEY);
		if (merged.tag().isEmpty()) {
			ctx.warn("实体 " + ctx.id() + " 的 " + SOURCE_KEYS.get(0) + " 全部无法转换，未写入 " + TARGET_KEY);
			return;
		}
		if (!merged.patched()) {
			ctx.step("DFU 已经写好 " + merged.kept() + " 条 " + TARGET_KEY + "，本规则不动");
			return;
		}
		fixed.put(TARGET_KEY, merged.tag());
		ctx.step("写入 " + TARGET_KEY + "：新转换 " + merged.converted() + " 条，沿用 DFU 结果 " + merged.kept() + " 条");
	}
}
