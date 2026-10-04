package regainnbt;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import regainnbt.command.RegainNBTCommand;
import regainnbt.core.TranslationCache;
import regainnbt.ids.IdRenames;
import regainnbt.patch.PatchRegistry;
import regainnbt.translate.LegacyTranslator;

public final class RegainNBT implements ModInitializer {
	public static final String MOD_ID = "regainnbt";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static RegainNBTConfig config = new RegainNBTConfig();

	public static RegainNBTConfig config() {
		return config;
	}

	@Override
	public void onInitialize() {
		config = RegainNBTConfig.load(FabricLoader.getInstance().getConfigDir().resolve("regainnbt.json"));
		TranslationCache.resize(config.cacheSize);
		IdRenames.load();
		PatchRegistry.load();
		RegainNBTCommand.register();

		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			TranslationCache.instance().clear();
			LegacyTranslator.warmUp(server);
		});

		LOGGER.info("[RegainNBT] 已加载：enabled={} autoMarkCommandBlocks={} 源数据版本={} 补丁规则={}",
			config.enabled, config.autoMarkCommandBlocks, config.sourceDataVersion, PatchRegistry.ruleIds());
	}
}
