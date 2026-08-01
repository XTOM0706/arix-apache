package com.arix.tool

import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 通讯录查询：按名字查联系人的电话号码，配合 make_phone_call / send_sms 用（「给张三打电话」→ 先查号）。
 * 只读，需通讯录权限（权限页「通讯录」授予）。
 */
class ContactsTool(private val context: Context) : Tool {
    override val name = "contacts"

    /** 读的是通讯录本体。见 [AndroidPermissionLevel.PRIVATE]：默认问一次、不进模型自动审批。 */
    override val permissionLevel = AndroidPermissionLevel.PRIVATE

    override val description = "按名字查通讯录联系人的电话号码（可留空列出部分联系人）。配合打电话/发短信用。需通讯录权限。"
    override val llmDescription = "Look up a contact's phone number by name; empty name lists some contacts. Pair it with calling or SMS."
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("name", JSONObject().apply { put("type", "string"); put("description", "name, fuzzy; empty lists some") })
            put("limit", JSONObject().apply { put("type", "integer"); put("description", "max results, default 10") })
        })
        put("required", JSONArray(listOf<String>()))
    }

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            return@withContext ToolResult("没有通讯录权限，请在「权限」页授予后再试", isError = true)
        try {
            val name = params.optString("name", "").trim()
            val limit = params.optInt("limit", 10).coerceIn(1, 50)
            val proj = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            )
            val sel = if (name.isBlank()) null else "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val args = if (name.isBlank()) null else arrayOf("%$name%")
            val seen = LinkedHashSet<String>()   // 去重（一个人多号也各列一次，但完全相同的行去掉）
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, sel, args,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { c ->
                val ni = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val pi = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext() && seen.size < limit) {
                    val dn = if (ni >= 0) c.getString(ni)?.trim().orEmpty() else ""
                    val num = if (pi >= 0) c.getString(pi)?.replace(" ", "")?.trim().orEmpty() else ""
                    if (num.isBlank()) continue
                    seen.add("· ${dn.ifBlank { "(无名)" }}：$num")
                }
            }
            if (seen.isEmpty())
                ToolResult(if (name.isBlank()) "通讯录为空或读不到" else "没找到名字含「$name」的联系人")
            else ToolResult(seen.joinToString("\n"))
        } catch (e: Exception) { ToolResult("查通讯录失败：${e.message}", isError = true) }
    }
}
