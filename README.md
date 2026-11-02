# StillMi 的小工具 (StillMi's Small Tools)

一个 Minecraft **1.21.1 / NeoForge 21.1.x** 的整合小工具模组。

> **隶属关系**：本模组是上位工程，**已包含**「末影传送石」的全部内容。
> 装了本模组就不需要再单独装传送石模组 —— 两者不建议同时安装。

## 包含的内容

### 末影传送石

8 块黑曜石 + 1 颗末影珍珠合成。长按右键引导 4 秒，在你瞄准的位置开一座双向传送门，
另一端落在**这块石头自己绑定的坐标**附近。

- **潜行 + 右键**设定坐标，一块石头**只能设定一次**，设定后不可更改；
- 没绑过坐标的石头使用配置文件里的默认值；
- 每块石头各自记各自的目的地，想要多个地方就多做几块。

### 地牢传送石

8 块金锭 + 1 颗末影珍珠合成。另一端开在附近的**地牢**（原版刷怪笼房间）里。
目的地由世界决定，**玩家不能设定坐标**。

### 传送门

- 门面用原版下界门的贴图与动画，同一张 16×512 / 32 帧图、同一份 `.mcmeta`；
- 几何是厚度 4/16 的薄片，正反两面都贴图，四个朝向各自旋转；
- 进门要站够 **4 秒**（原版 `playersNetherPortalDefaultDelay`），期间有下界门的
  镜头扭曲和 `PORTAL_TRIGGER` 环境音；
- 传送后 5 秒内不能再传（`teleportDelaySeconds`）；
- 结构为 4×5 门框 + 2×3 门面 + 门前一格两宽台阶，无高台、无支撑柱。

### 次数规则

每块石头 3 次开门机会（`durability`），用完后物品消失。
每次开门后每人可往返 2 次（`roundTrips`）。次数按人单独计算：
**创造者的次数用尽会关闭整对门**，其他玩家用尽只是自己不能再传。

## 配置

`config/smalltools-common.toml`

```toml
[general]
    channelSeconds = 4          # 引导秒数
    cooldownSeconds = 60        # 物品冷却
    teleportDelaySeconds = 5    # 传送后多久能再传
    roundTrips = 2              # 每人往返次数
    aimDistance = 24            # 能开门的准星距离
    dungeonRadiusChunks = 20    # 找地牢的搜索半径（区块）

[destination]
    targetX = 1000              # 没绑定坐标的石头用这里的默认值
    targetY = 64
    targetZ = 1000
    targetSearchRadius = 16
```

## 构建

本工程**不复制** NeoForm 重编译产物和 Gradle 依赖缓存（几百 MB），
`tools/compile.ps1` 会回落到同级目录的 `enderportal` 工程取依赖。

```powershell
# 本地没有 GDK 的环境（沙箱）用这条
powershell -ExecutionPolicy Bypass -File tools\compile.ps1   # -> build\classes
python tools\package.py                                      # -> build\libs\smalltools-1.0.jar
```

有 Gradle 的环境直接用 `gradlew build`。

> `tools/compile.ps1` 必须存成 **UTF-8 with BOM**，否则 Windows PowerShell 5.1
> 会按 GBK 解析中文注释而报语法错。

## 产物

- `build/libs/smalltools-1.0.jar`
- 装到 NeoForge 21.1.x 的 `mods/` 目录即可

## 许可

MIT
