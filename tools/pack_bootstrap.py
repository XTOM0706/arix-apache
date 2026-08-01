"""把裁剪后的 Termux bootstrap 从 zip 重新打成 .tar.xz。

为什么换：同样的内容，zip(deflate) 19.92MB，xz 只要 12.72MB —— APK 直接少 7.2MB。
解码器不用新加依赖，`org.tukaani:xz` 本来就在 build.gradle 里（原先是给 .tar.xz 的
rootfs 准备的，那条路后来走了 OCI，依赖就闲着了）。

**preset 用 6 不用 9**：xz 的字典大小跟 preset 走，preset 9 是 64MB 字典 ——
解压端就得吃 64MB 内存，手表上很容易直接 OOM；preset 6 是 8MB 字典，代价只是多 0.4MB。

用法：python tools/pack_bootstrap.py <裁剪后的.zip> <输出.tar.xz>
（上游原件 → 裁剪见 tools/slim_bootstrap.py，两步是分开的）
"""
import sys, zipfile, tarfile, lzma, io, os

src, dst = sys.argv[1], sys.argv[2]
preset = int(sys.argv[3]) if len(sys.argv) > 3 else 6

z = zipfile.ZipFile(src)
infos = [i for i in z.infolist() if not i.is_dir()]

# 先在内存里拼 tar：条目路径与 zip 里完全一致（都相对 $PREFIX），
# 这样宿主侧 SYMLINKS.txt 那套重建软链的逻辑一个字都不用改。
buf = io.BytesIO()
# **必须 GNU_FORMAT**：Python 默认的 PAX 会把超长路径塞进 'x' 扩展头，
# 而解包端（DistroInstaller.extractTarStream）不认 'x'——会跳过扩展头、
# 拿 ustar 头里那个被截断的名字落盘，文件就散了。GNU 格式用 'L' 长名记录，解包端认。
with tarfile.open(fileobj=buf, mode="w", format=tarfile.GNU_FORMAT) as tf:
    for i in infos:
        data = z.read(i.filename)
        ti = tarfile.TarInfo(i.filename)
        ti.size = len(data)
        ti.mode = 0o755          # 解压后宿主还会整树 chmod，这里给个合理默认
        ti.mtime = 0             # 不带时间戳：同样的输入产出同样的包（可复现）
        tf.addfile(ti, io.BytesIO(data))
raw = buf.getvalue()

filters = [{"id": lzma.FILTER_LZMA2, "preset": preset}]
with open(dst, "wb") as f:
    f.write(lzma.compress(raw, format=lzma.FORMAT_XZ, filters=filters))

print("条目 %d，裸 tar %.2f MB → xz preset%d %.2f MB（原 zip %.2f MB）"
      % (len(infos), len(raw) / 1048576, preset,
         os.path.getsize(dst) / 1048576, os.path.getsize(src) / 1048576))
