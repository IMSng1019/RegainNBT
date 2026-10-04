package regainnbt.command;

import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import regainnbt.core.Decision;
import regainnbt.core.PathKind;
import regainnbt.core.RouteResult;
import regainnbt.core.Router;
import regainnbt.core.TranslationCache;
import regainnbt.core.TranslationReport;
import regainnbt.detect.IntentDetector;
import regainnbt.detect.IntentVerdict;

/**
 * {@code /regainnbt why|translate} 的诊断核心。
 *
 * <p><b>无副作用保证</b>：只调用 {@code dispatcher.parse}（Brigadier 解析）与 {@link Router#route}（只出结论），
 * 绝不执行任何命令 —— 这是 why / translate 与「真的跑一遍」的本质区别。
 *
 * <p>输出与报告 3.2 的路由顺序一一对应：显式 old. → 类型感知意图检测 → 新版原生解析 → 解析失败回退。
 */
public final class Diagnostics {

	private Diagnostics() {
	}

	/**
	 * 一次诊断的完整证据。字段全部来自真实调用，{@link #format()} 只负责排版。
	 */
	public record Result(
			String command,
			boolean marked,
			IntentVerdict intent,
			PathKind path,
			String modernError,
			Decision decision,
			boolean adopted,
			List<String> steps,
			List<String> warnings,
			String translated,
			Decision cached,
			boolean bothPathsFailed,
			String routeError) {

		public List<String> format() {
			List<String> out = new ArrayList<>();
			out.add("[RegainNBT] why: " + command);
			out.add("  命中路径 : " + pathLabel(path));
			out.add("  旧版线索 : 显式 old.=" + (marked ? "是" : "否")
					+ " / 意图检测=" + intentLabel(intent));
			out.add("  新版解析 : " + (modernError == null ? "通过" : "失败 —— " + modernError));
			out.add("  路由结论 : " + decisionLabel(decision) + " / adopted=" + adopted
					+ " / 缓存=" + (cached == null ? "无" : cached.name()));
			if (routeError != null) {
				out.add("  路由异常 : " + routeError);
			}
			if (!steps.isEmpty()) {
				out.add("  过程 (" + steps.size() + " 步):");
				for (int i = 0; i < steps.size(); i++) {
					out.add("    " + (i + 1) + ") " + steps.get(i));
				}
			}
			if (!warnings.isEmpty()) {
				out.add((bothPathsFailed ? "  两条路都失败的报错/警告 (" : "  警告 (") + warnings.size() + "):");
				for (String warning : warnings) {
					out.add("    - " + warning);
				}
			}
			out.add("  翻译后   : " + (translated == null ? "（无）" : translated));
			return out;
		}

		/** 只保留翻译结论的行，给 /regainnbt translate 用。 */
		public List<String> formatTranslation() {
			List<String> out = new ArrayList<>();
			out.add("[RegainNBT] translate: " + command);
			out.add("  命中路径 : " + pathLabel(path) + " / " + decisionLabel(decision)
					+ " / adopted=" + adopted);
			out.add("  翻译后   : " + (translated == null ? "（无：这条命令不需要翻译或翻译失败）" : translated));
			if (modernError != null) {
				out.add("  新版解析 : 失败 —— " + modernError);
			}
			for (String warning : warnings) {
				out.add("  警告     : " + warning);
			}
			return out;
		}
	}

	/** 跑一次完整诊断：文本级判定 + 新版解析 + 0-1-2-3 路由。不执行任何命令。 */
	public static Result explain(String rawCommand, CommandSourceStack source,
			CommandDispatcher<CommandSourceStack> dispatcher) {
		String command = normalize(rawCommand);

		boolean marked = Router.isMarked(command);

		// 1) 类型感知的意图检测（文本级），只用于报告，不参与执行
		IntentVerdict intent = IntentVerdict.MODERN;
		List<String> probeWarnings = new ArrayList<>();
		try {
			TranslationReport probeReport = new TranslationReport(command);
			IntentVerdict verdict = IntentDetector.detect(command, probeReport);
			if (verdict != null) {
				intent = verdict;
			}
			probeWarnings.addAll(probeReport.warnings());
		} catch (Throwable t) {
			probeWarnings.add("意图检测异常: " + t);
		}

		// 2) 新版原生解析（只解析，不执行）
		String modernError = null;
		ParseResults<CommandSourceStack> parse = null;
		try {
			parse = dispatcher.parse(command, source);
			var exception = Commands.getParseException(parse);
			if (exception != null) {
				modernError = exception.getMessage();
			}
		} catch (Throwable t) {
			modernError = t.toString();
		}

		// 3) 路由：先 PRE_PARSE（显式标记 + 文本级意图）；没命中再带真实 ParseResults 走 POST_PARSE（解析失败回退）
		RouteResult route = null;
		String routeError = null;
		try {
			route = Router.route(command, source, dispatcher, Router.Stage.PRE_PARSE, null);
			if (route != null && route.path() == PathKind.NONE && parse != null) {
				RouteResult post = Router.route(command, source, dispatcher, Router.Stage.POST_PARSE, parse);
				if (post != null && (post.path() != PathKind.NONE || post.decision() != Decision.MODERN)) {
					route = post;
				}
			}
		} catch (Throwable t) {
			routeError = t.toString();
		}

		TranslationReport report = route == null || route.report() == null
				? new TranslationReport(command)
				: route.report();
		PathKind path = route == null ? PathKind.NONE : route.path();
		Decision decision = route == null ? Decision.MODERN : route.decision();

		List<String> steps = new ArrayList<>(report.steps());
		List<String> warnings = new ArrayList<>(report.warnings());
		warnings.addAll(probeWarnings);

		String translated = report.translated();
		if (translated == null && route != null && route.shouldReexecute()) {
			translated = route.command();
		}
		boolean adopted = report.adopted() && translated != null;

		boolean bothPathsFailed = modernError != null
				&& (decision == Decision.TRANSLATION_FAILED || (path != PathKind.NONE && translated == null));

		Decision cached = null;
		try {
			cached = TranslationCache.instance().decisionOf(command);
		} catch (Throwable ignored) {
			// 诊断命令不能因为缓存问题失败
		}

		return new Result(command, marked, intent, path, modernError, decision, adopted,
				List.copyOf(steps), List.copyOf(warnings), translated, cached, bothPathsFailed, routeError);
	}

	/** 去掉用户可能带上的前导斜杠与首尾空白。 */
	public static String normalize(String rawCommand) {
		String command = rawCommand == null ? "" : rawCommand.trim();
		while (command.startsWith("/")) {
			command = command.substring(1).trim();
		}
		return command;
	}

	private static String pathLabel(PathKind path) {
		return switch (path) {
			case NONE -> "无（未命中旧版特征，按新版语法处理）";
			case EXPLICIT_MARK -> "EXPLICIT_MARK（显式 old. 前缀）";
			case INTENT -> "INTENT（类型感知的旧版意图检测命中）";
			case PARSE_FAILURE -> "PARSE_FAILURE（新版解析/校验失败 → 回退旧路）";
		};
	}

	private static String intentLabel(IntentVerdict intent) {
		String verdict = intent.legacy() ? "LEGACY" : "MODERN";
		return intent.reason() == null || intent.reason().isEmpty() ? verdict : verdict + "（" + intent.reason() + "）";
	}

	private static String decisionLabel(Decision decision) {
		return switch (decision) {
			case MODERN -> "MODERN（原样放行）";
			case TRANSLATED -> "TRANSLATED（已翻译，验证后采用）";
			case TRANSLATION_FAILED -> "TRANSLATION_FAILED（翻译失败，回落新版报错）";
		};
	}
}
