# RegainNBT 可行性验证报告

- 验证日期：2026-10-04
- 验证对象：把 1.20.4 的 NBT 指令语法，在 1.20.5+ 服务端上透明翻译回当代组件语法
- 验证方式：**在真实 Minecraft 代码上跑**（headless 启动原版 Bootstrap，直接调用原版 API），非纸上推演

---

## 0. 结论摘要

**可行，而且比预期好得多。**

核心发现：**翻译器不需要我们自己写**。原版自带的 DataFixerUpper（DFU）里就有完整的「旧物品 NBT → 数据组件」转换链
（`net.minecraft.util.datafix.fixes.ItemStackComponentizationFix` 等一系列 fixer），并且 **26.1.2 里依然存在**。
我们要写的是「接线 + 兜底规则」，不是「几百个组件的映射表」。

同时验证出一个**必须规避的致命坑**：

> 「先试新语法，报错再试旧语法」这个策略，**只对物品类参数有效**。
> 对 `/summon`、`/data merge`、`/setblock` 这类「复合 NBT 参数」，旧写法在新版本下**会解析成功**，
> 然后静默产生错误结果。必须加「旧版意图检测」，不能只靠异常回退。

---

## 1. 验证环境与方法

| 项 | 值 |
|---|---|
| 验证的 Minecraft 版本 | **26.1.2**（官方映射 / mojmap 名，本机 Gradle 缓存内已有） |
| 当前最新正式版 | **26.3**（2026-09-15 发布） |
| 运行时 | 本机 Gradle 提供的 **JDK 25**（`~/.gradle/jdks/eclipse_adoptium-25-amd64-windows.2`） |
| 数据版本 | 1.20.4 = **3700**，26.1.2 = **4790** |
| 核心 API | `DataFixers.getDataFixer()`、`DataFixer#update(TypeReference, Dynamic, from, to)`、`References.ITEM_STACK / ENTITY / BLOCK_ENTITY`、`TagParser.parseCompoundFully`、`VanillaRegistries.createLookup`、`ItemArgument` |

验证程序源码保留在 `spike/src/Spike1..6.java`（可直接重跑，见 `spike/README.md`）。
它们做的事：`SharedConstants.tryDetectVersion()` → `Bootstrap.bootStrap()` → 调原版 API → 打印真实输出。

---

## 2. 已验证事实（逐条附证据）

### 2.1 原版 DFU 能把 1.20.4 物品 NBT 转成当代组件 ✅

输入（1.20.4 写法）：

    {id:"minecraft:diamond_sword",Count:1b,tag:{Enchantments:[{id:"minecraft:sharpness",lvl:5},{id:"minecraft:unbreaking",lvl:3}],
     display:{Name:'{"text":"Excalibur","italic":false}',Lore:['{"text":"line1"}']},
     Unbreakable:1b,CustomModelData:7,RepairCost:3,Damage:10}}

经 `FIXER.update(References.ITEM_STACK, ..., 3700, 4790)` 输出：

    {components:{
       "minecraft:custom_model_data":{floats:[7.0f]},
       "minecraft:custom_name":{italic:0b,text:"Excalibur"},
       "minecraft:damage":10,
       "minecraft:enchantments":{"minecraft:sharpness":5,"minecraft:unbreaking":3},
       "minecraft:lore":[{text:"line1"}],
       "minecraft:repair_cost":3,
       "minecraft:unbreakable":{}
     },count:1,id:"minecraft:diamond_sword"}

覆盖到：附魔、文本组件（JSON → SNBT）、耐久/修复成本/不可破坏/自定义模型数据、属性修饰符（`AttributeName`→`type`、
`Operation:0`→`add_value`、UUID 数组→`id`）、药水、容器内容、未知自定义键。

**未知自定义键自动进 `minecraft:custom_data`**（`{my_custom_flag:1,my_data:{a:1.5d}}` → `custom_data`），
正好符合「未知键兜底」策略，不用自己写。

### 2.2 三个必须做的「规范化」——漏一个就静默不转换 ⚠️（本次最大坑）

原版 fixer **不是容错的**：输入不符合它期望的形状时，它**不报错、不转换，原样返回**。实测：

| 场景 | 结果 |
|---|---|
| `/give` 的 NBT 直接丢给 ITEM_STACK 修复 | ❌ 原样返回（无 components），无任何异常 |
| 补上 `id`（命令 token 里的物品 ID） | ❌ 仍不转换 |
| 补上 `id` + `Count:1b` | ❌ 仍不转换 |
| **补上 `id` + `Count:1b` + 把命令里的 NBT 包进 `tag:{}`** | ✅ 转换成功 |

**完整链路实测（Spike6，4 通过 / 1 因环境受限失败）**：

    1) 输入    : give Steve diamond_sword{display:{Name:'{"text":"Excalibur","italic":false}'},Unbreakable:1b,CustomModelData:7,Damage:10}
    2) 规范化  : {Count:1b,id:"minecraft:diamond_sword",tag:{...}}
    3) 翻译后  : give Steve minecraft:diamond_sword[minecraft:custom_model_data={floats:[7.0f]},
                 minecraft:custom_name={italic:0b,text:"Excalibur"},minecraft:damage=10,minecraft:unbreakable={}]
    4) 原版解析: OK
    5) 真实组件: components={minecraft:custom_model_data=>CustomModelData[floats=[7.0]],
                            minecraft:custom_name=>literal{Excalibur}[style={!italic}],
                            minecraft:damage=>10, minecraft:unbreakable=>INSTANCE}

其他实测用例：

- 未知自定义键兜底：`give Steve paper{my_custom_flag:1,my_data:{a:1.5d}}` → `minecraft:paper[minecraft:custom_data={my_custom_flag:1,my_data:{a:1.5d}}]` ✅
- 描述文本：`{display:{Lore:['{"text":"old lore"}']}}` → `minecraft:lore=[{text:"old lore"}]` → `ItemLore[lines=[literal{old lore}], styledLines=[literal{old lore}[style={color=dark_purple,italic}]]]` ✅（连原版默认紫色斜体样式都还原了）
- 容器内容：`{BlockEntityTag:{Items:[{Slot:0b,id:"minecraft:diamond",Count:3b}]}}` → `minecraft:container=[{item:{count:3,id:"minecraft:diamond"},slot:0}]` ✅
- 附魔：翻译结果 `minecraft:enchantments={"minecraft:sharpness":5}` 正确生成；headless 环境缺动态附魔注册表导致解析报错（**环境限制，实服无此问题**，M0 起服复核）

原因很直白：`/give` 里 `{...}` 的内容等价于旧版的 `tag` 内容，必须还原成完整的旧版物品栈
`{id, Count, tag:{...}}` 才是 fixer 认识的东西。

同理，实体 NBT：`/summon minecraft:zombie ~ ~ ~ {...}` 的 NBT **没有 `id`**，
注入 `id:"minecraft:zombie"` 后 `References.ENTITY` 才生效（实测：不注入则原样返回）：

    输入: {CustomName:'{"text":"Bob"}',HandItems:[{id:...,Count:1b,tag:{...}},{}]}
    输出: {CustomName:{text:"Bob"},                    <-- JSON 文本组件已转成 SNBT
           HandItems:[...保留...],
           equipment:{mainhand:{components:{"minecraft:enchantments":{"minecraft:sharpness":2}},count:1,id:"minecraft:diamond_sword"}},
           id:"minecraft:zombie"}

> 结论：中间层必须有**按参数类型分派的规范化器**（give/item → 包 `tag`；summon/data/setblock → 直接 compound + 注入 `id`）。

### 2.3 静默陷阱：非物品 NBT 在新版本下「解析成功」🔴

`/summon minecraft:zombie ~ ~ ~ {CustomName:'{"text":"Bob"}',HandItems:[...]}`
在 26.1.2 的 `CompoundTagArgument` 下**解析完全成功**（复合标签参数什么都收），
于是「先新后旧」的回退根本不会触发，命令会带着旧语义执行：

- `CustomName` 是 JSON 字符串 → 1.21.5 之后按 SNBT 文本组件解释 → **名牌显示成 `"{\"text\":\"Bob\"}"` 字面量**
- `HandItems` 里的物品带 `tag:{...}` → 旧语义物品，新版本没有这个字段
- `Attributes` 用大写键 → 不再被读取

而 `/give Steve diamond_sword{...}` 则**明确报错**：

    Expected whitespace to end one argument, but found trailing data at position 24: ...mond_sword<--[HERE]

> 结论：**「异常回退」只覆盖物品类参数**；必须叠加「旧版意图检测」。

### 2.4 类型感知的意图检测有效 ✅

实测（同一键名、不同值类型必须区分）：

| NBT | 判定 | 依据 |
|---|---|---|
| `{CustomName:'{"text":"Bob"}'}` | LEGACY | `CustomName` 是**字符串**（JSON 文本组件） |
| `{CustomName:{text:"Bob"}}` | MODERN | `CustomName` 是复合标签 |
| `{HandItems:[{}]}` | LEGACY | 旧装备键 |
| `{equipment:{mainhand:{...}}}` | MODERN | 新装备键 |
| `{NoAI:1b,Health:20.0f}` | MODERN | 无关键，放行 |

关键点：**不能只按键名判断**（`CustomName` 两代都有），要看值的类型/形状。

### 2.5 旧键残留无害，不需要额外清理 ✅

DFU 输出会**同时保留** `HandItems`/`ArmorItems`/`Attributes` 旧键（只新增不改写）。
对 26.1.2 全量 class 常量池做精确扫描（UTF8 常量 + 长度，排除 `swapHandItems` 这类方法名误报）：

| 键 | 引用它的类 | 其中非 datafixer 的 |
|---|---|---|
| `HandItems` | 4 | **0** |
| `ArmorItems` | 5 | **0** |
| `Attributes` | 20 | 4（其中 2 个是 `AttributesRenameLegacy` / `AttributeModifierIdFix` 升级器，2 个是 JSON-RPC 注解类） |

> 结论：游戏代码已经**不再读取**这些旧键，残留只是冗余数据；实际生效的是 DFU 新写入的 `equipment` / `attributes`。
> （`equipment` 被 20 个非 datafixer 类读取，确认是当代读取路径。）不需要写清理逻辑。

### 2.6 ID 改名只覆盖一半，必须自建改名表 ⚠️

| 位置 | 是否被 DFU 改名 |
|---|---|
| 命令参数里的物品 ID（`/give Steve minecraft:grass`） | ❌ 完全不处理 → `Unknown item 'minecraft:grass'` |
| NBT 内部的 `id`（`{id:"minecraft:scute"}`） | ✅ → `minecraft:turtle_scute` |
| NBT 内部的 `id`（`{id:"minecraft:chain"}`） | ✅ → `minecraft:iron_chain` |
| NBT 内部的 `id`（`{id:"minecraft:grass"}`） | ❌ **仍是 `minecraft:grass`**（已不是合法 ID） |

> 结论：**必须自建一张 ID 改名表**，同时用于命令 token 和 NBT 内部结果校验。
> 建议做法：转换后拿 `BuiltInRegistries.ITEM.containsKey(id)` 校验，不合法才查改名表，查不到再报错——
> 这样表只需维护「原版漏掉的那部分」，新版新增改动时也不会静默出错。

### 2.7 拦截点存在且稳定 ✅

26.1.2（官方映射）中：

    public void net.minecraft.commands.Commands#performPrefixedCommand(CommandSourceStack, String)
    public void net.minecraft.commands.Commands#performCommand(ParseResults<CommandSourceStack>, String)
    public static CommandSyntaxException net.minecraft.commands.Commands#getParseException(ParseResults)

`performPrefixedCommand` 就是聊天 / 命令方块 / RCON 的公共入口（Yarn 名 `executeWithPrefix`）。
它是「拿到原始字符串 + 有真实 source」的地方，正好用来做「解析失败 → 翻译 → 重试」。

另外一个 26.x 的实测细节：`EntityArgument.parse` 会调用 `EntitySelectorParser.allowSelectors(Object)`——
**解析期就依赖 source**。所以重试必须用**真实的 `CommandSourceStack`**（我们本来就有），不能自造空 source。

### 2.8 原版解析器接受「我们生成」的组件语法 ✅

用真实 `ItemArgument` 直接解析生成结果，parse 全部成功：

    minecraft:diamond_sword[custom_name="Excalibur",unbreakable={},custom_model_data={floats:[7.0f]},damage=10]   -> OK
    minecraft:potion[minecraft:potion_contents={potion:"minecraft:strong_strength"}]                             -> OK

完整链路（`give Steve diamond_sword{...}` → 翻译 → 重解析）也走通：

    step1: modern parse FAILED -> trailing data
    step2: translated -> give Steve minecraft:diamond_sword[...]
    step3: reparse OK

> 注：headless 环境里 `new ItemStack(Items.STONE)` 会抛 `NullPointerException: Components not bound yet`，
> 这是**测试环境的引导限制**（组件未 bind），与方案无关；`ItemInput.item()` / `ItemInput.components()` 正常。
> 端到端起服验证放在 M0。

### 2.9 文本组件（含告示牌）会被正确转换 ✅

    1.20.4: {id:"minecraft:sign",front_text:{messages:['{"text":"hello"}',...],color:"black"}}
    转换后: {front_text:{color:"black",messages:[{text:"hello"},...]},id:"minecraft:sign"}

物品的 `display.Name` → `custom_name` 同样转成 SNBT 形式（`{italic:0b,text:"Excalibur"}`）。

### 2.10 为什么只有 1 类报错：**SNBT 语法层** 与 **内容语义层** 是两回事

关键认知：「NBT 被大改」改的**不是 SNBT 文本语法**，而是**内容的语义**。所以失效分成两层：

| 层 | 谁在管 | 旧写法会怎样 |
|---|---|---|
| ① 语法层（parser） | 参数类型自己的文法 | 只有**物品参数**有自己的文法（`id[组件=值,...]`），旧写法不是合法文法 → **报错** |
| ② 内容语义层（consumer） | 实体加载器 / 方块实体加载器 / NBT 路径匹配器 | 收「任意复合标签」的参数（`CompoundTagArgument`、`BlockStateArgument`、选择器 `nbt=`）只校验 SNBT 形状，**旧 NBT 永远合法** → 放行，消费时读不到旧键 → **静默失效** |

例如 `{HandItems:[{id:"minecraft:diamond_sword",Count:1b,tag:{...}}]}` 在 1.20.4 和 26.1.2 里都是**合法 SNBT**，
语法层没有任何理由拒绝它；只有 `LivingEntity` 不再读 `HandItems`（见 2.5 常量池扫描：非 datafixer 引用数为 0）。
结果就是：`/summon` 命令「成功」，僵尸却**空手**生成。

### 2.11 运行时身份证明（排除「其实跑的是旧版本」）

Spike8 由 JVM 自己报告加载来源，并做了自证式 A/B：

    ItemStack / DataFixers / ItemStackComponentizationFix / Bootstrap 全部加载自：
      file:/J:/mc/RegainNBT/spike/libs/minecraft-common-deobf-26.1.2.jar
    SharedConstants.getCurrentVersion().name() = 26.1.2
    SharedConstants.WORLD_VERSION              = 4790      （1.20.4 = 3700）
    DataVersion[version=4790, series=main]

同一运行时上的「一正一反」（如果这是 1.20.4 的代码，两个结果会完全颠倒）：

    give Steve diamond_sword[custom_name="ModernOnly"]   -> 解析通过，产出组件 literal{ModernOnly}   （1.20.4 上必然报错）
    give Steve diamond_sword{display:{Name:'"Legacy"'}}  -> 解析失败：trailing data                 （1.20.4 上必然通过）
    give Steve minecraft:grass                           -> Unknown item 'minecraft:grass'       （1.20.4 认识这个 ID）
    give Steve minecraft:short_grass                     -> 解析通过                               （1.20.5 改名后的新 ID）

以及版本跨度能力：`DataFixer.update(ITEM_STACK, ..., 3700, 4790)` 成功产出 `components` ——
只有「当前版本 > 3700」的新版代码才会执行这次升级，1.20.4 的代码在这里什么都不会发生。

> 所以：输入**确实是 1.20.4 的旧写法**，运行时**确实是 26.1.2**。五类不报错不是因为喂了旧版本，
> 而是因为它们的失败发生在语法层之后。

### 2.12 指令兼容的真实覆盖面：**1 个硬失败 + 5 个静默失败**（Spike7 实测）🔴

扫描 26.1.2 的 69 个指令参数类型，携带 NBT / 组件 / 物品的只有 6 个。逐个实测它们对「1.20.4 旧写法」的反应：

| 参数类型 | 受影响的指令 | 旧写法在新版下的反应 | 靠异常回退能救吗 |
|---|---|---|---|
| `item.ItemArgument` | `/give`、`/item replace ... with` | 🔴 **硬报错**（trailing data） | ✅ 能 |
| `CompoundTagArgument` | `/summon`、`/data merge block/entity/storage` | ⚠️ **解析通过**，旧语义生效 | ❌ 不能 |
| `blocks.BlockStateArgument` | `/setblock`、`/fill`（方块实体 NBT） | ⚠️ **解析通过** | ❌ 不能 |
| `NbtPathArgument` | `/data get/merge/modify/remove`、`/execute if data` | ⚠️ **路径能解析但取不到值** | ❌ 不能 |
| `EntityArgument` 的 `nbt=` | `/kill @e[nbt=...]`、`/execute if entity` | ⚠️ **解析通过**（实测 `kill @e[nbt={HandItems:[{}]}]` → parse OK） | ❌ 不能 |
| `ComponentArgument` | `/tellraw`、`/title`、`/bossbar` | ⚠️ **部分静默**（见下） | ❌ 不能 |

文本组件的实测细节：

    tellraw Steve {"text":"hi"}     -> literal{hi}                      ✅ 不受影响（JSON 对象语法恰好是 SNBT 子集）
    tellraw Steve "hi"              -> literal{hi}                      ✅ 不受影响
    tellraw Steve '{"text":"hi"}'   -> literal{{"text":"hi"}}            ❌ 静默变成字面量，玩家看到原始 JSON

> **这条直接修正了原设计**：`/give` 这类硬报错的只占 1/6，另外 5 类都**不会抛异常**。
> 只做「报错回退」的系统，会漏掉大部分失效的旧指令，而且漏得毫无痕迹。
> 「旧版意图检测」不是可选优化，是**必需组件**。

---

### 2.13 覆盖率审计：原版**不能**全包 1.20.4 的 NBT（重要修正）

Spike9 跑了 38 个真实 1.20.4 用例：**物品 27/27 全部转换**，但实体/方块实体出现「无变化」。
要判断「无变化」是不是真缺口，不能只看 DFU 有没有动，必须用**双条件判定**：

| 条件 | 含义 |
|---|---|
| ① 该键在当代代码里**还有没有读取方** | 用精确 UTF8 常量扫描（`\x01\x00<len><key>`，排除 datafixer）→ **0 引用 = 确定没有任何代码再读它** |
| ② DFU 有没有把它转换/改名 | 把真实样本喂给 `DataFixer.update`，看输出 |

**两个条件同时成立才是真缺口**：旧键没人读（数据必然失效）**且** DFU 没转（没有替代路径）。

#### 审计结果：101 个 1.20.4 常用键中，**35 个已彻底失去读取方**

    ActiveEffects, ArmorItem, ArmorItems, AttributeModifiers, Attributes, BlockEntityTag, BlockStateTag,
    BurnTime, CanDestroy, CanPlaceOn, ChargedProjectiles, CookTime, CookTimeTotal, CustomModelData,
    CustomPotionEffects, Damage, EntityTag, Explosion, Fireworks, HandItems, HideFlags, IsPlaying, Lock,
    Lore, Patterns, Potion, Primary, RepairCost, SaddleItem, Secondary, SkullOwner, Text1, Text2, Trim, Unbreakable

其中 **DFU 已经覆盖**（转换正常）：`AttributeModifiers` `Attributes` `ArmorItems` `HandItems` `BlockEntityTag`
`BlockStateTag` `BurnTime` `CookTime` `CookTimeTotal` `CanDestroy` `CanPlaceOn` `ChargedProjectiles` `CustomModelData`
`Damage` `EntityTag` `Fireworks` `HideFlags` `IsPlaying` `Lore` `Patterns` `Potion` `RepairCost` `SaddleItem` `ArmorItem`
`SkullOwner` `Trim` `Unbreakable`（Spike9/Spike10 实测）

#### 🔴 已确认的**真缺口**（旧键没人读 + DFU 不转 → 数据静默失效）

| 键 | 后果 | 实测 |
|---|---|---|
| `CustomPotionEffects`（物品） | 药水 / 药水箭的**自定义效果全部丢失**（DFU 把它丢进 `minecraft:custom_data` 不管了，而现代键是 `potion_contents.custom_effects`） | 已实测 |
| `ActiveEffects`（实体） | 怪物的**状态效果全部丢失**（现代键是 `active_effects`，`LivingEntity` 在读它） | 已实测 |
| 信标 `Primary` / `Secondary` | 信标效果丢失（`Levels` 仍被 `BeaconBlockEntity` 读取，所以只有这两个是死的，DFU 不动） | 已实测（beacon 用例「无变化」） |
| `Lock`（容器锁）等其余死键 | 待逐个实测 | 待办 |

> **这是对方案的重要修正**：原版 DFU 是「第一层翻译器」，覆盖了绝大部分物品 NBT，
> 但**实体效果 / 药水效果 / 部分方块实体**存在真实缺口，必须由 RegainNBT **自己补规则**（第二层）。
> 这也意味着「旧版兜底」不是可选保险，而是**必需品**。

#### 方法论可自动化（维护期的关键）

这套双条件审计完全机械化：**换一个新 MC 版本，重跑一遍** → 自动列出「失去读取方且 DFU 未覆盖」的键 →
就知道这次版本更新需要给 RegainNBT 补哪些规则。建议做成工程里的一个 Gradle 任务 / CI 检查。

#### 方法论的两个注意点

1. **「0 引用」是强结论**（整个代码库都没有这个字符串 → 不可能还有人读），可放心依赖。
2. **「>0 引用」是弱结论**：字符串可能只是同名噪声。实测反例：`tag`（280 处，全是结构/配方等无关用途）、
   `display`（51 处，含现代组件的 `display` 字段）、`Enchantments`（7 处，是现代 `EnchantmentsPredicate`）、
   `Count`（1 处，`ExperienceOrb`）——这些**都不是**旧物品 NBT 的读取方。
   而 `Owner`、`Inventory`、`Fuel`、`BrewTime`、`Items`、`Item`、`Levels` 则是**真实读取方**（`TamableAnimal`、`Player`、
   `BrewingStandBlockEntity`、`BaseContainerBlockEntity`、`BeaconBlockEntity` 等）。所以判定缺口只应依赖第 ① 条的否定形式。

### 2.14 词表提取：1.20.4 的 NBT 词表是**有限且可枚举**的（Spike11）

从本机 yarn 命名的 1.20.4 jar 里，用「常量池 → 字符串字面量」+「只保留真正引用 `NbtCompound`/`NbtElement`/`NbtList` 的类」两步过滤，提取出：

| 项 | 数量 |
|---|---|
| 真正做 NBT 读写的类 | 216（entity 121 / block-entity 34 / item 28 / inventory 5 / nbt 28） |
| 字符串字面量 | 937 |
| key 形态候选 | 655 |

把 655 个候选与 26.1.2 交叉分类：

| 类别 | 判据 | 数量 |
|---|---|---|
| **A** | 26.1.2 里仍有同名字符串字面量 → 大概率仍被读取 | 545 |
| **B** | 现代代码不再有该字面量，但原版 datafixer 提到过 → **疑似**有自动转换 | 94 |
| **C** | 两边都没有 → 疑似缺口，必须自己补规则 | **16** |

C 类完整名单（**候选，需人工核对**）：

    AngryAt  CanDuplicate  CustomDisplayTile  ShotFromCrossbow  TreasurePosX  TreasurePosY  TreasurePosZ
    changeDimension  factor_calculation_data  map_scale_direction  map_to_lock  missingno
    reloading  reposition  textures  undefined

其中 `AngryAt`（生物愤怒目标）、`CustomDisplayTile`（矿车显示方块）、`ShotFromCrossbow`（弩射出的箭）、
`TreasurePosX/Y/Z`（海豚藏宝点）看起来是**真实 NBT 键**；`missingno` / `undefined` / `textures` / `reloading` 等是噪声，人工核对时剔除即可。

#### ⚠️ B 类不可盲信（重要）

「fixer 提到过这个键」**不等于**「fixer 会转换它」。实测反例：`CustomPotionEffects` 与 `ActiveEffects` 都能在 datafixer 词表里找到，
但真实样本喂进去**原样返回**（`CustomPotionEffects` 还被丢进了 `minecraft:custom_data`）。
所以最终判定必须回到 2.13 之前的方法：**真实样本实测**，词表只用来缩小范围。

#### 这决定了项目的工作量定义

因为输入域封闭（只有 1.20.4 词表），整件事可以做成一张**有限清单**：

    655 个候选键  →  逐项实测  →  两栏结果：
        [原版自动转换]   ← 调用 DataFixers，零代码
        [必须自己补规则] ← RegainNBT 第 2 层的全部工作量

而且这份清单**换 MC 版本时重跑一次就行**（同一套脚本），维护成本是常数级。

## 3. 由证据确定的实现规则

1. **翻译链**：旧 SNBT → CompoundTag →〔按参数类型规范化〕→ 原版 `DataFixer.update(...)` → 序列化回目标版本语法
    - 物品类：注入 `id`（来自命令 token）+ `Count:1b`（缺省）+ 包 `tag:{}` → 输出 `id[key=value,...]`
    - 复合标签类：注入 `id`（来自命令 token，如实体类型）→ 输出 SNBT 复合标签
2. **路由决策**（顺序很重要）：
    - 显式 `old.` 前缀 → 直接走旧路
    - 否则做**类型感知意图检测** → 命中旧特征就走旧路（**不能等异常**）
    - 否则走新版原生路径；**解析失败**再走旧路
    - 两条都失败 → 返回**新版错误**（旧版错误写日志 / `/regainnbt why`）
3. **必须自建的部分**（原版不提供）：
    - ID 改名表（命令 token + NBT 校验兜底）
    - 意图检测器（键名 + 值类型）
    - 参数类型分派 / 片段定位（Brigadier 节点类型 + 配对括号扫描）
    - 解析结果缓存（命令方块 20Hz 场景）
4. **不需要写的部分**：组件映射表、文本组件转换、未知键兜底、属性字段迁移、物品/方块实体递归转换 —— 全部由原版 DFU 提供。

---

## 4. 工具链事实（影响工程配置）

| 事实 | 影响 |
|---|---|
| 最新正式版是 **26.3**；1.21.11 是 1.21 线最后一版 | 目标版本要选 |
| **Yarn 映射停在 `1.21.11+build.6`**，26.x 无 Yarn | 26.x 上要用官方映射（mojmap），当前 `gradle.properties` 里的 `yarn_mappings` 写法失效 |
| **26.x 需要 Java 25**；1.20.5+ 需要 Java 21；1.20.4 是 Java 17 | 一个 jar 无法同时覆盖 1.20.4 与 1.20.5+；建议最低只发 ≥1.20.5 |
| 26.3 的 version JSON 只暴露 `client`/`server`（无 mappings 字段） | 26.3 未逐一复核，见风险 |
| 当前仓库 `build.gradle` 仍指向 1.20.4 / Yarn / Java 17 | 需要改造成现代目标 |

---

## 5. 风险与未验证项

| 项 | 状态 | 处理 |
|---|---|---|
| 26.3 逐条复核 | ⚠️ 未做（官方映射未通过 version JSON 暴露） | 同属 26.x 线，M0 第一步在工程里复核 |
| 端到端起服（真实 server + 命令方块） | ⚠️ 未做（headless 已到极限） | M0 用 Loom `runServer` 验证 |
| `/data get/modify` 的 NBT 路径（如 `tag.display.Name`） | ⚠️ 未测 | 预期「能解析但取不到值」= 静默失败，需单独处理或至少告警 |
| 数据包 function 文件（不走字符串入口） | ⚠️ 未测 | 需挂函数加载流程，独立里程碑 |
| `/execute ... run` 嵌套、`/loot`、`/item` | ⚠️ 未测 | 同一机制，M1 扩展参数类型覆盖 |
| 性能（双解析 + DFU 开销） | ⚠️ 未测 | 加缓存；DFU 首次调用有初始化成本，需预热 |
| DFU fixer 静默 no-op | 🔴 **已确认存在** | 规范化器必须保证输入形状；转换结果要断言 `components` 是否出现，异常时告警 |

---

## 6. 下一步

**M0（钉子，目标 1–2 天）**

1. 把工程改造成现代目标版本（建议 **26.3**，官方映射 + Java 25 toolchain），能 build 通过。
2. 复核 26.3 上 4 个 API 与 26.1.2 是否一致（`Commands#performPrefixedCommand`、`DataFixers#getDataFixer`、`References.ITEM_STACK/ENTITY/BLOCK_ENTITY`、`ItemArgument`）。
3. 用 Mixin 挂 `performPrefixedCommand`，搭出「显式 old. → 意图检测 → 新版 → 旧版 → 双错误」骨架。
4. `runServer` 起真实服务器，用 `/give`、`/summon`、`/setblock` 三条真实旧指令验收。

**M1（翻译器成型）**

- 参数类型分派 + 片段定位（Brigadier 节点驱动）
- ID 改名表 + 结果校验
- 意图检测器（数据驱动，可被数据包覆盖）
- 缓存 + `/regainnbt why` 诊断

---

## 7. 自动标注 `old.` 的落地设计（挂载点已验证）

需求：命令方块里的旧指令被判定为旧版后，**回写**成 `old.` 前缀，后续直接走旧路、不再重复判定。

### 7.1 挂载点：`BaseCommandBlock#performCommand` ✅ 已验证存在

26.1.2 实测该类的公开 API（官方映射名）：

    public java.lang.String getCommand()
    public void setCommand(java.lang.String)
    public boolean performCommand(net.minecraft.server.level.ServerLevel)   <-- 命令方块执行漏斗
    public abstract CommandSourceStack createCommandSourceStack(ServerLevel, CommandSource)

`performCommand` 里既有原始命令字符串（`getCommand()`），又有写回能力（`setCommand()`），且命令方块与命令方块矿车共用这个基类。
**不需要**从 `CommandSourceStack` 反查方块实体（它的 `source` 字段没有公开反查路径），所以挂这一层是最干净的。

    // 伪代码
    @Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    void regainnbt$autoMark(ServerLevel level, CallbackInfoReturnable<Boolean> cir) {
        String cmd = getCommand();
        if (cmd.startsWith("old.")) { /* 已标记，直接放行 */ return; }
        Decision d = RegainNBT.decide(cmd);            // 意图检测 + 新版解析尝试
        if (d == Decision.LEGACY) {
            setCommand("old." + cmd);                 // 回写方块实体（幂等）
            // 标记 dirty，让区块保存
        }
        // 继续按翻译后的字符串执行
    }

### 7.2 三种来源的处理方式

| 来源 | 能否回写 | 方案 |
|---|---|---|
| **命令方块 / 命令方块矿车** | ✅ 能 | 挂 `BaseCommandBlock#performCommand`，判定为旧版后 `setCommand("old." + cmd)` |
| **聊天 / RCON** | ❌ 没有持久载体 | 用运行时 LRU 缓存（命令文本 → 路径），效果等同：同一条命令第二次不再判定 |
| **数据包 function 文件** | ❌ 只读资源，且改写会破坏数据包 | **加载期逐行翻译**，根本不需要标记 |

### 7.3 必须注意的三个副作用（要写进文档）

1. **存档会绑定本模组**：带 `old.` 的命令方块一旦离开 RegainNBT（比如换回原版服），`/old.give` 就是未知指令。
   → 需要配置开关；建议同时提供 `/regainnbt strip` 一键把 `old.` 去掉、还原成原版可读的形态。
2. **写入放大**：只在「判定结果发生变化」时回写（先比较再 `setCommand`），不要每次执行都写方块，否则高频命令方块会一直弄脏区块。
3. **只读世界/权限问题**：写入失败要优雅降级（本次照常翻译执行），不要因为回写失败就让命令报错。

### 7.4 为什么这个设计值得做（真正的价值不是性能）

判定本身很便宜（一次字符串扫描 + 可能一次解析），省下的性能可以忽略。`old.` 的真正价值是：

- **锁定语义**：一旦标记，这条命令永远走旧路。以后意图检测规则再怎么调整，都不会把已经跑通的旧指令误判成新版语法。
- **可审计**：服务器管理员能直接在命令方块里看到「哪些指令是被翻译过的」，而不是靠日志猜。
- **可回滚**：显式标记 = 可以一键剥离，风险可控。

## 附：如何复现本次验证

    # 1. 用 26.1.2 官方映射 jar + JDK25 组 classpath（脚本见 spike/README.md）
    # 2. 编译
    %USERPROFILE%\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2\bin\javac -cp "spike/libs/*" -d spike/out spike/src/Spike*.java
    # 3. 运行（Spike1=DFU 转换矩阵，Spike3=解析链路，Spike4=ID/意图，Spike5=收口，Spike6=完整正确链路）
    %USERPROFILE%\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2\bin\java -cp "spike/out;spike/libs/*" Spike6

一键脚本：`powershell -ExecutionPolicy Bypass -File spike\run.ps1 -Main Spike6`（自动重建 classpath、编译、运行）
