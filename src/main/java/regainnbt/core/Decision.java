package regainnbt.core;

/** 路由结论。 */
public enum Decision {
	/** 判定为现代语法（或与旧版无关），原样放行给原版解析器。 */
	MODERN,
	/** 判定为 1.20.4 旧版写法，并且已经成功翻译成目标版本语法。 */
	TRANSLATED,
	/** 判定为旧版写法，但翻译失败（此时应回落到原版报错路径，并把细节写日志）。 */
	TRANSLATION_FAILED
}
