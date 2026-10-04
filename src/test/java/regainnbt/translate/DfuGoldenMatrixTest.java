package regainnbt.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import regainnbt.test.Bootstrap;
import regainnbt.test.MinecraftTestHarness;

/**
 * 黄金用例（Spike6 / Spike9 的搬运）在第 1 层（原版 DFU）的真实产出。
 *
 * <p>说明：这里刻意绕过我们自己的实现，直接测原版链路，作用有两个：
 * <ol>
 *   <li>给出「原版能覆盖到什么」的可执行基线（报告 §2.13），T1 的序列化结果必须能通过同样的断言；</li>
 *   <li>T3 的第 2 层补丁规则要修的洞，正好是本类 {@code layer1Gap*} 几个用例证明的「DFU 不动」。</li>
 * </ol>
 * 期望值全部来自 26.3 实测（WORLD_VERSION=5023，Probe2 输出），不是猜测。
 */
class DfuGoldenMatrixTest extends Bootstrap {

	/** 物品：{id,Count:1b,tag:{...}} -> ITEM_STACK，断言产出的组件键集合。 */
	static Stream<Arguments> itemCases() {
		return Stream.of(
			Arguments.of("附魔 Enchantments", "minecraft:diamond_sword",
				"{Enchantments:[{id:\"minecraft:sharpness\",lvl:5}]}", List.of("minecraft:enchantments")),
			Arguments.of("名字 display.Name", "minecraft:diamond_sword",
				"{display:{Name:'{\"text\":\"Excalibur\",\"italic\":false}'}}", List.of("minecraft:custom_name")),
			Arguments.of("Lore", "minecraft:stone",
				"{display:{Lore:['{\"text\":\"old lore\"}']}}", List.of("minecraft:lore")),
			Arguments.of("不可破坏 Unbreakable", "minecraft:diamond_pickaxe",
				"{Unbreakable:1b}", List.of("minecraft:unbreakable")),
			Arguments.of("自定义模型数据 CustomModelData", "minecraft:stick",
				"{CustomModelData:7}", List.of("minecraft:custom_model_data")),
			Arguments.of("属性修饰符 AttributeModifiers", "minecraft:diamond_boots",
				"{AttributeModifiers:[{AttributeName:\"minecraft:generic.movement_speed\",Name:\"spd\",Amount:0.1d,Operation:2,UUID:[I;1,2,3,4],Slot:\"feet\"}]}",
				List.of("minecraft:attribute_modifiers")),
			Arguments.of("修复成本 RepairCost", "minecraft:shield",
				"{RepairCost:3}", List.of("minecraft:repair_cost")),
			Arguments.of("耐久 Damage", "minecraft:iron_axe",
				"{Damage:5}", List.of("minecraft:damage")),
			Arguments.of("药水 Potion", "minecraft:potion",
				"{Potion:\"minecraft:strong_strength\"}", List.of("minecraft:potion_contents")),
			Arguments.of("成书 StoredEnchantments", "minecraft:enchanted_book",
				"{StoredEnchantments:[{id:\"minecraft:mending\",lvl:1}]}", List.of("minecraft:stored_enchantments")),
			Arguments.of("容器 BlockEntityTag", "minecraft:shulker_box",
				"{BlockEntityTag:{Items:[{Slot:0b,id:\"minecraft:diamond\",Count:3b,tag:{display:{Name:'\"Gem\"'}}}]}}",
				List.of("minecraft:container")),
			Arguments.of("刷怪蛋 EntityTag", "minecraft:pig_spawn_egg",
				"{EntityTag:{id:\"minecraft:pig\",CustomName:'{\"text\":\"Piggy\"}'}}", List.of("minecraft:entity_data")),
			Arguments.of("烟花 Fireworks", "minecraft:firework_rocket",
				"{Fireworks:{Flight:2,Explosions:[{Type:1,Colors:[I;16711680],FadeColors:[I;255],Trail:1b,Flicker:1b}]}}",
				List.of("minecraft:fireworks")),
			Arguments.of("弩装填 ChargedProjectiles", "minecraft:crossbow",
				"{ChargedProjectiles:[{id:\"minecraft:arrow\",Count:1b}]}", List.of("minecraft:charged_projectiles")),
			Arguments.of("成书内容 written book", "minecraft:written_book",
				"{title:\"T\",author:\"A\",pages:['{\"text\":\"page1\"}'],resolved:1b}",
				List.of("minecraft:written_book_content")),
			Arguments.of("盔甲纹饰 Trim", "minecraft:diamond_chestplate",
				"{Trim:{material:\"minecraft:gold\",pattern:\"minecraft:vex\"}}", List.of("minecraft:trim")),
			Arguments.of("地图 map id", "minecraft:filled_map",
				"{map:1}", List.of("minecraft:map_id")),
			Arguments.of("头颅 SkullOwner", "minecraft:player_head",
				"{SkullOwner:\"Notch\"}", List.of("minecraft:profile")),
			Arguments.of("可破坏/可放置", "minecraft:diamond_pickaxe",
				"{CanDestroy:[\"minecraft:stone\"],CanPlaceOn:[\"minecraft:dirt\"]}",
				List.of("minecraft:can_break", "minecraft:can_place_on")),
			Arguments.of("未知自定义键兜底", "minecraft:paper",
				"{my_custom_flag:1,my_data:{a:1.5d}}", List.of("minecraft:custom_data")));
	}

	@ParameterizedTest(name = "物品 {0}")
	@MethodSource("itemCases")
	@DisplayName("黄金用例：物品旧 NBT -> DFU -> 预期组件键全部出现")
	void itemGolden(String label, String itemId, String inner, List<String> expectedKeys) {
		CompoundTag out = MinecraftTestHarness.itemStackFix(itemId, inner);
		TreeSet<String> keys = MinecraftTestHarness.componentKeys(out);
		assertFalse(keys.isEmpty(), () -> label + " 没有产出任何组件；输出=" + out);
		for (String key : expectedKeys) {
			assertTrue(keys.contains(key), () -> label + " 缺少组件 " + key + "；实际=" + keys + "；输出=" + out);
		}
	}

	@Test
	@DisplayName("黄金用例：名字 display.Name 的文本与 italic:false 真的还原")
	void customNameValue() {
		CompoundTag out = MinecraftTestHarness.itemStackFix("minecraft:diamond_sword",
			"{display:{Name:'{\"text\":\"Excalibur\",\"italic\":false}'}}");
		String name = out.getCompound("components").orElseThrow().getCompound("minecraft:custom_name").orElseThrow().toString();
		assertTrue(name.contains("Excalibur"), () -> "custom_name 内容不对: " + name);
		assertTrue(name.contains("italic:0b"), () -> "italic:false 没有保留: " + name);
	}

	@Test
	@DisplayName("黄金用例：Lore 的 JSON 文本组件被转成 SNBT 组件")
	void loreValue() {
		CompoundTag out = MinecraftTestHarness.itemStackFix("minecraft:stone",
			"{display:{Lore:['{\"text\":\"old lore\"}']}}");
		String lore = out.getCompound("components").orElseThrow().get("minecraft:lore").toString();
		assertTrue(lore.contains("old lore"), () -> "lore 内容不对: " + lore);
		assertFalse(lore.contains("{\\\"text\\\""), () -> "lore 仍是 JSON 字符串字面量: " + lore);
	}

	@Test
	@DisplayName("黄金用例：容器内容 Item 里的 tag 被递归转换（count + components）")
	void containerRecursion() {
		CompoundTag out = MinecraftTestHarness.itemStackFix("minecraft:shulker_box",
			"{BlockEntityTag:{Items:[{Slot:0b,id:\"minecraft:diamond\",Count:3b,tag:{display:{Name:'\"Gem\"'}}}]}}");
		String container = out.getCompound("components").orElseThrow().get("minecraft:container").toString();
		assertTrue(container.contains("count:3"), () -> "容器内物品的 count 没有转换: " + container);
		assertTrue(container.contains("minecraft:custom_name"), () -> "容器内物品的 tag 没有递归转换: " + container);
	}

	// ------------------------------------------------------------------
	// 实体
	// ------------------------------------------------------------------

	@Test
	@DisplayName("黄金用例：注入 id 前 ENTITY 修复静默 no-op（报告 §2.2 的实体版）")
	void entityWithoutIdIsNoOp() {
		CompoundTag in = MinecraftTestHarness.parseNbt(
			"{HandItems:[{id:\"minecraft:diamond_sword\",Count:1b,tag:{Enchantments:[{id:\"minecraft:sharpness\",lvl:2}]}},{}]}");
		CompoundTag out = (CompoundTag) MinecraftTestHarness.fixer
			.update(net.minecraft.util.datafix.fixes.References.ENTITY,
				new com.mojang.serialization.Dynamic<>(net.minecraft.nbt.NbtOps.INSTANCE, in),
				MinecraftTestHarness.V1_20_4, MinecraftTestHarness.currentDataVersion)
			.getValue();
		assertFalse(out.contains("equipment"), () -> "不注入 id 竟然转换了: " + out);
		assertEquals(in, out, "无 id 时必须原样返回");
	}

	@Test
	@DisplayName("黄金用例：实体装备 HandItems/ArmorItems -> equipment.mainhand / equipment.feet")
	void entityEquipment() {
		CompoundTag out = MinecraftTestHarness.entityFix("minecraft:zombie",
			"{HandItems:[{id:\"minecraft:diamond_sword\",Count:1b,tag:{Enchantments:[{id:\"minecraft:sharpness\",lvl:2}]}},{}],"
				+ "ArmorItems:[{id:\"minecraft:diamond_boots\",Count:1b,tag:{Unbreakable:1b}},{},{},{}]}");
		CompoundTag equipment = out.getCompound("equipment").orElseThrow(
			() -> new AssertionError("没有 equipment: " + out));
		assertTrue(equipment.contains("mainhand"), () -> "缺 mainhand: " + equipment);
		assertTrue(equipment.contains("feet"), () -> "缺 feet: " + equipment);
		String mainhand = equipment.getCompound("mainhand").orElseThrow().toString();
		assertTrue(mainhand.contains("minecraft:enchantments"), () -> "主手附魔没有转换: " + mainhand);
		assertTrue(equipment.getCompound("feet").orElseThrow().toString().contains("minecraft:unbreakable"),
			() -> "靴子 Unbreakable 没有转换: " + equipment);
		assertEquals("minecraft:zombie", out.getString("id").orElseThrow());
	}

	@Test
	@DisplayName("黄金用例：实体属性 Attributes -> attributes，CustomName JSON -> SNBT 复合")
	void entityAttributesAndName() {
		CompoundTag out = MinecraftTestHarness.entityFix("minecraft:pig",
			"{CustomName:'{\"text\":\"Bob\"}',Attributes:[{Name:\"minecraft:generic.max_health\",Base:20.0d}]}");
		assertTrue(out.contains("attributes"), () -> "没有 attributes: " + out);
		assertTrue(out.getList("attributes").orElseThrow().toString().contains("minecraft:max_health"),
			() -> "属性名没有改名: " + out);
		assertTrue(out.getCompound("CustomName").isPresent(), () -> "CustomName 没有变成复合标签: " + out);
		assertEquals("Bob", out.getCompound("CustomName").orElseThrow().getString("text").orElseThrow());
	}

	// ------------------------------------------------------------------
	// 方块实体
	// ------------------------------------------------------------------

	@Test
	@DisplayName("黄金用例：方块实体容器（chest）里的旧物品被递归转换")
	void blockEntityContainer() {
		CompoundTag out = MinecraftTestHarness.blockEntityFix("minecraft:chest",
			"{Items:[{Slot:0b,id:\"minecraft:diamond\",Count:3b,tag:{display:{Name:'{\"text\":\"Gem\"}'}}}]}");
		assertEquals("minecraft:chest", out.getString("id").orElseThrow());
		String items = out.getList("Items").orElseThrow().toString();
		assertTrue(items.contains("count:3"), () -> "Count 没有转成 count: " + items);
		assertTrue(items.contains("minecraft:custom_name"), () -> "物品 tag 没被递归转换: " + items);
	}

	@Test
	@DisplayName("黄金用例：告示牌文本组件 front_text（JSON -> SNBT）")
	void blockEntitySign() {
		CompoundTag out = MinecraftTestHarness.blockEntityFix("minecraft:sign",
			"{front_text:{messages:['{\"text\":\"hello\"}','{\"text\":\"\"}','{\"text\":\"\"}','{\"text\":\"\"}'],color:\"black\"}}");
		String front = out.getCompound("front_text").orElseThrow().toString();
		assertTrue(front.contains("text:\"hello\""), () -> "文本组件没有转成 SNBT: " + front);
		assertFalse(front.contains("{\\\"text\\\""), () -> "仍是 JSON 字面量: " + front);
	}
}
