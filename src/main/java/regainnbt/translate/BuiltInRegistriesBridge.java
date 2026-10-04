package regainnbt.translate;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/**
 * 目标版本注册表校验（报告 2.6）：ID 改名表可能给不出结果，
 * 但注册表是唯一权威 —— 不在注册表里的 ID 一定解析不过，必须在翻译前就拒绝并写诊断。
 */
final class BuiltInRegistriesBridge {

	private BuiltInRegistriesBridge() {
	}

	static boolean itemExists(String id) {
		Identifier key = Identifier.tryParse(id);
		return key != null && BuiltInRegistries.ITEM.containsKey(key);
	}

	static boolean blockExists(String id) {
		Identifier key = Identifier.tryParse(id);
		return key != null && BuiltInRegistries.BLOCK.containsKey(key);
	}

	static boolean entityTypeExists(String id) {
		Identifier key = Identifier.tryParse(id);
		return key != null && BuiltInRegistries.ENTITY_TYPE.containsKey(key);
	}
}
