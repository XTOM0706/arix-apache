# 内嵌 Termux bootstrap 构建配方

Arix 在自己进程内跑 Termux 需要一份**按 Arix 前缀重建**的 bootstrap——因为 Termux 二进制把
`/data/data/com.termux/files/usr` 焊死在 ELF 里（PT_INTERP、RUNPATH、脚本 shebang、apt 配置）。
直接用官方 zip 在 `com.arix.app` 下跑不起来。所以要用 termux-packages 构建系统，把前缀改成
`/data/data/com.arix.app/files/usr` 重新生成。**这一步在 Linux/Docker 里做，不在 Arix 的 Gradle 工程里。**

## 前提
- Linux（或装了 Docker 的机器）。arm64-v8a 手表 → 目标架构 `aarch64`。

## 步骤

```bash
git clone https://github.com/termux/termux-packages
cd termux-packages

# 关键：把包名/前缀改成 Arix 的（三处，改 scripts/properties.sh 或用环境变量覆盖）
export TERMUX_APP_PACKAGE=com.arix.app
export TERMUX_BASE_DIR=/data/data/com.arix.app/files
export TERMUX_PREFIX=/data/data/com.arix.app/files/usr
export TERMUX_ANDROID_HOME=/data/data/com.arix.app/files/home

# 用官方 Docker 环境构建 bootstrap（只出 aarch64，手表够用）
./scripts/run-docker.sh ./scripts/build-bootstraps.sh --architectures aarch64
# 产出：bootstrap-aarch64.zip（内含 bin/lib/etc + SYMLINKS.txt，全部指向上面的前缀）
```

> 若 `build-bootstraps.sh` 不吃环境变量，直接改 `scripts/properties.sh` 里的
> `TERMUX_APP_PACKAGE` / `TERMUX_BASE_DIR` / `TERMUX_PREFIX` / `TERMUX_ANDROID_HOME` 再跑。

## 放进 Arix

把产出的 zip 放到：

```
app/src/main/assets/bootstrap-aarch64.zip
```

重新 `assembleDebug` 即可。首次调用 `linux_exec` 时 `EmbeddedTermux` 会自动解压进
`filesDir/usr`、按 SYMLINKS.txt 建符号链接、chmod 可执行，之后直接复用。
工作目录默认 = AI 工作区 `filesDir/ai_workspace`，和文件工具同一批文件。

## 验证（真机，targetSdk 已 28）

```
linux_exec: uname -a && echo $PREFIX && bash --version
linux_exec: pkg install -y python && python -V     # apt 全生态
```

## 想更小
- 只打 aarch64（已是）。
- bootstrap 本身 ~30-45MB；可在 termux-packages 里裁掉不需要的默认包，或首次联网 `pkg install` 按需装（bootstrap 只留最小 + apt）。
- 备选：不放 bootstrap，改放静态 `busybox`（`app/src/main/jniLibs/arm64-v8a/libbusybox.so`）——无 apt 但几 MB，`linux_exec` 会自动回退用它。
