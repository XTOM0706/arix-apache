# 外部记忆/代码图谱项目调研 · 2026-07-26

> 调研目的：这些项目值不值得集成进 Arix，或有什么设计值得借鉴。
> **本文分三区：① 已核实事实 ② 待核实（subagent 报告，可信度存疑）③ 设计方案。**
>
> **2026-07-27 更新：②区 Hermes 那一节已全部核实完毕（见下方逐条打勾），并订正三处错误。
> 更完整的续篇（grok-build 记忆架构、六家安卓竞品源码级对照、跨项目重复出现的设计）
> 见 `RESEARCH-COMPETITIVE-2026-07-27.md`。**

---

## ⚠️ 可信度说明（先读这段）

调研用了 subagent。其中「Obsidian 自增长记忆库」那一路在报告里自称
"第一个 agent 返回了""还有一路在跑"——**但任务通知机制只在无存活子任务时触发，
该自述与事实矛盾**。因此该 agent 报告中的**所有具体数字、issue 编号、公式一律标记为待核实**，
不得直接引用。tolaria 那一路的数字同样未经本人核实。

已核实 = 本人用 `curl` 打 GitHub API 或用 codegraph/Read 读过源码。

---

## ① 已核实事实

### codebase-memory-mcp（DeusData/codebase-memory-mcp）
本人 curl 核实：

- 纯 C / MIT / 35,484★ / 2,767 fork / **349 open issues**
- 创建 2026-02-24（仅 5 个月），最新 v0.9.0（2026-07-08），今天仍在 push
- release 产物：darwin/linux/windows × amd64/arm64，**没有 Android 目标**；单包压缩 35MB
- 高热度 open issue 标题（原文）：
  - `Memory leak: process grows to 50+ GB virtual memory over hours/days, crashes Windows`
  - `task: Windows platform issues (8 bugs)`
  - `Index Error: outcome=killed exit_code=-1 signal=9`
  - `never finishes indexing large python project` / `Very high CPU usage` / `Crash when calling query_graph`
- 今日 commit 含多条 `revert(...)`，以及 `fix(store): drop the SQLite page-cache slab that faults on arm64`

**结论：不集成。** 无 Android 构建；35MB 二进制 vs Arix release APK 14.5MB；
RAM-first 索引在手表必 OOM；arm64 路径今天才修崩溃；**且场景不匹配**（手表上没有代码仓库）。
开发用途也不需要——`E:\OnyxAI\.codegraph` 已覆盖。

可白嫖的思路：Louvain 社区聚类 → 对应 TODO-V1【记忆簇】的合并管线。仅思路，不引依赖。

### hermes-agent（NousResearch/hermes-agent）
本人 curl 核实：

- Python / MIT / **221,048★ / 42,166 fork**
- 创建 2025-07-22，今天仍在 push
- ~~25.5k 未关闭 issue ⇒ triage 已崩~~
  **❌ 订正（2026-07-27）**：`open_issues_count = 25,637` **含 PR**。拆开是
  **真实 open issue 8,163 + open PR 17,474**。8k issue 仍然多，但性质不同：
  不是 triage 崩，是社区 PR 洪水没人合。**结论不变：不装。**

### Arix 自身（codegraph/Read 读过源码）

- **许可证**：AGPL-3.0-only（`LICENSE` + `LICENSE.md`，附 §7(b)(c) 署名/标明修改条款）
- **MCP 接入已就绪**：`OperitCompat.loadLocalPackages()`（`OperitCompat.kt:169-196`）扫
  `filesDir/operit_mcp/*.json`；有 `command` → `StdioMcpRegistry.discover()` 起子进程（stdio）；
  有 `url` → `McpTool.discoverTools()` 走 HTTP/SSE，支持 Bearer/Basic/自定义 header
- **深度注入机制已有**：`injectWbDepth()`（`ChatScreen.kt:477`）酒馆式按深度把内容并进
  倒数第 N 个 user 回合。**状态卡可复用这条路**（见第③区）
- **记忆系统现状**（`MemoryManager.kt` / `MemoryTool.kt`）——比预期完整得多：
  | 能力 | 现状 |
  |---|---|
  | 单工具多动作 | `MemoryTool` 一个工具带 `action`：search/add/update/delete/link/pin |
  | 带类型带权重的关系图 | `MemEdges`（related/causes/part_of + weight），**Hermes 没有** |
  | 向量检索 | 可配 embedding 模型、`backfillEmbeddings` 批量、换模型自动重算维度 |
  | 模糊检索 | `FuzzyMatch.rankBy` 补错字/词序/缺字 |
  | 标题去重 | `upsertByTitle` |
  | 满了自动压缩 | `MemorySalvage.autoCompress`（确定性去重+删临时） |
  | 其他 | importance / pinned / characterCardId / tags / source / lastAccessedAt |
- 关键行号：`MemoryTool.kt:106-109`（逼近上限→autoCompress→仍满→**返回"请手动整理"给用户**）；
  `MemoryTool.kt:92-95`（`searchMemories` 吐 title + **完整 content**）；
  `MemoryManager.kt:97-113`（`search` = `exact + fuzzy` **顺序拼接**）

### tolaria（refactoringhq/tolaria）— 仅许可证一条本人推定
- 它是 AGPL-3.0-or-later，Arix 是 AGPL-3.0-only ⇒ **方向兼容，可借鉴其代码**
  （"or-later" 允许选 v3）。商标政策另算，不能用其名称/logo。
- 其余数字（18,994★ 等）**未核实**。
- 它是 Tauri 桌面 Markdown 笔记应用，无 Android ⇒ 不集成。

---

## ② 待核实清单（subagent 报告，明天逐条打 API 验）

### Hermes 记忆机制（**2026-07-27 已全部核实**）
- [x] `~/.hermes/memories/` 两文件：`MEMORY.md` 2200 字符、`USER.md` 1375 字符
      → `tools/memory_tool.py:167` `def __init__(self, memory_char_limit=2200, user_char_limit=1375)`
- [x] 注入带容量表头 `[67% — 1,474/2,200 chars]`，条目 `§` 分隔
      → `memory_tool.py:744` `f"{...} [{pct}% — {current:,}/{limit:,} chars]"`；`ENTRY_DELIMITER = "\n§\n"`
- [x] `memory` 工具只有 add/replace/remove，**无 read** → `memory_tool.py:937`
- [x] 超限返回 error + `current_entries` + `usage`，要求同轮内整理后重试；**刻意不自动摘要**
      → 原文见 `RESEARCH-COMPETITIVE-2026-07-27.md` ⑤，可直接抄的文案
- [x] 会话启动冻结快照注入 system prompt，中途不变（为保 prefix cache）
      → `agent/system_prompt.py` 三层 stable/context/volatile + 注释原文；
      **时间戳只到「天」不到分钟**，理由是分钟级会打碎 prefix cache KV（PR #20451）
- [x] 第二层：全会话存 SQLite + **FTS5**，`session_search` 返回原始消息不摘要
      → `tools/session_search_tool.py:8-28`，三模式；且专门 `_is_compaction_summary()` 给机器摘要降 BM25 权重
- [x] `SOUL.md` 占 system prompt slot #1 → 官方 personality 文档
- [ ] ~~上下文压缩双层 50% / 85%，压缩新建 session 用 `parent_session_id`（称是 bug 源 #23811）~~
      **❌ 订正**：#23811 与 `parent_session_id` 无关。实际标题
      `ContextCompressor inflates small sessions, causing rapid re-compression and splits`：
      `_MIN_SUMMARY_TOKENS = 2000` 硬编码地板 → 小会话摘要比原文还大 → 越压越大 →
      用户每约 20 分钟裂一次会话。**这条对我们更有用**（见下方「新增」）
- [x] 安全审计 issue #7826 报 4 Critical + 9 High；默认 ALLOW-ALL
      → 至今 **open**，P2，0 评论 0 反应

**新增（原清单没有）：**
- **Hermes 有 CJK FTS5 解法**：`native/fts5_cjk` C 扩展（unicode61 + CJK bigram，Lucene CJKAnalyzer 语义），
  README 说是修「1-2 字中日韩词落回 LIKE 全表扫」。Android 不能加载 SQLite 扩展 ⇒ 这条路我们走不了，
  但 **RikkaHub 用「换一个自带 jieba/simple 分词器的 SQLite 打包」解决了**，见续篇 ⑤
- **只允许注册一个外部 memory provider**，第二个直接拒，理由 "prevents tool schema bloat and
  conflicting memory backends"（`agent/memory_manager.py` 开头）—— 对我们「工具精简」原则的独立印证
- **我们自己的 `ContextCompressor.maybeCompress` 有 #23811 同款病的雏形**：
  把 `prev.text` 喂回去再摘要，摘要长度无上界、也不判断新摘要是否比被替换内容短

### basic-memory（basicmachines-co/basic-memory）（**整体存疑，优先核实**）
- [ ] 仓库确实存在、star/license/活跃度
- [ ] 全代码库无 embedding 近重检测、无实体归并、无 merge 算法；去重外包给 `skills/` 散文提示词
- [ ] 设计原则原话 "Agents propose memory; they don't silently create it"
- [ ] 晋升阶梯 `raw → summarized → candidate → accepted/rejected` 已定义但**只发布 raw**
- [ ] LoCoMo 自测（issue #950/#951，1986 query，vs mem0 2.0.5）：
      BM recall@5 0.733 / recall@10 0.839 / MRR 0.619 / 45ms / p95 53ms
      mem0 recall@5 0.791 / recall@10 0.891 / MRR 0.648 / 882ms / p95 1603ms
- [ ] 失败归因：独漏 281 条里 ~48% 是**排序失败**（正确答案在第 6-8 名），
      其余是**跨会话实体混淆**（专有名词权重压不过泛化语义相似度）
- [ ] 分数融合公式 `score = max(vec, fts) + 0.3 * min(vec, fts)`（非 RRF）
- [ ] 时间衰减提案 #603 卡在提案阶段，原因是"文件即真相"导致 per-fact 状态无处安放
- [ ] `search_notes` 只回标题 + permalink + 截断 200 字符片段；session-start 注入硬上限 10000 字符
- [ ] PreCompact 检查点纯确定性抽取：首条 user + 最后 3 条，截断 300/200，跑 `detect-secrets`，零 LLM
- [ ] `created_by`/`last_updated_by` 在本地 CLI 使用时全为 null
- [ ] #59/#124 仍开着（AI 写入不可撤销，社区要 git diff 兜底）

**注**：即便这些数字全是编的，第③区里那几条设计判断（分数融合优于顺序拼接、
两段式召回、per-fact 状态需要 DB 而非文件）**在原理上独立成立**，可以不依赖它们。
但**不要对外引用任何具体数字**，直到核实。

---

## ③ 设计方案（基于已核实的 Arix 源码，可独立于第②区成立）

### 核心判断：「自增长」≠ 记忆库越长越大

正确形态是 **归档层无限增长 + 常驻注入层恒定不变**。
增长发生在"能查到的东西越来越多"，不是"每轮塞进去的越来越多"。

### 现状的四个真问题

1. **常驻层与归档层没分开** —— `MEMORY_MAX_COUNT` 限的是整张 memories 表。
   限错了对象：该无限增长的那层被限死，该限死的那层（每轮注入量）没限。
2. **限制单位是条数不是字符** —— 一条可以 10 字也可以 500 字，<300 token 目标控不住。
3. **满了把球传给用户** —— `MemoryTool.kt:109` 返回"请手动整理"。用户在手表上没看屏幕。
   应传给**模型**：返回当前条目 + 用量 + 要求同轮内合并后重试。`autoCompress` 保留在前面跑。
4. **改一条要重发整段** —— `update` 传整段 `content`。应加 `old` 唯一短子串参数
   （合并进现有 action，不新开工具，守「工具1用多」原则）。

### 注入结构

```
system prompt 头部（固定前缀，永不变）
  └─ 角色卡人格层

尾部深度注入（走现成的 injectWbDepth）
  ├─ 常驻事实块  [记忆 68% · 1420/2100 字]
  ├─ 状态卡      （关掉 → 整段不出现）
  └─ 环境信号    [2026-07-26 15:20 · 电量 42%]
```

- 前缀不动 → prefix cache 全程命中；可变全在尾部 → **开关切换零缓存代价**
- 容量表头是**纯数据**，不告诉模型该怎么办（守「注入只给数据」原则）
- **状态层关掉时不要注入空壳卡**：`relationship_level: 0` 会被读成"熟悉度就是 0"这个事实，
  比不注入更糟。要么整段不出现，要么显式写"状态层未启用"。零值默认 ≠ 缺失。
- 连带：状态层关掉后 `pending_question` 悬空；确认滚动摘要是否与状态卡折叠共用同一次
  LLM 调用——若是，关状态层不能把摘要一起关掉，得拆开。

### 检索改造（这条同时修「记忆归属错」bug）

把 `MemoryManager.search` 的 `exact + fuzzy` **顺序拼接**改成**分数融合**：
三路（LIKE / FuzzyMatch / embedding）归一成可比分数。顺序拼接的问题是两路分数
没有可比性，拼出来的顺序不代表相关性。

**关键**：融合时给 `characterCardId` 匹配一个硬性加权。纯语义检索没有归属维度，
这正是"专有名词权重压不过泛化语义相似度"导致实体混淆的地方——而 Arix 的记忆
天生带角色卡归属，这是结构性优势。

同一个打分函数**两用**：召回排序 + 常驻层选取/淘汰。

### 别做的事

- **别往 Markdown 迁存储**（结论不变，但 **2026-07-27 理由订正**）。
  ~~文件即真相的项目做不到时间衰减，正是因为这个~~ ❌ 这句是错的。
  grok-build 就是「Markdown 文件当真相源 + SQLite 只当可重建索引」，
  `chunks` 表照样带 `access_count`/`last_accessed`/`created_at`，时间衰减照做
  （`e^(-λ·age_days)`，`half_life_days=7`，且只对 session 来源生效、global/workspace evergreen 豁免）。
  **正确的理由是**：手表上多一层文件同步没收益、SQLite 已经是真相源、迁移是纯成本。
  这个区别很重要——它意味着**「用户可读可编辑的记忆文件」和「有状态的记忆」不冲突**，
  将来要做「记忆导出成 Markdown 给用户改」不必推翻现有架构。
- **别做"每条都等用户审"的晋升阶梯**。卡点不是技术是审查量。
  做成「默认写入 + 事后可见可撤销」，日志留着但不拦路。Arix 有 `GitHubBackup`，这条路好走。
- 别抄 Hermes 的外部 memory provider 插件体系 / 多趟 dialectic LLM 推理（手表算力/延迟/流量不允许）。
- 别抄它压缩时新建 session 用 `parent_session_id` 的设计。

### 落地顺序

1. 加容量表头（一行字符串，立刻能观察模型行为变化）
2. 常驻层限制单位改字符（<300 token 承诺的前提）
3. 满了报错给模型而非用户（改 `MemoryTool.kt:106-109` 返回值）
4. `update` 加 `old` 子串参数（可选参数，向后兼容）
5. 拆开常驻层 / 归档层（最大，动 `MEMORY_MAX_COUNT` 语义 + 注入路径；排在状态卡之前）
6. **打分融合 + 角色卡加权**（修已知 bug，不只是优化，可提前）
7. 两段式召回：`searchMemories` 截断到 ~200 字符，正文让模型按需再拉
8. ~~FTS5 虚拟表（作为融合的一路，不是独立一级）~~
   **⚠️ 2026-07-27 订正：对中文基本不成立。** 标准 FTS5 默认 `unicode61` 分词器不切中文，
   一整串连续汉字会变成一个不可分 token（grok-build 就是活标本，它中文只有向量通道在工作）。
   Android **不允许加载自定义 SQLite 扩展**，所以 Hermes 的 `fts5_cjk` C 扩展那条路走不通。
   可行的只有两条：① 换成 RikkaHub 那种自带 jieba/simple 分词器的 SQLite 打包
   （`com.github.rikkahub:sqlite-android`，代价是 native 体积，**要先量 APK**）；
   ② 应用层写入时做 bigram 切分存一列。
   **建议：这步先搁置，预算优先给第 6 步（融合打分）。**
9. 抽取式快照与 LLM 滚动摘要并存（防折叠漂移；折叠是复合运算，漂移会累积）
10. 记忆变更日志（不拦路，事后可撤销）

---

## ④ 动手前必须先查清的（**2026-07-27 已查清，结论见下**）

> ✅ **已破案**：`performSend` **根本没有记忆注入**，整个 `ChatScreen.kt` 没有一处调 `queryRelevant`。
> 记忆唯一进入对话的路径 = 模型自己想起来调 `memory` 工具的 search。
> ⇒ 原第 7 步「两段式召回」不是要做、是已做到最极端（连索引都不给，全场唯一）；
> 原第 5 步「拆常驻/归档层」不是拆、是**从零建常驻层**。
> 且 `staticSys | 【当前上下文】volatileTail` 的前缀缓存分段我们早就做了，优于 Hermes 的整段冻结。
> 常驻记忆块应加在 `volatileTail` 里（不破坏静态前缀）。完整分析见
> `RESEARCH-COMPETITIVE-2026-07-27.md` ⑫⑬⑭ 区。下面是查清之前的原文，留档。

### （原文·已过时）

**`performSend` 里记忆到底怎么自动注入进每轮提示词的**——本次 codegraph 返回的是
`runWithToolLoop`，没拿到 `performSend`（`ChatScreen.kt:527`）的系统提示拼装段。
第 5 步动手前必须看清现在注了什么、注在哪、注多少，否则容易改出重复注入。

已知：`runWithToolLoop` 把整串 `sysPrompt` 传给 `client.streamChat`；
历史压缩走 `ContextCompressor.forSend(context, convId, msgs)` 单独处理。
