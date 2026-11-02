"""生成末影传送石模组的物品贴图。

  * portal_stone.png          —— 灰紫色石头 + 末影珍珠
  * dungeon_portal_stone.png  —— 紫色宝石 + 一圈火焰（地牢传送石）

传送门方块的贴图不再由本脚本生成：v1.13 起门面直接引用原版
minecraft:block/nether_portal，动画和原版下界门完全一致。

用法：python tools/gen_textures.py
"""
import json
import math
import os
import random

from PIL import Image

ROOT = os.path.join(
    r"F:\文档\deepseek-harness\default-workspace",
    "smalltools", "src", "main", "resources", "assets", "smalltools", "textures",
)

SIZE = 16


def make_item() -> Image.Image:
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    px = img.load()

    # 石头轮廓：一个略微不规则的椭圆
    mask = [[False] * SIZE for _ in range(SIZE)]
    for y in range(SIZE):
        for x in range(SIZE):
            dx = (x - 7.5) / 6.3
            dy = (y - 8.2) / 5.4
            if dx * dx + dy * dy <= 1.0:
                mask[y][x] = True
    for (x, y) in ((2, 5), (3, 4), (12, 12), (11, 13), (1, 10), (14, 6)):
        mask[y][x] = False

    def inside(x: int, y: int) -> bool:
        return 0 <= x < SIZE and 0 <= y < SIZE and mask[y][x]

    palette = {
        0: (0, 0, 0, 0),
        1: (44, 39, 58, 255),
        2: (60, 54, 78, 255),
        3: (80, 72, 104, 255),
        4: (105, 95, 133, 255),
    }

    random.seed(11)
    for y in range(SIZE):
        for x in range(SIZE):
            if not mask[y][x]:
                continue
            edge = any(not inside(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            if edge:
                px[x, y] = (24, 20, 33, 255)
                continue
            t = (y - 3) / 11.0
            idx = 4 - int(t * 2.6) + random.choice([0, 0, 0, 1, -1])
            idx = max(1, min(4, idx))
            px[x, y] = palette[idx]

    # 中间嵌一颗末影珍珠
    cx, cy = 8.0, 8.0
    for y in range(SIZE):
        for x in range(SIZE):
            d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
            if d <= 2.9:
                if d <= 1.1:
                    color = (245, 226, 255)
                elif d <= 2.1:
                    color = (186, 120, 255)
                else:
                    color = (124, 54, 200)
                px[x, y] = (color[0], color[1], color[2], 255)
            elif d <= 4.3 and mask[y][x]:
                r, g, b, a = px[x, y]
                px[x, y] = (min(255, r + 20), max(0, g - 2), min(255, b + 24), a)

    # 一点高光
    px[7, 6] = (255, 250, 255, 255)
    px[9, 10] = (150, 92, 220, 255)
    return img


def make_dungeon_item() -> Image.Image:
    """紫色宝石 + 一圈火焰。

    构图分三层，从外到内：
      1. 火焰环  —— 距中心 4.9~7.5 像素，外缘随角度起伏（火舌），
                     颜色沿半径由亮黄渐变到暗红，形成"一圈火"的观感
      2. 暗色镶边 —— 宝石与火焰之间的一圈深紫，让宝石从火里"浮"出来
      3. 紫色宝石 —— 多面切割的椭圆，中心高光、边缘亮紫

    中心取 (8.0, 8.0)，也就是 16×16 的正中，四周各留 1 像素余量。
    """
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    px = img.load()

    cx, cy = 8.0, 8.0

    # ---- 1. 火焰环 ----
    # 火舌：几个正弦叠加出不规则的外缘，避免变成一个规整的甜甜圈
    random.seed(23)
    tongue = [0.0] * 360
    for i in range(360):
        a = math.radians(i)
        tongue[i] = (
            0.55 * math.sin(a * 5.0 + 0.7)
            + 0.32 * math.sin(a * 9.0 + 2.1)
            + 0.16 * math.sin(a * 15.0 + 4.3)
        )

    for y in range(SIZE):
        for x in range(SIZE):
            dx = x + 0.5 - cx
            dy = y + 0.5 - cy
            d = math.hypot(dx, dy)
            angle = math.degrees(math.atan2(dy, dx)) % 360.0
            wobble = tongue[int(angle) % 360]

            inner = 4.9
            outer = 6.9 + 0.55 * wobble
            if not (inner <= d <= outer):
                continue

            # 火焰从内到外：亮黄 → 橙 → 暗红
            t = (d - inner) / max(0.001, outer - inner)
            if t < 0.30:
                color = (255, 238, 158)
            elif t < 0.58:
                color = (255, 178, 56)
            elif t < 0.82:
                color = (234, 108, 26)
            else:
                color = (152, 48, 18)

            # 火舌尖上撒一点余烬，让边缘不那么整齐
            if t > 0.84 and random.random() < 0.4:
                color = (198, 64, 20)

            px[x, y] = (color[0], color[1], color[2], 255)

    # ---- 2. 暗色镶边 ----
    for y in range(SIZE):
        for x in range(SIZE):
            d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
            if 4.2 <= d < 4.9:
                px[x, y] = (58, 26, 92, 255)

    # ---- 3. 紫色宝石 ----
    for y in range(SIZE):
        for x in range(SIZE):
            dx = x + 0.5 - cx
            dy = y + 0.5 - cy
            d = math.hypot(dx, dy)
            if d > 4.2:
                continue
            # 多面切割：两个不同频率的正弦制造明暗切面
            facet = math.sin(dx * 1.9 + dy * 1.1) * 0.5 + math.sin(dx * 0.8 - dy * 2.3) * 0.5
            shade = 0.5 + 0.5 * facet
            if d < 1.1:
                color = (250, 232, 255)
            elif d < 2.3:
                color = (216, 150, 255)
            elif d < 3.4:
                color = (168 + int(56 * shade), 92 + int(40 * shade), 238)
            else:
                color = (130 + int(40 * shade), 58 + int(30 * shade), 204)
            px[x, y] = (color[0], color[1], color[2], 255)

    # 宝石高光与暗面
    px[6, 5] = (255, 252, 255, 255)
    px[7, 5] = (236, 200, 255, 255)
    px[10, 10] = (128, 52, 200, 255)
    px[9, 11] = (92, 34, 160, 255)

    return img



def make_wash_item() -> Image.Image:
    """附魔洗炼石：青白色的六边形结晶，中间一道流动的洗练波纹。

    和另外两颗石头区分开：那颗是灰紫、那颗是紫色带火，这颗走"青白 + 金边"，
    一眼能看出是消耗品而不是传送石。
    """
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    px = img.load()

    cx, cy = 8.0, 8.0
    # 六边形轮廓：按角度取半径，做出宝石切面感
    def inside(x: int, y: int) -> bool:
        dx = x + 0.5 - cx
        dy = y + 0.5 - cy
        if math.hypot(dx, dy) > 6.6:
            return False
        a = math.atan2(dy, dx)
        edge = 6.0 + 0.9 * math.cos(a * 6.0)
        return math.hypot(dx, dy) <= edge

    depth = {
        0: (18, 52, 62, 255),      # 外描边
        1: (32, 92, 106, 255),     # 暗面
        2: (66, 148, 162, 255),
        3: (120, 198, 208, 255),
        4: (196, 238, 240, 255),   # 亮面
    }

    random.seed(37)
    for y in range(SIZE):
        for x in range(SIZE):
            if not inside(x, y):
                continue
            edge = any(not inside(x + dx, y + dy)
                       for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            if edge:
                px[x, y] = depth[0]
                continue
            # 左上亮、右下暗，做出体积感
            t = ((x - 3) + (y - 3)) / 20.0
            idx = 4 - int(t * 2.6) + random.choice([0, 0, 1, -1])
            px[x, y] = depth[max(1, min(4, idx))]

    # 中间一道洗练波纹：横向的亮线，两端渐隐
    for y in range(SIZE):
        for x in range(SIZE):
            if not inside(x, y):
                continue
            wave = math.sin((x - cx) * 0.9) * 1.1
            if abs((y + 0.5) - (cy + wave)) < 0.9 and abs(x + 0.5 - cx) < 4.6:
                px[x, y] = (236, 252, 255, 255)

    # 一点金色描边高光，暗示"金块"的来历
    for (x, y) in ((5, 4), (6, 4), (10, 11), (11, 11)):
        if inside(x, y):
            px[x, y] = (255, 226, 138, 255)

    return img



def make_enhancer_item() -> Image.Image:
    """附魔强化器：金框包一颗蓝色核心，暗示"加压/强化"。

    配色和凝造青金呼应（青蓝 + 金），和洗炼石的青白结晶区分开。
    """
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    px = img.load()
    random.seed(71)

    GOLD_D = (150, 108, 30, 255)
    GOLD = (222, 176, 62, 255)
    GOLD_L = (255, 224, 138, 255)
    CORE_D = (28, 66, 110, 255)
    CORE = (58, 130, 200, 255)
    CORE_L = (150, 210, 255, 255)

    # 外框：一圈金色方环
    for y in range(SIZE):
        for x in range(SIZE):
            on_edge = x in (2,3,12,13) or y in (2,3,12,13)
            near_edge = 1 < x < 14 and 1 < y < 14
            if not near_edge:
                continue
            if on_edge:
                # 左上亮、右下暗
                top_left = (x + y) < 16
                px[x, y] = GOLD_L if top_left else GOLD
            elif x in (1,14) or y in (1,14):
                px[x, y] = GOLD_D

    # 中间核心：菱形
    for y in range(SIZE):
        for x in range(SIZE):
            dx = abs(x + 0.5 - 8.0)
            dy = abs(y + 0.5 - 8.0)
            if dx + dy <= 3.4:
                if dx + dy < 1.2:
                    px[x, y] = CORE_L
                elif dx + dy < 2.4:
                    px[x, y] = CORE
                else:
                    px[x, y] = CORE_D

    return img


def make_condensed_lapis() -> Image.Image:
    """凝造青金：深蓝多面结晶，带一点金色内光。"""
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    px = img.load()
    random.seed(83)

    DEEP = (18, 34, 86, 255)
    MID = (38, 74, 150, 255)
    BRIGHT = (86, 132, 220, 255)
    LIGHT = (168, 200, 255, 255)

    def inside(x, y):
        dx = x + 0.5 - 8.0
        dy = y + 0.5 - 8.0
        # 竖直拉长的菱形，像一颗被压实的晶体
        return abs(dx) * 1.0 + abs(dy) * 0.72 <= 4.9

    for y in range(SIZE):
        for x in range(SIZE):
            if not inside(x, y):
                continue
            if any(not inside(x+dx, y+dy) for dx, dy in ((1,0),(-1,0),(0,1),(0,-1))):
                px[x, y] = DEEP
                continue
            dx = x + 0.5 - 8.0
            dy = y + 0.5 - 8.0
            # 左上靠亮
            t = dx + dy
            if t < -2.4:
                px[x, y] = LIGHT
            elif t < -0.6:
                px[x, y] = BRIGHT
            elif t < 1.8:
                px[x, y] = MID
            else:
                px[x, y] = DEEP

    # 一点金色内光，表示"凝结"过的能量
    for (x, y) in ((7, 9), (8, 9), (8, 10)):
        if inside(x, y):
            px[x, y] = (255, 226, 138, 255)

    return img


def main() -> None:
    item_dir = os.path.join(ROOT, "item")
    os.makedirs(item_dir, exist_ok=True)

    targets = {
        os.path.join(item_dir, "portal_stone.png"): make_item(),
        os.path.join(item_dir, "dungeon_portal_stone.png"): make_dungeon_item(),
        os.path.join(item_dir, "wash_stone.png"): make_wash_item(),
        os.path.join(item_dir, "enchant_enhancer.png"): make_enhancer_item(),
        os.path.join(item_dir, "condensed_lapis.png"): make_condensed_lapis(),
    }

    for path, image in targets.items():
        image.save(path)
        with Image.open(path) as im:
            print(f"{path} -> {im.size} {im.mode}")



if __name__ == "__main__":
    main()
