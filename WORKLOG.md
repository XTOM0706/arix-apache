# WORKLOG — Arix Apache-2.0 精简版

> 更新时间：2026-08-01（opencode 会话收尾）
> 工作区：`E:\ArixApache`（独立 git 仓）
> 最近提交：`2c3d48c`
> 公开仓库：**https://github.com/XTOM0706/arix-apache**（已推送，main）

## 已完成（18 提交，编译 + 单测全绿）
Apache-2.0 精简版：干净可内置的 AI 助手骨架，原创特色留给 GPL 满血版（XTOM0706/arix-app）当卖点。

**功能裁剪清单：**
- 终端线 + GPL 组件（jlatexmath/proot/Termux）
- 超级岛胶囊、语音通话、技能/工作流/子Agent/AIGuard、ADB 常驻、隐身浏览器/站点登录
- 记忆改纯文件（ai_workspace/memory.json），删图谱/向量/自我进化
- 独立陪伴（日记/主动消息/陪伴包）、角色卡/世界书/Waifu（动 DB schema 23→24）
- 非开源网络接口：厂商 LLM 预设、Anthropic/Gemini 原生协议、云端市场、媒体/地图/生活查询
- 许可证 Apache-2.0，README 面向二次开发/内置

**保留：** OpenAI 兼容协议（可接本地 llama.cpp/Ollama/vLLM）、本地 STT/TTS/唤醒、文件工具、
RAG/搜索/浏览器、工具系统、MCP、Home Assistant、S3/MinIO、天气 open-meteo、心率 health_measure。

**更新引导：** 向导欢迎页最前放丑话「这是精简版，完整功能用 GPL 满血版」+ 关于页/更新页同样引导。

## 关键文件
- `E:\ArixApache`（本地仓，18 提交，历史可回滚）
- 主仓 `E:\OnyxAI` 未动

## 待办（未做）
- i18n 里已删功能的翻译串（I18nStrings.kt / i18n_table.json）残留，不影响编译，纯清理层
- 部分代码注释还引用已删功能（ToolManager/OnboardingPage 注释等），不影响编译
- token 曾在对话日志暴露（ghp_7pCM...），**建议轮换**（GitHub → Developer settings → Tokens）

## 技术要点
- 编译：`cd E:\ArixApache; .\gradlew.bat :app:assembleDebug`
- 推送走代理：`git -c http.proxy=http://127.0.0.1:7890 push origin main`
