# 极限优化计划 · 2026-07-28 定，下一个窗口开工

> 四条线：**体积 / token / 功耗 / 性能**。本文件是**开工清单**，不是调研记录。
> 四路只读摸底的原始结论已经压进来了，别再重新摸一遍。
>
> **口径**：标 ✅ 的是**我在主线程亲自核过**（给了 `文件:行号` 或实测字节数）；
> 标 🔶 的是子 agent 报的、我没逐条核，**动手前先验**；标 ⚠️ 的是纯推断，**必须先验证再动**。
>
> **铁律（沿用项目原则）**：**不许拿「用的人少 / 收益低 / 场景边缘」当理由砍功能**。
> 本文件里没有一条是"删掉某个能力"——只有「按需加载」「延后」「默认关但随时能开」「换个更小的实现」。

---

## ⭐ 进度（2026-07-28 晚 · 第一轮开工）

**做完了：第五节开工顺序的 1、2、3 步 + 功耗里不需要拍板的那几条。编译过（debug+release），真机零验证，未提交。**

| 条目 | 状态 | 实际做法（与原计划不同的地方已标） |
|---|---|---|
| **T1** 每轮白烧一次主模型请求 | ✅ | `queryRelevant` 加 `allowLlmFallback`（**默认 false**）。发送主路径(MemoryInjection)/唤醒上下文不再走 LLM 兜底，只有 `memory search` 工具显式开。顺带：兜底改走 `summary` 用途 + `max_tokens` 封顶 |
| **P3** MemoryTidy 缺网络约束 | ✅ | 加 `NetworkType.CONNECTED`；顺带把一轮**三次全表 `SELECT *`** 换成 `COUNT(*)` / 按类型带 `pinned=0` 的查询 / 只取 id |
| **F4** 主线程解码图片 | ✅ | `convertImage` 改 suspend + `Dispatchers.IO`；加 `inJustDecodeBounds` 定采样率（≤4M 像素），缩过会在结果里说明 |
| **S1** 删两个没人用的 .so | ✅ | 删文件 + 清 pickFirsts。**动手前自己复核过**：jni 只引用 `libonnxruntime.so`，不引用 c-api |
| **S2** tflite 运行时 | ✅ | 改 `compileOnly` + `WakeEngines` 用 `runCatching` 兜 **Throwable**（NoClassDefFoundError 是 Error）+ `-dontwarn org.tensorflow.lite.**` |
| **F1** 为一个字段拉整份会话 | ✅ | 新增 `cardIdOf(id)` 投影查询；换掉 ChatScreen ×2 / ContextCompressor / Diary。`loadBranches` 改走单列分块读；`getMostRecentActive()?.id` 换 `getMostRecentActiveId()` |
| **F2** 分支树解析在主线程 | ✅ | 建树/`deriveActivePath` 挪 `Dispatchers.Default`；同段的 `getById`→投影、世界书文件读/状态卡续接挪 `Dispatchers.IO` |
| **F3** 检索走全表 | ✅ | ⚠ **计划里"改 1 行用 `embeddedInCard`"是错的**——那个查询的 cardId 语义是「同卡内」（写入去重用），照搬会把**通用记忆**从语义检索里踢掉。新加了 `embeddedForRetrieval`，条件与原内存 filter 逐字等价 |
| **F5** 首帧写 /sdcard、字体重复解析 | ✅ | `publishShared` 挪到 `IO.limitedParallelism(1)` + 内容没变不写；`xtomTypography` 在 XtomTheme 里 `remember` |
| Room：8 次 `setLastAccessed` | ✅ | 合成一条 `setLastAccessedIn(ids)`（Room 失效是表级的，8 次写=记忆页订阅者被叫醒 8 次） |
| 置顶注入读 200 行 | ✅ | 新增 `pinnedForRetrieval(cardId)`，交给 SQL 挑 |
| **P3** 429 零退避 | ✅ | 429 才等（401/403 立刻换 key 是对的）；优先听 `Retry-After`，封顶 2s，用可取消的 `delay` |
| **P3** ADB 体检 60s 永远跑 | ✅ | 一直正常就周期翻倍（封顶 15min）；连续 5 次恢复不了就自己停（原来 `cancelHealthCheck` 无人调用） |
| **P3** WorkRequest 约束/退避 | ✅ | 日记加网络约束；主动消息加 `batteryNotLow` + 退避 15min 起（**全项目唯一会 `Result.retry()` 的 worker**）。⚠ **提醒/定时任务故意不加约束**——那是用户指定时刻要发生的事 |
| `embedScope` 无并发上限 | ✅ | `IO.limitedParallelism(2)` |

**实测**：release APK **47.18MB → 39.31MB**（41,217,496 B），与 S1+S2 预估的 −7.86MB 吻合。
dex 仍是 5.34MB、`libonnxruntime.so` 仍是 24.63MB（S3/S4 没动）。

### 第二轮（同日，按用户拍板结果做的）

| 条目 | 拍板 | 做法 |
|---|---|---|
| **默认功能包** | 只留最常用的 | `map` / `life_query` / `media` / `browser` 四个改 `enabledByDefault = false`。**不是砍功能**：工具照样注册、名字照样在提示里，模型 `request_permission` 一申请就开 |
| **T2 元任务模型** | 两个都做 | ① `getConfigForPurpose(purpose, capMaxTokens)` 加出参兜底上限，8 处元任务按出参长度分别封顶（摘要 2048 / 抽记忆·状态卡 1024 / 翻译·审批 512 / 建议 256）；② 新手向导「连接模型」步加一张卡：填个便宜小模型名，沿用同一地址密钥，一次写 `title`+`summary` 两个用途 |
| **P1 唤醒省电** | 先暴露设置，默认不变 | 唤醒页新增三档（一直听 / 充电时才一直听 / 只在窗口内听）+「低电量自动省电」开关；服务侧把项目里早写好却零调用的 `isCharging()` 接上，并新增电量读取；靠 `ACTION_BATTERY_LOW/OKAY` + 电源广播换档，**不轮询**。**默认仍是一直听，行为与从前逐字一致** |
| **T3** 未启用能力清单 | —— | 申请方式从「每个包重复一遍」改成末尾统一说一次；顺带**按包 id 排序**（原来遍历 ConcurrentHashMap，顺序一变前缀缓存就断，正是 T5 的第 2 条） |
| **T4** `web_search` 瘦身 | —— | 引擎清单改成**按本机此刻真能用的**动态生成（没配 key 的键控引擎连名字都不出现，顺带治「去调没配的引擎」那种幻觉）；删掉与 `depth` 重复的 `deep` 参数（execute 仍兼容收）；描述里"别用 deep"从三遍减到一遍 |

**实测**：默认每轮工具 schema **4663 → 3541 token**（−1122，−24%）。其中功能包 −864、T4 −258。
T3 省的那几百 token 在系统提示里，不在这个数里（脚本只量 schema）。

### 第三轮：中文税 + ORT 瘦身

**① 模型侧提示词一律改英文（用户提的方向，实测确认）**

先把口径钉死：`tools/token_cost.py` 改用真 tokenizer（装了 `tiktoken` 就用 `o200k_base`，拿不到才退回旧折算）——
**第七节 7.3 的第 5 条"token 估算口径没验"到此了结**。实测我们自己的句子：
中文在 o200k 是 **0.62 token/字**、在 cl100k 是 **0.85 token/字**；英文在两边都是约 4 字符/token。
所以「中文税」只对**中文语料训出词表**的那几家（DeepSeek/Qwen/GLM/Kimi）不成立，Claude/GPT/Gemini 都要交。
英文对它们大赚、对中文模型打平——**没有输面**。

做法：`Tool` 接口拆成两份描述（`Tool.kt`）——
- `description`：给**人**看，中文，权限页/功能包页/本地搜索在显示它，一个字没动；
- `llmDescription`：给**模型**看，英文，`toOpenAiTool()`/MCP 网关/MCP 服务端都改走它（默认回退到 description）。
- 参数里的 `description` 不用分家（只发模型、UI 不显示），直接改英文。

覆盖了**默认开着的全部 33 个工具**。实测（同一批工具，剥离掉"关了 4 个包"的影响）：

| | 改前 | 改后 | 省 |
|---|---|---|---|
| o200k（GPT-4o/5 系） | 3211 | 2308 | **−28%** |
| cl100k（GPT-4 系） | 4108 | 2355 | **−43%** |

连同默认关掉的 4 个包，默认每轮 schema 文本：o200k **4021 → 2308（−43%）**、cl100k **5160 → 2355（−54%）**。
⭐ 一个直观的旁证：改前同样内容 cl100k 比 o200k 贵 28%，改后两者只差 2%——**中文税消失了**。

**② ORT 瘦身：39.31 → 35.37MB（计划里 S3/S4 的前提有两条是错的）**

- ❌ **"换 sherpa 上游那份 15.25MB 的 ORT 省 9.38MB"不成立**：那份是 ORT **1.17.1**（`.gnu.version_d` 写着
  `VERS_1.17.1`），而我们的 jni 在 `.gnu.version_r` 里要求 `VERS_1.24.3`——正是当年 `OrtGetApiBase` 那个坑。
  而且官方 v1.12.15 的 jni 只导出 109 个 JNI 方法，我们的 Java 绑定要 131 个，往回退会缺方法。
- ❌ **"微软打包不干净"也不成立**：微软 1.17.1 = 15.29MB、sherpa 自建 1.17.1 = 15.25MB，一样大。
  那 9MB 是 **ORT 自己从 1.17 长到 1.24 长出来的**（1.24.3=24.63 / 1.27.0=26.69 / 1.28.0=27.31，只增不减）。
- ✅ **真正能省的**：sherpa 对**同一个版本**的构建关掉了 CUDA/TensorRT/OpenVINO/DirectML/WebGPU 那套
  在手表上永远跑不到的执行提供者——他们的 1.27.0 只有 **20.68MB**，比微软同版本小 6MB。

于是把整栈统一到 **ORT 1.27.0**：stt 的 jniLibs 换成 sherpa v1.13.4 的 jni + 他们自建的 libonnxruntime.so，
wake 的微软 aar 升到 1.27.0（只为 `ai.onnxruntime` 的 Java API 和 4j_jni）。**没有改任何一行逻辑代码。**

动手前逐条静态核对（不是推断）：v1.13.4 的 jni 导出 **131 个 JNI 方法、与我们现有的完全一致**（缺 0 多 0）；
它和微软 4j_jni 1.27.0 都要求 `VERS_1.27.0`，sherpa 的库都定义了；两者需要的 ORT 符号
（`OrtGetApiBase` / `..._CPU` / `..._Nnapi`）在 sherpa 库里全部存在。
打包后又验了一遍 APK：`libonnxruntime.so` 是 20.68MB 那份、`libsherpa-onnx-jni.so` 是 4710728 字节那份（配对的）。

**放弃的更激进路线**：static-link 单文件（22.51MB，−6.7MB）要把 wake 的 `SileroVad` 从「逐帧概率」
改成 sherpa Vad 的「分段检测」语义——那是唤醒最核心的一环，没真机验不了，不值当为 2.7MB 赌它。

**包体总账**：47.18 → 39.31 → **35.37MB**（−11.81MB，−25%）。

**仍没做、原因是有回归风险且计划里没评估**：P2 无障碍掩码收窄（会不会让 GUI 自动化漏事件，没人算过）。
**仍没做**：Room 复合索引（要 MIGRATION_20_21）、Compose 那批、S6、S5（用户定"先放着"）、
static-link 单文件（等有真机能验 VAD 时再谈）。

### ⭐ 明天从这里开始（2026-07-28 收工状态）

**已提交**（分支 `work/0728-terminal-fixes`，**未推送**）：
```
fd77384  备份恢复兼容旧包（OnyxAI）导出的数据
e1a2498  修真机语音闪退：R8 改名了 ONNX Runtime 的 Java API
d29f6ca  极限优化：包体 −7.9MB / 每轮 schema −43%~54% / 止血 token / 热路径与功耗
78da46f  更名 OnyxAI → Arix
```
⚠ 工作区里还留着**另一个会话正在写的终端文件**（`TermTransfer.kt` / `TermSettingsActivity.kt` /
`FetchSetup.kt`），别顺手 `git add -A` 把它们卷进自己的提交（今天犯过一次，已撤回）。

**第一件事：装 release 包（不是 debug）试语音。**
今天的语音闪退真因是 R8 把 `ai.onnxruntime` 的纯数据类改了名（`TensorInfo -> b.k`），
JNI `GetMethodID` 找不到构造函数直接 abort——**只有 release 会炸，debug 不会**，所以必须用 release 验。
已加 `-keep class ai.onnxruntime.** { *; }`，mapping.txt 里那几个类已确认保持原名。

**验完语音正常的话，第二件事：把 ORT 那 3.95MB 拿回来。**
此前误判成"换 sherpa 自建 ORT 1.27.0 导致闪退"并回退了，现在看那次回退多半没必要。
重做方法（静态核对当时全过，记录在下面第三轮那节）：stt 的 jniLibs 换 sherpa v1.13.4 的 jni +
他们自建的 libonnxruntime.so(20.68MB)，wake 的微软 aar 升 1.27.0。**这次务必用 release 真机验**。

**再往后**：Room 复合索引（要 MIGRATION_20_21）、Compose 那批、S6（PDFBox CMap）、
S5（`.so` 压缩，用户定"先放着"）、static-link 单文件（要改 wake VAD 语义，需真机）。

---

## 零、一句话结论

| 线 | 现状 | 最大的那块 | 性质 |
|---|---|---|---|
| 体积 | release **47.18MB** | 语音 native 栈 **33.7MB**（`libonnxruntime.so` 一个 24.63MB） | 代码怎么省都是零头，dex 才 5.34MB |
| token | 工具 schema **4663 tok/轮** | 但真正的出血点不在提示词里，是**每轮往主模型白扔一次检索请求** | 先止血，再瘦身 |
| 功耗 | —— | **唤醒一开 = 永久常开麦 + 每帧 VAD 推理**，省电那套代码够不着 | 一个开关顶过所有其它项之和 |
| 性能 | —— | 热路径上**为拿一个字段拉整份 2MB 会话**、检索走全表 | 对的写法就在旁边，是漏用不是没有 |

**四条线都有一个共同形状：正确的做法项目里已经写好了，只是没接上。** 这决定了下面绝大多数条目的成本是「小」。

---

## 一、体积：47.18MB → 目标 32MB 以内

### 包体构成（✅ 实测，解 APK 逐条量的）

```
24.63MB  lib/libonnxruntime.so        ← 一个文件 = 全包 52%
 5.34MB  dex（压缩后；原始 11.92MB）
 4.49MB  lib/libsherpa-onnx-jni.so
 4.20MB  lib/libsherpa-onnx-c-api.so
 3.24MB  lib/libtensorflowlite_jni.so
 1.86MB  assets/models/silero_vad.onnx
 1.54MB  assets/com（PDFBox 的 CJK CMap 表 + 后备字体）
 0.42MB  lib/libsherpa-onnx-cxx-api.so
 0.33MB  assets/org（jlatexmath 字体）
 0.64MB  resources.arsc ＋ 0.14MB res/
```

### 按「收益/风险」排的动作

| # | 做什么 | 省 | 成本 | 风险 |
|---|---|---|---|---|
| **S1** ⭐ | **删 `libsherpa-onnx-c-api.so` + `libsherpa-onnx-cxx-api.so`** | **−4.62MB** | 删两个文件 + 清 `app/build.gradle.kts` 的 pickFirsts | **≈零** |
| **S2** ⭐ | **去掉 `tensorflow-lite` 依赖** | **−3.24MB** | 删依赖 + 处理 `KwsDetector` | 低 |
| S3 | **换一份更小的同版本 ORT**（sherpa 上游那份 15.25MB） | −9.38MB | 换 .so + exclude 微软 aar 的 jni | 中 ⚠️ |
| S4 | **改用 ORT 静态链进 jni 的 sherpa 包**（21.88MB 单文件，含 S1） | −11.86MB | S3 + `SileroVad` 迁到 sherpa 自带 VAD | 中高，动核心唤醒链 |
| S5 | **开 `useLegacyPackaging`（压缩 .so）** | 下载 **−23MB** | 一行 | 装机反而 **+14MB** ← **要你拍板** |
| S6 | PDFBox 的 CJK CMap 表按需下载 | −1.54MB | 中（要接管 `PDFBoxResourceLoader`） | 中 |
| S7 | ORT / 模型整体后下 | 15~24MB 挪出包 | 大 | **今天做不了**，见下 |
| S8 | `shrinkResources` | <0.5MB | 小 | **不做**——见下面的更正 |

**S1 的依据（✅ 我核过）**：读 .so 里的依赖字符串，`libsherpa-onnx-jni.so` 只引用 `libonnxruntime.so`，**不引用 c-api**；
全项目 `loadLibrary` 只加载 `sherpa-onnx-jni` / `onnxruntime` / `xtomllm` 三个。这两个文件没有任何人用。

**S2 的依据（✅ 我核过）**：`wake/src/main/assets/models/` 是**空目录**，全项目 `find -name "*.tflite"` 零结果 →
`KwsDetector` 加载必然失败、恒回退到 `EmbeddingPrototypeDetector`。**这不是砍 KWS 功能**——KWS 模型还没训出来，
等真做的时候，模型和这个运行时**一起按需下载**（正好走 S7 的路子），比现在空占 3.24MB 强。
⚠ 实现注意：`KwsDetector.create()` 只 `catch (Exception)`，类缺失抛的是 `NoClassDefFoundError`（是 `Error`），
改 `compileOnly` 的话 catch 要放宽到 `Throwable`，否则会漏出去。

**S7 为什么今天做不了**：`RemoteAssets.DEFAULT_BASE` 现在是**空串**（发布仓库地址未定）。
前置条件是先有分发地址。另外 🔶 一条关键限制：TerminalInstaller 那套的信任锚点是「APK 签名与本 App 一致」，
**对裸 .so 不适用**——后下 .so 必须新写 sha256 固定校验，否则等于开了任意代码执行的后门。这是这条路上安全等级最高的一条。
利好：`targetSdk=28`（`app/build.gradle.kts:19-22` 明写是为了留在 `untrusted_app_28` 域）**不受 API 29+ 那条
"不许 dlopen 应用可写目录里的 .so"约束**——和 proot 能跑是同一条依据。

**S8 更正 `OPEN-QUESTIONS.md` Q32**：Q32 说不开 `shrinkResources` 是因为"大量按名字取的 raw/assets"。
🔶 这个理由站不住：全仓库**零个 `res/raw`**；`getIdentifier` 只有 2 处且都取 `android` 包的系统资源；
字体/模型/脚本全在 `assets/`，而**资源压缩从不碰 `assets/`**。真实上限收益 <0.5MB。
→ **结论不变（不做），但理由要改成"收益太小"，别再拿错误的理由挡着。**

**开工顺序**：S1 + S2 先做（纯删、几乎零风险，47.18 → ≈39.3MB）→ 拿 S5 问你 → S3/S4 需要真机验唤醒和语音。

---

## 二、token：先止血，再瘦身

### 🩸 止血（这两条比省提示词重要一个量级）

**T1 ⭐ `llmPickHits` 每轮往主模型白扔一次请求，然后超时丢弃（✅ 我核过全链）**

- `MemoryManager.kt:556`：**没配 embedding 模型**时，检索走"语义兜底"——把库里最多 50 条记忆
  （每条标题 + 正文前 80 字）连同用户这句话，发给 `purpose="chat"` 即**主对话模型**（`:687-696`）。一发就是上千 token。
- 而整个 `queryRelevant` 被包在 `MemoryInjection.kt:41` 的 **1500ms 超时**里。
- 后果：**prompt token 已发出、钱照付；结果超时丢弃、记忆块返回 null**。
- **触发条件是默认安装**（默认没配 embedding）+ 库里 ≥5 条记忆 → 这是默认路径，不是边角。
- ⚠️ "1.5 秒跑不完一次 chat 往返"是推断（机制是实测）。动手前先在真机上量一次这条路径的耗时，
  确认它到底是"经常超时"还是"偶尔超时"——这决定了是**给它单独放宽超时**还是**换个不花钱的兜底**。

**T2 ⭐ 所有元任务默认都在用最贵的模型（✅ 我核过）**

- `CloudApiConfigManager.kt:63-65`：`getConfigForPurpose(x)` 在该用途没配时**回退到主对话模型**
  （注释写明是有意的"未配也能干活不至于报错"）。
- 后果：代码里那些"走便宜的小模型省钱"的注释，**在默认安装下全是假的**。
- 涉及：起标题、建议芯片、记忆抽取、状态卡、上下文摘要、工具自动审批、记忆冲突消解、自动整理教训。
- 方向（不是砍功能）：① 新手向导里引导配一个便宜的小模型给元任务用；
  ② 或者在没配专用模型时，给元任务**自设 `max_tokens`**（现在一个都没设 🔶）并降低触发频率。

### 瘦身（每轮固定省）

| # | 做什么 | 省 | 依据 |
|---|---|---|---|
| **T3** | `disabledCapabilitiesNote` 去重复样板 | 🔶 ~370 tok/轮 | 33 个默认关的包，每个都拼一遍 `申请: request_permission(permission="package:<id>")`。同一件事说了三遍（工具 schema 里一遍、这里 33 遍、拦截时再一遍）。改成清单 + 末尾一句总说明 |
| **T4** | `web_search` 瘦身 | ✅ ~250-300 tok/轮 | engine 那串引擎清单占 195 tok，里面十几个键控引擎**没配 key 就用不了**却每轮都发（顺带治幻觉：它会去调没配的引擎）；`deep`(43) 与 `depth`(38) 是同一个开关的两份；描述里 deep 的用法讲了三遍(68) |
| **T5** | 前缀缓存的 4 处抖动源 | 间接、可能很大 | 见下 |
| T6 | 记忆注入预算 1200 字符 | ≤1000 tok/轮 | 易变段里最大的一块，每轮全价。等 T1 修完再谈调它 |

**T5 的四处抖动源（🔶 报的，动手前逐条验）**——一个字节的抖动就让后面几千 token 的缓存全废：

1. **最高**：`PromptVars.resolve` 作用于**整个** sysPrompt（含静态段）。人设里只要有 `{{time}}`，`HH:mm` 每分钟一变 →
   **整个静态前缀连同人设全部作废**。仓库自带内容不含 `{{time}}`，但酒馆导入的卡常带。
2. `disabledCapabilitiesNote` 遍历 `tools.values` **不排序**——而 `getToolsJson` 明确 `sortedBy { it.name }`
   并注明"定序=可缓存"。MCP 连上/技能开关改表 → 顺序变 → 它自己和后面全废。
3. `missingCapabilitiesNote` 依赖 30s TTL 的 `CapabilityProbe`，探测异常 fail-open → 一次 binder 抖动就让某能力
   "消失/出现" 30 秒 → 前缀断。
4. **两条发送链的提示词切分不一致**：语音链（`pendingAutoSend`）没做 static/volatile 切分，
   而且**不注入记忆/环境/状态卡**。打字↔语音混用同一对话会互相打掉缓存。

### 明确不用动的

**历史压缩机制在短对话下完全休眠**（✅ 常量实测）：`TRIM_TRIGGER_CHARS=16000`、`DEFAULT_TRIGGER=24 条`。
手表上 4-10 轮对话 = 8-20 条、3000-6000 字，**两个阈值一个都够不着**。
但它不是废的——它实际是"**工具循环保险丝**"（3 个密集工具轮次就同时越过两个阈值），不是聊天省钱器。
→ **调这几个常量没用，短对话本来也没多少历史可压。别在这儿花时间。**

### 要你拍板的

- **默认开的功能包砍不砍**（每轮固定成本，按包聚合见 `python tools/token_cost.py default`）：
  daily_life 630 / media 232 / map 212 / life_query 202 / browser 218 …
  ⚠ 按「先有」原则，这**不是砍功能**：关掉的包不发 schema，只在系统提示里留一行名字，
  模型需要时会 `request_permission` 申请开。但默认值确实是产品判断，要你定。

---

## 三、功耗：一个开关顶过其它所有项之和

### P1 ⭐⭐ 唤醒一开 = 永久常开麦 + 每帧 VAD 推理（✅ 我核过）

- `WakeService.kt:199-201`：非测试模式**无条件** `WakeConfig(powerPolicy = ALWAYS_ON)`，
  而 `WakeConfig.kt:16-17` 写着默认是 `WINDOWED`（只在门控触发后的窗口内开麦）。
  **省电那套（窗口 / 息屏门控 / 级联）代码全在，就是够不着。**
- **不是 bug**：`WakeConfig.kt:22-26` 写明原因——MIUI 这类 OEM 上，非系统 App 只有
  "前台开一次麦、之后不再重启"才能可靠后台持麦。是有意的取舍。
- 但代价完全没兜住：`WakeService.kt:337/340` 定义了 `isScreenInteractive()` / `isCharging()`，
  **全项目零调用**。息屏不停、拔电不停、低电量不降级，**也没有任何设置让用户选策略**。
- 🔶 同源：`WakeController.kt:100-101` 显式让 `ALWAYS_ON` 豁免断电停止；`quietDownsample8k` 从没被读过（麦克风恒 16kHz）。
- **方向**（不是关掉唤醒）：把策略做成**用户可选 + 有默认退避**——
  ① 暴露 `WINDOWED / ALWAYS_ON_WHEN_CHARGING / ALWAYS_ON` 三档给用户；
  ② 把已经写好的息屏/充电判据接上；③ 低电量自动降档。
  ⚠ 改之前先确认 MIUI 那条限制今天还成不成立——如果成立，`WINDOWED` 在那些机器上会直接不可用，
  那就得按 OEM 分档，而不是一刀切换默认值。

### P2 无障碍事件洪水（🔶 待验）

`res/xml/xtom_accessibility_config.xml:3,5,8`：订阅 `typeWindowContentChanged|typeViewScrolled|typeViewTextChanged`
+ `notificationTimeout="100"` + `flagRetrieveInteractiveWindows`。**设备级**每次 UI 变化都触发。
handler 第一行就 `if (SkillRecorder.isRecording)` 丢弃——**但那是在系统已经建好事件并 IPC 过来之后**。
全项目**没有 `setServiceInfo` 调用** → 静态声明的是最宽的掩码。
方向：静态声明收到最小，只在真正录制时用 `setServiceInfo` 动态放宽。

### P3 周期任务与约束（🔶）

- `AdbKeepAlive.kt:40` 健康检查 **60 秒一次、永远跑**，从 `BOOT_COMPLETED` 起；`cancelHealthCheck()` 存在但**无人调用**。
- 5 个 WorkRequest 里 3 个**没有任何约束**；全项目 `setBackoffCriteria` **零命中**。
- `MemoryTidy`（今天加的）有 `requiresBatteryNotLow` 但**没有网络约束**，而它要发 LLM 请求 →
  离线被唤醒＝全表读三遍 + 请求失败，而且时间戳是**先写后跑**，等于白跳过一轮。**这条现在就该补。**
- `CloudApiClient.kt:244-254` 密钥池重试**零退避**，包括 429 —— 对 429 立刻重试是最坏情况。

### P4 悬浮窗的帧循环不随息屏停（🔶）

6 个 `TYPE_APPLICATION_OVERLAY` host 把生命周期钉在 RESUMED，**不观察息屏**。
Activity 里的帧循环有 Compose 的 `PausableMonotonicFrameClock` 在 `ON_STOP` 时暂停，这些**什么都没有**。
`UiActionOverlay.kt:403-413` 是全屏 60fps 脉冲 Canvas，整个 `ui_control` 会话期间跑。

### 已确认干净的（别在这儿花时间，✅ 交叉核过）

- **零 `WakeLock`**、零 `FLAG_KEEP_SCREEN_ON`、零周期性 `AlarmManager`、零高频系统广播。
- **传感器/定位/BLE/健康全部有界且正确反注册**（心率 45s 上限、定位 5-8s 硬顶 + 首次定位就 `removeUpdates`、
  BLE 15s + `finally { stopScan }`、健康三层 TTL 从不轮询）。
- **`hazeEffect` 已经不在了**——玻璃现在是"每次壁纸/尺寸变化算一次 CPU 预模糊，之后画裁剪过的位图"，**零每帧模糊开销**。
  （玻璃那套已定案，不许再动。）
- `ui/FrameMotion.kt` 三个循环都会自己停。
- 零遥测/统计/崩溃上报 SDK。
- 默认开的常驻只有 `MemoryTidy` 和通知监听——**电池故事完全由用户打开了什么决定**，而 P1、P2 正是那两个最贵的开关。

⚠ **本节没有任何耗电数字，一个都没有。** 上面全是结构性判断（谁在跑、多频）。真要排序得上真机量。

---

## 四、性能：对的写法就在旁边

### 五条「热路径上的漏用」（前四条我 ✅ 核过）

| # | 问题 | 位置 | 修 |
|---|---|---|---|
| **F1** ⭐ | **为拿一个 `characterCardId`，把整份可达 2MB 的会话分块拼回来** | `ChatScreen.kt:1332` 和 `:1482`（**每轮助手回复收尾**）、`ContextCompressor.kt:296`、`ConversationManager.kt:189` | DAO 里 `getSummaryById`(`:43`) / `readBranchesJson`(`:78`) **早就有了，这三处没用** |
| **F2** ⭐ | `branchesJson` 的 JSON 解析**留在主线程** | `ChatScreen.kt:1197` 的 `LaunchedEffect`（Main）；同段还有 5 处主线程阻塞 IO（读 `world_trees.json` 全文并排序等） | 同文件 `ConversationManager.kt:147` 早已为 `messagesJson` 挪到 `Dispatchers.Default` 并写了注释，**这条漏了** |
| **F3** ⭐ | 语义检索走**全表 `SELECT *`**（含 `embedding` 大字段、无 LIMIT） | `MemoryManager.kt:676` 用 `allList()` | `MemoryDao.kt:50-52 embeddedInCard` 已存在，注释原文「比 allList() 少读整表」——写入去重路径用了，**检索路径没用**。约 1 行 |
| **F4** ⭐ | **唯一一个没切线程的工具**，在主线程整幅解码+重编码图片 | `EnhancedTools.kt:178` 无 `withContext`（同类 4 个工具全都有）；`:209` `decodeFile` 无 `inSampleSize` | 小。⚠ 注意 `ToolManager.kt:245` 的 `withContext(CallerContext(...))` **只换 context element、不换 dispatcher** |
| F5 🔶 | 冷启动首帧往 `/sdcard` 写文件；自定义字体每次主题重组重新 `createFromFile` | `ThemeConfigStore.kt:28`、`XtomTheme.kt:80` 未 remember | 小 |

### Room（🔶 待验，但方向明确）

- **最热的会话查询没索引**：`WHERE isArchived=0 ORDER BY isPinned DESC, updatedAt DESC`，
  而实体上只有两个外键索引。这条被抽屉/对话列表/记忆页/项目页**四处同时订阅**，
  而**每发一条消息都会 UPDATE conversations** → 四个订阅者全部重跑全表扫+临时排序。→ 加复合索引（要 MIGRATION_20_21）。
- `MemoryManager.kt:527` 每次发消息 8 次 `setLastAccessed` UPDATE，而 Room 失效是**表级**的 →
  记忆页在前台时全表 `SELECT *` 被反复重跑。改成一条 `WHERE id IN (:ids)`。
- 记忆表 11 个查询**全是 `SELECT *`**。⚠ 单行不可能超 2MB，**不会复现会话表那次崩溃**，是纯性能问题，别按同一紧急度处理。
- `UsageStatsPage.kt:63-69` N+1：对每条会话完整读+反序列化，只为数条数。

### Compose（🔶，只列前几轮没覆盖的）

- **lazy item body 里做磁盘 IO**：`MainActivity.kt:562`（抽屉，读 `world_trees.json` 全文 + 排序，无 remember）、
  `ProjectsPage.kt:88`（每行一次 prefs + JSON 解析 + 全表 filter）。
- **`PageScaffold` 默认是 `Column + verticalScroll`，不是 Lazy** → `PermissionsPage`（~65 个工具全量非懒，
  点一行 `permTick++` 整列重组）、`ConfigPage:671`（32 个 provider 非懒 + 每个字符重跑 fuzzy 排序）、`WakePage:101`。
  ✅ 参照物就在仓库里：`OnboardingPage.kt:693-745` 已经是拍平 + LazyColumn + key + contentType 的正确版本。
- `ChatAppearancePage.kt:313` 给 **`staticCompositionLocalOf` 传新实例** → 整棵子树重组（正是 `DESIGN-CHAT-PERF.md` 第八节记的坑）。
- `compose_stability.conf` 漏了 `com.arix.tool.PackageDef`。

### 发送路径的固定开销（🔶）

- `ChatScreen.kt:652`：**每轮工具循环在主线程重建整张工具表**（~65 个工具排序 + 逐个建 JSONObject + 逐个查包开关走 prefs）。
- `ChatScreen.kt:1414-1432`：拼系统提示在主线程做 **binder 调用**（AppOps / 通知监听清单 / `getPackageInfo`，
  30s TTL 过期就重探，未装终端时每次还抛一次异常）。
- `MemoryTool.add` 会 **await 一次 embedding 网络往返**（`MemoryManager.kt:386` 的 `scanSimilar`）→ 直接进发送时延。

### 今天新加的三块（🔶 复核结论）

- **`McpGateway` 缓存：没找到雷**，纯内存、无锁但最坏只重算两次、无网络无 IPC。它反而把原来每轮两次全表扫降下来了。
- **冲突消解**：触发条件收得很紧、不阻塞写入返回。唯一问题是 `embedScope` 无并发上限——
  一次自动抽取写 N 条就可能 N 个 LLM 请求同时飞。→ 加 `limitedParallelism`。
- **`MemoryTidy`**：调度路径没问题；问题是**缺网络约束**（见 P3）+ 一轮三次全表 `SELECT *`。

---

## 五、建议的开工顺序

1. **止血（半天，收益最大）**：T1（`llmPickHits` 白烧）、P3 里 `MemoryTidy` 补网络约束、F4（主线程解码图片）。
2. **纯删的体积（半天）**：S1 + S2 → 47.18 → ≈39.3MB。做完重打一次 release 量确认。
3. **热路径漏用（一天）**：F1 / F2 / F3 / F5 —— 全是"换个已有 API"，风险低、收益直接。
4. **token 瘦身（一天）**：T3 + T4（**要先过你的眼**，是提示词）、T5 的四处抖动源逐条验。
5. **功耗（要你先定方向）**：P1 唤醒策略暴露成设置 + 接上息屏/充电判据；P2 无障碍掩码收窄。
6. **之后**：Room 索引 + 迁移 21、Compose 那批、S3/S4（要真机验语音与唤醒）、S5（要你定取舍）。

---

## 六、等你拍板的

| # | 事项 | 为什么卡在这 |
|---|---|---|
| 1 | **T3/T4 两处提示词瘦身** | 提示词改动要过你的眼 |
| 2 | **默认开的功能包砍不砍** | 产品判断。关掉≠砍功能（模型可申请开），但默认值是你的 |
| 3 | **S5：`.so` 压缩** | 下载 −23MB，装机 +14MB。手表上这笔账怎么算是你的取舍 |
| 4 | **P1：唤醒默认走哪一档** | 涉及"MIUI 上还能不能后台持麦"这条产品底线，不是纯技术判断 |
| 5 | **T2：元任务用什么模型** | 引导用户配一个便宜模型，还是给元任务加 `max_tokens` 上限 |

---

## 七、给下一个窗口的指路（**先读这节，别上来就全项目扫**）

> 这节的唯一目的：**让新对话不用重新摸一遍项目**。摸底已经花过一次钱了，别再花第二次。

### 7.1 直接用现成的，别重新扫

| 想知道什么 | 用这个 | 别做什么 |
|---|---|---|
| 某段代码怎么工作 / 谁调谁 | **`codegraph explore "<符号名或问题>"`**（仓库有 `.codegraph/`） | 别 grep + Read 循环 |
| 每轮工具 schema 花多少 token、按包分布 | `python tools/token_cost.py default` | 别手数 |
| APK 里什么最占地方 | 解 `app/build/outputs/apk/release/app-release.apk` 用 python `zipfile` 按前缀聚合 | 别装第三方分析器 |
| .so 之间谁依赖谁 | python 读 .so 二进制里有没有对方的 soname 字符串（够用） | 别找 readelf（Windows 上没有） |
| 本轮已查明的事实 | **本文件**（✅ 标记的都核过） | 别重查 |
| 历史决策的理由 | `OPEN-QUESTIONS.md`（Q1–Q64，含"怎么才算判定了"） | 别翻 git log |
| 聊天页性能已经做过什么 | `DESIGN-CHAT-PERF.md`（尤其第十一节） | 别重扫 Compose |
| 唤醒的设计意图 | `DESIGN-WAKE.md`（⚠ §3.1 的策略表**与代码现状不符**，见 P1） | —— |

### 7.2 已经查明、**别再查**的（省钱清单）

- 包体构成、每个 .so 的字节数、assets 明细 → 见第一节表，**实测**。
- `libsherpa-onnx-jni.so` 只依赖 `libonnxruntime.so`；全项目只 `loadLibrary` 三个库 → **S1 可以直接动手**。
- `wake/src/main/assets/models/` 是空目录，全项目零个 `.tflite` → **S2 可以直接动手**。
- `abiFilters` 已经只打 arm64（三个模块都是）→ **这条没有剩余空间**。
- `res/raw` 零个、`getIdentifier` 只有 2 处且取的是系统资源 → **shrinkResources 的收益 <0.5MB，别再评估**。
- 冷启动的 `XtomApp.onCreate` / `MainActivity.onCreate` / Room 迁移 / 各 `init()` → **都干净**，别重看。
- 零 `WakeLock`、零周期 `AlarmManager`、零高频广播、传感器/定位/BLE/健康都正确反注册 → **别重扫**。
- `hazeEffect` 已经不在了，玻璃是"预模糊一次画位图"，零每帧开销 → **玻璃定案，不许动，也别再分析**。
- `ui/FrameMotion.kt` 三个循环都会自己停 → 干净。
- 历史压缩的阈值在短对话下够不着 → **调那几个常量没用，别试**。
- `McpGateway` 缓存没雷 → 别重审。

### 7.3 还**没**查的（下一轮真正该挖的）

按"该先挖哪个"排。每条都给了起点，别从零找。

1. **内存占用完全没查**（手表上和功耗同等重要，这轮一个字没碰）。
   起点：同进程里同时住着 sherpa-onnx + ORT + PDFBox + Coil 缓存 + 终端仿真器。
   `XtomApp.kt:61-67` 的全局 `ImageLoader` **没配 `memoryCache` / `bitmapConfig`**（全项目零命中），
   手表堆很小 → 先量 `Runtime.maxMemory()` 与实际峰值，再谈调参。
2. **网络流量没查**（直接影响手表流量与功耗）。起点：元任务的请求体积、图片入站压缩档位（`OPEN-QUESTIONS.md` Q7）、
   备份自动上传（`MainActivity.kt:394` 每次开 App 四个网络动作）。
3. **T1 的严重度要真机量**：`MemoryInjection` 那条 1500ms 超时到底是"经常超时"还是"偶尔超时"，
   决定了是放宽超时还是换掉兜底。**这是全篇最值钱的一个数字。**
4. **P1 的前提要复核**：`WakeConfig.kt:22-26` 说"MIUI 上只有 ALWAYS_ON 能后台持麦"——**这条今天还成不成立？**
   它是整个功耗方案的支点，不成立的话直接切回 `WINDOWED` 就完事了。
5. **token 估算口径本身没验**（`OPEN-QUESTIONS.md` Q8）：`tools/token_cost.py` 用的是中文 1.2 字/tok。
   拿一次真实响应的 `prompt_tokens` 对账，**否则本文件所有 token 数字都是同一个假设下的相对值**。
6. **dex 5.34MB 没拆**（R8 已混淆，包级归因不可靠）。相对 native 栈是零头，**优先级最低**。
7. **性能那批 🔶 条目里，这几页是"模式扫描"不是逐行读，置信度低**：
   `BrowserPage / SttPage / TtsPage / MonitorPage / WorkflowPage / DialogSettingsPage / SearchSettingsPage /
   ToolKeysPage / CompanionSettingsPage / theme/*`。要动它们先自己读一遍。
8. **四条线之间的互相拉扯没算过**：`.so` 压缩省下载却涨装机；按需下载省包体却费流量和首启时间；
   元任务改用小模型省钱却可能降质量。**这轮是四条线各自最优，没做过全局权衡。**

### 7.4 这轮方法上的片面（下轮别重蹈）

- **每条线只有一路 agent、没有对抗性复核**。四份报告里凡是我没标 ✅ 的，都只经过一双眼睛。
- **全程零真机数据**。所有"耗电高/慢"都是结构性推断——谁在跑、多频，不是量出来的。
- **没有横向对照**：竞品在同样这四条线上是什么水位，一次都没看（`RESEARCH-COMPETITIVE-2026-07-27.md` 里有六个竞品的源码级材料，可复用）。
- **只看了"哪里胖"，没看"改完会不会更差"**：比如收窄无障碍事件掩码会不会让 GUI 自动化漏事件，
  这类**回归风险**一条都没评估。

---

## 八、这份计划**没有**做到的

- **一个真机数字都没有**：没有耗时、没有耗电、没有真实 `prompt_tokens` 对账。全部是结构性判断。
- 🔶 标记的条目是子 agent 报的、我没逐条核；⚠️ 标记的是纯推断。**动手前先验，别照着改。**
- dex 那 5.34MB 没深挖（R8 已混淆，包级归因不可靠，而且相对 native 栈是零头）。
- 没有量过「记忆表/会话表的真实行数分布」——好几条 Room 结论的严重度取决于它。
