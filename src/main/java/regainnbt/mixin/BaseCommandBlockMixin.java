package regainnbt.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BaseCommandBlock;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import regainnbt.RegainNBT;
import regainnbt.core.CommandBlockMarker;

/**
 * 命令方块 / 命令方块矿车的 old. 自动回写（报告 7.1，挂载点已在 26.3 复核）。
 *
 * 在 performCommand 返回后检查：这条命令文本在路由缓存里的结论是不是 TRANSLATED。
 * 只在判定结果变化时回写；写失败优雅降级（本次照常翻译执行）。
 */
@Mixin(BaseCommandBlock.class)
public abstract class BaseCommandBlockMixin {

	@Inject(method = "performCommand", at = @At("RETURN"))
	private void regainnbt$autoMark(ServerLevel level, CallbackInfoReturnable<Boolean> cir) {
		if (!RegainNBT.config().enabled || !RegainNBT.config().autoMarkCommandBlocks) {
			return;
		}
		try {
			CommandBlockMarker.afterPerform((BaseCommandBlock) (Object) this, level);
		} catch (Throwable t) {
			RegainNBT.LOGGER.warn("[RegainNBT] old. 自动回写失败（本次照常执行）: {}", t.toString());
		}
	}
}
