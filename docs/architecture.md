# RegainNBT 架构与开发约定（26.3 / Fabric）

> 面向本仓库的实现者（含 AI 子代理）。先读 [feasibility-report.md](feasibility-report.md) 的结论，**不要重新验证已有实测证据**。

## 0. 一句话定义

**RegainNBT = 把原版只给存档做的那次数据迁移，补到指令字符串上。**
让为 1.20.4 写的指令（聊天 / 命令方块 / RCON / 数据包函数）在 26.3 服务端上仍然生效。
输入域封闭（只有 1.20.4 的词表），输出端由**原版 DataFixerUpper** 负责，我们只负责接线。

不做：旧客户端连新服务端（协议兼容）、旧存档加载（原版自带）。

## 1. 环境事实（已实测，2026-10-04）

| 项 | 值 |
|---|---|
| 目标版本 | **26.3**（最新正式版，2026-09-15） |
| 加载器 | Fabric，loader 0.19.5，Loom **1.18.2** |
| 语言级别 | **Java 25**（`options.release = 25`） |
| 映射 | **不需要 mappings**：26.x 的 Minecraft jar 官方已反混淆，Loom 用 `implementation "com.mojang:minecraft:26.3"` |
| Fabric API | 0.161.0+26.3 |
| 数据版本 | 1.20.4 = 3700，26.3 用 `SharedConstants.WORLD_VERSION` |

构建：

    $env:JAVA_HOME = "$env:USERPROFILE\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2"
    .\gradlew.bat build --console=plain

快速编译检查（不要并发跑 Gradle，会抢锁）：

    .\gradlew.bat dumpClasspath --no-configuration-cache   # 只需一次
    powershell -ExecutionPolicy Bypass -File tools\dev-compile.ps1

## 2. 路由（硬性顺序）

    BaseCommandBlock#performCommand ─┐
    Commands#performPrefixedCommand ─┼─→ 路由
    数据包函数加载 ─────────────────┘
         ├─ 0) 显式 old. 前缀 ─────────────→ 旧路
         ├─ 1) 类型感知旧版意图检测命中 ──→ 旧路（不能等异常！）
         ├─ 2) 新版原生解析/校验 ─────────→ 成功就执行
         └─ 3) 新版解析/校验失败 ─────────→ 旧路
                    ↓ 旧路
              规范化（id / Count / tag 包装 或 注入 id）
                    ↓
              原版 DataFixers.update(...)      ← 第 1 层：调用，零维护
                    ↓
              我们自己的补丁规则                ← 第 2 层：覆盖原版的洞（★核心资产）
                    ↓
              序列化回目标版本语法 → 原版 reparse → **验证后采用**
                    ↓
              两条路都失败 → 返回**新版**错误（旧版错误写日志 + /regainnbt why）

两个注入点（见 `regainnbt/mixin/CommandsMixin.java`）：

- `Commands#performPrefixedCommand(HEAD)` —— 未 parse。只做「显式 old.」+「文本级意图检测」。
- `Commands#performCommand(HEAD)` —— 已 parse。节点驱动意图检测 + 原版校验失败时的回退。

## 3. 六条硬性约束（违反了会静默出错）

1. **回退只能发生在 parse 阶段**（Brigadier 解析失败无副作用）；绝不能在 execute 阶段重试，否则执行两次。
2. **不能用正则解析/替换 NBT**：用 `TagParser.parseCompoundFully` + 配对括号扫描（处理引号、`[I;...]` 数组）。
3. **不要复制原版源码进仓库或 jar**：调用 API；需要阅读用 Loom 的 `genSources`，要访问 package-private 用 Mixin/Accessor。
4. **意图检测不能只按键名**，必须看值类型（`CustomName` 两代都有：字符串=旧，复合=新）。
5. **转换必须「验证后采用」**：reparse 通过 + 关键结果（components / equipment / active_effects）真的出现，才替换原命令。
6. **必须自建**：ID 改名表、意图检测器、参数类型分派 + 片段定位、翻译缓存、`/regainnbt why` 诊断。

## 4. 原版 DFU 的三个静默陷阱（报告 2.2 / 2.3）

- 物品：必须构造 `{id:"<命令里的物品ID>", Count:1b, tag:<命令里的NBT>}`，否则**原样返回、不报错**。
- 实体/方块实体：必须注入 `id`（实体类型来自命令参数），否则 ENTITY 修复同样静默 no-op。
- 非物品 NBT 在新版下**解析成功**（`/summon`、`/setblock`、`nbt=`、`/tellraw` 的引号 JSON）→ 只能靠意图检测发现。

## 5. 已确认的真缺口（第 2 层补丁的全部工作量起点）

| 键 | 后果 | 现代键 |
|---|---|---|
| `CustomPotionEffects`（物品） | 药水自定义效果全丢（DFU 把它塞进 custom_data） | `minecraft:potion_contents.custom_effects` |
| `ActiveEffects`（实体） | 怪物状态效果全丢 | `active_effects` |
| 信标 `Primary` / `Secondary` | 信标效果丢失 | 见 26.3 `BeaconBlockEntity` 的读取键 |

## 6. 26.3 API 备忘（已用 javap 复核）

    net.minecraft.commands.Commands#performPrefixedCommand(CommandSourceStack, String)
    net.minecraft.commands.Commands#performCommand(ParseResults<CommandSourceStack>, String)
    net.minecraft.commands.Commands#validateParseResults(ParseResults) / #getParseException(ParseResults)
    net.minecraft.commands.Commands#getDispatcher()
    net.minecraft.commands.Commands#hasPermission(PermissionCheck)   // LEVEL_GAMEMASTERS 等
    net.minecraft.util.datafix.DataFixers#getDataFixer()
    net.minecraft.util.datafix.fixes.References#ITEM_STACK / ENTITY / BLOCK_ENTITY / ITEM_NAME / BLOCK_NAME
    net.minecraft.nbt.TagParser#parseCompoundFully(String)
    net.minecraft.commands.arguments.item.ItemArgument#item(CommandBuildContext) / #getItem(ctx, name)
    net.minecraft.commands.arguments.CompoundTagArgument#compoundTag()
    net.minecraft.world.item.ItemStack#CODEC / #MAP_CODEC
    net.minecraft.SharedConstants#WORLD_VERSION
    net.minecraft.resources.Identifier                     // 26.x 里叫 Identifier，不是 ResourceLocation
    net.minecraft.server.permissions.PermissionSet#ALL_PERMISSIONS
    net.minecraft.commands.functions.CommandFunction#fromLines(Identifier, CommandDispatcher, T, List<String>)
    net.minecraft.commands.CommandBuildContext#simple(HolderLookup.Provider, FeatureFlagSet)

注意：26.x 里 `EntityArgument.parse` 会调用 `EntitySelectorParser.allowSelectors(Object)`，**解析期就依赖 source**，
重试必须传**真实的 CommandSourceStack**。

## 7. 代码结构（文件所有权）

    regainnbt/
      RegainNBT.java                 总入口（Lead）
      RegainNBTConfig.java           配置（Lead）
      core/
        Decision / PathKind / RouteResult / TranslationReport / RecursionGuard / TranslationCache
        Router.java                  路由 0-1-2-3（T5）
        CommandBlockMarker.java      old. 回写（T5）
      detect/
        IntentDetector / IntentVerdict / LegacySignatures / NbtFragments   （T2）
      translate/
        LegacyTranslator / DfuTranslator / CommandShape / Normalizer / serializer （T1）
      patch/
        PatchRule / PatchContext / PatchRegistry / 各规则                （T3）
      ids/
        IdRenames（+ 生成脚本）                                          （T4）
      command/
        RegainNBTCommand（/regainnbt why|translate|strip|status|reload） （T6）
      function/
        FunctionTranslator + mixin/functions/CommandFunctionMixin        （T6）
      mixin/
        CommandsMixin / BaseCommandBlockMixin                            （Lead/T5）

## 8. 参考实现（已验证可跑的代码）

`spike/src/Spike6.java` 是**完整正确链路**的可运行版本（/give 旧指令 → 规范化 → DFU → 原版解析 → 真实组件）。
`spike/src/Spike5.java` 有实体注入 id、ID 改名缺口、类型感知检测的原始代码。
一键复现：`powershell -ExecutionPolicy Bypass -File spike\run.ps1 -Main Spike6`
