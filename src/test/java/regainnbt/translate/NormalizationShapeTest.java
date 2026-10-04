package regainnbt.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.TreeSet;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import regainnbt.test.Bootstrap;
import regainnbt.test.MinecraftTestHarness;

/**
 * 报告 §2.2 的规范化断言：原版 DFU 的 ITEM_STACK 修复不是容错的 —— 输入形状不对时
 * <b>不报错、不转换、原样返回</b>。只有 {@code {id, Count:1b, tag:{...}}} 才会产出 components。
 *
 * <p>这条测试直接用真实 {@code DataFixers.getDataFixer()}（26.3）跑四种输入形状，
 * 是 T1「规范化器」必须满足的前置事实；不依赖 T1/T2/T4 的任何实现。
 */
class NormalizationShapeTest extends Bootstrap {

	private static final String ITEM_ID = "minecraft:diamond_sword";
	private static final String INNER = "{display:{Name:'{\"text\":\"Excalibur\"}'}}";

	@Test
	@DisplayName("形状 1/4：只把命令里的 NBT 丢给 ITEM_STACK -> 静默 no-op")
	void rawNbtOnlyIsSilentNoOp() throws Exception {
		CompoundTag raw = TagParser.parseCompoundFully(INNER);
		CompoundTag out = (CompoundTag) MinecraftTestHarness.fixer
			.update(net.minecraft.util.datafix.fixes.References.ITEM_STACK,
				new com.mojang.serialization.Dynamic<>(net.minecraft.nbt.NbtOps.INSTANCE, raw),
				MinecraftTestHarness.V1_20_4, MinecraftTestHarness.currentDataVersion)
			.getValue();
		assertFalse(out.getCompound("components").isPresent(), () -> "形状 1 竟然产出了 components: " + out);
		assertEquals(raw, out, "DFU 对不认识的形状应当原样返回");
	}

	@Test
	@DisplayName("形状 2/4：补上 id -> 仍然静默 no-op")
	void withIdOnlyIsStillNoOp() throws Exception {
		CompoundTag in = TagParser.parseCompoundFully(INNER);
		in.putString("id", ITEM_ID);
		assertNoComponents(in, "形状 2");
	}

	@Test
	@DisplayName("形状 3/4：补上 id + Count:1b -> 仍然静默 no-op")
	void withIdAndCountIsStillNoOp() throws Exception {
		CompoundTag in = TagParser.parseCompoundFully(INNER);
		in.putString("id", ITEM_ID);
		in.putByte("Count", (byte) 1);
		assertNoComponents(in, "形状 3");
	}

	@Test
	@DisplayName("形状 4/4：id + Count:1b + 把命令里的 NBT 包进 tag:{} -> components 出现")
	void withIdCountAndTagWrapperWorks() {
		CompoundTag out = MinecraftTestHarness.itemStackFix(ITEM_ID, INNER);
		CompoundTag components = out.getCompound("components").orElseThrow(
			() -> new AssertionError("形状 4 必须产出 components，实际输出=" + out));
		assertTrue(components.contains("minecraft:custom_name"), () -> "缺少 custom_name: " + components);
		assertEquals(new TreeSet<>(java.util.List.of("minecraft:custom_name")),
			new TreeSet<>(components.keySet()), () -> "输出=" + out);
		assertTrue(MinecraftTestHarness.normalizeItemToModernSyntax(ITEM_ID, INNER)
			.startsWith("minecraft:diamond_sword["), "现代语法必须以 id[ 开头");
	}

	private static void assertNoComponents(CompoundTag in, String label) {
		CompoundTag out = (CompoundTag) MinecraftTestHarness.fixer
			.update(net.minecraft.util.datafix.fixes.References.ITEM_STACK,
				new com.mojang.serialization.Dynamic<>(net.minecraft.nbt.NbtOps.INSTANCE, in),
				MinecraftTestHarness.V1_20_4, MinecraftTestHarness.currentDataVersion)
			.getValue();
		assertFalse(out.getCompound("components").isPresent(), () -> label + " 竟然产出了 components: " + out);
	}
}