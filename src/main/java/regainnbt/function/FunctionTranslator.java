package regainnbt.function;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.ExecutionCommandSource;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.resources.Identifier;

import regainnbt.RegainNBT;
import regainnbt.core.Decision;
import regainnbt.core.RouteResult;
import regainnbt.core.Router;

/**
 * 数据包函数（datapack function）的加载期翻译。
 *
 * <p>函数是只读资源，改写会破坏数据包，所以做法是「加载期逐行翻译」，不需要 old. 标记（报告 7.2）。
 * 只影响函数：聊天 / 命令方块 / RCON 走 {@link Router}，这里不重复处理。
 *
 * <p><b>与原版逐字对齐的两条规则</b>（26.3 {@code CommandFunction#fromLines} 字节码复核）：
 * <ol>
 *   <li>续行拼接：{@code shouldConcatenateNextLine} = 长度 &gt; 0 且最后一个字符是 {@code '\\'}；
 *       拼接时「删掉上一段末尾的反斜杠 + 追加下一段的 trim 结果」，末尾仍是反斜杠就继续；
 *       文件末尾出现续行时抛 {@code IllegalArgumentException("Line continuation at end of file")}。</li>
 *   <li>宏行：{@code fromLines} 在 {@code peek()=='$'} 时走 {@code FunctionBuilder#addMacro}，
 *       即整行是宏模板，绝不能当普通命令翻译。</li>
 * </ol>
 *
 * <p>本类不做「无 dispatcher 的翻译」：翻译必须「验证后采用」（reparse 通过才算数），
 * 没有真实 dispatcher 时只能原样返回，否则就是把未经验证的命令塞进数据包。
 */
public final class FunctionTranslator {

	/** 续行字符，与原版一致。 */
	private static final char CONTINUATION = '\\';

	/** 宏行前缀，与原版 {@code CommandFunction#fromLines} 里的 peek()=='$' 一致。 */
	private static final char MACRO_PREFIX = '$';

	/** 宏占位符；行内出现 {@code $(} 的一律不碰（宁可漏译，也不能把宏模板改坏）。 */
	private static final String MACRO_PLACEHOLDER = "$(";

	/**
	 * 「现在这次 fromLines 调用是 RegainNBT 自己发起的」标记。
	 * {@link #fromTranslatedLines} 会带着翻译后的行重新调用原版 {@code fromLines}，
	 * 那次重入必须直接放行原版实现，否则无限递归。
	 */
	private static final ThreadLocal<Boolean> INSIDE_ORIGINAL_CALL = ThreadLocal.withInitial(() -> Boolean.FALSE);

	private FunctionTranslator() {
	}

	/**
	 * 把函数文件的行列表翻译成目标版本语法（任务要求的入口）。
	 *
	 * <p>没有 dispatcher / source 时<strong>不翻译</strong>并返回原列表：
	 * 翻译结果必须用原版解析器 reparse 验证（硬性约束 5），拿不到 dispatcher 就无法验证。
	 * 真实加载路径走 {@link #translateLines(List, ExecutionCommandSource, CommandDispatcher)}。
	 *
	 * @param lines 原始行
	 * @return 翻译后的行；没有任何改动时返回<strong>同一个列表实例</strong>
	 */
	public static List<String> translateLines(List<String> lines) {
		return translateLines(lines, null, null);
	}

	/**
	 * 带上下文的翻译入口：{@code CommandFunction#fromLines} 的调用现场同时拿得到 dispatcher 与 source，
	 * 交给 {@link Router} 走和聊天 / 命令方块完全相同的 0-1-2-3 路由（不执行任何命令）。
	 *
	 * @param source     函数加载时的命令源（26.3 服务端数据包函数是 {@code CommandSourceStack}）
	 * @param dispatcher 函数加载时使用的派发器，用于翻译结果 reparse 验证
	 * @return 翻译后的行；没有任何改动时返回同一个列表实例
	 */
	public static List<String> translateLines(List<String> lines, ExecutionCommandSource<?> source,
			CommandDispatcher<?> dispatcher) {
		if (lines == null || lines.isEmpty()) {
			return lines;
		}
		if (Boolean.TRUE.equals(INSIDE_ORIGINAL_CALL.get())) {
			// 我们自己发起的原版调用：原样交给原版实现，绝不二次翻译
			return lines;
		}
		if (!RegainNBT.config().enabled || !RegainNBT.config().translateDataPackFunctions) {
			return lines;
		}
		CommandSourceStack commandSource = source instanceof CommandSourceStack stack ? stack : null;
		CommandDispatcher<CommandSourceStack> typedDispatcher = commandSource == null ? null : asCommandDispatcher(dispatcher);
		if (commandSource == null || typedDispatcher == null) {
			RegainNBT.LOGGER.debug("[RegainNBT] 函数翻译缺少可用的 CommandSourceStack，按原样加载");
			return lines;
		}
		return translateLines(lines, line -> translateLine(line, commandSource, typedDispatcher));
	}

	/**
	 * 用自定义的「逐行翻译器」跑拼接 + 替换逻辑（{@code null} 表示这一行不变）。
	 *
	 * <p>抽出来是为了能在 headless 环境验证「续行拼接 / 宏行跳过 / 无改动返回原列表」这些纯逻辑：
	 * 真实路由需要 {@code CommandSourceStack}，headless 起不来。
	 *
	 * @param lineTranslator 输入逻辑行，返回翻译后的行；返回 {@code null} 或与输入相同表示不修改
	 */
	public static List<String> translateLines(List<String> lines, UnaryOperator<String> lineTranslator) {
		if (lines == null || lines.isEmpty() || lineTranslator == null) {
			return lines;
		}
		List<String> out = null;
		for (LogicalLine logical : splitLogical(lines)) {
			String translated = shouldSkip(logical.text()) ? null : lineTranslator.apply(logical.text());
			if (translated != null && !translated.equals(logical.text())) {
				if (out == null) {
					out = new ArrayList<>(lines.size() + 4);
					out.addAll(lines.subList(0, logical.start()));
				}
				out.add(translated);
			} else if (out != null) {
				out.addAll(lines.subList(logical.start(), logical.end()));
			}
		}
		return out == null ? lines : out;
	}

	/**
	 * 只做续行拼接（与原版同规则），返回逻辑行列表，供诊断 / 验证使用。
	 *
	 * @return 逻辑行；没有任何续行时返回原列表实例
	 */
	public static List<String> logicalLines(List<String> lines) {
		if (lines == null || lines.isEmpty()) {
			return lines;
		}
		List<LogicalLine> logical = splitLogical(lines);
		for (LogicalLine line : logical) {
			if (line.end() - line.start() > 1) {
				List<String> out = new ArrayList<>(logical.size());
				for (LogicalLine l : logical) {
					out.add(l.text());
				}
				return out;
			}
		}
		return lines;
	}

	/** 与原版 {@code CommandFunction#shouldConcatenateNextLine} 完全一致的续行判定。 */
	public static boolean shouldConcatenateNextLine(CharSequence line) {
		int length = line.length();
		return length > 0 && line.charAt(length - 1) == CONTINUATION;
	}

	/** 宏行判定：整行以 {@code $} 开头（原版规则）或含宏占位符 {@code $(}。 */
	public static boolean isMacroLine(String line) {
		return (!line.isEmpty() && line.charAt(0) == MACRO_PREFIX) || line.contains(MACRO_PLACEHOLDER);
	}

	/**
	 * 这一条逻辑行是否必须原样跳过：
	 * 空行 / {@code #} 注释 / 前导斜杠（26.3 的 fromLines 认为函数行不该带 {@code /}）/ 宏行。
	 *
	 * <p>放在拼接之后、逐行翻译之前，任何调用方都绕不过去 —— 宁可漏译一行，也不能改坏宏模板。
	 */
	public static boolean shouldSkip(String logicalLine) {
		if (logicalLine.isEmpty()) {
			return true;
		}
		char first = logicalLine.charAt(0);
		return first == '#' || first == '/' || isMacroLine(logicalLine);
	}

	/**
	 * 带着翻译后的行重新调用原版 {@code CommandFunction#fromLines}。
	 *
	 * <p>方法体内对 {@code fromLines} 的调用会再次进入我们的注入点；靠
	 * {@link #INSIDE_ORIGINAL_CALL} 让那次重入直接走原版实现。
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	public static CommandFunction<?> fromTranslatedLines(Identifier id, CommandDispatcher<?> dispatcher,
			ExecutionCommandSource<?> source, List<String> lines) {
		if (Boolean.TRUE.equals(INSIDE_ORIGINAL_CALL.get())) {
			throw new IllegalStateException("RegainNBT: 原版 fromLines 调用出现意外重入");
		}
		INSIDE_ORIGINAL_CALL.set(Boolean.TRUE);
		try {
			return CommandFunction.fromLines(id, (CommandDispatcher) dispatcher, (ExecutionCommandSource) source, lines);
		} finally {
			INSIDE_ORIGINAL_CALL.set(Boolean.FALSE);
		}
	}

	// ------------------------------------------------------------------

	/**
	 * 逐逻辑行翻译（跳过规则已由 {@link #shouldSkip} 处理）；只路由，不执行任何命令。
	 *
	 * <p>走完整的 0-1-2-3：先 PRE_PARSE（显式 old. + 文本级意图检测，便宜），没命中再拿真实
	 * {@code ParseResults} 走 POST_PARSE —— 函数里一条 {@code give Steve grass} 这种「新版解析失败」
	 * 的行必须能回退旧路，否则整个数据包加载直接失败（硬性约束 1：回退仍在 parse 阶段）。
	 */
	private static String translateLine(String line, CommandSourceStack source,
			CommandDispatcher<CommandSourceStack> dispatcher) {
		try {
			RouteResult pre = Router.route(line, source, dispatcher, Router.Stage.PRE_PARSE, null);
			if (pre != null && pre.decision() != Decision.MODERN) {
				return adopted(pre, line);
			}
			ParseResults<CommandSourceStack> parse = dispatcher.parse(line, source);
			return adopted(Router.route(line, source, dispatcher, Router.Stage.POST_PARSE, parse), line);
		} catch (Throwable t) {
			// 单行翻译失败绝不能让整个数据包加载失败：保持原样，交给原版
			RegainNBT.LOGGER.warn("[RegainNBT] 函数行翻译异常，保持原样: {} ({})", line, t.toString());
			return null;
		}
	}

	/** 只有「确实翻译出了不同的命令」才替换；其余一律保持原样。 */
	private static String adopted(RouteResult result, String line) {
		if (result != null && result.shouldReexecute() && result.command() != null
				&& !result.command().equals(line)) {
			return result.command();
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private static CommandDispatcher<CommandSourceStack> asCommandDispatcher(CommandDispatcher<?> dispatcher) {
		if (dispatcher == null) {
			return null;
		}
		// 服务端数据包函数固定是 CommandFunction<CommandSourceStack>（ServerFunctionLibrary 字段类型），
		// 这里的强转在运行时恒成立。
		return (CommandDispatcher<CommandSourceStack>) dispatcher;
	}

	/** 一条逻辑行：原始行区间 [start, end) + 拼接后的文本。 */
	private record LogicalLine(int start, int end, String text) {
	}

	/**
	 * 按原版规则把物理行拼成逻辑行。抛错行为也与原版一致
	 * （{@code CommandFunction#checkCommandLineLength} 是原版的 public 静态方法，直接复用）。
	 */
	private static List<LogicalLine> splitLogical(List<String> lines) {
		List<LogicalLine> out = new ArrayList<>(lines.size());
		int index = 0;
		while (index < lines.size()) {
			int next = index + 1;
			String logical = lines.get(index).trim();
			if (shouldConcatenateNextLine(logical)) {
				StringBuilder sb = new StringBuilder(logical);
				while (true) {
					if (next >= lines.size()) {
						throw new IllegalArgumentException("Line continuation at end of file");
					}
					sb.deleteCharAt(sb.length() - 1);
					sb.append(lines.get(next).trim());
					next++;
					CommandFunction.checkCommandLineLength(sb);
					if (!shouldConcatenateNextLine(sb)) {
						break;
					}
				}
				logical = sb.toString();
			}
			CommandFunction.checkCommandLineLength(logical);
			out.add(new LogicalLine(index, next, logical));
			index = next;
		}
		return out;
	}
}
