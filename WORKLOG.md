# WORKLOG — Arix Apache-2.0 精简版

> 更新：2026-08-01（UTC+8 深夜，opencode 会话）
> 工作区：`E:\ArixApache`（独立 git 仓，不含 .git，与 E:\OnyxAI 隔离）
> 最近提交：`0f1331f`

## 正在做
Apache-2.0 精简版：砍掉原创特色功能（留给 GPL 满血版当卖点），目标 = 干净可内置的 AI 助手骨架。

## 已完成（每块编译过 + 提交过）
| commit | 内容 |
|---|---|
| `76e3996` | 初始基线（E:\OnyxAI work/0728-terminal-fixes 导入） |
| `ba55c63` | 剪终端线 + GPL 组件（jlatexmath），-1059 行 |
| `1886a9f` | 清终端残留引用（CodeRunner/StdioMcp/ToolRequirement），保证编译 |
| `614624b` | 砍超级岛胶囊整套，-1768 行（Capsule*/SuperIsland/InAppIsland/XmsfUnlock；新建 ChatStopBus 保悬浮球停止生成） |
| `8369977` | 砍语音通话/数字助手会话，-632 行（VoiceCall/XtomVoiceSession*） |
| `be3a767` | 砍技能录制/工作流/子Agent/AIGuard + ADB 常驻，-3349 行 |
| `0f1331f` | 砍隐身浏览器/站点登录，-602 行（Incognito*+SiteLogin+SiteCookies） |

累计删 33 个文件、约 -7000 行。`assembleDebug` 每次编译通过。

## 下一步（记忆改造，深改动，已确认方案）
**⑥ 记忆改纯文件**（用户拍板：留 memory 工具、内部改 JSON 文件；留简化记忆页；删图谱/向量/自我进化）：
1. 重写 `app/MemoryManager.kt`（807 行）→ JSON 文件存储，保留 search/add/update/delete/queryRelevant/count/recent/getById/setPinned/setType/setFolder/byCard/idByTitle/upsertByTitle/searchTop/getTagNames/allTags/idsByTag/setCard
2. `MemoryEntity` 从 Room 移到 app 层纯数据类；删 `data/.../MemoryDao.kt`；AppDatabase 迁移 22→23 删 memory 三表
3. 删 `MemoryTidy`（自我进化）、`MemorySalvage`（回收站/自动压缩）
4. 简化 `MemoryPage`（1600 行 → 删图谱/连线/关联编辑 UI）
5. `MemoryTool` 去掉 link action
6. 清 `MemoryInjection`/`MemoryMentions`/`ChatScreen` 的 memoryManager 引用

**⑦ 陪伴砍到只剩心率**（用户拍板：日记/世界书/角色卡/Waifu 全砍，只留 health_measure）：
- 日记（Diary.kt）、世界书（WorldBookTool/WorldTree*）、提醒、角色卡（CharacterCard*/CardRoleplayStore/CardToolStore/WaifuProcessor）、CardFromChat、CardPng
- ⚠ 角色卡嵌在对话核心（characterCardId 是 Conversation 字段），需单独细拆

**⑧ 非开源网络接口**：厂商 LLM 预设（ApiProviders 20+ 家）、Anthropic/Gemini 原生协议、云端市场（Operit/marketwatch）、B站/网易云/地图/12306/生活查询

**⑨ Apache 版更新引导**：更新检查/关于页/README 永远建议换 GPL 满血版

**⑩ 许可证**：LICENSE/LICENSE.md/NOTICE 改 Apache-2.0（含 wake/LICENSE）
**⑪ 清理内部文档 + 写内置版 README**
**⑫ WORKLOG 交接 + 最终提交 + 确认仓库名/推送**

## 关键决策（已拍板）
- 只留自建可控端点（网络接口）＋ OpenAPI 兼容协议保留
- 终端线全砍（Termux 不可行）
- 工作区 E:\ArixApache
- 砍原创特色 → GPL 版留卖点；保 Shizuku/root 通用特权层
- 记忆改纯文件、陪伴只留心率检测
- Apache 版更新永远引导去 GPL 满血版

## 待用户拍板
- GitHub 仓库名（建议 arix-apache）与是否推送
- ⑧ 非开源网络接口的天气 open-meteo 已确认保留

## 技术要点
- 编译命令：`cd E:\ArixApache; .\gradlew.bat :app:assembleDebug`
- 每次改完跑编译再提交；大文件改后自检括号配平
- 原项目主仓 `E:\OnyxAI` 不动；本仓是剪裁版
