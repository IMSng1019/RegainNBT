package regainnbt.core;

import java.util.Optional;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;

import regainnbt.RegainNBT;
import regainnbt.RegainNBTConfig;
import regainnbt.detect.IntentDetector;
import regainnbt.detect.IntentVerdict;
import regainnbt.translate.LegacyTranslator;

/**
 * 路由：按 0 -> 1 -> 2 -> 3 的顺序决定这条命令走新路还是旧路。
 *
 * <pre>
 *   0) 显式 old. 前缀            -> 旧路
 *   1) 类型感知的旧版意图检测命中 -> 旧路（不能等异常！报告 2.12：6 类参数里 5 类旧写法不报错）
 *   2) 新版原生解析/校验成功      -> 原样放行
 *   3) 新版解析/校验失败          -> 旧路
 * </pre>
 *
 * 注意（硬性约束 1）：回退只能发生在 parse 阶段，绝不能在 execute 阶段重试，否则会执行两次。
 * POST_PARSE 注入点在 {@code Commands#performCommand} 的 HEAD，原版的校验（finishParsing -> validateParseResults）
 * 还没跑、更没有任何副作用，所以这里取消并重新执行是安全的。
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
	 * @param command 原始命令文本（可带前导斜杠；mixin 注入在 HEAD，原版还没做 trimOptionalPrefix）
	 * @param source  真实的命令源（重试必须用它；26.x 的 EntityArgument.parse 在解析期就依赖 source）
	 * @param dispatcher 原版命令派发器，用于重新解析验证
	 * @param stage   调用位置
	 * @param parse   POST_PARSE 时的解析结果，PRE_PARSE 时为 null
	 */
	public static RouteResult route(String command, CommandSourceStack source,
			CommandDispatcher<CommandSourceStack> dispatcher, Stage stage, ParseResults<CommandSourceStack> parse) {
		if (command == null || command.isEmpty()) {
			return RouteResult.passThrough(command);
		}

		// 归一化成原版语义：Commands#performPrefixedCommand 内部会调 trimOptionalPrefix 去掉一个前导 '/'，
		// 但我们的注入点在 HEAD，拿到的是没去过的原文。命令方块里存 "/give ..." 时若不先归一化，
		// 0) 的 old. 判定与 1) 的意图检测都会漏。缓存键同样用归一化后的文本。
		String text = normalize(command);
		TranslationCache cache = TranslationCache.instance();

		// ── 缓存命中：直接返回，不再判定（同一文本的 PRE/POST 结论一致；聊天/RCON 靠它，命令方块 20Hz 也靠它）
		RouteResult cached = cache.get(text);
		if (cached != null) {
			RegainNBT.LOGGER.debug("[RegainNBT] 路由缓存命中: {} -> {}", text, cached.decision());
			return cached;
		}

		RegainNBTConfig config = RegainNBT.config();
		if (isCommandDisabled(config, text)) {
			// 管理员点名不接管的指令根名：完全交给原版，也不写缓存（改配置立即生效）
			return RouteResult.passThrough(text);
		}

		TranslationReport report = new TranslationReport(text);

		// ── 0) 显式 old. 前缀 -> 旧路
		if (isMarked(text)) {
			report.path(PathKind.EXPLICIT_MARK);
			report.step("显式 old. 前缀");
			return legacy(text, stripMark(text), source, dispatcher, report, cache);
		}

		// ── 1) 文本级意图检测 -> 旧路
		//     报告 2.12：/summon、/setblock、nbt=、/tellraw 的旧写法在新版下解析成功然后静默失效，
		//     「等异常再回退」只能救 /give 这一种，所以意图检测必须先跑、不能等异常。
		IntentVerdict textVerdict = detectText(text, report);
		if (textVerdict.legacy()) {
			report.path(PathKind.INTENT);
			report.step("文本级意图检测命中: " + textVerdict.reason());
			return legacy(text, text, source, dispatcher, report, cache);
		}

		// ── 2) PRE_PARSE 阶段没有更多信息：原样放行给原版
		//     ★不写缓存：解析失败的回退只能在 POST_PARSE 判定，这里缓存 MODERN 会把 /give 的旧 NBT 放过去。
		if (stage != Stage.POST_PARSE || parse == null) {
			report.step("PRE_PARSE 未命中旧版特征，原样放行");
			return RouteResult.passThrough(text, PathKind.NONE, report);
		}

		// ── 3) POST_PARSE：新版解析/校验是否可用（失败则回退旧路）
		String modernError = null;
		boolean modernUsable;
		try {
			Commands.validateParseResults(parse);
			modernUsable = true;
		} catch (CommandSyntaxException e) {
			modernUsable = false;
			modernError = e.getMessage() != null ? e.getMessage() : e.toString();
		} catch (Throwable t) {
			// 校验本身出问题：按「新版不可用」处理，交给旧路；绝不在这里执行
			modernUsable = false;
			modernError = t.toString();
		}

		if (!modernUsable) {
			report.path(PathKind.PARSE_FAILURE);
			report.step("新版解析/校验失败，回退旧路");
			report.warn("新版错误: " + modernError);
			return legacy(text, text, source, dispatcher, report, cache);
		}

		// ── 4) 新版可用，再做节点驱动检测（Brigadier 节点类型定位 NBT 片段，文本级看不出参数类型的场景靠它）
		IntentVerdict nodeVerdict = detectNodes(text, parse, report);
		if (nodeVerdict.legacy()) {
			report.path(PathKind.INTENT);
			report.step("节点驱动意图检测命中: " + nodeVerdict.reason());
			return legacy(text, text, source, dispatcher, report, cache);
		}

		// ── 5) 确认是现代语法：这是这条文本的最终结论，可以缓存
		RouteResult result = RouteResult.passThrough(text, PathKind.NONE, report);
		cache.put(text, result);
		RegainNBT.LOGGER.debug("[RegainNBT] 现代语法，原样放行: {}", text);
		return result;
	}

	/**
	 * 执行翻译后的命令（带重入保护）。
	 *
	 * mixin 在调用 {@link #route} 之前已经 {@link RecursionGuard#enter()} 过，所以这里正常情况下 enter() 返回 false，
	 * 说明「本次 performPrefixedCommand 不会再触发路由」；若本方法被其它入口（数据包函数翻译等）直接调用，
	 * 则由本方法自己持有守卫，效果相同。
	 */
	public static void executeRewritten(CommandSourceStack source, String command) {
		if (source == null || command == null) {
			return;
		}
		String text = normalize(command);
		if (text == null || text.isEmpty()) {
			return;
		}

		boolean ownsGuard = RecursionGuard.enter();
		try {
			MinecraftServer server = source.getServer();
			if (server == null) {
				RegainNBT.LOGGER.warn("[RegainNBT] 取不到服务器，翻译后的命令未执行: {}", text);
				return;
			}
			Commands commands = server.getCommands();
			if (commands == null) {
				RegainNBT.LOGGER.warn("[RegainNBT] 取不到 Commands，翻译后的命令未执行: {}", text);
				return;
			}
			// 关键：本次调用期间 RecursionGuard.depth() >= 1，mixin 的 HEAD 判定会直接放行原版，
			// 不会对翻译结果再路由一次（否则可能无限递归 / 二次翻译）。
			commands.performPrefixedCommand(source, text);
		} finally {
			if (ownsGuard) {
				RecursionGuard.exit();
			}
		}
	}

	/** 旧路：LegacyTranslator 成功 -> TRANSLATED（+缓存）；失败 -> TRANSLATION_FAILED（不取消，让原版报它自己的错）。 */
	private static RouteResult legacy(String cacheKey, String legacyCommand, CommandSourceStack source,
			CommandDispatcher<CommandSourceStack> dispatcher, TranslationReport report, TranslationCache cache) {
		Optional<String> translated = Optional.empty();
		try {
			translated = LegacyTranslator.translate(legacyCommand, source, dispatcher, report);
		} catch (Throwable t) {
			report.warn("旧路翻译异常: " + t);
		}

		String result = translated == null ? null : translated.orElse(null);
		if (result != null && !result.isBlank() && !isMarked(result)) {
			report.translated(result);
			RouteResult outcome = new RouteResult(Decision.TRANSLATED, report.path(), result, report);
			// 键 = 原始命令文本（带 old. 前缀的命令是另一条键，单独缓存）
			cache.put(cacheKey, outcome);
			if (RegainNBT.config().logTranslations) {
				RegainNBT.LOGGER.info("[RegainNBT] {} 旧路翻译成功: {} -> {}", report.path(), legacyCommand, result);
			}
			return outcome;
		}

		if (result != null && isMarked(result)) {
			report.warn("旧路产出的命令仍带 old. 前缀，拒绝采用: " + result);
		}
		// 翻译失败：返回 TRANSLATION_FAILED 且 command=null —— mixin 不会 cancel，原版照常报它自己的错误。
		// 旧路细节只进日志与 /regainnbt why（report），不覆盖新版错误。
		RouteResult outcome = new RouteResult(Decision.TRANSLATION_FAILED, report.path(), null, report);
		// 负缓存：命令方块是 20Hz 的，一条翻不出来的旧命令不能每 tick 重试一次翻译。
		// 只影响同一条文本的重复开销，不影响正确性（原版照样报自己的错误）。
		cache.put(cacheKey, outcome);
		if (RegainNBT.config().logFailures) {
			RegainNBT.LOGGER.warn("[RegainNBT] {} 旧路翻译失败，回退原版错误: {} | {}", report.path(), legacyCommand,
				report.summary());
		}
		return outcome;
	}

	private static IntentVerdict detectText(String command, TranslationReport report) {
		try {
			IntentVerdict verdict = IntentDetector.detect(command, report);
			return verdict == null ? IntentVerdict.MODERN : verdict;
		} catch (Throwable t) {
			report.warn("文本级意图检测异常，按现代语法处理: " + t);
			return IntentVerdict.MODERN;
		}
	}

	private static IntentVerdict detectNodes(String command, ParseResults<CommandSourceStack> parse,
			TranslationReport report) {
		try {
			IntentVerdict verdict = IntentDetector.detect(command, parse, report);
			return verdict == null ? IntentVerdict.MODERN : verdict;
		} catch (Throwable t) {
			report.warn("节点驱动意图检测异常，按现代语法处理: " + t);
			return IntentVerdict.MODERN;
		}
	}

	private static boolean isCommandDisabled(RegainNBTConfig config, String command) {
		String root = rootOf(command);
		if (root.isEmpty()) {
			return false;
		}
		// 内建豁免：本模组自己的管理指令永不参与路由。
		// 否则 /regainnbt why <旧命令> 这条外层命令会先被翻译（参数里的旧 NBT 会命中意图检测），
		// 于是 why 拿到的是改写后的文本、诊断出 MODERN —— 独立验证者实测到过这个现象。
		if (RegainNBT.MOD_ID.equals(root)) {
			return true;
		}
		if (config.disabledCommands == null || config.disabledCommands.isEmpty()) {
			return false;
		}
		if (config.isCommandDisabled(root)) {
			return true;
		}
		// 允许写 "minecraft:give" 或 "give" 两种形态
		if (root.startsWith("minecraft:")) {
			return config.isCommandDisabled(root.substring("minecraft:".length()));
		}
		return false;
	}

	/** 命令根名（去掉 old. 前缀后的第一个空白分隔 token）。 */
	private static String rootOf(String command) {
		String text = stripMark(command);
		for (int i = 0; i < text.length(); i++) {
			if (Character.isWhitespace(text.charAt(i))) {
				return text.substring(0, i);
			}
		}
		return text;
	}

	/**
	 * 归一化成原版语义的命令文本：去掉一个前导 '/'（与 {@code Commands#trimOptionalPrefix} 完全一致）。
	 * 路由内部、缓存键、命令方块回写都用它，保证同一文本在各入口下是同一个键。
	 */
	public static String normalize(String command) {
		if (command == null) {
			return null;
		}
		return Commands.trimOptionalPrefix(command);
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
