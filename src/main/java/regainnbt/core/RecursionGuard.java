package regainnbt.core;

/**
 * 重入保护：翻译后的命令会再次进入 Commands#performPrefixedCommand，
 * 此时必须直接交给原版执行，不能再触发一次路由（否则可能无限递归）。
 */
public final class RecursionGuard {
	private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

	private RecursionGuard() {
	}

	/** @return true 表示允许本次路由；false 表示已经在重入中，应当让原版继续处理。 */
	public static boolean enter() {
		int depth = DEPTH.get();
		if (depth > 0) {
			return false;
		}
		DEPTH.set(depth + 1);
		return true;
	}

	public static void exit() {
		int depth = DEPTH.get();
		DEPTH.set(Math.max(0, depth - 1));
	}

	public static int depth() {
		return DEPTH.get();
	}
}
