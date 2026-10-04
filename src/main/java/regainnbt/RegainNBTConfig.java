package regainnbt;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** config/regainnbt.json */
public final class RegainNBTConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** 总开关。false = 完全不介入（相当于没装这个模组）。 */
	public boolean enabled = true;

	/** 命令方块里判定为旧版的指令是否自动回写成 old. 前缀。 */
	public boolean autoMarkCommandBlocks = true;

	/** 数据包函数是否在加载期逐行翻译。 */
	public boolean translateDataPackFunctions = true;

	/** 翻译成功时是否写日志。 */
	public boolean logTranslations = true;

	/** 翻译失败/存在缺口时是否写警告日志。 */
	public boolean logFailures = true;

	/** 路由缓存容量（命令方块 20Hz 与高频 function 场景）。 */
	public int cacheSize = 4096;

	/** 源数据版本：1.20.4 = 3700。 */
	public int sourceDataVersion = 3700;

	/** 补丁规则开关：规则 id -> 是否启用（缺省启用）。 */
	public Map<String, Boolean> patchRules = new LinkedHashMap<>();

	/** 完全不接管的指令根名（例如别的模组/数据包自己处理）。 */
	public List<String> disabledCommands = new ArrayList<>();

	public boolean isPatchEnabled(String ruleId) {
		return patchRules.getOrDefault(ruleId, true);
	}

	public boolean isCommandDisabled(String root) {
		return disabledCommands.contains(root);
	}

	public static RegainNBTConfig load(Path file) {
		RegainNBTConfig cfg = new RegainNBTConfig();
		try {
			if (Files.exists(file)) {
				try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
					RegainNBTConfig read = GSON.fromJson(reader, RegainNBTConfig.class);
					if (read != null) {
						cfg = read;
						if (cfg.patchRules == null) {
							cfg.patchRules = new LinkedHashMap<>();
						}
						if (cfg.disabledCommands == null) {
							cfg.disabledCommands = new ArrayList<>();
						}
					}
				}
			}
			cfg.save(file);
		} catch (Exception e) {
			RegainNBT.LOGGER.warn("[RegainNBT] 读取配置失败，使用默认配置: {}", e.toString());
		}
		return cfg;
	}

	public void save(Path file) {
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			RegainNBT.LOGGER.warn("[RegainNBT] 写配置失败: {}", e.toString());
		}
	}
}
