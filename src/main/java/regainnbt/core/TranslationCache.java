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

	private TranslationCache(int capacity) {
		this.capacity = Math.max(16, capacity);
		this.cache = new LinkedHashMap<>(64, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, RouteResult> eldest) {
				return size() > TranslationCache.this.capacity;
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
		return cache.get(command);
	}

	public synchronized void put(String command, RouteResult result) {
		if (result == null || result.decision() == Decision.TRANSLATION_FAILED) {
			return;
		}
		cache.put(command, result);
	}

	/** 只查结论，不触发翻译。 */
	public synchronized Decision decisionOf(String command) {
		RouteResult r = cache.get(command);
		return r == null ? null : r.decision();
	}

	public synchronized void clear() {
		cache.clear();
	}

	public synchronized int size() {
		return cache.size();
	}

	public int capacity() {
		return capacity;
	}
}
