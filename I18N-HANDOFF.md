# Arix i18n 翻译交接（给 opencode）

**项目路径**：`E:\OnyxAI`（注意：已从 `C:\Users\XTOM\OnyxAI` 搬到 E 盘，旧路径不存在）。
Arix = 安卓智能手表**数字助手** App。现在要做多语言。

## 你的任务（只干这一件）
把 **`E:\OnyxAI\i18n\i18n_table.json`** 里每个中文界面串，翻译成**全部 33 种语言**并填进去。
**只编辑这一个 JSON 文件。绝对不要碰任何 `.kt` 源码。** 翻译不影响编译，风险为零。

语言列表（33 种，见文件 `languages` 字段）：
`zh-TW en es pt fr de it nl ru uk pl cs ro el hu sv da fi nb tr ar fa he hi bn ta ja ko th vi id ms tl`
（其中 ar/fa/he 为从右到左语言，正常翻译即可。）

### 文件结构
```json
{
  "languages": ["zh-TW","en","es", ... 33 个],
  "count": 477,
  "strings": {
    "保存": {"zh-TW":"","en":"","es":"", ... 每种语言一个空槽},
    "外观 / 主题": {...},
    ...
  }
}
```
把每个 `""` 填成对应语言的译文即可。key（中文串）**一个字都不要改**，也不要增删条目。

### 翻译规则
1. 这些是 **App 界面文字**（按钮/标签/设置项/提示），按各语言的 UI 惯例翻**简洁自然的短句**，不要直译长句。
2. **专有名词/技术词保留原文**：API、Key、URL、Bing、Baidu、Sogou、Shizuku、Termux、MCP、TTS、STT、QR、Markdown、GitHub、WebDAV、Waifu、SillyTavern、Operit、Arix、模型名等。
3. **保留占位与符号**：数字、`%`、emoji、`/`、括号里的补充说明按目标语言自然处理；中文标点（，。：「」）转成目标语言习惯标点。
4. `zh-TW` = 把简体**转成繁體中文**（用语也台/港化，如「设置」→「設定」、「视频」→「影片」、「登录」→「登入」），不是重新翻译。
5. 拿不准就给最贴切的简洁等价说法；**不要编造含义**、不要留空当没看见（留空运行时会回退中文，但尽量都填）。
6. 输出必须是**合法 UTF-8 JSON**、结构与原文件一致。**分批做也行，但每次保存整份 JSON 都要能被解析。**

### 完成后（由 Claude/用户做，你不用管）
```
python tools/i18n_embed.py     # 生成 app/.../I18nStrings.kt
gradlew.bat :app:compileDebugKotlin --offline   # 验证
```

## 硬约束（务必遵守）
- **只改** `i18n/i18n_table.json`。不改 `.kt`、不改脚本、不改其它任何文件。
- **不要跑任何破坏性 git 命令**（`git checkout .` / `git reset --hard` / `git clean` 一律禁止——本项目有过被 `git checkout` 清掉未提交代码的事故）。做完让用户/Claude 提交。
- 工作目录固定 `E:\OnyxAI`。
- 有疑问先停下问，别猜着大改。
