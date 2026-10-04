package regainnbt.mixin;

import com.mojang.brigadier.ParseResults;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import regainnbt.RegainNBT;
import regainnbt.core.RecursionGuard;
import regainnbt.core.RouteResult;
import regainnbt.core.Router;

/**
 * 拦截点（报告 2.7 / 5）：Commands#performPrefixedCommand 是聊天、命令方块、RCON 的公共入口。
 *
 * 两个注入点分工：
 *   performPrefixedCommand(HEAD) —— 还没有 parse：只做「显式 old. 前缀」和「文本级意图检测」，
 *                                   命中就换成翻译后的命令重新执行（不浪费一次解析）。
 *   performCommand(HEAD)         —— 已经 parse：用 ParseResults 做节点驱动的意图检测，
 *                                   并在原版校验失败（parse 阶段失败，无副作用）时回退到旧路。
 *
 * 硬性约束：回退只能发生在 parse 阶段；绝不能在 execute 阶段重试（会执行两次）。
 */
@Mixin(Commands.class)
public abstract class CommandsMixin {

	@Inject(method = "performPrefixedCommand", at = @At("HEAD"), cancellable = true)
	private void regainnbt$routeBeforeParse(CommandSourceStack source, String command, CallbackInfo ci) {
		if (!RegainNBT.config().enabled || !RecursionGuard.enter()) {
			return;
		}
		try {
			Commands self = (Commands) (Object) this;
			RouteResult result = Router.route(command, source, self.getDispatcher(), Router.Stage.PRE_PARSE, null);
			if (result.shouldReexecute() && !result.command().equals(command)) {
				ci.cancel();
				Router.executeRewritten(source, result.command());
			}
		} catch (Throwable t) {
			RegainNBT.LOGGER.warn("[RegainNBT] 前置路由异常，已放行原版处理: {}", t.toString());
		} finally {
			RecursionGuard.exit();
		}
	}

	@Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
	private void regainnbt$routeAfterParse(ParseResults<CommandSourceStack> parse, String command, CallbackInfo ci) {
		if (!RegainNBT.config().enabled || !RecursionGuard.enter()) {
			return;
		}
		try {
			CommandSourceStack source = parse.getContext().getSource();
			Commands self = (Commands) (Object) this;
			RouteResult result = Router.route(command, source, self.getDispatcher(), Router.Stage.POST_PARSE, parse);
			if (result.shouldReexecute() && !result.command().equals(command)) {
				ci.cancel();
				Router.executeRewritten(source, result.command());
			}
		} catch (Throwable t) {
			RegainNBT.LOGGER.warn("[RegainNBT] 后置路由异常，已放行原版处理: {}", t.toString());
		} finally {
			RecursionGuard.exit();
		}
	}
}
