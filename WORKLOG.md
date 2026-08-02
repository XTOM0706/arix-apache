# WORKLOG — Arix Apache-2.0 精简版

> 更新时间：2026-08-02（opencode 会话）
> 工作区：`E:\ArixApache`（独立 git 仓）
> 最近提交：`7c916ca`（本轮向导两步 + 更新检查指自有仓 + 审查修复）
> 公开仓库：**https://github.com/XTOM0706/arix-apache**（已推送 main 到 a541506，本轮未 push）
> 🔴 待办见下方「未完成 / 待续」——**下次做「内置公告功能」**（两个仓都做）。

## ✅ 2026-08-02 向导同步 + 更新检查改指自有仓（本会话）
- 提交 `3f5f3ef`~`7c916ca` 共 5 个：
  1. `3f5f3ef` **更新检查 REPO 改指 `XTOM0706/arix-apache`**——之前一直指向 GPL 的 arix-app，本版更新要查自己的仓（含 v0.0.1 的 app-release.apk）。提示语同步。
  2. `3bb04bb` **向导同步主仓两步**：语音模型步（STT 引擎/语言/密钥 + TTS 引擎/试听，写 SttPrefs/TtsTool）+ 设为默认助手步（RoleManager，拦下一步，设备不支持放行）。步骤 8→10。
  3. `961553b` 审查修复（试听瞬时状态改普通 remember、available 提出组合外）。
  4. `f6c112a` 设置页「新手向导」描述同步。
  5. `7c916ca` 完成页默认助手汇总取反修复。
- Apache 版完成页角色读 `AssistantRolePrefs.characterSetting`（无角色卡体系，保留原逻辑）。
- 编译全绿（assembleDebug）。**未 push**，origin/main 落后 5 提交。

## ✅ 2026-08-02 已推送 + 首发 Release（本会话）
- push `49dccd3..a541506` 到 origin/main（WORKLOG 存盘，直连成功）
- 创建 **GitHub Release `v0.0.1`**（https://github.com/XTOM0706/arix-apache/releases/tag/v0.0.1）：
  - `app-release.apk`（40.4MB，versionCode=1 / 0.0.1，R8 + 正式签名，已上传）
  - `arix-prebuild-libs-0.0.1.zip`（预编译库：wake/cloudapi/stt/data 的 **release AAR** + logic.jar，2.4MB，已上传）
- 预编译库构建命令（各库 release AAR，已跑通）：`.\gradlew.bat :wake:assembleRelease :stt:assembleRelease :tts:assembleRelease :cloudapi:assembleRelease :data:assembleRelease :logic:jar`
- ⚠ `:tts` 模块是**空壳**（只有 Manifest，无源码，app 也不依赖它）——预编译 zip **不含 tts**，别被它带偏。
- ⚠ token 处理：用 git credential 里的 PAT（ghp_7pCM...）上传 release；用过一次，安全起见下次仍建议轮换。

## 已完成（19 提交，编译 + 单测全绿）
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
- `E:\ArixApache`（本地仓，历史可回滚）
- 主仓 `E:\OnyxAI`（GPL 满血版，未动代码，只改了 README QQ 群号）

## 未完成 / 待续（明天做）
**🔴 内置「公告」功能（两个仓都做，已拍板方案）**
- 目标：软件里内置「公告」入口，从 GitHub Releases 拉公告展示 + 新 Release 弹通知。
  以后公告直接在 hub 的 Releases 里发，软件自动拉取，不靠 QQ 群/邮箱。
- 拍板：公告来源 = 主仓拉 `XTOM0706/arix-app`（自己），Apache 版拉 `XTOM0706/arix-app`（满血版，引导用）。
- 形态：公告列表页 + 通知（两者都做）。
- 现状摸底（已做）：
  - `UpdateChecker`（UpdateCheckPage.kt）已能拉 Releases，`Release` 数据结构含 tag/name/notes/url/published/apkUrl/prerelease。
  - `UpdateNotifier` 已有后台查更新 + 通知 + 弹窗（`EXTRA_OPEN_PAGE="update"` 路由）。
  - 主仓 REPO 已指 `XTOM0706/arix-app`（UpdateCheckPage.kt:89）；Apache 版 REPO 也已指满血版。
- 待做：新建「公告」页面拉**最新 N 条** Releases 列表展示（现有页面只显示最新一条）+ 通知复用/扩展。
  两个仓分别做：Apache 版做进 E:\ArixApache，主仓做进 E:\OnyxAI。

**QQ 群号已改（本会话）**
- 主仓 E:\OnyxAI：README.md（2 处）、README_EN.md（2 处）、terminal/.../AboutActivity.kt —— `1063208484` → `1047592322`
- 终端仓 E:\OnyxTerminal：README.md、terminal/.../AboutActivity.kt —— 同上
- ⚠ 这些改动**尚未提交/推送**（明天一起处理或另行确认）

## 待办（未做）
- i18n 里已删功能的翻译串（I18nStrings.kt / i18n_table.json）残留，不影响编译，纯清理层
- 部分代码注释还引用已删功能（ToolManager/OnboardingPage 注释等），不影响编译
- token 曾在对话日志暴露（ghp_7pCM...），**建议轮换**（GitHub → Developer settings → Tokens）
- ⚠ `E:\ArixApache\app\build.gradle.kts` 有未提交改动（versionCode=1/versionName=0.0.1，定位为可被任何版本覆盖的垫底版）——**是否已提交需确认**（本会话最后一次 git 操作是提交 `49dccd3`）

## 技术要点
- 编译：`cd E:\ArixApache; .\gradlew.bat :app:assembleDebug`
- 推送走代理：`git -c http.proxy=http://127.0.0.1:7890 push origin main`
- Apache 版 R8 release 已出：`app/build/outputs/apk/release/app-release.apk`（40.4 MB，正式密钥签名，
  指纹 32726f2a...，与满血版同钥匙，可被任何版本覆盖安装）
