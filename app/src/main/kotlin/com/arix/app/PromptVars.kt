package com.arix.app

// ============================================================
// 提示词变量 —— 对齐酒馆 {{占位符}}：注入前把人设/世界书里的 {{user}}/{{char}}/{{model}}/{{time}}/{{date}}
// 替换成运行时实际值。大小写不敏感。空文本或无 "{{" 直接返回，零开销。
// ============================================================
object PromptVars {
    fun resolve(text: String, userName: String, charName: String, model: String): String {
        if (text.isBlank() || !text.contains("{{")) return text
        val now = java.util.Date()
        val loc = java.util.Locale.getDefault()
        val time = java.text.SimpleDateFormat("HH:mm", loc).format(now)
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", loc).format(now)
        return text
            .replace("{{user}}", userName.ifBlank { "用户" }, ignoreCase = true)
            .replace("{{char}}", charName.ifBlank { "助手" }, ignoreCase = true)
            .replace("{{model}}", model, ignoreCase = true)
            .replace("{{time}}", time, ignoreCase = true)
            .replace("{{date}}", date, ignoreCase = true)
    }
}
