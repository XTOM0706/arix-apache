# Arix — 架构

> **定位：** 手表优先的语音 AI 助理（手机同样可用）
> **目标硬件：** 展讯 W527 / 虎贲 T310，4GB RAM，64GB 存储，Android 11+
> **最低要求：** Android 8.0（API 26）
> **更新：** 2026-07-15

---

## 核心声明：本地不跑 LLM

**本项目不包含任何本地 LLM 推理。** 所有对话请求经 `:cloudapi` 发往用户配置的云端 AI
服务（OpenAI 兼容接口）。本地只做语音处理（唤醒 / 识别 / 合成）、工具执行、数据存储。

原因是硬件：4GB RAM、Cortex-A55 ×4 @1.2GHz。塞得下量化小模型，但它的能力配不上
「助理」二字，还会把电吃光。需要离线对话请通过 MCP 接外部推理服务。

---

## 模块现状

**先说实话：这基本上是个单体。** 92% 的代码在 `:app` 里。

| Module | 类型 | 代码量 | 职责 |
|---|---|---|---|
| `:app` | Application | 207 文件 / 64.7k 行 | 主应用：Compose UI、82 个工具、记忆、角色卡、MCP、插件运行时、Arix 工作台 |
| `:wake` | Library | 20 文件 / 2.3k 行 | 语音唤醒（SileroVAD + MFCC + DTW + KWS） |
| `:cloudapi` | Library | 12 文件 / 1.4k 行 | 云端 AI 客户端（OpenAI 兼容 + SSE 流式） |
| `:stt` | Library | 6 文件 / 1.0k 行 | 语音识别 |
| `:data` | Library | 15 文件 / 0.9k 行 | 数据层（Room + DataStore） |
| `:marketwatch` | Library | 1 文件 / 0.2k 行 | 竞品监控 |

### 空模块（历史遗留）

`settings.gradle.kts` 里还挂着四个模块，**它们一个文件都没有**：

| Module | 原计划 | 实际去了哪 |
|---|---|---|
| `:tts` | 语音合成 | `:app` 的 `TtsTool` / `FloatingTtsPlayer` |
| `:tools` | 工具系统 | `:app` 的 `tool/` 包（60+ 文件） |
| `:mcp` | MCP 客户端 | `:app` 的 `McpTool` / `StdioMcpClient` / `McpServer` |
| `:persona` | 角色卡 | `:app` 的 `CharacterCardManager` / `persona/` |

这是项目早期「按功能切模块」计划的残留。实现最后都长进了 `:app`——因为这些功能
与 UI、权限系统、工具注册表耦合得比预想紧，硬切出去只会制造循环依赖。

**留着它们不影响构建**（空模块编译产出空 aar），但看目录结构会误以为项目是分层的。
要清理的话删掉 `settings.gradle.kts` 里那四行 `include` 与对应目录即可，
本文件在此记录以免下一个人白找。

---

## 依赖关系

```
:app ──┬── :wake       唤醒
       ├── :cloudapi   云端 AI
       ├── :stt ────── :cloudapi     （SpeechRoute 要用 cloudapi 的类型）
       └── :data       数据层
```

扁平图，只有 `:app` 依赖别人。`:cloudapi` 是出口，`:data` 是共享基础层。
`:marketwatch` 目前不被任何模块依赖（独立工具）。

---

## `:wake` — 语音唤醒

**原创实现（独立重写），AGPL-3.0-only。**

> ⚠️ 本文件的早期版本曾写「从 Operit LGPL 模块抽取」——**那个说法已作废**。
> wake 模块经过独立重写：参考公开论文与他人的实现**思路**，不搬运任何
> GPL/LGPL 源码。目的是让 Arix 成为独立项目。
> 早期确有 4 个 Operit 衍生文件，已整个删除、按另一套架构重写。
> 这里说「独立重写」而不说「clean-room」：后者是个术语，特指实现者从未接触过原始代码，
> 而本模块是同一作者重写的，够不上那个标准——如实说明不吃亏，反正 Operit 是
> LGPL-3.0-or-later，可升 GPL-3.0，与本项目 AGPL-3.0 本就兼容。
> 权威说明见 [wake/NOTICE](wake/NOTICE) 与 [NOTICE](NOTICE)，以那两份为准。

技术栈：ONNX Runtime（SileroVAD）+ MFCC + DTW 模板匹配 + TFLite KWS

| 文件 | 职责 |
|---|---|
| `SileroVad.kt` | VAD 端点检测 |
| `MfccFrontend.kt` | MFCC 特征提取 |
| `DtwMatcher.kt` | DTW 模板匹配 |
| `KwsDetector.kt` | 关键词检出（microWakeWord / TFLite） |
| `EmbeddingPrototypeDetector.kt` | 声纹原型匹配 |
| `WakeAudioPipeline.kt` | 音频管线编排 |
| `VoiceTurn.kt` | 带 VAD 断句的录音（语音通话用） |
| `AIService.kt` / `SpeechService.kt` | LLM 与 STT/TTS 的接口抽象 |

唤醒策略：**不常听麦克风**——抬腕门控 + 级联唤醒，逐级放行，省电。见 [DESIGN-WAKE.md](DESIGN-WAKE.md)。

---

## 硬件

下表是**参考机型（展讯 W527 / 虎贲 T310）的配置**，不是运行要求。
真正的门槛只有一条：**Android 8.0（API 26）以上**。

| 项 | 参考机型 | 对设计的影响 |
|---|---|---|
| RAM | 4GB | 本地 STT/TTS 模型（~100-200MB）可行，无本地 LLM |
| CPU | Cortex-A55 ×4 @1.2GHz | STT/TTS 推理需要优化 |
| GPU | Mali-GE8300 | 持续 3D 渲染不可行 |
| 电池 | ~400-600mAh | 每 mA 都敏感——唤醒策略的全部设计动机 |
| 存储 | 64GB | 用不了这么多，但也**不是完全无所谓**，见下 |

### 存储实际占多少（实测）

| | 大小 | 说明 |
|---|---|---|
| APK | ~230 MB | 其中 134MB 是打包进 assets 的工作台 bootstrap，25MB 是 STT 模型（zipformer int8），25MB 是 ONNX Runtime |
| 工作台展开 | ~379 MB | 首次启用时把 bootstrap 解压到 `filesDir/usr`，之后常驻 |
| **合计** | **~600 MB 起** | 再加对话/记忆/角色卡等用户数据 |

所以 64GB 用不完，但**几个 G 的低配设备要留意**。

不要工作台的话，构建时不放 `bootstrap-<abi>.zip` 即可——APK 掉到 ~96MB，
终端回退 busybox，其余功能不受影响。

---

## targetSdk 钉在 28

不是懒得升。Arix 工作台要在 App 自己的数据目录里 `execve` 二进制，这个权限只有旧
SELinux 域（`untrusted_app_28`）才有。

`compileSdk` 仍是 36，新 API 照常用；只是运行时行为按 28 走——对这类工具型 App，
28 的差异多半是「少一些限制」。详见 `app/build.gradle.kts` 里的注释。

---

## 开源合规

第三方组件一律：标注来源仓库、版本、协议；保持原始 LICENSE / NOTICE 完整。
清单见 [NOTICE](NOTICE)。许可与附加条款见 [LICENSE.md](LICENSE.md)。
