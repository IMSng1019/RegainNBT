# RegainNBT 开发交接 Prompt

> 用法：把「复制从这里开始」到文末的内容**整段**粘贴给新会话的第一个消息。
> 本文件本身也放在仓库里，新会话可以直接读：docs/handoff-prompt.md

---

<!-- ==================== 复制从这里开始 ==================== -->

# 任务：实现 RegainNBT（Minecraft 服务端模组）

工作目录：`J:\mc\RegainNBT`

## 0. 开工前必做（不要跳过）

1. **完整阅读 `docs/feasibility-report.md`**（上一轮的验证报告，所有结论都有实测证据，不要重新验证已经验证过的东西）。
2. 阅读 `spike/README.md` 了解如何复现验证程序（`spike/run.ps1 -Main SpikeN`）。
3. **向用户确认这 3 件事**，得到答复前不要写业务代码：
   - **目标 MC 版本**：默认建议 **26.3**（最新正式版）。备选 1.21.11。
     - 26.x 用**官方映射（mojmap）**、需要 **Java 25**、**Yarn 已停更**（最后一个是 1.21.11+build.6）
     - 1.21.11 可用 Yarn + Java 21，生态更成熟
   - **加载器**：默认 Fabric（当前仓库就是 Fabric 模板；本模组**只需服务端**）
   - **命令方块自动回写 `old.` 前缀**：默认开启，但会让存档绑定本模组，需用户确认

## 1. 项目定义（不要扩大范围）

**RegainNBT = 把原版只给存档做的那次数据迁移，补到指令字符串上。**

- 目标：让**为 1.20.4 写的指令**（聊天 / 命令方块 / RCON / 数据包函数）在新版服务端上仍然生效。
- 输入域是**封闭**的：只有 1.20.4 的词表（NBT 键 + 物品/方块 ID + 语法点）。不需要跟踪新版本新增了什么。
- 输出端必须是目标版本的语法，但**那由原版 DataFixerUpper 负责**，我们只负责"接线"。
- **不做**：旧客户端连新服务端（那是协议兼容，另一个项目）；旧存档加载（原版自带）。

## 2. 已实测确认的事实（直接采信，不要重新验证）

### 2.1 翻译器不用自己写：调用原版 DFU

    DataFixers.getDataFixer().update(
        References.ITEM_STACK,                       // 或 ENTITY / BLOCK_ENTITY
        new Dynamic<>(NbtOps.INSTANCE, compoundTag),
        3700,                                        // 1.20.4 的数据版本
        SharedConstants.WORLD_VERSION                // 26.1.2 = 4790
    ).getValue();

实测：物品用例 **27/27 全覆盖**（附魔 / 名字 / Lore / 属性 / 烟花 / 容器 / 成书 / 盔甲纹饰 / 地图 / 可疑 stew…），
连"未知自定义键自动落到 `minecraft:custom_data`"都做了。**不要自己写组件映射表。**

### 2.2 🔴 三个必须做的规范化（漏一个就**静默不转换**，不报错）

原版 fixer 不认得的输入会**原样返回**。实测：

| 输入 | 结果 |
|---|---|
| `/give` 的 NBT 直接丢给 ITEM_STACK | ❌ 原样返回 |
| 补 `id` | ❌ 仍不转换 |
| 补 `id` + `Count:1b` | ❌ 仍不转换 |
| **补 `id` + `Count:1b` + 把命令里的 NBT 包进 `tag:{}`** | ✅ 成功 |

- **物品类（/give、/item）**：必须构造 `{id:"<命令里的物品ID>", Count:1b, tag:<命令里的NBT>}`
- **复合标签类（/summon、/data、/setblock）**：必须注入 `id`（实体类型来自命令参数），否则 ENTITY 修复同样静默 no-op
- 转换后**断言 `components` / `equipment` 是否真的出现**，没出现要告警（这是"验证后采用"的实现）

### 2.3 🔴 静默陷阱：非物品 NBT 在新版下"解析成功"

| 参数类型 | 受影响指令 | 旧写法的反应 | 异常回退能救吗 |
|---|---|---|---|
| `item.ItemArgument` | `/give`、`/item ... with` | 🔴 硬报错 | ✅ 能 |
| `CompoundTagArgument` | `/summon`、`/data merge` | ⚠️ **解析通过** | ❌ 不能 |
| `blocks.BlockStateArgument` | `/setblock`、`/fill` | ⚠️ **解析通过** | ❌ 不能 |
| `NbtPathArgument` | `/data get/modify`、`/execute if data` | ⚠️ 能解析、取不到值 | ❌ 不能 |
| `EntityArgument` 的 `nbt=` | `/kill @e[nbt=...]` | ⚠️ **解析通过**（实测） | ❌ 不能 |
| `ComponentArgument` | `/tellraw`、`/title` | ⚠️ 带引号的 JSON 静默变字面量（`'{"text":"hi"}'` → 显示原始 JSON） | ❌ 不能 |

**所以必须有"旧版意图检测"，不能只靠异常回退。**这就是路由里最重要的一环。

### 2.4 意图检测必须"类型感知"

| NBT | 判定 | 依据 |
|---|---|---|
| `{CustomName:'{"text":"Bob"}'}` | LEGACY | 值是**字符串**（JSON 文本组件） |
| `{CustomName:{text:"Bob"}}` | MODERN | 值是复合标签 |
| `{HandItems:[{}]}` | LEGACY | 旧装备键 |
| `{equipment:{...}}` | MODERN | 新装备键 |

**只按键名判会误判**（`CustomName` 两代都有）。

### 2.5 原版**不能**全包，有真实缺口（RegainNBT 的核心资产在这里）

判定标准 = ① 该键在当代代码里还有没有读取方（精确 UTF8 常量扫描，0 引用 = 确定死了）② DFU 有没有转换。
**两条同时成立 = 真缺口。**

已确认的真缺口（旧键没人读 + DFU 不动 → 数据静默丢失）：

| 键 | 后果 |
|---|---|
| `CustomPotionEffects`（物品） | 药水/药水箭的**自定义效果全丢**（被丢进 custom_data 就不管了；现代键是 `potion_contents.custom_effects`） |
| `ActiveEffects`（实体） | 怪物**状态效果全丢**（现代键 `active_effects`，LivingEntity 在读） |
| 信标 `Primary` / `Secondary` | 信标效果丢失（`Levels` 还活着，只有这两个死了） |

词表审计规模（Spike11）：1.20.4 里真正做 NBT 读写的类 216 个 → key 形态候选 **655** 个 →
A 类 545（仍被读取）/ B 类 94（fixer 提到过）/ **C 类 16（疑似缺口）**：
`AngryAt` `CanDuplicate` `CustomDisplayTile` `ShotFromCrossbow` `TreasurePosX/Y/Z` `changeDimension`
`factor_calculation_data` `map_scale_direction` `map_to_lock` `missingno` `reloading` `reposition`
`textures` `undefined`（后几个是噪声，人工剔除）

**⚠️ B 类不可盲信**："fixer 提到过"≠"fixer 会转换"——`CustomPotionEffects` 和 `ActiveEffects` 都在 fixer 词表里，
但实测原样返回。**最终判定必须用真实样本实测。**

### 2.6 ID 改名只覆盖一半，必须自建表

| 位置 | DFU 是否处理 |
|---|---|
| 命令参数里的 ID（`/give Steve minecraft:grass`） | ❌ 完全不处理 → `Unknown item` |
| NBT 内部的 `id`（`scute`、`chain`） | ✅ 转了 |
| NBT 内部的 `id`（`grass`） | ❌ **没转**（已不是合法 ID） |

做法：转换后用 `BuiltInRegistries.ITEM.containsKey(id)` 校验，不合法才查改名表，查不到再报错。

### 2.7 旧键残留无害（不用写清理逻辑）

精确常量池扫描：`HandItems` / `ArmorItems` 在 26.1.2 里**非 datafixer 引用数 = 0**；
`Attributes` 的引用全是 fixer 和 JSON-RPC 注解。游戏只读 DFU 新写的 `equipment` / `attributes`。

## 3. 已确定的架构

    BaseCommandBlock#performCommand ─┐
    Commands#performPrefixedCommand ─┼─→ 路由（按顺序）
    数据包函数加载 ─────────────────┘
         │
         ├─ 0) 显式 old. 前缀 ─────────────→ 直接走旧路
         ├─ 1) 类型感知的旧版意图检测命中 ─→ 走旧路（不能等异常！）
         ├─ 2) 新版原生解析 ──────────────→ 成功就执行
         └─ 3) 新版解析失败 ──────────────→ 走旧路
                    ↓ 旧路 = 
              规范化（id / Count / tag 包装 或 注入 id）
                    ↓
              原版 DataFixers.update(...)      ← 第 1 层：调用，零维护
                    ↓
              我们自己的补丁规则               ← 第 2 层：覆盖原版的洞（★核心资产）
                    ↓
              序列化回目标版本语法 → 原版解析器 reparse → **验证后采用**
                    ↓
              两条路都失败 → 返回**新版**错误（旧版错误写日志 + /regainnbt why）

**自动标注 `old.`**（挂载点已验证存在）：

    public String net.minecraft.world.level.BaseCommandBlock#getCommand()
    public void   net.minecraft.world.level.BaseCommandBlock#setCommand(String)
    public boolean net.minecraft.world.level.BaseCommandBlock#performCommand(ServerLevel)   ← 挂这里

- 命令方块/矿车命令方块：判定为旧版后 `setCommand("old." + cmd)`，**只在判定变化时写**，写失败要优雅降级
- 聊天/RCON：没有持久载体 → 用运行时 LRU 缓存（命令文本 → 路径）
- 数据包函数：只读资源 → **加载期逐行翻译**，不需要标记
- 提供 `/regainnbt strip` 把 `old.` 剥掉（存档可回滚）

## 4. 硬性约束（违反会静默出错）

1. **回退只能发生在 parse 阶段**。Brigadier 解析失败没有副作用；绝不能在 execute 阶段重试（会执行两次）。
2. **不能用正则替换 NBT**。要真解析（`TagParser.parseCompoundFully`）+ 配对括号扫描定位片段（处理引号、`[I;...]` 数组）。
3. **不要复制原版源码进仓库或 jar**。调用 API；需要阅读就用 Loom 的 `genSources` 在本地生成；需要访问 package-private 成员用 Mixin/Accessor。
   （技术理由：DFU fixer 不是独立可用的类，它依赖整套 schema 注册链，搬过去不会工作；而且会给每次版本升级增加维护负担。）
4. **意图检测不能只按键名**，必须看值类型。
5. **转换必须"验证后采用"**：reparse 通过 + 关键结果（components/equipment/attributes）真的出现，才替换原命令。
6. **必须自建**：ID 改名表、意图检测器、参数类型分派 + 片段定位、翻译缓存、`/regainnbt why` 诊断。

## 5. 已确认可用的 API（26.1.2 官方映射名，26.3 上第一步复核）

    net.minecraft.commands.Commands#performPrefixedCommand(CommandSourceStack, String)   // 聊天/命令方块/RCON 公共入口
    net.minecraft.commands.Commands#performCommand(ParseResults, String)
    net.minecraft.commands.Commands#validateParseResults(ParseResults) / #getParseException(ParseResults)
    net.minecraft.util.datafix.DataFixers#getDataFixer()
    net.minecraft.util.datafix.fixes.References#ITEM_STACK / ENTITY / BLOCK_ENTITY
    net.minecraft.nbt.TagParser#parseCompoundFully(String)
    net.minecraft.core.RegistryAccess#fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
    net.minecraft.data.registries.VanillaRegistries#createLookup()
    net.minecraft.commands.CommandBuildContext#simple(HolderLookup.Provider, FeatureFlagSet)
    net.minecraft.world.item.ItemStack#CODEC / #MAP_CODEC
    net.minecraft.commands.arguments.item.ItemArgument#item(CommandBuildContext) / #getItem(ctx, name)
    net.minecraft.commands.arguments.CompoundTagArgument#compoundTag()

注意：26.x 里 `EntityArgument.parse` 会调用 `EntitySelectorParser.allowSelectors(Object)`，**解析期就依赖 source**。
重试必须传**真实的 CommandSourceStack**（我们本来就有），不要自造。

## 6. M0 任务与验收标准

1. 把 Gradle 工程改造到目标版本（当前是 **1.20.4 / Yarn / Java 17 的空模板**，必须改）：
   改 `gradle.properties` + `build.gradle`；删除 `ExampleMixin` / `ExampleClientMixin` / `regainnbt.client.mixins.json`（本模组服务端侧）；
   如走 26.x 需 Java 25 toolchain + 官方映射。
2. 复核 26.3 上第 5 节的 API 是否与 26.1.2 一致。
3. 挂 `performPrefixedCommand`，实现路由骨架（0→1→2→3）+ 缓存 + 日志。
4. 挂 `BaseCommandBlock#performCommand`，实现 `old.` 自动回写。
5. **`runServer` 起真实服务器验收**（headless 环境做不到端到端，见报告 §2.8 的说明）：
   - `/give Steve diamond_sword{Enchantments:[{id:"minecraft:sharpness",lvl:5}],display:{Name:'{"text":"Excalibur"}'}}` → 拿到带附魔+名字的剑
   - `/summon minecraft:zombie ~ ~ ~ {HandItems:[{id:"minecraft:diamond_sword",Count:1b,tag:{Enchantments:[...]}}]}` → 僵尸**手上有剑**（不是空手）
   - `/setblock ~ ~ ~ minecraft:chest{Items:[{Slot:0b,id:"minecraft:diamond",Count:3b}]}` → 箱子里有钻石
   - 命令方块里放第一条命令 → 执行一次后，方块里应变成 `old.give ...`
   - 对照组：现代语法命令 `/give Steve diamond_sword[custom_name="X"]` 必须**不受影响**

## 7. 测试策略

- 黄金用例：把 `spike/src/Spike6.java`、`Spike9.java` 里的用例搬成 JUnit（可以在测试里 `Bootstrap.bootStrap()`，不需要起服务器）
- 每个目标版本跑一遍"双条件审计"（报告 §2.13/§2.14 的方法），自动列出新增缺口
- 意图检测要有**误判回归**：现代语法命令不能被误判成旧版

## 8. 不要做的事

- ❌ 不要重新推导/验证报告里已有实测证据的结论（先读 `docs/feasibility-report.md`）
- ❌ 不要自己写组件映射表（原版 DFU 已经有了）
- ❌ 不要用正则解析/替换 NBT
- ❌ 不要复制原版源码进仓库或 jar
- ❌ 不要在没和用户确认目标版本前改 Gradle 配置

## 9. 当前仓库状态

    J:\mc\RegainNBT\
      build.gradle / gradle.properties / settings.gradle   ← 1.20.4 Fabric 空模板，待改造
      src/main/java/regainnbt/modid/RegainNBT.java          ← 模板主类
      src/main/java/regainnbt/modid/mixin/ExampleMixin.java ← 待删
      src/client/**                                         ← 待删（服务端模组）
      docs/feasibility-report.md                            ← ★完整验证报告，先读这个
      docs/handoff-prompt.md                                ← 本文件
      spike/README.md + run.ps1 + src/Spike1..11.java       ← 验证程序（可复现）
      .github/workflows/build.yml                           ← 已有 CI

`spike/libs` 与 `spike/out` 是生成物（已在 .gitignore），`spike/run.ps1` 会自动重建。

<!-- ==================== 复制到这里结束 ==================== -->
