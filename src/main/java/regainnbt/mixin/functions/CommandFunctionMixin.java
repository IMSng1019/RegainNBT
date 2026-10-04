package regainnbt.mixin.functions;

import java.util.List;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.commands.ExecutionCommandSource;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.resources.Identifier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import regainnbt.RegainNBT;
import regainnbt.function.FunctionTranslator;

/**
 * 数据包函数加载期逐行翻译（报告 7.2）。
 *
 * <p>26.3 目标方法（javap 复核）：
 * <pre>
 *   public static &lt;T extends ExecutionCommandSource&lt;T&gt;&gt; CommandFunction&lt;T&gt;
 *       net.minecraft.commands.functions.CommandFunction#fromLines(
 *           Identifier, CommandDispatcher&lt;T&gt;, T, List&lt;String&gt;)
 *   descriptor: (Lnet/minecraft/resources/Identifier;Lcom/mojang/brigadier/CommandDispatcher;Lnet/minecraft/commands/ExecutionCommandSource;Ljava/util/List;)Lnet/minecraft/commands/functions/CommandFunction;
 * </pre>
 *
 * <p>它是 <b>static interface 方法</b>，所以注入点写在接口 mixin 的 {@code private static} 处理器里：
 * HEAD + cancellable，自己调原版方法处理翻译后的行再 {@code setReturnValue}
 * （重入由 {@link FunctionTranslator#fromTranslatedLines} 的线程标记挡住）。
 *
 * <p>mixin 配置 {@code regainnbt.functions.mixins.json} 是 required=false + defaultRequire=0：
 * 注入失败只会记日志，服务器照常启动。
 */
@Mixin(CommandFunction.class)
public interface CommandFunctionMixin {

	@SuppressWarnings({"rawtypes", "unchecked"})
	@Inject(method = "fromLines", at = @At("HEAD"), cancellable = true)
	private static void regainnbt$translateFunctionLines(Identifier id, CommandDispatcher dispatcher,
			ExecutionCommandSource source, List<String> lines, CallbackInfoReturnable<CommandFunction> cir) {
		try {
			List<String> translated = FunctionTranslator.translateLines(lines, source, dispatcher);
			if (translated == lines) {
				// 没有任何改动（或配置关闭 / 宏行 / 无上下文）：交给原版处理
				return;
			}
			cir.setReturnValue(FunctionTranslator.fromTranslatedLines(id, dispatcher, source, translated));
		} catch (Throwable t) {
			// 翻译本身出问题绝不能影响数据包加载：记日志，放行原版
			RegainNBT.LOGGER.warn("[RegainNBT] 数据包函数翻译失败，已交给原版处理: {}", t.toString());
		}
	}
}
