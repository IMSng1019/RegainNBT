# 函数逐行 fixtures（tools/acceptance/fixtures/functions/）

来源：T6（cmd-function）的 scratch/cmd-function/fixtures/functions/（含 .translated.txt 与
TRANSLATION-CHECK.md，那些是人看的人工核对材料，服务端不读，故未复制）。T7 于 2026-10-04 冻结复制到
这里，让 tools/acceptance/run-acceptance.ps1 在干净克隆下自包含运行（scratch/ 不属于交付物）。

文件格式（每行一条，井号开头的注释行与空行忽略）：
    setup: <命令>            run 之前执行（清场）
    run: <命令>              执行函数（宏用例在这里传参）
    probe: <命令>            run 之后取观测值
    contains: <文本>         probe 输出必须包含
    not_contains: <文本>     probe 输出必须不包含

acceptance 脚本行为：起服前把所有 *.mcfunction 复制进数据包
  <ServerDir>/world/datapacks/rnbt_acceptance/data/rnbt_acceptance/function(s)/
然后按 setup/run/probe 顺序发命令（每条后跟 RNBT-FIX-<case>-<step> 哨兵，事后按哨兵切窗口断言）。

用例（坐标 6 -60 1..6，沿用 T6 的设计，全部不依赖玩家）：
    d1_legacy_container   旧版容器物品 NBT -> 出现 minecraft:custom_name（不翻译则读不到）
    splice_continuation   反斜杠续行先拼成一条逻辑行再翻译（只测得到函数路径）
    comment_and_blank     注释/空行跳过，后面的旧版行照常翻译
    macro_args            宏行不得被改写，$(n) 实例化后现代写法生效
    modern_passthrough    现代形状（小写 count）原样放行（回归锚点）
    old_mark              old. 前缀在函数加载期也生效（EXPLICIT_MARK）

与 T6 原件的唯一差异：macro_args 的观测口径。原用例断言 contains: Macro，但宏行按设计不翻译，
实服上 26.3 的容器 codec 会忽略旧版 Count/tag，只得到 {count: 1, Slot: 0b, id: "minecraft:diamond"}，
永远不可能出现 Macro。现改为「宏实例化 + 现代形状原样通过」（详见该 .expected.txt 内的日志证据）。

刷新方式：从 T6 的 scratch 目录重新复制 *.mcfunction / *.expected.txt，并保留上面的差异说明。
