package regainnbt.translate;

/** 一段 NBT/物品/组件载荷的类型，决定规范化方式与使用哪个 DFU 引用。 */
public enum PayloadKind {
	/** 物品参数（/give、/item ... with）：命中 DFU 的 ITEM_STACK。 */
	ITEM,
	/**
	 * 物品<b>谓词</b>参数（/clear、/execute if items ...）：只有物品 ID token、没有 NBT，走 IdRenames 改名。
	 * 26.3 没有公开的取值口，验证用「原文解析失败 + 译文 reparse 通过」的 A/B。
	 */
	ITEM_PREDICATE,
	/** 实体复合标签（/summon、/data merge entity、选择器 nbt=）：注入 id 后命中 DFU 的 ENTITY。 */
	ENTITY,
	/** 方块实体复合标签（/data merge block）：注入方块实体 id 后命中 DFU 的 BLOCK_ENTITY。 */
	BLOCK_ENTITY,
	/** 方块状态参数里的 NBT（/setblock、/fill）：注入方块 id 后命中 DFU 的 BLOCK_ENTITY。 */
	BLOCK_STATE,
	/** 文本组件参数（/tellraw、/title）：引号包着的 JSON 文本组件要转成 SNBT 组件。 */
	TEXT_COMPONENT,
	/** NBT 路径（/data get|modify|remove、/execute if data）：当前只做告警（路径无法可靠自动迁移）。 */
	NBT_PATH
}
