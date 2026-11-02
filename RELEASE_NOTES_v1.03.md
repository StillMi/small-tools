# StillMi 的小工具 v1.03

修附魔洗炼石的两个问题：附魔名显示成英文标识、以及"点了就不能反悔"没做到。

## 1. 附魔名显示成一长串英文

之前同步包只传附魔 id，客户端自己拼翻译键：

```java
Component.translatable("enchantment." + id.getNamespace() + "." + id.getPath());
```

这只是在**碰巧命中**的情况下才成立。1.21 里附魔的显示名是
`Enchantment#description` 这个 **Component 字段**，模组附魔、或者用了自定义
description 的附魔，由 id 推出来的键根本不存在，于是界面上显示出原始键名
（一长串 `enchantment.xxx.yyy`）。

现在改成**由服务端直接生成显示名**并作为 `Component` 传过去：

```java
Enchantment.getFullname(holder, level)   // 读 description 字段，自动补罗马数字
```

和原版附魔书用的是同一个方法，所以名字、颜色、等级写法完全一致。
客户端不再拼任何翻译键。

## 2. 点击后不可取消、石头必消耗

这个之前**没有真正做到**：石头是在最后一步（选候选）才扣的，
玩家看到三条候选不满意时按 `Esc` 就能白嫖一次随机结果，石头还在手里。

现在把**锁定和扣石头放在同一个动作里**，发生在"点词条"那一刻：

- 点下词条 → 立刻 `stone.shrink(1)`、`locked = true`、生成三条候选；
- 锁定后输入槽 `mayPlace` 和 `mayPickup` 都返回 `false`，物品换不掉也拿不走；
- 之后关界面也不回退，石头已经没了；
- `chooseEntry` 开头检查 `locked`，重复点击直接无效。

界面上的提示也提前了：

- **点击之前**（还在列词条阶段）就显示红字
  「⚠ 点附魔后不可取消，洗炼石将被消耗」；
- 锁定后显示「洗炼石已消耗，请选择一个新附魔」。

还有一个边界：如果某件物品上的附魔实在找不到任何可替换项（候选为空），
**不锁定、也不扣石头**，避免白吃一颗。

## 顺带修正

- 判断"能否附在这件物品上"改用 NeoForge 的
  `ItemStack#supportsEnchantment`（原版那个 `isSupportedItem` 已标注废弃）；
- 词条列表按显示名排序，避免每次打开界面按钮位置乱跳；
- 明确排除护甲（需求写的是"武器或者工具"）。

## 产物

- `smalltools-1.03.jar`
- 93,566 字节 / 58 条目
- SHA256: `d3399eb50151fb9b76cfd4c631ae8fb115096b64fc39b2bbfbfbcfc539871827`

## 字节码已核对

- `WashStoneMenu` 调用了 `Enchantment.getFullname`；
- 调用了 `supportsEnchantment`；
- 存在 `locked` 字段；
- `WashStoneSyncPayload$Entry` 使用 `ComponentSerialization`；
- 客户端 `WashStoneScreen` 里**不再出现** `enchantment.` 拼接。

仍未游戏内实测，请重点看：附魔名是否正常中文/英文可读、点词条后石头是否当场消失、
关界面后石头是否确实不回退。
