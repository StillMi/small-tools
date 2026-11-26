"""把最新构建复制到 PCL 的 mods 目录，并删除本模组的历史版本。

只处理 smalltools-*.jar，绝不动别的模组。

顺序很重要：先复制到临时文件并校验哈希，成功之后才动已有文件。
游戏开着的时候 jar 会被占用，copy2 / os.replace 都会抛 PermissionError，
这时目标目录里的旧版本必须原样留着 —— 否则一次失败就把模组装没了。
"""
from pathlib import Path
import hashlib
import os
import re
import shutil
import sys

ROOT = Path(__file__).resolve().parent.parent
LIBS = ROOT / "build" / "libs"
TARGET = Path(r"H:\PCL\.minecraft\versions\1.21.1\mods")
PATTERN = re.compile(r"^smalltools-(\d+(?:\.\d+)*)\.jar$", re.IGNORECASE)

# 版本号补齐到 4 段再比，避免 "1.01" 与 "1.1" 都得到 (1, 1) 而并列
VERSION_PARTS = 4


def version_key(text: str):
    """1.10 要排在 1.9 之后，所以按数字分段比较；位数补齐到 4 段。"""
    parts = [int(p) for p in text.split(".")]
    return tuple(parts + [0] * (VERSION_PARTS - len(parts)))


def sha256_of(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    if not TARGET.is_dir():
        print(f"目标目录不存在：{TARGET}")
        return 1

    candidates = []
    for jar in LIBS.glob("smalltools-*.jar"):
        m = PATTERN.match(jar.name)
        if m:
            candidates.append((version_key(m.group(1)), jar))
    if not candidates:
        print("build/libs 里没有 smalltools-*.jar，先打包")
        return 1

    # 版本键相同时按文件名兜底，排序结果不依赖 Path 之间的比较
    candidates.sort(key=lambda item: (item[0], item[1].name))
    newest_key, newest = candidates[-1]
    print(f"最新构建：{newest.name}")

    # 先落到 .jar.tmp，校验通过再 os.replace 换成正式名
    dest = TARGET / newest.name
    tmp = dest.with_suffix(".jar.tmp")
    src_hash = sha256_of(newest)
    try:
        shutil.copy2(newest, tmp)
        tmp_hash = sha256_of(tmp)
        if tmp_hash != src_hash:
            print(f"复制后哈希不一致，旧版本保持不动（tmp {tmp_hash[:12]} != {src_hash[:12]}）")
            return 1
        os.replace(tmp, dest)
    except OSError as exc:
        print(f"复制失败，旧版本保持不动：{exc}")
        return 1
    finally:
        # 成功时 os.replace 已经把 tmp 挪走了；失败时把残留清掉
        if tmp.exists():
            tmp.unlink()

    # 新版本已经落地，这时才清理比它旧的历史版本。
    # 比它新的已装版本不动 —— 否则 build/libs 落后于 mods 目录时会变成降级。
    for jar in sorted(TARGET.iterdir()):
        if not jar.is_file() or jar == dest:
            continue
        m = PATTERN.match(jar.name)
        if m and version_key(m.group(1)) < newest_key:
            jar.unlink()
            print(f"删除旧版本：{jar.name}")

    print(f"已部署：{dest}")
    print(f"大小：{dest.stat().st_size} 字节")
    print(f"SHA256：{src_hash}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
