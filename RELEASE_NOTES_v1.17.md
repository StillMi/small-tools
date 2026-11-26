# v1.17 — 全模组逻辑审计与修复

这一版只有一个新物品（同伴传送石，v1.16 做出来但一直没发布，所以在这里首次公开），
其余全部是修 bug。做法是把整个模组拆成六块，每块派一个只读审计去读源码，
报告回来后主控逐条核对，确认成立才回派原班人马去修——一共 19 条，全部有源码行号对得上。

- 产物：`build/libs/smalltools-1.17.jar`，96 entries，165,022 字节
- SHA256：`6a0bb3eebf863f64c752b0550b25df560d895bd3d134e3c6b47307ca4d08c4c6`
- 编译：`64 个 class`

---

## 最严重的一条：同伴传送石从上线起一次都没成功过

`CompanionTeleports.findLanding` 里原本是：

```java
if (standable(level, pos) && standable(level, pos.above())) {
```

`standable(pos)` 成立的前提是 `pos` 本身**能被替换**（空气、草、雪）。
而 `standable(pos.above())` 要求 `pos` 这一格的**顶面结实**（`isFaceSturdy(..., UP)`）——
空气不可能是结实的。两项同时成立是逻辑上的空集，所以这个方法**永远**返回 `null`，
`execute` 每次都走 `no_ground`：人不动、石头不扣、只在聊天框弹一句"找不到落脚点"。

改成查"头那一格空不空"（`hasHeadroom`）：不是液体 + 能被替换。玩家 1.8 格高，
脚和头两格都放得下才算落脚点。

同一处还有第二处：`renderBackground` 的挪位。选人界面和同意界面的面板底色原本画在
`super.render(...)` **之后**，把 `addRenderableWidget` 加进去的按钮整个压在下面
（alpha 0xD8 / 0xE0），"取消""同意""拒绝"几乎看不见——虽然还能点。现在面板挪进
`renderBackground`，先画背景再画按钮。

---

## 传送门运行时

| 问题 | 后果 | 修法 |
| --- | --- | --- |
| 旧存档迁移把 `creator` 写成 `SHARED_ACTOR`，而 `leftFor` 只认 `actor.equals(creator)` | 每个玩家都从 `othersLeft` 缺省值拿到一份满额 `quota`，**门永远不会消失** | 加 `isSharedBucket()`，`leftFor`/`setLeftFor` 一律落到 `creatorLeft` |
| 结构自检只跑 `masterPos`，唯一兜底 `onRemove` 在 `isBuilding()` 时被跳过 | 别人在门洞位置建门 → master 被覆盖 → 没人接力自检，`configured` 仍 true，进去照样传到旧 partner | master 不在了就由当前方块接管，按坐标错开相位（`checkPhase`） |
| `configured = false` 写在可能 no-op 的 `invalidate` 之前 | 留下"方块还立着、BE 已经哑了"的门，只能手挖 | 挪到 `invalidate` 之后 |
| `executeTeleport` 不查冷却、不复查骑乘，partner BE 找不到也照样传 | 传送发生在错误时机；落点按 legacy 散布算、`clearLandingSpace` 在空处挖空气 | 补 `isOnPortalCooldown` / 骑乘复查；**6 格一个可用 partner BE 都没有就直接 return** |
| `othersLeft` 只增不减 | 长期开的门，每次存档全量写盘 | `left >= quota` 时移除条目 |
| 读档 `getUUID("Creator")` 可能给 null；两个计数键都缺时 `creatorLeft` 保持 0 | `saveAdditional` 的 `putUUID` NPE；读档 20~40 tick 后门被当"已用尽"拆掉 | 判空回落 `SHARED_ACTOR`；补 `else if (configured)` 分支 |
| `PortalTeleports` 的静态队列没有停机清理 | 强引用旧世界的 `ServerLevel`/`Entity` | 新增 `clear()`，挂到 `ServerStoppedEvent` |
| `PortalCooldowns` 里 `remainingTicks` / `releasePass` 是死方法 | — | 删除 |
| 玩家往门面格放方块时，`onRemove` 在同一调用栈里把刚放的方块写成空气 | 方块被"吞" | `invalidate` 增加带 `skip` 的重载，`onComponentBroken` 把 `brokenPos` 透传下去 |

---

## 开门选址与结构

| 问题 | 后果 | 修法 |
| --- | --- | --- |
| 门洞清空循环特意跳过带方块实体的格子，紧接着的摆放循环又对同一批格子无条件 `setBlockAndUpdate` | 门洞里的箱子、木桶、刷怪笼**连同内容一起消失** | `placeStructure` 改返回 `boolean`，加 `siteBuildable` 预检：任一格有方块实体、或框架/台阶位置压着别人的方块，就整套放弃、一根方块都不碰 |
| `closePrevious` 是"只要一端完整就**双端** `invalidate`" | 另一端旧址上玩家自建的同类方块被当成我们的铲平 | 改用现成的 `destroyIntactEnds`，只拆仍然完整的那一端 |
| 选址早于 `closePrevious` | 玩家自己的旧门参与选址，原地重开必然 `NO_GROUND` | 先试一次，只有 `NO_GROUND` 才拆旧门重选一次 |
| 全路径没有实体占用检查 | 远端落点站着人时，黑曜石直接盖在他身上 | 新增 `anyPlayerInside`，结构 AABB 外扩 1 格内有人就换落点 |
| `SetTargetPayload` 只校验 Y | X/Z 填 ±999,999,999 也能通过，越界开不出门 | 按 `WorldBorder` 校验 X/Z；客户端输入框收窄到 `-?\d{0,8}` / `setMaxLength(9)` |
| `SetTargetPayload` 靠 `InteractionHand.values()` 挑"第一颗传送石" | 双手各持一颗时改错那颗；主手是地牢石时静默失败 | payload 带 `hand` 字段，只认那只手；`allowsBinding()` 不满足给提示 |
| `PortalRegistry.forget` 在维度判空之前 | 维度没加载时记录被丢、门还立着 | 挪到判空之后 |

---

## 洗炼石

| 问题 | 后果 | 修法 |
| --- | --- | --- |
| `locked` 判断写在清空 `entries/candidates` **之后** | 锁定状态下改窗口大小 / F11 / 改 GUI 缩放会重新 `init()` → 清空 → `applyCandidate` 的下标检查永远失败 → **拿不出、洗不了、石头已扣** | 判断提到清空之前；`KIND_REFRESH` 改走只重发不重算的 `resend()` |
| `chooseEntry` 里就 `shrink(1)`，`removed()` 只 `clearContainer` 不回补 | 选了词条但没选候选就关界面 = 白扣一颗（注释还写着"中途关界面不会损失"） | 改成"先写附魔、再记运气、最后扣石头"；`applyCandidate` 成功才结算 |
| 洗完只 `broadcastChanges + sync`，重建只挂在 `setChanged` 上 | 直接改附魔组件不触发 `setChanged` → 界面退回"请放入物品" | 成功后改调 `onInputChanged()` 重建 |
| 候选池空时静默 `return false` | 玩家不知道发生了什么 | 补 action bar 提示 |
| 候选不查 `exclusive_set` | 能洗出锋利 + 亡灵杀手、时运 + 精准采集 | 双向查互斥组（和原版 `Enchantment.areCompatible` 同一套判定） |
| `WashLuck` 读路径用 `computeIfAbsent` | 只读也会建条目，UUID 无界增长 | 读改 `states.get`，写才创建；`load()` 里 clamp `progress` 并同步 `armed` |
| 每次点击发两份全量包 | 带宽浪费 | 删掉多余的那次 send |
| 石头按"当前那只手"现取 | 界面开着把石头拖走 → 静默失败 | 构造时定下 `stoneSlot`，点击时校验那一格还是洗炼石 |

---

## 强化器

| 问题 | 后果 | 修法 |
| --- | --- | --- |
| `ENHANCE_FILL` 只在 `enhance()` 成功时归零，而 `hasWork()` 不含"fill 有剩余" | 物品被外部改到"3 条附魔全满级"后，投入的经验**永久卡死**，tooltip 还一直显示"下一次需要 X 经验" | 新增 `refundStuck`：`!hasWork && fill > 0` 时按 `fill / XP_PER_LAPIS` 退青金（背包满则掉落脚下）并清零 fill；开屏 / 投入 / 强化 / 关界面四处都调 |
| `tryFill` 不查互斥组、不查宝箱附魔 | 能造出锋利 + 亡灵杀手、无限 + 修补 | 双向查 `exclusiveSet`；跳过 `EnchantmentTags.TREASURE` |
| 客户端按钮条件抄了一份：`ready` 缺 `isEnhanceable`/`hasWork`，投入按钮用 `lapis >= 1` 而非 `>= cost`；服务端 `canEnhance()` **零调用点** | 规则被写成三份，必然漂移 | 服务端算、客户端只读：`canInvest`/`canEnhance` 塞进 `packed` 的 bit24/25；`canEnhance()` 终于有调用点 |
| 菜单只 addSlot 快捷栏 9 格，`lapisCount()`/`consumeLapis()` 却扫 36 格 | **主背包里的青金看不见却被计数和扣除** | 统一走 `lapisStacks()`，只扫 `0..8` |
| `consumeLapis` 预检 / 实扣两次遍历 | 中途失败会扣一部分 | 先汇总总量、不够直接返回一颗不动；够了一次性扣 |
| 空槽与"已到上限"共用同一句提示，`Math.max(1, cost)` 把 0 顶成 1 | 提示错、按钮文案荒谬 | 按 `insert / not_enhanceable / exhausted / maxed / ready / not_ready / no_material` 分流 |
| 附魔列表无限下画 | 第 4 行压住进度文字与五段条；`result` 和空槽提示重叠 | 列表最多 3 行，超出显示"…还有 N 条"；`result` 挪到标题下方 |
| 每次点击发两遍全量包 | 带宽浪费 | `KIND_INVEST`/`KIND_ENHANCE` 只调菜单；`KIND_REFRESH` 加 2 tick 节流 |

---

## 工具链与文档

- `tools/deploy.py`：原本**先删光再拷贝**，游戏开着时 `copy2` 抛 `PermissionError`，
  mods 目录里本模组一个 jar 都不剩。改成"临时文件 → SHA256 校验 → `os.replace` 原子替换"，
  任何失败路径都不碰已有文件；版本键补零（`1.01` 与 `1.1` 不再撞车）；只删比本次部署更旧的。
- `tools/compile.ps1`：Gradle 缓存按路径排序后加入 classpath（同源类三份的问题），
  打印实际命中的依赖清单并落盘到 `build/javac-classpath.txt`。**BOM 已确认保留。**
- `tools/package.py`：资源根改成 `src/main/resources` + `src/generated/resources` 两个。
- `MagicButton`：`Mth.sin(System.currentTimeMillis() / 260.0F)` 溢出 int，下标被钉死成常数
  → 悬停描边"呼吸"其实一动不动。改成先 `% 4000L` 再算。
- 语言文件：中英各 **130 键、集合一致、占位符个数一致**。统一成"浓缩液态青金" /
  "Concentrated Liquid Lapis"；删掉 3 个死键；补上修复过程中新增的 10 个键。
- `README.md`：修掉路径里的控制字符（`\v`、`\1` 被当转义吃掉），补上 `companionDelaySeconds`
  等配置项，说明部署脚本的新语义与门的贴图来源。
- `Config.java`：`teleportDelaySeconds` 的注释原本写"0 表示原版的 5 秒"，实际是 10 tick（0.5 秒）。

---

## 刻意不修的

- **洗炼界面的输入槽只存在内存里**，崩溃即丢。改成落盘要处理"同一个容器被两人打开"等一堆
  边界，属设计取舍，这版保留现状。
- **客户端无法得知传送门的剩余次数**：`Entity#portalCooldown` 没有 DataWatcher，
  次数用完的玩家本地仍会播满 4 秒的镜头扭曲，服务端 `canAccept` 拒绝。要修得新增 S2C 同步通道，
  这版只在代码里写清这一点，没有动行为。
- **`isStructureIntact` 只比方块状态、不比方块实体归属**：`siteBuildable` 的预检已经把
  "同位置完全重叠"这条路堵死（门面必带方块实体，预检会拒绝），部分重叠会生成真正残缺的结构，
  自检能识别。再加归属校验会把 `PortalManager` 和 BE 耦得更深，不划算。
