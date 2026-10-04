package regainnbt.patch;

import java.util.List;

/** 补丁规则注册表。 */
public final class PatchRegistry {

	private PatchRegistry() {
	}

	public static void load() {
		// TODO(T3)
	}

	/** 按顺序应用所有启用的规则。 */
	public static void apply(PatchContext ctx) {
		// TODO(T3)
	}

	public static List<String> ruleIds() {
		// TODO(T3)
		return List.of();
	}
}
