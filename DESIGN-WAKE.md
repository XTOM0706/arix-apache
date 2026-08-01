# DESIGN-WAKE.md — 语音唤醒 clean-room 重写设计

> 目标：既**脱掉 Operit 的 LGPL 代码**（走 Apache-2.0 宽松许可），又**真正好用**——
> 低性能手表可用 + 日均几乎无功耗 + 准 + 能语音呼出。
> 本文件是 wake 重写的设计基准；进度/下一步在末尾「分阶段提交计划」。配套记忆 `arix-wake-redesign`。
> **本轮只设计，不写实现，待用户审。**

---

## 1. 目标与约束

| 维度 | 目标 | 现实约束 |
|---|---|---|
| 功耗 | 日均几乎为 0 | 可移植 App **拿不到 DSP 常听通路**（SoundTrigger 仅系统语音助手）；常开麦+CPU 不睡就 100–400mW，表电池仅 ~1150mWh |
| 性能 | 低端表 CPU 能跑 | 现状每 32ms 跑一次 Silero ONNX = 耗电大头；KWS 必须极轻 |
| 准 | 少漏报/少误唤醒 | 现状说话人相关 MFCC+DTW，抗噪一般 |
| 呼出 | 能语音叫醒 | 不追求"真常听"，改「窗口内听 + 显式兜底」 |
| 许可 | 宽松、可发 GitHub | 现 wake/ 4 文件是 Operit LGPL 移植；模型许可也要干净 |

**核心结论（不推翻，见记忆 `arix-wake-redesign`）**：
> `<0.05W` 的**真·常时监听**在 App 里做不到。方向不是压低常听功耗，而是**「别一直听」**——
> 用近乎零功耗的传感器/系统事件做**主触发**，只在窗口内开麦跑轻量级联；显式呼出兜底。

---

## 2. 现状剖析（要被替换的东西）

**LGPL 来源（Operit 移植，4 文件，均带诚实署名头）**：
- `wake/…/PersonalWakeListener.kt` — 常开 `AudioRecord` 16k/512帧 → **每帧跑 Silero ONNX**（耗电大头）→ 成段 → MFCC → **DTW 余弦比对个人模板** → 阈值判决。说话人相关模板匹配，非训练网。
- `wake/…/OnnxSileroVad.kt` — Silero VAD 的 ONNX I/O 封装（**模型本身 MIT，封装代码是 LGPL**）。
- `wake/…/PersonalWakeFeatureExtractor.kt` — 自写 FFT+log-mel+MFCC(13)+Δ+ΔΔ = 39维，≤64帧。**只在语音段跑，便宜**。
- `wake/…/PersonalWakeEnrollment.kt` — VAD 门控录 3 次 → 融合成 mean 模板 + stddev，存 `wake_template.bin`。

**app 侧（干净，非 LGPL，只需适配）**：
- `app/…/WakeService.kt` — 前台麦克风服务，`loadTemplate()` → `PersonalWakeListener.runLoop()` 永久常开，命中拉起 MainActivity。**它就是"常开麦"的执行者，是本次重写主战场。**
- `app/…/WakeTool.kt` — AI 工具（enroll/start/stop/status），内部另起一份 listener（进程死即失效，与 Service 重复）。
- `app/…/WakePage.kt` — 唤醒管理 UI（待迁到设计系统 XtomField/XtomCard/PageScaffold）。
- Manifest 已有 `RECORD_AUDIO` / `FOREGROUND_SERVICE_MICROPHONE` / `POST_NOTIFICATIONS`。**无** `ACTIVITY_RECOGNITION`（本设计主门控用 screen-on，零权限，不需要它）。
- wake 模块依赖：`onnxruntime-android:1.17.1` + coroutines + json。

**耗电病灶**：① 常开麦（IDLE 也在录）② 每 32ms 一次 Silero 推理。两者都被本设计干掉。

---

## 3. 分级架构总览

```
┌─────────────────────────────────────────────────────────────┐
│  WakeController （状态机，常驻前台服务内，IDLE 时近零功耗）      │
│                                                               │
│   [主触发/门控]  亮屏(ACTION_SCREEN_ON) · 充电 · 物理键/显式     │
│         │                                                     │
│         ▼   开麦 N 秒窗口                                       │
│   ┌──────────────  窗口内级联 WakeAudioPipeline  ───────────┐  │
│   │ L0 能量门(RMS/过零, 无NN)  → 挡掉安静帧, 大缓冲批处理后睡  │  │
│   │ L1 轻量 VAD(Silero, 仅二次确认)  → 只在有能量时推理        │  │
│   │ L2 KWS 判决(microWakeWord, INT8流式)  → 只在语音段跑       │  │
│   └───────────────────────────────────────────────────────┘  │
│         │ 命中                                                 │
│         ▼                                                      │
│   TRIGGERED → 拉起/通知 → 交给 STT → 回 IDLE                    │
└─────────────────────────────────────────────────────────────┘
```

### 3.1 传感器门控状态机（手表杀手锏，让日均≈0）

**状态**：
- `IDLE` — **麦克风关闭**，仅前台服务存活（一个通知 + 广播接收器），无音频循环、无推理 → 近零增量功耗。
- `ARMED` — 开麦 N 秒窗口，跑 L0/L1/L2 级联。窗口内无语音/无命中则超时回 `IDLE`。
- `TRIGGERED` — 命中，拉起 UI/通知，交 STT，然后回 `IDLE`（或续窗）。
- `ALWAYS_ON` — 持续跑级联（不回 IDLE）。**仅充电时 + 用户显式开启**。

**主触发（进入 ARMED）——按功耗从低到高**：
1. **亮屏 `ACTION_SCREEN_ON`（默认主门控，真·零成本）** — 表由 OS 级抬腕检测点亮屏幕（硬件门控，近零功耗），我们只监听广播。抬腕→屏亮→N 秒内说唤醒词。天然贴合手表交互。
2. **充电 `ACTION_POWER_CONNECTED`** — 切到 `ALWAYS_ON`（床头/桌面免抬腕）。
3. **显式呼出（兜底，永远可用）** — 物理键 / 应用快捷方式 / 通知动作 / 双击。
4.（增强，可选）**加速度计抬腕/`SIGNIFICANT_MOTION` 唤醒传感器** — 想"屏没亮也能预开窗"时用；零/低功耗硬件传感器。非默认。

**功耗策略（用户可选，落 SharedPreferences）**：
| 模式 | 电池时 | 充电时 |
|---|---|---|
| **省电窗口（默认）** | 仅亮屏后 N 秒窗口开麦 | 可选常听 |
| **插电常听** | 同上 | `ALWAYS_ON` 持续级联 |
| **仅显式** | 只物理键/快捷方式呼出，从不自动开麦 | 同 |
| **关闭** | 完全关 | 完全关 |

> 关键：`IDLE` 下前台服务**保持存活但不持麦**（FGS 存活≠麦活）。不在 IDLE 停服务是为了绕开 Android 14 后台启动 FGS 限制；空转成本仅"进程驻留 + 注册的广播接收器"，无麦无 CPU 轮询。

### 3.2 窗口内级联（省电 + 准）

- **L0 能量门**：读**大缓冲(~100ms 而非 32ms)** 让 CPU 批处理后睡；RMS + 过零率阈值挡掉安静时 99% 帧，**无任何 NN**。安静环境可降 8kHz 采样。
- **L1 轻量 VAD**：Silero **仅作二次确认**（只在 L0 放行的块上推理），大幅减少 ONNX 调用次数（对比现状每 32ms 一次）。
- **L2 KWS 判决**：microWakeWord INT8 流式模型，**只在 VAD 判为语音的段上跑**，每 30ms 一次推理、微控制器级算力 → 低端表无压力。替换 DTW。

对齐目标：**省电**（窗口+级联，日均≈0）· **准**（真 KWS 抗噪 > MFCC/DTW）· **能呼出**（窗口内 + 显式兜底）· **clean-room**（新 KWS + 重写管线，天然绕开 Operit）。

---

## 4. KWS 选型对比与结论

| 维度 | **microWakeWord**（选定） | openWakeWord | 现状 MFCC+DTW |
|---|---|---|---|
| 框架许可 | **Apache-2.0** | Apache-2.0 | Operit **LGPL**（要脱） |
| **模型许可** | **Apache-2.0**（esphome/micro-wake-word-models 仓 Apache-2.0） | **CC BY-NC-SA 4.0（非商用+传染）**⚠️ 除非自训练 | 无（个人录制） |
| 算力 | **微控制器级**（ESP32-S3；INT8 流式，每30ms一次，MixConv）→ 低端表最省 | 较重（含 Google 语音嵌入骨干每次推理） | 轻（仅语音段） |
| 训练数据 | 合成 TTS（Piper，含中文 zh_CN）→ **不用录音** | 合成 TTS（~20万条） | 用户录 3 次 |
| 说话人 | 无关（固定短语） | 无关（固定短语） | **相关**（本人） |
| 运行时 | 需 **TFLite/LiteRT** Android 依赖（小）**或**把模型转 ONNX 复用现有 onnxruntime | 复用现有 **onnxruntime**（不加依赖） | 现有 onnxruntime |
| 自定义短语 | 离线训练（microwakeword.com / Colab，"几分钟") | 离线训练（Colab，需自训练才能商用） | **在端 3 次录制任意短语** |

**结论：选 microWakeWord。** 它同时命中用户两个硬约束——
1. **许可干净**：框架 + 模型都 Apache-2.0，可发 GitHub、可商用、无传染。openWakeWord 的预训练模型是 **CC-BY-NC-SA 非商用**，直接用会污染许可；自训练虽能洗白但要引入较重的 Google 嵌入骨干。
2. **最省**：为 ESP32-S3 微控制器设计，INT8 流式、算力极小 → 正是"低性能手表可用"要的。

**运行时（已定）**：**加 LiteRT/TFLite-Android 依赖**（~1–2MB，官方支持稳）直接跑 `.tflite`。备选 tflite→ONNX 复用 onnxruntime（省依赖但 INT8 流式转换有坑）仅作降级预案。

**默认短语（已定）**：**「Hi Arix」**——品牌名直呼、国际化。音节偏短，需在 P7 重点做误唤醒调优（阈值 + 训练增强）。用 Piper 合成训练随包发。

---

## 5. 唤醒词模式 —— 待用户拍板（影响 L2 与 enrollment 设计）

microWakeWord/openWakeWord 都是**固定短语、说话人无关、离线训练**。切过去意味着**放弃"在端录任意短语"的现有体验**。三种走法：

- **(A) 仅固定短语 KWS**（v1 最省事、最稳）：随包发默认短语模型 + 可选"导入自训练模型"。**没有在端自定义短语**。
- **(B) 二者兼得**：默认固定短语 KWS + 保留**clean-room 重写的个人录制**（任意短语，用嵌入/原型匹配，非 DTW）作为可选"自定义模式"。功能全，但工作量更大。
- **(C) 仅个人录制、clean-room 重写**（保留现体验，脱 LGPL，但不上 KWS）：准度受限于模板匹配（或升级到小嵌入+原型）。

**已定：走 (B) 二者兼得。** 默认「Hi Arix」固定短语 KWS 作主路径（稳、准、许可干净）；**同时保留 clean-room 重写的个人录制**作可选「自定义唤醒词」模式——不再用 Operit 的 MFCC+DTW，改**小语音嵌入 + 原型/相似度匹配**（在端录 3 次 → 存嵌入原型 → 窗口内比对）。
> 落地节奏：主路径（KWS）先做（P4），自定义录制模式作为 (B) 的第二条腿在 P5 clean-room 重写；二者共用同一套状态机 + L0/L1 前端，只是 L2 判决器可切换（KWS 判决器 ↔ 嵌入原型判决器）。

---

## 6. 许可 / clean-room 策略（逐文件如何脱 LGPL）

| 现文件（LGPL） | 处置 | 依据 |
|---|---|---|
| `PersonalWakeListener.kt` | **重写** → `WakeAudioPipeline`（大缓冲循环 + L0 能量门 + L1 VAD 调用）；DTW 判决删除 | 音频循环/RMS/过零是通用 DSP，无需照抄 |
| `OnnxSileroVad.kt` | **重写封装** → `SileroVad`（ONNX I/O 是样板代码；模型 `silero_vad.onnx` 本身 MIT，保留资源文件） | 封装无独创性，重写即可 |
| `PersonalWakeFeatureExtractor.kt` | **重写** → KWS 主路径用 microWakeWord 自带 40维频谱前端；自定义模式用小嵌入模型前端（公开算法） | 走 (B)，两判决器各自前端 |
| `PersonalWakeEnrollment.kt` | **clean-room 重写** → 录 3 次 → 存**语音嵌入原型**（非 DTW 模板），供自定义模式窗口内比对 | 走 (B)，保留在端自定义 |

**产物**：`wake/LICENSE`(Apache-2.0) + `wake/NOTICE`（致谢 Silero VAD/MIT、microWakeWord/Apache-2.0、onnxruntime、LiteRT）。完工后**全 wake/ 零 Operit 署名头**。与 v1 路线图「发布合规」的 LICENSE/NOTICE 任务合流。

---

## 7. 分阶段实现 / 提交计划（一阶段一提交，编译过才算完）

> 排序原则：**先落"状态机"拿最大日均功耗收益（且不碰检测算法，可先包旧 listener 当黑盒）→ 再做 clean-room KWS 级联脱 LGPL + 提准**。这样收益早、风险分散，收尾时 LGPL 全清。

> **状态（2026-07-08）：P1–P7 代码全部完成并提交（master）。全模块 clean-room Apache-2.0，copyleft 清零。**
> 提交：P1 `46f24fc` · P2 `aa55c09` · P3 `845c873` · P4脚手架 `7c2893f` · P5 `892da27` · P6 `1e1534f` · P7 `76672c0`；助手 UI+默认助手 `bacd182`。
> **仅剩收尾（需真机/用户）**：① 用户 microwakeword.com 训练 `hi_xtom.tflite` 放 `wake/src/main/assets/models/` → 按真实张量校准 `KwsDetector` I/O（未到时 KWS 模式自动回退嵌入原型）；② 目标表实测功耗/VAD/分段/`enrollmentSimilarityThreshold`(默认 0.80)/SileroVad·MFCC 数值；③ 手动唤醒触发已留（语音唤醒页「手动唤醒」按钮 + 通知「呼出」动作 + 设为默认助手后的系统手势）；④ 可选 SoundTrigger 探路。

- **P1 · 许可骨架 + 状态机接口（纯 clean-room 新文件）** ✅
  加 `wake/LICENSE`(Apache-2.0)+`NOTICE`；新建 `WakeConfig` / `WakeState`(enum) / `WakeTrigger`(sealed) / `WakeEngine`(facade 接口) / **`WakeDetector`(L2 判决器接口，让 KWS 判决器与嵌入原型判决器可切换)**。全是新写，不含 Operit 代码，桩件编译通过。

- ✅ **P2 · 传感器门控状态机（`WakeController`）+ `WakeService` 重写** ← **最大功耗收益**
  实现 IDLE/ARMED/TRIGGERED，由 `ACTION_SCREEN_ON` / `ACTION_POWER_CONNECTED` / 显式触发驱动；N 秒窗口超时；功耗策略设置。**窗口内检测暂时委托现有 `PersonalWakeListener`（当黑盒，LGPL 暂留，后阶段替换）**。IDLE 不再持麦。此提交即拿到"日均≈0"。可独立于 KWS 验证。

- ✅ **P3 · clean-room 音频前端 + L0 能量门 + L1 VAD**
  重写捕获循环：大缓冲(~100ms) `AudioRecord` 读、RMS+过零能量门(L0)、`SileroVad`(L1, clean-room 重写封装) 二次确认、8kHz 安静档。替换 `PersonalWakeListener` 的 Operit 循环为 `WakeAudioPipeline`。L2 先桩/临时桥接。

- **P4 · L2 KWS 主路径集成（microWakeWord）** ← **脱 LGPL 检测 + 提准**
  加 **LiteRT/TFLite 依赖**；clean-room `KwsDetector`(实现 `WakeDetector` 接口) 跑 Apache-2.0 模型；随包 **「Hi Arix」** 默认短语模型资源 + clean-room 40维频谱前端。删 `PersonalWakeFeatureExtractor` + DTW。主路径检测链此刻全 clean-room + 宽松许可。

- ✅ **P5 · 自定义唤醒词模式（走 (B) 的第二条腿，clean-room）**
  clean-room 重写 `PersonalWakeEnrollment` → 录 3 次存**语音嵌入原型**；新 `EmbeddingPrototypeDetector`(同 `WakeDetector` 接口，与 KWS 判决器可切换)；加"导入自训练模型"入口。窗口内 L2 依用户选择路由到 KWS 判决器或嵌入原型判决器。

- ✅ **P6 · WakePage UI + 设置**
  功耗模式选择器、短语/模型选择、测试/录入按钮、状态显示；迁到设计系统（`XtomField`/`XtomCard`/`PageScaffold`，令牌配色）。同时理顺 `WakeTool`（复用 `WakeController`，去掉重复 listener）。

- **P7 · 去衍生化收尾 + 实测**
  删所有残留 Operit 署名头文件，核验 wake/ 零 LGPL，定稿 `NOTICE`。**目标表实测功耗**（Battery Historian / 功耗仪）验证"日均≈0"并调阈值。可选 **SoundTrigger 探路分支**（系统 App+DSP，官改包+root 才可能，非主线，仅存档评估）。

---

## 8. 风险与必测项

- ⚠️ **所有功耗数字是工程量级估计，非实测** — P7 必须在目标表实测拍板。
- microWakeWord 流式模型在**标准 Android TFLite（非 tflite-micro）**上跑：需管理流式状态张量，可行但要验证；ONNX 转换 INT8 流式有坑（作降级）。
- 默认短语的**误唤醒率**：Piper 合成训练质量决定，多音节短语 + 阈值调优；P7 调。
- `ACTION_SCREEN_ON` 主门控依赖表的抬腕点屏行为；个别 ROM 差异 → 提供加速度计增强档兜底。
- Android 14+ 麦克风前台服务启动限制：FGS 常驻不停、仅窗口内开关 `AudioRecord`，绕开后台启动限制。

---

## 9. 已定决策（2026-07-08）

1. **唤醒词模式 = (B) 二者兼得**：默认「Hi Arix」固定短语 KWS 主路径 + clean-room 重写的个人录制（嵌入原型）作可选自定义模式。
2. **KWS 运行时 = 加 LiteRT/TFLite 依赖**（tflite→ONNX 仅降级预案）。
3. **默认短语 = 「Hi Arix」**（音节偏短，P7 重点调误唤醒）。
4. **阶段排序认可**：先状态机（P1+P2）拿功耗收益，再 clean-room KWS 级联（P3+）脱 LGPL。

> 下一步（待用户放行）：开写 **P1**（许可骨架 + 状态机/判决器接口，纯 clean-room 新文件，编译过）。本文件是 wake 重写基准，改大方向先更新此处。
