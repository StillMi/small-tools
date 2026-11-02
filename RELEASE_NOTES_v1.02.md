# StillMi 的小工具 v1.02

修两个附魔洗炼石的 bug。都是真实缺陷，其中第一个是我上一版的补丁静默失败导致的。

## 1. 列不出词条

`WashStoneMenu.onInputChanged()` 算完词条后只调了 `broadcastChanges()` —— 那只是通知
原版"内容变了"，**并不会把词条发到客户端**。而界面渲染用的是自己定义的
`WashStoneSyncPayload`，没人发它就永远是空列表，于是界面一直停在"请把物品放进槽里"。

更糟的是，我上一版明明加过一句发送，但那次替换的锚点被后来插入的 `levelOf()`
打断，**补丁静默没命中**，我也没有验证就发布了。

现在 `onInputChanged()` 末尾统一走 `sync()`：

```java
private void sync() {
    if (owner != null) {                    // 客户端构造时 owner 为 null，自然不会发
        WashStoneSyncPayload.send(owner, this);
    }
}
```

`Slot.set()` → `setChanged()` → `container.setChanged()` → `onInputChanged()` 这条链
在原版里是通的（`Slot.set` 第 67 行就调了 `setChanged`），所以放物品进槽会立即刷新并同步。

## 2. 关界面物品不归还

原来 `removed()` 里手写了一段"放回背包，失败就 `player.drop`"，但顺序是
`super.removed(player)` **之后**才动容器 —— 那时菜单已被视为关闭，操作容易被忽略，
物品就卡在临时容器里跟着 menu 一起没了。

现在改成两点：

- **调换顺序**：先归还，再 `super.removed`；
- **改用原版 `clearContainer(player, container)`** —— 原版已经把"放回背包失败就掉在地上"
  这套边界处理写好了，手写容易漏分支。

## 3. 顺带修掉一个会让界面异常关闭的判定

`stillValid()` 原来要求"手上还拿着洗炼石"。问题是**洗完炼石头就没了**，
界面会被原版立刻强制关闭，玩家看不到结果；把石头放进槽里时同理。

现在 `stillValid` 直接返回 `true`。真正需要校验的地方是 `applyCandidate`，
它自己会检查石头还在不在手上。

## 产物

- `smalltools-1.02.jar`
- 93,469 字节 / 58 条目
- SHA256: `6d2015dbb10e93de0ce61d78532d8e65f8bc7bfca31e5ddd3066a1e80936c3f0`

## 验证方式

这次我逐条核对了字节码：

- `WashStoneMenu` 里确实引用了 `WashStoneSyncPayload`（同步包真的发出去了）；
- 确实调用了 `clearContainer`；
- `stillValid` 不再引用 `WashStoneItem`。

仍然没有游戏内实测。请按这个顺序看：

1. 右键洗炼石，放入一件**已附魔**的剑 —— 应立刻列出它的附魔词条；
2. 未附魔的物品 —— 应放不进去；
3. 点一个词条 —— 应出现三条候选；
4. 选一条 —— 替换成功、石头消失、界面不闪退；
5. 中途 `Esc` 关掉 —— 槽里的物品应回到背包。
