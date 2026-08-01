"""把 Termux 官方 bootstrap 里手表上用不到的东西剥掉，重新打包。

剥的是文档/man/info/C 头文件/自带测试——手表终端上没人 man ls，
真要编译东西的走发行版容器。SYMLINKS.txt 里指向被删目录的条目也一并去掉，
否则解压后是一堆断链。
"""
import sys, zipfile, shutil, os

SRC = sys.argv[1]
DST = sys.argv[2]
MODE = sys.argv[3] if len(sys.argv) > 3 else "apply"

DROP_PREFIXES = (
    "share/man/", "share/doc/", "share/info/",
    "include/", "libexec/installed-tests/",
    "share/locale/",          # 非中英文的翻译目录，下面单独放行
    "share/gtk-doc/",
)
# bash-completion 留着：它是实打实的交互体验（敲 apt inst<TAB>），而且才几十 KB
KEEP_LOCALE = ("share/locale/zh", "share/locale/en")

# **许可文本一律不删**：bootstrap 里装的是 GPL/Apache/MIT 一堆上游包，
# share/doc/<pkg>/copyright 就是随二进制分发所必须的那份许可，NOTICE 也明确指向它。
# 所以 share/doc 只丢 README/changelog/示例，copyright 与 LICENSES/ 全留。
LICENSE_NAMES = ("copyright", "license", "licence", "notice", "copying")


def is_license(name):
    low = name.lower()
    if "licenses/" in low or "license/" in low:   # share/LICENSES/*.txt、share/doc/*/licenses/*.md
        return True
    base = name.rsplit("/", 1)[-1].lower()
    return any(base.startswith(k) for k in LICENSE_NAMES)


def dropped(name):
    if is_license(name):
        return False
    if name.startswith("share/locale/"):
        return not name.startswith(KEEP_LOCALE)
    return name.startswith(DROP_PREFIXES)


zin = zipfile.ZipFile(SRC)
infos = zin.infolist()

keep, drop = [], []
for i in infos:
    (drop if dropped(i.filename) else keep).append(i)

drop_c = sum(i.compress_size for i in drop)
drop_u = sum(i.file_size for i in drop)
keep_c = sum(i.compress_size for i in keep)
print("剥掉 %d 个条目：压缩 %.2f MB / 解压后 %.2f MB"
      % (len(drop), drop_c / 1048576, drop_u / 1048576))
print("保留 %d 个条目：压缩 %.2f MB" % (len(keep), keep_c / 1048576))

by_top = {}
for i in drop:
    top = "/".join(i.filename.split("/")[:2])
    by_top[top] = by_top.get(top, 0) + i.compress_size
for k, v in sorted(by_top.items(), key=lambda x: -x[1])[:12]:
    print("   %-34s %6.2f MB" % (k, v / 1048576))

if MODE != "apply":
    sys.exit(0)

# SYMLINKS.txt：U+2190 分隔的 `目标←链接`，把指向被删目录的条目滤掉
sym_name = "SYMLINKS.txt"
sym_new = None
if sym_name in zin.namelist():
    txt = zin.read(sym_name).decode("utf-8")
    lines, gone = [], 0
    for raw in txt.splitlines():
        line = raw.strip()
        if not line:
            continue
        idx = line.find("←")
        if idx <= 0:
            lines.append(line)
            continue
        target = line[:idx]
        link = line[idx + 1:].lstrip("./")
        if dropped(link) or dropped(target.lstrip("./")):
            gone += 1
            continue
        lines.append(line)
    sym_new = ("\n".join(lines) + "\n").encode("utf-8")
    print("SYMLINKS.txt：滤掉 %d 条断链，剩 %d 条" % (gone, len(lines)))

zout = zipfile.ZipFile(DST, "w", zipfile.ZIP_DEFLATED, compresslevel=9)
for i in keep:
    if i.filename == sym_name and sym_new is not None:
        zout.writestr(i, sym_new)
    else:
        zout.writestr(i, zin.read(i.filename))
zout.close()
zin.close()
print("写出 %s：%.2f MB（原 %.2f MB）"
      % (DST, os.path.getsize(DST) / 1048576, os.path.getsize(SRC) / 1048576))
