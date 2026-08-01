package com.arix.tool

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具参数按 JSON Schema 的**执行前**预校验（在 [ToolManager.execute] 里，任何设备副作用发生之前）。
 *
 * 为什么要：模型输出不是可信输入。它会把 `tap` 调成缺 x/y、把 `direction` 填成枚举外的词、
 * 把该是对象的字段塞成一句话。这些坏参数直接落到 `optInt`/`optString` 会被静默兜成默认值
 * （tap 到 (0,0)、方向乱走），做成了错的操作，比报错还糟。这里在执行前按「本轮发给模型的
 * 同一份 schema」校验，不合法就回一条结构化错误让模型重规划——思路借鉴竞品 Eta 的 AgentToolCallValidator。
 *
 * **刻意宽容**（这是和 Eta 最大的差别）：Arix 同时吃原生 `tool_calls` 和内联文本工具调用，
 * 后者（[InlineToolCallParser]）**故意把数值保留成字符串**（"0123"/长号码防丢前导零），不少模型也
 * 把标量序列化成字符串。若照 Eta 那样严格「integer 必须是 Int」会把大量正常调用误杀。所以：
 *  - 缺必填字段 / 枚举外取值 / 把对象·数组塞进标量位 → **挡**（高置信、几乎无误伤）
 *  - 标量类型（string/number/integer/boolean）收到字符串 → **放行**（可能是序列化后的值）
 *  - 数值/长度/项数越界 → 能判定时挡，判不定（如字符串形数字）就放行
 *
 * 取不到 schema 一律放行（fail-open）：宁可少挡，也不因为一次 schema 缺失就跟 AI 说「你参数错了」。
 */
object ToolArgValidator {

    /** 合法返回 null；不合法返回一句给模型看的中文错误（含 path 与期望），调用方据此回结构化错误。 */
    fun validate(schema: JSONObject?, args: JSONObject): String? {
        if (schema == null) return null
        return validateValue(args, schema, "参数")
    }

    private fun validateValue(value: Any?, schema: JSONObject, path: String): String? {
        typeError(value, schema.optString("type"), path)?.let { return it }

        schema.optJSONArray("enum")?.let { enum ->
            if (!enumContains(enum, value)) return "$path 取值不在允许集合中（应为 ${enumList(enum)}）"
        }

        return when (value) {
            is JSONObject -> validateObject(value, schema, path)
            is JSONArray -> validateArray(value, schema, path)
            is String -> validateString(value, schema, path)
            is Number -> validateNumber(value.toDouble(), schema, path)
            else -> null
        }
    }

    // 只挡「结构类型不符」，且**字符串一律放行**：内联调用/不少模型把嵌套对象、数组、数值都序列化成字符串
    // （object 位收到 "{\"a\":1}"、array 位收到 "[1,2]"），严格判会把本来能跑的调用误杀。字符串放行后
    // 仍保留 required/enum/min-max/长度校验；真正误传的只剩「非字符串标量塞进对象/数组位」这类硬结构错。
    private fun typeError(value: Any?, type: String, path: String): String? {
        if (value is String) return null   // 字符串可能是序列化后的任意类型，放行（下游工具会自行解析）
        return when (type) {
            "object" -> if (value is JSONObject) null else "$path 应为对象"
            "array" -> if (value is JSONArray) null else "$path 应为数组"
            "string", "number", "integer", "boolean" ->
                if (value is JSONObject || value is JSONArray) "$path 应为 $type，不该是对象/数组" else null
            else -> null   // 无 type 或未知 type：不判
        }
    }

    private fun validateObject(value: JSONObject, schema: JSONObject, path: String): String? {
        schema.optJSONArray("required")?.let { required ->
            for (i in 0 until required.length()) {
                val key = required.optString(i)
                if (key.isBlank()) continue
                // 缺失或显式 null 都算没给——这是最该挡的一类（tap 缺坐标）
                if (!value.has(key) || value.isNull(key) || value.opt(key).isBlankString()) {
                    return "$path 缺少必填字段「$key」"
                }
            }
        }
        val properties = schema.optJSONObject("properties") ?: return null
        val keys = value.keys()
        while (keys.hasNext()) {
            // ⚠ 搬进 :logic 后必须显式转 String：安卓平台的 JSONObject.keys() 返回 Iterator<String>，
            // 而这里编译用的 AOSP org.json（compileOnly）返回的是**裸 Iterator**，next() 是 Any?。
            // 运行时仍是平台那份、行为不变；不转就编译不过。往 :logic 搬别的用 org.json 的代码会再撞上这条。
            val key = keys.next() as? String ?: continue
            val childSchema = properties.optJSONObject(key) ?: continue   // 未声明的额外字段不管（宽容）
            validateValue(value.opt(key), childSchema, "$path.$key")?.let { return it }
        }
        return null
    }

    private fun validateArray(value: JSONArray, schema: JSONObject, path: String): String? {
        val minItems = schema.optInt("minItems", -1)
        if (minItems >= 0 && value.length() < minItems) return "$path 至少要 $minItems 项"
        val maxItems = schema.optInt("maxItems", -1)
        if (maxItems >= 0 && value.length() > maxItems) return "$path 最多 $maxItems 项"
        val itemSchema = schema.optJSONObject("items") ?: return null
        for (i in 0 until value.length()) {
            validateValue(value.opt(i), itemSchema, "$path[$i]")?.let { return it }
        }
        return null
    }

    private fun validateString(value: String, schema: JSONObject, path: String): String? {
        val minLength = schema.optInt("minLength", -1)
        if (minLength >= 0 && value.length < minLength) return "$path 长度不能少于 $minLength"
        val maxLength = schema.optInt("maxLength", -1)
        if (maxLength >= 0 && value.length > maxLength) return "$path 长度不能超过 $maxLength"
        // 字符串形数字（"100"）遇到数值上下界：能解析才判，判不了就放行
        val num = value.trim().toDoubleOrNull()
        if (num != null) return validateNumber(num, schema, path)
        return null
    }

    private fun validateNumber(number: Double, schema: JSONObject, path: String): String? {
        if (schema.has("minimum") && number < schema.optDouble("minimum")) {
            return "$path 不能小于 ${schema.opt("minimum")}"
        }
        if (schema.has("maximum") && number > schema.optDouble("maximum")) {
            return "$path 不能大于 ${schema.opt("maximum")}"
        }
        return null
    }

    // 枚举比较：按去空白后的字符串比（数值枚举 1 与字符串 "1" 也能对上），兼顾内联字符串化取值。
    // 不做大小写折叠——枚举多是精确 token，接受错大小写只会让工具下游静默拿到用不了的值。
    private fun enumContains(enum: JSONArray, value: Any?): Boolean {
        val v = value.stringForm() ?: return false
        for (i in 0 until enum.length()) {
            if (enum.opt(i).stringForm() == v) return true
        }
        return false
    }

    private fun enumList(enum: JSONArray): String =
        (0 until enum.length()).joinToString("/") { enum.opt(it)?.toString() ?: "null" }

    private fun Any?.stringForm(): String? = when (this) {
        null, JSONObject.NULL -> null
        is JSONObject, is JSONArray -> null
        else -> toString().trim()
    }

    private fun Any?.isBlankString(): Boolean = this is String && this.isBlank()
}
