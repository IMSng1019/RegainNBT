package regainnbt.patch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import regainnbt.RegainNBT;

/**
 * 补丁规则注册表（RegainNBT 第 2 层：覆盖原版 DFU 没有转换的真实缺口，报告 §2.13）。
 *
 * <p>顺序即应用顺序；每条规则的开关来自 config/regainnbt.json 的 {@code patchRules}（缺省启用）。
 * 单条规则抛异常只记 warn，不影响其它规则与整条翻译链。
 */
public final class PatchRegistry {

	private static final List<PatchRule> RULES = new ArrayList<>();
	private static volatile boolean loaded;

	private PatchRegistry() {
	}

	/** 注册内置规则；重复调用安全（幂等），RegainNBT 初始化时调用一次。 */
	public static synchronized void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		register(new CustomPotionEffectsRule());
		register(new ActiveEffectsRule());
		register(new BeaconEffectsRule());
	}

	/** 注册一条规则；id 重复时忽略。 */
	public static synchronized void register(PatchRule rule) {
		for (PatchRule existing : RULES) {
			if (existing.id().equals(rule.id())) {
				return;
			}
		}
		RULES.add(rule);
	}

	/** 按顺序应用所有启用的规则，就地修改 {@link PatchContext#fixed()}。 */
	public static void apply(PatchContext ctx) {
		load();
		for (PatchRule rule : List.copyOf(RULES)) {
			if (!isEnabled(rule.id())) {
				continue;
			}
			try {
				rule.apply(ctx);
			} catch (Throwable t) {
				ctx.warn("补丁规则 " + rule.id() + " 抛异常（已跳过）：" + t);
			}
		}
	}

	/** 配置开关（缺省启用）。 */
	public static boolean isEnabled(String ruleId) {
		try {
			return RegainNBT.config().isPatchEnabled(ruleId);
		} catch (Throwable t) {
			return true;
		}
	}

	/** 已注册的规则 id（稳定顺序）。 */
	public static List<String> ruleIds() {
		load();
		List<String> ids = new ArrayList<>(RULES.size());
		for (PatchRule rule : RULES) {
			ids.add(rule.id());
		}
		return Collections.unmodifiableList(ids);
	}
}
