package regainnbt.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 命令文本 -> 路由结论 的 LRU 缓存。
 * 聊天或 RCON 没有持久载体，靠它避免同一条命令被反复判定；命令方块 20Hz 场景也靠它。
 */
public final class TranslationCache {
	private static volatile TranslationCache instance = new TranslationCache(4096);

	private final int capacity;
	private final Map<String, RouteResult> cache;
	/**
	 * 翻译失败的负缓存（容量小得多）。
	 * 为什么需要：翻译失败的结果本来不缓存，但命令方块是 20Hz 的 —— 一条翻译不出来的旧命令会每 tick 重试一次翻译。
	 * 负缓存只影响「同一条文本」的重复开销，不影响正确性（新版解析失败时原版照样报它自己的错误）。
	 */
	private final Map<String, RouteResult> failures;

	private TranslationCache(int capacity) {
		this.capacity = Math.max(16, capacity);
		this.cache = new LinkedHashMap<>(64, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, RouteResult> eldest) {
				return size() > TranslationCache.this.capacity;
			}
		};
		int failureCapacity = Math.max(16, this.capacity / 8);
		this.failures = new LinkedHashMap<>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, RouteResult> eldest) {
				return size() > failureCapacity;
			}
		};
	}

	public static TranslationCache instance() {
		return instance;
	}

	/** 配置里改了容量时重建（会丢弃旧内容）。 */
	public static synchronized void resize(int capacity) {
		instance = new TranslationCache(capacity);
	}

	public synchronized RouteResult get(String command) {
		RouteResult hit = cache.get(command);
		return hit != null ? hit : failures.get(command);
	}

	public synchronized void put(String command, RouteResult result) {
		if (result == null) {
			return;
		}
		if (result.decision() == Decision.TRANSLATION_FAILED) {
			failures.put(command, result);
			return;
		}
		cache.put(command, result);
	}

	/** 只查结论，不触发翻译。 */
	public synchronized Decision decisionOf(String command) {
		RouteResult r = get(command);
		return r == null ? null : r.decision();
	}

	public synchronized void clear() {
		cache.clear();
		failures.clear();
	}

	public synchronized int size() {
		return cache.size();
	}

	public synchronized int failureSize() {
		return failures.size();
	}

	public int capacity() {
		return capacity;
	}
}
