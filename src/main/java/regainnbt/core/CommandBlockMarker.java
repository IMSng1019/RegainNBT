package regainnbt.core;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BaseCommandBlock;

import regainnbt.RegainNBT;
import regainnbt.RegainNBTConfig;

/**
 * 命令方块 / 命令方块矿车的 old. 自动回写（报告 7.1 / 7.3）。
 *
 * 只在「这条命令文本在路由缓存里的结论是 TRANSLATED」时回写；已经带 old. 的跳过（幂等），
 * 写入失败必须优雅降级（本次翻译照常执行），因为回写只是持久化优化，不是执行的前提。
 */
public final class CommandBlockMarker {

	private CommandBlockMarker() {
	}

	/** 在 BaseCommandBlock#performCommand 返回后调用。 */
	public static void afterPerform(BaseCommandBlock block, ServerLevel level) {
		if (block == null) {
			return;
		}
		try {
			RegainNBTConfig config = RegainNBT.config();
			if (!config.enabled || !config.autoMarkCommandBlocks) {
				return;
			}

			String current = block.getCommand();
			// 与 Router 用同一个归一化 + 同一个缓存键（命令方块里可能存着 "/give ..."）
			String command = Router.normalize(current);
			if (command == null || command.isEmpty()) {
				return;
			}
			// 已经标记过：幂等，不重复写（避免高频命令方块每次都弄脏区块）
			if (Router.isMarked(command)) {
				return;
			}
			// 只有路由真的把这条命令翻译成功（TRANSLATED）才值得锁定语义
			if (TranslationCache.instance().decisionOf(command) != Decision.TRANSLATED) {
				return;
			}

			String marked = Router.addMark(command);
			if (marked.equals(current)) {
				return;
			}
			block.setCommand(marked);
			// 让区块变脏并同步客户端（BaseCommandBlock#onUpdated 是抽象方法，命令方块/矿车各自实现）
			block.onUpdated(level);
			if (config.logTranslations) {
				RegainNBT.LOGGER.info("[RegainNBT] 命令方块已标记 old.: {}", marked);
			}
		} catch (Throwable t) {
			// 写失败不能影响执行
			RegainNBT.LOGGER.warn("[RegainNBT] old. 自动回写失败（本次照常执行）: {}", t.toString());
		}
	}
}
