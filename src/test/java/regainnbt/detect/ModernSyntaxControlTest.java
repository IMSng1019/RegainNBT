package regainnbt.detect;

import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import regainnbt.test.Bootstrap;
import regainnbt.test.MinecraftTestHarness;

/**
 * 误判回归的「事实底座」：这些现代语法命令在 26.3 的真实 dispatcher 上必须原样解析通过。
 *
 * <p>它与 {@link IntentDetectorRegressionTest}（T2）成对使用：这里证明「这些命令确实是合法的现代语法」，
 * 那里断言「意图检测不得把它们判成 LEGACY」。换句话说，本类一旦变红，说明测试用的反例本身就不合法，
 * 而不是检测器的问题。
 */
class ModernSyntaxControlTest extends Bootstrap {

	static Stream<String> modernCommands() {
		return Stream.of(
			"give Steve diamond_sword[custom_name=\"Modern\"]",
			"give Steve stone[minecraft:lore=[{text:\"line1\"}]]",
			"give Steve paper[minecraft:custom_data={my_custom_flag:1}]",
			"give Steve diamond_sword[minecraft:unbreakable={},minecraft:damage=10]",
			"give Steve filled_map[minecraft:map_id=1]",
			"give Steve shulker_box[minecraft:container=[{slot:0,item:{id:\"minecraft:diamond\",count:3}}]]",
			"give Steve written_book[minecraft:written_book_content={title:\"T\",author:\"A\",pages:[\"page1\"]}]",
			"give Steve firework_rocket[minecraft:fireworks={flight_duration:2,explosions:[{shape:\"small_ball\",colors:[16711680]}]}]",
			"give Steve potion[minecraft:potion_contents={potion:\"minecraft:strong_strength\"}]",
			"give Steve diamond_boots[minecraft:attribute_modifiers=[{type:\"minecraft:movement_speed\",amount:0.1,operation:\"add_value\",slot:\"feet\",id:\"minecraft:test\"}]]",
			"item replace block 0 -60 0 container.0 with diamond_sword[minecraft:custom_name=\"X\"]",
			"summon minecraft:zombie 0 -60 0 {NoAI:1b}",
			"summon minecraft:zombie 0 -60 0 {equipment:{mainhand:{id:\"minecraft:diamond_sword\",count:1}}}",
			"summon minecraft:zombie 0 -60 0 {attributes:[{id:\"minecraft:max_health\",base:20.0}]}",
			"summon minecraft:zombie 0 -60 0 {CustomName:{text:\"Bob\"}}",
			"setblock 0 -60 0 minecraft:chest",
			// Slot:0b 在现代方块实体物品里仍然合法（DFU 输出就是 Slot + 小写 count），所以这条不算旧版信号
			"setblock 0 -60 0 minecraft:chest{Items:[{Slot:0b,id:\"minecraft:diamond\",count:3}]}",
			"tellraw Steve {\"text\":\"hi\"}",
			"data merge block 0 -60 0 {Items:[{id:\"minecraft:diamond\",count:3}]}");
	}

	@ParameterizedTest(name = "现代语法可解析: {0}")
	@MethodSource("modernCommands")
	@DisplayName("现代语法命令必须被真实 dispatcher 原样接受")
	void parsesAsModern(String command) {
		String error = MinecraftTestHarness.parseError(command);
		assertNull(error, () -> "现代语法反例解析失败（测试数据本身有问题）: " + command + "\n  -> " + error);
	}
}
