"""把最新构建复制到 PCL 的 mods 目录，并删除本模组的历史版本。

只处理 smalltools-*.jar，绝不动别的模组。
"""
from pathlib import Path
import hashlib
import re
import shutil
import sys

ROOT = Path(__file__).resolve().parent.parent
LIBS = ROOT / "build" / "libs"
TARGET = Path(r"H:\PCL\.minecraft\versions\1.21.1\mods")
PATTERN = re.compile(r"^smalltools-(\d+(?:\.\d+)*)\.jar$", re.IGNORECASE)


def version_key(text: str):
    """1.10 要排在 1.9 之后，所以按数字分段比较。"""
    return tuple(int(p) for p in text.split("."))


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

    candidates.sort()
    _, newest = candidates[-1]
    print(f"最新构建：{newest.name}")

    # 先看清要删哪些，再动手
    stale = []
    for jar in TARGET.iterdir():
        if jar.is_file() and PATTERN.match(jar.name):
            stale.append(jar)

    for old in stale:
        old.unlink()
        print(f"删除旧版本：{old.name}")

    dest = TARGET / newest.name
    shutil.copy2(newest, dest)

    src_hash = hashlib.sha256(newest.read_bytes()).hexdigest()
    dst_hash = hashlib.sha256(dest.read_bytes()).hexdigest()
    if src_hash != dst_hash:
        print("复制后哈希不一致，请重试")
        return 1

    print(f"已部署：{dest}")
    print(f"大小：{dest.stat().st_size} 字节")
    print(f"SHA256：{src_hash}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
