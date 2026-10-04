package regainnbt.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import regainnbt.RegainNBT;
import regainnbt.core.TranslationCache;

/**
 * /regainnbt 管理指令：
 *   status    运行状态
 *   why &lt;command&gt;  诊断：判定路径 / 翻译结果 / 警告
 *   translate &lt;command&gt; 只翻译并回显（不执行）
 *   strip [范围] 把命令方块里的 old. 前缀剥掉（存档可回滚）
 *   reload    重载配置、清空缓存
 */
public final class RegainNBTCommand {

	private RegainNBTCommand() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			dispatcher.register(Commands.literal("regainnbt")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("status").executes(RegainNBTCommand::status))
				.then(Commands.literal("why")
					.then(Commands.argument("command", StringArgumentType.greedyString())
						.executes(RegainNBTCommand::why)))));
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		ctx.getSource().sendSuccess(() -> Component.literal(
			"[RegainNBT] enabled=" + RegainNBT.config().enabled
				+ " autoMark=" + RegainNBT.config().autoMarkCommandBlocks
				+ " 缓存=" + TranslationCache.instance().size() + "/" + TranslationCache.instance().capacity()), false);
		return 1;
	}

	private static int why(CommandContext<CommandSourceStack> ctx) {
		String command = StringArgumentType.getString(ctx, "command");
		ctx.getSource().sendSuccess(() -> Component.literal("[RegainNBT] TODO: " + command), false);
		return 1;
	}
}
