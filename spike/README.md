# spike - RegainNBT 可行性验证程序

这些程序用**真实的 Minecraft 代码**（26.1.2 官方映射 jar + JDK 25）验证中间层方案，
结论见 [docs/feasibility-report.md](../docs/feasibility-report.md)。

## 各程序验证什么

| 程序 | 内容 |
|---|---|
| Spike1 | 原版 DataFixerUpper 把 1.20.4 的物品 / 实体 / 方块实体 NBT 转成当代组件（含 custom_data 兜底、文本组件、属性改名） |
| Spike2 | 第一版命令层链路（保留失败过程：启发式判错类型、缺 id/Count/tag 导致静默不转换） |
| Spike3 | 用真实 CommandDispatcher + ItemArgument 跑「新版解析失败 → 翻译 → 重解析」 |
| Spike4 | 物品 ID 注入、ID 改名缺口、/summon 的静默陷阱与意图检测 |
| Spike5 | 收口：实体注入 id、ID 改名覆盖度、类型感知检测（同时暴露缺 tag 包装时的静默 no-op） |
| Spike6 | **完整正确链路**：/give 旧指令 → 规范化 → 翻译 → 原版解析 → 真实组件对象（4 通过 / 1 环境受限） |
| Spike7 | 指令兼容覆盖面实测：文本组件参数、实体选择器 `nbt=` —— 1 个硬失败 vs 5 个静默失败 |
| Spike8 | 运行时身份证明：jar 路径 + 版本号 + 「现代语法通过 / 旧语法报错」的自证式 A/B |
| Spike9 | 38 用例覆盖率扫描：物品 27/27 全覆盖，实体/方块实体出现「无变化」 |
| Spike10 | 缺口确认：CustomPotionEffects / ActiveEffects / 信标 Primary·Secondary + 101 键双条件审计 |

## 运行

    powershell -ExecutionPolicy Bypass -File spike\run.ps1 -Main Spike5

脚本会：

1. 从本机 Gradle/Loom 缓存（`~/.gradle/caches/fabric-loom/26.1.2` + `modules-2`）重建 `spike/libs`；
2. 用 Gradle 提供的 JDK 25（`~/.gradle/jdks/eclipse_adoptium-25-amd64-windows.2`）编译；
3. 运行指定主类（Spike1..Spike5）。

`spike/libs` 与 `spike/out` 是生成物，已被 .gitignore 忽略。

## 环境要求

- JDK 25（26.x 的 class 文件版本要求；Gradle toolchain 会自动下载到 `~/.gradle/jdks`）
- 本机 Gradle 缓存里有 26.1.2 的 `minecraft-common-deobf` jar 与配套 libraries
- 无需联网、无需启动游戏
