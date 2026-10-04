package regainnbt.core;

/** 判定这条命令是旧版的「依据」。 */
public enum PathKind {
	/** 没有命中任何旧版特征。 */
	NONE,
	/** 命令文本带显式 old. 前缀（用户或命令方块自己标记的）。 */
	EXPLICIT_MARK,
	/** 类型感知的旧版意图检测命中（关键：不能只按键名判）。 */
	INTENT,
	/** 新版解析或校验失败（例如 /give 的旧 NBT 触发 trailing data）。 */
	PARSE_FAILURE
}
