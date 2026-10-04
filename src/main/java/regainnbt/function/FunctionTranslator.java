package regainnbt.function;

import java.util.List;

/**
 * 数据包函数（datapack function）的加载期翻译。
 * 函数是只读资源，改写会破坏数据包，所以做法是「加载期逐行翻译」，不需要 old. 标记。
 */
public final class FunctionTranslator {

	private FunctionTranslator() {
	}

	/**
	 * 逐行翻译函数内容。会先按原版规则把以反斜杠结尾的续行拼成逻辑行，
	 * 翻译后再拆回单行，避免一条命令跨行时被截断。
	 *
	 * @param lines 原始行（已去掉注释与空行由调用方决定是否保留）
	 * @return 翻译后的行；没有任何改动时返回原列表
	 */
	public static List<String> translateLines(List<String> lines) {
		// TODO(T6)
		return lines;
	}
}
