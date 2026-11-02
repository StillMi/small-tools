"""把 smalltools 复制成独立的 smalltools 模组（modid / 包名 / 资源目录全换）。"""
from pathlib import Path
import shutil, re

WS = Path(r"F:\文档\deepseek-harness\default-workspace")
SRC = WS / "smalltools"
DST = WS / "smalltools"

OLD_ID = "smalltools"
NEW_ID = "smalltools"
OLD_PKG = "com.example.smalltools"
NEW_PKG = "com.example.smalltools"
OLD_MAIN = "SmallTools"
NEW_MAIN = "SmallTools"

if DST.exists():
    raise SystemExit(f"{DST} 已存在，先手动确认再删")

# 只复制源码和构建脚本，不带 .git / build 产物
IGNORE = shutil.ignore_patterns(".git", "build", ".gradle", "*.jar", ".idea", "*.iml")
shutil.copytree(SRC, DST, ignore=IGNORE)
print("copied:", DST)

# ---- 1. 包目录改名 ----
OLD_DIR = DST / "src/main/java/com/example/smalltools"
NEW_DIR = DST / "src/main/java/com/example/smalltools"
OLD_DIR.rename(NEW_DIR)
print("package dir renamed")

# ---- 2. 资源 / 数据目录改名 ----
for sub in ("assets", "data"):
    old = DST / "src/main/resources" / sub / OLD_ID
    if old.is_dir():
        old.rename(old.parent / NEW_ID)
        print(f"{sub} dir renamed")

# ---- 3. 全量文本替换 ----
TEXT_SUFFIX = {".java", ".json", ".toml", ".mcmeta", ".properties", ".md",
               ".py", ".ps1", ".gradle", ".txt", ".yml"}
count = 0
for path in sorted(DST.rglob("*")):
    if not path.is_file() or path.suffix.lower() not in TEXT_SUFFIX:
        continue
    try:
        text = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        continue
    original = text
    text = text.replace(OLD_PKG, NEW_PKG)
    text = text.replace(OLD_ID, NEW_ID)
    text = text.replace(OLD_MAIN, NEW_MAIN)
    if text != original:
        path.write_text(text, encoding="utf-8")
        count += 1
print("files rewritten:", count)

# ---- 4. 主类文件名改名 ----
main_old = NEW_DIR / f"{OLD_MAIN}.java"
if main_old.exists():
    main_old.rename(NEW_DIR / f"{NEW_MAIN}.java")
    print("main class file renamed")

# ---- 5. gradle.properties 元数据 ----
props = DST / "gradle.properties"
text = props.read_text(encoding="utf-8")
text = text.replace("mod_id=smalltools", "mod_id=smalltools")
text = text.replace("mod_name=Ender Portal Stone",
                    "mod_name=StillMi's Small Tools")
text = text.replace("mod_group_id=com.example.smalltools",
                    "mod_group_id=com.example.smalltools")
text = text.replace("mod_authors=you", "mod_authors=StillMi")
text = text.replace(
    "mod_description=A stone that opens a compact temporary two-way portal to its bound coordinates.",
    "mod_description=A small collection of handy tools for Minecraft.")
text = text.replace("mod_version=1.18", "mod_version=1.0")
props.write_text(text, encoding="utf-8")
print("gradle.properties updated")
