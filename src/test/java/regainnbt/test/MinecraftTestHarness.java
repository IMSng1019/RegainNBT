package regainnbt.test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.CompoundTagArgument;
import net.minecraft.commands.arguments.ComponentArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.NbtPathArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.commands.arguments.SlotArgument;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.commands.arguments.blocks.BlockInput;

/**
 * 测试夹具：真实的 {@link CommandDispatcher}（用真实参数类型注册，参考 Spike6）+ 执行结果捕获 +
 * DFU 参考实现（Probe2 验证过的规范化链路，用作黄金期望与 T1 的交叉对照）。
 *
 * <p>注册的命令形态与真实原版一致（只保留黄金用例需要的分支），这样 {@code LegacyTranslator#translate}
 * 内部的 reparse 验证走的是真实 Brigadier 命令树与真实参数类型。
 */
public final class MinecraftTestHarness {

	/** 1.20.4 的数据版本（报告 §1）。 */
	public static final int V1_20_4 = 3700;

	public static DataFixer fixer;
	public static int currentDataVersion;
	public static CommandBuildContext buildContext;
	public static CommandSourceStack source;
	public static CommandDispatcher<CommandSourceStack> dispatcher;

	private static boolean initialized;

	// ---- 执行结果捕获（每次 execute 前清空）----
	private static ItemInput capturedItem;
	private static CompoundTag capturedCompoundTag;
	private static CompoundTag capturedBlockNbt;
	private static BlockState capturedBlockState;
	private static Component capturedComponent;
	private static int capturedSlot = Integer.MIN_VALUE;
	private static int executions;

	private MinecraftTestHarness() {
	}

	public static synchronized void init() {
		if (initialized) {
			return;
		}
		// 26.3 起 SharedConstants.WORLD_VERSION 已 @Deprecated，用 WorldVersion#dataVersion（T4 的经验）
		currentDataVersion = net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version();
		fixer = DataFixers.getDataFixer();
		Map<ResourceKey<? extends Registry<?>>, Registry<?>> registries = new LinkedHashMap<>();
		for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
			registries.put(registry.key(), registry);
		}
		buildContext = CommandBuildContext.simple(
			new RegistryAccess.ImmutableRegistryAccess((Map) registries), FeatureFlags.DEFAULT_FLAGS);
		source = Commands.createCompilationContext(PermissionSet.ALL_PERMISSIONS);
		dispatcher = new CommandDispatcher<>();
		registerCommands();
		initialized = true;
	}

	private static void registerCommands() {
		dispatcher.register(Commands.literal("give")
			.then(Commands.argument("targets", EntityArgument.players())
				.then(Commands.argument("item", ItemArgument.item(buildContext))
					.executes(ctx -> {
						capturedItem = ItemArgument.getItem(ctx, "item");
						executions++;
						return 1;
					}))));

		dispatcher.register(Commands.literal("item")
			.then(Commands.literal("replace")
				.then(Commands.literal("block")
					.then(Commands.argument("targetPos", BlockPosArgument.blockPos())
						.then(Commands.argument("slot", SlotArgument.slot())
							.then(Commands.literal("with")
								.then(Commands.argument("item", ItemArgument.item(buildContext))
									.executes(ctx -> {
										capturedSlot = SlotArgument.getSlot(ctx, "slot");
										capturedItem = ItemArgument.getItem(ctx, "item");
										executions++;
										return 1;
									}))))))));

		dispatcher.register(Commands.literal("summon")
			.then(Commands.argument("entity", ResourceArgument.resource(buildContext, Registries.ENTITY_TYPE))
				.then(Commands.argument("pos", Vec3Argument.vec3())
					.executes(ctx -> {
						executions++;
						return 1;
					})
					.then(Commands.argument("nbt", CompoundTagArgument.compoundTag())
						.executes(ctx -> {
							capturedCompoundTag = CompoundTagArgument.getCompoundTag(ctx, "nbt");
							executions++;
							return 1;
						})))));

		dispatcher.register(Commands.literal("setblock")
			.then(Commands.argument("pos", BlockPosArgument.blockPos())
				.then(Commands.argument("block", BlockStateArgument.block(buildContext))
					.executes(ctx -> {
						BlockInput input = BlockStateArgument.getBlock(ctx, "block");
						capturedBlockState = input.getState();
						capturedBlockNbt = readBlockInputTag(input);
						executions++;
						return 1;
					}))));

		dispatcher.register(Commands.literal("data")
			.then(Commands.literal("merge")
				.then(Commands.literal("block")
					.then(Commands.argument("targetPos", BlockPosArgument.blockPos())
						.then(Commands.argument("nbt", CompoundTagArgument.compoundTag())
							.executes(ctx -> {
								capturedCompoundTag = CompoundTagArgument.getCompoundTag(ctx, "nbt");
								executions++;
								return 1;
							})))))
			.then(Commands.literal("get")
				.then(Commands.literal("block")
					.then(Commands.argument("targetPos", BlockPosArgument.blockPos())
						.then(Commands.argument("path", NbtPathArgument.nbtPath())
							.executes(ctx -> {
								executions++;
								return 1;
							}))))));

		dispatcher.register(Commands.literal("tellraw")
			.then(Commands.argument("targets", EntityArgument.players())
				.then(Commands.argument("message", ComponentArgument.textComponent(buildContext))
					.executes(ctx -> {
						capturedComponent = ComponentArgument.getRawComponent(ctx, "message");
						executions++;
						return 1;
					}))));
	}

	// ------------------------------------------------------------------
	// 执行 / 解析
	// ------------------------------------------------------------------

	/** 用真实 dispatcher 解析 + 原版校验 + 执行，返回本次捕获结果。 */
	public static Capture execute(String command) {
		resetCapture();
		try {
			ParseResults<CommandSourceStack> parse = dispatcher.parse(command, source);
			Commands.validateParseResults(parse);
			dispatcher.execute(parse);
		} catch (Exception e) {
			throw new AssertionError("执行失败: " + command + "\n  -> " + e, e);
		}
		return new Capture(command, capturedItem, capturedCompoundTag, capturedBlockNbt, capturedBlockState,
			capturedComponent, capturedSlot, executions);
	}

	/** 只解析并做原版校验（不执行）：用于「翻译结果能被真实 dispatcher reparse」这条断言。 */
	public static void assertParses(String command) {
		try {
			ParseResults<CommandSourceStack> parse = dispatcher.parse(command, source);
			Commands.validateParseResults(parse);
		} catch (Exception e) {
			throw new AssertionError("翻译结果无法被真实 dispatcher 解析: " + command + "\n  -> " + e, e);
		}
	}

	/** 解析并返回异常信息；解析/校验通过时返回 null。 */
	public static String parseError(String command) {
		try {
			ParseResults<CommandSourceStack> parse = dispatcher.parse(command, source);
			Commands.validateParseResults(parse);
			return null;
		} catch (Exception e) {
			return e.toString();
		}
	}

	private static void resetCapture() {
		capturedItem = null;
		capturedCompoundTag = null;
		capturedBlockNbt = null;
		capturedBlockState = null;
		capturedComponent = null;
		capturedSlot = Integer.MIN_VALUE;
		executions = 0;
	}

	/**
	 * 26.3 的 {@link BlockInput} 没有公开 NBT 读取口（{@code private final CompoundTag tag}），
	 * 测试里用反射取出来做真实断言（Spike 之外的方块实体 NBT 校验只能这么拿）。
	 */
	private static CompoundTag readBlockInputTag(BlockInput input) {
		try {
			java.lang.reflect.Field field = BlockInput.class.getDeclaredField("tag");
			field.setAccessible(true);
			return (CompoundTag) field.get(input);
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}

	/** 一次命令执行的捕获结果。 */
	public record Capture(String command, ItemInput item, CompoundTag compoundTag, CompoundTag blockNbt,
			BlockState blockState, Component component, int slot, int executions) {

		public boolean executed() {
			return executions > 0;
		}

		/** 解析后真实绑定到解析器上的组件集合（headless 下 createItemStack 不可用，见基类注释）。 */
		public DataComponentMap itemComponents() {
			if (item == null) {
				throw new AssertionError("这条命令没有产生 item 参数: " + command);
			}
			return item.components().split().added();
		}
	}

	// ------------------------------------------------------------------
	// DFU 参考实现（Probe2 验证过的链路；用作黄金期望 / T1 交叉对照）
	// ------------------------------------------------------------------

	/** 物品：{id, Count:1b, tag:{...}} -> DFU ITEM_STACK -> 现代 {@code id[k=v,...]} 语法。 */
	public static String normalizeItemToModernSyntax(String itemId, String innerNbt) {
		CompoundTag out = itemStackFix(itemId, innerNbt);
		CompoundTag components = out.getCompound("components").orElse(null);
		if (components == null || components.isEmpty()) {
			// 报告 §2.2：形状不对时 DFU 静默 no-op，原样返回
			return out.getString("id").orElse(itemId);
		}
		StringBuilder sb = new StringBuilder(out.getString("id").orElse(itemId)).append('[');
		boolean first = true;
		for (String key : components.keySet()) {
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append(key).append('=').append(components.get(key));
		}
		return sb.append(']').toString();
	}

	/** 物品：{id, Count:1b, tag:{...}} -> DFU ITEM_STACK（原始输出，用于断言 components 键）。 */
	public static CompoundTag itemStackFix(String itemId, String innerNbt) {
		try {
			CompoundTag legacy = new CompoundTag();
			legacy.putString("id", itemId);
			legacy.putByte("Count", (byte) 1);
			legacy.put("tag", TagParser.parseCompoundFully(innerNbt));
			return (CompoundTag) fixer.update(References.ITEM_STACK,
				new Dynamic<>(NbtOps.INSTANCE, legacy), V1_20_4, currentDataVersion).getValue();
		} catch (Exception e) {
			throw new AssertionError("DFU ITEM_STACK 修复失败: " + itemId + " " + innerNbt, e);
		}
	}

	/** 实体：注入 id 后走 DFU ENTITY。 */
	public static CompoundTag entityFix(String entityId, String nbt) {
		try {
			CompoundTag in = TagParser.parseCompoundFully(nbt);
			in.putString("id", entityId);
			return (CompoundTag) fixer.update(References.ENTITY,
				new Dynamic<>(NbtOps.INSTANCE, in), V1_20_4, currentDataVersion).getValue();
		} catch (Exception e) {
			throw new AssertionError("DFU ENTITY 修复失败: " + entityId + " " + nbt, e);
		}
	}

	/** 方块实体：注入方块 id 后走 DFU BLOCK_ENTITY。 */
	public static CompoundTag blockEntityFix(String blockId, String nbt) {
		try {
			CompoundTag in = TagParser.parseCompoundFully(nbt);
			in.putString("id", blockId);
			return (CompoundTag) fixer.update(References.BLOCK_ENTITY,
				new Dynamic<>(NbtOps.INSTANCE, in), V1_20_4, currentDataVersion).getValue();
		} catch (Exception e) {
			throw new AssertionError("DFU BLOCK_ENTITY 修复失败: " + blockId + " " + nbt, e);
		}
	}

	public static CompoundTag parseNbt(String snbt) {
		try {
			return TagParser.parseCompoundFully(snbt);
		} catch (Exception e) {
			throw new AssertionError("SNBT 解析失败: " + snbt, e);
		}
	}

	/** 物品 components 的键集合（TreeSet，断言输出稳定）。 */
	public static java.util.TreeSet<String> componentKeys(CompoundTag fixedItemStack) {
		CompoundTag components = fixedItemStack.getCompound("components").orElse(new CompoundTag());
		return new java.util.TreeSet<>(components.keySet());
	}

	public static Optional<String> componentKeys(String itemId, String innerNbt) {
		CompoundTag out = itemStackFix(itemId, innerNbt);
		CompoundTag components = out.getCompound("components").orElse(null);
		return components == null || components.isEmpty() ? Optional.empty() : Optional.of(components.keySet().toString());
	}
}