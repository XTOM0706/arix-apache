# Arix · 第一个正式版路线图（全量待办）

> 来源：用户 `Downloads/markDown…md`（35 功能 + 5 bug）+ 后续讨论追加，合并 `TODO-REFACTOR.md` 结构级/下一轮项，去重按主题簇 + 优先级整理。
> 记忆/陪伴的设计方向单独见 **`DESIGN-MEMORY.md`**（交互状态引擎 + 环境上下文 + 续接上下文）。
> `↔` = 两份文档重叠项（已合并）。

## 优先级图例
- **P0** bug，功能之前先修 · **P1** v1 核心 · **P2** v1 加分 · **P3** v1 之后

---

## 🐞 P0 — Bug
- [x] 引用图片等附件，刷新对话后消失（数据持久化）｜复制进 app 私有目录存 file:// 路径：`ChatScreen.kt` persistAttachments()（filesDir/chat_attachments/$convId，规避相机 cacheDir / picker content URI 权限失效）
- [x] **多模态路由 400**：把视觉模型设为「图片识别」用途后，给纯文本模型发图片报 HTTP 400。
      → 已修：模型配置加 `supportsVision`/`supportsAudio`/`supportsVideo` 能力位（`ApiConfigEntity` + `CloudApiConfigManager` add/update + `ConfigPage`），图片消息路由到视觉模型，不支持时友好提示替代裸 400。
- [x] 部分设备/系统闪退（用户确认已修；如再现从崩溃报告页取日志）
- [x] 记忆自动抽取归到通用记忆而非角色卡（按 characterCardId 落库 + `MemorySalvage.kt` 补救侧）→ 见【记忆簇】+ `DESIGN-MEMORY.md`
- [ ] 记忆无法开新对话“他还是他”（人格延续）→ 见【记忆簇】+ `DESIGN-MEMORY.md`（状态卡 `InteractionState.kt` 实验中）
- [ ] 「待测」占位（用户补）

## 🚀 发布与合规（P1 · 若要把 v1 发到 GitHub，这是拦路项）
- [ ] **去衍生化 / 原创性审计**：确认本项目**不是任何项目（尤其 Operit）的衍生作品**。
      重点分清：`OperitCompat`/`CloudMarketplace`/`ImportConverters` 是**「读其格式做互操作」**（合法）还是**「搬了源码」**（有风险）→ 搬了的要重写/剔除。
- [ ] 选定 License，检查所有依赖 License 兼容，可干净发布 GitHub。
- [ ] 「关于软件」页承载 版本 / License / 开源致谢(OSS notices)（正好服务合规）。

## 🧠 记忆簇（P1，头号 · 详见 `DESIGN-MEMORY.md`）
- [ ] **交互状态层（状态卡）**——本簇核心：事实/人格之外新增 interaction state，解决“他不像他”
- [ ] **记忆智能压缩合并**：对话→抽取→记忆数>上限−30→删临时(低权少调用)→AI 合并重要记忆→取权重均值重设权重 ↔ 上下文上限/自动摘要
- [ ] **对话归档（生成总结摘要）**：归档时自动产出摘要；**该摘要与「续接上下文」的会话摘要复用，一份两用**
- [ ] **未定义记忆归档**：把未分配角色卡的通用记忆，走一个「分配一个角色卡给它」的流程（= 修记忆归属 bug 的补救侧）
- [x] 记忆抽取归属修正（抽取时按当前会话 characterCardId 落库）= bug〔已修〕
- [ ] 开新对话人格延续（角色卡绑定记忆 + 状态卡注入）= bug
- [ ] 记忆图谱可视化（节点/连线；DB 已有 memory_tags / memory_tag_cross_ref）

## ⏱️ 环境/传感上下文（P1 · 详见 `DESIGN-MEMORY.md` 第七节）
- [x-决策] **所有消息都带时间数据**（已定，Level 1，随发送注入）
- [ ] 电量注入（`BatteryManager`，零权限）→ 体贴行为（深夜放缓/低电量精简）
- [ ] 健康信号（Level 2，P2）：心率/步数/睡眠（需 `BODY_SENSORS` / Health Connect + 开关授权，按本机确认接口）

## 🏗️ 前端骨架（P1，承接已完成的 11 页表现层迁移）
- [ ] **修路由**：MainActivity 字符串路由 + 两平行 when → 单一 sealed route 模型（含标题）
- [ ] 全屏 inset 单一真源（替换魔法 58dp + `displayCutoutPadding` 防挖孔）
- [ ] **「个性化」入口留位置**（调 UI 配色 / 聊天背景用）——先占导航/设置入口
- [ ] **「关于软件」入口留位置**（版本/License/致谢，见发布合规）
- [ ] 用户自定义 UI/配色/背景 ↔ 主题系统（亮/暗/自定义色）+ 字体大小/密度
- [ ] 补全动画
- [ ] 清理 root 与 `ui/` 双份聊天组件
- [ ] `MarkdownText.kt` 7 处写死色收拾（具名颜色表保留）
- [ ] 完整的 markdown 图片/视频引用（参考豆包）
- [ ] 多语言 i18n（目前全中文硬编码）

## 🔌 API 与模型（P1–P2）
- [ ] 再次补充免费文本 API
- [ ] 密钥池（多 key 轮换/容错）
- [ ] 优化 token 消耗 ↔ 上下文上限/自动摘要（与记忆簇联动；状态卡目标 <300 token）
- [ ] 每功能模型绑定（title/STT 校正/图片识别等各绑各的模型）
- [ ] 再次适配/补全/优化各种 API：识图 / 视频生成 / 音频识别 / TTS / STT
- [ ] 增加免费 TTS / STT
- [ ] 再次补全跟进 Operit 包（注意与「去衍生化」审计一致）
- [ ] Enter 发送可配置（把发送动作从大内联 lambda 抽成可调用函数）
- [ ] 消息队列（处理中排队）
- [ ] 更多附件类型（相机/位置/通知/记忆/应用包）

## 🔍 搜索 —— XSEARCHING 1.0（P2，大件）
- [x] **XSEARCHING 1.0** 多轮研究循环（已实现：`deep_search` 工具｜多引擎并发→LLM评估→扩子查询→综合报告+引用[n]+置信度；引擎含 AnySearch/Perplexica；研究模型可自选、轮数可配）：
  - 输入：用户查询 + 可选约束（语言/时效/深度）
  - 循环（≤N 轮）：①多引擎查询(Google/Bing/Brave/DuckDuckGo/国内) →②相关性+新鲜度初评 →③置信不足则 LLM 联想扩展子查询 →④热度过滤(TF-IDF/Embedding/LLM 打分) →⑤合规抓取 →⑥清洗+总结 →⑦判断是否下一轮
  - 输出：结构化 JSON + 来源引用 + 置信度
- [◐] **修网页工具 + 防 403**：已做 `fetch` 直连被拒(403)时走 AnySearch `extract`(服务端浏览器式抓取)兜底；robots 尊重已在。待补：UA 轮换 / WebView/无障碍渲染兜底链。

## 📱 设备控制（P2–P3，agentic）
- [ ] 无障碍 / shell / su 操控设备 ↔ 权限阶段2（Shizuku/root/无障碍 `ShellExecutor` 工厂）
- [ ] 纯对话控制设备（自然语言→设备操作）
- [ ] 导航 API
- [ ] termux 终端插件
- [ ] 屏幕 OCR 悬浮（MediaProjection + OCR）
- [ ] 悬浮窗助手（WindowManager overlay + 前台服务）

## 🤖 Agent 能力（P3）
- [ ] AI 创建子 agent
- [ ] AI 列出 todo
- [ ] AI 自己给自己制作能力
- [ ] AI 高权能：对话中直接改角色卡 / 世界书 / UI / 配色
- [ ] AI 主动发消息 ↔ 工作流定时任务（AlarmManager/WorkManager）

## 💬 消息与通信（P2–P3）
- [ ] 语音唤醒界面（更完整的唤醒管理 UI；WakePage 已迁）
- [ ] AI 朗读消息（TTS）
- [ ] AI 发送语音消息（TTS→音频消息）
- [ ] 发送表情功能
- [ ] 以图片形式转发消息 ↔ 分享为图片（Compose graphicsLayer 截图）
- [ ] 端口发包代回消息（QQmax / Telegram 等桥接）
- [ ] 跨设备互联，同步一切数据
- [ ] 聊天：多选模式、分支/消息变体（需会话树数据模型）

## 🧰 工具箱 & 其他（P3）
- [ ] 内嵌浏览器 + 书签/历史（WebView）
- [ ] 代码编辑器工作区、远程电脑屏
- [ ] 工具箱：SQL 查看 / FFmpeg / UI 调试器
- [ ] 「待补充」占位（用户补）

---

**优先级速览**：先修 P0（4 bug，含多模态 400）→ P1 三条主线并进【记忆簇 + 环境上下文 / 前端骨架修路由 / 发布合规去衍生化】→ P2（XSEARCHING、健康信号、API 适配）→ P3。
