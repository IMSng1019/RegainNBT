package regainnbt.ids;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import regainnbt.core.TranslationReport;
import regainnbt.test.Bootstrap;

/**
 * ID 改名表（报告 §2.6）：命令参数里的 ID 原版 DFU 完全不处理，必须自建表。
 *
 * <p>实测结论（报告 §2.6，1.20.4 -> 26.x）：
 * <ul>
 *   <li>{@code minecraft:grass} 命令 token 与 NBT 内部都 <b>不会</b> 被 DFU 改名（26.3 里已不是合法 ID）；</li>
 *   <li>NBT 内部的 {@code scute -> turtle_scute}、{@code chain -> iron_chain} 由 DFU 自己处理。</li>
 * </ul>
 */
class IdRenamesTest extends Bootstrap {

	@BeforeAll
	static void loadTable() {
		IdRenames.load();
	}

	@Test
	@DisplayName("内置改名表非空")
	void tableIsLoaded() {
		assertTrue(IdRenames.itemTableSize() > 0, "物品改名表为空 —— regainnbt/id_renames.json 没有加载");
		assertTrue(IdRenames.blockTableSize() > 0, "方块改名表为空 —— regainnbt/id_renames.json 没有加载");
	}

	@Test
	@DisplayName("grass -> short_grass（DFU 漏掉的那个）")
	void grassRenamed() {
		assertEquals("minecraft:short_grass", IdRenames.resolveItemId("minecraft:grass", new TranslationReport("x")));
		assertEquals("minecraft:short_grass", IdRenames.resolveItemId("grass", new TranslationReport("x")),
			"没有命名空间的 token 也要能解析");
	}

	@Test
	@DisplayName("NBT 内部由原版 DFU 处理的改名（scute/chain）也要能解析到合法 ID")
	void dfuRenamedIdsStillResolve() {
		assertEquals("minecraft:turtle_scute", IdRenames.resolveItemId("scute", new TranslationReport("x")));
		assertEquals("minecraft:turtle_scute", IdRenames.resolveItemId("minecraft:scute", new TranslationReport("x")));
		assertEquals("minecraft:iron_chain", IdRenames.resolveItemId("chain", new TranslationReport("x")));
	}

	@Test
	@DisplayName("解析失败必须写诊断（不能静默返回原值）")
	void failureIsReported() {
		TranslationReport report = new TranslationReport("x");
		assertNull(IdRenames.resolveItemId("not_a_real_id_xyz", report));
		assertTrue(!report.warnings().isEmpty(), "解析失败必须写 warnings");
	}

	@Test
	@DisplayName("空串 / 非法字符 / 标签形式都返回 null")
	void invalidInputs() {
		assertNull(IdRenames.resolveItemId("", new TranslationReport("x")));
		assertNull(IdRenames.resolveItemId("Not A Valid Id", new TranslationReport("x")));
		assertNull(IdRenames.resolveItemId("#minecraft:logs", new TranslationReport("x")));
	}

	@Test
	@DisplayName("合法的新版 ID 原样返回")
	void validIdUnchanged() {
		assertEquals("minecraft:diamond_sword", IdRenames.resolveItemId("minecraft:diamond_sword", new TranslationReport("x")));
		assertEquals("minecraft:diamond_sword", IdRenames.resolveItemId("diamond_sword", new TranslationReport("x")));
		TranslationReport clean = new TranslationReport("x");
		IdRenames.resolveItemId("diamond_sword", clean);
		assertTrue(clean.warnings().isEmpty(), () -> "合法 ID 不该产生警告: " + clean.warnings());
	}

	@Test
	@DisplayName("不存在的 ID 返回 null（调用方据此报错并写诊断）")
	void unknownIdReturnsNull() {
		assertNull(IdRenames.resolveItemId("minecraft:definitely_not_an_item_xyz", new TranslationReport("x")));
	}

	@Test
	@DisplayName("方块 ID 同样可解析（stone 合法；不存在的返回 null）")
	void blockIds() {
		assertNotNull(IdRenames.resolveBlockId("minecraft:stone", new TranslationReport("x")));
		assertNull(IdRenames.resolveBlockId("minecraft:definitely_not_a_block_xyz", new TranslationReport("x")));
	}
}