package regainnbt.core;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.commands.CommandSourceStack;

/**
 * 路由：按 0 -> 1 -> 2 -> 3 的顺序决定这条命令走新路还是旧路。
 *
 * <pre>
 *   0) 显式 old. 前缀            -> 旧路
 *   1) 类型感知的旧版意图检测命中 -> 旧路（不能等异常！）
 *   2) 新版原生解析/校验成功      -> 原样放行
 *   3) 新版解析/校验失败          -> 旧路
 * </pre>
 *
 * 注意（硬性约束）：回退只能发生在 parse 阶段，绝不能在 execute 阶段重试，否则会执行两次。
 */
public final class Router {

	public static final String MARK_PREFIX = "old.";

	public enum Stage {
		/** Commands#performPrefixedCommand 的 HEAD：还没有 parse，只能做文本级判定。 */
		PRE_PARSE,
		/** Commands#performCommand 的 HEAD：已经有 ParseResults，可以做节点驱动的判定与解析失败回退。 */
		POST_PARSE
	}

	private Router() {
	}

	/**
	 * @param command 原始命令文本（不含前导斜杠）
	 * @param source  真实的命令源（重试必须用它；26.x 的 EntityArgument.parse 在解析期就依赖 source）
	 * @param dispatcher 原版命令派发器，用于重新解析验证
	 * @param stage   调用位置
	 * @param parse   POST_PARSE 时的解析结果，PRE_PARSE 时为 null
	 */
	public static RouteResult route(String command, CommandSourceStack source,
			CommandDispatcher<CommandSourceStack> dispatcher, Stage stage, ParseResults<CommandSourceStack> parse) {
		// TODO(T5): 实现 0->1->2->3 路由
		return RouteResult.passThrough(command);
	}

	/** 执行翻译后的命令（带重入保护）。 */
	public static void executeRewritten(CommandSourceStack source, String command) {
		// TODO(T5)
	}

	public static boolean isMarked(String command) {
		return command != null && command.startsWith(MARK_PREFIX);
	}

	public static String stripMark(String command) {
		return isMarked(command) ? command.substring(MARK_PREFIX.length()) : command;
	}

	public static String addMark(String command) {
		return isMarked(command) ? command : MARK_PREFIX + command;
	}
}
