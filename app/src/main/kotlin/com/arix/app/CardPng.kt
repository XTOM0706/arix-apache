package com.arix.app

import android.util.Base64
import java.util.zip.Inflater
import org.json.JSONObject

// ============================================================
// PNG 角色卡导入 —— 解 SillyTavern/TavernAI 式 PNG 里内嵌的角色卡 JSON。
// 卡 JSON 以 base64 藏在 PNG 的文本块里：tEXt/iTXt/zTXt，关键字 "chara"(V2) 或 "ccv3"(V3)。
// 解出后仍走本应用的 ImportConverters.normalizeCard 归一化 —— 只是多认一种载体，格式照旧「转化」。
// 纯 Java(zip Inflater + Base64)，不引依赖。
// ============================================================
object CardPng {
    private val SIG = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)  // 0x89 'P' 'N' 'G' \r \n 0x1A \n

    /** 从 PNG 字节里提取内嵌的角色卡 JSON；不是 PNG / 没内嵌卡则返回 null。 */
    fun extractCardJson(bytes: ByteArray): String? {
        if (bytes.size < 8) return null
        for (i in SIG.indices) if (bytes[i] != SIG[i]) return null
        var pos = 8
        val texts = LinkedHashMap<String, String>()  // keyword(小写) -> 文本(多为 base64)
        while (pos + 12 <= bytes.size) {
            val len = ((bytes[pos].toInt() and 0xFF) shl 24) or ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or (bytes[pos + 3].toInt() and 0xFF)
            if (len < 0 || pos + 8 + len + 4 > bytes.size) break
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            val dataStart = pos + 8
            when (type) {
                "tEXt" -> parseTEXt(bytes, dataStart, len)
                "iTXt" -> parseITXt(bytes, dataStart, len)
                "zTXt" -> parseZTXt(bytes, dataStart, len)
                "IEND" -> return pickAndDecode(texts)
                else -> null
            }?.let { (k, v) -> if (v.isNotBlank()) texts[k.lowercase()] = v }
            pos = dataStart + len + 4  // 跳过 data + 4 字节 CRC
        }
        return pickAndDecode(texts)
    }

    private fun pickAndDecode(texts: Map<String, String>): String? {
        val raw = texts["ccv3"] ?: texts["chara"] ?: return null   // 优先 V3，再 V2
        // 去掉可能的 BOM/前导空白（BOM 不算空白，下游 JSONObject 解析也会因 BOM 报错，故一并剥掉）
        val decoded = (decodeMaybeBase64(raw) ?: raw).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return decoded.takeIf { it.startsWith("{") }
    }

    // tEXt: keyword \0 text（Latin-1，未压缩）
    private fun parseTEXt(b: ByteArray, start: Int, len: Int): Pair<String, String>? {
        val end = start + len
        val nul = indexOf0(b, start, end) ?: return null
        val key = String(b, start, nul - start, Charsets.ISO_8859_1)
        val text = String(b, nul + 1, end - (nul + 1), Charsets.ISO_8859_1)
        return key to text
    }

    // zTXt: keyword \0 compMethod(1) 压缩文本(zlib)
    private fun parseZTXt(b: ByteArray, start: Int, len: Int): Pair<String, String>? {
        val end = start + len
        val nul = indexOf0(b, start, end) ?: return null
        val key = String(b, start, nul - start, Charsets.ISO_8859_1)
        val compStart = nul + 2  // 跳过 \0 与 1 字节压缩方法
        if (compStart >= end) return null
        val text = inflate(b, compStart, end - compStart) ?: return null
        return key to text
    }

    // iTXt: keyword \0 compFlag(1) compMethod(1) langTag \0 transKeyword \0 text
    private fun parseITXt(b: ByteArray, start: Int, len: Int): Pair<String, String>? {
        val end = start + len
        val nul1 = indexOf0(b, start, end) ?: return null
        val key = String(b, start, nul1 - start, Charsets.ISO_8859_1)
        if (nul1 + 2 >= end) return null
        val compFlag = b[nul1 + 1].toInt()
        // 跳过 compFlag(1)+compMethod(1)，再跳过 langTag\0 与 transKeyword\0
        val nul2 = indexOf0(b, nul1 + 3, end) ?: return null   // langTag 结束
        val nul3 = indexOf0(b, nul2 + 1, end) ?: return null   // transKeyword 结束
        val textStart = nul3 + 1
        if (textStart > end) return null
        val text = if (compFlag == 1) inflate(b, textStart, end - textStart) ?: return null
        else String(b, textStart, end - textStart, Charsets.UTF_8)
        return key to text
    }

    private fun indexOf0(b: ByteArray, from: Int, to: Int): Int? {
        for (i in from until to) if (b[i].toInt() == 0) return i
        return null
    }

    private fun inflate(b: ByteArray, off: Int, len: Int): String? = try {
        val inf = Inflater(); inf.setInput(b, off, len)
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16 * 1024)
        while (!inf.finished()) {
            val n = inf.inflate(buf); if (n == 0 && inf.needsInput()) break
            out.write(buf, 0, n)
        }
        inf.end(); out.toString("UTF-8")
    } catch (_: Exception) { null }

    private fun decodeMaybeBase64(s: String): String? = try {
        val cleaned = s.filterNot { it == '\n' || it == '\r' || it == ' ' }
        String(Base64.decode(cleaned, Base64.DEFAULT), Charsets.UTF_8)
    } catch (_: Exception) { null }

    // ============================================================
    // 酒馆 v2 spec 里 ImportConverters.normalizeCard 目前不落的三项——多开场白/越狱指令/深度提示。
    // 它们不进 CharacterCardEntity（DB 字段），走 CardRoleplayStore 旁路存储，所以这里单独抽出来解析，
    // 不改 ImportConverters.kt（那边只管落 DB 字段的那一份归一化）。
    // 输入不限于 PNG 解出来的 JSON——扫码/粘贴导入的卡 JSON 也是同一套字段，同样能喂给这个函数。
    // ============================================================
    data class RoleplayExtras(
        val alternateGreetings: List<String>,
        val postHistoryInstructions: String,
        val depthPromptDepth: Int?,   // null = 卡里没写深度，交给调用方套默认值
        val depthPromptRole: String,
        val depthPromptText: String,
    )

    /** 从角色卡原始 JSON 解出这三项；认不出的字段就给空值，不抛异常（跟 ImportConverters 一个宽容原则）。 */
    fun extractRoleplayExtras(raw: JSONObject): RoleplayExtras {
        val o = unwrapOne(raw)
        val greetings = mutableListOf<String>()
        (o.optJSONArray("alternate_greetings") ?: o.optJSONArray("alternateGreetings"))?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i).trim().takeIf { it.isNotBlank() }?.let { greetings.add(it) }
        }
        val phi = firstNonBlank(o, "post_history_instructions", "postHistoryInstructions", "jailbreak")
        // 酒馆把「按深度插入的角色笔记」塞在 extensions.depth_prompt 里；也认顶层/我们自己镜像导出的写法
        val dp = o.optJSONObject("extensions")?.optJSONObject("depth_prompt")
            ?: o.optJSONObject("depth_prompt") ?: o.optJSONObject("depthPrompt")
        val depth = dp?.let { if (it.has("depth")) it.optInt("depth", 4) else null }
        val role = roleFrom(dp)
        val text = dp?.let { firstNonBlank(it, "prompt", "text") } ?: ""
        return RoleplayExtras(greetings, phi, depth, role, text)
    }

    private fun firstNonBlank(o: JSONObject, vararg keys: String): String {
        for (k in keys) { val v = o.optString(k, ""); if (v.isNotBlank()) return v }
        return ""
    }

    // depth_prompt.role 各家写法不一：字符串 system/user/assistant，或酒馆世界书同款的数字编码(0/1/2)。都认。
    private fun roleFrom(dp: JSONObject?): String = when (val v = dp?.opt("role")) {
        is String -> when (v.lowercase()) { "user" -> "user"; "assistant" -> "assistant"; else -> "system" }
        is Number -> when (v.toInt()) { 1 -> "user"; 2 -> "assistant"; else -> "system" }
        else -> "system"
    }

    /** 只做「一层」下钻剥掉酒馆 V2 的 {data:{...}} 外壳，够认 alternate_greetings 等字段所在的层。
     * 跟 ImportConverters.unwrap 是同样的思路各自维护一份（那边管落 DB 字段，不在本文件改动范围内）。 */
    private fun unwrapOne(o: JSONObject): JSONObject {
        val inner = o.optJSONObject("data") ?: return o
        return if (inner.has("name") || inner.has("first_mes") || inner.has("alternate_greetings") ||
            inner.has("post_history_instructions")) inner else o
    }
}
