# letta-ai/claude-subconscious 剖析（2026-07-30）

> 结论先写：**云依赖不能要，形状值得抄一部分，其中一条已经做了。**
> 材料来自仓库原文，不是二手摘要。它是 MIT，法律上可抄（对比：Eta 改过禁商用、HermesApp 是未授权拷贝）。

## 一、它是什么

`letta-ai/claude-subconscious` · MIT · TypeScript · 约 2.8k 星 · "Give Claude Code a subconscious"

解决的问题一句话：`Claude Code forgets everything between sessions`。

**架构**（本机跑 Claude Code，记忆 agent 跑在 Letta Cloud 或自托管 Letta server）：

| Hook | 同步/异步 | 干什么 |
|---|---|---|
| `SessionStart` | — | 通知 agent，建一个 conversation |
| `UserPromptSubmit` | **同步** | 从 stdout 把记忆注进 prompt 之前 |
| `PreToolUse` | **同步** | 用 `additionalContext` 在**工具调用前**再注一次 |
| `Stop` | **异步** | spawn 一个 detached worker，把整段 transcript 发给 Letta SDK |

**记忆 = 8 个固定可重写块**：`core_directives` `guidance` `user_preferences` `project_context`
`session_patterns` `pending_items` `self_improvement` `tool_guidelines`。
**块由那个后台 agent 重写，Claude Code 侧只读。**

不走 MCP，也不写 CLAUDE.md。

## 二、两条硬事实（决定了我们能拿什么）

1. **必须有 `LETTA_API_KEY`**（Letta Cloud，或自托管一个 Letta server）。
2. 作者原文：`is a demo app built using the Letta Code SDK, **and is not intended to be used in production**`。
   另外自己写着首次使用要几个 session 攒够信号才有用。

## 三、不能拿的：那个云依赖

记忆是 Arix 的核心，不能租。这直接撞「第三方 API 不破坏独立性」那条线——搜索可以外挂（拿不到就降级），
记忆外挂等于产品的心脏放在别人机房里。它是 MIT 能抄代码，但它是 TypeScript + Letta SDK，
**没有可直接搬的代码**，值钱的只是形状。

## 四、我们已经有的（先查过再写，别重复造）

- `LessonRecorder` 注释里明确的设计取舍：**确定性写入、不调模型**。理由是失败信号本身就是结构化的
  （哪个工具、哪类失败），再花一次 LLM 往返「既慢又可能把事实写歪」。
- `MemoryTidy`：已经是一个后台整理层，有「后台自动整理」开关、拿不准的停进**待确认列表**等用户拍板、有回收站。

所以「**同步只读 / 异步重写**」这个形状 Arix 已经有了。差别在**范围**：`MemoryTidy` 只盯
`LessonRecorder` 沉下来的教训、评估它还有没有用；不是「读完整段对话、重写一批槽位」。

## 五、值得拿的三条

### 1. ⭐ `PreToolUse` 那个注入点 —— **已实现（2026-07-30）**

这是它和我们最实的差别：我们只在**回合开始**注入一次，它在**每次工具调用前**再注一次。
而 `LessonRecorder` 攒的恰恰是「上回这个工具这么调不行」，那条教训最该在调这个工具的前一刻出现。

**我们的落法和它不一样，而且更省**：它靠 hook 往上下文里塞一段；我们直接把教训接在
**那个工具自己的 schema description 后面**（`ToolManager.withLessonHint`）。因为 schema 每轮现读，
模型是在「正在读这个工具的定义、正在决定要不要调它」的那一刻读到的——同一个位置，但不额外占一段上下文，
而且只有**真踩过坑的那几个工具**多出一句，没踩过的一个字都不多发。

落点：
- `LessonRecorder.hints` —— 进程内镜像（`tool → 一句提示`），**同步无 IO**（schema 构造是发送路径，不能读盘）。
  `record()` 写库后顺手更新；`warmUp()` 在 `XtomApp` 那条守护线程里装一次。
- `MemoryDao.byType(type)` —— 新增只读查询（**含置顶**；`byTypeUnpinned` 会漏掉用户特意置顶的教训，
  而那恰恰是他最想让 AI 记住的）。纯 `@Query`，不动表结构、不需要迁移。
- 开关在权限页「调用前提醒踩过的坑」，**默认开**（只对踩过坑的工具生效 = 没踩过就零成本）。

### 2. 8 个块名当**检查表**用，不是照搬结构

我们是图 + 状态卡，它是固定槽位，结构不该照搬。但拿它的块名逐项对一遍能看出真缺哪类：
- `tool_guidelines` / `self_improvement` —— 自指槽位，我们的 lesson 覆盖了一部分
- `pending_items` —— 大概对应状态卡里的「未决问题」
- `session_patterns` —— **这一类我们没有**（见下条）

### 3. 一个真会读对话、会改记忆的后台 agent

这是根本分歧：我们刻意不调模型，所以只沉淀得下**结构化的失败信号**，
沉淀不了「他这周问过三次错误处理」这种**要模型读完 transcript 才看得出的模式**。

要补这个，前置条件是**异步任务基础设施**：任务注册表（id/状态/进度/结果）+ 句柄 +
完成后把结果**当工具结果注入并触发一轮新生成**（而不是像现在 `create_agent` 那样贴一段 assistant 文字）。
那正是另一条已知缺口，两件事是同一块地基 —— 见 `TODO-NOW.md` 里「子 agent 管理」那条。

## 六、旁证

hermes-agent 有个 issue #553 就是提议照着这个做一个 "Subconscious Observer Agent"
（https://github.com/NousResearch/hermes-agent/issues/553）。说明这个模式正在被抄，不是只有我们看到。
