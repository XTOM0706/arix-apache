# WORKLOG — Arix
更新时间：2026-08-01 19:50
最近提交：34d90c4（README：Release 发布说明 + 二次开发预编译库用法）· 公开仓 main = 34d90c4

## 本次交接（2026-08-01 晚）
- **预编译库发布**：v0.2.2 release 新增资产 `arix-prebuild-libs-0.2.2.zip`（2.5MB）= wake/cloudapi/stt/data 的 release AAR + logic.jar（stt 含 native libtermux/lib）。二开者只改 :app 时可跳过库模块整编。
- README.md / README_EN.md 已加「二次开发（改完不用重编库）」章节 + 修正「Releases 还是空的」过时文案。
- 本地 work 分支已 push 到公开仓 main（34d90c4），fast-forward，无 force。


## 发布状态（公开仓 XTOM0706/arix-app）
- **v0.2.2 已发布（修复版）**：public main = work/0728-terminal-fixes 的 4057e2c（force-push 覆盖旧 pub/arix-app 历史）。tag v0.2.2，release 资产 app-release.apk。**v0.2.1 坏包已删**（release+tag）。
- ⚠ **0.2.1 坏包事故**：0.2.1 误用 pub/arix-app（筛后旧代码）构建，缺 SettingsChoiceRow 的 FlowRow 布局修复 → 英文选项文本竖排/断字。**以后发版只用 work 分支，别再碰 pub/arix-app。**
- 版本号 0.2.2/versionCode 7（含主页更新弹窗 + 布局修复 + i18n）。
- 发布流程（记牢）：work 分支 assembleRelease → force-push work:main → gh 建 tag + release。git 走代理 127.0.0.1:7890。
- 密钥 E:\ArixKeys\arix-release.jks。GitHub token：Claude 私有历史 jsonl 里 ghp_。

## 正在做
主页更新弹窗已上线 0.2.2；0.2.1 坏包已修复覆盖

## 已完成（本会话：52e0a29）
- **根因**：大量用户可见界面文字以「数据槽」形式硬编码、根本没进 i18n 表 → 消费点虽写了 tr()，查表返回 null 回退中文，非中文界面永远露中文。
- 数据槽串进表并渲染处延迟 tr()：`ApiProviders`（模型配置预设 46 条，ConfigPage+OnboardingPage）、`CapabilityTier`（能力等级）、`FontMarket`（字体）、`CapsulePrefs`（主题色名）、`AssistantRole`（数字助理诊断）、`DrawerLayoutStore`（档位）、`AboutPage`（开源致谢）、`SettingProposal`（设置项名）
- 状态/通知文案包 tr()：`WakeAssistantActivity`（浮层状态）、`WakePage`（唤醒状态）、`CapsuleBridge`（灵动岛+通知）、`ChatScreen`（翻译错误提示/回复前缀）
- 表从 2151 增至 **2292 条，33 语无空槽**；重生成 I18nStrings.kt；`assembleDebug` 通过
- 新增串由 6 个子 agent 并行翻译（nordic/fitr/rtl/hi/ja/se 分片）

## 下一步（未完成）
- 剩余 UI 裸串（之前扫描是误报或该保留的）：备份类（GitHubBackup/S3Backup/WebDavBackup）的返回串被 ImportExportPage 当 `startsWith("恢复成功")` 等前缀判断用，**翻译会破坏逻辑，需结构化改造后才能翻**——留给下次单独立项
- WorkflowTriggers 触发上下文变量（"到达 X"/"离开 X"/"开机"）是给模型/通知的数据，非静态 UI 串，保留未翻
- ChatScreen 的 convTitle 默认值「对话/新对话」牵涉持久化+判断逻辑，未翻（渲染处可再议）
- 终端 arix-terminal release 没重打（还是 v1.1）

## 待拍板
- 备份类串的结构化改造（返回值区分状态 vs 消息）要不要做

## 关键文件
- i18n 管线：tools/i18n_wrap.py / i18n_merge.py / i18n_embed.py；i18n/i18n_table.json（2292 条）
- 本轮改动：ApiProviders.kt / CapabilityTier.kt / FontMarket.kt / CapsulePrefs.kt / AssistantRole.kt / DrawerLayoutStore.kt / AboutPage.kt / SettingProposal.kt / WakeAssistantActivity.kt / WakePage.kt / CapsuleBridge.kt / ChatScreen.kt / ConfigPage.kt / OnboardingPage.kt / PermissionsPage.kt / PersonalizationPage.kt
