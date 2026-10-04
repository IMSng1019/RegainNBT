package regainnbt.command;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.MinecartCommandBlock;
import net.minecraft.world.level.BaseCommandBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import regainnbt.core.Router;

/**
 * {@code /regainnbt strip [半径]}：把半径内命令方块 / 命令方块矿车里的 old. 前缀剥掉。
 *
 * <p>报告 7.3 的可回滚开关：带 old. 的命令方块离开 RegainNBT 后在原版服是未知指令，
 * strip 让它重新变成原版能读的形态（代价是下次执行要重新判定，autoMark 会再标回来）。
 *
 * <p>写入只发生在「确实带 old. 前缀」的方块上：先判断再写，不做无谓的区块弄脏（报告 7.3 副作用 2）。
 */
public final class CommandBlockStripper {

	/** 半径上限：防止一条指令扫穿整个世界。 */
	public static final int MAX_RADIUS = 256;

	private CommandBlockStripper() {
	}

	/** 一次 strip 的统计（含「扫描了多少」）。 */
	public record Stats(int radius, BlockPos center, int chunksScanned, int blockEntitiesScanned,
			int entitiesScanned, int commandBlocksFound, int stripped, int alreadyPlain) {

		public String describe() {
			return "[RegainNBT] strip 完成：中心=" + center.toShortString() + " 半径=" + radius
					+ "\n  扫描：区块 " + chunksScanned + " / 方块实体 " + blockEntitiesScanned
					+ " / 实体 " + entitiesScanned
					+ "\n  命令方块 " + commandBlocksFound + " 个（其中带 old. 前缀 " + stripped + " 个）"
					+ "\n  已剥离 old. 前缀 " + stripped + " 个；本来就是原版形态 " + alreadyPlain + " 个";
		}
	}

	/**
	 * @param radius 0 表示只处理执行者所在方块；&gt;0 表示以 center 为中心的立方体半径（与原版 /fill 同语义）
	 */
	public static Stats strip(ServerLevel level, BlockPos center, int radius) {
		int r = Math.max(0, Math.min(radius, MAX_RADIUS));
		int chunksScanned = 0;
		int blockEntitiesScanned = 0;
		int entitiesScanned = 0;
		int found = 0;
		int stripped = 0;
		int alreadyPlain = 0;

		// 1) 方块实体：只遍历已加载的整区块，绝不为了 strip 加载区块
		int minChunkX = (center.getX() - r) >> 4;
		int maxChunkX = (center.getX() + r) >> 4;
		int minChunkZ = (center.getZ() - r) >> 4;
		int maxChunkZ = (center.getZ() + r) >> 4;
		for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
			for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
				ChunkAccess access = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
				if (!(access instanceof LevelChunk chunk)) {
					continue;
				}
				chunksScanned++;
				for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
					blockEntitiesScanned++;
					if (!(blockEntity instanceof CommandBlockEntity commandBlockEntity)) {
						continue;
					}
					if (!inRange(blockEntity.getBlockPos(), center, r)) {
						continue;
					}
					found++;
					BaseCommandBlock block = commandBlockEntity.getCommandBlock();
					if (unmark(level, block)) {
						stripped++;
					} else {
						alreadyPlain++;
					}
				}
			}
		}

		// 2) 命令方块矿车：只有给了半径才扫实体（省略半径 = 只看执行者所在方块）
		if (r > 0) {
			for (Entity entity : level.getAllEntities()) {
				entitiesScanned++;
				if (!(entity instanceof MinecartCommandBlock minecart)) {
					continue;
				}
				if (!inRange(minecart.blockPosition(), center, r)) {
					continue;
				}
				found++;
				if (unmark(level, minecart.getCommandBlock())) {
					stripped++;
				} else {
					alreadyPlain++;
				}
			}
		}

		return new Stats(r, center.immutable(), chunksScanned, blockEntitiesScanned, entitiesScanned,
				found, stripped, alreadyPlain);
	}

	/**
	 * 剥掉一个命令方块里的 old. 前缀并落盘。
	 *
	 * @return true 表示确实剥掉了（写回成功）；false 表示本来就没有前缀
	 */
	public static boolean unmark(ServerLevel level, BaseCommandBlock block) {
		String command = block.getCommand();
		String stripped = stripOne(command);
		if (stripped == null) {
			return false;
		}
		block.setCommand(stripped);
		// 让方块实体 / 区块变脏，否则重启后又变回带前缀的形态（报告 7.3）
		block.onUpdated(level);
		return true;
	}

	/**
	 * 纯函数版：{@code old.give ...} -&gt; {@code give ...}。
	 *
	 * @return 剥掉一层前缀后的文本；本来没有前缀时返回 {@code null}
	 */
	public static String stripOne(String command) {
		if (command == null || !Router.isMarked(command)) {
			return null;
		}
		return Router.stripMark(command);
	}

	private static boolean inRange(BlockPos pos, BlockPos center, int radius) {
		return Math.abs(pos.getX() - center.getX()) <= radius
				&& Math.abs(pos.getY() - center.getY()) <= radius
				&& Math.abs(pos.getZ() - center.getZ()) <= radius;
	}
}
