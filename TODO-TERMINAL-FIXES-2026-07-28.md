# 终端 2026-07-28 对抗审查台账 —— 待修清单（新窗口直接从这份开工）

---

## ⭐ 2026-07-28 晚 修复轮：进度总览（**先读这一节**）

**编译**：`:terminal:assembleDebug` EXIT=0（33.0 MB）；`:terminal:assembleRelease` EXIT=0（14.5 MB，R8 开，debug 密钥签名）。
**真机：用户实测「都没有问题」** —— 装机可用、功能正常，**R8 的 release 包也跑通了**
（这条顺带解掉了长期挂着的「R8 运行时未验证」风险项）。
⚠️ 但这是**整体使用层面的确认**，不等于逐条对照过下面那张表；
第五节那些「审查方未能证伪」的细节项（Braille 位序、SGR 去重、混合 run 偏移等）仍属**未逐条核对**。
分支是 `work/0727-p1-p2-p3`（与主 App 的 P1/P2/P3 同分支，**终端本身没有 P1P2P3 之分**），**全部未提交**。

### 本轮已修完（代码已落盘 + 编译通过）

| 台账条目 | 状态 |
|---|---|
| §2.2 双线拐角剩余三处 | ✅ 补完，手算验过 `╬` 中心方孔仍在、`╔` 内拐角已盖、`╠` 丁字口正确；细线族公式恒等、零变化 |
| §3 S-A cwd 零校验 | ✅ 生产侧校验（绝对路径/≤4096/无 <0x20 与 0x7F/无 `..` 段），不过即整条丢弃 |
| §3 S-B DCS termcap 回写 | ✅ 只回应「偶数长度且全 hex」的名字，其余**不回应**，整类回写注入被挡在源头 |
| §3 S-C rc 侧 OSC 7 转义注入 | ✅ 新增 POSIX `__xtom_urlenc`（只放行 unreserved，逐字节 %XX），bash/zsh/mksh 共用；fish 用 `string escape --style=url` 分段编码 |
| §3 S-D ascii 原样 cat | ✅ 宿主侧生成时就洗（`sanitizeArt`：只放行可打印字符 + 换行 + CSI SGR），另加行数上限与编码探测 |
| §3 S-E OSC 7 host 段 | ✅ authority 非空且非 localhost 整条拒收 |
| §4.1 四处漏补 clearShellMarks | ✅ SD / RI / DECALN / 带左右边距滚动 全部补完 |
| §4.2 标记优先级 | ✅ 加了提示符轮次（epoch）+ `SHELL_MARK_PROMPT_INPUT` 合并态；`C` 不再被陈年标记吞、`INPUT` 不再是死常量 |
| §4.3 DEBUG trap 无条件覆盖 | ✅ `trap -p DEBUG` 探测后链式调用，幂等；命令含转义单引号时让步保留别人的 |
| §4.4 F1 删/覆盖用户 fastfetch 配置 | ✅ 按首行标记区分「我们写的/用户写的」，覆盖前备份 `.xtom-bak`，卸载时只删我们的并还原备份 |
| §4.4 F2 只卸当前环境 | ✅ 改为遍历全部环境卸载（单个失败只跳过） |
| §4.4 F3 无 Mutex 并发写 | ✅ `FetchSetup.rcMutex` 串行化 `applyWith` 全程 |
| §4.4 F4 页面销毁即取消 | ✅ 改用进程级 `appScope` + `NonCancellable`（`applyDetached`） |
| §4.4 F5 大小上限写完才校验 | ✅ 边拷边计数、超限即止、清 tmp、原子 rename |
| §4.4 F6 死代码 | ✅ 死 import/常量已清；`clearAssets()` 接进卸载路径 |
| §4.5 R1 sprite 开关丢失 | ✅ 新增 `setSpriteEnabled`，重建 renderer 统一走 `applyRendererSettings()` 重放 |
| §4.5 R2+R3 矩形取整 | ✅ 背景/光标矩形按**绝对列号** `Math.round` 取整、移出缩放坐标系、绘制时关 AA |
| §4.5 R4 下划线位置 | ✅ 从 Paint 反推基线，套 Android 文字装饰默认量（下划线顶边 24 vs 旧 27，正是那 3px 台阶） |
| §6 标记块 end 损坏吞内容 | ✅ `TermBeautify` 与 `FetchSetup` 两处同源缺陷一起修：缺 end 标记时只摘孤儿起始标记行，其余一字不动 |
| §6 sessionSignature 不含 fetch/prompt | ✅ 已纳入，「回终端自动重开会话生效」这句文案现在是真的 |
| §6 `:2191` 注释与实现不符 | ✅ 已改对 |
| 体验项：未选图报「已应用」 | ✅ 改为明确失败原因 |
| 体验项：拷贝失败抹掉原有状态位 | ✅ 失败不动状态位 |

### 本轮**没做**的（连同理由，别当成漏了）

- **§4.3 子 shell 不发 `C`**（`( echo sub )` 发 0 个）：**决定不开 `set -o functrace`**。
  functrace 会让 DEBUG 进子 shell，而 PS1 里的 `$(xtom_git_branch)` 每画一次提示符就开一个子 shell，
  那时 armed 恰好是 1 → 用户还没敲命令就先发一个**假的 C**。等于拿「罕见地少一个 C」换
  「每个提示符多一个错的 C」，后者直接毁掉「复制整段输出」的起点判定。
  想靠 `$BASH_SUBSHELL` 区分也不成立（子 shell 命令和 PS1 命令替换都是 `>0`）。
  理由已写进 `TermBeautify.bashIntegration` 的 KDoc。真要修得换条路。
- **dash/ash 不再发 OSC 7**：它们的 PS1 只做参数展开、不做命令替换，塞 `$( )` 会原样显示，
  而直接塞 `$PWD` 就没法 percent 编码。两害相权选了不发（mksh 走 `$(__xtom_osc7)`，不受影响）。
- **§六的其余存疑项**（首帧同步 I/O、Braille 每帧开销、极小字号点越界、E0B0 clip、
  Q1 mksh 提示符宽度、Q2/Q3/Q4/Q5）：**一条都没动**，全部需要真机数据才能判断。

### ⚠️ 接手第一件事

**真机验证**，重点：
1. **§五全部条目**（本轮仍未验证，不得默认没问题）；
2. **Q1 mksh 提示符宽度** —— 新的 OSC 7 走 `$(__xtom_osc7)` 仍是变长的，
   在 `/system/bin/sh` 里 `cd` 到深路径按 ←/退格试一分钟；
3. **R2+R3 改的是 vendor 既有行为**：带背景色的表格/`ls --color`/htop 色块看有没有新的亮缝，
   块状光标移动时看有没有「胖瘦跳动」（宽度现在是整数像素）；
4. **玻璃/字体无关，但 sprite 构造函数签名变了**，确认框线字下划线不再成台阶。

---

> **接手须知**：这份是「四路对抗审查的全部发现 + 已修/待修状态」。
> 相关文档：调研 `RESEARCH-TERMINAL-2026-07-28.md`、实现台账 `TODO-TERMINAL-APP.md`
> 的「2026-07-28 第一批实现」与「2026-07-28 待办：多容器」两节。**不要重新摸现状、不要重新审计。**
>
> ⚠️ **本文件里没有任何一条是「已澄清、可以关掉」的。**
> 审查方声称「打不动 / 声明属实」的条目一律记在第五节，状态是
> **「本轮未被证伪，但未经真机验证，仍需复核」** —— 不是结论。

---

## 零、当前状态快照

- **分支**：`work/0727-p1-p2-p3`（不是 master）。**全部未提交。**
- **编译**：`:terminal:assembleDebug` 通过（EXIT=0），`terminal-debug.apk` 32.44 MB，
  APK 时间戳已核对是新打的包。⚠️ **本文件第二节的两处修复之后还没重编，接手第一件事是重编一遍。**
- **真机验证：一条都没有。**
- 编译命令（⚠️ 别用 `gradlew | tail`，退出码会变成 tail 的、永远是 0）：
  ```sh
  cd /e/OnyxAI && ./gradlew :terminal:assembleDebug > /tmp/b.log 2>&1; echo "EXIT=$?"; tail -n 5 /tmp/b.log
  ```
  改完 assets 必须 `:terminal:clean`（增量打包幽灵字节，老坑）。

### 本批涉及的文件（11 个，3 个新建）

| 文件 | 状态 |
|---|---|
| `terminal/src/main/kotlin/com/arix/terminal/TextArt.kt` | 新建，473 行 |
| `terminal/src/main/kotlin/com/arix/terminal/FetchSetup.kt` | 新建，~480 行 |
| `terminal-view/src/main/java/com/termux/view/TerminalSprites.java` | 新建（**Arix 原创，非上游**） |
| `terminal/src/main/kotlin/com/arix/terminal/TermSettingsActivity.kt` | 改：加「启动信息」分区 |
| `terminal/src/main/kotlin/com/arix/terminal/TermBeautify.kt` | 改：OSC 133 rc 注入；`homeOf` 提 internal |
| `terminal/src/main/kotlin/com/arix/terminal/DistroProvision.kt` | 改：`writeExec` 提 internal |
| `terminal-view/src/main/java/com/termux/view/TerminalRenderer.java` | 改：sprite run 拆分与分派 |
| `terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java` | 改：OSC 7 / OSC 133 |
| `terminal-emulator/src/main/java/com/termux/terminal/TerminalBuffer.java` | 改：标记读写与搬运 |
| `terminal-emulator/src/main/java/com/termux/terminal/TerminalRow.java` | 改：`mShellMark` |
| `NOTICE` | 改：已按 Apache-2.0 §4(b) 声明本批全部 vendor 改动 |

⚠️ **`NOTICE` 还需补一条**：第二节已修的双线拐角属于 `TerminalSprites`（已在声明覆盖范围内，不用补）；
但若接手按第三节改了 `TerminalRenderer` 的背景矩形取整、或改了 `TerminalEmulator` 的 DCS 回写，
**都要再补进 §4(b) 声明**。

---

## 一、审查方法与一条必须记住的教训

四路并行对抗审查，每路拿到**实现者的自我声明清单**，任务是**证伪**而非复核，
并明确要求「属实就说属实，不要为凑数编造」。

### ⚠️ 教训：两路审查独立犯了同一个假阳性

sprite 路和 OSC 路都断言「全仓 grep 找不到测试文件 → 实现者宣称的测试不存在 / 声明是假的」。
**这是错的。** 实现者当时明说测试写在 scratchpad、按要求未落进项目。实测 scratchpad 里确实有：
`sim/` `stub/` `out/` `out2/` `grid_big.png` `grid_small.png` `grid_small2.png`（sprite 的 AWT 影子实现与渲染网格）、
`oscchk/` `shtest/`（OSC 的模拟器与 shell 测试）、`javac.log`。

**根因是交接信息缺失**（没告诉审查方实现者在哪儿跑的测试），不是审查方能力问题。
**下次派审查一定要把实现者的验证方式与产物位置一并交底。**

> 但由此引出的**真问题仍然成立**：sprite 的测试**报警过** `╬`，实现者把它解释成
> 「字形本来就有的中心洞」而放过 —— 中心洞确实是字形该有的，**但拐角那 4 个 t×t 缺口不是**，
> 被一起当误报解释掉了（见 §2.2）。**测试报警不要急着解释成误报。**

---

## 二、已修（2 处，未重编译验证）

### 2.1 ✅ `cellAspect` 语义传反 —— 字符画纵向拉长约 4 倍
**两路审查独立复现，我本人也核过原文，坐实。**

- `TextArt.kt:65` 契约：`cellAspect = 字符格的高/宽比 = fontLineSpacing / fontWidth`（≈2.0）；
  内部 `TextArt.kt:118` 的 `rows = cols*srcH/(srcW*aspect)` 与 `:114` 的兜底 `2f` 都自洽。
- `FetchSetup.kt:179` 初版返回 `w / h`（≈0.5），KDoc 自己也写「宽/高」，且 `coerceIn(0.2f, 1.5f)`
  —— **值域永远够不到 2.0**；0.5 是「合法但语义反了」的值，能躲过 TextArt 内部 `> 0.05f` 的哨兵，
  那个正确的 `2f` 兜底永远不触发。
- 实测数（font_sp=15, density=2 → measureText("X")≈18, ceil(fontSpacing)≈35）：
  传 0.514 → 20 列方图算出 **39 行**（3.79:1 竖条）；正确值 1.944 → **10 行**（≈1:1）。
  手表 454×454 该字号约 25 列 × 13 行，**开场画面自己就要滚 3 屏**。
- **已改**：`FetchSetup.kt` 的 `cellAspect()` 改为 `(h / w).coerceIn(1.2f, 3.0f)`，
  两处兜底 `0.5f` → `2f`，KDoc 改成「高/宽」并写明踩坑原因。
- **根因（我的责任）**：给 TextArt 那路的 prompt 写死了方向，给 FetchSetup 那路只写「传真实字体度量」
  没写方向。**并行分工时，跨文件契约必须双方都拿到同一份完整定义。**

### 2.2 ✅ 双线族拐角系统性缺 `mThin × mThin` 缺口
**我手算复现，坐实。**

`TerminalSprites.java` 的 `drawBox`：双线的两股线内端点原本收在**整条带子的近/远边**（`vx0`/`vx1`/`hy0`/`hy1`），
应该收在**与之相交的那条细线自身的远边**（`vx0+mThin` / `vx1-mThin` / `hy0+mThin` / `hy1-mThin`）。

手算 `╬`（w=8, h=16, mThin=1）：横线上股 `x[0,2) y[6,7)`，竖线左股 `x[2,3) y[0,6)`
→ **像素 (2,6) 两股都没盖到**，只在一个点上斜着相碰。
`╔` 的内拐角同理落在 (4,8)（= `[vx0+2t,vx0+3t] × [hy0+2t,hy0+3t]`）。
影响 `╬╔╗╚╝╠╣╦╩` **全族**的拐角与丁字口；mThin=2 时是 2×2 px，**肉眼可见「角被咬掉一口」**。

- **已改**：`left == D` 分支的两处 `vx0` → `vx0 + mThin`，并补了详细注释。
- ⚠️ **只改了 `left == D` 一处，另外三处还没改！** 接手必须补完：
  - `right == D`：`(up != N) ? vx1 : vx0` → `vx1 - mThin`；`(down != N) ? vx1 : vx0` → `vx1 - mThin`
  - `up == D`：`(left != N) ? hy0 : hy1` → `hy0 + mThin`；`(right != N) ? hy0 : hy1` → `hy0 + mThin`
  - `down == D`：`(left != N) ? hy1 : hy0` → `hy1 - mThin`；`(right != N) ? hy1 : hy0` → `hy1 - mThin`
  - **注意**：只改「垂直臂存在」的那个分支（三元的真值侧）；
    「不存在」的分支用带子远边是为了封外角，**是对的，别动**。
  - 改完自己手算验一遍中心方孔还在（`╬` 的中心洞应为 `x[3,4) y[7,8)`，不能被填上）。

---

## 三、待修 —— 安全（本轮必须做完，别留给下一轮）

### 🔴 S-A `getShellCwd()` 零校验，任何终端里的程序都能投毒 cwd
`TerminalEmulator.java:2194-2210`（`setShellCwdFromOsc7`）、`:2310`（`getShellCwd`）

解码后的路径**直接赋给 `mShellCwd`**，无绝对路径检查、无长度上限、无 `..` 规范化、无容器根检查、
无控制字符过滤。`percentDecode` 能解出 `\0`、`\n`、引号、任意 <0x20 字节。

攻击（`cat` 一个恶意文件就够，无需任何权限）：
```
printf '\033]7;file:///%2e%2e/%2e%2e/system/bin\a'
printf '\033]7;file:///tmp%27%3b%20rm%20-rf%20~%3b%20%23\a'   → cwd = /tmp'; rm -rf ~; #
printf '\033]7;file:///data/x%00/../../../\a'                 → 含 NUL，过 JNI/native 会截断
```
下一轮「新会话继承 cwd」若拼进 `cd '<cwd>'` 就是**命令注入**；当 `File(path)` 用就是**路径穿越**。

**缓解事实**：全仓 grep `getShellCwd|getShellMark|getLastCommandExitCode` **零消费方**，现在还不可利用。
但 API 已 public，交给下一轮就是漏洞。

**修法**：校验必须加在**生产侧** `setShellCwdFromOsc7`，不能指望消费方自觉。
至少：必须以 `/` 开头、长度上限（如 4096）、拒绝任何 <0x20 与 0x7F 字节、拒绝含 `..` 段、
规范化后校验落在当前环境 rootfs 内（Termux/发行版各自的根）。校验不过就**整条丢弃**，不要「尽力修正」。

### 🔴 S-B vendor DCS termcap 查询原样回写 pty = 命令注入
`TerminalEmulator.java:1014`：`mSession.write("\033P0+r" + part + "\033\\");`

**我本人已核实**：`part` 是 DCS 载荷按 `;` 切出的原始子串，唯一要求是**长度为偶数**（`:976`）；
非 hex 字符在 `:982` 只是 `continue` 跳过解码，**`part` 本身未经过滤就写回 pty = shell 的 stdin**。
DCS 收集器对字节零过滤（`:1034-1039` `appendCodePoint(b)`，换行 0x0A 照收）。
→ `ESC P + q <含换行的偶数长度文本> ESC \` 可让换行后那段**当命令执行**。

⚠️ **责任边界**：这是**上游 Termux 自带的洞，不是本批引入的**，且**任何显示到终端的内容都能触发**
（`cat` 文件、`curl` URL、看日志）。但本批的 ascii 模式把它变成了**开机自动触发**的稳定载荷。

**修法**：vendor 侧别原样回写 —— 要么对未识别的 termcap 名**不回应**，
要么只回写经 hex 校验通过的内容。这是根治，能挡掉一整类。**改完要补 §4(b) 声明。**

### 🔴 S-C rc 侧 OSC 7 只 escape 了 `%`，目录名可做转义注入
`TermBeautify.kt:180`(zsh)、`:198`(bash)、`:295`(mksh，**连 `%` 都没编码**)、`:391`(fish)

`${PWD//%/%25}` 只处理 `%`。目录名除 `/` 和 NUL 外可含任意字节：
```sh
mkdir $'evil\a\033]0;pwned\a' && cd evil*
```
→ OSC 7 在 BEL 处提前终止，后半段字节被终端当**独立转义序列执行**。
mksh 分支另有纯功能 bug：路径里的 `%41` 会被终端侧 `percentDecode` 解成 `A`，**cwd 直接错**。

**修法**：只放行 unreserved 字符（`A-Za-z0-9-._~/`），其余一律 `%XX`。四种 shell 各自实现。

### 🔴 S-D ascii 模式把任意文件原样 `cat` 进终端，零过滤
`FetchSetup.kt:206-208`（`readText()` 整份读入）、`:369`/`:358`（`cat '$logoGuest'`）、
`TermSettingsActivity.kt:311`（SAF 用 `*/*`，任意文件可选）。

三层后果（审查方已在 vendor 里逐条核过）：
1. **OSC 52 写剪贴板**（`TerminalEmulator.java:2132-2136` `onCopyTextToClipboard`）——
   每次进终端静默改写系统剪贴板。
2. **回显式命令注入** —— 即 S-B 那条链，被这个功能变成开机自动触发。
3. 改标题 / `ESC[?1049h` / 改 scroll region / 禁自动换行 —— 整个会话废掉。

**已澄清不成立的**（审查方主动设限，我认可）：OSC 52 **读**(`?`)不支持、
窗口标题上报 `:1837/:1840` 已被 vendor 硬编码禁掉 → **剪贴板外泄这条不成立**，别夸大。

**修法**：`cat` 改成过滤控制字节，或只放行 CSI SGR + 可打印字符。
建议在**宿主侧生成时**就过滤（写进 logo.txt 之前），而不是靠 guest 侧的 `cat` 管道。

### 🟠 S-E OSC 7 的 host 段被无条件丢弃
`TerminalEmulator.java:2196-2199`：`file://attacker.example.com/etc/shadow` →
`indexOf('/', 7)` 直接取到 `/etc/shadow`，当本机路径用。
**修法**：host 非空且 ≠ `localhost` / 本机 hostname 时**整条拒收**。

---

## 四、待修 —— 功能

### 4.1 OSC 133 标记失效点漏了四处
| 位置 | 问题 |
|---|---|
| `TerminalEmulator.java:1692-1693` | **SD**（`CSI T` 滚动下移）：`blockCopy` 搬走了标记，随后 `blockClear` 只清内容、**没 `clearShellMarks(mTopMargin, linesToScroll)`** → 顶部空行留旧 PROMPT 标记 |
| `TerminalEmulator.java:1470-1478` | **RI**（`ESC M` 反向索引）：同一模式漏补清。**RI 在 `less`/`man`/不用 altscreen 的分页器里很常见，比 SD 更容易碰到** |
| `TerminalEmulator.java:1407` | **DECALN**（`ESC # 8`）整屏填 `E`，标记全留（低频，vttest） |
| `TerminalEmulator.java:2379-2383` | 带**左右边距**时滚动走 `blockCopy(mLeftMargin, …)`，`sx != 0` → `moveShellMarks=false` → 内容上移而标记不动，**全屏错位一行**（DECLRMM 罕见但一触发是系统性的） |

IL(`L`)/DL(`M`) 已补，这四处漏了。前两处各两行代码。

### 4.2 标记优先级规则有两个后果
- **`SHELL_MARK_INPUT` 永远写不进去**（死常量）：A 和 B 在**所有** shell、**所有**五种风格下都落在同一行
  （bash/zsh/mksh 都在 PS1 内，fish 在 `fish_prompt` 内；双行风格是 AK 与 B 同行），
  `markCursorRow`（`:2301-2306`）的「OUTPUT/INPUT 不覆盖 PROMPT」一律挡掉 B。
  → 下一轮 UI **无法区分「提示符行」和「输入行」**。
- **`C` 会被静默吞掉**：`blockSet`/`setChar` 故意不清标记（为了不让 `\033[2K` 误杀提示符标记），
  于是**任何被「就地覆写、不 erase」的旧提示符行，PROMPT 标记永久残留**
  （`\e[H` 全屏重绘、`watch`、`dialog` 这类不进 altscreen 的程序）。
  下一条命令的 `C` 落到这样一行时被 `return` 丢掉 → **那段输出没有起点，「复制整段输出」直接废掉**。

**修法方向**：优先级规则加「**本轮提示符**」作用域（比如记一个 epoch/序号，只有本轮的 PROMPT 才免于被覆盖）。

### 4.3 rc 侧两条
- **`trap … DEBUG` 无条件覆盖既有 DEBUG trap**（`TermBeautify.kt:212`，**实测**：预先
  `trap '__other_debug' DEBUG` 再 source，`trap -p DEBUG` 只剩我们的）。
  → 与 **bash-preexec / Atuin / 各家 shell integration 直接互踩**，谁后加载谁赢。
  **修法**：`trap -p DEBUG` 探测后**链式调用**。
- **`( … )` 子 shell 命令完全不发 `C`**（实测表：`true`/`{ }`/`if`/`for`/`while`/`$( )`/`cmd &`/管道
  都发 1 个 C ✓，唯独 `( echo sub )` 发 **0** 个 ✗）。原因是没开 functrace，DEBUG 不进子 shell。
  → 子 shell 命令的输出段起点丢失。

### 4.4 FetchSetup 六条
| 编号 | 位置 | 问题 |
|---|---|---|
| F1 | `FetchSetup.kt:336` | `uninstall()` **无条件删** `~/.config/fastfetch/config.jsonc` → **用户自己手写的 fastfetch 配置被静默删除，无提示无备份**。且 `:300-303` 是 `writeText()` **整份覆盖不合并**，点任一模式就把用户配置替换成我们那 4 行 |
| F2 | `FetchSetup.kt:324-334` | `uninstall` **只对当前 activeEnv 动手** → 在系统终端开启后切到 Termux 再点「关闭」，**系统终端的 `.mkshrc` 标记块原封不动**，用户以为关了、切回去照样弹。且 UI 一句提示都没有（美化那节 `:255` 至少写了「换环境要再点一次」） |
| F3 | `TermSettingsActivity.kt:123-129` | 每次 `scope.launch{}` 起新协程，`applyWith` 里**无 Mutex**。① 快速切模式 → 两次 `logoHost.writeText` 竞争，**谁后完成谁赢不确定**；② `.mkshrc` 是 read-modify-write（`:404-414`），与「提示符美化」（`:234`）并发时 **lost update，整个标记块凭空消失** |
| F4 | `TermSettingsActivity.kt:275` | `setMode` 同步落 prefs，`applyFetch` 挂 `rememberCoroutineScope()`（`:95`）**页面销毁即取消** → 点「关闭」立刻返回：prefs 记成 off 但 `uninstall()` 没跑，**永久不一致**（全仓只有 TermSettingsActivity 引用 FetchSetup，没有任何地方在启动时补做 apply） |
| F5 | `FetchSetup.kt:141-142` | 大小上限是**写完才校验**（先 `copyTo` 整份落盘再判 length）。误选 4GB 文件 → 先写爆存储；`:145-147` 的 catch **不删 tmp 残骸**。另 `:143` 先删后 rename 不原子，rename 失败时新旧都没了 |
| F6 | 多处 | 死代码：`:9` `import android.system.Os` 无引用、`:46` `MODE_0755` 无引用、`:129-132` `clearAssets()` **全仓无调用者**（这正是素材 `logo-src.img/.txt/.tmp` 删不掉的原因） |

其它：`:209` 图片模式未选图时静默降级成内置 logo 却报「已应用」；
`TermSettingsActivity.kt:157` 新图拷贝失败会把**原本已有的图**的状态位抹成 false；
`:207` `readText()` 固定 UTF-8（GBK 的 ascii art 满屏 U+FFFD）；256KB 上限**没有行数上限**。

### 4.5 sprite / renderer 四条
| 编号 | 位置 | 问题 |
|---|---|---|
| R1 | `TerminalView.java:545-556` | `mSpriteEnabled` 在 renderer 重建时**静默丢弃**（`setTextSize`/`setTypeface` 各重建一次，只补了 `mHideCursor`）。→ 按文档「出问题先关 sprite 对比」去排障，用户捏合改一次字号（`TermActivity.kt:764`）就静默切回，**排障结论作废** |
| R2 | `TerminalRenderer.java:151` + `:243-247` | run 拆分把背景矩形切碎；背景用 **AA 打开**的 `mTextPaint` 且坐标是未取整的 `startColumn * mFontWidth`（小数）→ 相邻矩形共用小数边界时该像素仍有 ~22.5% 旧背景透出 = **1px 偏暗竖线**。带背景色的表格每行每个框线字两侧各一条 |
| R3 | `TerminalRenderer.java:230-231` vs `TerminalSprites.java:262,279` | sprite 走 `Math.round(col*fontWidth)` 整数格边界，背景/光标矩形走未取整 float → **最多半像素错位**。反色/选中下的 `█`/`▐`/`▀` 边缘多一条细边或缝 |
| R4 | `TerminalSprites.java:286-294` | 下划线画在 `cellBottom - t`（行盒最底），而 `Paint.setUnderlineText` 画在基线下方（≈行高 0.85）；删除线同理。→ `\e[4m│ abc │\e[0m` 里框线字下方的横线比文字的低 1–2px，**成台阶** |

**R2+R3 建议一并修**：把背景/光标矩形的 x 坐标也改成 `Math.round(绝对列号 * mFontWidth)`
（**按绝对列号算、不要起点+累加**）—— 相邻矩形边界必然落同一整数，AA 接缝和半像素错位一起消失。
⚠️ 这是改 vendor 的既有行为（非 sprite 路径也受影响），**要补 §4(b) 声明**，且要真机确认没引入新问题。

另：`TerminalSprites.java:278` `if (cellColumns <= 0) continue;` 会把跟在 sprite 码点后的
**组合字符（U+0300 / VS16）静默丢弃**（上游是交给字体做基字+组合的）。极罕见，但是**无声**丢弃、不是回退字体。

---

## 五、审查方未能证伪的条目 —— **仍需复核，不是结论**

> 用户明确要求：**「不存在打不动」**。以下条目仅代表「本轮对抗审查没打下来」，
> **全部未经真机验证**，接手**不得**据此认为它们已经没问题。真机验证时要重点回看这些。

### 5.1 TextArt
- Braille 位序（`TextArt.kt:54-59`）：审查方手工展开 8 条映射与 Unicode 标准点序逐条对上。**待真机看实际出图。**
- SGR 去重状态机（`TextArt.kt:364-416` `LineWriter`）：审查方逐格走过换行复位、行尾裁剪回滚、
  只设前景不设背景、透明格拖影五个考点。**待真机看实际彩色输出。**
- 可分离高斯就地写回（`TextArt.kt:447-471`）：横向遍只读 src 写 tmp、竖向遍只读 tmp 写 dst，
  竖向遍未再碰 src，故 `dst===src` 安全。**这是我埋的重点怀疑项，没打动。**
- 边界护栏：不会越界读 `px[]`；`cellAspect` 非法值回落；`rows` 有 `coerceAtLeast(1)`；
  Bitmap 回收有 `!== src` 守卫。

### 5.2 sprite
- `fontWidthMismatch` 强制 false **无副作用** —— 它根本没传进 `drawTextRun`，
  上游唯一作用是拆 run；横向缩放由 `mes` 与 `runWidthColumns` 比较驱动。**我埋的重点怀疑项，没打动。**
- 混合 run **不累积偏移** —— 两条路径列号都用 `column += codePointWcWidth`，
  `left` 恒为 `startColumn * mFontWidth` 绝对值、不累加。
- run 长度为 0 不存在；两处 `WcWidth.width()` 一致；`0x2500–0x28FF`/`0xE0B0–0xE0B7` 全部宽度 1。
- `save/restore` 配对：唯一提前 `return` 在 `save()` 之前。**我埋的怀疑项，没打动。**
- 判定集合 == 可画集合（BOX 128 项全显式初始化无空洞、块元素 32 项全覆盖、powerline 8 个全有 case）。
- Paint 无泄漏；`mSpriteEnabled=false` 时 `&&` 短路、逐字节等价上游。
- 纵向对齐严格（`heightOffset` 全程整数，`cellTop(r+1) == cellBottom(r)`）。
- BOX 表 128 项、QUAD 10 项、1/8 阶梯块四组起止方向，审查方逐条核过 Unicode 名称。

### 5.3 OSC 133 / 7
- **环形缓冲标记跟随**（最关键）：标记存 `TerminalRow`，`scrollDownOneLine` 搬对象引用；
  所有行复用点（`:404-408`/`:223`/`:456`/`:531-534`）都走 `clear()` 或新建，无绕过路径。**我埋的重点怀疑项，没打动。**
- `blockCopy` 反向搬运方向逻辑与内容一致（`copyingUp` 决定的迭代顺序是上游原有的重叠安全顺序）。
- `resize` 只变行数时标记跟得上（快路径只挪 `mScreenFirstRow`）。
- `percentDecode` 实现正确（边界、非法 `%XX` 原样保留、不把 `+` 当空格、容量安全）。
- 既有 OSC 0/1/2/4/10/11/12/52/104/110-119 一字未改，`default` 兜底完好。
- OSC 133 参数解析不会 OOM（8192 硬顶）。
- **8192 截断不产生「看起来合法的错误路径」** —— `collectOSCArgs`（`:2455-2461`）到顶走
  `unknownSequence → finishSequence()`，**整条 OSC 丢弃、根本不派发**。
  ⚠️ **但 `:2191` 的注释与实现不符（写成「截断后交给我们」），要改注释。**
- bash `PROMPT_COMMAND` 前插**幂等**（实测：标量三次 source 不累积；数组形式其余元素不丢）。
- PS1 里的 `$(命令替换)` 不消耗 armed 标志；管道只发 1 个 C（实测）。
- fish `set -l st $status` 在第一句；`--on-event` 事件名正确；`autoload -Uz add-zsh-hook` 有。
- 转义包裹：bash `\[..\]` 成对完整、zsh `%{..%}` 且 `PROMPT_SUBST` 已开、fish 不需包裹。
- 五种风格 × 四种 shell 覆盖完整，双行 `k=s` bash/zsh/fish 都补了（mksh 的 flow 退化成单行，不需要）。

### 5.4 FetchSetup
- **Termux 的 profile.d 钩子真的生效** —— 审查方解开 `bootstrap-aarch64.tar.xz` 看了 `etc/profile`，
  头 5 行就是 `for i in .../etc/profile.d/*.sh; do . $i; done`；`TerminalEnv.kt:515` 起的是 `shell --login`；
  `01-xtom-fetch.sh` 字典序排在 `01-termux-bootstrap-second-stage-fallback.sh` 之前，无冲突。
- `.mkshrc` 写对了地方（`TerminalEnv.kt:443` 的 `ENV` 与 `TermBeautify.homeOf(SYSTEM)` 是同一个文件）。
- guest 路径拼接正确（distro `/root/.config/fastfetch/logo.txt`、Termux 用 `GUEST_HOME`）。
- **路径注入不成立** —— 我点名怀疑的「自定义镜像 `home` 字段是用户填的」被证伪：
  `EnvModel.kt:167-180` 的自定义镜像解析**根本不读 `home` 字段**，恒为默认 `/root`；
  `addCustomDistro`（`:188`）也不收 home 参数。进脚本的路径 100% 是 App 常量，单引号包裹足够。
- **交互门有效** —— `TerminalEnv.kt:516` 非交互走 `--login -c`，`$-` 无 `i`，AI 的 `linux_exec` 挡得住。
- 两块标记互不干扰（`fetch` vs `prompt` 标记名独立，`unhook` 只摘自己那块，反复 apply 不累积）。
  ⚠️ **但并发下会互相吃（F3）、end 标记损坏时会互相吃（见下）。**
- `file-raw` / `"type":"builtin"` 写法正确；`trimIndent()` 后首行 `//` 注释 fastfetch 的 jsonc 解析器认。
- 两遍采样解码属实（`inJustDecodeBounds` → `inSampleSize` → 再解，`DECODE_MAX_EDGE=1024` 封顶约 4MB）。
- Slider 只在 `onValueChangeFinished` 落地。
- 字号 key 同源（`term_ui`/`font_sp`/默认 15/区间 6..32，与 `TermActivity.kt:50-55` 一致）。
- `renameTo` 跨文件系统不成立（tmp 建在 `dest.parentFile`，同目录同 FS）。
- `VERSION_CODENAME` 读不到不会炸（`?: ""` + `else -> null`）。
- 新分区没破坏原有状态（6 个 `remember` 变量名独立，SettingsKit 签名对得上）。

---

## 六、另外记下的（跨文件同源缺陷 / 存疑项）

- **标记块 end 标记损坏会吞掉后面所有内容**：`FetchSetup.kt:407-409` 的
  `old.substringAfter(MARKER_END, "")` 缺省值是空串 —— 若 end 标记被用户删了或上次写到一半崩了，
  走替换分支时**「标记之后的全部内容」被静默丢弃**（包括排在后面的 `# >>> Arix prompt >>>` 整块）。
  ⚠️ **`TermBeautify.kt:109-112` 有完全相同的缺陷，属同源，一起修。**
- **「回终端会自动重开会话生效」这句文案是假的**：`TerminalEnv.kt:454-465` 的 `sessionSignature`
  只含 `activeEnv/backend/loginShell/root`，**不含 fetch，也不含 prompt style** → `TermActivity.kt:300`
  比对签名没变、不重开，用户回终端什么都看不到。
  （**这条和已有的「提示符美化」共用同一句错文案，不是本批新引入，但本批照抄了。**）
- **`writeExec` 静默吞异常**：`DistroProvision.kt:174-182` 只 `Log.w`，
  `FetchSetup.kt:312/314` 拿不到失败信号 → 脚本没写成也返回「已应用」。
  distro 分支被 `homeOf` 的 rootfs 存在性挡了一道，**Termux 分支没挡**。
- **首帧同步 I/O（未实测）**：`TermSettingsActivity.kt:120-121` 的 `remember(activeEnv){}` 在**主线程**
  读 `<rootfs>/etc/os-release` 并做一次 `EnvRegistry.all()` 的 JSON 解析。
  按本仓 `DESIGN-CHAT-PERF.md` 的既定标准这是首帧同步 I/O。
- **Braille 字符画每帧开销（未实测）**：`TerminalSprites.java:511` 每格最多 8 次**开 AA 的 `drawCircle`**，
  `chafa --symbols braille` 满屏 40×20 → 单帧最多 6400 次抗锯齿画圆，滚动逐帧重画。
  「零分配」属实，但零分配 ≠ 便宜。**需要真机帧率数据。**
- **极小字号下 Braille 点越界（未实测）**：`radius = max(1f, min(cellW,cellH)*0.45f)`，
  公式部分永不越界，**只有 `max(1f,…)` 下限生效时才越界**，触发条件 `min(cellW,cellH) < 2.22px`
  即 `fontWidth < 4.5px`。`MIN_FONT=6sp` × density 1.0 会踩到；density ≥2 不会。
  **需确认目标设备是否存在 density 1.0 的路径。**
- **`E0B0/E0B2` 实心三角其实不需要 clip**（顶点本来就在 cell 内），每个三角白花一次 save/restore；
  反过来 `E0B1/E0B3` 细分隔线的基边描边**被 clip 削掉一半**，是 clip 的必然代价、未必是想要的观感。
- **⭐ Q1 mksh 到底算不算提示符宽度（最该先上机的一条）**：审查方在 Windows 上无 mksh 无法证伪。
  即便旧的定长颜色码没暴露问题，**新加的 OSC 7 是变长的**（`file://` + 完整 `$PWD`，随 `cd` 变化）。
  若 mksh 的 emacs 行编辑器确实计宽，症状会从「固定小偏移」升级成
  **「随目录深度变化的严重错位 + 退格删进提示符」**。
  真机 `/system/bin/sh` 里 `cd` 到深路径按 ←/退格试一分钟就知道。
- **Q4 bash/mksh 下 A 会落在上一条命令的输出行上**：命令输出不以换行结尾时（`printf hi`），
  bash 提示符起在同一行，这行被标成 PROMPT（zsh 会先补 `%`+换行所以没事）。
  行粒度标记的固有限制，下一轮「整段复制」会切错。
- **Q2 fish 的 `$PWD` 含换行**：`(string replace -a -- '%' '%25' $PWD)` 是命令替换，fish 按换行切分 →
  `$PWD` 含换行时变成多个参数，fish 的 `printf` 会**复用格式串**再发一条 OSC 7。
- **Q3 `blockCopy` 判据会误伤 DECCRA**（`:715`）：全宽整行的矩形复制也满足判据，标记被当整行搬。
  DECCRA 极少用，实际影响未评估。
- **Q5 非交互路径**：`TerminalEnv.kt:608` 用 `shell -l`，`bash -lc` 也会走
  `.bash_profile → .bashrc → .xtomrc.sh`，DEBUG trap 装进每个 AI `linux_exec` 的 shell
  （PROMPT_COMMAND 非交互不跑，所以不发 OSC，只是每条命令多一次 trap）。
  **开场 greeting 本来就没有交互门**（`TermBeautify.kt:303`，pre-existing，TODO 第 5 条已记）。

---

## 七、建议的修复顺序

1. **§2.2 把双线拐角剩下的三处补完**（已改 1/4，别忘了）→ 重编译。
2. **§3 四条安全全做完**（S-A 生产侧校验 / S-B vendor 不回写 / S-C 完整 percent 编码 / S-D 过滤控制字节）
   + S-E host 校验。
3. **§4.1 四处漏补的 `clearShellMarks`**（SD/RI 各两行）。
4. **§4.4 F1/F2/F3/F4**（别删用户 config、跨环境卸载、加 Mutex、换 lifecycleScope 或 NonCancellable）。
5. **§4.2 标记优先级加「本轮」作用域**（否则 `C` 被吞、`INPUT` 是死常量，下一轮 UI 做不了）。
6. **§4.5 R1/R2+R3/R4**（R2+R3 一并按绝对列号取整）。
7. **§4.3 DEBUG trap 链式 + 子 shell functrace**。
8. **§6 的同源缺陷**（标记块 end 损坏、sessionSignature 不含 fetch/prompt 的错文案、`:2191` 注释）。
9. §4.4 F5/F6 与其余体验项。
10. **真机验证**，重点回看 **§五全部条目**（不得默认它们没问题）+ §6 的 Q1 mksh 宽度。

改完记得：重编译（看 EXIT 和 APK 时间戳）、按 §4(b) 补 NOTICE、把结论回写 `TODO-TERMINAL-APP.md`。
