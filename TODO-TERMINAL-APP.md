# 终端拆分为独立 App + proot（完全体）

**目标**：让内嵌终端像真·Termux 一样，`apt`/`pkg install` 随便装、**不打架**。
根因=旧方案把每个二进制里写死的 `/data/data/com.termux/files/usr` 前缀改写成
`com.arix.app`（`termux2xtom`）→ 与官方滚动源版本对不齐 → 依赖打架 / dpkg 死循环。
**解法=proot**：把 `/data/data/com.termux/files` **虚拟重定向**到 App 能写的目录，
于是装**原封不动的官方 Termux 包**、路径全部命中 → 兼容性 ≈ 真 Termux。

**架构选型（完全体，仿 Operit）**：终端拆成独立 App `com.arix.terminal`（自己的
applicationId/进程），承载 proot + 官方 bootstrap；主 App 通过**绑定服务(AIDL)** 驱动。
好处：主 App 可升 targetSdk、APK 瘦身、终端可独立更新。
（更名到 Cyane 时，`com.arix.terminal` 随全局一起改。）

## 素材（P0 已取得并验证 · AArch64）
- proot `5.1.107.84`：`bin/proot` + `libexec/proot/loader` + `loader32`
- libtalloc `2.4.3`：`libtalloc.so.2.4.3`（proot 依赖）
- 官方 bootstrap `2026.07.12-r1`：`bin/{bash,apt,dpkg,apt-get,login}` + `SYMLINKS.txt`(1160)
- 来源：packages.termux.dev（proot/libtalloc）、github.com/termux/termux-packages releases（bootstrap）
- License：proot=GPL-2.0-or-later、libtalloc=LGPL-3.0-or-later、libandroid-shmem=BSD-3-Clause → 当独立可执行/动态库 fork·exec（聚合，不传染主 App），
  需在终端 App NOTICE 附许可文本 + 源码获取指引、不改动它们。

## 阶段
- [x] **P0 取件验证** —— proot/loader/libtalloc/bootstrap 齐全，路径均为 `com.termux` 前缀（实锤 proot 对症）
- [x] **P1 模块脚手架** —— `:terminal` 独立 App（targetSdk28/arm64/AIDL），绑定服务桩，
      编译出 `terminal-debug.apk`(2.08M)；AIDL 对接接口定形；签名级权限保护
- **P2 proot 跑通**（用户选 P2b 交互式；人和 AI 都能用：人走 PTY 会话、AI 走 exec，同一环境）
  - [x] **P2b-1** 素材入 assets（proot/loader/libtalloc 入库、30M bootstrap gitignore）；
        `TerminalEnv` 装环境（解压+SYMLINKS+安置 proot+chmod）+ proot argv/env 构造；服务环境安装/重置实装。编译过。
  - [x] **P2b-2** native `forkpty`（CMake+JNI `Pty`/`libxtompty.so`，arm64 编出）→ 交互会话 openSession/write/resize/signal/close，reader 线程流式回传。编译过。
  - [x] **P2b-3** `exec` 一次性命令（proot 管道，AI 用，redirectErrorStream 干净输出）。编译过。**真机验 apt 不打架仍待做。**
- [x] **P3 服务/IPC** —— AIDL 定稿：环境安装/重置、一次性 exec、长驻管道进程(openProcess/writeStdin/closeStdin/killProcess)。
      **交互会话(PTY)从 AIDL 拿掉了**：人用的终端由终端 App 自己的 TermActivity 直接跑(Termux TerminalView 自带 pty)，
      服务只负责「AI 的一次性命令」与「MCP stdio 的长驻管道」。
- [~] **旧终端下线（已做）** —— 删 `EmbeddedTermux.kt` + `assets/xtom/*` + 140M 孤儿 bootstrap（主 APK -140M）。
      对抗审查(2 agent)：无崩溃，但 **busybox 从未打包**(`jniLibs` 不存在)→ `linux_exec`/`code_runner`(非算术)/MCP-stdio(node·python)/终端页 **接入前全不可用**。
      已把误导 AI/用户的描述改诚实：`PackageManager`(local_linux)·`LocalLinuxTool`(desc+未装配提示)·`EnhancedTools`(code_runner desc+注释)·`MainActivity`(终端设置副标题)·`StdioMcpClient`(错误文案)·`ApiProviders`(llama.cpp)。
      残留(无害未改)：`I18nStrings` 那句 xtom-install-node 已成死翻译数据(无 tr 调用点)；`StdioMcpClient` 头注释。
- [x] **P4 主 App 接线** —— TerminalPage 改为拉起 TermActivity(不再自渲染 PTY)；`linux_exec` 走服务 exec；
      **MCP-stdio 已接**(优先 openProcess 跑 proot 里的 node/python，未装终端 App 才回落系统 sh)；
      **第二 APK 已内置**(gradle `bundleTerminalApk` 拷进 assets)+终端页一键安装(FileProvider→系统安装器)。
      `code_runner` 也已接回（见 P5）。
- **真机迭代（用户实测中）**：① proot 缺 `libandroid-shmem.so` → 补齐 ② 绑定式失败(只读/data/data建挂载点) → 改 **`-r rootfs`**(bootstrap 物理放 rootfs/data/data/com.termux/files/usr) + bind /system·/apex ③ `-0` 致 Termux pkg 拒绝 root → **去掉 -0**(Termux 以普通 uid 跑) → **apt/pkg install 真通了、不打架** ④ 过滤 linker /linkerconfig 无害警告。
- **真终端 UI（用户要「像 Termux」+TUI 支持，选真组件）**：
  - [x] **vendor Termux `terminal-emulator`+`terminal-view`**(Apache-2.0，自 jackpal ATE，非 GPL) 为 `:terminal-emulator`/`:terminal-view` 模块(含 `libtermux.so` pty JNI)
  - [x] 终端 App `TermActivity`：TerminalView + TerminalSession 跑 proot → 完整 VT(颜色/光标/全屏 TUI：termux-change-repo/vim/top)。设为 launcher + 供主 App Intent 拉起
  - [x] 主 App 终端页改为「打开终端」入口(Intent 拉 TermActivity)；AI linux_exec 仍走服务 exec
  - [ ] **真机验**：真终端能否开、颜色/光标、termux-change-repo 等 TUI、键盘输入、额外按键条(Ctrl+C/方向键/ESC)
  - [x] **额外按键条** `ExtraKeysView`：ESC/CTRL/ALT/TAB/方向键/符号/HOME/END/PGUP/PGDN/切键盘，横向可滚；
        CTRL/ALT 粘滞(点=管一个字符、长按=锁定)。**vendor 未改**：一度打过一个
        `TerminalView.sendTextToTerminal` 的补丁，后经复核是错的（`inputCodePoint` 本就 `|| readControlKey()`，
        IME 字符早就吃得到修饰键，补丁只让修饰键被读两次）——已整个还原，vendor diff 为空。
- **`TerminalRender`**（app）：行缓冲(\r 覆盖+ANSI 剥+\e[K)，给 AI linux_exec 输出用。
- [x] **已清理**：删掉自写的 `Pty.kt`/`pty.c`/CMakeLists + build.gradle 的 externalNativeBuild/ndkVersion；
      服务的 openSession/write/resize/signal/closeSession 换成管道式 openProcess 四件套。
- [~] **P5 收尾** —— [x] NOTICE 已补(proot GPL-2.0-or-later 含源码获取指引、libtalloc LGPL-3.0、libandroid-shmem BSD-3、
      官方 bootstrap、terminal-emulator/terminal-view Apache-2.0)；[x] linker 警告消噪(rootfs 里造 ld.config.txt+删失败的二段脚本)；
      [x] 旧自编 bootstrap 流水线 `termux-build/` 已删（改前缀那套被 proot+官方 bootstrap 取代；
          同源的 `TERMUX-BOOTSTRAP-BUILD.md` 也已过时，待用户点头一并删）；
      [x] `code_runner` 接回终端服务 —— 原实现把片段写进主 App 的 `ai_workspace/.code_runner/`
          再用相对路径跑，而 `linux_exec` 已在独立终端 App 的 proot 里（另一 uid/另一 HOME）读不到，
          装了终端反而必坏；改为 heredoc 把代码随命令送过去、在终端侧 `$TMPDIR` 落盘执行后自删。

## 后端/环境可切换（proot-distro 化）—— 分 4 批，用户选「先 A」

目标两轴：**执行后端** proot↔chroot、**容器内跑什么** Termux↔其它发行版；两种入口（App UI + 终端内 `xtom-env` 指令）。

- **A. 执行后端 proot↔chroot（仅 Termux）—— [!] 代码已实现+编译过，但 chroot 部分经红队判定「同 uid 架构下=一键 root 后门」，⛔ 安全阻断，待用户定方向后才可开放。proot 部分安全、可用。**
  - 🔴 **红队结论（2026-07-21）**：根因三件套叠加——① guest(Termux) 与终端 App **同 uid** ② rootfs 在 App 可写目录 ③ chroot 以 **uid 0** 跑该 rootfs。→ guest 里任何 app-uid 代码（AI 的 linux_exec / apt 装的包 / 恶意插件 / 用户命令）可**预埋 payload**（覆写 rootfs 里的 `bin/bash`、写 `etc/profile`/`profile.d/*.sh`/`~/.bash_profile`），用户下次开 chroot 交互终端时 **root 的 `bash --login` 就地执行它 = app-uid→设备 root**，仅一次 su 授权为门（而那正是用户本就要点的）。
    - SAFE_SHELL 白名单/内联脚本防 TOCTOU/非交互回落 proot **都成立但只在「注入字符串」层，够不到这个设计层根因**（攻击者不需要任何特殊字符，直接让合法路径指向自己写的二进制）。
    - 附带：chroot 内是**真 uid 0** + bind 了 `/dev`(含 `/dev/block`)/`/proc` → 不是被 chroot 关住的 root，是设备级 root（可读写裸分区/他进程）。
    - **已防住的**：非交互(AI/插件/MCP)一律 proot 拿不到 root（`launchIsChroot=interactive&&CHROOT`，全仓 interactive=true 仅 TermActivity 一处）；内联 su -c 不落盘；元字符注入；proot 路径无回归。**唯一残留**：automation 能预埋、等人开 chroot 时替它以 root 跑（延迟可达）。
  - **待用户决策（三选一，见对话）**：(a) 下线 chroot，只留 proot；(b) chroot 后 setuid 降到 app-uid（=proot 定位、丧失 root 收益，且需 rootfs 内有 setpriv/helper，Termux base 未必有）；(c) 保留但硬门控+醒目警告「给容器设备级 root，勿在其中跑 AI/不可信包」+ 补 3 条中危缓解（启动复验 su / mount 目标 realpath 防符号链接 TOCTOU / 最小化 /dev 不给 /dev/block、不 bind /proc）。**在定下来之前，chroot 选项应视为不可安全启用。**
  - 已实现的代码骨架（决策后按方向调整）：`TerminalEnv.buildProotArgv`→`buildLaunchArgv` 分派、`Backend{PROOT,CHROOT}`+prefs、`suAvailable/verifyRoot`、`buildChrootArgv`(内联 su -c)、外观页「运行后端」区。
  - 核心抽象：`TerminalEnv.buildProotArgv` → 泛化成 `buildLaunchArgv(interactive,command)` 按 `activeBackend` 分派；
    三处调用点（`TermActivity` 交互会话 / `Proot.run` AI 一次性 / `TerminalService.startProot` 管道）统一走它。
  - `Backend{PROOT,CHROOT}` + `activeBackend/setBackend`（存 `term_ui` 的 `backend`，默认 proot）；
    `suAvailable()` 静默探 su 决定 UI 显不显 chroot；`verifyRoot()` 真跑 `su -c id -u`（弹授权框，走 IO）确认再切。
  - chroot 启动：`buildChrootArgv` 生成一次性脚本 → `su -c "sh <script>"`。脚本先 re-exec 进 `unshare -m`
    私有挂载命名空间（退出即自动卸载、不污染全局），再 bind /dev /proc /sys /dev/pts /system /apex /vendor /product +
    共享目录，`export` guest 环境，`chroot rootfs bash --login -i|-c`。同一份 rootfs 与 proot `-r` 完全通用。
  - 交互脚本复用稳定名 `chroot-login.sh`；一次性命令用临时脚本 `chroot-exec*.sh`（防并发覆盖）+ 2 分钟清残。
  - `buildEnv` 也按后端分派：chroot 只给 su 进程一个能找到 su/sh/unshare/mount/chroot 的 PATH，guest 环境在脚本里 export。
  - UI：外观页新增「运行后端」区（proot / chroot；chroot 仅 `suAvailable()` 时出现，选它先 `verifyRoot` 拿到 root 才切）。
  - **⚠️ chroot 全程需 root 真机验**：①挂载能否成功 ②`/dev/pts` 能否给 PTY（交互终端能否打字/画 TUI）
    ③退出后挂载是否干净（unshare -m 生效？没有 unshare 会残留）④AI 一次性命令按 STOP 时 su 的孙子进程会不会成孤儿
    （chroot 无 proot 的 `--kill-on-exit`，已在注释标记为已知风险）⑤proot 仍是默认、免 root 那条不该有任何回归。
  - 产物：`terminal-debug.apk`（直接侧载即可测 A）。**注**：若要经主 App「下载安装」入口分发，需再 `bundleTerminalApk`+`:app:assembleDebug` 重打内置包；直接侧载测试不需要。
- **B. 多发行版跑在 proot 里 —— [x] 第一版已做（2026-07-21，编译+assembleDebug 过，⚠️全未真机验）。** 4 子agent 并行(查源/装rootfs/UI)+主线程写共享脊梁。
  - **共享契约** `EnvModel.kt`：`DistroSpec`(id/label/rootfsUrl/rootfsUrlCn/loginUser/home) + `EnvRegistry`(TERMUX 常量、DISTROS 清单、activeEnv/setActiveEnv 存 term_ui/active_env、distroDir/distroRootfs=filesDir/distros/<id>/rootfs、isDistroInstalled(有 bin/sh)、installedDistros、removeDistro)。**并行铁律**：三方只 import 这份、绝不另建同名模型。
  - **装** `DistroInstaller.kt`：自带 HttpURLConnection 下载(主源→国内镜像回退、手动跟重定向、60s 超时、取消安全删半截)、magic 嗅压缩(gzip 解/xz 明确报错不支持/裸 tar)、**手写最小 tar reader**(typeflag 0/5/2/1/L/K、ustar prefix、zip-slip 防护)、**硬链接→guest 绝对路径软链**(我修的：原用宿主绝对路径 proot 里解不到→Alpine busybox 全废)、strip 单层包裹、整树 chmod 0755、写 resolv.conf。**XZ 不支持**(Java 无解码器、未加依赖)→只能用 .tar.gz 源。
  - **通用发行版 proot 启动**(`TerminalEnv.buildDistroProotArgv` + `ensureDistroRuntime`，按 proot-distro v4.38 实测 flag)：`--link2symlink -0 -r rootfs -w /root` + bind(/dev、/dev/urandom:/dev/random、/proc、/proc/self/fd*、/sys、伪造 /proc/.loadavg 等 7 项、/sys/.empty:/sys/fs/selinux、/system /vendor /apex /linkerconfig /storage /sdcard)+ `env -i HOME/LANG/PATH/TERM/TMPDIR /bin/{bash,sh} -l`。`ensureDistroRuntime` 幂等预置伪造 /proc + DNS(阿里/DNSPod/Google)+hosts+挂载点。
  - **分派**：`buildLaunchArgv` 先看 `activeDistro`(激活且已装的发行版)→通用 proot；否则 Termux(proot/chroot)。发行版**不走 chroot**。`buildEnv(ctx,interactive)` 三分支(chroot最小/发行版只给proot自身/Termux全套)。
  - **UI** `EnvActivity.kt`(抽屉入口 `TermDest.ENV` + TermActivity 自建抽屉 + Manifest 已接)：当前环境单选(Termux+已装发行版)、可装列表(装/删/设为当前、进度、取消)。
  - **现状**：Alpine(~4MB gz)、Ubuntu 24.04(~30MB gz)可装；**Debian 只有 .tar.xz 源→暂空置不可装**(待加 `org.tukaani:xz` 依赖)。硬编码点版本(3.24.1/24.04.4)会过期，TODO 改动态解析目录。
  - **⚠️ 真机必验**：装 Alpine/Ubuntu 能否下载+解压成功、切过去开终端能否起 shell/apt-apk 能否装包联网(DNS)、`rm -rf` 确认被 proot 挡在宿主外、硬链接修复后 Alpine busybox 命令是否都通、共享目录、切回 Termux 无回归。
- **C. 终端内 `xtom-env` 指令**（list/use/install/remove，经共享目录控制文件通知 App 重起会话）—— 未开工。
- **D. rootfs 下载镜像/动态版本解析 + XZ(Debian) + i18n + 各发行版 NOTICE/许可 + 收尾。**

## 2026-07-25 大改：容器 OCI 化 + 系统终端 + 多会话 + 美化/光标（编译过 · **真机全未验**）

用户提的 5 条：①参照 ZeroTermux 补功能 ②切了容器却还在 Termux 里 ③加发行版 ④加"真跑在系统里、能 su"的终端 ⑤更多发行版+套餐+自创美化+Neovide 光标。

### ② 修 bug：切换容器不生效（**根因不是切换没写进去**）
`active_env` 是切了的，但**会话的 argv 只在起会话那一刻读一次设置**，屏幕上那条会话不会自己变身。
解法：`TerminalEnv.sessionSignature(ctx)`（环境 id + 后端 + shell + 系统 root 开关），
每条会话记住出生时的指纹，`TermActivity.onResume` 一比对，不一致就自动重开当前会话。
顺带把"改 shell / 改后端要手动重开终端"那两条提示也消灭了（同一机制覆盖）。

### ④ 系统终端（新环境 `EnvRegistry.SYSTEM`）
直接跑 `/system/bin/sh`，不套任何容器：看得到手机真实的 /data /sdcard /proc，`pm`/`am`/`dumpsys`/
`logcat`/`getprop`/`settings` 直接可用。设置里可开"以 root 启动"（用 su，每次弹授权框；没 root 时灰置）。
`$HOME` = `filesDir/sysroot-home`（内含指向共享目录的 `shared` 软链，好让 `xtom-env` 也能用）；
安卓的 sh 是 mksh，交互时读 `$ENV` → 美化配置就挂那儿。
- **安全红线（照搬 chroot 那次红队结论）**：系统终端**只对交互终端生效**。AI 的 `linux_exec`、
  插件、MCP（interactive=false）遇到 SYSTEM 一律回落 proot —— 否则用户在 UI 上点一下，
  AI 就静默拿到了设备 shell，开了 root 更等于把 su 递给 AI。见 `TerminalEnv.isSystemEnv`。

### ③⑤ 发行版改走 OCI/Docker 镜像（用户拍板）
硬编码 rootfs 直链这条路已被上游打脸：Arch 官方 rootfs 涨到 780MB、openSUSE 的 lxc 包下架、
Alpine/Ubuntu 点版本写死会过期。新版 proot-distro 也改成拉容器镜像了。
- `OciImagePuller.kt`：Registry HTTP API v2 只读子集 —— 401 按 `WWW-Authenticate` 换匿名 token
  （Docker Hub/ghcr/quay 一套流程）→ manifest list 里挑 `linux/arm64`（挑不到**明确报错**，
  绝不静默装个跑不了的）→ 逐层 blob **边下边解**（不落中间文件，手表存储金贵）→ 按 OCI 规范处理
  `.wh.*` / `.wh..wh..opq` whiteout。跨主机重定向到对象存储时**不能再带 Authorization**（会 400）。
  国内加速：整体换主机名重试（daocloud / 1ms.run / dockerproxy）。
- 清单 9 个：Alpine / Ubuntu 24.04 / Debian / Kali / Fedora / AlmaLinux 9 / openSUSE TW / Arch ARM / Void。
  外加**自定义镜像**（填任意 repo:tag，加之前先 `probe()` 探架构，省得下完几十兆才发现没 arm64）。
- `DistroInstaller.install` 按 `DistroSpec.source` 分派（Oci / Tarball），tar reader 抽成
  `extractTarStream(ins, dir, whiteout)` 两边共用。

### ⑤ 套餐（精简/办公/专业）
`DistroProvision`：按包管理器族（apt/apk/pacman/dnf/zypper/xbps）出包名表 + 装包命令。
装完把脚本写进容器（`~/.xtom/provision.sh` + `/etc/profile.d/00-xtom-provision.sh` + `xtom-setup`），
**首次进入该环境时自动跑**——那时才有容器可跑、也才好把过程摊给用户看；跑完打标记不再跑，可随时换套餐重跑。
Arch 那条要先 `pacman-key --init/--populate archlinuxarm`，否则签名校验必挂（ARM 版 Arch 老坑）。

### ① ZeroTermux 借鉴（四条全做了）
- **一键换源**（纯宿主侧写文件，不进容器不联网）：Termux/Debian 系/Alpine/Arch/Void 整份重写，
  Fedora·Alma·openSUSE 走 .repo 前缀替换（注释 metalink/mirrorlist、baseurl 换镜像站）。
  **版本代号一律从容器里的 `/etc/os-release` 读**，写死 codename 会在上游滚版本后把源改坏；
  **arm64 的 Ubuntu 走 `ports.ubuntu.com/ubuntu-ports`**（archive 那个只有 x86，用错全 404）。
  Ubuntu 24.04/Debian trixie 是 deb822 `.sources` 格式，存在就改它。
- **多会话标签**：一个 TerminalView + N 个 TerminalSession（同 Termux 套路），顶栏可横滑切换，
  `＋` 选环境开新的，长按标签关。每条记住自己的环境（左边 Termux 编译、右边 Ubuntu 跑服务、再来个系统 shell 看日志）。
  会话**起来不到 2 秒就结束**时故意不关标签——那多半是没起成（su 被拒/rootfs 坏），一闪而过用户看不到错误。
- **备份/恢复**：rootfs → `.tar.gz` 到 `/sdcard/Arix/backups`。自己写了个最小 tar writer：
  **zip 存不了符号链接**，而 rootfs 里软链遍地（Alpine 的 busybox applet、各家 lib），用 zip 备份等于备坏。
- **快捷命令**：顶栏「⚡」开一排按钮，内容按当前环境的包管理器自动给（在 Alpine 里不会给 apt-get）；
  系统终端另给一套（ps/getprop/logcat/dumpsys…）。危险的留 `run=false` 只填不回车。

### ⑤ 自创美化（学 fish）+ Neovide 光标
- `TermBeautify`：**不装 oh-my-zsh/p10k 那种重框架**，自己生成 `~/.xtomrc.sh`（bash/zsh/mksh 内部分支）
  + `config.fish`，再往 `.bashrc/.zshrc` 插一行带标记的 source（幂等、用户自己的内容一字不动）。
  4 种提示符：纯净/Arix/双行流/胶囊。**一律不用 Nerd Font**——"胶囊"那种 powerline 观感用反色背景块做，
  任何等宽字体都画得对。"学 fish"学的是体验：bash/zsh 上绑 `history-search-backward`
  复刻"敲一半按 ↑ 按前缀翻历史" + 打开补全/autocd；真装了 fish 就用它原生的自动建议。
  提示符转义**按 shell 分开写**（bash 要 `\[..\]`、zsh 要 `%{..%}`、mksh 两者都不认要塞真 ESC 字节），
  包错了光标位置就算错、行首打字会覆盖提示符。
- **Neovide 光标**：`CursorOverlayView` 盖在字符矩阵之上——位置做**指数逼近**（按真实 dt，掉帧/高刷都一致速度），
  拖尾是"当前矩形 ↔ 目标矩形的**凸包**"（停下时凸包退化成矩形，尾巴自然消失，比画一串残影干净）；
  呼吸是正弦淡入淡出不是硬闪；**闲下来就停帧**不空转。必须 Choreographer 手推（手表动画缩放=0 会让补间直接跳终点）。
- **vendor 补丁 #1（terminal-view，Apache-2.0，已在 NOTICE 声明修改）**：
  `TerminalRenderer.mHideCursor` + `getCellTopOffset()`、`TerminalView.setCursorHidden()`。
  不改是做不到的：光标跟文字同批画，且 TerminalView 每次按键都强制把 blink 置 true，外部藏不住它。

### 追加①：软键盘长按退格连续删除（vendor patch #2）
用户报"长按删除不连续"。**不是按键条那个 ⌫**（它本来就有连发），是**输入法**的退格。
根因：BaseInputConnection 背后的 Editable 在终端里恒为空（字符一提交就送进 pty 然后 clear），
输入法长按时反复问 `getTextBeforeCursor()`，拿到空串就判定"前面没东西可删"→ 停止连发，只删一个。
解法：覆写 `getTextBeforeCursor()` 回一段占位空格（上限 64 字符）、`getTextAfterCursor()` 回空；
加 `setImeDeleteRepeatWorkaround(boolean)` 开关（个性化页「输入法」一节，默认开）留后路。
改完要 `restartInput()` 才让输入法重建连接读到新行为（已在 `TermActivity.onResume` 里做）。
顺带把**按键条**的连发也加固了：按住期间 `requestDisallowInterceptTouchEvent(true)`
（表盘上手指抖过 touchSlop 会被外层 HorizontalScrollView 抢走手势 → CANCEL → 连发断掉），
并加了越删越快（55ms→22ms）和"连发过就吃掉抬手那次 click"（否则松手多删一个）。

### 追加②：功能重新归类（IA）
原先「运行后端」放在外观页、环境相关散在两处。按**"跑什么/怎么跑" vs "长什么样/什么手感"**重排：
- 抽屉顺序：终端 → **运行环境** → 软件包 → 文件 → **个性化**（原「外观」改名）。
- **运行环境页**：当前环境 → 系统终端 root → **运行后端（从外观页搬来）** → 软件源 → 可安装发行版(套餐/自定义镜像) → 备份恢复。
- **个性化页**：配色方案 → 字体 → 提示符美化 → 光标 → 背景 ‖ Shell → **输入法（新）** → 快捷命令。
- 新增 `res/drawable/ic_nav_env.xml`（原来「运行环境」和「外观」共用一个图标）。

### ⚠ 全部只编译验证（`:terminal:assembleDebug` BUILD SUCCESSFUL，产物 terminal-debug.apk 51.6M），真机一条没验
必验清单：① 切容器回终端是否自动重开、② 装一个 OCI 发行版能否成功（arm64 挑选/whiteout/国内镜像回退）、
③ 套餐首次进入是否真的自动装、④ 换源后 apt/apk 是否真的走镜像、⑤ 系统终端能否起、su 是否弹框、
⑥ 多标签切换/长按关闭、⑦ 备份→删环境→恢复是否真的还原（软链是否完好）、⑧ 光标动画是否流畅且不吃电、
⑨ 美化在 bash/zsh/fish/mksh 四种 shell 下提示符是否都不错位。

## 旁路需求：ADB 保活（用户 2026-07-21 提，源自 mo2/tmoe 也带这功能）—— 未开工，研究中
- 目标（推测待确认）：在无 PC 情况下用设备自身保持无线 ADB / Shizuku 授权存活（重启后自恢复），让 Arix 的 Shizuku 特权功能不掉线。与终端/proot 正交，单列。

## 对接（AIDL 接口，P1 定形）
`ITerminalService`：isReady / ensureInstalled / resetEnv / bootstrapInfo /
openSession(cols,rows,cb)→sessionId / write / resize / signal / closeSession /
exec(command,cwd,cb)→jobId
`ITerminalCallback`(oneway)：onOutput(sid,bytes) / onExit(sid,code) / onEnvEvent(kind,msg)
安全：服务 `exported` 但用**签名级权限** `com.arix.terminal.permission.BIND_TERMINAL`
（两 App 同签名才能绑；调试期都用 debug key）。

## 决策记录
- 分发：第二 APK **内置进主 App assets + 首次用终端时 PackageInstaller 引导安装**（国内 GitHub 不稳，内置更稳），GitHub 作更新通道。
- 二进制大件：由助手联网从 Termux 官方拉取，校验后入模块 assets（版本见上）。
- Q2 待办：第二 APK 用户安装 UX（静默 vs 系统安装器）——真机阶段定。

---

# 2026-07-26 大摸底（5 路并行审计 · 全是发现，一条都还没修）

范围：`:terminal` 28 个 kt / 10133 行 + vendor 两模块 + assets + 构建配置 + 与主 App 的联动。
维度：存储占用 / 性能与代码质量 / 正确性与安全（红队）/ 功能缺口对比竞品 / i18n 与可用性。
背景：`2026-07-25 大改`那一节的东西**编译过、真机一条没验**，本次摸底就是在真机验之前先把地雷排出来。

## 零、先做这两件（做别的之前）

- [ ] **P0-A 立刻提交**。整轮大改 26 个文件挂在工作区未提交：19 个 modified + **7 个 untracked**
  （`BackupManager` `CursorOverlayView` `DistroProvision` `OciImagePuller` `QuickCmds` `TermBeautify` `ic_nav_env.xml`），
  vendor 的 `TerminalRenderer.java`/`TerminalView.java` 两个 patch 也未提交。最近一次终端相关提交还是 `7f6f12d`。
  **手一滑就没了两天的活**，这是当前最大单点风险。
- [ ] **P0-B 一行日志定生死**：`Log.d(TAG, filesDir.canonicalPath + " | " + filesDir.absolutePath)`。
  它决定下面「一、地基」那条是不是真的（判断是真的，见旁证）。

## 一、地基级 bug：`isSymlink` 在 Android 上恒为 true —— 一条错误连炸四个功能

三份同款实现：`DistroInstaller.kt:501`、`BackupManager.kt:276`、`EnvModel.kt:295`

```kotlin
private fun isSymlink(f: File) = try { f.canonicalFile != f.absoluteFile } catch (_: Exception) { false }
```

Android 的 `ctx.filesDir` = `/data/user/0/<pkg>/files`，而 **`/data/user/0` 本身就是指向 `/data/data` 的符号链接**
⇒ rootfs 底下**任何**文件的 canonicalPath 都 ≠ absolutePath ⇒ 一律判成软链。连锁后果：

- [ ] **① 发行版装完起不来**：`DistroInstaller.kt:481` `chmodTree` 在根节点第一行就 `return`，整棵树没 chmod
  ⇒ tar 解出来是 0600 ⇒ `/bin/sh` 没执行位。而 `isDistroInstalled` 用 lstat 只看存在，照样报「装好了」。
- [ ] **② 备份文件是空的**：`BackupManager.kt:174` 把 root 当软链，只写一条 `'2'` 头就结束；`dirSize` 同理返回 0（进度条秒满）。
- [ ] **③ `stripSingleWrapper` 永不生效**（`DistroInstaller.kt:461`）；**④ 环境体积恒显示 0B**（`EnvModel.kt:287`）。
- **修**：全部改用 `Os.lstat(path).st_mode and S_IFMT == S_IFLNK`（`EnvRegistry.lexists` 已经是这个思路，只是没推广），
  并**收口成一份**（见「四」的重复实现条）。
- **旁证（很硬）**：Termux 那条路的 `TerminalEnv.chmodTree:201` 用的是 `walkTopDown()`，**没有这个守卫** ——
  所以 Termux 环境真机验通了；带守卫的发行版路径从没真机验过。完美对上。
  （反过来 `TerminalEnv.chmodTree` 自己的毛病是跟随目录软链，且它恰好跑在 `applySymlinks()` 之后，
  应该换成 `DistroInstaller` 那个手写递归版 —— 两边合并成一份就同时解决。）

## 二、还会炸的（真机首验前建议先修）

- [ ] **首装中断后永久锁死** `TerminalEnv.kt:59-62,77-107`：`bootstrapDone` 只判 `bin/bash` 存在，
  `isInstalled` 却要求 `canExecute()`。首装在「解出 bash」之后、`applySymlinks`/`chmodTree` 之前被打断
  ⇒ 下次 `install()` 认定已完成、跳过重建 ⇒ 永远「终端环境准备失败」，只能重置逃出。
  **修**：改成独立哨兵文件 `.bootstrap-ok`，全部步骤成功才写。
- [ ] **`exec()` 起的进程从不登记** `TerminalService.kt:179-203`：只有 `openProcess` 会 `procs[id]=job`（`:145`）。
  ⇒ `killProcess` 对 AI 的 `linux_exec` 是**空操作**、`onDestroy` 也漏掉它们、且 exec 完全没超时。
  AI 跑一条 `top`/`ping` 就永久泄漏一棵 proot 进程树。**修**：照 `Proot.run:63` 登记 + deadline + `destroyForcibly`。
- [ ] **超时对「不输出的命令」完全失效** `Proot.kt:72-86`：deadline 检查在阻塞的 `ins.read(buf)` **之前**。
  `sleep`/dpkg 抢锁/网络挂起 ⇒ `timeoutMs` 是摆设，`PkgActivity` 的两个超时常量全不生效，协程永久挂住。
  **修**：另起看门狗协程按 deadline `destroyForcibly()`。
- [ ] **多标签下 sessionSignature 误杀** `TermActivity.kt:280-296`：签名算的是**全局** activeEnv，不是标签自己的环境，
  而 `promptNewTab:375` 开新标签会 `setActiveEnv`。⇒ Termux 标签跑着编译 → 新建 Ubuntu 标签 → 切回 Termux 标签
  → 切出 App 再回来 ⇒ Termux 标签被 `restartSession` 换成 Ubuntu，编译没了。**修**：按 `t.envId` 重算签名。
- [ ] **恢复备份先删后解，中途失败＝环境全丢** `BackupManager.kt:126-140`，无回滚路径。
  **修**：解到 `rootfs.new` 再原子 rename，成功后才删旧的。
- [ ] **userns 探测 flag 比真启动少** `TerminalEnv.kt:611-628` vs `:678-681`：探测 `unshare --user --map-root-user --mount true`，
  真启动多了 `--pid --fork`。旧 toybox 没 `-f` ⇒ 探测通过、真起立刻失败，用户只看到闪一下的空标签。
- [ ] **备份 tar 的长名/长链接截断** `BackupManager.kt:224-234,258-262`：`putStr` 只写 `len-1` 字节而长名判据是 `>100`
  ⇒ 正好 100 字节的路径被静默截成 99；且**没做 `'K'` 长链接名**，目标 ≥100 字节的软链备份后变断链
  （Debian/Ubuntu 的 `/usr/share/…` 很容易超）⇒ 恢复出来的 rootfs 是坏的。

## 三、安全

- [ ] **红线被 `/system` 绑挂绕过** `TerminalEnv.kt:466,551`：`isSystemEnv` 挡非交互路径这条**本身没有绕过口子**
  （逐条追过 `Proot.run:53`、`TerminalService.startProot:87`），但 proot argv 里无条件 `-b /system`，
  容器里 `/system/bin/su` 就在那儿。已 root 且授权过的机器上，AI 的 `linux_exec` 直接 `su -c` 就拿到真 root。
  **修**：非交互路径绑 `/system` 时遮蔽 su（交互路径不动）。
- [ ] **OCI blob 从不校验 sha256** `OciImagePuller.kt:276-308`：`layer.digest` 只用来拼 URL，从不比对内容；
  叠加 `:66-71` 会自动回退到第三方镜像站（daocloud/1ms.run/dockerproxy）⇒ 镜像站或中间人能把任意 rootfs 塞进手表，
  而它随后要被 proot 跑起来。**修**：边解边 `MessageDigest` 累加，层结束比对，不符整源判失败。
- [ ] **`xtom-env` 控制通道在 /sdcard 上且无来源校验** `EnvControl.kt:82-88,285-292`：targetSdk 28 走 legacy 存储
  ⇒ 任何有 WRITE_EXTERNAL_STORAGE 的第三方 App 都能写 `request` 文件，触发 `remove <id>`（删掉整个容器）/`install`/`use`。
- [ ] **恶意镜像可把文件写到 rootfs 之外** `DistroInstaller.kt:487-494` `finalizeRootfs`：
  `if (!resolv.exists()) writeText(...)`，镜像里若 `etc/resolv.conf` 是指向外面的**断链**软链，`exists()`＝false 就跟随写出去。
  `safeResolve:403` 只管了 tar 条目、没管这道收尾写。同理 `TerminalEnv.ensureDistroRuntime:513-518`。**修**：写前 `deleteIfExists`。
- [ ] **killJob 违反自己写的前置条件** `TerminalService.kt:77-81`：`destroy()`（SIGTERM，异步）后**立刻** `release()` 关流，
  而 `release()` 文档（`:58-65`）明确要求「调用前确保子进程已死」，`Proot.kt:63` 也写了 destroy 杀不干净 proot
  ⇒ 正在 write 的 stdin 线程脚下被抽走 fd，正是他们自己描述的 fd 复用风险。**修**：`destroyForcibly()` + `waitFor` 后再 release。
- [ ] **挂载泄漏 / 容器孤儿** `TerminalEnv.kt:698-733`：chroot 脚本末尾的 `umount -l` 只有正常 `exit` 才跑
  ⇒ 关标签/杀 App 时 su 被 KILL，`/dev /proc /sys /system` 的 bind mount 永久挂着直到重启。
  userns 那条同理（KILL `unshare` 不会杀掉 `--fork` 出去的新 pid ns init）。proot 有 `--kill-on-exit`，没这问题。
- **AIDL 权限核过没问题**：`<service android:permission=...>` 是元素级的，`bindService` 就被拦住，
  「每个方法都要校验」这条不成立，现有写法是对的。（残留风险只有 binder 对象被主 App 转交第三方，属 Binder 固有语义。）

## 四、性能与代码质量（DESIGN-CHAT-PERF 第十二节已做的不在此列）

- [ ] **[高] 光标浮层默认配置下永久 60fps 空转** `CursorOverlayView.kt:133-145,187-191`：`blink` 默认 true ⇒
  静置 600ms 后 `blinkNeeded()` 恒 true ⇒ `keepFraming()` 恒 true ⇒ 帧回调无限自投，`invalidate()` 在 `:143` 无条件调用。
  文件头写的「闲下来就停帧」**在默认参数下根本不成立**。切到别的页 TermActivity 只是 stopped、View 仍 attached，照烤。
  **修**：呼吸阶段限帧到 ~15fps + `onWindowVisibilityChanged` 门控。
- [ ] **[高] 控制台把 20 万字符塞进单个 `Text`** `PkgActivity.kt:97,199-208,733-735,774-779`：
  `CONSOLE_MAX_CHARS=200_000` 只框了内存没框渲染，`apt-get upgrade` 每秒几十次 flush ⇒ 每次对 20 万字符等宽文本做主线程 layout。
  **全模块最大 ANR 源**。**修**：显示层只截末尾 ~8KB / 200 行，写入按 100ms 节流（全量留给「复制输出」）。
- [ ] **[高] 拉镜像进度回调不节流** `OciImagePuller.kt:311-321` + `EnvActivity.kt:181,363-381`：
  GZIP 默认 512B 读一次 ⇒ 30MB 层约 6 万次 `onProgress` ⇒ 10 个 `DistroRow` 全部重组。进度条反而更不跟手。
  **修**：整数百分比变化才回调 + 非安装行传 `progress = -1f`。
- [ ] **[高] 环境页状态是主线程同步扫的，每次点击都重扫** `EnvActivity.kt:96-98,130-135` + 根因 `EnvModel.kt:218-220`：
  `refresh()` ＝ 解自定义镜像 JSON ×2 + 10 个发行版 × 3 次 `Os.lstat`，且挂在每个回调上。
  第十二节只把**派生**记忆化了，**数据源**还在主线程。**修**：`all()` 加进程级缓存（`saveCustom` 失效）+ `refresh()` 挪 IO。
- [ ] **[高] 文件页点击那一帧做磁盘 IO** `FilesActivity.kt:264-270`：每个选中项一次 stat + `canonicalPath`（逐段 readlink）。
  选中几百个文件点复制＝主线程当场卡死。**修**：挪进已有的 `scope.launch(IO)`。
- [ ] **[高] 拷贝进度每 64KB 全量刷 state** `FilesActivity.kt:282-288`（1GB ≈ 1.6 万次），和 IO 抢主线程；
  `BackupManager.kt:193`、`DistroInstaller.kt:229` 同一个毛病。**修**：每 100ms 或每 1% 合并一次。
- [ ] [中] `ui/XtomTheme.kt:125-142,201,246` 每个 Activity 冷启都在主线程读 `/sdcard/Arix/theme.json` 并解 JSON，
  且读不止一次（`TermActivity:111` 一次、`ExtraKeysView:46` 又一次）。**修**：进程内 `@Volatile` 缓存。
- [ ] [中] `TermActivity.kt:365-370` 点「＋」在主线程跑 `installedDistros()` + 每个 id 重解 JSON（有现成 `labelCache:256` 没用）。
- [ ] [中] `FilesActivity.kt:954-957,1003-1013` 每个可见行常驻两个动画对象（未选中行也在跑），双栏 ~30 行＝60 个 Animatable。
- [ ] [中] `ExtraKeysView.kt:71,334-338,379-409` `repeatHandlers` **无界增长**：每次长按 ESC 弹备用键就新建 12 个 Handler 并 `+=`，永不移除。
- [ ] [中] `TermSettingsActivity.kt:170,173,184` 换配色/恢复默认字体仍在主线程（同页 `:199` 已经是对的写法）。
- [ ] [中] **重复实现该收口成 `FsUtil.kt`**：`isSymlink` **4 份**、目录求和 **3 份**、shell 单引号转义 **4 份**、字节格式化 **4 份**。
  「一、地基」那个 bug 就是这么来的 —— 改一处漏三处。
- [ ] [中] **形状没走令牌**（`MaterialTheme.shapes` 定义了 8/12/18/24/32 却到处硬写 dp）：
  `SettingsKit.kt:63,104`、`XtomComponents.kt:81`、`TerminalNav.kt:83,103`、`PkgActivity.kt:702`、`EnvActivity.kt:631`。
  写死颜色只有一处：`TermActivity.kt:514` 的 scrim `0x99000000`。
- [ ] [低] **死代码**：`MainActivity.kt` 整个（exported=false、全仓无人启动，类注释还停留在「终端由主 App 渲染」的旧世界）、
  `OciImagePuller.kt:348 mirrorsInfo`、`EnvModel.kt:244 isReady`、`EnvModel.kt:287 distroSizeBytes`、`XtomMotion.kt:172 XtomLoadingDot`。
- [ ] [低] **半成品**：`QuickCmds.kt:40 save()` 零调用 ⇒「自定义快捷命令」只有读没有写，用户永远造不出自定义项（要么补 UI 要么删）。
- [ ] [低] **`DistroSource.Tarball` 整条路径不可达**（约 150 行 + `org.tukaani:xz` 依赖）：BUILTIN 全是 Oci、
  `addCustomDistro` 只造 Oci、`saveCustom`（`EnvModel.kt:207`）还会丢弃非 Oci 条目。要留就注释写清「离线包保留」，要瘦就连依赖一起删。

## 五、存储（APK 51.62MB / 设备最坏约 1.8GB）

**APK 构成实测**（153 条目）：`assets/bootstrap-aarch64.zip` **30.35MB(58.8%)** + 8 个 dex 合计 **18.69MB(36.2%)**
+ resources.arsc 0.48MB + `assets/proot` 0.14MB + lib 仅 0.019MB（abiFilters 干净）。
**dex 里 31759 个 type，其中 `compose.material.icons.*` 占 11450 个（36%）**，而 `com.arix.terminal` 自己只有 **758 个类**。

- [ ] **[最高性价比] 开 R8**：`terminal/build.gradle.kts:37` release `isMinifyEnabled = false`，两个 vendor 模块同样是 false，
  全仓没有任何 `proguard-rules.pro`，**从没出过 release 包**。开了之后那 11450 个图标类会被剪掉 99%，
  dex 18.69MB → 预计 3~5MB。**纯配置、不动功能，省 13~15MB**。（vendor 的 termux 类有 JNI 回调，要 `-keep`，别裸开。）
- [ ] **重打 bootstrap 剥文档**：`share/info` 2.28M + `share/man` 2.60M + `share/doc` 1.88M + `include/` 1.70M
  + `libexec/installed-tests` 0.75M ＝ **压缩 9.26MB / 解压后 27.69MB**。手表上没人 `man ls`，要编译的走发行版。
  剥完 bootstrap 28.9→19.6MB，解压后 71.2→43.5MB。**APK 和设备各省一份**。
- [ ] [可选，待拍板] bootstrap 改首次运行在线下载（`TerminalEnv.kt:80` 现在从 assets 流式解压）：
  再省 30.35MB，但丢掉离线首装能力，且 Termux 官方 bootstrap URL 带版本号会过期。
  做完 R8＋剥文档 release 约 25~27MB，再做这条约 6~8MB。
- [ ] [低] 去掉 coil（`build.gradle.kts:77`，只为 `PageBackground.kt:140`/`XtomGlass.kt:28` 两处**本地**图，
  却拖进 okhttp 200 多个类 + 41KB publicsuffix 表）。开了 R8 后收益不大，优先级低。
- [ ] **`sizeHint` 是骗人的** `EnvModel.kt:103-144` → 显示在 `EnvActivity.kt:618`：那是 OCI **压缩层**大小。
  用户看「Ubuntu ~30MB」实际装完 80MB，「Arch ~150MB」实际 400MB+，普遍 **2.5~3 倍**落差。**修**：文案改成解压后估值。
- [ ] **包管理器缓存全线不清，且没有任何清理入口** `DistroProvision.kt:87-97`：
  DEBIAN/ARCH/FEDORA/SUSE/VOID 五族的装包命令后面都没有清缓存（只有 ALPINE 的 `--no-cache` 是对的）。
  pacman 的 `/var/cache/pacman/pkg` 在 PRO 套餐下能到 300~500MB。`QuickCmds` 给了「空间 df -h」却没给「清缓存」。
- [ ] **备份 `.part` 是永久隐形垃圾** `BackupManager.kt:46,79`：`list()` 只 filter `.tar.gz`
  ⇒ 中断留下的 `.part`（一份 25~30MB）**在 UI 里彻底不可见，用户永远删不掉**。
- [ ] **备份无数量/总量上限、无总占用显示、无一键清空** `EnvActivity.kt:124,403,442`；且它在 /sdcard 上，卸载 App 也不消失。
- [ ] **三处临时目录永不清理**：`$PREFIX/tmp`（`TerminalEnv.kt:39`）、`proot-tmp`（`:41`）、发行版 `/tmp`。
  真 Linux 上 /tmp 是 tmpfs 重启即空，这里全是磁盘上的普通目录、跨重启永久保留。
- [ ] **[新页面] 加一个「存储」页**（一页覆盖上面大半）：列 Termux rootfs / 每个发行版 / 备份 / 缓存 四行实际字节数
  （`EnvRegistry.distroSizeBytes` 现成、目前是死代码），每行给删除按钮，底部「清理缓存」
  （清 proot-tmp + $PREFIX/tmp + cacheDir/*.part + backups/*.part + 按族跑一条包管理器清缓存）。
  另：**终端 App 自己没有「重置 Termux 环境」入口**，`TerminalEnv.reset()` 只暴露给主 App（`TerminalService.kt:131`），
  用户在终端里没法回收那 71MB。

## 六、功能缺口

### 台账勘误（这几条其实早做完了，之前没更）

- **C 项 `xtom-env` 终端内指令 → 已全做完**：`assets/xtom-env.sh`（list/current/use/install/remove/help）
  + 宿主侧 `EnvControl.kt:35-295`（FileObserver + 三文件协议）+ 装进 PATH `:100-118`。
  **唯一残缺**：`EnvControl.start()` 只由 `TermActivity.onCreate` 调、`onDestroy` 停（`:176`/`:690`）
  ⇒ **终端界面没开时 `xtom-env` 请求无人处理**。
- **D 项 XZ → 已做**（`build.gradle.kts:65`）；**动态版本解析 → 已被 OCI 化等效解决**（`EnvModel.kt:92,102-142`）；
  **各发行版 NOTICE → 已做**（`NOTICE:69-72`）；**code_runner 接回终端 → 已做**（`EnhancedTools.kt:70,116`）；
  **`termux-build/` → 已删**（但 `TERMUX-BOOTSTRAP-BUILD.md` 还在，该删）。
- **台账「决策记录」那条已过时**：说「第二 APK 内置进主 App assets（`bundleTerminalApk`）」—— 全仓已找不到那个 task，
  现在是 `TerminalInstaller.kt:17-22` 在线下载，而 `canDownload():28` 默认 false ⇒ `TerminalPage.kt:89` 提示「请手动侧载」。
- **别当缺口**：捏合缩放字号（`TermActivity.kt:756` + `ExtraKeysView.kt:58`）、剪贴板双向（`:722-729`）、
  文本选择（vendor textselection/）、CJK 宽字符（vendor WcWidth）—— **都已经有了**。

### 真缺口 · 按性价比排序

- [ ] **1. 会话交给前台服务托管**（把 `tabs` 从 TermActivity 搬进 `TerminalService`，`startForeground` + 常驻通知
  显示会话数/一键退出；Activity 只做 attach/detach）。现在 `TerminalService` 全文件零 `startForeground`，
  `TermActivity.kt:688-691 onDestroy` 直接把所有 session `finishIfRunning()`
  ⇒ **切个表盘，正在编译的活就没了**。这是 SSH / 长作业 / 低内存三件事的共同前提。**收益最大。**
- [ ] **2. 圆屏内缩**：主 App 有 `ScreenFitPrefs.kt:19-40`（insetH/insetV/floatInset 用户手调），
  终端 `ui/XtomWindow.kt` 只处理 displayCutout + systemBars，**没有圆角内缩** ⇒ 圆屏上字符矩阵四角被表圈切掉。成本极小。
- [ ] **3. 旋钮/表冠滚动**：全仓（app + terminal）**零** `onGenericMotionEvent`/`AXIS_SCROLL`。
  TerminalView 只走手指 `onScroll`。手表上手指滑动会挡住本就极小的字。成本小，立竿见影。
- [ ] **4. `linux_exec` 状态化 + 长作业异步化**：`LocalLinuxTool.kt:22-27` 参数只有 command/timeout，
  **没有 cwd、没有 env（在哪个容器跑）**，每次 `bash -lc` 新进程 ⇒ `cd`/`export`/venv 全不保留；
  `:32` 超时 `coerceIn(1,600)` 且**超时即杀**，装大包/编译必然做不完。
  **改**：加 `cwd`/`session` 参数复用同一个 shell；超时转后台作业返回 jobId + `action="poll"` 取增量。
- [ ] **5. AI 输出分页续读**：`LocalLinuxTool.kt:65` `out.take(4000)`、超时分支 `:71` `take(2000)`，
  **无分页无续读无落盘兜底** ⇒ apt 日志后半截全丢。**改**：超长落共享目录 + 提示 `action=read_more`。
- [ ] **6. `EnvControl` 从 Activity 搬进 Service**（跟 1 一起做）：让 `xtom-env` 在终端界面没开时也生效，顺带 AI 就能装/切环境。
- [ ] **7. 终端里的语音输入**：按键条加麦克风键 → RecognizerIntent → 结果**填进输入行让用户确认**（不直接回车）。
  表上敲 `apt-get install python3-dev` 是酷刑，主 App 已有语音基建可借。
- [ ] **8. `linux_exec` 加状态动作**（`action="status"` 返回：终端装没装 / 当前环境 / 已装发行版 / 包管理器族）。
  按「工具 1 用多」铁律**合并进现有工具，不新开**。AI 现在完全不知道终端处于什么状态。
- [ ] **9. 终端 APK 分发落地**：配 `RemoteAssets` 的 `TERMINAL_APK` 基址，或改回 gradle 内置。
  **现在唯一安装路径是手动侧载 ⇒ 终端功能对普通用户等于不存在。** 顺手把台账那条改成诚实描述。
- [ ] **10. SSH 服务端一等公民**：运行环境页加「远程访问」区（一键装 openssh + 生成/展示密钥 + 显示
  `ssh user@<ip> -p 8022` + 端口设置）。**手表输入的根本解法**，依赖 1 保活。
- [ ] **11. AI 看不见交互会话**：`ITerminalService.aidl` 只有 `exec`/`openProcess`，无 attach、无 get_screen。
  用户在终端里跑着 vim/编译，AI 完全瞎（Operit 有 `get_terminal_session_screen` 那一整套，见 `COMPETITIVE.md:66`）。
- [ ] **12. 套餐钩子会灌进 AI 的每一条命令**（这条其实该算 bug）：`DistroProvision.kt:149-155` 钩子写在 `/etc/profile.d/`，
  而非交互 argv 是 `shell -l -c cmd`（`TerminalEnv.kt:565-574`），`-l` 同样 source /etc/profile
  ⇒ 装完发行版后 AI 的第一条 `linux_exec` 会触发几分钟 apt、输出全混进结果；**失败还不写标记**（`:132-137`）⇒ 每次登录重跑。
- [ ] **13. 换源会抹掉用户第三方仓库** `DistroProvision.kt:333`：`deb822.forEach { it.writeText(body) }`
  把 `sources.list.d` 下**每一个 `.sources` 都覆盖成同一份**，用户加的 nodesource/docker 静默消失。**修**：按文件名白名单。
- [ ] [中] 镜像测速（ZeroTermux 有，我们三家固定手选）/ 终端内搜索 scrollback / 会话输出导出 /
  离线 rootfs 导入（现在所有 OCI 发行版必须联网，`BackupManager` 只能恢复自己备的）/ 开机自启 / 脚本定时任务。
- [ ] [低] URL 点击、入站分享 Intent、横屏紧凑排布、外接实体键盘修饰键
  （`TermActivity.kt:769-777` `readShiftKey/readFnKey` **恒 false**、`readControlKey/readAltKey` 只从按键条读）、
  `onTrimMemory` 低内存防护。**明确不做**：VNC/X11（手表上没意义）。

## 七、i18n（实测数字）

`terminal/src/main/res/` 下**只有 anim/drawable/xml，没有 values/**；全模块 `getString(R.string` 和 `tr(` 调用数 ＝ **0**。
硬编码中文串 **548 处 / 去重 460 条 / 分布 24 个文件**：
FilesActivity 95 · EnvActivity 90 · PkgActivity 66 · TermSettingsActivity 56 · DistroProvision 32 · EnvModel 25 ·
EnvControl 21 · TermActivity 19 · TermBeautify 19 · OciImagePuller 18 · QuickCmds 18 · BackupManager 14 ·
DistroInstaller 13 · 其余 11 个文件共 62。

**好消息**：主 App 的管线是 `I18n.kt:89` 的 `tr(zh: String)` —— **key 就是中文原串本身**，缺译回退中文，
表在 `i18n/i18n_table.json`。所以终端接入 ＝ 共享/复制 `I18n.kt` + 把 460 条包一层 `tr(...)` + 跑现成的 译表→embed→kt 管线，
**机械成本很低，真正的成本是 460 条的翻译量**。

- [ ] **建议：等功能盘子定下来再一次性做**，别现在边改边译（现在还要新增存储页/前台服务/SSH 区等一堆文案）。

## 八、真机首验必测清单（按最可能翻车排序）

1. 打一行日志比 `filesDir.canonicalPath` 和 `absolutePath` —— 一行定「一、地基」那条的死活，它牵连 4 个功能。
2. 装 Alpine 然后进去；起不来就 `run-as com.arix.terminal ls -l files/distros/alpine/rootfs/bin/` 看是不是 0600。
3. 点一次备份看 `.tar.gz` 体积 —— 几 KB 就是地基 bug 确认（writeTar 只写了一条根软链头）。
4. Termux 首装进度走到一半强杀 App，再打开 —— 验是不是永久锁死在「环境准备失败」。
5. 开 Termux + Ubuntu 两标签，点回 Termux 标签，按 Home 再回来 —— 验 Termux 标签会不会被换掉。
6. 装完发行版后立刻让主 App 发一条 `linux_exec`（`echo hi`）—— 验会不会卡在 apt 上几分钟、输出被污染。
7. 包管理页搜包时切飞行模式，或跑 `sleep 300` —— 验超时是否生效、UI 会不会永久转圈。
8. AI 起一条长命令后调 `killProcess` —— 验（预期是完全停不掉）。
9. 有 root 的机器：系统终端开 root → 起会话 → 关标签，然后 `ps -A | grep su`、`cat /proc/mounts | grep rootfs`
   验进程孤儿与挂载泄漏；顺手在容器里敲 `/system/bin/su -c id` 验红线绕过。
10. 拉 `debian:stable-slim`（多层）走国内镜像站，中途切飞行模式 —— 一次性验清理、错误提示、半截 rootfs 是否被当成功。

---

# 2026-07-26 第一轮修复（编译过 · **真机仍未验**）

产物：`terminal-debug.apk` **38.00 MiB**（原 49.23）、`terminal-release.apk` **21.59 MiB**（首次出 release）。
三个 APK 签名证书一致（`833e1f43…`，apksigner 验过），release 版终端能绑主 App。

## 已修（对应上面「大摸底」各节）

### 地基
- **[x] `isSymlink` 恒为 true** —— 新建 `FsUtil.kt` 作为唯一实现（`Os.lstat` + `S_IFMT/S_IFLNK`），
  收口 `isSymlink` / `lexists` / `dirSize` / `chmodTree` / `deleteTree`。
  `DistroInstaller`（整树 chmod 不再第一行 return）、`BackupManager`（writeTar 不再把 root 当软链）、
  `EnvModel`（体积不再恒 0）、`TerminalEnv`（`chmodTree` 从 `walkTopDown()` 换成不跟随软链的递归）四处全部改调它。

### 会炸
- **[x] 首装中断永久锁死** —— `TerminalEnv.install` 改用独立哨兵 `.bootstrap-ok`，全部步骤成功才写；
  检出半截环境时提示「上次没装完，清理残留」。
- **[x] `exec()` 进程不登记** —— `TerminalService.exec` 现在建 `Job` 并 `procs[jobId]=it`、
  `finally` 里摘除收尾，`killProcess` 对 AI 起的命令真的生效；另加服务端硬上限 `EXEC_MAX_MS = 30min`
  （独立看门狗线程 `destroyForcibly`，客户端那层 `withTimeoutOrNull` 打断不了阻塞的 read）。
- **[x] `Proot.run` 超时对不输出的命令失效** —— deadline 检查从读循环里挪出来，改成独立看门狗线程
  `proc.waitFor(timeoutMs)` 到点 `destroyForcibly`；超时标记用 `AtomicBoolean`
  （Kotlin 局部变量不能加 `@Volatile`）。
- **[x] 恢复备份先删后解** —— 改成解到 `<rootfs>.new` → 旧的 rename 到 `.old` → 新的顶上 → 才删旧的；
  任何一步失败都尽力回滚，取消/异常只删 staging，**旧环境全程原地不动**。
- **[x] userns 探测 flag 少** —— 探测补上 `--pid --fork`，与真启动完全一致。
- **[x] 备份 tar 长名/长链接截断** —— 阈值从 `>100` 改成 `>99`（`putStr` 只写 `len-1`）；
  新增 GNU `'K'` 长链接记录（原来只有 `'L'`），抽出 `writeLongRecord` 两者共用。

### 安全
- **[x] `/system` 绑挂绕过红线** —— 新增 `TerminalEnv.maskSuArgs(ctx, interactive)`：
  非交互路径（AI linux_exec / MCP / 插件）用一个 0400 的空文件 `-b` 盖住 7 条已知 su 路径；
  交互终端不受影响。Termux 与发行版两条 proot argv 都挂上了。
- **[x] OCI blob 不校验** —— 新增 `DigestStream`，边解边算 sha256，层末 `verify()` 比对 manifest digest，
  对不上直接判失败（配合会自动回退第三方镜像站这一点，这条是必须的）。`drain()` 补齐 gzip 没读完的尾巴。
- **[x] 恶意镜像写到 rootfs 外** —— `DistroInstaller.finalizeRootfs` 与 `TerminalEnv.ensureDistroRuntime`
  写 `resolv.conf`/`hosts` 前先 `isSymlink` 摘链，判存在改用 `lexists`。
- **[x] killJob 抽 fd** —— `destroy()` → `destroyForcibly()` + `waitFor(2s)` 之后才 `release()`。

### 性能
- **[x] 光标浮层 60fps 空转** —— 新增 `atRest` 标记，到位后只剩呼吸时改用
  `postFrameCallbackDelayed(66ms)`（≈15fps）；新增 `onWindowVisibilityChanged` 门控，
  窗口不可见直接停帧（切到别的页时 TermActivity 只是 stopped、View 仍 attached，原来会一直烤）。
- **[x] 包管理控制台 20 万字符** —— 显示层只喂末尾 `CONSOLE_VISIBLE_CHARS = 8000`（前面折叠提示），
  写入按 `CONSOLE_FLUSH_MS = 100ms` 节流，全量仍留在 `buf` 里供复制。
- **[x] OCI 进度回调不节流** —— `CountingStream` 按 256KB 台阶才回调一次（原来 gzip 每 512B 一次）。
- **[x] 备份/恢复进度每 64KB 刷 state** —— 改成每 1%（`PROGRESS_STEP`）。

### 存储
- **[x] 裁剪 bootstrap** —— `tools/slim_bootstrap.py` 从未裁剪原件生成 assets 里那份：
  删 `share/man`、`share/info`、`include/`、`libexec/installed-tests`、`share/doc` 下的
  README/changelog/示例、非中英文 `share/locale`；**许可文本 94 份一份不少**
  （`copyright`/`COPYING`/`share/LICENSES/`/各包 `licenses/`，NOTICE 已据实改写）；
  SYMLINKS.txt 同步剔除 902 条指向已删目录的断链（剩 258 条，`bin/` `lib/` 一条没动）。
  29.55 MB → 20.11 MB，设备上解压后省约 27 MB。
  原件备份在 `terminal/bootstrap-src/`（已 gitignore）。
- **[x] 开 R8** —— release 打开 `isMinifyEnabled` + `isShrinkResources`，新增 `terminal/proguard-rules.pro`
  （`-keep class com.termux.**` 保 JNI 回调、AIDL Stub、Activity/Service、xz）。
  dex **17.83 MiB → 1.44 MiB**（那 11450 个没用到的 material icon 全被剪掉）。
  另加 `androidResources.localeFilters = [zh, en]`，以及 `lint { disable += "ExpiredTargetSdkVersion" }`
  （targetSdk 28 是刻意的，不关这条 lintVitalRelease 会把 release 拦死）。
- **[x] 备份 `.part` 隐形垃圾** —— `BackupManager.list()` 顺手清 `.part`；
  用 `@Volatile backingUp` 守住，备份进行中不扫（否则刷新一次列表就把正在写的那份删了）。
- **[!] 增量打包幽灵字节** —— 改完 assets 后 `assembleDebug` 出的包大小一个字节没变，
  内容其实已经缩了、外面挂着 11 MB 空隙。**改 assets 后必须 `:terminal:clean`**（老坑，主 App 也踩过）。

## 这一轮没动的（仍在上面各节挂着）
挂载泄漏/容器孤儿、`xtom-env` 控制通道无来源校验、套餐钩子灌进 AI 命令、换源抹掉第三方仓库、
多标签 sessionSignature 误杀、环境页主线程扫盘、文件页点击 IO、`XtomTheme` 每 Activity 读 theme.json、
`ExtraKeysView` Handler 无界增长、重复实现收口（shell 转义 ×4 / 字节格式化 ×4）、形状令牌、死代码清理、
以及「六、功能缺口」和「七、i18n」整节。

---

# 2026-07-26 第二轮：剩余 bug 修完 + 体积深挖（编译过 · **真机仍未验**）

产物：`terminal-debug.apk` **30.89 MiB**、`terminal-release.apk` **14.48 MiB**（起点 49.23）。

## 又修的 bug

- **[x] 多标签 sessionSignature 误杀** `TermActivity.onResume`：加 `if (t.envId != active) return`。
  指纹算的是全局激活环境，而新建标签会 `setActiveEnv` —— 不加这道门，
  「Termux 标签跑着编译 → 新建 Ubuntu 标签 → 切回 Termux → 出去再回来」会把编译中的标签重开掉。
- **[x] `xtom-env` 控制通道无来源校验** —— 加**令牌**：`EnvControl.token()` 生成 24 字节随机串存进私有 prefs，
  写脚本时替换 `assets/xtom-env.sh` 里的 `__XTOM_TOKEN__` 占位；请求首字段必须是它。
  脚本本体在容器 rootfs 里（App 私有目录），第三方 App 读不到 → 伪造不出合法请求。
  原来任何有 WRITE_EXTERNAL_STORAGE 的 App 都能写 `/sdcard/.../request` 触发 `remove <id>`（删掉整个容器）。
- **[x] FileObserver 掩码含 CREATE** —— 去掉，只留 `CLOSE_WRITE or MOVED_TO`。
  `printf > request` 先触发 CREATE，那次会读到空文件并把它 delete 掉，随后的 CLOSE_WRITE 就找不到文件了
  （表现为 `xtom-env use/install` 偶发无反应）。
- **[x] 套餐钩子灌进 AI 的每条命令** `DistroProvision`：钩子包进 `case $- in *i*)`（只对交互登录生效）。
  非交互命令走 `shell -l -c`，`-l` 同样 source /etc/profile —— 原来装完发行版后 AI 的第一条 linux_exec
  会触发几分钟 apt、输出全混进结果，失败还不写标记于是每条命令都重跑。
- **[x] 换源抹掉用户第三方仓库** `DistroProvision`：新增 `isDistroOwnedSources()` 白名单，
  只覆盖发行版自带的 `.sources`；目录里全是第三方源时不碰它们、另开一份 `xtom-mirror.sources`。
  原来是 `deb822.forEach { it.writeText(body) }` 把每一个都覆盖成同一份，nodesource/docker 静默消失。
- **[x] chroot 挂载泄漏** `TerminalEnv`：卸载列表抽成 `XTOM_MNT` + `xtom_umount()`，
  ① **开工前先无条件卸一次**（会话被 SIGKILL 时 trap 跑不到，靠下次进来自愈）
  ② `trap xtom_umount EXIT INT TERM HUP` 覆盖可捕获的退出路径。
- **[x] theme.json 每个 Activity 反复读** `ui/XtomTheme`：加进程内 `@Volatile` 缓存（双检锁）。
  它是外部存储上的文件，原来 Compose 侧一次、`XtomViewColors.of()` 一次、`ExtraKeysView` 再一次。
- **[x] `ExtraKeysView.repeatHandlers` 无界增长** —— 改成全 View 共用一个 `sharedHandler`。
  原来一键一个 Handler 且只加不减，而长按 ESC 弹备用键每次都重建 12 个按钮。
- **[x] 环境页每次点击主线程扫盘** `EnvActivity.refresh()` —— 整个挪进 `scope.launch { withContext(IO) }`。
  它要解两遍自定义镜像 JSON + 十个发行版各几次 lstat，而每个回调都调它。
- **[x] 文件页点击那一帧做 IO** `FilesActivity.runOp` —— `exists()` 过滤和 `isInside`（canonicalPath 逐段
  readlink）挪进已有的 IO 协程，失败再回主线程弹提示；拷贝进度按 100ms 合并（原来每 64KB 全量刷 state）。

## 体积深挖：assets 是唯一还值得动的地方

release 里 assets 占 12.87 / 14.48 MiB＝**89%**，dex 只剩 1.44 MiB —— 继续抠代码没意义了。
对裁剪后的 bootstrap（1070 个文件、解压 44 MB）实测：

| 打包方式 | 大小 | vs 现状 |
|---|---|---|
| zip / deflate（上游格式） | 19.92 MB | — |
| .tar.gz -9 | 19.56 MB | −0.36 |
| .tar.bz2 -9 | 18.26 MB | −1.66 |
| **.tar.xz preset 6** | **12.72 MB** | **−7.20** |
| .tar.xz preset 9\|EXTREME | 12.33 MB | −7.59 |

- **[x] 换成 .tar.xz preset 6**（`tools/pack_bootstrap.py`）。解码器用**本来就在依赖里**的
  `org.tukaani:xz`（审计发现它原是死依赖，Tarball 路径不可达），不新增任何依赖。
  `TerminalEnv.install` 改成 `XZInputStream` + `DistroInstaller.extractTarStream`
  （条目路径与旧 zip 完全一致，`SYMLINKS.txt` 那套一个字没改）。
  - **preset 用 6 不用 9**：xz 字典大小跟 preset 走，preset 9 是 **64MB 字典 → 解压端就要吃 64MB 内存**，
    手表上很可能直接 OOM；preset 6 是 8MB 字典，代价只是多 0.39MB。
  - **打包必须 `GNU_FORMAT`**：Python `tarfile` 默认 PAX，超长路径会写进 `'x'` 扩展头，
    而 `extractTarStream` 不认 `'x'` → 跳过扩展头、拿 ustar 里被截断的名字落盘，文件就散了。
    GNU 格式用 `'L'` 长名记录，解包端认。（本次裁剪后已无超 100 字节路径，但格式还是要对。）
  - **⚠ 真机要盯**：纯 Java 的 XZ 解码比 deflate 慢，首装那 44MB 的解压会比以前久。
    首装本来就是几分钟量级、且只发生一次，预期可接受，但**这是这轮唯一有性能回归风险的改动**。
- **[x] 去重不值得做**：全包只有 17 组重复内容，合计 0.23 MB（且大半是 GPL 许可全文，本来就不能删）。

### 体积账（累计）
| | 原始 | 第一轮 | 第二轮 |
|---|---|---|---|
| debug APK | 49.23 | 37.84 | **30.89** MiB |
| release APK | （没出过） | 21.59 | **14.48** MiB |
| └ dex | 17.83 | 17.83 / 1.44 | 17.83 / **1.44** |
| └ assets | 30.35 | 19.82 | **12.87** |

### 还能更小的（未做，按性价比）
1. **bootstrap 改首次运行在线下载** —— release 会掉到 **约 1.7 MiB**。但丢掉离线首装，
   且要先解决「终端 APK 自己都还没有分发渠道」那条（见「六、功能缺口」第 9 项）。**建议等分发方案定了一起做。**
2. `libexec/termux-am/am.apk` 0.55 MB（从终端里唤起安卓 Activity 用）—— 不用就能省，但会掉功能。
3. lib/ 里 libgnutls 0.88 + libunbound 0.47 + libgcrypt 0.47 MB 是 apt 的 TLS 依赖链，
   理论上能换成只用 openssl，**风险高**（apt 装不动就全废），不建议碰。
4. dex 再抠（R8 full mode / 去 coil）—— 现在只剩 1.44 MB，抠不出什么了。

---

# 2026-07-28 待办：多容器（一个发行版开 N 个实例）

用户需求原文：「增加一个"容器"功能，可以自己选一个容器的发行版，然后自己装包，切换容器就可以换发行版，
还能导出/导入，每个容器内部文件互不干涉」。

## 现状盘点（读码确认，别重新查）

**已经有的**（不用重做）：选发行版（`EnvRegistry.BUILTIN` 9 个 OCI 镜像 + `custom_distros` 自定义 repo:tag）、
装包（`DistroProvision.installCmd()` 八族分派齐全）、切换（`active_env`）、
每环境独立的套餐与镜像源（`flavor_<id>` / `mirror_<id>`）、备份（`BackupManager` 自写 tar writer，
zip 存不了符号链接所以不能用 zip）。

**缺的就一层**：`EnvModel.kt:259-260` 的
```kotlin
fun distroDir(ctx, id) = File(ctx.filesDir, "distros/$id")
fun distroRootfs(ctx, id) = File(distroDir(ctx, id), "rootfs")
```
—— **id 是发行版 id，一个发行版只有一个 rootfs**。装第二个 Debian = 覆盖第一个。

**改动面**：`distroRootfs|distroDir|EnvRegistry.spec|activeEnv|isDistroInstalled|installedDistros|
removeDistro|flavorOf|setFlavor` 共 **80 处、10 个文件**（BackupManager 2 / EnvControl 16 / EnvModel 15 /
FetchSetup 7 / EnvActivity 9 / DistroProvision 6 / TermActivity 6 / TermBeautify 3 / TerminalEnv 9 /
TermSettingsActivity 7）。**横切改动，不是加个页面。**

## 设计

**核心：把 `id` 拆成 `cid`（容器实例）与 `specId`（发行版模板）两个概念。**

- 容器注册表：prefs `term_ui` 加一条 JSON 数组 `containers` = `[{cid, name, specId, created}]`，
  与 `custom_distros` 同样的「坏数据跳过、不上 Room」策略。
- 目录：`distros/<cid>/rootfs`（**路径公式不变，只是 id 的语义从 specId 变成 cid**）。
- `active_env` 继续存单个 id，值域扩展为 `termux | system | <cid>`。

**⭐ 老数据零迁移（关键约束）**：现有已装环境的 **cid 直接等于 specId**。
即 `distros/debian/` 原地不动，`active_env=debian`、`flavor_debian`、`mirror_debian` 全部继续有效，
**一个文件都不用搬、一条 prefs 都不用改**。只有「同一发行版的第 2 个实例」才分配新 cid（如 `debian-2`）。
启动时若发现 `distros/<x>/rootfs` 存在但注册表无 `<x>` 条目 → **自动补登记**（自愈，兼容任何历史状态）。

**per-container 数据**：`flavor_<id>` / `mirror_<id>` 的 key 结构天然按 id 走，**改按 cid 走不用改结构**。

**导出 / 导入**：
- 导出 = rootfs 的 tar（复用 `BackupManager` 已有的自写 tar writer，符号链接必须保住）+ 一份
  `manifest.json`（cid/name/specId/family/flavor/mirror/创建时间/导出时间）。
- 导入 = 读 manifest → **分配新 cid**（避免与现有撞名）→ **staging 目录解压 + 原子 rename**。
  ⚠️ **不能先删后解**（这条已经踩过并修过一次，见「第一轮修复」）。
- 顺带填掉「六、功能缺口」里的 **离线 rootfs 导入** 那条。
- 落点 `/sdcard/Arix/containers/`，另提供 SAF 导出到任意位置。要有进度（rootfs 动辄几百 MB）。

**切换时活会话的处理**：`TerminalEnv.sessionSignature()` 机制已存在（切了环境后 `TermActivity.onResume`
比对签名不一致就自动重开会话）—— **把 cid 纳进签名即可**，不用新造轮子。

**隔离性**：rootfs 分目录本就天然隔离。⚠️ 但 `/sdcard/Arix/shared`（`TerminalEnv.sharedHostDir()`）
是**故意绑进所有环境**的共享目录（四条 argv 构造函数都绑了）。
**待用户拍板**：保留共享目录 + 每容器再给一个独立目录（倾向此方案），还是彻底隔离。

## 实施顺序（等 2026-07-28 那批 agent 落地、编译通过后再开工）

1. `EnvModel.kt` 加 Container 模型 + 注册表 + 自愈补登记；`distroDir/distroRootfs` 语义改 cid。
2. 逐个文件把 80 处调用点的 id 语义捋一遍（**重点**：哪些该用 cid、哪些仍该用 specId
   —— 比如 `DistroProvision` 按 family 分派要经 `specId` 查 spec，而路径/prefs 全用 cid）。
3. `EnvActivity` UI：容器列表（新建/改名/删除/切换/查看体积）、新建时选发行版模板 + 套餐。
4. 导出 / 导入。
5. `sessionSignature` 纳入 cid。

**⚠️ 不要在有其它 agent 占着 TermSettingsActivity.kt / FetchSetup.kt / TermBeautify.kt 时开工** —— 这三个文件都在 80 处调用点里。

---

# 2026-07-28 第一批实现（四路并行，已编译通过、真机零验证）

调研材料见 `RESEARCH-TERMINAL-2026-07-28.md`。**`:terminal:assembleDebug` 通过，
terminal-debug.apk 32.44 MB（较上轮 30.89 MiB 基本持平）。真机一条没验。NOTICE 已按 §4(b) 补声明。**

## 1. `TextArt.kt`（新建，473 行）图片 → 终端字符画

三模式：`HALF_BLOCK`(▀，每格 2 个真彩色像素) / `BRAILLE`(⠿，每格 2×4=8 二值点 + 1 平均色) /
`ASCII`(亮度映射 `" .:-=+*#%@"`)。借鉴 MurthiNext/img2text(MIT)，但**三处刻意不同**：

1. ⭐ **Braille 位序用 Unicode 标准点序**（`BRAILLE_BIT = [1,8,2,16,4,32,64,128]`，按 `行*2+列` 查）。
   **img2text 那个 `dot_map` 是错的**（只有首尾两位对，中间六位乱序）——后果很阴：每格点数和颜色都对、
   整体还像那张图，但**每格内部点位被打乱、细节成麻子**。别照抄。
2. **相邻同色不重发 SGR**（img2text 每格都重发）：这些字节要过我们自己的 `TerminalEmulator`
   状态机逐字节解析，去重能砍掉一半以上载荷。另做行尾空白裁剪（连同那段发过的转义一起截，
   并把颜色状态回滚到最后一个可见字符处，否则去重对不上）。
3. **宽高比由 `cellAspect` 参数驱动**（img2text 硬编码 0.5）。

其它要点：缩放**逐级折半**再收尾（安卓双线性缩小超 2 倍只采 4 点＝抽样，细线会断）；
alpha 单独 NEAREST 缩一份再二值化；⚠️**安卓位图是预乘的，透明像素 RGB=0，双线性会在轮廓外糊一圈黑晕**
→ 边缘像素改取 NEAREST 原色；Braille 的局部统计里透明像素按「背景=亮」记 255f（否则边缘外冒点）。
自适应局部阈值 `threshold = localMean - k*localStd`，自写可分离高斯，零新依赖。

## 2. `FetchSetup.kt`（新建）+ `TermSettingsActivity.kt`：启动信息 / fastfetch

prefs（`term_ui`）：`fetch_mode`(off/builtin/image/ascii)、`fetch_art_mode`(half/braille/ascii)、`fetch_cols`(8~60,默认20)。

| 环境 | 钩子 | logo | fastfetch 配置 |
|---|---|---|---|
| Termux | `$PREFIX/etc/profile.d/01-xtom-fetch.sh`（0755 + `case $- in *i*)` 交互门） | `~/.config/fastfetch/logo.txt` | `~/.config/fastfetch/config.jsonc` |
| OCI 发行版 | `<rootfs>/etc/profile.d/01-xtom-fetch.sh`（同上） | `<home>/.config/fastfetch/logo.txt` | 同左 |
| 系统终端(mksh) | `~/.mkshrc` 的 `# >>> Arix fetch >>>` 标记块 | `~/.xtom-logo.txt` | 不写（装不了 fastfetch，**只走 cat 分支**） |

- 用户选的**素材**落 `sharedHostDir()/fetch/`（无权限回落 `filesDir/fetch`）；
  **生成的 logo.txt 落各环境自己的家目录** —— 因为共享目录在三种环境里的 guest 路径各不相同
  （Termux `~/shared` / 发行版 `<home>/shared` / 系统终端靠软链），而写进脚本的必须是 guest 视角路径。
- `logo.type` 一律 **`file-raw`**：`file`(不带 -raw) 会做 `$1..$9` 占位符替换、**会吃掉我们的 ANSI 彩色**。
  （研究文档 §3.5 初稿此处自相矛盾，已勘误。）
- ⚠️ **Ubuntu 24.04 / Debian bookworm 官方源没有 fastfetch**（我们内置的正是 ubuntu:24.04）→
  读 `VERSION_CODENAME` 出 noble/bookworm 警告，提示下官方 polyfilled 二进制；**代码里不硬下载**。
- `cellAspect` 按 vendor `TerminalRenderer` 构造函数的原式用 Paint 现算
  （⚠️ vendor 量的是 `measureText("X")` 不是 "M"，跟 vendor 保持一致才与终端真实排版相符）。
- **收口**：初版因「只准改本文件」的限制复制了 `homeOf` / `writeExec` 两份实现，
  已把 `TermBeautify.homeOf` 与 `DistroProvision.writeExec` 提成 `internal`，副本改为委托。

## 3. sprite face（vendor `terminal-view`）：程序化绘制，不读字体

新增 `TerminalSprites.java`（**Arix 原创**，包级私有 final 类，静态 `int[128]` 表把 U+2500–257F
每个码点编成「上/右/下/左 各 2bit 线型 + 虚线段数 + 圆角位 + 对角线位」）。
`TerminalRenderer` 加 `mSpriteEnabled`(默认 true，一键退回字体) + run 拆分 + `drawTextRun(…, isSpriteRun)`。

覆盖：**U+2500–257F 制表符全套 128 个**（细/粗/双线、2/3/4 段虚线、圆角、对角线，无回退分支）、
U+2580–259F 块元素（阴影块用 alpha 0x40/0x80/0xC0 而非点阵，小字号不出摩尔纹）、
U+2800–28FF 盲文、**U+E0B0–E0B7** powerline。
⚠️ **U+E0B8–E0D4 未实现，且 `isSpriteCodePoint` 也不认它们** → 原样走字体
（判定集合与能画的集合严格一致，避免「拦截了却画不出＝空白」）。

自检修掉两个真问题：
1. **越界糊到邻格**（严重）：圆角 U+256D–2570、对角线 U+2571–2573、powerline 细分隔线/细半圆这些
   **描边+抗锯齿**图形，笔宽有一半落在 cell 边界外。修法＝**只对这几个码点** `save/clipRect/restore`；
   矩形/块元素/Braille 精确落格不裁剪（避免在字符画热路径上白花 save/restore）。
2. **下划线/删除线丢失**：走 sprite 就吃不到 `Paint.setUnderlineText/StrikeThru`，末尾补画两条细矩形。

设计约束：按**绝对列号**算 cell 边界（不是起点+累加宽度），否则相邻格差 1px 出缝；
直线/矩形关 AA、三角/圆弧开 AA，用独立 Paint 不污染文字 Paint；热路径第一条比较 `< 0x2500` 排除全部 ASCII。

## 4. OSC 133 / OSC 7 语义标记（vendor `terminal-emulator` + `TermBeautify.kt`）

**本轮只做「产生 + 存储」，UI 消费（跳转/整段复制）留下一轮。**

- `TerminalRow` 加 `byte mShellMark`（**不用 java enum**：transcript 上万行，每行多一个对象引用不划算）
  + `SHELL_MARK_NONE/PROMPT/PROMPT_CONT/INPUT/OUTPUT`。
- ⭐ **标记存进 `TerminalRow` 跟着行走**，不是外挂按屏幕行号索引的表 —— termux 屏幕是环形缓冲，
  外挂表一滚动就错位。`blockCopy()` 整行纵向搬运时一并搬 `mShellMark`。
- 失效点：ED(`J` 0/1/2)、IL(`L`)、DL(`M`)、进备用屏(DECSET 1049)、RIS(`reset()`，
  **清退出码和标记但不清 cwd** —— shell 的工作目录并没变)。
- ⚠️ **故意没在 `blockSet` 里通用地清标记**：否则 zsh/readline 重画提示符发的 `\033[2K` 会误杀提示符标记。
- ⚠️ **不能用 `java.net.URLDecoder`** 解 OSC 7 的路径（它会把 `+` 解成空格，路径就错）→ 自写 percent 解码。
- rc 侧四种 shell：bash(`PROMPT_COMMAND` 前插 + `trap DEBUG`+armed 标志；**`__xtom_ec=$?` 必须是函数第一条语句**)、
  zsh(`add-zsh-hook precmd/preexec`)、fish(`--on-event fish_preexec/fish_postexec`；
  ⚠️ **`set -l st $status` 必须留在 `fish_prompt` 第一句**，否则双行风格的失败标红废掉)、
  mksh/dash/ash(**只能把 A/B+OSC7 塞 PS1**，用真 ESC/BEL 字节；**C/D 做不到**)。
- 转义包裹按 shell 分开：bash `\[..\]` / zsh `%{..%}` / mksh 塞真字节 —— 沿用本仓既有做法。
- 双行风格换行后补 `133;A;k=s` 续行标记。
- 已确认：PS1 里的 `$(xtom_git_branch)` 命令替换**不会**误触发 DEBUG trap（真 bash 实测 4 命令→4 个 C）。

### 下一轮 UI 消费需要的接口（agent 建议，未做）
`findPrevPromptRow/findNextPromptRow`（**要跳过 `PROMPT_CONT`**，否则停在双行提示符第二行）、
`outputRangeAt(row)`（配合已有 `TerminalBuffer.getSelectedText`）、
把退出码挂到「该段 OUTPUT 起始行」（现在只存了最近一条）、
`TerminalSessionClient.onShellCwdChanged()` 回调（现在只能轮询）。

## ⚠️ 真机验证清单（按风险排序，全部未验）

1. **sprite face 的视觉与性能** —— 动的是**每帧每格都过的热路径**。看：htop/btop 的框线连不连得上、
   有没有缝/重叠、字符画糊不糊、滚动掉不掉帧、下划线/删除线还在不在、反色和选中状态颜色对不对。
   出问题先把 `mSpriteEnabled=false` 对比。
2. **OSC 133 的提示符渲染** —— 转义包裹错了**光标位置就错**（本仓老坑）。看：bash/zsh/fish/mksh
   各自提示符有没有错位、退格删到提示符里去、双行风格第二行对不对。
3. **启动信息** —— 三种环境各进一次，看 logo 出不出、彩色对不对、`fetch_mode=off` 能不能干净卸载；
   Ubuntu 24.04 下 fastfetch 装不上时**有没有正确退回 cat 字符画**。
4. 字符画三模式在**手表窄屏**（~20 列）下的实际观感，尤其 Braille 的 4 倍纵向分辨率是否真有肉眼差别。
5. 非交互路径确认：AI 的 `linux_exec` 跑一条命令，**输出里不许出现 logo / OSC 序列**（交互门是否生效）。

> ⭐ **2026-07-28 对抗审查的待修清单已独立成文**：`TODO-TERMINAL-FIXES-2026-07-28.md`。
> 含四路审查的全部发现（4 条安全 + 一堆功能）、已修 2 条、以及
> **「审查方未能证伪但仍需复核」整节**（用户明确要求：不存在「打不动」，不得当结论关掉）。
> 接手修 bug 直接从那份开工，不要重新审计。

---

# 2026-07-30 图形化落地：X11 + VNC（编译状态见文末 · **真机零验证**）

对应 `RESEARCH-TERMINAL-2026-07-28.md` 第二节的 Step 1~4。**Step 0（零代码真机验证）被跳过了** ——
直接把代码写了，所以「`-b .X11-unix` 在 proot 下到底通不通」这个根问题仍然悬着，第一次上机就是验它。

## 新增文件（全在 `terminal/.../gfx/`）

| 文件 | 干什么 |
|---|---|
| `GraphicsEnv.kt` | 地基。X socket 目录 / `/dev/shm` / `DISPLAY` / 分辨率 / 后端选择的唯一收口点 |
| `X11Server.kt` | 探测并用 `app_process` 直起**外部** X server App，拉起显示端，降级 flag |
| `VncServer.kt` | 容器内 Xvnc 的装、起、停、密码、桌面配方 |
| `rfb/RfbClient.kt` `rfb/Keysyms.kt` | 自写 RFB 3.8 客户端（Raw / CopyRect / ZRLE / DesktopSize） |
| `VncCanvasView.kt` | 画布 + 触摸/键盘 → RFB 事件；两套指针模式（直接触摸 / 触控板） |
| `VncViewerActivity.kt` | 内置查看器界面 |
| `GfxActivity.kt` | 「图形界面」控制页（抽屉里新增的一项） |
| `GfxService.kt` | 前台服务，把跑着图形会话的进程钉住 |

改到的既有文件：`TerminalEnv.kt`（四条 argv + `buildEnv` + `sessionSignature`）、
`TerminalNav.kt`（`TermDest.GFX`）、`AndroidManifest.xml`、`NOTICE`。

## 三条定死的设计（别改回去）

1. **X server 永远在我们的 APK 之外**。termux-x11 是 GPL-3.0，编进来 = 整包 GPLv3。
   我们只做启动方：`app_process` + `CLASSPATH=<对方 apk>` + `TMPDIR=<我们的私有目录>`，
   于是 server 跑在**我们的 uid** 下、socket 落在我们写得进的地方。官方那套
   Loader/广播/签名校验是为「Termux 与 Termux:X11 两个 uid」准备的，我们单 uid 用不上。
2. **VNC 客户端自写**。能嵌的全是 GPL，noVNC 要在容器里再装 websockify。只跑回环 →
   不需要 Tight/JPEG，Raw+ZRLE 足够。
3. **图形支持默认关闭**（`GraphicsEnv.enabled`）。关着时四条 argv 一个字节都不变，
   与已经真机验过的那条基线完全一致；开了才加绑定。开关进 `sessionSignature`，改完自动重开会话。

## ⚠️ 一个容易漏的跨边界契约

X socket 的客户端查找路径**两套不一样**：标准 glibc 的 libX11 找 `/tmp/.X11-unix`，
**Termux 的 libX11 被上游改成了 `$PREFIX/tmp/.X11-unix`**。所以 Termux 环境两个路径都要绑
（`GraphicsEnv.guestSocketPaths`）。少绑一个的表现是「一切看着正常、就是 cannot open display」。

## 真机验证清单（按风险排序，全部未验）

1. **`-b .X11-unix` 在 proot 下能不能让容器里的 X 客户端连上** —— 整条路的生死判据。
   最小验法：图形支持打开 → 装 X server App → 本页启动 → 终端里 `pkg install x11-repo` +
   `pkg install xorg-xterm` → 敲 `xterm`。
2. **`app_process` 能否在我们这个 targetSdk 28 的 App 里正常 exec 别人的 APK 入口类**，
   以及对方入口类名（`com.termux.x11.CmdEntryPoint`）是否还对得上。
3. **Android 12+ 幻影进程上限**会不会杀掉图形进程树 —— X11/VNC 共同的最高风险，VNC 先中枪。
4. **Xvnc**：Termux 版能否在不带 `-0` 的 proot 里起来；`stop()` 后桌面进程有没有残留；
   `-dpi/-nolisten tcp/-AlwaysShared` 是否被实际打包的版本接受。
5. **自写 RFB**：ZRLE 解码（调色板位打包的行末补齐、CPIXEL 3 字节、共用 Inflater）、
   VncAuth 的 DES 位逆序、CopyRect 重叠区。花屏/认证失败都指向这三处。
6. 触摸手感：圆屏上「触控板模式 + 小分辨率 + 无边框单窗口」是不是真能点中菜单。

## 这一期明确没做（不是遗漏）

音频（PULSE_SERVER 整条链路一次都没查证）、GPU 加速（virgl/Turnip/Zink，且都是 bionic 二进制、
glibc rootfs 用不了）、Wayland、我们自己 fork 的 `com.arix.x11` APK（Step 2，需要单独一个仓和 NDK 构建）。

## 编译状态

`:terminal:assembleDebug` 通过（`terminal-debug.apk` 33.05MB）、`:terminal:assembleRelease`
通过（R8，`terminal-release.apk` 14.59MB，比图形化之前只多约 0.1MB —— 因为 X server 不在包里、
RFB 客户端是纯 Kotlin）。**零警告。真机一次都没跑过。**

⚠️ 踩到的编译坑：**Kotlin 的块注释可以嵌套**，所以注释里出现「斜杠 + 星号」会开一个内层注释、
把后面半个文件吞掉，报错却指在文件末尾（`Unclosed comment`）。写路径通配符时避开这个组合。
