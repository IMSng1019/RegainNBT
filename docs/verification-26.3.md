# RegainNBT 在 26.3 上的验证记录

- 验证日期：2026-10-04
- 目标：`Minecraft 26.3`（最新正式版，2026-09-15）+ Fabric Loader 0.19.5 + Fabric API 0.161.0+26.3 + Java 25
- 构建：Fabric Loom **1.18.2**（26.x 官方反混淆 jar，**不需要 mappings**）
- 数据版本：源 1.20.4 = **3700**，目标 26.3 = **5023**

> 上一轮的可行性结论与证据见 [feasibility-report.md](feasibility-report.md)（针对 26.1.2）。
> 本文只记录**本次在 26.3 上重新复核/新发现**的内容。

---

## 1. 挂载点与 API 复核（26.3 全部一致）

用 `javap` 直接对 26.3 反混淆 jar 复核，全部与 26.1.2 相同：

    net.minecraft.commands.Commands#performPrefixedCommand(CommandSourceStack, String)   ✅ 存在
    net.minecraft.commands.Commands#performCommand(ParseResults<CommandSourceStack>, String) ✅
    net.minecraft.commands.Commands#validateParseResults(ParseResults) / #getParseException ✅
    net.minecraft.world.level.BaseCommandBlock#getCommand() / #setCommand(String) / #performCommand(ServerLevel) ✅
    net.minecraft.util.datafix.DataFixers#getDataFixer()                                  ✅
    net.minecraft.util.datafix.fixes.References#ITEM_STACK / ENTITY / BLOCK_ENTITY / ITEM_NAME / BLOCK_NAME ✅
    net.minecraft.nbt.TagParser#parseCompoundFully(String)                                ✅
    net.minecraft.commands.arguments.item.ItemArgument#item(CommandBuildContext) / #getItem(ctx, name) ✅
    net.minecraft.commands.arguments.CompoundTagArgument#compoundTag()                     ✅
    net.minecraft.world.item.ItemStack#CODEC / #MAP_CODEC                                  ✅
    net.minecraft.commands.functions.CommandFunction#fromLines(Identifier, CommandDispatcher, T, List<String>) ✅

两个 26.3 的新事实：

1. `SharedConstants.WORLD_VERSION` 已被 **@Deprecated**（值仍是 5023）；
   代码统一改用 `SharedConstants.getCurrentVersion().dataVersion().version()`。
2. `net.minecraft.resources.Identifier`（不是 ResourceLocation），`Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)` 做权限。

**Mixin 注入点实测有效**：装有本模组的 26.3 服务端正常启动（`regainnbt.mixins.json` 是 `required: true` + `defaultRequire: 1`，
任何注入点找不到都会让服务器起不来 —— 起得来即为注入成功的强证据）。

---

## 2. M0 验收（真实 26.3 服务端，无玩家，控制台喂命令）

| # | 验收项 | 结果 |
|---|---|---|
| 1 | `/item replace block ... with diamond_sword{Enchantments:[...],display:{Name:'{"text":"Excalibur"}'}}` | ✅ 落地数据 `{components: {"minecraft:enchantments": {"minecraft:sharpness": 5}, "minecraft:custom_name": "Excalibur"}, count: 1, ...}` |
| 2 | `/summon minecraft:zombie ~ ~ ~ {HandItems:[{...附魔剑...},{}]}` | ✅ 实体数据出现 `equipment: {mainhand: {components: {"minecraft:enchantments": {"minecraft:sharpness": 2}}, count: 1, id: "minecraft:diamond_sword"}}`（**僵尸手上有剑**） |
| 3 | `/setblock ~ ~ ~ minecraft:chest{Items:[{Slot:0b,id:"minecraft:diamond",Count:3b}]}` | ✅ 容器数据 `[{count: 3, Slot: 0b, id: "minecraft:diamond"}]` |
| 4 | 命令方块里放旧指令 → 执行一次后回写 `old.` | ✅ 日志 `[RegainNBT] 命令方块已标记 old.: old.give Steve diamond_sword{...}`；`/data get block` 读出 `'old.give Steve ...'`；`/regainnbt strip 16` 后读回 `'give Steve ...'`（前缀被剥掉） |
| 5 | 对照：现代语法 `/item replace ... with diamond_sword[custom_name="Modern"]` | ✅ 未被改写，落地 `components: {"minecraft:custom_name": "Modern"}`，日志无翻译行 |

附加（超出 M0）：

- **纯 ID 改名**：`/give Steve scute` → 翻译成 `give Steve minecraft:turtle_scute`（随后才报 "No player was found"，证明解析已通过）；
  `/regainnbt why give Steve scute` 打印新版原始错误 `Unknown item 'minecraft:scute'` + 翻译路径。
- **数据包函数加载期翻译**：往 `world/datapacks` 放一个含旧写法的 `.mcfunction`，`/reload` 后函数**加载期**即被逐行翻译
  （日志出现在 Worker-Main 线程）：`summon ... {CustomName:'{"text":"FuncZombie"}',HandItems:[...]}` → `{CustomName:{text:"FuncZombie"},...,equipment:{mainhand:{...}}}`；
  执行后实体数据 `CustomName: "FuncZombie"`（不再是 JSON 字面量）、`equipment.mainhand` 有附魔剑；`setblock ... chest{Items:[{Slot:0b,...,Count:7b}]}` → `{count: 7}`。
- **不重复执行**（T5 独立实例）：同一条旧 `/summon` 连跑两次，`kill @e[tag=...]` 每次只杀 1 只；`old.` 路径同样只 1 只。
- **回写持久化与幂等**（T5 独立实例）：命令方块回写后**重启服务端**，`Command` 仍是单个 `old.` 前缀；再次上电走 EXPLICIT_MARK，不重复标记。

---

## 3. 测试

| 层面 | 数量 | 结果 |
|---|---|---|
| JUnit（headless，真实 26.3 代码 + 真实 dispatcher） | **129** | 全绿（`gradlew test` 与 `tools/acceptance/run-junit.ps1` 两条路径） |
| 端到端验收脚本 `tools/acceptance/run-acceptance.ps1` | 16 项断言 | `passed=16 failed=0`，exit 0（每次重建 world + 哨兵切窗，可重复跑） |
| 意图检测探针（47 用例 + 11 健壮性） | 58 | 全 PASS；23 条现代反例**零误判** |
| 路由探针（含缓存/负缓存/回写/executeRewritten） | 52 | 全 PASS |
| 翻译器探针 Probe1–9 | 旧版 30/34 adopted（4 条为**明确拒绝**）、现代 11/11 未改写 | — |
| 补丁规则探针 | 31 | 全 PASS（补丁前后用 26.3 真实读取方对比） |
| ID 改名探针 | 14 | 全 PASS |

---

## 4. 第 2 层补丁规则的证据（原版 DFU 覆盖不到的洞）

| 规则 | 补丁前（真实 26.3 读取方） | 补丁后 |
|---|---|---|
| `CustomPotionEffects`（物品） | `PotionContents.CODEC -> customEffects=[]`（DFU 把它塞进 `custom_data` 就不管了） | `potion_contents.custom_effects=[{id:"minecraft:strength",...}]` → 读出 `[minecraft:strength x2 200t]` |
| `ActiveEffects`（实体） | 读不到 | `active_effects` → `MobEffectInstance.CODEC.listOf() -> [minecraft:speed x1 600t]`（`hidden_effect` 递归也过） |
| 信标 `Primary`/`Secondary` | 真 `BeaconBlockEntity.loadAdditional` 读出 null | `primary_effect="minecraft:speed"` / `secondary_effect="minecraft:haste"`，真实 loadAdditional 读出正确值 |

26.3 里 `MobEffectInstance` 的字段：`id`（字符串）+ `amplifier`(UNSIGNED_BYTE,默认0) / `duration` / `ambient` / `show_particles`(默认 true) / `show_icon` / `hidden_effect`。
`LivingEntity` 读写 `active_effects`；`BeaconBlockEntity` 读**字符串** `primary_effect/secondary_effect` 且再过 `BEACON_EFFECTS` 白名单。

---

## 5. 对上一轮报告的三处修正（有实测证据）

1. **`grass` 不是 1.20.4 的 ID**。1.20.4 的注册表里只有 `short_grass`（改名发生在 **1.20.3**）。
   所以「DFU 在 1.20.4 域内漏了 grass」应修正为：**1.20.4 域内原版一个都没漏**（scute/chain 都改了）；
   `grass` 属于 pre-1.20.3 兼容域。ID 审计脚本因此自动派生两个输入域并分开统计。
2. **`CustomPotionEffects` / `ActiveEffects` / 信标 `Primary`·`Secondary` 是 1.20.2 之前**的形状，不是 1.20.4 的
   （真实 1.20.4 服务端活代码已用现代键 + 字符串 id；旧名只在 DataFix 类里）。
   原版 `MobEffectIdFix` 注册在 schema **3568**，而源版本 3700 → DFU 不会执行它，这才是「DFU 不转」的成因。
   结论：补丁规则对真·1.20.4 写法是安全 no-op，真正救的是更老的命令/存档 NBT —— 依然必需。
3. **`SharedConstants.WORLD_VERSION` 在 26.3 已弃用**，见 §1。

---

## 6. 已知限制 / 未覆盖项（诚实清单）

- **NBT 路径**（`/data get|modify ... HandItems`、`tag.display.Name`）**不会自动迁移**，只做告警：路径语义无法可靠映射。
- **选择器 `nbt=` 无 `type=`** 时用合成实体 id 触发 DFU，每次都会告警（有意的可审计降级）。
- `/data merge block` 在 headless（无 level）下按键猜方块实体类型；实服会用 `source`+坐标反查。
- `kill @e[nbt={HandItems:[{}]}]` 这类「空物品」DFU 是真空操作、无产物键，因此拒绝采用（返回原版报错）。
- **Mixin 注解处理器**在当前工程不可用（26.x 无 mappings，Mixin AP 报 "Unable to locate obfuscation mapping"），
  注入正确性靠**真实服务器启动 + 行为验证**（函数 mixin 配置为 `required: false`，注入失败只记日志）。
- **性能**未做基准测试：已知有 LRU 路由缓存（含翻译失败负缓存），DFU 首次调用有初始化成本（起服时预热）。
- 上一轮报告中 `spike` 的 11 个验证程序仍是 26.1.2 的产物；`spike/libs` 与 `spike/out` 是生成物。本模组自身的验证不依赖它们。
