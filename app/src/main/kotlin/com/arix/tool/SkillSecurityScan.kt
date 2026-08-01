package com.arix.tool

// ============================================================
// SkillSpector 式安全审查（原生轻量版，参考 NVIDIA/SkillSpector Apache-2.0 的静态检测思路）。
// 装第三方 skill/包前先扫文本文件里的危险信号，给 SAFE/CAUTION/DANGER 判定——防「装到带毒的 skill」。
// 纯静态、离线、不执行被扫内容。
// ============================================================
object SkillSecurityScan {
    data class Finding(val category: String, val where: String)
    data class Result(val level: String, val findings: List<Finding>) {
        fun summary(): String = when (level) {
            "DANGER" -> "🛑 高危（不建议安装）"
            "CAUTION" -> "⚠️ 注意"
            else -> "✅ 未见明显风险"
        } + if (findings.isEmpty()) "" else "：" + findings.joinToString("；") { "${it.category}(${it.where})" }

        /** 详细报告：逐条说清哪个文件、什么问题、会干什么——给用户看后自己决定要不要放行。 */
        fun detail(): String = buildString {
            append(when (level) { "DANGER" -> "🛑 安全审查：发现高危内容"; "CAUTION" -> "⚠️ 安全审查：有需要注意的地方"; else -> "✅ 安全审查通过" })
            if (findings.isEmpty()) return@buildString
            append("\n\n具体问题：\n")
            findings.groupBy { it.category }.forEach { (cat, fs) ->
                append("• ").append(cat).append("：").append(explain(cat)).append("\n")
                append("   出现在：").append(fs.map { it.where }.distinct().joinToString("、")).append("\n")
            }
            append("\n以上是这个 skill/包会做的可疑动作。确认无碍才安装。")
        }
    }

    private fun explain(category: String): String = when (category) {
        "下载即执行" -> "从网络下载脚本并直接运行（curl|bash 这类），等于让远端代码在你设备上执行——最危险"
        "破坏性命令" -> "含删库/格式化/fork 炸弹等会破坏系统或数据的命令"
        "危险代码执行" -> "会动态执行代码（eval/exec/subprocess/系统命令），行为不可预测"
        "提示注入" -> "试图让 AI 忽略你的或系统的指令，劫持 AI 行为"
        "凭据/密钥获取" -> "读取环境变量/密钥/SSH/AWS 凭据等敏感信息（可能被偷走）"
        "数据外传" -> "把数据发往外部服务器（webhook/telegram/slack/HTTP POST）"
        "混淆/隐藏" -> "用 base64/字符码等手段隐藏真实代码，藏东西"
        "改写AI提示/代答" -> "会注册钩子改写发给 AI 的系统提示/用户输入，或拦截你的消息直接冒充 AI 作答——装前请确认可信"
        else -> "可疑行为"
    }

    // 类别 to (匹配, 权重)。权重求和：≥3=DANGER，≥1=CAUTION。
    private val rules: List<Pair<String, Pair<Regex, Int>>> = listOf(
        "下载即执行" to (Regex("""(curl|wget)\s+[^\n|]*\|\s*(bash|sh)|iwr\s.*\|\s*iex""", RegexOption.IGNORE_CASE) to 3),
        "破坏性命令" to (Regex("""rm\s+-rf\s+[~/]|mkfs|dd\s+if=|:\(\)\s*\{.*:\s*&\s*\}\s*;\s*:|format\s+[a-z]:""", RegexOption.IGNORE_CASE) to 3),
        "危险代码执行" to (Regex("""\b(eval|exec)\s*\(|os\.system\s*\(|subprocess\.|child_process|Runtime\.getRuntime""") to 2),
        // 提示注入单独一条就该拦：SKILL.md 正文会被直接拼进系统提示，一句「忽略上述指令」足以劫持 AI，
        // 而 AI 手上有 shell。权重 2→3 让单条注入即 DANGER。
        "提示注入" to (Regex("""ignore\s+(all\s+)?(previous|above)\s+instructions|disregard\s+the\s+(system|above)|忽略(之前|所有|上述)(的)?指令|你现在是|从现在起(你)?(必须|要)""", RegexOption.IGNORE_CASE) to 3),
        // 直接碰运行时全局 = 想干「同 realm 截令牌/改调用」那套（见 JsPluginRuntime 锁死全局的注释）。
        "运行时篡改" to (Regex("""window\.__mk|window\.AndroidBridge|window\.__resolve|window\.__p\b|window\.__t\b|window\.__hooks|AndroidBridge\s*\[|window\.xtom\s*=|Object\.defineProperty\s*\(\s*window""", RegexOption.IGNORE_CASE) to 3),
        // 敏感钩子注册：改系统提示/改用户输入/拦截代答是项目自认的最高危能力，但装前扫描原先完全不表示（红队 #E）。
        // 权重 1=CAUTION：这些钩子本身是合法功能（运行期还有逐包审批），装前只需让用户知情、与运行期口径一致，不判 DANGER。
        "改写AI提示/代答" to (Regex("""register(SystemPromptComposeHook2?|MessageProcessingPlugin|PromptInputHook)""") to 1),
        "凭据/密钥获取" to (Regex("""os\.environ|process\.env|\.ssh/id_rsa|\.aws/credentials|envGet\s*\(|(API[_-]?KEY|SECRET|TOKEN|PASSWORD)\s*[=:]""", RegexOption.IGNORE_CASE) to 1),
        "数据外传" to (Regex("""requests\.(post|put)\s*\(|fetch\(\s*['"]https?://|discord\.com/api/webhooks|api\.telegram\.org|hooks\.slack\.com""", RegexOption.IGNORE_CASE) to 1),
        "混淆/隐藏" to (Regex("""base64\.b64decode|eval\(\s*atob|String\.fromCharCode|\\x[0-9a-fA-F]{2}\\x[0-9a-fA-F]{2}\\x[0-9a-fA-F]{2}|\[\s*['"][a-zA-Z]['"]\s*\+\s*['"]""") to 1),
    )

    /** 只扫文本类文件（脚本/文档/配置），返回判定。 */
    fun scan(files: List<Pair<String, String>>): Result {
        val hits = LinkedHashSet<Finding>()
        var score = 0
        for ((name, content) in files) {
            for ((cat, ruleWeight) in rules) {
                val (rx, w) = ruleWeight
                if (rx.containsMatchIn(content)) { if (hits.add(Finding(cat, name.substringAfterLast('/')))) score += w }
            }
        }
        val level = when { score >= 3 -> "DANGER"; score >= 1 -> "CAUTION"; else -> "SAFE" }
        return Result(level, hits.toList())
    }

    private val textExt = setOf("md", "txt", "sh", "bash", "zsh", "py", "js", "ts", "mjs", "cjs", "json", "yaml", "yml", "toml", "rb", "pl", "ps1", "bat")
    fun isTextFile(name: String) = name.substringAfterLast('.', "").lowercase() in textExt || name.substringAfterLast('/').equals("SKILL.md", true)
}
