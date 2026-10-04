package regainnbt.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import regainnbt.test.Bootstrap;

/**
 * 数据包函数的「加载期逐行翻译」纯逻辑（报告 7.2：函数是只读资源，改写会破坏数据包，所以只能加载期翻译）。
 *
 * <p>这里只测 headless 能测的部分：续行拼接、宏行/注释/空行跳过、无改动返回原实例、缺 dispatcher 时拒绝翻译。
 * 真正「翻译成现代语法」需要真实 dispatcher 做 reparse 验证（硬性约束 5），由端到端验收脚本覆盖。
 *
 * <p>续行规则必须与原版 {@code CommandFunction#fromLines} 逐字一致（T6 已用字节码复核）。
 */
class FunctionTranslatorTest extends Bootstrap {

	@Test
	@DisplayName("续行判定：末尾反斜杠才算续行")
	void concatenationPredicate() {
		assertTrue(FunctionTranslator.shouldConcatenateNextLine("probe \\"));
		assertFalse(FunctionTranslator.shouldConcatenateNextLine("probe"));
		assertFalse(FunctionTranslator.shouldConcatenateNextLine(""));
	}

	@Test
	@DisplayName("续行拼接：probe \\ + hello -> probe hello")
	void concatenatesTwoLines() {
		assertEquals(List.of("probe hello"), FunctionTranslator.logicalLines(List.of("probe \\", "hello")));
	}

	@Test
	@DisplayName("续行拼接：三段连续续行合成一条")
	void concatenatesThreeLines() {
		assertEquals(List.of("probe abc"), FunctionTranslator.logicalLines(List.of("probe a\\", "b\\", "c")));
	}

	@Test
	@DisplayName("文件末尾悬空续行：与原版一样抛 IllegalArgumentException")
	void danglingContinuationThrows() {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
			() -> FunctionTranslator.logicalLines(List.of("probe \\")));
		assertEquals("Line continuation at end of file", ex.getMessage());
	}

	@Test
	@DisplayName("没有续行时返回同一个列表实例（加载路径零拷贝）")
	void noContinuationReturnsSameInstance() {
		List<String> lines = List.of("say a", "say b");
		assertSame(lines, FunctionTranslator.logicalLines(lines));
	}

	@Test
	@DisplayName("宏行 / 注释 / 空行必须原样跳过，只有普通命令被改写")
	void skipsMacroCommentBlank() {
		List<String> lines = List.of("$give @s $(item)", "# c", "", "give x");
		List<String> out = FunctionTranslator.translateLines(lines, line -> line + " TRANSLATED");
		assertEquals(List.of("$give @s $(item)", "# c", "", "give x TRANSLATED"), out);
	}

	@Test
	@DisplayName("没有任何改动时返回同一个列表实例")
	void unchangedReturnsSameInstance() {
		List<String> lines = List.of("say a", "say b");
		assertSame(lines, FunctionTranslator.translateLines(lines, line -> line));
	}

	@Test
	@DisplayName("宏行判定：$ 开头或含 $( 占位符")
	void macroDetection() {
		assertTrue(FunctionTranslator.isMacroLine("$give @s $(item)"));
		assertTrue(FunctionTranslator.isMacroLine("give @s $(item)"));
		assertFalse(FunctionTranslator.isMacroLine("give @s minecraft:stone"));
		assertTrue(FunctionTranslator.shouldSkip(""));
		assertTrue(FunctionTranslator.shouldSkip("# comment"));
		assertTrue(FunctionTranslator.shouldSkip("/give @s minecraft:stone"));
		assertFalse(FunctionTranslator.shouldSkip("give @s minecraft:stone"));
	}

	@Test
	@DisplayName("没有 dispatcher 时拒绝翻译（硬性约束 5：验证后采用），原样返回")
	void refusesToTranslateWithoutDispatcher() {
		List<String> lines = List.of("give @s minecraft:grass", "say hi");
		assertSame(lines, FunctionTranslator.translateLines(lines),
			"缺少 dispatcher 时不能改写函数行，否则等于把未验证的命令塞进数据包");
	}
}
