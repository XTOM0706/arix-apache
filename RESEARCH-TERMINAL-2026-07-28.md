# 终端调研 2026-07-28：Ghostty / 图形化(X11·VNC) / fastfetch 图片美化

四路并行调研的汇总。标注约定：**✅ 一手查证**（有 URL / 文件:行号）、**🔶 推断**、**⚠️ 二手或传闻，需真机验证**。
接 [TODO-TERMINAL-APP.md]。**本文件只记调研结论，不记实现进度**。

---

## 零、总原则（用户 2026-07-28 定）

> **「先有，用不用和用的人多少是另一回事。」**
> 以及：「VNC 和 X11 如果不在手表上是有用的。」

- **图形化的主场是手机 / 平板**，手表是降级场景（能跑就行），不拿最差场景一刀切否掉整条路线。
- 调研结论只能是**排序**（P0/P1/P2），不能是**淘汰**。成本高照实说，但要给出路径。
- ⚠️ **`TODO-TERMINAL-APP.md:412` 那句「明确不做：VNC/X11（手表上没意义）」据此作废。**

---

## 一、Ghostty

### 1.1 许可证（✅ 我方直读 LICENSE 原文核实）

`https://raw.githubusercontent.com/ghostty-org/ghostty/main/LICENSE`
首行 `MIT License`，版权行 `Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors`。

**这是目前查到的唯一一个许可证完全干净、代码可直接搬进我们工程的成熟终端**，与 vendor 的
termux terminal-emulator/terminal-view（Apache-2.0）兼容。
对照 [RESEARCH-COMPETITIVE-2026-07-27.md] 的结论（Eta 改禁商用 / HermesApp 未授权拷贝 / 橘瓣许可证有争议）——
**Ghostty 是唯一没有法律障碍的可抄对象**。

### 1.2 libghostty：现在不换，持续盯

- `libghostty` = 官方在拆的 C-ABI 跨平台库；先拆出 `libghostty-vt`（零依赖，只做序列解析 + 终端状态）。
- 官方明确警告 API **尚未稳定**、未作为独立库发布。作者原话大意：alpha 是指 API 形状，核心逻辑本身很稳。
- **Android 阻塞点（✅ 有官方 discussion）**：Zig 至今不支持 Android bionic libc，产出的 `.so` 缺
  `DT_NEEDED libc.so`，运行时 `dlopen` 解析 `__tls_get_addr` 失败；另需 `link_z_max_page_size = 16384`（16KB page）。
  作者已表态「有 workaround + CI 就 100% 接受」，但 **PR 尚未落地**。
- 无官方 Kotlin/Java JNI 绑定。两个下游 Android 移植项目：一个自述 "Research & Planning" 阶段，
  一个 README 直接 404。**都不能当参考实现。**
- 官方最小示例 **Ghostling**（单个 main.c + Raylib 2D 画布出终端，MIT）—— 🔶 形态和我们
  Android Canvas 同构，将来真要接 libghostty 时是最好的起点。
- **结论：不动解析层。盯着 Android CI 那条 discussion，一旦官方预编译 `.so` 落地再重估。**

### 1.3 该抄的设计（按性价比排序）

**① OSC 133 + OSC 7 — 语义标记（最高性价比）**

我们现在往 rc 里塞转义提示符是**单向的**：App 侧完全不知道哪几行是提示符、哪里是命令、哪里是输出、
上条命令退出码几、cwd 在哪。加语义标记后直接解锁：

- 新会话/新标签**继承 cwd**（OSC 7）
- **上滑跳到上一个 prompt**（手表小屏刚需）
- 长按**复制整段命令输出**
- 命令失败时 UI 高亮/震动
- QuickCmds **知道 shell 是否空闲**再插入命令

落地：
- `TerminalEmulator.java` 的 `doOscSetTextParameters` 加 `case 133`（行号 + kind + 退出码存进 row 元数据）
  和 `case 7`（解析 `file://host/path` 存 session cwd）。现状只处理 0/1/2/4/10/11/12/52/104/110-119。
- `TermBeautify.kt` 已有的 rc 注入里加 A/B/C/D 序列钩子（bash/zsh 用 precmd/preexec，fish 单独）。
  我们「一份 `.xtomrc.sh` 管三种 shell + fish 单独」的结构正好复用。
- **坑**：多行 prompt 必须照 Ghostty 用 `OSC 133;A;k=s` 标续行，否则跳转落错行。
- 🔶 成本：emulator + rc 侧 1~1.5 天；UI 消费（跳转/整段复制）再 1~2 天。风险低（未知 OSC 本就被忽略）。

**② Sprite face — 程序化画 powerline / box drawing / Braille（解我们的死结）**

`TermBeautify.kt` 注释白纸黑字写着「手表上装字体麻烦、渲染常出方块，所以绝不用 U+E0B0 那类私有区图标」，
只好用反色背景块凑合。**Ghostty 的答案是根本不读字体**：`src/font/sprite/` 里 `Box.zig` / `Powerline.zig`
把这些码点用几何绘制画出来，永远严丝合缝对齐 cell（官方原话大意：三角形不从任何字体加载，由内部 sprite 绘制，
所以总能对齐网格）。

落地：`TerminalRenderer.java`（仅 267 行，改动面小）在绘制单元格前加一层拦截 ——
码点落在 U+2500–U+259F（box/block）、U+E0B0–U+E0D4（powerline）、U+2800–U+28FF（Braille）时
不走 `Canvas.drawText`，改 `drawPath`/`drawRect` 按 cell 宽高比例画。

- 三角分隔符 = 3 点 Path；圆角框 = `arcTo`；块元素 = 按 1/8 分割填矩形。
- **坑**：按 grid 对齐而非按 glyph 对齐，否则相邻 cell 之间留 1px 缝（最常见翻车）。
- 🔶 成本：powerline 6 个码点 0.5 天；box drawing 全套 1.5~2 天；Braille 半天。可分批。
- **⭐ 与第三节联动**：`▀`(U+2580) 和 Braille(U+2800–28FF) **都在 sprite 覆盖范围内** →
  这层做了以后，图转字符画**彻底不依赖字体有没有这些字形**，"缺字形出方块/错位" 风险归零。

**③ kitty graphics 的架构决策（图片显示的正解）**

- Ghostty **明确放弃 Sixel 且不打算做**（✅ 有官方 discussion）：理由是 sixel 未规定的边界情况太多、
  libsixel 质量差不适合 drop-in、已有更好的 kitty graphics。**这两条理由对我们同样成立。**
- Ghostty 自称 kitty graphics 支持仅次于 kitty 本身，支持 unicode placeholder（能穿透 tmux）——
  ⚠️ 最强表述源自作者 X 帖，未直读原文，打折看待。
- **⭐ 最值得抄的一条：图片挂在 terminal screen 状态上，不挂在 renderer 上。**
  因为放置位置相对光标、删除语义、滚动/scrollback 联动**都属于终端状态机**。
  图省事塞进 View 层，一滚动就错位。
- ⚠️ 二手（文件名一致，可信度较高）：`src/terminal/kitty/` 六文件分工 ——
  `graphics_command`(解析 key=value + base64) → `graphics_exec`(执行) → `graphics_image`(校验/解码)
  → `graphics_storage`(按 Screen 存 Image+Placement，**每屏 320MB 上限 + LRU 驱逐**)
  → `graphics_unicode`(placeholder 还原) → `graphics_render`。

**④ 光标：我们领先，不用抄**

✅ Ghostty 到 1.2.0 为止**没有一等公民的动画光标**，靠给 custom shader 暴露光标 uniform
（`iTimeCursorChange` 等）+ 官方示例 shader 实现拖尾，release notes **自称 "stop-gap measure"**，
并说计划将来做一等公民动画光标以免用户承担 shader 的性能开销。社区 discussion 标题就是
"Neovide/Kitty-like Cursor Trail" —— **Neovide 才是原型，Ghostty 也在追**。

我们的 `CursorOverlayView.kt` 是凸包 smear + 指数逼近 + Choreographer 帧驱动 + 闲时停帧，
在手表低刷 / 动画缩放=0 的环境下比 shader 路线更省电也更可靠。

唯一可借鉴的一个点：Ghostty 的 `iTimeCursorChange` 语义是**按键触发、闪烁不触发**。
核一下我们的 `poke()` 有没有把闪烁也算作活动（会白白多跟帧）。🔶 0.5 小时。

**⑤ keybind 的 `performable:` 语义（小而美）**

Ghostty 的 `performable:` 前缀 = 该动作当前执行不了时，绑定视为不存在、按键**透传给终端程序**。
我们的 `ExtraKeysView` / 手势快捷键同样会和 vim/tmux 抢键。🔶 成本 2~4 小时。

### 1.4 宽字符隐患（新发现）

- Ghostty 有 `grapheme-width-method` 配置：`unicode`(默认，按 grapheme cluster 算，正确但可能与用
  `wcswidth` 的老程序光标失步) / `legacy`(走 wcswidth，兼容老程序但 emoji 宽度会错)。
  终端 mode 2027 启用时强制走 unicode。1.3.0 已支持 Unicode 17。
- **我们**：`WcWidth.java` 文件头注释写着 `wcwidth(3) for Unicode 15`，emulator 里 grep
  `grapheme` / `2027` **零命中** → 等同 Ghostty 的 legacy 模式，**没有 grapheme cluster 概念**。
- 🔶 影响：中文正文没问题（CJK 宽度在 wcwidth 里稳）。真正会错位的是
  **emoji ZWJ 序列 / 肤色修饰符 / 变体选择符 VS16** —— 我们 App 场景里 AI 输出 emoji 不少，会碰到。
- 🔶 性价比路线：不上完整 grapheme 引擎。① `WcWidth.java` 表升到 Unicode 16/17；
  ② 对 ZWJ(U+200D) 和 VS16(U+FE0F) 特判合并。约 1 天，风险低。完整 mode 2027 是大工程，排后面。

---

## 二、图形化：X11 / VNC

### 2.1 架构级结论（先定这个）

**termux-x11 是 GPL-3.0**（✅ 整仓 LICENSE 35,149 B + README 逐字 "Released under the GPLv3 license."）。
编进去的上游（xserver/libX11/pixman/xkbcomp）本身是 MIT/X11，但**合并分发后整体受 GPLv3 约束**。

- ❌ **把 `lorie` 直接编进 `com.arix.terminal` → 整个 APK 成为 GPLv3 派生作品，必须开源。**
- ✅ **规避：做成独立 APK `com.arix.x11`**（fork 自 termux-x11，delta 很小，源码公开），
  `com.arix.terminal` 只当 X 客户端宿主。X 协议是公开标准，客户端-服务器关系与任何 X 应用对 Xorg
  一样，不构成派生作品。🔶 **这是法律推断，不是律师意见**——但与我们已有的独立 App 架构天然契合。

**X server 只可能来自两处**（🔶 强推断，未见专门文档但机理清楚）：
① Android 侧的 APK（termux-x11 / XSDL）；② 容器内的 Xvnc（=VNC 路线）。
容器里 `apt install xorg` 装出的 `/usr/bin/Xorg` **跑不起来** —— 它要 DRM master + VT + `/dev/input/event*`，
Android 上 app uid 拿不到，**proot 只是 ptrace 伪 root，给不了真实设备访问**。
而 `Xvfb`/`Xvnc` 是纯用户态 server（Xvfb man 页 ✅：可在无显示硬件、无物理输入设备的机器上运行），容器里跑得动。
**没有第三种。**

### 2.2 Termux:X11 架构（✅ 源码级）

三模块：`lorie/`（**`com.android.library`**，minSdk 26，X server 本体 + 16 个上游 submodule）、
`lorie-app/`（薄壳 APK，nightly 产物 14,508,829 B 多 ABI debug）、`shell-loader/`（≈7KB）。

**启动链**：`termux-x11` 脚本 → `CLASSPATH=.../loader.apk` + `app_process ... com.termux.x11.Loader`
→ Loader 按包名找已装 APK → **`if (uid != Process.myUid() && 签名不匹配) continue`（同 uid 直接跳过签名校验）**
→ `PathClassLoader` 反射调 `CmdEntryPoint.main`。

> **⭐ X server 跑在 `app_process` 进程里、uid = 调用方，不在 Activity 进程里。**
> 之所以绕这么大圈，是因为官方场景下 server 必须以 `com.termux` 的 uid 才能写 `$PREFIX/tmp`，
> 而 APK 是 `com.termux.x11`，**两个 uid**。**我们是单 App，这个理由不成立 → 整套 app_process/广播/签名
> 可以全部省掉，由我们自己的 Service 直起。**

Activity 接入：`CmdEntryPoint` 广播 `ACTION_START`，`bundle.putBinder(null, this)` 传 Binder；
AIDL 只有两个方法 `getXConnection()` / `getLogcatOutput()`，返回 **socketpair FD**；
之后 Surface / ANativeWindow / 共享内存 FD / `pthread_cond_t` 都走这条 FD。server 每 1s 重播广播直到连上。

**能不能不装官方 Termux App？能，官方明确留了口子**：README「Using with 3rd party apps」+
chroot 一节给了绕过 loader 的原生调用法（✅ 对我们最有用）：

```sh
export TMPDIR=/path/to/container/tmp
export CLASSPATH=$(/system/bin/pm path com.termux.x11 | cut -d: -f2)
/system/bin/app_process / --nice-name=termux-x11 com.termux.x11.CmdEntryPoint :0
```

完全不依赖 Termux 前缀、不依赖 `termux-x11-nightly` 包。
（顺带：我们 targetSdk 28 < 30，**不受 Android 11 package visibility 限制**，`pm path` / `getPackageInfo`
无需 `<queries>`。）

### 2.3 socket 在哪 —— 决定改造量（✅ 最关键的一条）

**不是 abstract socket，是文件系统 unix socket，路径由运行时 `TMPDIR` 决定。**

- 编译期 `-D_PATH_TMP=getenv("TMPDIR")?:"/tmp"`；`cmdentrypoint.c` 回退链
  `$TMPDIR/.X11-unix/X<n>` → `/tmp` → `/data/data/com.termux/files/usr/tmp`。
- → **我们可以把 TMPDIR 指向任意自己的宿主目录**，再在 proot 里绑到 `/tmp/.X11-unix`。
  这正是 proot-distro 的做法（✅ `--bind=$PREFIX/tmp/.X11-unix:/tmp/.X11-unix`），
  同处还有 **`--bind=<rootfs>/tmp:/dev/shm` 并 chmod 1777 —— 我们也得照抄这条**。
- `cmdentrypoint.c` **专门写了 proot 分支**（注释大意：proot/proot-distro 场景下
  `LD_PRELOAD` 里的 libtermux-exec 会破坏链接，故跳过预加载）；`XKB_CONFIG_ROOT` 有 `// proot case`
  自动探 `/usr/share/X11/xkb`。**说明官方把 proot 当一等场景支持。**
- ⚠️ `-listen tcp`：属 xorg-server 上游语义，README **完全没文档化**，有 issue 称只有加它才连得上
  且被 closed as not planned。**别当主方案。**

### 2.4 共享内存 / GPU / 输入（✅）

- 三套出图路径：**AHardwareBuffer**(GPU) / **ashmem**(LorieBuffer) / **SysV shm**
  （`-force-sysvshm`）。降级开关齐全：`-legacy-drawing` / `-force-bgra` / `-disable-dri3` / `-disable-gpu-present`。
- 我们**已经带了 `libandroid-shmem.so`**（随 proot 安置），MIT-SHM 在 Termux rootfs 场景正好对上。
  🔶 glibc 的 OCI rootfs 里没有它 → MIT-SHM 失败，退化成 socket 传图（慢但能用）。
- 输入：Pointer 三模式 **Trackpad / Simulated touchscreen / Direct touch**；Back 键切软键盘；
  三指下滑出附加键条。
- **GPU 加速**：`virglrenderer-android` / `mesa-vulkan-icd-freedreno`(Turnip) 在 termux-main，
  `mesa-zink` 在第三方 TUR 源；全是 **bionic 二进制，Debian glibc rootfs 里用不了** →
  只能「Termux 侧起 virgl server，容器内 `GALLIUM_DRIVER=virpipe` 当客户端」。
  Turnip/Zink 实测只在 Qualcomm Adreno 有意义；Termux wiki 自己写着 "there is no hardware acceleration
  for rendering"。🔶 **一期完全不碰。**
- 音频（pulseaudio）：**完全未查证**。

### 2.5 x11 客户端包从哪来（✅）

- **Termux bootstrap rootfs**：`pkg install x11-repo` 只写一个 sources.list
  （`deb https://packages-cf.termux.dev/apt/termux-x11/ x11 main` → `$PREFIX/etc/apt/sources.list.d/x11.list`），
  验签靠 `termux-keyring`（官方 bootstrap 自带）。索引实测含
  `termux-x11-nightly / xorg-server / xorg-server-xvfb / tigervnc / x11vnc / xwayland / sway / labwc /
  weston / wlroots / wayvnc / xfce4`。
  **我们的 rootfs 物理路径就是真前缀 → 直接可用。** 自起 server 时 `termux-x11-nightly` 可以不装。
- **OCI Debian/Ubuntu/Arch**：🔶 **用不了 x11-repo**（bionic vs glibc、前缀不符）。装发行版自己的 `xterm/xfce4/…`。

### 2.6 VNC 路线

- **可行性**：⚠️ 有一篇 2025-12 的完整实操记录（个人博客）：Ubuntu proot-distro 里
  `apt install tigervnc-standalone-server awesome qterminal` → `vncserver` 直起 →
  Android VNC Viewer 连 `127.0.0.1:5902` 成功，**无需 /dev/shm、dbus、SecurityTypes workaround**。
  proot 不做网络命名空间，回环监听正常。
- **性能**（✅ 最有分量的一条，termux-x11 作者 twaik 原话）：
  > "Performance of X server itself is same for TigerVNC and Termux:X11, but there is a significant
  > difference in the way it displays image to your screen."

  即**差距不在 X server，在出图链路**（VNC 多一层 RFB 编解码）。
  ❌ **全网没有任何真实 benchmark（fps/ms/CPU%），不要在设计文档里引用具体数字。**
- **⚠️ 最大隐患：Android 12+ phantom process killer**。多篇记录把 "connection closed unexpectedly /
  signal 9" 归因于系统级 32 个 phantom process 上限，需 adb
  `settings put global settings_enable_monitor_phantom_procs false`（有上游 termux-app issue）。
  **VNC 的进程树（Xvnc + WM + 终端 + 应用）计数涨得最快，且 targetSdk 28 挡不住这个机制。**
  手表 OEM ROM 上没法要求用户跑 adb。**这是 A/B 两条路共同的最高风险项。**
- **体积**（🔶 按 apt 索引算依赖闭包，量级可信、精确值待实测）：Debian bookworm arm64 上
  `tigervnc-standalone-server` 全闭包 **+216.7 MB**，加 openbox+xterm **+364.5 MB**，
  改 twm+xterm **+225.3 MB**；人为剔掉 libgl1/mesa/llvm 才降到 **+30 MB**。
  真凶 = `opengl → mesa → libllvm15 (106.5 MB)` + `libz3-4` + Termux 侧的 `perl (66.7 MB)` / `libicu (42.6 MB)`。
  （单包本身很小：`tigervnc-standalone-server` .deb 仅 905.2 KB ✅。）
- **内嵌 VNC 客户端的许可证**（✅ 全部核对）：

  | 项目 | 许可证 | 能否嵌进我们 APK |
  |---|---|---|
  | AVNC | GPL-3.0，且只有 `:app`、**无可复用 library 模块** | ❌ |
  | bVNC | GPL-3.0 | ❌ |
  | LibVNCServer/libvncclient | **GPL-2.0-or-later，无 linking exception** | ❌ 链进去整包传染 |
  | TightVNC Java viewer | GPL-2.0 | ❌ |
  | **noVNC** core | **MPL-2.0**（HTML/CSS 为 BSD-2） | ✅ 文件级 copyleft，WebView 里可用 |
  | **vnc-rs** (HsuJv) | **MIT OR Apache-2.0** | ✅ 最干净，但工作量最大 |

  **⭐ 一条常被忽略的分界：容器内 `apt` 装的东西不是我们分发的，GPL 不传染；只有打进 APK 的才受约束。**
  **最省事的合法路**：发 `vnc://127.0.0.1:5901` deep link 给 AVNC
  （它有 exported 的 `UriReceiverActivity` + `scheme="vnc"` ✅），属正常 App 间调用，零法律风险。

### 2.7 其他候选

- **XSDL**（pelya/xserver-xsdl）：xorg-server 的 SDL/Android 移植，`COPYING` = **X.Org MIT（许可证最友好）**，
  **纯 TCP 连接**（天然穿透 proot，不用共享目录）。但源码仓库最后提交 **2016-06**；
  ⚠️ Android 14 崩溃属传闻。→ **备胎/降级**。
- **Wayland**：x11-repo 有 `wayland/xwayland/sway/labwc/weston/wlroots/wayvnc`（**无 cage**）；
  Android 原生 Wayland compositor 基本不存在（两个 termux-wayland 项目一个 2021 停更、一个自述
  "DO NOT USE YET"）。`sway --headless + wayvnc` 最后还是落回 VNC，比 termux-x11 更绕。→ 排最后。
  （⚠️ Termux wiki 称 Termux:X11 "provides a Wayland compositor"，与 README 自称 "a fully fledged X server"
  矛盾，**wiki 那句过时，以源码为准**。）
- **Winlator**（LGPL-2.1）：唯一可复用点是自带**纯 Java X server**（`com.winlator.xserver`，106 文件，
  unix socket + epoll），但与 Wine/Vortek 耦合深。🔶 记一笔，暂不动。
- **Box64 / Mobox / Andronix / udroid**：与「有没有 X server」正交，或只是 proot-distro 配方合集。排除。

### 2.8 对比表

| 维度 | A. Termux:X11 | B. VNC | C1. XSDL | C2. Wayland |
|---|---|---|---|---|
| 可行性 | ✅ 官方支持 proot，源码有专门分支 | ⚠️ 有个人实操记录 | 🔶 能跑但代码冻结 10 年 | ❌ Android 端缺显示端 |
| 性能 | 出图链路最短 | 多一层 RFB 编解码 | 老 SDL 路径，未知 | 最终仍落回 VNC |
| APK 增量 | 🔶 arm64+release 估 4–6 MB（独立 APK） | 0（不内嵌）或 +noVNC 几百 KB | 装第三方 App | — |
| rootfs 增量 | 只需 X 客户端（xterm 级几十 MB） | 🔶 **+200～380 MB**（mesa/llvm/perl 硬依赖） | 同 A | 同 B |
| 许可证 | **GPLv3** → 必须拆独立 APK | 不内嵌=零风险；noVNC(MPL)/vnc-rs(MIT) 可内嵌 | **MIT，最低** | — |
| 手表小圆屏 | 有 Direct touch/Trackpad，仍需单窗口 | ⚠️ 查不到任何 Wear VNC 客户端 | ❌ | ❌ |
| 改造量 | **小**：+1 条 `-b .X11-unix`、TMPDIR/DISPLAY、`/dev/shm`、前台服务 | 中：装 200MB+ 包、进程树长、phantom killer 风险高 | 小（纯 TCP） | 大 |

### 2.9 地基现状（✅ 读码，非听说）

- 全仓 `DISPLAY` / `XDG_RUNTIME_DIR` / `.X11-unix` / `/dev/shm` **零处理**；
  `/tmp` 只是 rootfs 里的普通目录，不是 tmpfs 也没 bind。
- **bind/env 没有统一收口点** —— `TerminalEnv.kt` 里**四条独立的 argv 构造函数**要各改一遍：
  `buildProotArgv`(Termux，**故意不加 -0**) / `buildDistroProotArgv`(`-0 --link2symlink` + 伪 /proc，
  env 走 `env -i` 白名单) / `buildDistroUsernsArgv`(`unshare -Urmpf` 内联挂载脚本) / `buildChrootArgv`(su)。
  **这是主要成本。**
- 已有「宿主目录 → guest 路径」的 `-b` 机制（`sharedHostDir()` → `~/shared`），
  **加一条 `.X11-unix` 绑定就是同款代码，几行。**
- manifest：**无 `SYSTEM_ALERT_WINDOW`、无任何 `foregroundServiceType`、`TerminalService` 全文件零
  `startForeground`**。图形化必然长驻，这块要补（而前台服务本来就是功能缺口第 1 项）。

### 2.10 分步落地

- **Step 0（半天，零代码，先证真伪）**：装官方 Termux:X11 APK，用 2.2 的原生调用法启动
  （`TMPDIR` 指向我们 rootfs 里对应 `/tmp` 的宿主目录），proot 加
  `-b <hostTmp>/.X11-unix:/tmp/.X11-unix` + `DISPLAY=:0`，`apt install xterm` 后敲 `xterm`。
  **判据 = X server 窗口里出现 xterm 并能打字。** 全程手工跑，不改一行代码。
  Termux rootfs 场景更简单：`$PREFIX/tmp` 在宿主上物理存在，理论上连 bind 都不用。
- **Step 1**：把 TMPDIR / `-b .X11-unix` / `-b <rootfs>/tmp:/dev/shm`(1777) / `DISPLAY`
  固化进四条 argv 构造函数。
- **Step 2**：fork `lorie` 成独立 APK `com.arix.x11`（arm64-only、release、**源码公开 GPLv3**），
  去掉 loader/签名那套，由我们的 Service 直起 + AIDL 握手。
- **Step 3**：补前台服务 + 圆屏适配 + **单窗口模式**。
- **Step 4**：xfce4/openbox 一键配方（手机）、noVNC/WebView 远程（MPL-2.0）、deep link 给 AVNC。
- **排后面**：GPU 加速（virgl/Zink/Turnip）、Wayland、音频。

### 2.11 必须真机验证（别当已知）

1. `-b .X11-unix` 在 proot 下能否让容器内 X 客户端连上 —— **Step 0 的唯一目的**。
2. **Android 12+ phantom process killer 会不会杀我们的进程树** —— A/B 共同的最高风险项。
3. `app_process` 能否在 targetSdk 28 的 App 里正常 exec（🔶 应无问题，它是系统二进制）。
4. MIT-SHM 在 glibc OCI rootfs 里必然退化（无 libandroid-shmem），退化后实际可用性未知。
5. `-listen tcp` 是否真可用（未文档化）。
6. `lorie` 能否在我们 compileSdk 36 / targetSdk 28 / minSdk 26 的 Gradle 里编过。
7. 所有体积数字（rootfs +200~380MB、APK 4~6MB）均为索引推算，**未实测**。
8. 音频整条链路完全未查证。

### 2.12 手表场景（附录）

**做，但手表上默认收起入口 / 只走单窗口模式** —— 不是不做。

- 内存：Xlorie + xterm 起步几十 MB 尚可；xfce4 一套 300MB+，手表常见 1–2GB RAM 且系统占大半 → 必然被杀。
- 体积：VNC 那条 200–380MB 的 rootfs 增量在手表上出局；**A 路线的 rootfs 增量小得多，是唯一有戏的**。
- **圆屏几何**：X server 的 geometry 是矩形，**四角被裁**，WM 的标题栏/关闭按钮/菜单必然落进不可见区。
  客户端缩放救不了 → 正确姿势是**服务端就跑小分辨率 + 无边框全屏单窗口**。
- 无鼠标：Simulated touchscreen（单击=左键、长按=按住）在 ~450×450 上勉强可用；Trackpad 基本不可用。
- ⚠️ Wear OS 上的 VNC 客户端：**查不到任何一个**，也无任何实测报告。
- 🔶 手表上真正有意义的用法：`-xstartup` 直接起**单个应用**（无 WM 或 openbox 无边框），
  例如 gnuplot/matplotlib 出张图看一眼。多数场景下
  **「容器里生成图片 → 回传到聊天里看」比开 X server 更合理省电**，值得同时做这条产品路径。

---

## 三、fastfetch + 自定义图片 / ASCII

### 3.1 我们的终端到底能不能显示图片（✅ vendor 源码实证）

文件：`terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java`

| 能力 | 结论 | 证据 |
|---|---|---|
| **24bit 真彩色** | ✅ **完全支持**（前景38/背景48/下划线58 的 `;2;r;g;b`） | L1924-1949 |
| **Sixel (DCS q)** | ❌ 不支持 | `doDeviceControl()` L918-1038 只认 `$q`(DECRQSS) 和 `+q`(termcap)，其余 → "Unrecognized device control string" |
| **kitty (APC _G)** | ❌ 不支持，但**静默吞掉** | `doApc()` L1043-1048，注释原文 `// Eat APC sequences silently for now.` |
| **iTerm2 (OSC 1337)** | ❌ 不支持 | `doOscSetTextParameters()` L2013-2152 只处理 0/1/2/4/10/11/12/52/104/110-119 |

**⭐ 失败方式不同，这点很重要：**
1. **kitty / APC：不会乱吐**。L571-578 在 `processCodePoint` 开头就把 APC 状态截走，整串静默吃掉，屏幕干净。
   → **插入点干净现成。**
2. **Sixel / DCS：会满屏乱码**。DCS 数据逐字节塞进 `mOSCOrDeviceControlArgs`，上限
   `MAX_OSC_STRING_LENGTH = 8192`（L95）；超限 L1029-1032 直接 `setLength(0); finishSequence();`
   → 状态机回 `ESC_NONE`，**后续 sixel 数据（全是可打印 ASCII）被当普通文字打印**。任何真图都远超 8KB。
3. **iTerm2 / OSC 1337：同样会乱吐**（`collectOSCArgs()` L2285-2292 超限 → `unknownSequence`）。

渲染侧佐证：`terminal-view/.../TerminalRenderer.java` 的 `drawTextRun()` L177-254 只有
`canvas.drawRect`(背景) + `canvas.drawTextRun`(字形)，**没有任何位图层**。

### 3.2 三条路（按「先有」原则排序，不淘汰）

- **P0：ASCII/ANSI 字符画（`▀` 半格块 / Braille / ASCII 梯度）**
  **vendor 零改动**，24bit 已验证支持。宿主侧自己生成，或容器内 chafa/fastfetch 生成。
  **⭐ 这是唯一能覆盖「系统终端(mksh)」的方案** —— 那条路没有包管理器，装不了 fastfetch/chafa。
- **P1：kitty graphics 最小子集**。入口就是 L1043 那句 `// Eat APC sequences silently for now.`。
  最小可用 = `a=T`(传输并显示) + `f=100`(PNG，Android 有 `BitmapFactory`，不必自己解码) + `m=0/1`(分块) + `d=`(删除)。
  放置信息存进行元数据，`TerminalRenderer` 按 cell 坐标 `drawBitmap`。
  🔶 3~5 天。**风险=内存**：照 Ghostty 设总量上限+LRU 驱逐（手表上 320MB 要降到 ~32MB）；
  base64 大载荷**必须流式解**，别拼 String（已有 `MAX_OSC_STRING_LENGTH = 8192` 的教训）。
  unicode placeholder（tmux 穿透）再 +2 天，排后面。
- **P2：Sixel**。要动 5 处：① `doDeviceControl` 改流式子状态机（绝不能进 `mOSCOrDeviceControlArgs`）
  ② 写 sixel 解码器（调色板 `#Pc;Pu;Px;Py`、重复 `!Pn`、`$`回车、`-`换行、六像素位段）
  ③ 图片放置表 + **锚定绝对行号**（termux 屏幕是环形缓冲 `mScreenFirstRow`，否则滚动错位）
  + ED/清屏/主屏↔备用屏切换的失效 ④ renderer 加 `drawBitmap` ⑤ 光标推进语义 + Bitmap 回收。
  🔶 800~1500 行。**Ghostty 明确放弃 sixel 押注 kitty，我们同理，所以排最后 —— 但不写"不做"。**

> 改 vendor 要按 Apache-2.0 §4(b) 在 NOTICE 逐条声明，`NOTICE` L48-61 已有两个 patch 的先例，走流程即可，不是红线。

### 3.3 img2text（用户指定借鉴，MurthiNext/img2text，MIT，Python）

**该拿的：**
1. **三种模式互补** —— 做成用户可选，成本只是多两个分支：

   | 模式 | 每格信息量 | 强项 | 适合 |
   |---|---|---|---|
   | `▀` 半格块 | 2 个真彩色像素 | 颜色准 | 照片、彩色 logo |
   | `⠿` Braille | **2×4=8 个二值点** + 1 个平均色 | **结构细（纵向 4 倍）** | 线稿、剪影、单色 logo |
   | ASCII `" .:-=+*#%@"` | 1 字符 + 1 色 | 复古、最省字节 | 兜底 |

   手表 ~20 列窄屏上，Braille 那 4 倍纵向分辨率是实打实的差别。
2. **自适应局部阈值**（它最聪明的一处）：`threshold = local_mean - k * local_std`，
   靠高斯滤波求局部均值/标准差。比全局阈值对 logo 好太多（渐变背景、抗锯齿边缘不会整块糊掉）。
   它用 SciPy `gaussian_filter`；我们写个可分离高斯（横一遍竖一遍）即可，几十行。
3. **缩放细节**：RGB 用 LANCZOS、**alpha 用 NEAREST**（不能插值，否则透明边缘出半透明脏边）；
   高度取整到 4 的倍数（Braille）/ 2 的倍数（半格块）。
4. Braille 每格颜色 = 非透明像素的**平均 RGB**；透明像素在半格块模式下输出空格。

**⚠️ 不能照抄的三处：**
1. **它的 Braille 位映射不符合 Unicode 标准。** 原文（逐字核过）：
   ```python
   dot_map = [(0,0), (2,0), (0,1), (2,1), (1,0), (3,0), (1,1), (3,1)]   # (行, 列)
   ```
   Unicode Braille Patterns 标准点序应为
   bit0→(0,0) bit1→(1,0) bit2→(2,0) bit3→(0,1) bit4→(1,1) bit5→(2,1) bit6→(3,0) bit7→(3,1)。
   **它只有首尾两位对，中间六位是乱序排列。**
   后果很阴：每格的**点数和颜色都还是对的**，整体看还像那张图，但**每个 2×4 格内部点位被打乱、细节成麻子**。
   （大概就是它带 79 star 也没人发现的原因。）**用标准点序。**
2. **它每个字符都重发完整前景+背景 SGR，没做去重。** 对它无所谓，对我们不行 ——
   这些字节要过我们自己的 `TerminalEmulator` 状态机逐字节解析。**相邻同色不重发能砍掉一半以上载荷。**
3. **它宽高比硬编码 `aspect_ratio = 0.5`。** 我们有真实字体度量
   `TerminalRenderer.mFontWidth` / `mFontLineSpacing`，直接算，不要猜。

**⭐ 与 Ghostty sprite face 的呼应**：`▀`(U+2580) 和 Braille(U+2800–28FF) **都在 sprite 覆盖范围内**。
把 §1.3② 那层绘制拦截做了，字符画就**彻底不依赖字体有没有这些字形**，"缺字形出方块/错位" 风险归零。**两条线合流。**

### 3.4 fastfetch 侧（✅）

- **logo-type 全表**：`auto` / `builtin` / `small` / `file`(做 `$1..$9` 颜色占位符替换) /
  `file-raw`(不替换，`-`=stdin) / `data` / `data-raw` / `command-raw`(仅 JSONC 可用) /
  `sixel` / `kitty` / `kitty-direct` / `iterm` / `chafa` / `raw` / `none` / `media-cover`。
- **⭐ 所有图像库都是运行时 dlopen，不是硬链接** —— fastfetch 只硬依赖 libc/libdl/libm/libpthread。
  **「二进制装了 ≠ 能出图」，还得单独装 chafa / imagemagick。**
- **配置** `~/.config/fastfetch/config.jsonc`：
  ```jsonc
  "logo": { "type": "chafa", "source": "/path/logo.png", "width": 18,
            "height": 0, "padding": { "top": 0, "left": 0, "right": 2 },
            "color": { "1": "blue", "2": "green" } }
  ```
  padding 默认 left 0 / **right 4** / top 0。只给 width 或 height 之一则保持宽高比。
  自定义 ASCII 用 `"type": "file"` + 文件里 `$1`…`$9` 占位符（字面 `$` 写 `$$`；
  **未设置的占位符直接被丢弃**，不会打印出 `$1`）。不想替换就 `file-raw`。
  schema 里还有 `printRemaining` / `preserveAspectRatio` / `recache` / `position`，
  以及 `chafa` 子对象（`fgOnly`/`symbols`/`canvasMode`/`colorSpace`/`ditherMode`）——
  **`chafa.symbols` 就是"只用半格块"的开关**。
- **安装现状**：
  - Termux：`pkg install fastfetch` **有**，MIT，`TERMUX_PKG_BUILD_DEPENDS` **含 chafa 和 imagemagick**
    → chafa/sixel/kitty/iterm 都编进去了。
  - 官方 release：`fastfetch-linux-aarch64.tar.gz` ~5.96MB（glibc）、
    **`-polyfilled` ~1.48MB**；**没有 Android/bionic 资产，也没有真 static musl 版**
    → 官方二进制只能进 proot 的 glibc rootfs，**不能在 Termux(bionic) 跑**。
  - **⚠️ Debian bookworm 里根本没有 fastfetch（连 backports 也没有）；Ubuntu 24.04 无，24.10+ 才有。**
    **我们内置的正是 `library/ubuntu:24.04`** → 必须用 polyfilled 二进制或 PPA 兜底。
    Debian trixie 有 `2.40.4+dfsg-1`，sid `2.62.1+dfsg-1`。
  - **chafa**：Termux 有（**LGPL-3.0**）；Debian/Ubuntu 全线都有 `chafa` 包。
- **Android 下能读到什么**：OS/内核/型号/Shell/Terminal/CPU/内存/磁盘/包数走 `/proc` 和 `getprop`，正常。
  🔶 **电池/WiFi 在 Termux 下需要 `termux-api` 包 + Termux:API App，我们是独立 App → 大概率空**。
  🔶 GPU 靠 `libvulkan`/`libEGL`，**proot + glibc rootfs 里读不到宿主 bionic 库 → 基本会空**。
- **取舍**：neofetch **已于 2024-04-26 归档只读**，不要用。macchina 活着但 logo 图像能力远不如。
  → **fastfetch 是唯一合理选择。**

### 3.5 落地方案

**用户侧**：「个性化」页加一组「启动信息」= 关 / 内置 / 自定义图片 / 自定义 ASCII，
选图走 SAF、模式选 半格块/Braille/ASCII。

**⚠️ 挂点（两路 agent 有分歧，以读码那路为准）**：
**不要挂 `TermBeautify.greetingSh()`** —— 美化设为 `off` 时 `apply()` 会**删掉 `.xtomrc.sh`**，
fastfetch 会跟着一起消失。正解：
- 发行版 → `/etc/profile.d/01-xtom-fetch.sh`，**必须抄 `DistroProvision.kt:158` 那道
  `case $- in *i*)` 交互门**（否则非交互 `shell -l -c` 也 source /etc/profile，输出会灌进 AI 的 `linux_exec`）
- Termux → `$PREFIX/etc/profile.d/`
- 系统终端 → `~/.mkshrc` 标记块（`$ENV` 指向它），复用 `TermBeautify.hook()` 的幂等标记块机制
- 落盘复用 `DistroProvision.writeExec()`（含 `Os.chmod 0755`）

**送图片进容器**：抄 `TermuxStyle.setFontFromUri()`（tmp + rename + 大小上限）+
`TermSettingsActivity` 的 SAF（**必须 IO 线程 copy，主线程拷大文件=ANR**）。
🔶 落点建议用 `TerminalEnv.sharedHostDir()`（`/sdcard/Arix/shared`，四条启动路径都已 bind），
**而非 `TermuxStyle.styleDir()`** —— 后者写死 Termux 家目录、不跟随 activeEnv，是个现成的坑。

**按环境分工**：

| 环境 | 装什么 | logo 放哪 | logo.type |
|---|---|---|---|
| Termux | `pkg install fastfetch`（chafa 可选，见下） | `homeDir/.config/fastfetch/` | **`file-raw`** |
| OCI 发行版 | `apt install fastfetch`；**Ubuntu 24.04/bookworm 无包 → 下 polyfilled 二进制** | `rootfs/<home>/.config/fastfetch/` | **`file-raw`** |
| **系统终端 (mksh)** | **装不了，无包管理器** | 宿主生成 `~/.xtom-logo.txt` | 无配置，rc 里直接 `cat` |

> **⚠️ 勘误（2026-07-28，实现时发现）**：本节初稿此表写「图→`chafa`、文本→`file`」，与同节 P0 的
> 「三种环境统一喂 `file-raw`」自相矛盾。**以 `file-raw` 为准**，理由有二：
> ① `file`(不带 -raw) 会对内容做 `$1..$9` 颜色占位符替换，**会吃掉我们生成的 ANSI 彩色转义**；
> ② 走我们自写的 `TextArt` 生成字符画，就不需要 fastfetch 侧的 chafa/imagemagick 依赖。
> `chafa` 留作 P1 的可选增强（用户已在容器里装了 chafa 时可切过去换更好的抖动质量）。

**装包落点**：`DistroProvision.pkgs()` 加档 + 复用 `installCmd()`（**apt/apk/pacman/dnf/zypper/xbps/pkg
八族分支已齐全**）；或用 `TermBeautify.installShellCommand(family, shell)` 生成一次性命令。
⚠️ **`PkgActivity` 是写死 apt 的、不族感知**（`DPKG_LIST_CMD` / `apt-cache search` / `apt update`），
在那儿加按钮要自己接族分派，否则在 Alpine/Arch 上是死的。

**分阶段**：
- **P0**：宿主自写 Kotlin「图片→字符画」生成器（三模式）→ 三种环境统一喂 `logo.type: "file-raw"`；
  系统终端直接 `cat`。**vendor 零改动、不引入 chafa 的 LGPL、不装 imagemagick。**
- **P1**：rootfs 里若已装 chafa，允许切 `logo.type: "chafa"` 拿更好的抖动质量。
- **P2**：kitty graphics（见 §3.2）。

### 3.6 待实测

1. Termux 里 fastfetch 的电池/GPU 模块是否为空（无 Termux:API）。
2. 我们终端字体是否有 U+2580 / U+2800 等字形、宽度判定是否正确
   （`TerminalRenderer` L130 有 `fontWidthMismatch` 兜底，缺字形不崩但可能错位）。
   —— **做了 sprite face 后这条自动消失。**
3. proot glibc rootfs 里 fastfetch 能读到多少宿主信息。
4. `logo.type: "chafa"` 是否仍需 imagemagick 才能载入 PNG（wiki 措辞含糊）。

---

## 四、合并排期建议

按「先有」原则，全部保留，只排先后：

| 序 | 事项 | 🔶成本 | 依赖 | 见效 |
|---|---|---|---|---|
| 1 | **图片→字符画生成器（三模式）+ fastfetch 接线** | 2~3 天 | 无 | 立刻可见 |
| 2 | **X11 Step 0 手工验证**（零代码） | 半天 | 需真机 | 决定整条路 |
| 3 | **sprite face**（powerline + box + Braille） | 2~3 天 | 无 | 美化+字符画双收 |
| 4 | **OSC 133/7 语义标记** | 2~3 天 | 无 | 交互质变 |
| 5 | **X11 Step 1~3**（bind/env + 独立 APK + 前台服务） | 5~8 天 | 依赖 2 | 大功能 |
| 6 | WcWidth 升 Unicode 17 + ZWJ/VS16 特判 | 1 天 | 无 | 修 emoji 错位 |
| 7 | kitty graphics 最小子集 | 3~5 天 | 建议在 3 之后 | 真图片 |
| 8 | `performable:` 语义 | 2~4 小时 | 无 | 小修 |
| 9 | Sixel / GPU 加速 / Wayland / 音频 / unicode placeholder | — | — | 排最后，不写"不做" |

**注**：1、3、4、6、8 互不依赖，可并行分工；2 必须真机、且是 5 的前提。
