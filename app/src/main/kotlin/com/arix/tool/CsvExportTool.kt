package com.arix.tool

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ============================================================
// export_csv —— 把 AI 给的表格数据导出成 .csv 文件到系统「下载」目录。
// 写文件走 MarkdownText.saveCodeToFile 同款：Android 10+ 用 MediaStore.Downloads（免权限、可见），
// 更低版本落应用外部下载目录。CSV 用 \r\n 分隔、UTF-8 带 BOM（让 Excel 正确识别中文）。
// ============================================================
class CsvExportTool(private val context: Context) : Tool {
    override val name = "export_csv"
    override val description =
        "把表格数据导出成 .csv 文件保存到系统「下载」目录（Excel/WPS 可直接打开，中文不乱码）。" +
            "filename=文件名（不含扩展名也行，会自动补 .csv）；data=表格数据，支持两种写法：" +
            "① 二维数组，每个子数组一行，第一行通常是表头，如 [[\"姓名\",\"年龄\"],[\"张三\",18]]；" +
            "② 对象数组，每个对象一行，自动用键名作表头（按第一个对象的键顺序，并入所有对象出现过的键），" +
            "如 [{\"姓名\":\"张三\",\"年龄\":18},{\"姓名\":\"李四\",\"年龄\":20}]。适合把统计/清单/导出结果落成文件给用户。"

    override val permissionLevel = AndroidPermissionLevel.STANDARD

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("filename", JSONObject().apply {
                put("type", "string")
                put("description", "保存的文件名，不含扩展名也可（自动补 .csv），如『销售统计』")
            })
            put("data", JSONObject().apply {
                put("type", "array")
                put(
                    "description",
                    "表格数据：二维数组 [[\"a\",\"b\"],[1,2]]（每行一个子数组），或对象数组 [{\"名\":\"x\",\"值\":1}]（键名作表头）",
                )
            })
        })
        put("required", JSONArray(listOf("filename", "data")))
    }

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val rawName = params.optString("filename", "").trim()
            if (rawName.isBlank()) return@withContext ToolResult("请提供 filename（文件名）", isError = true)

            val data = params.optJSONArray("data")
            if (data == null || data.length() == 0)
                return@withContext ToolResult("data 为空：请提供二维数组或对象数组形式的表格数据", isError = true)

            // 组装成二维行表（List<List<String>>）
            val rows = buildRows(data)
                ?: return@withContext ToolResult("data 格式无法识别：请传二维数组或对象数组", isError = true)
            if (rows.isEmpty()) return@withContext ToolResult("没有可导出的数据行", isError = true)

            // CSV 文本：\r\n 行分隔
            val csv = rows.joinToString("\r\n") { row -> row.joinToString(",") { escape(it) } } + "\r\n"
            // UTF-8 带 BOM，让 Excel 正确按 UTF-8 识别中文
            val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + csv.toByteArray(Charsets.UTF_8)

            val fileName = sanitizeFileName(rawName)
            val savedPath = saveToDownloads(fileName, bytes)
                ?: return@withContext ToolResult("保存失败：无法写入下载目录", isError = true)

            ToolResult("已导出 CSV：$fileName（${rows.size} 行）\n保存位置：$savedPath")
        } catch (c: CancellationException) {
            throw c   // STOP 要能中断
        } catch (e: Exception) {
            ToolResult("导出失败：${e.message}", isError = true)
        }
    }

    // ---- data → 二维行表 ----
    private fun buildRows(data: JSONArray): List<List<String>>? {
        val first = data.opt(0)
        return when (first) {
            is JSONArray -> {
                // 二维数组：每个子数组一行
                (0 until data.length()).map { i ->
                    val arr = data.optJSONArray(i) ?: JSONArray()
                    (0 until arr.length()).map { j -> cellToString(arr.opt(j)) }
                }
            }
            is JSONObject -> {
                // 对象数组：表头 = 键的并集（按首个对象顺序，其余对象的新键追加在后）
                val headers = LinkedHashSet<String>()
                (0 until data.length()).forEach { i ->
                    data.optJSONObject(i)?.let { obj -> obj.keys().forEach { headers.add(it) } }
                }
                val headerList = headers.toList()
                val out = ArrayList<List<String>>()
                out.add(headerList)                                  // 首行表头
                (0 until data.length()).forEach { i ->
                    val obj = data.optJSONObject(i)
                    out.add(headerList.map { key -> if (obj != null && obj.has(key)) cellToString(obj.opt(key)) else "" })
                }
                out
            }
            else -> null
        }
    }

    // 单元格值 → 字符串（null/JSONObject.NULL 视为空）
    private fun cellToString(v: Any?): String = when {
        v == null || v === JSONObject.NULL -> ""
        else -> v.toString()
    }

    // CSV 转义：含逗号/双引号/换行(含\r)时用双引号包裹，内部 " → ""
    private fun escape(field: String): String {
        // 防公式注入：Excel/WPS 会把以 = + - @ 或 tab/回车 开头的单元格当公式执行（可被用于窃取数据/执行命令）→ 前缀单引号中和
        val c = field.firstOrNull()
        val f = if (c != null && c in charArrayOf('=', '+', '-', '@', '\t', '\r')) "'$field" else field
        val needQuote = f.contains(',') || f.contains('"') || f.contains('\n') || f.contains('\r')
        if (!needQuote) return f
        return "\"" + f.replace("\"", "\"\"") + "\""
    }

    // 文件名清洗 + 自动补 .csv 扩展名
    private fun sanitizeFileName(raw: String): String {
        var name = raw.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").trim().trim('.')
        if (name.isBlank()) name = "export"
        if (name.length > 100) name = name.substring(0, 100)   // 防超长文件名触发 ENAMETOOLONG
        if (!name.lowercase().endsWith(".csv")) name += ".csv"
        return name
    }

    // 写入下载目录：Android 10+ 走 MediaStore.Downloads（免权限、可见）；更低版本落应用外部下载目录。
    private fun saveToDownloads(fileName: String, bytes: ByteArray): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            // 写失败(含取消/磁盘满)必须删掉这条 pending 记录，否则在下载库里留一条 7 天才自动清的隐形条目
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: run { context.contentResolver.delete(uri, null, null); return null }
            } catch (e: Throwable) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                throw e
            }
            // 解除 pending，别的 App 才看得到文件
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            return "下载/$fileName"
        }
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        val f = File(dir, fileName)
        f.writeBytes(bytes)
        return f.absolutePath
    }
}
