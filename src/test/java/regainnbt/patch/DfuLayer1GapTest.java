package regainnbt.patch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import regainnbt.test.Bootstrap;
import regainnbt.test.MinecraftTestHarness;

/**
 * 报告 §2.13 的「真缺口」在 26.3 上的可执行证据：原版 DFU <b>不动</b>这些键，
 * 而当代代码又不再读它们 —— 这正是 T3 第 2 层补丁规则必须覆盖的全部工作量起点。
 *
 * <p>这些断言是「缺口仍然存在」的证明，不是期望行为。等 T3 的补丁规则接入后，
 * 端到端（LegacyTranslator）的输出应当不再带这些旧键；本类固定在第 1 层做对照。
 */
class DfuLayer1GapTest extends Bootstrap {

	@Test
	@DisplayName("缺口 1：物品 CustomPotionEffects 被 DFU 丢进 custom_data，没有 potion_contents.custom_effects")
	void customPotionEffectsGap() {
		CompoundTag out = MinecraftTestHarness.itemStackFix("minecraft:potion",
			"{CustomPotionEffects:[{Id:1b,Amplifier:2b,Duration:200,Ambient:0b,ShowParticles:1b}]}");
		CompoundTag components = out.getCompound("components").orElseThrow();
		assertTrue(components.contains("minecraft:custom_data"),
			() -> "DFU 行为变了：CustomPotionEffects 没有进 custom_data，请复核 T3 规则是否还需要: " + components);
		assertFalse(components.getCompound("minecraft:potion_contents")
			.map(c -> c.contains("custom_effects")).orElse(false),
			() -> "DFU 已经自己覆盖了 CustomPotionEffects？T3 规则可以删掉: " + components);
	}

	@Test
	@DisplayName("缺口 2：实体 ActiveEffects 完全没被转换（没有 active_effects）")
	void activeEffectsGap() {
		CompoundTag out = MinecraftTestHarness.entityFix("minecraft:zombie",
			"{ActiveEffects:[{Id:1b,Amplifier:1b,Duration:200,ShowParticles:1b}]}");
		assertTrue(out.contains("ActiveEffects"), () -> "旧键没有保留: " + out);
		assertFalse(out.contains("active_effects"),
			() -> "DFU 已经自己覆盖了 ActiveEffects？T3 规则可以删掉: " + out);
	}

	@Test
	@DisplayName("缺口 3：信标 Primary/Secondary 完全没被转换")
	void beaconGap() {
		CompoundTag out = MinecraftTestHarness.blockEntityFix("minecraft:beacon",
			"{Primary:1,Secondary:3,Levels:4}");
		assertTrue(out.contains("Primary") && out.contains("Secondary"), () -> "旧键没有保留: " + out);
		assertTrue(out.contains("Levels"), () -> "Levels 应当仍被读取: " + out);
	}
}
