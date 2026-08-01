# WORKLOG — Arix Apache-2.0 精简版

> 更新时间：2026-08-01（opencode 会话收尾）
> 工作区：`E:\ArixApache`（独立 git 仓）
> 最近提交：`9cd2984`

## 已完成（全部编译通过 + 单测过）
Apache-2.0 精简版已完成核心裁剪，16 个提交。目标 = 干净可内置的 AI 助手骨架，原创特色留给 GPL 满血版当卖点。

| commit | 内容 |
|---|---|
| `76e3996` | 基线导入 |
| `ba55c63`+`1886a9f` | 剪终端线 + GPL 组件（jlatexmath/proot/Termux） |
| `614624b` | 砍超级岛胶囊 |
| `8369977` | 砍语音通话/数字助手 |
| `be3a767` | 砍技能/工作流/子Agent/AIGuard + ADB |
| `0f1331f` | 砍隐身浏览器/站点登录 |
| `1b73d88` | 记忆改纯文件（ai_workspace/memory.json），删图谱/向量/自我进化 |
| `ec9713a` | 砍独立陪伴（日记/主动消息/陪伴包） |
| `780bb1c` | 砍角色卡/世界书/Waifu（最重一刀，动 DB schema） |
| `0d9200a`/`a8bc263`/`1e003da` | 非开源网络接口全砍（厂商预设/Anthropic/Gemini/云端市场/媒体/地图/生活查询） |
| `288d169` | 关于页/更新页引导用 GPL 满血版 |
| `9cd2984` | 许可证 Apache-2.0 + 清理内部文档 + README 重写 |

净删 27511 行。`assembleDebug` + `:app:testDebugUnitTest` + `:logic:test` 全绿。

## 关键决策
- 只留自建可控端点（OpenAI 兼容/Ollama/vLLM/Home Assistant/MCP/S3/MinIO）
- 终端线全砍；记忆改纯文件；陪伴砍到只剩心率 health_measure；角色卡全砍
- Apache 版更新永远引导用户换 GPL 满血版（XTOM0706/arix-app）
- 许可证 Apache-2.0；README 面向二次开发/内置

## 下一步（未做）
- GitHub 仓库名（README 里写的是 `arix-apache`）与是否推送
- 有残留注释引用已删功能（如 ToolManager 注释里的 CardToolStore、OnboardingPage 注释等），不影响编译，纯清理层
- i18n 里还有已删功能的翻译串（I18nStrings.kt 和 i18n_table.json），不影响编译

## 技术要点
- 编译：`cd E:\ArixApache; .\gradlew.bat :app:assembleDebug`
- 原项目主仓 `E:\OnyxAI` 不动
