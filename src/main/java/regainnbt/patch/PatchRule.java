package regainnbt.patch;

/** RegainNBT 第 2 层：覆盖原版 DFU 没有转换的真实缺口（报告 2.13）。 */
public interface PatchRule {
	/** 稳定 id，用于配置开关与 /regainnbt patch。 */
	String id();

	/** 就地修改 {@link PatchContext#fixed()}。 */
	void apply(PatchContext ctx);
}
