package regainnbt.detect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * 旧版（1.20.4）NBT 特征表 —— 数据驱动、可读、每条命中带人类可读 reason。
 *
 * <p>硬性约束 4：意图检测不能只按键名，必须看值类型/形状。典型例子：
 * <pre>
 *   {CustomName:'{"text":"Bob"}'}  -> LEGACY（值是 JSON 字符串文本组件）
 *   {CustomName:{text:"Bob"}}      -> MODERN（值是复合标签）
 *   {CustomName:"Bob"}             -> MODERN（两代同义：字面量 Bob）
 *   {HandItems:[{}]}               -> LEGACY（旧装备键，现代为 equipment）
 *   {equipment:{mainhand:{...}}}   -> MODERN
 *   {Items:[{Slot:0b,id:"...",count:1}]}  -> MODERN（现代容器条目：Slot 大写 + count 小写 + components）
 *   {Items:[{Slot:0b,id:"...",Count:1b}]} -> LEGACY（旧物品格式：大写 Count / tag / display…）
 * </pre>
 *
 * <p>键表来源：docs/feasibility-report.md §2.13（101 个 1.20.4 常用键中 35 个已彻底失去读取方）
 * 与 §2.14（C 类疑似缺口）。表中每个键在 26.3 里都<strong>没有非 datafixer 读取方</strong>，
 * 因此出现即等价于「这是 1.20.4 的写法」。不在表里的键（如 NoAI / Health / Items / id / ActiveEffects 的现代拼写）
 * 一律放行，保证不误判。
 */
public final class LegacySignatures {

	/** 分析上下文：同一个键在不同位置含义不同（Count 只在物品栈里有意义）。 */
	public enum Ctx {
		/** 实体 / 方块实体 / storage 的普通复合标签。 */
		TOP,
		/** 物品栈（容器 Items 元素、Item、HandItems 元素……）。 */
		ITEM_STACK
	}

	/** 值形状：类型感知的核心。 */
	public enum Shape {
		ANY,
		COMPOUND,
		LIST,
		NUMBER,
		STRING,
		/** 字符串，且内容看起来是 JSON 文本组件（报告 §2.4）。 */
		STRING_JSON
	}

	/**
	 * 一条键规则。
	 *
	 * @param key      旧版键名（大小写敏感：Count=旧 / count=新）
	 * @param shape    值形状要求
	 * @param contexts 生效上下文
	 * @param reason   人类可读原因（会出现在 /regainnbt why）
	 */
	public record KeyRule(String key, Shape shape, Set<Ctx> contexts, String reason) {

		public boolean appliesTo(Ctx ctx) {
			return contexts.contains(ctx);
		}

		public boolean matches(CompoundTag parent, Tag value) {
			switch (shape) {
				case COMPOUND -> {
					if (!(value instanceof CompoundTag)) return false;
				}
				case LIST -> {
					if (!(value instanceof ListTag)) return false;
				}
				case NUMBER -> {
					if (!isNumber(value)) return false;
				}
				case STRING -> {
					if (!(value instanceof StringTag)) return false;
				}
				case STRING_JSON -> {
					if (!(value instanceof StringTag s) || !looksLikeJsonComponent(s.value())) return false;
				}
				default -> {
				}
			}
			return switch (key) {
				// 旧物品栈的 tag 包装：必须和 id / Count 同时出现才算旧写法（避免把自定义数据里的 tag 当旧键）
				case "tag" -> parent.contains("id") || parent.contains("Count");
				// display 只在装着 Name / Lore / color 时才是旧版物品展示键
				case "display" -> {
					CompoundTag d = (CompoundTag) value;
					yield d.contains("Name") || d.contains("Lore") || d.contains("color");
				}
				default -> true;
			};
		}
	}

	private static final Set<Ctx> TOP = Set.of(Ctx.TOP);
	private static final Set<Ctx> ITEM = Set.of(Ctx.ITEM_STACK);
	private static final Set<Ctx> ANY_CTX = Set.of(Ctx.TOP, Ctx.ITEM_STACK);

	private static final List<KeyRule> RULES = new ArrayList<>();
	private static final Map<String, KeyRule> BY_KEY;
	private static final Set<String> NON_PATH_KEYS = Set.of("CustomName", "display", "tag");

	/** 会接收文本组件参数的指令（文本级回退时用来判断引号 JSON，报告 §2.12）。 */
	public static final Set<String> COMPONENT_COMMANDS =
			Set.of("tellraw", "title", "bossbar", "team", "scoreboard", "dialog");

	/** 使用 ItemArgument 的指令：这类指令里顶层 {...} 就是旧版物品 NBT（报告 §2.2）。 */
	public static final Set<String> ITEM_ARG_COMMANDS = Set.of("give", "item");

	private static final String[] JSON_COMPONENT_MARKERS = {
			"\"text\"", "\"translate\"", "\"extra\"", "\"score\"", "\"selector\"", "\"keybind\"",
			"\"nbt\"", "\"storage\"", "\"clickEvent\"", "\"click_event\"",
			"\"hoverEvent\"", "\"hover_event\"", "\"color\"", "\"bold\"", "\"italic\"",
			"\"font\"", "\"with\"", "\"interpret\"", "\"separator\""
	};

	static {
		// ------------------------------------------------------------------
		// 生物装备 / 属性 / 效果（报告 §2.13：现代代码 0 读取方）
		// ------------------------------------------------------------------
		rule("HandItems", Shape.LIST, ANY_CTX, "旧版生物手持装备键 HandItems（现代为 equipment）");
		rule("ArmorItems", Shape.LIST, ANY_CTX, "旧版生物护甲键 ArmorItems（现代为 equipment）");
		rule("ArmorItem", Shape.COMPOUND, ANY_CTX, "旧版马铠键 ArmorItem（现代为 body_armor_item 组件）");
		rule("SaddleItem", Shape.COMPOUND, ANY_CTX, "旧版鞍物品键 SaddleItem（现代为 saddle 组件）");
		rule("AttributeModifiers", Shape.LIST, ANY_CTX, "旧版属性修饰符键 AttributeModifiers（现代为 attributes）");
		rule("Attributes", Shape.LIST, ANY_CTX, "旧版属性键 Attributes（现代为 attributes）");
		rule("ActiveEffects", Shape.LIST, TOP,
				"旧版实体状态效果键 ActiveEffects（现代为 active_effects；报告 §2.13 真缺口，DFU 不转）");
		rule("CustomPotionEffects", Shape.LIST, ANY_CTX,
				"旧版自定义药水效果键 CustomPotionEffects（现代为 potion_contents.custom_effects；报告 §2.13 真缺口）");

		// ------------------------------------------------------------------
		// 物品 NBT（现代是 id[组件=值]）
		// ------------------------------------------------------------------
		rule("display", Shape.COMPOUND, ANY_CTX, "旧版物品展示键 display（Name/Lore；现代为 custom_name / lore 组件）");
		rule("Enchantments", Shape.LIST, ANY_CTX, "旧版附魔键 Enchantments（现代为 enchantments 组件）");
		rule("Unbreakable", Shape.ANY, ANY_CTX, "旧版不可破坏键 Unbreakable（现代为 unbreakable 组件）");
		rule("Damage", Shape.NUMBER, ANY_CTX, "旧版耐久损伤键 Damage（现代为 damage 组件）");
		rule("CustomModelData", Shape.NUMBER, ANY_CTX, "旧版自定义模型数据键 CustomModelData（现代为 custom_model_data 组件）");
		rule("HideFlags", Shape.NUMBER, ANY_CTX, "旧版隐藏提示键 HideFlags（现代为 tooltip_display 组件）");
		rule("RepairCost", Shape.NUMBER, ANY_CTX, "旧版修复成本键 RepairCost（现代为 repair_cost 组件）");
		rule("Lore", Shape.LIST, ANY_CTX, "旧版描述键 Lore（现代为 lore 组件）");
		rule("Potion", Shape.STRING, ANY_CTX, "旧版药水键 Potion（现代为 potion_contents 组件）");
		rule("Fireworks", Shape.COMPOUND, ANY_CTX, "旧版烟花键 Fireworks（现代为 fireworks 组件）");
		rule("Explosion", Shape.COMPOUND, ANY_CTX, "旧版烟花爆炸键 Explosion（现代为 explosion 组件）");
		rule("SkullOwner", Shape.COMPOUND, ANY_CTX, "旧版头颅主人键 SkullOwner（现代为 profile 组件）");
		rule("Trim", Shape.COMPOUND, ANY_CTX, "旧版盔甲纹饰键 Trim（现代为 trim 组件）");
		rule("CanDestroy", Shape.LIST, ANY_CTX, "旧版可破坏方块键 CanDestroy（现代为 can_break 组件）");
		rule("CanPlaceOn", Shape.LIST, ANY_CTX, "旧版可放置方块键 CanPlaceOn（现代为 can_place_on 组件）");
		rule("ChargedProjectiles", Shape.LIST, ANY_CTX, "旧版弩装填键 ChargedProjectiles（现代为 charged_projectiles 组件）");
		rule("Patterns", Shape.LIST, ANY_CTX, "旧版旗帜图案键 Patterns（现代为 banner_patterns 组件）");
		rule("BlockEntityTag", Shape.COMPOUND, ANY_CTX, "旧版物品方块实体键 BlockEntityTag（现代为 block_entity_data 组件）");
		rule("EntityTag", Shape.COMPOUND, ANY_CTX, "旧版物品实体键 EntityTag（现代为 entity_data 组件）");
		rule("BlockStateTag", Shape.COMPOUND, ANY_CTX, "旧版物品方块状态键 BlockStateTag（现代为 block_state 组件）");
		rule("BurnTime", Shape.NUMBER, ANY_CTX, "旧版燃料燃烧时间键 BurnTime");
		rule("CookTime", Shape.NUMBER, ANY_CTX, "旧版熔炉烹饪进度键 CookTime");
		rule("CookTimeTotal", Shape.NUMBER, ANY_CTX, "旧版熔炉总烹饪时间键 CookTimeTotal");
		rule("IsPlaying", Shape.ANY, ANY_CTX, "旧版唱片机播放键 IsPlaying");
		rule("Lock", Shape.STRING, ANY_CTX, "旧版容器锁键 Lock（现代为 lock 组件）");

		// 方块实体 / 告示牌
		rule("Text1", Shape.STRING, ANY_CTX, "旧版告示牌文本键 Text1（现代为 front_text/back_text）");
		rule("Text2", Shape.STRING, ANY_CTX, "旧版告示牌文本键 Text2（现代为 front_text/back_text）");
		rule("Text3", Shape.STRING, ANY_CTX, "旧版告示牌文本键 Text3（现代为 front_text/back_text）");
		rule("Text4", Shape.STRING, ANY_CTX, "旧版告示牌文本键 Text4（现代为 front_text/back_text）");
		rule("Primary", Shape.NUMBER, ANY_CTX, "旧版信标主效果键 Primary（报告 §2.13 真缺口：现代代码已不读）");
		rule("Secondary", Shape.NUMBER, ANY_CTX, "旧版信标副效果键 Secondary（报告 §2.13 真缺口：现代代码已不读）");

		// ------------------------------------------------------------------
		// 类型感知：同名键两代都有，只能看值形状（报告 §2.4）
		// ------------------------------------------------------------------
		rule("CustomName", Shape.STRING_JSON, TOP,
				"CustomName 是 JSON 字符串（旧版文本组件写法；现代应为复合标签 {text:...} 或纯字符串）");

		// ------------------------------------------------------------------
		// 旧物品栈内部（只在 ITEM_STACK 上下文生效，避免误伤自定义数据）
		// ------------------------------------------------------------------
		rule("Count", Shape.NUMBER, ITEM, "旧版物品数量键 Count（现代为小写 count）");
		// 注意：大写 Slot **不是**旧版信号（T6/T7 实测）：26.3 的方块实体容器条目仍然是
		// {Slot:0b,id:"...",count:N,components:{...}}（ContainerHelper + ItemStackWithSlot），
		// 也就是说「条目里有 Slot」两代都成立。判别旧版只能靠大写 Count / tag / display 等确凿旧形状。
		rule("tag", Shape.COMPOUND, ITEM, "旧版物品 tag 包装（现代为 components）");

		Map<String, KeyRule> map = new LinkedHashMap<>();
		for (KeyRule r : RULES) map.put(r.key(), r);
		BY_KEY = Collections.unmodifiableMap(map);
	}

	private LegacySignatures() {
	}

	private static void rule(String key, Shape shape, Set<Ctx> contexts, String reason) {
		RULES.add(new KeyRule(key, shape, contexts, reason));
	}

	/** 按键名取规则（大小写敏感）。 */
	public static KeyRule ruleFor(String key) {
		return key == null ? null : BY_KEY.get(key);
	}

	/** 全部旧版键（只读，供诊断/测试使用）。 */
	public static Set<String> legacyKeys() {
		return BY_KEY.keySet();
	}

	/** 该键是否可以作为「旧版 NBT 路径段」触发（CustomName/display/tag 两代都有同名字段，不能只看名字）。 */
	public static boolean isLegacyPathSegment(String segment) {
		return BY_KEY.containsKey(segment) && !NON_PATH_KEYS.contains(segment);
	}

	/**
	 * 从某个键递归到子上下文；返回 null 表示不递归。
	 * 只递归到「结构已知」的键，绝不递归进 custom_data / components 这类用户数据。
	 */
	public static Ctx childContext(String key, Ctx ctx) {
		return switch (key) {
			case "Items", "Inventory", "container", "Item", "ChargedProjectiles", "SaddleItem", "ArmorItem" ->
					Ctx.ITEM_STACK;
			case "BlockEntityTag", "EntityTag", "display" -> Ctx.TOP;
			case "tag" -> ctx == Ctx.ITEM_STACK ? Ctx.TOP : null;
			default -> null;
		};
	}

	/** 看起来像 JSON 文本组件吗（报告 §2.4/§2.9/§2.12 的判据）。 */
	public static boolean looksLikeJsonComponent(String raw) {
		if (raw == null) return false;
		String s = raw.trim();
		if (s.length() < 2) return false;
		// 旧写法 '"§aTitle"'：整个值被一对引号包住 -> 现代会连引号一起显示
		if (s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') return true;
		char first = s.charAt(0);
		if (first != '{' && first != '[') return false;
		// 双引号被转义的写法（"{\"text\":\"hi\"}"）也要认出来
		String flat = s.indexOf('\\') >= 0 ? s.replace("\\", "") : s;
		for (String marker : JSON_COMPONENT_MARKERS) {
			if (flat.contains(marker)) return true;
		}
		return false;
	}

	/** 数值标签判定（不依赖 NumericTag，直接用 Tag id）。 */
	public static boolean isNumber(Tag value) {
		if (value == null) return false;
		return switch (value.getId()) {
			case Tag.TAG_BYTE, Tag.TAG_SHORT, Tag.TAG_INT, Tag.TAG_LONG, Tag.TAG_FLOAT, Tag.TAG_DOUBLE -> true;
			default -> false;
		};
	}

	/** 小写化指令名（诊断用）。 */
	public static String normalizeCommandName(String name) {
		return name == null ? "" : name.toLowerCase(Locale.ROOT);
	}
}
