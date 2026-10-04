package regainnbt.ids;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.DSL;
import com.mojang.serialization.Dynamic;

import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import regainnbt.core.TranslationReport;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ID 改名表（T4）。
 *
 * 报告 §2.6：原版 DFU 只覆盖一半 ——
 *   - 命令参数里的 ID（{@code /give Steve minecraft:grass}）DFU 完全不处理 -> Unknown item；
 *   - NBT 内部的 {@code id} 有时转（scute -> turtle_scute、chain -> iron_chain），有时不转（grass）。
 *
 * 所以解析顺序是「先验证、再问原版、最后才查表」，表只需要维护原版漏掉的那部分：
 * <ol>
 *   <li>{@link BuiltInRegistries#ITEM} / {@link BuiltInRegistries#BLOCK} 校验 —— 合法就直接用（零维护）；</li>
 *   <li>不合法才问原版 DFU 的 {@link References#ITEM_NAME} / {@link References#BLOCK_NAME}
 *       （3700 -> {@link #targetDataVersion()}）—— 命中且结果合法就用 DFU 的（零维护）；</li>
 *   <li>都不是才查本模组内置的 {@code regainnbt/id_renames.json}（由 {@code tools/audit} 离线生成）；</li>
 *   <li>仍然失败 -> 返回 {@code null} 并在 {@link TranslationReport} 上 warn（绝不静默返回原值）。</li>
 * </ol>
 *
 * 内置表内容（26.3 实测，见 {@code tools/audit/reports/report.txt}）：
 * 1.20.4 的 1312 个物品名里只有 2 个在 26.3 不合法（scute / chain），1058 个方块名里只有 1 个（chain），
 * 且这三个 DFU 都能改；表里真正「DFU 漏掉」的只有 1.20.3 之前就存在的 {@code minecraft:grass -> minecraft:short_grass}。
 */
public final class IdRenames {

	/** 模组内置表的资源路径。 */
	public static final String RESOURCE_PATH = "/regainnbt/id_renames.json";

	/** 输入域的数据版本：1.20.4。 */
	public static final int SOURCE_DATA_VERSION = 3700;

	private static final Logger LOGGER = LoggerFactory.getLogger("regainnbt.ids");

	private static volatile Map<String, String> itemTable = Map.of();
	private static volatile Map<String, String> blockTable = Map.of();
	private static volatile String sourceVersion = "?";
	private static volatile String targetVersion = "?";
	private static volatile boolean loaded;

	private IdRenames() {
	}

	/** 读取模组内置的 {@code regainnbt/id_renames.json}。重复调用是幂等的（已加载则直接返回）。 */
	public static synchronized void load() {
		if (loaded) {
			return;
		}
		loaded = true;   // 先置位：资源缺失/解析失败也不要每次调用都重试
		Map<String, String> items = new LinkedHashMap<>();
		Map<String, String> blocks = new LinkedHashMap<>();
		try (InputStream in = IdRenames.class.getResourceAsStream(RESOURCE_PATH)) {
			if (in == null) {
				LOGGER.warn("[RegainNBT] 找不到内置 ID 改名表 {}（只有原版 DFU 兜底）", RESOURCE_PATH);
				return;
			}
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
				JsonElement root = JsonParser.parseReader(reader);
				if (!root.isJsonObject()) {
					LOGGER.warn("[RegainNBT] {} 顶层不是 JSON 对象，忽略", RESOURCE_PATH);
					return;
				}
				JsonObject obj = root.getAsJsonObject();
				sourceVersion = optString(obj, "sourceVersion");
				targetVersion = optString(obj, "targetVersion");
				readTable(obj.get("items"), items, "items");
				readTable(obj.get("blocks"), blocks, "blocks");
			}
		} catch (Exception e) {
			LOGGER.warn("[RegainNBT] 读取内置 ID 改名表失败：{}", e.toString());
			return;
		}
		itemTable = Map.copyOf(items);
		blockTable = Map.copyOf(blocks);
		LOGGER.info("[RegainNBT] ID 改名表已加载：物品 {} 条，方块 {} 条（{} -> {}）",
			itemTable.size(), blockTable.size(), sourceVersion, targetVersion);
	}

	/** 强制重新读取（供 /regainnbt reload 用）。 */
	public static synchronized void reload() {
		loaded = false;
		itemTable = Map.of();
		blockTable = Map.of();
		load();
	}

	public static boolean isLoaded() {
		return loaded;
	}

	public static int itemTableSize() {
		ensureLoaded();
		return itemTable.size();
	}

	public static int blockTableSize() {
		ensureLoaded();
		return blockTable.size();
	}

	/** 表来源版本描述（供 /regainnbt status）。 */
	public static String sourceVersion() {
		ensureLoaded();
		return sourceVersion;
	}

	/** 表目标版本描述（供 /regainnbt status）。 */
	public static String targetVersion() {
		ensureLoaded();
		return targetVersion;
	}

	/**
	 * 把命令 token / NBT 里的物品 ID 解析成目标版本里合法的物品 ID。
	 *
	 * @param rawId  命令参数或 NBT 里的 ID，允许省略命名空间（{@code grass} -> {@code minecraft:grass}）
	 * @param report 诊断记录，可为 null
	 * @return 目标版本里合法的物品 ID；无法解析时返回 {@code null}（并已写 warn）
	 */
	public static String resolveItemId(String rawId, TranslationReport report) {
		ensureLoaded();
		return resolve(rawId, BuiltInRegistries.ITEM, false, itemTable, report, "物品");
	}

	/**
	 * 把命令 token / NBT 里的方块 ID 解析成目标版本里合法的方块 ID。
	 *
	 * @param rawId  命令参数或 NBT 里的 ID，允许省略命名空间（{@code grass} -> {@code minecraft:grass}）
	 * @param report 诊断记录，可为 null
	 * @return 目标版本里合法的方块 ID；无法解析时返回 {@code null}（并已写 warn）
	 */
	public static String resolveBlockId(String rawId, TranslationReport report) {
		ensureLoaded();
		return resolve(rawId, BuiltInRegistries.BLOCK, true, blockTable, report, "方块");
	}

	// ------------------------------------------------------------------ 解析

	private static String resolve(String rawId, Registry<?> registry, boolean block,
			Map<String, String> table, TranslationReport report, String kind) {
		if (rawId == null || rawId.isBlank()) {
			warn(report, kind + " ID 为空");
			return null;
		}
		if (rawId.charAt(0) == '#') {
			warn(report, kind + "标签（" + rawId + "）不走 ID 改名表，需要按标签解析");
			return null;
		}
		String normalized = normalize(rawId);
		Identifier loc = Identifier.tryParse(normalized);
		if (loc == null) {
			warn(report, kind + " ID 语法不合法：\"" + rawId + "\"（原版解析器同样会拒绝）");
			return null;
		}
		String id = loc.toString();

		// 26.x 的注册表在 Bootstrap 之前是空的；空表会把一切判成不合法，必须显式区分。
		if (registry.keySet().isEmpty()) {
			warn(report, "目标版本（" + SharedConstants.getCurrentVersion().name() + "）的" + kind
				+ "注册表为空，无法校验 " + id + "（Bootstrap 未运行？）");
			return null;
		}

		// 路径 1：本来就是合法 ID（绝大多数情况，零维护）
		if (registry.containsKey(loc)) {
			return id;
		}

		// 路径 2：原版 DFU 的 ITEM_NAME / BLOCK_NAME 改名
		String dfu = block ? dfuFixBlockName(id) : dfuFixItemName(id);
		if (dfu != null) {
			Identifier renamed = Identifier.tryParse(dfu);
			if (renamed != null && registry.containsKey(renamed)) {
				step(report, kind + " " + id + " -> " + renamed + "（原版 DFU "
					+ (block ? "BLOCK_NAME" : "ITEM_NAME") + " 改名）");
				return renamed.toString();
			}
			warn(report, "原版 DFU 把 " + id + " 改成了 " + dfu + "，但它在目标版本里仍然不合法"
				+ "（需要重跑 tools/audit 生成改名表）");
		}

		// 路径 3：内置表（只维护原版漏掉的那部分）
		String mapped = table.get(id);
		if (mapped != null) {
			Identifier target = Identifier.tryParse(mapped);
			if (target != null && registry.containsKey(target)) {
				step(report, kind + " " + id + " -> " + target + "（内置改名表）");
				return target.toString();
			}
			warn(report, "内置改名表把 " + id + " 映射到 " + mapped + "，但目标在 "
				+ SharedConstants.getCurrentVersion().name() + " 里不合法 —— 表已过期，请重跑 tools/audit");
			return null;
		}

		warn(report, "无法解析" + kind + " ID \"" + rawId + "\"：不在 " + SharedConstants.getCurrentVersion().name()
			+ " 的注册表里，原版 DFU 没有改名，内置表（物品 " + itemTable.size() + " 条 / 方块 "
			+ blockTable.size() + " 条）也没有条目 —— 若它确实是 1.20.4 的合法 ID，请重跑 tools/audit 补表");
		return null;
	}

	/** 允许省略命名空间：{@code grass} -> {@code minecraft:grass}（与命令参数一致）。 */
	private static String normalize(String rawId) {
		String trimmed = rawId.trim();
		return trimmed.indexOf(':') < 0 ? Identifier.DEFAULT_NAMESPACE + ":" + trimmed : trimmed;
	}

	/**
	 * 目标版本的数据版本号（26.3 = 5023）。
	 *
	 * <p>注意：{@code SharedConstants.WORLD_VERSION} 在 26.3 里已被标记 {@code @Deprecated}
	 * （javac 实测），官方访问器是 {@code SharedConstants.getCurrentVersion().dataVersion().version()}，
	 * 两者取值相同（探针里断言过）。
	 */
	public static int targetDataVersion() {
		return SharedConstants.getCurrentVersion().dataVersion().version();
	}

	// ------------------------------------------------------------------ 原版 DFU 改名

	/**
	 * 用原版 DFU 的 {@link References#ITEM_NAME} 做 3700 -> 当前版本的改名。
	 *
	 * <p>给 {@code regainnbt.translate.DfuTranslator#fixItemName} 复用，语义一致：返回 null 表示 DFU 没改名。
	 *
	 * @param itemId 完整的（带命名空间的）物品 ID
	 * @return DFU 改出来的新 ID；DFU 没改名、或改名结果与输入相同、或 DFU 抛异常时返回 {@code null}
	 */
	public static String dfuFixItemName(String itemId) {
		return dfuRename(References.ITEM_NAME, itemId);
	}

	/** 用原版 DFU 的 {@link References#BLOCK_NAME} 改名；返回 null 表示没改名。 */
	public static String dfuFixBlockName(String blockId) {
		return dfuRename(References.BLOCK_NAME, blockId);
	}

	/**
	 * 26.3 实测（Spike/Probe1）：ITEM_NAME / BLOCK_NAME 是「余项类型」（remainder），
	 * 输入必须是<b>裸字符串</b>的 Dynamic；喂复合标签会打出 {@code Not a string} 并原样返回。
	 */
	private static String dfuRename(DSL.TypeReference reference, String id) {
		try {
			DataFixer fixer = DataFixers.getDataFixer();
			Dynamic<Tag> input = new Dynamic<>(NbtOps.INSTANCE, StringTag.valueOf(id));
			String renamed = fixer.update(reference, input, SOURCE_DATA_VERSION, targetDataVersion())
				.asString(null);
			if (renamed == null || renamed.equals(id)) {
				return null;
			}
			return renamed;
		} catch (Throwable t) {
			LOGGER.debug("[RegainNBT] DFU {} 改名 {} 失败：{}", reference, id, t.toString());
			return null;
		}
	}

	// ------------------------------------------------------------------ 杂项

	private static void ensureLoaded() {
		if (!loaded) {
			load();
		}
	}

	private static void readTable(JsonElement element, Map<String, String> target, String what) {
		if (element == null || !element.isJsonObject()) {
			return;
		}
		for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
			String from = entry.getKey();
			if (from.startsWith("_") || !entry.getValue().isJsonPrimitive()) {
				continue;
			}
			Identifier loc = Identifier.tryParse(from);
			Identifier to = Identifier.tryParse(entry.getValue().getAsString());
			if (loc == null || to == null) {
				LOGGER.warn("[RegainNBT] 改名表 {} 里的条目语法不合法，已跳过：{} -> {}", what, from,
					entry.getValue());
				continue;
			}
			target.put(loc.toString(), to.toString());
		}
	}

	private static String optString(JsonObject obj, String key) {
		JsonElement e = obj.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : "?";
	}

	private static void step(TranslationReport report, String message) {
		if (report != null) {
			report.step(message);
		}
	}

	private static void warn(TranslationReport report, String message) {
		LOGGER.debug("[RegainNBT] {}", message);
		if (report != null) {
			report.warn(message);
		}
	}
}
