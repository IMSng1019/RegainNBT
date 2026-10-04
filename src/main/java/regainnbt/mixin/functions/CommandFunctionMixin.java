package regainnbt.mixin.functions;

import net.minecraft.commands.functions.CommandFunction;

import org.spongepowered.asm.mixin.Mixin;

/**
 * 数据包函数加载期逐行翻译（报告 7.2）。
 * TODO(T6): 目标方法 net.minecraft.commands.functions.CommandFunction#fromLines(
 *   Identifier, CommandDispatcher, ExecutionCommandSource, List&lt;String&gt;)
 * 它是一个 static interface 方法：用 @Inject(at = HEAD, cancellable = true) + CallbackInfoReturnable
 * 自行调用原方法处理翻译后的行，然后 setReturnValue。
 * 该 mixin 配置 required=false，注入失败只会记日志，不会让服务器起不来。
 */
@Mixin(CommandFunction.class)
public interface CommandFunctionMixin {
}
