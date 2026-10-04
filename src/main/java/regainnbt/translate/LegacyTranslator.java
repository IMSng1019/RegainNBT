package regainnbt.translate;

import java.util.Optional;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import regainnbt.core.TranslationReport;

/**
 * 旧路（1.20.4 -> 目标版本）的完整翻译链：
 *
 * <pre>
 *   规范化（id / Count / tag 包装 或 注入 id）
 *     -> 原版 DataFixers.update(...)        第 1 层：零维护
 *     -> 我们自己的补丁规则                  第 2 层：覆盖原版的洞
 *     -> 序列化回目标版本语法 -> 原版解析器 reparse -> 验证后采用
 * </pre>
 */
public final class LegacyTranslator {

	private LegacyTranslator() {
	}

	/**
	 * @return 翻译后的命令；无法翻译时返回 empty（调用方应回落到原版报错路径）
	 */
	public static Optional<String> translate(String command, CommandSourceStack source,
			CommandDispatcher<CommandSourceStack> dispatcher, TranslationReport report) {
		// TODO(T1)
		return Optional.empty();
	}

	/** 预热：DFU 首次调用有初始化成本，起服时先跑一次空转换。 */
	public static void warmUp(MinecraftServer server) {
		// TODO(T1)
	}
}
