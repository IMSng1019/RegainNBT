# RegainNBT

把**为 1.20.4 写的指令**，在 **Minecraft 26.3** 服务端上原样跑通。

> **一句话定义**：原版只给存档做了那次数据迁移，RegainNBT 把同一次迁移补到**指令字符串**上。

聊天 / 命令方块 / RCON / 数据包函数里的旧写法 NBT，会被透明地翻译成当代组件语法；
命令方块里的旧指令还会被自动标注 `old.` 前缀，方便审计与一键回滚。

    /give Steve diamond_sword{Enchantments:[{id:"minecraft:sharpness",lvl:5}],display:{Name:'{"text":"Excalibur"}'}}
    # 实际执行：
    /give Steve minecraft:diamond_sword[minecraft:enchantments={"minecraft:sharpness":5},minecraft:custom_name={text:"Excalibur"}]

---

## 支持范围（输入域是封闭的：只有 1.20.4 的词表）

| 参数类型 | 受影响指令 | 旧写法的反应 | RegainNBT 的处理 |
|---|---|---|---|
| `ItemArgument` | /give、/item ... with | 硬报错 | 解析失败 → 回退翻译 |
| `CompoundTagArgument` | /summon、/data merge | **解析通过但静默失效** | 意图检测 → 提前走旧路 |
| `BlockStateArgument` | /setblock、/fill | **解析通过但静默失效** | 意图检测 → 提前走旧路 |
| `NbtPathArgument` | /data get/modify、/execute if data | 能解析、取不到值 | 检测 + 告警（路径不自动改写） |
| `EntityArgument` 的 `nbt=` | /kill @e[nbt=...]、/execute if entity | **解析通过但静默失效** | 意图检测 → 提前走旧路；谓词里的旧键会被删掉（见下） |
| `ItemPredicateArgument` | /clear、/execute if items | 旧 ID / 旧 NBT 硬报错 | 纯 ID 改名 + `id{旧NBT}` 走完整物品链 |
| `ComponentArgument` | /tellraw、/title | 引号 JSON 变成字面量 | 意图检测 → 转 SNBT 组件 |

> **谓词 vs 写数据**：`nbt={...}` 这类**谓词**要求所有列出的键都匹配。26.3 的实体 NBT 里已经没有 `HandItems`/`ArmorItems`/`Attributes` 了，
> 所以翻译谓词时 RegainNBT 会**删掉这些已无读取方的旧键**（仅当对应的现代键确实生成出来时才删，否则会把「永不命中」变成「匹配所有实体」），
> `nbt={HandItems:[...]}` 因此能真正匹配到现代实体。`/summon`、`/data merge` 这类**写数据**的载荷则保留旧键（报告 §2.5：残留无害）。

翻译本身**不是**我们写的映射表：调用原版 DataFixerUpper（`References.ITEM_STACK / ENTITY / BLOCK_ENTITY`）。
RegainNBT 负责的是**接线**（规范化输入形状、按参数类型定位片段、序列化回目标语法、验证后采用）
以及**原版覆盖不到的洞**（见下）。

### 原版 DFU 的真实缺口（RegainNBT 第 2 层补丁）

| 键 | 后果 | 现代键 |
|---|---|---|
| `CustomPotionEffects`（物品） | 自定义药水效果全丢（DFU 把它塞进 `custom_data` 就不管了） | `minecraft:potion_contents.custom_effects` |
| `ActiveEffects`（实体） | 状态效果全丢 | `active_effects` |
| 信标 `Primary` / `Secondary` | 信标效果丢失 | `primary_effect` / `secondary_effect` |

> 注：这三组旧键属于 **1.20.2 之前**的写法（真实 1.20.4 已用现代形状），但对「更老的命令 / 存档里的旧 NBT」仍然必须救 ——
> 否则数据会被静默丢弃。规则对现代形状是安全 no-op。数字化的 mob effect id（1..33）也会自动映射成名空间 id。

### ID 改名

命令 token 里的 ID 原版**完全不处理**（`/give Steve grass` → Unknown item）。解析顺序：

1. `BuiltInRegistries` 校验（合法就直接用）
2. 原版 DFU 的 `ITEM_NAME` / `BLOCK_NAME` 改名探测（scute → turtle_scute、chain → iron_chain）
3. 模组内置表（原版漏掉的部分，例如 grass → short_grass）
4. 查不到 → 拒绝翻译并报错（绝不静默返回原值）

---

## 安装

1. Minecraft **26.3** 服务端 + **Fabric Loader ≥ 0.19.5** + **Fabric API**（必需）
2. 把 `regainnbt-<version>.jar` 和 `fabric-api-<...>+26.3.jar` 放进 `mods/`
3. 需要 **Java 25**

客户端可选：单个玩家的存档（集成服务器）同样生效。

## 配置 `config/regainnbt.json`

    {
      "enabled": true,                  // 总开关，false = 完全不介入
      "autoMarkCommandBlocks": true,    // 命令方块里判定为旧版的指令自动回写成 old. 前缀
      "translateDataPackFunctions": true, // 数据包函数加载期逐行翻译
      "logTranslations": true,
      "logFailures": true,
      "cacheSize": 4096,
      "sourceDataVersion": 3700,        // 1.20.4
      "patchRules": {},                 // 例：{"beacon_effects": false}
      "disabledCommands": []            // 例：["give"]，不接管的指令根名
    }

## 指令

| 指令 | 说明 |
|---|---|
| `/regainnbt status` | 运行状态、缓存、ID 表、补丁规则、数据版本 |
| `/regainnbt why <命令>` | **诊断**：命中路径 / 判定依据 / 每一步 / 警告 / 翻译结果 / 是否采纳 / 两条路都失败时的原始报错。不执行命令 |
| `/regainnbt translate <命令>` | 只翻译并回显，不执行 |
| `/regainnbt strip [半径]` | 把命令方块里的 `old.` 前缀剥掉（存档可回滚） |
| `/regainnbt reload` | 重载配置、清空路由缓存 |

（全部需要 OP / 权限等级 2）

## `old.` 自动标注：为什么值得做

判定本身很便宜，`old.` 的真正价值是：**锁定语义**（标记后永远走旧路，不受检测规则调整影响）、
**可审计**（管理员直接在命令方块里看到哪些被翻译过）、**可回滚**（`/regainnbt strip`）。

⚠️ 代价：带 `old.` 的命令方块离开 RegainNBT 后（换回原版服）会是未知指令。
所以这是一个配置开关（默认开启），并且提供了 strip。只在判定结果**发生变化**时回写，不会每 tick 弄脏区块。

## 明确不做

- 旧客户端连新服务端（那是协议兼容，另一个项目）
- 旧存档加载（原版自带）
- 跟踪新版本**新增**的语法（输入域只有 1.20.4 的词表）
- 复制/内置原版源码：全部通过调用 API 实现

---

## 开发

    # 构建（需要 JDK 25；Gradle 会自动用 toolchain）
    .\gradlew.bat build

    # 测试（129 个 JUnit，headless，不启服）
    .\gradlew.bat test

    # 端到端验收（真实 Fabric 26.3 服务端；首次会下载服务端）
    powershell -ExecutionPolicy Bypass -File tools\acceptance\run-acceptance.ps1

    # ID 改名表离线审计 / 重新生成（换 MC 版本时跑一次）
    powershell -ExecutionPolicy Bypass -File tools\audit\generate-id-renames.ps1

    # 快速编译检查（不跑 Gradle）
    .\gradlew.bat dumpClasspath --no-configuration-cache
    powershell -ExecutionPolicy Bypass -File tools\dev-compile.ps1

### 目录

    src/main/java/regainnbt/
      core/      路由（0-1-2-3）、缓存、old. 回写
      detect/    类型感知的旧版意图检测（键名 + 值形状 + Brigadier 节点）
      translate/ 规范化 + 原版 DFU 调用 + 序列化 + 验证后采用
      patch/     第 2 层补丁规则（原版 DFU 的洞）
      ids/       ID 改名（注册表 → DFU → 内置表）
      command/   /regainnbt 诊断与管理
      function/  数据包函数加载期翻译
      mixin/     Commands / BaseCommandBlock / CommandFunction 注入点
    docs/        可行性报告、架构与开发约定、验证记录
    spike/       上一轮的 11 个可行性验证程序（可复现）
    tools/       构建/测试/验收/审计脚本

## 文档

- [docs/architecture.md](docs/architecture.md) —— 架构、6 条硬性约束、26.3 API 备忘、文件所有权
- [docs/verification-26.3.md](docs/verification-26.3.md) —— 本版本在 26.3 上的实测验证记录
- [docs/feasibility-report.md](docs/feasibility-report.md) —— 上一轮的可行性验证报告（含全部实测证据）

## 许可

MIT
