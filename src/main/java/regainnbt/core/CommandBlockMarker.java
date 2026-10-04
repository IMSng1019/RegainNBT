package regainnbt.core;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BaseCommandBlock;

/**
 * 命令方块 / 命令方块矿车的 old. 自动回写。
 * 只在判定结果发生变化时回写；写失败要优雅降级（本次照常翻译执行）。
 */
public final class CommandBlockMarker {

	private CommandBlockMarker() {
	}

	/** 在 BaseCommandBlock#performCommand 返回后调用。 */
	public static void afterPerform(BaseCommandBlock block, ServerLevel level) {
		// TODO(T5): 命中缓存里的 TRANSLATED 才回写 old.，并调用 block.onUpdated(level) 让区块变脏
	}
}
