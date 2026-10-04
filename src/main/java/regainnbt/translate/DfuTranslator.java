package regainnbt.translate;

import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.DSL;
import com.mojang.serialization.Dynamic;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import regainnbt.core.TranslationReport;
import regainnbt.ids.IdRenames;

/**
 * 原版 DataFixerUpper 接线层（不要自己写组件映射表）。
 *
 * <p>实测（报告 2.2）：输入形状不对会「静默原样返回」，所以这里只负责「投喂 + 记录」，
 * 形状由 {@link Normalizer} 保证，结果断言由 {@link Verifier} 与 {@link LegacyTranslator} 负责。
 */
public final class DfuTranslator {

	/** 1.20.4 的数据版本。 */
	public static final int SOURCE_DATA_VERSION = 3700;

	private static volatile DataFixer fixer;

	private DfuTranslator() {
	}

	/** 目标版本数据版本（26.3 = 5023；走 IdRenames 的官方访问器，避免已废弃的 WORLD_VERSION）。 */
	public static int targetDataVersion() {
		return IdRenames.targetDataVersion();
	}

	private static DataFixer fixer() {
		DataFixer f = fixer;
		if (f == null) {
			synchronized (DfuTranslator.class) {
				if (fixer == null) {
					fixer = DataFixers.getDataFixer();
				}
				f = fixer;
			}
		}
		return f;
	}

	/** {@code {id, Count, tag:{...}}} -> components。 */
	public static CompoundTag fixItem(CompoundTag legacyStack, TranslationReport report) {
		return asCompound(update(References.ITEM_STACK, legacyStack, report, "ITEM_STACK"), legacyStack, report);
	}

	/** 注入 id 的实体标签 -> equipment / active_effects 等。 */
	public static CompoundTag fixEntity(CompoundTag legacyEntity, TranslationReport report) {
		return asCompound(update(References.ENTITY, legacyEntity, report, "ENTITY"), legacyEntity, report);
	}

	/** 注入 id 的方块实体标签。 */
	public static CompoundTag fixBlockEntity(CompoundTag legacyBlockEntity, TranslationReport report) {
		return asCompound(update(References.BLOCK_ENTITY, legacyBlockEntity, report, "BLOCK_ENTITY"),
			legacyBlockEntity, report);
	}

	/** 引号 JSON 文本组件 -> SNBT 组件（报告 2.9）。 */
	public static Tag fixTextComponent(String json, TranslationReport report) {
		return update(References.TEXT_COMPONENT, StringTag.valueOf(json), report, "TEXT_COMPONENT");
	}

	/**
	 * 用 DFU 的 ITEM_NAME 引用改名；返回 null 表示没有改名（原样）。
	 * 委托给 {@link IdRenames#dfuFixItemName(String)}，全工程只有一份 DFU 改名探测实现。
	 */
	public static String fixItemName(String id) {
		return IdRenames.dfuFixItemName(id);
	}

	/** 用 DFU 的 BLOCK_NAME 引用改名；返回 null 表示没有改名（原样）。 */
	public static String fixBlockName(String id) {
		return IdRenames.dfuFixBlockName(id);
	}

	private static Tag update(DSL.TypeReference ref, Tag in, TranslationReport report, String label) {
		int target = targetDataVersion();
		try {
			Tag out = fixer().update(ref, new Dynamic<>(NbtOps.INSTANCE, in), SOURCE_DATA_VERSION, target).getValue();
			return out == null ? in : out;
		} catch (Throwable t) {
			if (report != null) {
				report.warn("DFU " + label + " 调用失败(" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")，已按原样返回");
			}
			return in;
		}
	}

	private static CompoundTag asCompound(Tag out, CompoundTag fallback, TranslationReport report) {
		if (out instanceof CompoundTag c) {
			return c;
		}
		if (report != null) {
			report.warn("DFU 输出不是复合标签（" + out.getClass().getSimpleName() + "），已按原样返回");
		}
		return fallback;
	}

	/** 预热：DFU 首次调用有初始化成本。 */
	public static void warmUp(MinecraftServer server) {
		try {
			CompoundTag stack = new CompoundTag();
			stack.putString("id", "minecraft:stone");
			stack.putByte("Count", (byte) 1);
			stack.put("tag", new CompoundTag());
			fixItem(stack, null);
			CompoundTag entity = new CompoundTag();
			entity.putString("id", "minecraft:pig");
			fixEntity(entity, null);
			CompoundTag blockEntity = new CompoundTag();
			blockEntity.putString("id", "minecraft:chest");
			fixBlockEntity(blockEntity, null);
			fixItemName("minecraft:stone");
			fixBlockName("minecraft:stone");
			fixTextComponent("{\"text\":\"\"}", null);
		} catch (Throwable ignored) {
			// 预热失败不影响功能
		}
	}
}
