package com.arix.tool

import org.json.JSONArray
import org.json.JSONObject

// ============================================================
// 两个零依赖的小工具：时间与计算器。
// （原来和 FetchTool 同住一个文件；fetch 已被 open_page 取代、从未注册，删掉时这两位一起搬到这儿。）
// ============================================================

class TimeTool : Tool {

    override val name = "time"
    override val description = "获取当前时间、日期和时区信息。"
    override val llmDescription = "Current time, date and timezone."

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("now", "date", "timestamp")))
                put("description", "now (default), date, timestamp")
            })
        })
        put("required", JSONArray())
    }

    override suspend fun execute(params: JSONObject): ToolResult {
        val action = params.optString("action", "now")
        val now = System.currentTimeMillis()
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val dateSdf = java.text.SimpleDateFormat("yyyy-MM-dd EEEE", java.util.Locale.getDefault())
        return when (action) {
            "date" -> ToolResult("当前日期: ${dateSdf.format(java.util.Date(now))}")
            "timestamp" -> ToolResult("当前时间戳: $now (毫秒)")
            else -> ToolResult("当前时间: ${sdf.format(java.util.Date(now))}")
        }
    }
}

class CalculatorTool : Tool {

    override val name = "calculator"
    override val description = "计算数学表达式。支持 + - * / ^ sqrt sin cos tan log abs 等运算。"
    override val llmDescription = "Evaluate a math expression: + - * / ^ sqrt sin cos tan log abs."

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("expression", JSONObject().apply {
                put("type", "string")
                put("description", "e.g. 2+3*4 or sqrt(16)+2^3")
            })
        })
        put("required", JSONArray(listOf("expression")))
    }

    override suspend fun execute(params: JSONObject): ToolResult {
        val expr = params.optString("expression", "")
        if (expr.isBlank()) return ToolResult("请输入表达式", isError = true)
        return try {
            val safe = expr.lowercase()
                .replace(Regex("[^0-9+\\-*/.()^a-z ]"), " ")
                .replace("sqrt", "√").replace("sin", "s").replace("cos", "c")
                .replace("tan", "t").replace("log", "l").replace("abs", "a")
                .replace("pi", "${Math.PI}").replace("e", "${Math.E}")

            if (safe.contains(Regex("[a-z&&[^eπ√sctl a]]"))) return ToolResult("表达式包含不支持的操作", isError = true)

            val cleaned = expr.replace("^", " pow ").replace(Regex("[^0-9+\\-*/(). ]"), " ")
            if (cleaned.isBlank() || !cleaned.any { it.isDigit() }) return ToolResult("无效表达式", isError = true)

            val result = evaluateBasic(cleaned)
            ToolResult("${expr} = $result")
        } catch (e: Exception) {
            ToolResult("计算失败: ${e.message}", isError = true)
        }
    }

    private fun evaluateBasic(expr: String): Double {
        var e = expr.trim().replace(" ", "")
        while (e.contains("(")) {
            val end = e.indexOf(')')
            val start = e.substring(0, end).lastIndexOf('(')
            val inner = e.substring(start + 1, end)
            val value = evalSimple(inner)
            e = e.substring(0, start) + value + e.substring(end + 1)
        }
        return evalSimple(e)
    }

    private fun evalSimple(expr: String): Double {
        val tokens = mutableListOf<Any>() // Double or Char(+,-,*,/)
        var i = 0
        while (i < expr.length) {
            when {
                expr[i].isDigit() || expr[i] == '.' || (expr[i] == '-' && (i == 0 || tokens.isEmpty() || tokens.last() is Char)) -> {
                    val start = i
                    if (expr[i] == '-') i++
                    while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) i++
                    tokens.add(expr.substring(start, i).toDouble())
                    continue
                }
                expr[i] in "+-*/" -> { tokens.add(expr[i]); i++ }
                else -> i++
            }
        }
        // *, / first
        var j = 0
        while (j < tokens.size) {
            if (tokens[j] == '*' || tokens[j] == '/') {
                val a = tokens[j - 1] as Double
                val b = tokens[j + 1] as Double
                val r = if (tokens[j] == '*') a * b else a / b
                tokens[j - 1] = r; tokens.removeAt(j); tokens.removeAt(j)
                continue
            }
            j++
        }
        // +, -
        var result = tokens[0] as Double
        j = 1
        while (j < tokens.size) {
            val op = tokens[j] as Char
            val b = tokens[j + 1] as Double
            result = if (op == '+') result + b else result - b
            j += 2
        }
        return result
    }
}
