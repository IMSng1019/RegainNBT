package regainnbt.test;

import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;

/**
 * 所有需要真实 Minecraft 26.3 代码的测试的基类（headless，不启动服务器）。
 *
 * <p>与 spike 程序完全相同的引导顺序：
 * <pre>
 *   SharedConstants.tryDetectVersion();
 *   net.minecraft.server.Bootstrap.bootStrap();
 * </pre>
 *
 * <p>注意：本类故意叫 {@code Bootstrap}（任务约定的基类名），因此引用原版引导类时必须写全限定名
 * {@code net.minecraft.server.Bootstrap}，不能 import（简单名会被本类遮蔽）。
 *
 * <p>引导后可直接使用真实参数类型、真实 DataFixer、真实 codec；但 headless 环境有两个已知限制（实测于 26.3）：
 * <ul>
 *   <li>动态注册表（enchantment / trim_pattern / trim_material）未加载 → 含附魔、盔甲纹饰的组件语法
 *       {@code ItemArgument} 解析会失败（实服无此问题，端到端验收脚本覆盖）。</li>
 *   <li>{@code new ItemStack(...)} / {@code ItemInput#createItemStack} 抛
 *       {@code NullPointerException: Components not bound yet} → 断言语义请用
 *       {@code itemInput.components().split().added()}（真实绑定的组件对象，Probe3 实测可用）。</li>
 * </ul>
 */
public abstract class Bootstrap {

	private static boolean booted;

	@BeforeAll
	static void regainnbt$bootStrap() {
		boot();
	}

	/** 幂等引导；也可以被非 JUnit 的验收入口（见 tools/acceptance/run-junit.ps1）直接调用。 */
	public static synchronized void boot() {
		if (booted) {
			return;
		}
		SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		MinecraftTestHarness.init();
		booted = true;
	}

	public static boolean isBooted() {
		return booted;
	}
}
