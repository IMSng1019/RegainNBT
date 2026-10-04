package regainnbt.patch;

import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import regainnbt.translate.PayloadKind;

/**
 * 第 2 层规则：物品的 {@code CustomPotionEffects} / {@code custom_potion_effects}
 * -> {@code minecraft:potion_contents.custom_effects}。
 *
 * <p>为什么需要（报告 §2.13 实测）：1.20.4 的物品键是 {@code custom_potion_effects}，
 * DFU 的 {@code MobEffectIdFix} 注册在 schema 3568，源版本 3700 时不会被触发；
 * 而旧写法 {@code CustomPotionEffects} 会被 {@code ItemStackComponentizationFix}
 * 原样塞进 {@code minecraft:custom_data} —— 效果就此静默丢失。
 */
public final class CustomPotionEffectsRule implements PatchRule {

	public static final String ID = "custom_potion_effects";

	private static final List<String> SOURCE_KEYS = List.of("CustomPotionEffects", "custom_potion_effects");
	private static final String POTION_CONTENTS = "minecraft:potion_contents";
	private static final String CUSTOM_EFFECTS = "custom_effects";
	private static final String CUSTOM_DATA = "minecraft:custom_data";

	@Override
	public String id() {
		return ID;
	}

	@Override
	public void apply(PatchContext ctx) {
		if (ctx.kind() != PayloadKind.ITEM) {
			return;
		}
		ListTag legacyEffects = EffectTags.lookupList(ctx.legacy(), SOURCE_KEYS);
		if (legacyEffects == null || legacyEffects.isEmpty()) {
			return;
		}
		CompoundTag components = ctx.target();
		CompoundTag potionContents = components.getCompound(POTION_CONTENTS).orElse(null);
		ListTag existing = potionContents == null ? null : potionContents.getList(CUSTOM_EFFECTS).orElse(null);

		EffectTags.Merged merged = EffectTags.merge(legacyEffects, existing, ctx, "物品 " + ctx.id() + "." + CUSTOM_EFFECTS);
		if (merged.tag().isEmpty()) {
			ctx.warn("物品 " + ctx.id() + " 的 " + SOURCE_KEYS.get(0) + " 全部无法转换，未写入 " + POTION_CONTENTS);
			return;
		}
		if (!merged.patched()) {
			ctx.step("DFU 已经写好 " + merged.kept() + " 条 " + CUSTOM_EFFECTS + "，本规则不动");
			return;
		}
		if (potionContents == null) {
			potionContents = new CompoundTag();
			components.put(POTION_CONTENTS, potionContents);
		}
		potionContents.put(CUSTOM_EFFECTS, merged.tag());
		ctx.step("写入 " + POTION_CONTENTS + "." + CUSTOM_EFFECTS + "：新转换 " + merged.converted()
			+ " 条，沿用 DFU 结果 " + merged.kept() + " 条");
		removeStaleCopy(components, ctx);
	}

	/** DFU 把旧键原样搬进 custom_data 时留下的死副本，转换成功后清掉，避免同一份数据两处存在。 */
	private void removeStaleCopy(CompoundTag components, PatchContext ctx) {
		CompoundTag customData = components.getCompound(CUSTOM_DATA).orElse(null);
		if (customData == null) {
			return;
		}
		boolean removed = false;
		for (String key : SOURCE_KEYS) {
			if (customData.remove(key) != null) {
				removed = true;
			}
		}
		if (removed) {
			ctx.step("清理 " + CUSTOM_DATA + " 里的旧副本");
		}
	}
}
