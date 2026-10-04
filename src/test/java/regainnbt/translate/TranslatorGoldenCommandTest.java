package regainnbt.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.saveddata.maps.MapId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import regainnbt.core.TranslationReport;
import regainnbt.test.Bootstrap;
import regainnbt.test.MinecraftTestHarness;
import regainnbt.test.MinecraftTestHarness.Capture;

/**
 * 黄金用例整链：旧命令字符串 -> {@link LegacyTranslator#translate} -> 原版 dispatcher reparse -> 真实组件出现。
 *
 * <p>每条用例都断言三件事（架构硬性约束 5「验证后采用」）：
 * <ol>
 *   <li>translate 返回非空，且 reparse 通过；</li>
 *   <li>翻译后的命令被真实 dispatcher 执行后，捕获到的组件集合里出现预期组件（不是只看字符串）；</li>
 *   <li>报错文本里不再有旧写法残留。</li>
 * </ol>
 *
 * <p>环境限制（26.3 headless 实测，见 {@code Bootstrap} 类注释）：含附魔 / 盔甲纹饰的组件语法需要动态注册表，
 * 解析必然失败，因此这两类只在 {@link DfuGoldenMatrixTest}（DFU 层）与端到端验收脚本里覆盖。
 */
class TranslatorGoldenCommandTest extends Bootstrap {

	// ------------------------------------------------------------------
	// /give 与 /item ... with（ItemArgument）
	// ------------------------------------------------------------------

	@Test
	@DisplayName("give：名字 + 不可破坏 + 自定义模型数据 + 耐久（Spike6 P1）")
	void giveNameUnbreakableModelDamage() {
		DataComponentMap added = components(translateAndRun(
			"give Steve diamond_sword{display:{Name:'{\"text\":\"Excalibur\",\"italic\":false}'},Unbreakable:1b,CustomModelData:7,Damage:10}"));
		assertTrue(added.has(DataComponents.CUSTOM_NAME), () -> "缺少 custom_name: " + added);
		assertEquals("Excalibur", added.get(DataComponents.CUSTOM_NAME).getString());
		assertEquals(10, added.get(DataComponents.DAMAGE));
		assertTrue(added.has(DataComponents.UNBREAKABLE), "缺少 unbreakable");
		assertTrue(added.has(DataComponents.CUSTOM_MODEL_DATA), "缺少 custom_model_data");
	}

	@Test
	@DisplayName("give：未知自定义键兜底进 custom_data（Spike6 P2）")
	void giveUnknownCustomKeys() {
		DataComponentMap added = components(translateAndRun("give Steve paper{my_custom_flag:1,my_data:{a:1.5d}}"));
		assertTrue(added.has(DataComponents.CUSTOM_DATA), () -> "缺少 custom_data: " + added);
		CompoundTag tag = ((CustomData) added.get(DataComponents.CUSTOM_DATA)).copyTag();
		assertTrue(tag.contains("my_custom_flag"), () -> "custom_data 内容不对: " + tag);
		assertTrue(tag.contains("my_data"), () -> "custom_data 内容不对: " + tag);
	}

	@Test
	@DisplayName("give：描述 Lore（Spike6 P3）")
	void giveLore() {
		DataComponentMap added = components(translateAndRun("give Steve stone{display:{Lore:['{\"text\":\"old lore\"}']}}"));
		assertTrue(added.has(DataComponents.LORE), () -> "缺少 lore: " + added);
		ItemLore lore = (ItemLore) added.get(DataComponents.LORE);
		assertEquals("old lore", lore.lines().get(0).getString());
	}

	@Test
	@DisplayName("give：容器内容 BlockEntityTag -> container（Spike6 P5）")
	void giveContainer() {
		DataComponentMap added = components(translateAndRun(
			"give Steve shulker_box{BlockEntityTag:{Items:[{Slot:0b,id:\"minecraft:diamond\",Count:3b}]}}"));
		assertTrue(added.has(DataComponents.CONTAINER), () -> "缺少 container: " + added);
	}

	@Test
	@DisplayName("give：成书 title/author/pages")
	void giveWrittenBook() {
		DataComponentMap added = components(translateAndRun(
			"give Steve written_book{title:\"T\",author:\"A\",pages:['{\"text\":\"page1\"}'],resolved:1b}"));
		assertTrue(added.has(DataComponents.WRITTEN_BOOK_CONTENT), () -> "缺少 written_book_content: " + added);
	}

	@Test
	@DisplayName("give：地图 map -> map_id")
	void giveMap() {
		DataComponentMap added = components(translateAndRun("give Steve filled_map{map:1}"));
		assertTrue(added.has(DataComponents.MAP_ID), () -> "缺少 map_id: " + added);
		assertEquals(1, ((MapId) added.get(DataComponents.MAP_ID)).id());
	}

	@Test
	@DisplayName("give：烟花 Fireworks")
	void giveFireworks() {
		DataComponentMap added = components(translateAndRun(
			"give Steve firework_rocket{Fireworks:{Flight:2,Explosions:[{Type:1,Colors:[I;16711680],Trail:1b,Flicker:1b}]}}"));
		assertTrue(added.has(DataComponents.FIREWORKS), () -> "缺少 fireworks: " + added);
		assertEquals(2, ((Fireworks) added.get(DataComponents.FIREWORKS)).flightDuration());
	}

	@Test
	@DisplayName("give：属性修饰符 AttributeModifiers -> attribute_modifiers")
	void giveAttributeModifiers() {
		DataComponentMap added = components(translateAndRun(
			"give Steve diamond_boots{AttributeModifiers:[{AttributeName:\"minecraft:generic.movement_speed\",Name:\"spd\","
				+ "Amount:0.1d,Operation:2,UUID:[I;1,2,3,4],Slot:\"feet\"}]}"));
		assertTrue(added.has(DataComponents.ATTRIBUTE_MODIFIERS), () -> "缺少 attribute_modifiers: " + added);
	}

	@Test
	@DisplayName("give：药水 Potion -> potion_contents")
	void givePotion() {
		DataComponentMap added = components(translateAndRun("give Steve potion{Potion:\"minecraft:strong_strength\"}"));
		assertTrue(added.has(DataComponents.POTION_CONTENTS), () -> "缺少 potion_contents: " + added);
		assertTrue(added.get(DataComponents.POTION_CONTENTS).toString().contains("strong_strength"),
			() -> "药水内容不对: " + added.get(DataComponents.POTION_CONTENTS));
	}

	@Test
	@DisplayName("item replace block ... with：旧 NBT 物品（真实服务端用 /data get 复核）")
	void itemReplaceBlock() {
		Capture capture = translateAndRun(
			"item replace block 0 -60 0 container.0 with diamond_sword{display:{Name:'{\"text\":\"Excalibur\"}'}}");
		assertEquals(0, capture.slot(), "slot 解析结果应当是 container.0 -> 0");
		DataComponentMap added = capture.itemComponents();
		assertTrue(added.has(DataComponents.CUSTOM_NAME), () -> "缺少 custom_name: " + added);
		assertEquals("Excalibur", added.get(DataComponents.CUSTOM_NAME).getString());
	}

	// ------------------------------------------------------------------
	// /summon（CompoundTagArgument + 注入实体 id）
	// ------------------------------------------------------------------

	@Test
	@DisplayName("summon：旧 CustomName(JSON) + HandItems -> 现代 equipment.mainhand")
	void summonEquipment() {
		Capture capture = translateAndRun(
			"summon minecraft:zombie 0 -60 0 {CustomName:'{\"text\":\"Bob\"}',HandItems:[{id:\"minecraft:diamond_sword\","
				+ "Count:1b,tag:{Unbreakable:1b}},{}]}");
		CompoundTag nbt = capture.compoundTag();
		assertNotNull(nbt, "summon 没有捕获到 NBT");
		// 实体 id 由命令 token 携带（summon minecraft:zombie ...），DFU 需要注入它才能转换，
		// 但写回命令时不该再带一份 id —— 这里断言的是「转换真的发生了」，id 归命令参数管。
		assertTrue(capture.command().startsWith("summon minecraft:zombie"), () -> "实体类型被改动了: " + capture.command());
		CompoundTag equipment = nbt.getCompound("equipment").orElseThrow(
			() -> new AssertionError("没有 equipment: " + nbt));
		CompoundTag mainhand = equipment.getCompound("mainhand").orElseThrow(
			() -> new AssertionError("equipment 里没有 mainhand: " + equipment));
		assertTrue(mainhand.toString().contains("minecraft:unbreakable"), () -> "主手物品没有转换: " + mainhand);
		CompoundTag customName = nbt.getCompound("CustomName").orElseThrow(
			() -> new AssertionError("CustomName 没有转成 SNBT 复合: " + nbt));
		assertEquals("Bob", customName.getString("text").orElse(null));
	}

	@Test
	@DisplayName("summon：旧 Attributes -> 现代 attributes")
	void summonAttributes() {
		Capture capture = translateAndRun(
			"summon minecraft:pig 0 -60 0 {Attributes:[{Name:\"minecraft:generic.max_health\",Base:20.0d}]}");
		CompoundTag nbt = capture.compoundTag();
		assertNotNull(nbt, "summon 没有捕获到 NBT");
		String attributes = nbt.getList("attributes").orElseThrow(
			() -> new AssertionError("没有 attributes: " + nbt)).toString();
		assertTrue(attributes.contains("minecraft:max_health"), () -> "属性没有改名: " + attributes);
	}

	// ------------------------------------------------------------------
	// /setblock（BlockStateArgument -> 注入方块 id 走 BLOCK_ENTITY）
	// ------------------------------------------------------------------

	@Test
	@DisplayName("setblock：方块实体容器 NBT（Count -> count，物品 tag 递归转换）")
	void setblockBlockEntity() {
		Capture capture = translateAndRun(
			"setblock 0 -60 0 minecraft:chest{Items:[{Slot:0b,id:\"minecraft:diamond\",Count:3b,tag:{display:{Name:'\"Gem\"'}}}]}");
		assertEquals("minecraft:chest",
			String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(capture.blockState().getBlock())),
			"方块状态应当是 chest（翻译不应改动方块 id）");
		assertTrue(capture.command().contains("minecraft:chest{"), () -> "翻译结果没有把方块实体 NBT 挂回方块 id: " + capture.command());
		CompoundTag nbt = capture.blockNbt();
		assertNotNull(nbt, "setblock 没有捕获到方块实体 NBT（BlockInput.tag 反射失败？）");
		String items = nbt.getList("Items").orElseThrow(() -> new AssertionError("没有 Items: " + nbt)).toString();
		assertTrue(items.contains("count:3"), () -> "Count 没有转成 count: " + items);
		assertTrue(items.contains("minecraft:custom_name"), () -> "物品 tag 没有递归转换: " + items);
	}

	// ------------------------------------------------------------------
	// 文本组件
	// ------------------------------------------------------------------

	@Test
	@DisplayName("tellraw：引号包着的 JSON 文本组件 -> SNBT 组件（不再显示原始 JSON）")
	void tellrawQuotedJson() {
		Capture capture = translateAndRun("tellraw Steve '{\"text\":\"hi\"}'");
		assertNotNull(capture.component(), "tellraw 没有捕获到组件");
		assertEquals("hi", capture.component().getString(), () -> "组件内容不对: " + capture.component());
	}

	// ------------------------------------------------------------------
	// 验证后采用 / 现代命令不被改写
	// ------------------------------------------------------------------

	@Test
	@DisplayName("验证后采用：成功的翻译 report.adopted()=true 且记录了步骤")
	void adoptedAfterValidation() {
		String legacy = "give Steve stone{display:{Lore:['{\"text\":\"old lore\"}']}}";
		TranslationReport report = new TranslationReport(legacy);
		Optional<String> translated = LegacyTranslator.translate(legacy, MinecraftTestHarness.source,
			MinecraftTestHarness.dispatcher, report);
		assertTrue(translated.isPresent(), () -> "translate 返回 empty；report=" + report.summary());
		assertTrue(report.adopted(), () -> "翻译没有标记为「验证后采用」；report=" + report.summary());
		assertFalse(report.steps().isEmpty(), () -> "report 没有记录任何步骤: " + report.summary());
		MinecraftTestHarness.assertParses(translated.get());
	}

	@Test
	@DisplayName("现代语法命令：translate 不得改写（原样返回或返回 empty，都不能塞进旧版翻译）")
	void modernCommandUntouched() {
		String modern = "give Steve diamond_sword[custom_name=\"Modern\"]";
		TranslationReport report = new TranslationReport(modern);
		Optional<String> translated = LegacyTranslator.translate(modern, MinecraftTestHarness.source,
			MinecraftTestHarness.dispatcher, report);
		translated.ifPresent(out -> assertEquals(modern, out,
			() -> "现代语法命令被改写了: " + out + "；report=" + report.summary()));
	}

	// ------------------------------------------------------------------

	/** translate -> 断言非空 + reparse 通过 + 真的执行成功，返回捕获结果。 */
	private static Capture translateAndRun(String legacy) {
		TranslationReport report = new TranslationReport(legacy);
		Optional<String> translated = LegacyTranslator.translate(legacy, MinecraftTestHarness.source,
			MinecraftTestHarness.dispatcher, report);
		assertTrue(translated.isPresent(),
			() -> "LegacyTranslator.translate 返回 empty（旧命令没被翻译）: " + legacy + "\n  report=" + report.summary());
		String modern = translated.get();
		assertFalse(modern.equals(legacy), () -> "翻译结果与输入完全相同，说明没有改写: " + modern);
		MinecraftTestHarness.assertParses(modern);
		Capture capture = MinecraftTestHarness.execute(modern);
		assertTrue(capture.executed(), () -> "翻译后的命令没有真正执行到命令体: " + modern);
		return capture;
	}

	private static DataComponentMap components(Capture capture) {
		return capture.itemComponents();
	}
}
