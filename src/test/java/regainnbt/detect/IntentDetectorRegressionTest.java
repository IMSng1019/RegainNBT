package regainnbt.detect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import regainnbt.core.TranslationReport;
import regainnbt.test.Bootstrap;

/**
 * 意图检测误判回归（报告 §2.3 / §2.4 / §2.12）。
 *
 * <p>关键背景：旧写法在新版下「不报错」的参数类型占 5/6，靠异常回退救不了，所以检测器必须
 * <b>类型感知</b>（同名键看值类型），而且不能把合法的现代语法误判成旧版（误判会让现代命令走翻译链，
 * 轻则白跑一趟，重则改写用户命令）。
 *
 * <p>反例（必须 MODERN）与正例（必须 LEGACY）各 12 条以上；{@link ModernSyntaxControlTest} 用真实
 * dispatcher 证明反例确实是合法现代语法。
 */
class IntentDetectorRegressionTest extends Bootstrap {

	/** 现代语法：必须判 MODERN（误判 = 回归）。 */
	static Stream<Arguments> modernCases() {
		return Stream.of(
			Arguments.of("现代物品组件语法", "give Steve diamond_sword[custom_name=\"Modern\"]"),
			Arguments.of("现代组件 lore", "give Steve stone[minecraft:lore=[{text:\"line1\"}]]"),
			Arguments.of("现代组件 custom_data", "give Steve paper[minecraft:custom_data={my_custom_flag:1}]"),
			Arguments.of("现代组件 unbreakable+damage", "give Steve diamond_sword[minecraft:unbreakable={},minecraft:damage=10]"),
			Arguments.of("现代组件 container", "give Steve shulker_box[minecraft:container=[{slot:0,item:{id:\"minecraft:diamond\",count:3}}]]"),
			Arguments.of("现代 /item replace with", "item replace block 0 -60 0 container.0 with diamond_sword[minecraft:custom_name=\"X\"]"),
			Arguments.of("现代 summon 无 NBT", "summon minecraft:zombie 0 -60 0 {NoAI:1b}"),
			Arguments.of("现代 summon equipment", "summon minecraft:zombie 0 -60 0 {equipment:{mainhand:{id:\"minecraft:diamond_sword\",count:1}}}"),
			Arguments.of("现代 summon CustomName 复合", "summon minecraft:zombie 0 -60 0 {CustomName:{text:\"Bob\"}}"),
			Arguments.of("现代 summon attributes", "summon minecraft:zombie 0 -60 0 {attributes:[{id:\"minecraft:max_health\",base:20.0}]}"),
			Arguments.of("现代 tellraw 对象语法", "tellraw Steve {\"text\":\"hi\"}"),
			// 注意：Slot:0b 在 26.3 的方块实体物品里仍然存在（DFU 的输出就是 {Items:[{Slot:0b,...,count:3}]}），
			// 所以这里刻意不带 Slot，避免把「两代都有的键」当成旧版信号来考检测器。
			Arguments.of("现代 data merge block", "data merge block 0 -60 0 {Items:[{id:\"minecraft:diamond\",count:3}]}"),
			Arguments.of("现代 /execute run 嵌套", "execute as Steve run give Steve diamond_sword[custom_name=\"X\"]"),
			Arguments.of("现代 setblock 无 NBT", "setblock 0 -60 0 minecraft:chest"));
	}

	/** 旧写法：必须判 LEGACY（漏判 = 静默失效）。 */
	static Stream<Arguments> legacyCases() {
		return Stream.of(
			Arguments.of("旧 give display.Name", "give Steve diamond_sword{display:{Name:'{\"text\":\"Excalibur\"}'}}"),
			Arguments.of("旧 give Enchantments", "give Steve diamond_sword{Enchantments:[{id:\"minecraft:sharpness\",lvl:5}]}"),
			Arguments.of("旧 give CustomModelData", "give Steve stick{CustomModelData:7}"),
			Arguments.of("旧 item replace with", "item replace block 0 -60 0 container.0 with diamond_sword{display:{Name:'\"X\"'}}"),
			Arguments.of("旧 summon HandItems", "summon minecraft:zombie 0 -60 0 {HandItems:[{id:\"minecraft:diamond_sword\",Count:1b},{}]}"),
			Arguments.of("旧 summon ArmorItems", "summon minecraft:zombie 0 -60 0 {ArmorItems:[{},{},{},{}]}"),
			Arguments.of("旧 summon CustomName 字符串", "summon minecraft:zombie 0 -60 0 {CustomName:'{\"text\":\"Bob\"}'}"),
			Arguments.of("旧 summon Attributes 大写键", "summon minecraft:zombie 0 -60 0 {Attributes:[{Name:\"minecraft:generic.max_health\",Base:20.0d}]}"),
			Arguments.of("旧 summon ActiveEffects", "summon minecraft:zombie 0 -60 0 {ActiveEffects:[{Id:1b,Amplifier:1b,Duration:200}]}"),
			Arguments.of("旧 give CustomPotionEffects", "give Steve potion{CustomPotionEffects:[{Id:1b,Amplifier:2b,Duration:200}]}"),
			Arguments.of("旧 setblock 物品 Count:3b", "setblock 0 -60 0 minecraft:chest{Items:[{Slot:0b,id:\"minecraft:diamond\",Count:3b}]}"),
			Arguments.of("旧选择器 nbt=", "kill @e[nbt={HandItems:[{}]}]"),
			Arguments.of("旧 tellraw 引号 JSON", "tellraw Steve '{\"text\":\"hi\"}'"),
			Arguments.of("旧 data merge entity Attributes", "data merge entity @e[limit=1] {Attributes:[{Name:\"minecraft:generic.max_health\",Base:20.0d}]}"));
	}

	@ParameterizedTest(name = "MODERN 反例：{0}")
	@MethodSource("modernCases")
	@DisplayName("现代语法命令不得被判成 LEGACY")
	void modernMustStayModern(String label, String command) {
		IntentVerdict verdict = IntentDetector.detect(command, new TranslationReport(command));
		assertFalse(verdict.legacy(),
			() -> "误判：现代语法命令 " + label + " 被判成 LEGACY；reason=" + verdict.reason() + "\n  " + command);
	}

	@ParameterizedTest(name = "LEGACY 正例：{0}")
	@MethodSource("legacyCases")
	@DisplayName("旧写法必须被判成 LEGACY（不能等异常回退）")
	void legacyMustBeDetected(String label, String command) {
		IntentVerdict verdict = IntentDetector.detect(command, new TranslationReport(command));
		assertTrue(verdict.legacy(),
			() -> "漏判：旧写法 " + label + " 被判成 MODERN（会静默失效）\n  " + command);
		assertFalse(verdict.reason() == null || verdict.reason().isBlank(),
			() -> "LEGACY 结论必须给出可读 reason: " + label);
	}

	@ParameterizedTest(name = "reason 可读：{0}")
	@MethodSource("legacyCases")
	@DisplayName("LEGACY 结论的 reason 必须是给管理员看的可读文本（非空、无首尾空白）")
	void reasonIsReadable(String label, String command) {
		IntentVerdict verdict = IntentDetector.detect(command, new TranslationReport(command));
		assertTrue(verdict.legacy(), () -> "漏判，无法检查 reason: " + label);
		String reason = verdict.reason();
		assertFalse(reason == null || reason.isBlank(), () -> "reason 为空: " + label);
		assertEquals(reason.trim(), reason, () -> "reason 有首尾空白: [" + reason + "]");
		assertTrue(reason.length() >= 2, () -> "reason 太短，无法定位特征: [" + reason + "]");
	}
}
