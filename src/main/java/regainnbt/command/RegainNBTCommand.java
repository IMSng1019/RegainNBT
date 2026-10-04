package regainnbt.command;

import java.nio.file.Path;
import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import regainnbt.RegainNBT;
import regainnbt.RegainNBTConfig;
import regainnbt.core.Decision;
import regainnbt.core.TranslationCache;
import regainnbt.ids.IdRenames;
import regainnbt.patch.PatchRegistry;

/**
 * /regainnbt 管理指令：
 *
 * <pre>
 *   status                 运行状态（开关 / 缓存 / ID 表 / 补丁规则 / 数据版本）
 *   why &lt;command&gt;          核心诊断：跑一次路由但绝不执行，输出命中路径与每一步过程
 *   translate &lt;command&gt;    只翻译并回显，不执行
 *   strip [半径]           把命令方块 / 命令方块矿车里的 old. 前缀剥掉（可回滚）
 *   reload                 重载 config/regainnbt.json、清空路由缓存
 * </pre>
 *
 * 整棵树 requires GM（26.3：{@code Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)}）。
 */
public final class RegainNBTCommand {

	private RegainNBTCommand() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			dispatcher.register(Commands.literal("regainnbt")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("status")
					.executes(RegainNBTCommand::status))
				.then(Commands.literal("why")
					.then(Commands.argument("command", StringArgumentType.greedyString())
						.executes(RegainNBTCommand::why)))
				.then(Commands.literal("translate")
					.then(Commands.argument("command", StringArgumentType.greedyString())
						.executes(RegainNBTCommand::translate)))
				.then(Commands.literal("strip")
					.executes(context -> strip(context, 0))
					.then(Commands.argument("radius", IntegerArgumentType.integer(0, CommandBlockStripper.MAX_RADIUS))
						.executes(context -> strip(context, IntegerArgumentType.getInteger(context, "radius")))))
				.then(Commands.literal("reload")
					.executes(RegainNBTCommand::reload))));
	}

	// ------------------------------------------------------------------ status

	private static int status(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		String text = statusText();
		source.sendSuccess(() -> Component.literal(text), false);
		return 1;
	}

	/** 状态文本（无副作用，不碰世界）。拆成独立方法便于在 headless 环境验证输出内容。 */
	public static String statusText() {
		RegainNBTConfig config = RegainNBT.config();
		List<String> rules = PatchRegistry.ruleIds();
		StringBuilder sb = new StringBuilder("[RegainNBT] 状态");
		sb.append("\n  enabled=").append(config.enabled)
			.append("  autoMarkCommandBlocks=").append(config.autoMarkCommandBlocks)
			.append("  translateDataPackFunctions=").append(config.translateDataPackFunctions)
			.append("\n  logTranslations=").append(config.logTranslations)
			.append("  logFailures=").append(config.logFailures)
			.append("\n  路由缓存=").append(TranslationCache.instance().size())
			.append(" / ").append(TranslationCache.instance().capacity())
			.append("\n  ID 改名表=").append(IdRenames.itemTableSize()).append(" 物品 / ")
			.append(IdRenames.blockTableSize()).append(" 方块")
			.append(IdRenames.isLoaded() ? "" : "（未加载）")
			.append("  版本=").append(IdRenames.sourceVersion()).append(" → ").append(IdRenames.targetVersion());
		if (rules.isEmpty()) {
			sb.append("\n  补丁规则=0 条");
		} else {
			sb.append("\n  补丁规则=").append(rules.size()).append(" 条: ");
			for (int i = 0; i < rules.size(); i++) {
				if (i > 0) {
					sb.append(", ");
				}
				sb.append(rules.get(i)).append(PatchRegistry.isEnabled(rules.get(i)) ? "(启用)" : "(停用)");
			}
		}
		// SharedConstants.WORLD_VERSION 在 26.3 已 @Deprecated，改用 WorldVersion#dataVersion()
		sb.append("\n  数据版本=源 ").append(config.sourceDataVersion)
			.append("（1.20.4） → 目标 ").append(SharedConstants.getCurrentVersion().dataVersion().version())
			.append("（").append(SharedConstants.getCurrentVersion().name()).append("）");
		if (!config.disabledCommands.isEmpty()) {
			sb.append("\n  完全放行的指令根=").append(config.disabledCommands);
		}
		return sb.toString();
	}

	// ------------------------------------------------------------------ why

	private static int why(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		String command = StringArgumentType.getString(context, "command");
		Diagnostics.Result result;
		try {
			result = Diagnostics.explain(command, source, dispatcher(source));
		} catch (Throwable t) {
			source.sendFailure(Component.literal("[RegainNBT] 诊断失败: " + t));
			return 0;
		}
		reply(source, result.format());
		// 有翻译结果才算诊断成功；MODERN（本来就不需要翻译）同样算正常
		return result.translated() != null || result.decision() == Decision.MODERN ? 1 : 0;
	}

	// ------------------------------------------------------------------ translate

	private static int translate(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		String command = StringArgumentType.getString(context, "command");
		Diagnostics.Result result;
		try {
			result = Diagnostics.explain(command, source, dispatcher(source));
		} catch (Throwable t) {
			source.sendFailure(Component.literal("[RegainNBT] 翻译失败: " + t));
			return 0;
		}
		reply(source, result.formatTranslation());
		return result.translated() == null ? 0 : 1;
	}

	// ------------------------------------------------------------------ strip

	private static int strip(CommandContext<CommandSourceStack> context, int radius) {
		CommandSourceStack source = context.getSource();
		ServerLevel level;
		try {
			level = source.getLevel();
		} catch (Throwable t) {
			level = null;
		}
		if (level == null) {
			source.sendFailure(Component.literal("[RegainNBT] strip 需要在一个维度里执行（当前命令源没有世界）"));
			return 0;
		}
		BlockPos center = BlockPos.containing(source.getPosition());
		CommandBlockStripper.Stats stats;
		try {
			stats = CommandBlockStripper.strip(level, center, radius);
		} catch (Throwable t) {
			source.sendFailure(Component.literal("[RegainNBT] strip 失败: " + t));
			return 0;
		}
		source.sendSuccess(() -> Component.literal(stats.describe()), true);
		return stats.stripped();
	}

	// ------------------------------------------------------------------ reload

	private static int reload(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		Path file = FabricLoader.getInstance().getConfigDir().resolve("regainnbt.json");
		RegainNBTConfig fresh;
		try {
			fresh = RegainNBTConfig.load(file);
		} catch (Throwable t) {
			source.sendFailure(Component.literal("[RegainNBT] 重载配置失败: " + t));
			return 0;
		}
		boolean changed = applyConfig(fresh);
		// resize 会重建缓存（等价于清空，并应用新的 cacheSize）
		TranslationCache.resize(Math.max(16, RegainNBT.config().cacheSize));
		// 内置 ID 改名表也从 jar 资源重读一次（配置里的 patchRules 是实时读取的，不需要重载）
		IdRenames.reload();
		String text = "[RegainNBT] 已重载 " + file
				+ (changed ? "（配置有变化）" : "（配置无变化）")
				+ "；路由缓存已清空 = " + TranslationCache.instance().size()
				+ " / " + TranslationCache.instance().capacity()
				+ "；ID 改名表=" + IdRenames.itemTableSize() + " 物品 / " + IdRenames.blockTableSize() + " 方块";
		source.sendSuccess(() -> Component.literal(text), false);
		return 1;
	}

	/**
	 * 把刚读出来的配置写回<strong>现存实例</strong>（RegainNBT.config() 的引用不变，
	 * 避免其它线程看到半新半旧的配置对象）。
	 *
	 * @return 是否有字段发生变化
	 */
	public static boolean applyConfig(RegainNBTConfig fresh) {
		return applyConfig(RegainNBT.config(), fresh);
	}

	/** 注意：{@link RegainNBTConfig} 新增字段时这里要同步，否则 reload 不会生效。 */
	private static boolean applyConfig(RegainNBTConfig live, RegainNBTConfig fresh) {
		boolean changed = live.enabled != fresh.enabled
				|| live.autoMarkCommandBlocks != fresh.autoMarkCommandBlocks
				|| live.translateDataPackFunctions != fresh.translateDataPackFunctions
				|| live.logTranslations != fresh.logTranslations
				|| live.logFailures != fresh.logFailures
				|| live.cacheSize != fresh.cacheSize
				|| live.sourceDataVersion != fresh.sourceDataVersion
				|| !live.patchRules.equals(fresh.patchRules)
				|| !live.disabledCommands.equals(fresh.disabledCommands);
		live.enabled = fresh.enabled;
		live.autoMarkCommandBlocks = fresh.autoMarkCommandBlocks;
		live.translateDataPackFunctions = fresh.translateDataPackFunctions;
		live.logTranslations = fresh.logTranslations;
		live.logFailures = fresh.logFailures;
		live.cacheSize = fresh.cacheSize;
		live.sourceDataVersion = fresh.sourceDataVersion;
		live.patchRules = fresh.patchRules;
		live.disabledCommands = fresh.disabledCommands;
		return changed;
	}

	// ------------------------------------------------------------------

	private static CommandDispatcher<CommandSourceStack> dispatcher(CommandSourceStack source) {
		return source.getServer().getCommands().getDispatcher();
	}

	/** 多行诊断一次性发出（聊天框支持换行），避免刷屏。 */
	private static void reply(CommandSourceStack source, List<String> lines) {
		String text = String.join("\n", lines);
		source.sendSuccess(() -> Component.literal(text), false);
	}
}
