package regainnbt.translate;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * 序列化：把 DFU 的输出渲染回目标版本的命令语法。
 *
 * <p>物品（报告 2.8 已实测原版解析器接受这种形式）：
 * {@code minecraft:diamond_sword[minecraft:custom_name={...},minecraft:damage=10]}
 * <br>实体/方块实体：直接 SNBT 复合标签（{@link CompoundTag#toString()} 会自行给含特殊字符的键加引号）。
 */
final class Serializer {

	private Serializer() {
	}

	/** {@code {id, components:{...}}} -> {@code id[comp=value,...]} */
	static String renderItem(CompoundTag fixed) {
		String id = fixed.getStringOr("id", null);
		if (id == null) {
			return null;
		}
		CompoundTag components = fixed.getCompound("components").orElse(null);
		if (components == null || components.isEmpty()) {
			return id;
		}
		StringBuilder sb = new StringBuilder(id).append('[');
		boolean first = true;
		for (String key : components.keySet()) {
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append(key).append('=').append(components.get(key));
		}
		return sb.append(']').toString();
	}

	static String renderCompound(Tag tag) {
		return tag == null ? null : tag.toString();
	}

	/** 方块状态参数：{@code minecraft:chest[facing=north]{...}} */
	static String renderBlockState(String blockToken, String properties, CompoundTag fixed) {
		StringBuilder sb = new StringBuilder(blockToken);
		if (properties != null) {
			sb.append(properties);
		}
		sb.append(fixed);
		return sb.toString();
	}
}
