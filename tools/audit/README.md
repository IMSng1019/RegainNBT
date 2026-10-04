# T4 —— ID 改名表与离线审计

> 对应任务：task-4 «T4 ID 改名表»。运行时代码在 [IdRenames.java](../../src/main/java/regainnbt/ids/IdRenames.java)，
> 生成的表在 [id_renames.json](../../src/main/resources/regainnbt/id_renames.json)。

## 1. 为什么需要这张表（报告 §2.6）

原版 DFU 只覆盖一半（下表为 26.3 实测）：

| 位置 | 原版 DFU 会改名吗 |
|---|---|
| 命令参数里的 ID（`/give Steve minecraft:grass`） | ❌ 完全不处理 → `Unknown item` |
| NBT 内部的 `id`（`{id:"minecraft:scute"}`） | ✅ → `minecraft:turtle_scute` |
| NBT 内部的 `id`（`{id:"minecraft:chain"}`） | ✅ → `minecraft:iron_chain` |
| NBT 内部的 `id`（`{id:"minecraft:grass"}`） | ❌ 仍是 `minecraft:grass`（已不是合法 ID） |

所以运行时按 **注册表 → 原版 DFU → 内置表 → 报错诊断** 的顺序解析，表只维护「原版漏掉的那部分」。

## 2. 运行时接口

```java
IdRenames.load();                                        // 读内置资源（幂等；RegainNBT#onInitialize 已调用）
String item  = IdRenames.resolveItemId(rawId, report);   // null = 解析失败（已写 report.warn），绝不静默返回原值
String block = IdRenames.resolveBlockId(rawId, report);
IdRenames.itemTableSize();  IdRenames.blockTableSize();  // /regainnbt status 用
IdRenames.reload();                                      // /regainnbt reload 用
IdRenames.targetDataVersion();                           // 5023（26.3）；SharedConstants.WORLD_VERSION 在 26.3 已 @Deprecated
// T1 的 DfuTranslator#fixItemName / #fixBlockName 可直接委托给：
IdRenames.dfuFixItemName(id);   // null = 原版 DFU 没改名
IdRenames.dfuFixBlockName(id);
```

解析顺序（源码里的三条路径，探针会打印走的是哪条）：

1. `BuiltInRegistries.ITEM/BLOCK.containsKey(id)` —— 合法就用（零维护，绝大多数情况）；
2. 不合法才问原版 DFU 的 `References.ITEM_NAME` / `BLOCK_NAME`（3700 → `targetDataVersion()`）—— 命中且结果合法就用（零维护）；
3. 都不是才查内置表；
4. 仍失败 → 返回 `null` 并 `report.warn(...)`。

其他行为：

- 省略命名空间：`grass` → `minecraft:grass`（与命令参数一致）；
- `#tag`、空串、语法不合法的 token 都返回 null + 明确诊断；
- 注册表为空（Bootstrap 未运行）时显式报警，而不是把一切都判成不合法；
- 表里的目标 ID 会**再次**用注册表校验，表过期时报警而不是放行。

### DFU 探测的实测细节（26.3）

`References.ITEM_NAME` / `BLOCK_NAME` 是「余项类型」（remainder），输入必须是**裸字符串**的 `Dynamic`：

```java
new Dynamic<>(NbtOps.INSTANCE, StringTag.valueOf("minecraft:scute"))   // -> minecraft:turtle_scute
```

喂复合标签（`{id:"minecraft:grass"}`）会打日志 `Not a string` 并原样返回（实测），所以只能用字符串。
探测成本实测 0.013 ms/次（200 次共 2.5 ms），而且只对「在目标版本里不合法」的 ID 才跑。

## 3. 生成 / 审计脚本

```powershell
powershell -ExecutionPolicy Bypass -File tools\audit\generate-id-renames.ps1
powershell -ExecutionPolicy Bypass -File tools\audit\generate-id-renames.ps1 -SkipDownload   # 用已有缓存
powershell -ExecutionPolicy Bypass -File tools\audit\generate-id-renames.ps1 -Strict         # 有洞时 exit 2（CI 用）
```

流程：

1. **下载名字清单**（缓存在 `build/audit-cache/`，已被 .gitignore 忽略）：

   | 来源 | 用途 |
   |---|---|
   | `misode/mcmeta` 的 `1.20.4-registries/item/data.json`、`block/data.json` | 原版 registries 报告的直接转储 |
   | `PrismarineJS/minecraft-data` 的 `pc/1.20.3/items.json`、`pc/1.20.4/blocks.json` | 社区维护的独立索引 |

   两个来源取并集（写到 `tools/audit/data/names-*.txt`，提交进仓库），脚本会打印差集：
   **两边都是 1312 物品 / 1058 方块，差集为 0**。

2. **跑真实 26.3 代码**（`IdRenameAudit.java`，用 `build/devcp/main.txt` 的完整 classpath + JDK 25）：
   逐个名字先查 `BuiltInRegistries`，不合法再跑 DFU `ITEM_NAME` / `BLOCK_NAME`（3700 → 5023）。

3. **产出**：

   | 文件 | 说明 |
   |---|---|
   | `src/main/resources/regainnbt/id_renames.json` | 运行时内置表（只收「目标版本里不合法且有确定替代名」的条目） |
   | `tools/audit/reports/report.txt` | 人类可读的审计报告（含全部条目与未解决项） |
   | `tools/audit/reports/id_renames.full.json` | 带来源标注（dfu / manual、domain）的完整结果 |
   | `tools/audit/reports/unresolved-*.txt` | 无解的名字 + 候选建议（人工确认后填进 manual-fixes.json） |

### 人工补洞

`tools/audit/manual-fixes.json` 是**唯一**手写的地方：

```json
{ "items":  { "minecraft:grass": "minecraft:short_grass" },
  "blocks": { "minecraft:grass": "minecraft:short_grass" } }
```

脚本会校验目标 ID 在 26.3 注册表里合法；非法、或与 DFU 结果冲突，都会打出来。
**不要手改 `src/main/resources/regainnbt/id_renames.json`**，它每次都会被重新生成。

## 4. id_renames.json 格式（运行时契约）

```json
{
  "format": 1,                       // 格式版本（运行时当前只认 1，多余字段忽略）
  "sourceVersion": "1.20.4 (data version 3700)",
  "targetVersion": "26.3 (data version 5023)",
  "stats": { ... },                  // 仅供人看：条目数、DFU 覆盖数、人工补的条数
  "items":  { "<目标版本里不合法的 ID>": "<目标版本里合法的 ID>" },
  "blocks": { ... }
}
```

约束：键和值都必须是带命名空间的完整 ID；值必须在目标版本注册表里存在（运行时二次校验）。
未知顶层字段会被忽略（向前兼容）。

## 5. 实测结果（2026-10-04，26.3 / 数据版本 5023）

| 项 | 物品 | 方块 |
|---|---|---|
| 1.20.4 域内名字总数 | 1312 | 1058 |
| 26.3 里仍然合法（无需处理） | 1310 | 1057 |
| 不合法 → **原版 DFU 改名成功** | 2（scute、chain） | 1（chain） |
| 不合法 → DFU 改出来仍不合法 | 0 | 0 |
| **DFU 漏掉、必须人工补表** | 1（grass） | 1（grass） |
| 写入内置表的条目 | 3 | 2 |

内置表实际内容：

```json
"items":  { "minecraft:chain": "minecraft:iron_chain",
            "minecraft:grass": "minecraft:short_grass",
            "minecraft:scute": "minecraft:turtle_scute" },
"blocks": { "minecraft:chain": "minecraft:iron_chain",
            "minecraft:grass": "minecraft:short_grass" }
```

### 重要修正：`grass` 不是 1.20.4 的 ID

报告 §2.6 的 `grass` 用例是真的（DFU 确实不改它），但**它不在 1.20.4 的词表里**：

- 1.20.4 的注册表转储里只有 `short_grass`，没有 `grass` —— `grass -> short_grass` 是 **1.20.3** 做的改名；
- 所以「DFU 在 1.20.4 域内漏了 grass」要修正为：**1.20.4 域内原版一个都没漏**（scute / chain 都改了），
  `grass` 属于**更老的输入**（1.20.3 之前的指令 / NBT）。

脚本因此显式增加第二个输入域 `tools/audit/data/extra-legacy-names.txt`：
**1.20.2 有、1.20.4 没有**的名字（自动求差集，实测就是 `grass` 一个，物品与方块各一条）。
这正是内置表里唯一「DFU 漏掉」的条目来源。两个域分开统计，报告里能看到 `domain=1.20.4` / `domain=pre-1.20.3`。

### 维护方式（换 MC 版本时）

1. 重跑 `generate-id-renames.ps1`；
2. 看 `reports/report.txt` 与 `reports/unresolved-*.txt`：新增的洞就是「这次版本更新里原版没跟上的改名」；
3. 人工确认后填 `manual-fixes.json`，再跑一次到无洞（`-Strict` 可让 CI 卡住）；
4. 表本身不用手改，`stats` 里的数字会同步更新。

## 6. 目录内容

| 路径 | 说明 |
|---|---|
| `IdRenameAudit.java` | 审计 / 生成器（用真实 26.3 代码 + 原版 DFU） |
| `generate-id-renames.ps1` | 一键脚本：下载 → 提取名字 → 编译 → 跑审计 → 写表 |
| `run-id-probe.ps1` | T4 验收探针运行器（编译 ids 包 + `scratch/id-renames/Probe2.java`，不依赖 Gradle 产物） |
| `manual-fixes.json` | 唯一手写输入：DFU 漏掉的改名 |
| `data/names-*-1.20.4.txt` | 1.20.4 名字清单（两个来源并集，1312 / 1058） |
| `data/extra-legacy-names.txt` | pre-1.20.3 兼容域（实测 1 个：grass） |
| `reports/report.txt` | 审计报告（每次生成覆盖） |

> 脚本一律保持 **纯 ASCII**：Windows PowerShell 5.1 按 ANSI 读取 `.ps1`，
> 非 ASCII 注释会变成杂散引号 / 反引号导致解析失败（本次踩过）。中文文档只放在本文件里。

## 7. 验收证据

- 探针 `scratch/id-renames/Probe2.java`（14/14 通过）：对 `minecraft:grass`、`grass`、`scute`、`chain`、
  `diamond_sword`、`not_a_real_id_xyz` 逐个打印「走哪条路 + 最终结果 + report.steps/warnings」，
  另有空串、非法语法、`#tag`、方块用例与 DFU 成本测量。
- 审计报告 `tools/audit/reports/report.txt`：1312 / 1058 个名字的真实分类结果。
- 运行器不起 Gradle：`tools/audit/run-id-probe.ps1` 直接编译 `regainnbt/ids` + 探针后运行。
