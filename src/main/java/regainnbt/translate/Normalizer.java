package regainnbt.translate;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import regainnbt.core.TranslationReport;

/**
 * 规范化：把命令里的旧写法还原成 DataFixerUpper 认识的形状（报告 2.2 的三个静默坑）。
 *
 * <ul>
 *   <li>物品：必须 {id, Count:1b, tag:{...}}，否则 DFU 原样返回</li>
 *   <li>实体/方块实体：必须注入 id，否则 ENTITY / BLOCK_ENTITY 修复静默 no-op</li>
 *   <li>方块状态参数（/setblock、/fill）：命令里没有 id，要先用方块→方块实体类型映射补上</li>
 * </ul>
 */
final class Normalizer {

	/** 选择器 nbt= 拿不到实体类型时的合成 id：必须是 DFU 认识、且具备 equipment/attributes/effects 的实体。 */
	static final String SYNTHETIC_ENTITY_ID = "minecraft:zombie";

	private Normalizer() {
	}

	/** 物品参数拆成「物品 token」与「旧 NBT」。{@code /give @s diamond_sword{...}} */
	record ItemPayload(String itemToken, String nbtText) {
	}

	/** 方块状态参数拆成「方块 token」「属性 [..]」「方块实体 NBT {..}」。 */
	record BlockPayload(String blockToken, String properties, String nbtText) {
	}

	/**
	 * @return null 表示这个参数不是物品 token（例如现代组件语法 id[...]）；
	 *         {@code nbtText == null} 表示「只有 ID、没有 NBT」—— 仍然可能是旧 ID（grass）。
	 */
	static ItemPayload splitItem(String text) {
		String t = text.trim();
		if (t.isEmpty()) {
			return null;
		}
		int brace = SnbtScanner.firstTopLevelChar(t, '{');
		if (brace < 0) {
			if (t.indexOf('[') >= 0) {
				return null; // 现代语法 id[组件=...]
			}
			return new ItemPayload(t, null);
		}
		String token = t.substring(0, brace).trim();
		if (token.isEmpty()) {
			return null;
		}
		int close = SnbtScanner.matchBracket(t, brace);
		if (close < 0) {
			return null;
		}
		return new ItemPayload(token, t.substring(brace, close + 1));
	}

	/** {@code nbtText == null} 表示方块参数没有 { }（例如只有 ID 需要改名）。 */
	static BlockPayload splitBlockState(String text) {
		String t = text.trim();
		int i = 0;
		while (i < t.length() && t.charAt(i) != '[' && t.charAt(i) != '{') {
			i++;
		}
		String token = t.substring(0, i).trim();
		if (token.isEmpty()) {
			return null;
		}
		String props = null;
		String nbt = null;
		if (i < t.length() && t.charAt(i) == '[') {
			int close = SnbtScanner.matchBracket(t, i);
			if (close < 0) {
				return null;
			}
			props = t.substring(i, close + 1);
			i = close + 1;
		}
		if (i < t.length() && t.charAt(i) == '{') {
			int close = SnbtScanner.matchBracket(t, i);
			if (close < 0) {
				return null;
			}
			nbt = t.substring(i, close + 1);
		}
		return new BlockPayload(token, props, nbt);
	}

	/** 方块 id -> 方块实体类型 id（用 BlockEntityType.isValid 扫描注册表）。 */
	static String blockEntityTypeOf(String blockId) {
		Identifier id = Identifier.tryParse(namespace(blockId));
		if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
			return null;
		}
		Block block = BuiltInRegistries.BLOCK.getValue(id);
		if (block == null) {
			return null;
		}
		var state = block.defaultBlockState();
		for (BlockEntityType<?> type : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
			if (type.isValid(state)) {
				return BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type).toString();
			}
		}
		return null;
	}

	/**
	 * /data merge block 没有方块 id：优先用 source 的真实世界 + 位置参数反查方块实体类型；
	 * 拿不到时按 NBT 键猜一个（并在诊断里告警）。
	 */
	static String resolveBlockEntityType(String command, ParseResults<CommandSourceStack> parse,
			CommandSourceStack source, CompoundTag nbt, TranslationReport report) {
		String explicit = nbt.getStringOr("id", null);
		if (explicit != null && !explicit.isEmpty()) {
			return explicit;
		}
		String fromWorld = blockEntityTypeFromWorld(parse, source);
		if (fromWorld != null) {
			return fromWorld;
		}
		String guessed = guessBlockEntityType(nbt);
		if (guessed != null && report != null) {
			report.step("方块实体类型无法从世界反查，按 NBT 键猜为 " + guessed);
		}
		return guessed;
	}

	private static String blockEntityTypeFromWorld(ParseResults<CommandSourceStack> parse, CommandSourceStack source) {
		if (parse == null || source == null) {
			return null;
		}
		try {
			ServerLevel level = source.getLevel();
			if (level == null) {
				return null;
			}
			CommandContext<CommandSourceStack> ctx = parse.getContext().build(source.getTextName());
			BlockPos pos = BlockPosArgument.getBlockPos(ctx, "target");
			BlockEntity be = level.getBlockEntity(pos);
			if (be == null) {
				return null;
			}
			return BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).toString();
		} catch (Throwable ignored) {
			return null;
		}
	}

	/** 拿不到世界时的按键猜测（只在无法反查时使用，调用方会告警）。 */
	static String guessBlockEntityType(CompoundTag nbt) {
		if (nbt == null) {
			return null;
		}
		if (nbt.contains("front_text") || nbt.contains("back_text") || nbt.contains("Text1")) {
			return "minecraft:sign";
		}
		if (nbt.contains("RecordItem") || nbt.contains("IsPlaying")) {
			return "minecraft:jukebox";
		}
		if (nbt.contains("Primary") || nbt.contains("Secondary") || nbt.contains("Levels")) {
			return "minecraft:beacon";
		}
		if (nbt.contains("BurnTime") || nbt.contains("CookTime") || nbt.contains("CookTimeTotal")) {
			return "minecraft:furnace";
		}
		if (nbt.contains("BrewTime")) {
			return "minecraft:brewing_stand";
		}
		if (nbt.contains("SkullOwner")) {
			return "minecraft:skull";
		}
		if (nbt.contains("SpawnData") || nbt.contains("SpawnCount") || nbt.contains("Delay")) {
			return "minecraft:mob_spawner";
		}
		if (nbt.contains("Items")) {
			return "minecraft:chest";
		}
		return null;
	}

	/** 实体类型 id：优先命令给的 hint（/summon 参数、选择器 type=），否则用合成 id。 */
	static String resolveEntityId(String hint) {
		if (hint != null && !hint.isEmpty() && !hint.startsWith("#")) {
			Identifier id = Identifier.tryParse(namespace(hint));
			if (id != null && BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
				return id.toString();
			}
		}
		return SYNTHETIC_ENTITY_ID;
	}

	static String namespace(String id) {
		return id.indexOf(':') >= 0 ? id : "minecraft:" + id;
	}

	/** 注入 id（已存在则保留原值）。 */
	static boolean injectId(CompoundTag tag, String id) {
		if (tag.contains("id")) {
			return false;
		}
		tag.putString("id", id);
		return true;
	}
}
