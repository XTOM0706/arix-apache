package com.arix.tool

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

// ============================================================
// AI 私有工作目录（沙盒）—— AI 干活的文件全部锁在 filesDir/ai_workspace，
// 禁止逃逸到外部/系统路径（绝对路径、".." 越界一律拒绝）。这是安全边界。
// ============================================================
object AiWorkspace {
    fun root(context: Context): File = File(context.filesDir, "ai_workspace").apply { if (!exists()) mkdirs() }

    /** 沙盒解析：路径限制在工作区内；逃逸（绝对路径出界 / .. 越界）返回 null。 */
    fun resolve(context: Context, path: String): File? {
        val base = try { root(context).canonicalFile } catch (_: Exception) { return null }
        val raw = if (path.startsWith("/")) File(path) else File(base, path)
        val f = try { raw.canonicalFile } catch (_: Exception) { return null }
        return if (f.path == base.path || f.path.startsWith(base.path + File.separator)) f else null
    }

    fun deny() = ToolResult("路径越界：AI 只能在自己的工作目录内操作（用相对路径），不能碰外部/系统路径。", isError = true)
}

// ============================================================
// 系统「下载」目录落盘 —— 沙盒外唯一的出口，给**用户要拿走的东西**用
//（下载的文件、导出的 PDF/DOCX/CSV）。AI 自己干活的中间产物一律留在 AiWorkspace。
//
// 写法与 export_csv 一致：Android 10+ 走 MediaStore.Downloads（免存储权限、在「下载」里可见），
// 更低版本落应用外部下载目录。写失败必须删掉 pending 记录，否则下载库里会留一条 7 天才自动清的隐形条目。
// ============================================================
internal object DownloadsSink {
    /** 文件名清洗：去掉路径分隔符与非法字符，限长，空则给默认名。 */
    fun sanitize(raw: String, fallback: String = "download.bin"): String {
        var name = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").trim().trim('.')
        if (name.isBlank()) name = fallback
        if (name.length > 100) {
            // 超长名会触发 ENAMETOOLONG；截前缀但保住扩展名（扩展名决定用什么 App 打开）
            val ext = name.substringAfterLast('.', "").take(12)
            name = name.take(if (ext.isBlank()) 100 else 100 - ext.length - 1) + if (ext.isBlank()) "" else ".$ext"
        }
        return name
    }

    /**
     * 写一个文件到系统「下载」目录，返回给用户看的位置（失败 null）。
     * [write] 是挂起函数：下载那种边收边写的场景要在里面 ensureActive，STOP 才停得掉。
     */
    suspend fun save(context: Context, fileName: String, mime: String, write: suspend (OutputStream) -> Unit): String? =
        withContext(Dispatchers.IO) {
            val name = sanitize(fileName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext null
                try {
                    context.contentResolver.openOutputStream(uri)?.use { write(it) }
                        ?: run { context.contentResolver.delete(uri, null, null); return@withContext null }
                } catch (e: Throwable) {
                    // 含协程取消：半截文件不能留在下载库里冒充成品
                    runCatching { context.contentResolver.delete(uri, null, null) }
                    throw e
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                return@withContext "下载/$name"
            }
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return@withContext null
            val f = File(dir, name)
            try { f.outputStream().use { write(it) } } catch (e: Throwable) { runCatching { f.delete() }; throw e }
            f.absolutePath
        }
}

/** 行级 diff：找出公共首尾，中间差异以 -删除 / +新增 呈现（供 file_edit/file_write 显示「改了什么」）。 */
internal fun lineDiff(old: String, new: String, maxLines: Int = 60): String {
    if (old == new) return "（无变化）"
    val a = old.split("\n"); val b = new.split("\n")
    var p = 0
    while (p < a.size && p < b.size && a[p] == b[p]) p++
    var sa = a.size - 1; var sb = b.size - 1
    while (sa >= p && sb >= p && a[sa] == b[sb]) { sa--; sb-- }
    val removed = if (sa >= p) a.subList(p, sa + 1) else emptyList()
    val added = if (sb >= p) b.subList(p, sb + 1) else emptyList()
    return buildString {
        append("@@ 第 ${p + 1} 行附近 @@\n")
        removed.take(maxLines).forEach { append("- ").append(it).append("\n") }
        if (removed.size > maxLines) append("… 另删 ${removed.size - maxLines} 行\n")
        added.take(maxLines).forEach { append("+ ").append(it).append("\n") }
        if (added.size > maxLines) append("… 另加 ${added.size - maxLines} 行\n")
    }.trim()
}

// 只读文件工具合一：read(读文本，默认) / list(列目录) / exists(查存在)。全是只读、STANDARD 级（自动放行），
// 与写操作 [FileOpTool] 分开——正好卡在权限边界上：只读自动放行、改动要授权，合并不破坏这条界线。
class FileReadTool(private val context: Context) : Tool {
    override val name = "file_read"
    override val description = "查看工作目录里的文件/目录（只读）。action：read=读文本文件(带行号；offset起始行/limit行数/pattern正则筛行)、list=列目录内容、exists=查路径是否存在。默认 read。path 用相对路径。"
    private val listTool by lazy { FileListTool(context) }
    private val existsTool by lazy { FileExistsTool(context) }
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("read", "list", "exists"))); put("description", "read=读文件(默认) / list=列目录 / exists=查存在") })
            put("path", JSONObject().apply { put("type", "string"); put("description", "路径（相对工作目录）；list 可留空=根目录") })
            put("offset", JSONObject().apply { put("type", "integer"); put("description", "起始行号(1起，可选，仅 read)") })
            put("limit", JSONObject().apply { put("type", "integer"); put("description", "读取行数(可选，默认全部/上限约500行，仅 read)") })
            put("pattern", JSONObject().apply { put("type", "string"); put("description", "正则筛选：只返回匹配的行(含行号，仅 read)") })
        })
        put("required", JSONArray(listOf("path")))
    }
    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        when (params.optString("action", "read").trim().lowercase()) {
            "list" -> return@withContext listTool.execute(params)
            "exists" -> return@withContext existsTool.execute(params)
        }
        // action=read（默认）
        val f = AiWorkspace.resolve(context, params.optString("path", "")) ?: return@withContext AiWorkspace.deny()
        if (!f.exists() || !f.isFile) return@withContext ToolResult("文件不存在: ${f.name}", isError = true)
        try {
            val lines = f.readText().split("\n")
            val pat = params.optString("pattern", "")
            if (pat.isNotBlank()) {
                val rx = runCatching { Regex(pat) }.getOrNull() ?: return@withContext ToolResult("正则无效: $pat", isError = true)
                val hits = lines.mapIndexed { i, l -> i + 1 to l }.filter { rx.containsMatchIn(it.second) }
                if (hits.isEmpty()) {
                    // 正则零命中多半是拼写/大小写记错，模糊兜一层，别让 AI 误以为文件里根本没有
                    val fz = FuzzyMatch.rank(pat, lines.mapIndexed { i, l -> i + 1 to l }, 50) { it.second }
                    if (fz.isNotEmpty()) return@withContext ToolResult(
                        "「$pat」无精确匹配，以下为模糊匹配的行：\n" +
                            fz.joinToString("\n") { "${it.item.first}\t${it.item.second}" })
                    return@withContext ToolResult("没有匹配「$pat」的行")
                }
                return@withContext ToolResult(hits.take(200).joinToString("\n") { "${it.first}\t${it.second}" })
            }
            val start = (params.optInt("offset", 1).coerceAtLeast(1)) - 1
            val count = params.optInt("limit", 500).coerceIn(1, 2000)
            val slice = lines.drop(start).take(count)
            val body = slice.mapIndexed { i, l -> "${start + i + 1}\t$l" }.joinToString("\n")
            val more = if (start + slice.size < lines.size) "\n… 共 ${lines.size} 行，还有 ${lines.size - start - slice.size} 行未显示（用 offset/limit 继续）" else ""
            ToolResult(body.take(9000) + more)
        } catch (e: Exception) { ToolResult("读取失败: ${e.message}", isError = true) }
    }
}

class FileWriteTool(private val context: Context) : Tool {
    override val name = "file_write"
    override val description = "写入文本到工作目录的文件（覆盖）。append=true 追加。"
    override val permissionLevel = AndroidPermissionLevel.ACCESSIBILITY
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("path", JSONObject().apply { put("type", "string"); put("description", "文件路径（相对工作目录）") })
            put("content", JSONObject().apply { put("type", "string"); put("description", "要写入的内容") })
            put("append", JSONObject().apply { put("type", "boolean"); put("description", "是否追加，默认false") })
        })
        put("required", JSONArray(listOf("path", "content")))
    }
    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val f = AiWorkspace.resolve(context, params.optString("path", "")) ?: return@withContext AiWorkspace.deny()
        try {
            f.parentFile?.mkdirs()
            val content = params.optString("content", "")
            val append = params.optBoolean("append", false)
            val old = if (!append && f.exists() && f.isFile) runCatching { f.readText() }.getOrNull() else null
            // 改动前抓快照：写下去就没了，只能在这一刻留后悔药（追加也算改动，同样记）
            val pending = FileHistory.capture(context, f)
            if (append) f.appendText(content) else f.writeText(content)
            FileHistory.commit(context, pending, FileHistory.OP_WRITE, f)
            // 覆盖已有文件时显示改了什么
            if (old != null && old != content) ToolResult("已覆盖 ${f.name}（${old.length}→${content.length} 字符）\n${lineDiff(old, content)}")
            else ToolResult("已${if (append) "追加" else "写入"} ${f.name}（${content.length} 字符）")
        } catch (e: Exception) { ToolResult("写入失败: ${e.message}", isError = true) }
    }
}

class FileEditTool(private val context: Context) : Tool {
    override val name = "file_edit"
    override val description = "精确改文件：把 old_string 替换成 new_string（old_string 要能在文件里唯一定位；要替换多处用 replace_all=true）。返回改动的 diff（-删除 / +新增），比整篇覆盖 file_write 更安全、能看清改了什么。"
    override val permissionLevel = AndroidPermissionLevel.ACCESSIBILITY
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("path", JSONObject().apply { put("type", "string"); put("description", "文件路径（相对工作目录）") })
            put("old_string", JSONObject().apply { put("type", "string"); put("description", "要被替换的原文（含足够上下文以唯一定位）") })
            put("new_string", JSONObject().apply { put("type", "string"); put("description", "替换成的新内容") })
            put("replace_all", JSONObject().apply { put("type", "boolean"); put("description", "替换所有匹配，默认false（要求唯一）") })
        })
        put("required", JSONArray(listOf("path", "old_string", "new_string")))
    }
    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val f = AiWorkspace.resolve(context, params.optString("path", "")) ?: return@withContext AiWorkspace.deny()
        if (!f.exists() || !f.isFile) return@withContext ToolResult("文件不存在: ${f.name}", isError = true)
        val oldStr = params.optString("old_string", "")
        val newStr = params.optString("new_string", "")
        if (oldStr.isEmpty()) return@withContext ToolResult("old_string 不能为空", isError = true)
        if (oldStr == newStr) return@withContext ToolResult("old_string 与 new_string 相同，无需修改", isError = true)
        try {
            val content = f.readText()
            val n = content.split(oldStr).size - 1
            if (n == 0) return@withContext ToolResult("没找到要替换的内容（old_string 在文件里不存在，检查是否逐字一致）", isError = true)
            val all = params.optBoolean("replace_all", false)
            if (n > 1 && !all) return@withContext ToolResult("old_string 不唯一（出现 $n 次），请补更长的上下文，或设 replace_all=true 替换全部", isError = true)
            val newContent = if (all) content.replace(oldStr, newStr) else content.replaceFirst(oldStr, newStr)
            val pending = FileHistory.capture(context, f)
            f.writeText(newContent)
            FileHistory.commit(context, pending, FileHistory.OP_EDIT, f)
            ToolResult("已修改 ${f.name}${if (all) "（$n 处）" else ""}\n${lineDiff(content, newContent)}")
        } catch (e: Exception) { ToolResult("修改失败: ${e.message}", isError = true) }
    }
}

class FileListTool(private val context: Context) : Tool {
    override val name = "file_list"
    override val description = "列出工作目录（或其子目录）内容。path 留空=工作目录根。"
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply { put("path", JSONObject().apply { put("type", "string"); put("description", "子目录路径，可选") }) })
        put("required", JSONArray())
    }
    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val dir = AiWorkspace.resolve(context, params.optString("path", "")) ?: return@withContext AiWorkspace.deny()
        if (!dir.exists() || !dir.isDirectory) return@withContext ToolResult("目录不存在", isError = true)
        val items = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
        if (items.isEmpty()) return@withContext ToolResult("(空)")
        ToolResult(items.joinToString("\n") { "${if (it.isDirectory) "📁" else "📄"} ${it.name}${if (it.isFile) "  (${it.length()}B)" else ""}" })
    }
}

class FileDeleteTool(private val context: Context) : Tool {
    override val name = "file_delete"
    override val description = "删除工作目录里的文件或目录。"
    override val permissionLevel = AndroidPermissionLevel.ACCESSIBILITY
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply { put("path", JSONObject().apply { put("type", "string"); put("description", "要删除的路径") }) })
        put("required", JSONArray(listOf("path")))
    }
    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val f = AiWorkspace.resolve(context, params.optString("path", "")) ?: return@withContext AiWorkspace.deny()
        if (!f.exists()) return@withContext ToolResult("不存在", isError = true)
        try {
            // 删除是最不可逆的操作，快照尤其重要（目录/超大文件存不下会在记录里标明「不可回滚」，不假装能退）
            val pending = FileHistory.capture(context, f)
            if (f.deleteRecursively()) {
                FileHistory.commit(context, pending, FileHistory.OP_DELETE, f)
                ToolResult("已删除 ${f.name}")
            } else ToolResult("删除失败", isError = true)
        }
        catch (e: Exception) { ToolResult("删除失败: ${e.message}", isError = true) }
    }
}

class FileMoveTool(private val context: Context) : Tool {
    override val name = "file_move"
    override val description = "移动/重命名工作目录里的文件或目录。"
    override val permissionLevel = AndroidPermissionLevel.ACCESSIBILITY
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("from", JSONObject().apply { put("type", "string"); put("description", "源路径") })
            put("to", JSONObject().apply { put("type", "string"); put("description", "目标路径") })
        })
        put("required", JSONArray(listOf("from", "to")))
    }
    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val src = AiWorkspace.resolve(context, params.optString("from", "")) ?: return@withContext AiWorkspace.deny()
        val dst = AiWorkspace.resolve(context, params.optString("to", "")) ?: return@withContext AiWorkspace.deny()
        if (!src.exists()) return@withContext ToolResult("源不存在", isError = true)
        try {
            dst.parentFile?.mkdirs()
            val pending = FileHistory.capture(context, src)
            if (src.renameTo(dst)) {
                // move 的回滚只需把文件移回去，不依赖快照，所以目录/大文件也能退
                // 用规范化后的相对路径，而不是 AI 传的原始 to（可能是绝对路径或含 ..，回滚时对不上）
                FileHistory.commit(context, pending, FileHistory.OP_MOVE, dst, toPath = FileHistory.relOf(context, dst))
                ToolResult("已移动到 ${dst.name}")
            } else ToolResult("移动失败", isError = true)
        }
        catch (e: Exception) { ToolResult("移动失败: ${e.message}", isError = true) }
    }
}

class FileCopyTool(private val context: Context) : Tool {
    override val name = "file_copy"
    override val description = "复制工作目录里的文件。"
    override val permissionLevel = AndroidPermissionLevel.ACCESSIBILITY
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("from", JSONObject().apply { put("type", "string"); put("description", "源文件") })
            put("to", JSONObject().apply { put("type", "string"); put("description", "目标路径") })
        })
        put("required", JSONArray(listOf("from", "to")))
    }
    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val src = AiWorkspace.resolve(context, params.optString("from", "")) ?: return@withContext AiWorkspace.deny()
        val dst = AiWorkspace.resolve(context, params.optString("to", "")) ?: return@withContext AiWorkspace.deny()
        if (!src.exists() || !src.isFile) return@withContext ToolResult("源文件不存在", isError = true)
        try { dst.parentFile?.mkdirs(); src.copyTo(dst, overwrite = true); ToolResult("已复制到 ${dst.name}") }
        catch (e: Exception) { ToolResult("复制失败: ${e.message}", isError = true) }
    }
}

class MakeDirTool(private val context: Context) : Tool {
    override val name = "make_directory"
    override val description = "在工作目录里创建目录（含多级）。"
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply { put("path", JSONObject().apply { put("type", "string"); put("description", "目录路径") }) })
        put("required", JSONArray(listOf("path")))
    }
    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val d = AiWorkspace.resolve(context, params.optString("path", "")) ?: return@withContext AiWorkspace.deny()
        try { if (d.mkdirs() || d.isDirectory) ToolResult("已创建目录 ${d.name}") else ToolResult("创建失败", isError = true) }
        catch (e: Exception) { ToolResult("创建失败: ${e.message}", isError = true) }
    }
}

class FileExistsTool(private val context: Context) : Tool {
    override val name = "file_exists"
    override val description = "检查工作目录里某路径是否存在，返回类型/大小。"
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply { put("path", JSONObject().apply { put("type", "string"); put("description", "路径") }) })
        put("required", JSONArray(listOf("path")))
    }
    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val f = AiWorkspace.resolve(context, params.optString("path", "")) ?: return@withContext AiWorkspace.deny()
        if (!f.exists()) return@withContext ToolResult("不存在")
        ToolResult(if (f.isDirectory) "存在（目录）" else "存在（文件，${f.length()}B）")
    }
}

// ============================================================
// 文件写操作合一：write/edit/delete/move/copy/mkdir/zip/unzip 收进一个工具，按 action 分派到各原工具实现
//（沙盒/历史快照/diff 逻辑一字不改地复用）。目的：减少工具数量、降低模型「选错文件动词」的幻觉。
// 权限等级取这组里最高的「无障碍」——写/删/移动本就要用户放行(ASK)，与拆开时完全一致；
// 只读的 read/list/exists 仍在 [FileReadTool]（STANDARD，自动放行）。故合并不改变权限边界。
// ============================================================
class FileOpTool(private val context: Context) : Tool {
    override val name = "file_op"
    override val description = "改动工作目录里的文件（会向用户申请授权）。action：write=写入(覆盖，append=true 追加)、edit=精确替换(old_string→new_string，唯一定位；replace_all 全替)、delete=删除、move=移动/重命名(from→to)、copy=复制(from→to)、mkdir=建多级目录、zip=压缩(path→dest.zip)、unzip=解压(path.zip→dest目录)。只读的查看/列目录/查存在用 file_read。所有路径都相对工作目录、限沙盒内。"
    override val permissionLevel = AndroidPermissionLevel.ACCESSIBILITY
    private val write by lazy { FileWriteTool(context) }
    private val edit by lazy { FileEditTool(context) }
    private val delete by lazy { FileDeleteTool(context) }
    private val move by lazy { FileMoveTool(context) }
    private val copy by lazy { FileCopyTool(context) }
    private val mkdir by lazy { MakeDirTool(context) }
    private val archive by lazy { FileArchiveTool(context) }
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("write", "edit", "delete", "move", "copy", "mkdir", "zip", "unzip"))); put("description", "要做的操作") })
            put("path", JSONObject().apply { put("type", "string"); put("description", "目标路径（write/edit/delete/mkdir，及 zip/unzip 的源）") })
            put("content", JSONObject().apply { put("type", "string"); put("description", "写入内容（write）") })
            put("append", JSONObject().apply { put("type", "boolean"); put("description", "追加而非覆盖（write，默认 false）") })
            put("old_string", JSONObject().apply { put("type", "string"); put("description", "被替换的原文，需能唯一定位（edit）") })
            put("new_string", JSONObject().apply { put("type", "string"); put("description", "替换成的新内容（edit）") })
            put("replace_all", JSONObject().apply { put("type", "boolean"); put("description", "替换全部匹配（edit，默认 false 要求唯一）") })
            put("from", JSONObject().apply { put("type", "string"); put("description", "源路径（move/copy）") })
            put("to", JSONObject().apply { put("type", "string"); put("description", "目标路径（move/copy）") })
            put("dest", JSONObject().apply { put("type", "string"); put("description", "zip 输出 .zip 路径 / unzip 解压到的目录") })
        })
        put("required", JSONArray(listOf("action")))
    }
    // 各原工具自己已 withContext(Dispatchers.IO) + 走沙盒/快照，这里只做纯分派。zip/unzip 交给 archive，
    // 它内部按 params 的 action 字段区分——与本工具 action 同名，直接透传即可。
    override suspend fun execute(params: JSONObject): ToolResult =
        when (params.optString("action", "").trim().lowercase()) {
            "write" -> write.execute(params)
            "edit" -> edit.execute(params)
            "delete", "remove", "rm" -> delete.execute(params)
            "move", "rename", "mv" -> move.execute(params)
            "copy", "cp" -> copy.execute(params)
            "mkdir", "make_directory", "makedir" -> mkdir.execute(params)
            "zip", "unzip" -> archive.execute(params)
            "" -> ToolResult("缺少 action。可选：write/edit/delete/move/copy/mkdir/zip/unzip", isError = true)
            else -> ToolResult("未知 action「${params.optString("action")}」。可选：write/edit/delete/move/copy/mkdir/zip/unzip", isError = true)
        }
}

// ============================================================
// HTTP 请求工具（对齐 Operit extended_http_tools）—— GET/POST/PUT/DELETE + headers + body
// ============================================================
class HttpRequestTool(private val context: Context) : Tool {
    override val name = "http_request"
    override val description = "发起 HTTP 请求调 REST API，或把 URL 指向的文件下载到本机。method(GET/POST/PUT/DELETE)、headers(JSON)、body；" +
        "save_to=把响应体存成文件（配 save_dir 选存到系统「下载」目录给用户、还是存进工作目录留着接着处理）；" +
        "另支持 cookies(Cookie 头)、upload(工作目录里的文件做 multipart 上传，配 form 附加字段)、insecure(跳过 SSL 校验，仅自签名内网用)。"
    // 模型侧英文（见 Tool.llmDescription）
    override val llmDescription = "Call a REST API, or download a file. method (GET/POST/PUT/DELETE), headers (JSON), body. " +
        "Set save_to to stream the response body into a file instead of returning it — save_dir=downloads puts it in the user's Downloads folder, " +
        "save_dir=workspace keeps it in your private workspace for further processing (e.g. an .apk to install, an image returned by open_page mode=images/video). " +
        "Also supports cookies, upload (multipart from a workspace file, with form fields) and insecure (skip SSL checks, self-signed intranet only)."
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("url", JSONObject().apply { put("type", "string"); put("description", "请求 URL") })
            put("method", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("GET", "POST", "PUT", "DELETE"))); put("description", "方法，默认 GET；有 upload 时自动 POST") })
            put("headers", JSONObject().apply { put("type", "string"); put("description", "请求头 JSON，如 {\"Authorization\":\"Bearer x\"}") })
            put("body", JSONObject().apply { put("type", "string"); put("description", "请求体（POST/PUT，无 upload 时用）") })
            put("cookies", JSONObject().apply { put("type", "string"); put("description", "Cookie 串，如 a=1; b=2") })
            put("upload", JSONObject().apply { put("type", "string"); put("description", "要上传的文件路径（相对 AI 工作目录）；设了就走 multipart/form-data") })
            put("form", JSONObject().apply { put("type", "string"); put("description", "multipart 附加表单字段 JSON，如 {\"name\":\"x\"}") })
            put("insecure", JSONObject().apply { put("type", "boolean"); put("description", "跳过 SSL 证书校验（默认 false，只在内网自签名时开）") })
            put("save_to", JSONObject().apply { put("type", "string"); put("description", "Save the response body to this file name/path instead of returning it. Use \"auto\" to take the name from the URL or Content-Disposition") })
            put("save_dir", JSONObject().apply {
                put("type", "string"); put("enum", JSONArray(listOf("downloads", "workspace")))
                put("description", "Where save_to lands: downloads = the user's Downloads folder (default, user can open it); workspace = your private sandbox (needed if you want to process the file afterwards)")
            })
        })
        put("required", JSONArray(listOf("url")))
    }
    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val urlStr = params.optString("url", "").ifBlank { return@withContext ToolResult("请提供 URL", isError = true) }
        // SSRF 闸：这工具是 STANDARD 级、默认自动放行，一旦被插件（尤其被盗令牌的可信插件）拿到，
        // 就是一把打内网/环回/云元数据端点的枪。解析出主机后核对，环回/私网/链路本地/元数据一律拒。
        // 只认 http/https，挡掉 file://、gopher:// 之类。DNS rebinding 这里挡不全（真要防得解析 IP 再连），
        // 但字面量与常见内网名先堵住，成本低、拦得住绝大多数。
        blockedHost(urlStr)?.let { return@withContext ToolResult("拒绝请求：$it（http_request 不允许访问内网/本机/云元数据地址）", isError = true) }
        val uploadPath = params.optString("upload", "")
        val savePath = params.optString("save_to", "").trim()
        val method = params.optString("method", if (uploadPath.isNotBlank()) "POST" else "GET").uppercase()
        // upload 文件在循环外只解析一次（沙盒内）
        val uploadFile = if (uploadPath.isNotBlank()) {
            val f = AiWorkspace.resolve(context, uploadPath) ?: return@withContext AiWorkspace.deny()
            if (!f.exists()) return@withContext ToolResult("上传文件不存在: ${f.name}", isError = true)
            f
        } else null
        try {
            var currentUrl = urlStr
            var currentMethod = method
            var sendBody = true    // 301/302/303 对 POST 会降级为 GET 并丢弃 body（标准语义）
            // 手动逐跳跟随重定向：**每一跳都重新做 SSRF 校验**，堵住「公网 302 → 内网/云元数据」这条绕过。
            // instanceFollowRedirects=false 关掉 HttpURLConnection 的自动跟随（默认 true，会跟进内网不复检）。最多 5 跳。
            for (hop in 0..5) {
                val conn = URL(currentUrl).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                // 跳过 SSL 校验（仅显式 insecure=true）：内网自签名场景，须在连接前设 socketFactory
                if (params.optBoolean("insecure", false) && conn is javax.net.ssl.HttpsURLConnection) {
                    val tm = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                        override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
                        override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
                        override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
                    })
                    val sc = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, tm, java.security.SecureRandom()) }
                    conn.sslSocketFactory = sc.socketFactory; conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                }
                conn.requestMethod = currentMethod
                conn.connectTimeout = 12000; conn.readTimeout = 30000
                conn.setRequestProperty("User-Agent", "Arix/1.0")
                params.optString("cookies", "").takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Cookie", it) }
                params.optString("headers", "").takeIf { it.isNotBlank() }?.let { h ->
                    try { val ho = JSONObject(h); ho.keys().forEach { k -> conn.setRequestProperty(k, ho.optString(k)) } } catch (_: Exception) {}
                }
                if (uploadFile != null && sendBody) {
                    // multipart/form-data 上传工作目录里的文件（沙盒内）
                    val boundary = "----Arix${System.currentTimeMillis()}"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    conn.outputStream.use { os ->
                        val w = os.bufferedWriter()
                        params.optString("form", "").takeIf { it.isNotBlank() }?.let { fo ->
                            try { val jo = JSONObject(fo); jo.keys().forEach { k -> w.write("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n${jo.optString(k)}\r\n") } } catch (_: Exception) {}
                        }
                        w.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${uploadFile.name}\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                        w.flush()
                        uploadFile.inputStream().use { it.copyTo(os) }
                        os.flush()
                        w.write("\r\n--$boundary--\r\n"); w.flush()
                    }
                } else if (sendBody) {
                    val body = params.optString("body", "")
                    if (currentMethod in listOf("POST", "PUT") && body.isNotBlank()) {
                        conn.doOutput = true
                        conn.outputStream.bufferedWriter().use { it.write(body) }
                    }
                }
                val code = conn.responseCode
                // 3xx 重定向：手动跟随，对 Location 目标解析成绝对 URL 后重新过 blockedHost（含 DNS 解析校验）
                if (code in intArrayOf(301, 302, 303, 307, 308) && hop < 5) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc.isNullOrBlank()) return@withContext ToolResult("重定向缺少 Location 头", isError = true)
                    val next = try { URL(URL(currentUrl), loc).toString() } catch (_: Exception) { return@withContext ToolResult("重定向地址无效: $loc", isError = true) }
                    blockedHost(next)?.let { return@withContext ToolResult("拒绝重定向：$it（重定向目标指向内网/本机/云元数据）", isError = true) }
                    currentUrl = next
                    // 标准语义：303 一律转 GET；301/302 对 POST/PUT 也转 GET 丢 body；307/308 保持方法与 body
                    if (code == 303 || ((code == 301 || code == 302) && currentMethod in listOf("POST", "PUT"))) {
                        currentMethod = "GET"; sendBody = false
                    }
                    continue
                }
                // 落盘分支：响应体**不进上下文**，边收边写。一张图/一个 apk 几十 MB，
                // 读成字符串再截断既撑爆内存又什么也没得到。
                if (savePath.isNotBlank() && code in 200..299) {
                    val r = try { saveBody(conn, savePath, params.optString("save_dir", "downloads"), currentUrl) }
                            finally { conn.disconnect() }
                    return@withContext r
                }
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
                conn.disconnect()
                return@withContext ToolResult("HTTP $code\n${text.take(5000)}", isError = code !in 200..299)
            }
            return@withContext ToolResult("重定向次数过多（>5 跳），已中止", isError = true)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c   // 协程取消必须重抛，别被下面的 catch(Exception) 吞掉
        } catch (e: Exception) { ToolResult("请求失败: ${e.message}", isError = true) }
    }

    // ---- 下载（save_to）----

    /** 单次下载上限。手表的存储和内存都小，没有上限就是一条命令把机器写满。 */
    private val MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024

    /**
     * 把响应体写成文件。两个落点：
     *  - downloads（默认）：系统「下载」目录，用户能自己打开——「帮我把这个下载下来」要的就是这个。
     *  - workspace：AI 私有沙盒，给「下完还要接着处理」用（装 APK、转格式、解压）。仍走 [AiWorkspace.resolve] 防越界。
     */
    private suspend fun saveBody(conn: HttpURLConnection, savePath: String, saveDir: String, srcUrl: String): ToolResult {
        val mime = conn.contentType?.substringBefore(';')?.trim().orEmpty()
        val declared = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
        if (declared > MAX_DOWNLOAD_BYTES)
            return ToolResult("文件太大（${declared / 1024 / 1024}MB，上限 ${MAX_DOWNLOAD_BYTES / 1024 / 1024}MB），没有下载。", isError = true)
        val name = resolveName(savePath, conn, srcUrl)
        val toWorkspace = saveDir.equals("workspace", true) || saveDir.equals("sandbox", true)
        val body = conn.inputStream ?: return ToolResult("响应没有内容可保存", isError = true)
        return try {
            if (toWorkspace) {
                // savePath 可能带子目录（dl/a/b.bin），沙盒解析用原串而不是清洗过的文件名；
                // 只给了目录（结尾是 /）就把猜出来的文件名接上去，否则会往一个目录里写文件而失败
                val relative = when {
                    savePath.isBlank() || savePath == "auto" -> name
                    savePath.endsWith("/") -> savePath + name
                    else -> savePath
                }
                val f = AiWorkspace.resolve(context, relative) ?: return AiWorkspace.deny()
                f.parentFile?.mkdirs()
                val n = try { body.use { input -> f.outputStream().use { out -> pump(input, out) } } }
                        catch (e: Throwable) { runCatching { f.delete() }; throw e }
                ToolResult("已下载到工作目录：$relative（${human(n)}${if (mime.isNotBlank()) "，$mime" else ""}）")
            } else {
                val where = DownloadsSink.save(context, name, mime) { out -> body.use { pump(it, out) } }
                    ?: return ToolResult("保存失败：写不进下载目录", isError = true)
                ToolResult("已下载：$where${if (mime.isNotBlank()) "（$mime）" else ""}")
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (e: Exception) {
            ToolResult("下载失败：${e.message}", isError = true)
        }
    }

    /** save_to 给了名字就用它；给 "auto"/空就从 Content-Disposition 或 URL 末段猜一个。 */
    private fun resolveName(savePath: String, conn: HttpURLConnection, srcUrl: String): String {
        if (savePath.isNotBlank() && savePath != "auto" && !savePath.endsWith("/")) return DownloadsSink.sanitize(savePath)
        val cd = conn.getHeaderField("Content-Disposition").orEmpty()
        Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd)?.groupValues?.get(1)
            ?.let { raw -> return DownloadsSink.sanitize(runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)) }
        val fromUrl = runCatching { URL(srcUrl).path.substringAfterLast('/') }.getOrDefault("")
        val ext = when {
            fromUrl.contains('.') -> ""
            else -> when (conn.contentType?.substringBefore(';')?.trim()) {
                "image/jpeg" -> ".jpg"; "image/png" -> ".png"; "image/gif" -> ".gif"; "image/webp" -> ".webp"
                "video/mp4" -> ".mp4"; "application/pdf" -> ".pdf"; "application/zip" -> ".zip"
                "application/vnd.android.package-archive" -> ".apk"
                "text/plain" -> ".txt"; "application/json" -> ".json"
                else -> ".bin"
            }
        }
        return DownloadsSink.sanitize(fromUrl + ext, "download_${System.currentTimeMillis()}.bin")
    }

    /** 边收边写，逐块查取消（STOP 要能停掉一个正在下的大文件），超上限直接中止。 */
    private suspend fun pump(input: InputStream, out: OutputStream): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
            if (total > MAX_DOWNLOAD_BYTES) throw java.io.IOException("超过下载上限 ${MAX_DOWNLOAD_BYTES / 1024 / 1024}MB，已中止")
        }
        out.flush()
        return total
    }

    private fun human(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1fMB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "${bytes / 1024}KB"
        else -> "${bytes}B"
    }

    /**
     * 返回被拒原因；null=放行。**把主机名解析成 IP 再挨个核对**，天然覆盖十进制(2130706433)/八进制(0177.0.0.1)/
     * 十六进制(0x7f.0.0.1)/尾点(127.0.0.1.)/IPv6(::1、::ffff:127.0.0.1) 各种写法——都由 resolver 归一，
     * 也堵住 DNS rebinding（域名解析到内网即拒）。DNS 是阻塞调用，本函数只应在 IO 线程调用（execute 已在 Dispatchers.IO）。
     * 注：HttpURLConnection 连接时会再次解析 DNS，存在极小的 TOCTOU 窗口（校验后被改指向内网），彻底根治需固定 IP 连接，
     * 此处以「解析即校验 + 逐跳复检」把绝大多数真实攻击面堵死。
     */
    private fun blockedHost(urlStr: String): String? {
        val url = try { URL(urlStr) } catch (_: Exception) { return "URL 格式无效" }
        val scheme = url.protocol?.lowercase()
        if (scheme != "http" && scheme != "https") return "不支持的协议 $scheme"
        val host = url.host?.trim('[', ']')?.lowercase()?.trimEnd('.') ?: return "缺少主机"
        if (host.isBlank()) return "缺少主机"
        // 先按主机名兜一层（即便 DNS 挂了也能挡常见内网名）
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
            host == "metadata" || host.endsWith(".internal")) return "内网主机名 $host"
        // 关键：解析成 InetAddress，对**每个**返回地址判环回/私网/链路本地/组播/云元数据。
        val addrs = try { InetAddress.getAllByName(host) } catch (_: Exception) { return "主机无法解析 $host" }
        if (addrs.isEmpty()) return "主机无法解析 $host"
        for (addr in addrs) {
            if (addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isLinkLocalAddress ||
                addr.isSiteLocalAddress || addr.isMulticastAddress) return "私有/保留地址 ${addr.hostAddress}"
            val ip = addr.address
            // IPv4-mapped IPv6 (::ffff:a.b.c.d)：上面的 isSiteLocal 等对映射地址判不出，手动拆内嵌 IPv4 再判
            if (ip.size == 16 && isV4Mapped(ip)) {
                val a = ip[12].toInt() and 0xff; val b = ip[13].toInt() and 0xff
                if (a == 127 || a == 10 || a == 0 || a >= 224 ||
                    (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254))
                    return "私有/保留地址(IPv4 映射) $a.$b.${ip[14].toInt() and 0xff}.${ip[15].toInt() and 0xff}"
            }
            // IPv6 唯一本地地址 ULA (fc00::/7)：isSiteLocalAddress 只认已废弃的 fec0::/10，不认 ULA，手动兜
            if (ip.size == 16 && (ip[0].toInt() and 0xfe) == 0xfc) return "IPv6 唯一本地地址(ULA)"
            if (ip.size == 4) {
                val a = ip[0].toInt() and 0xff; val b = ip[1].toInt() and 0xff
                val c = ip[2].toInt() and 0xff; val d = ip[3].toInt() and 0xff
                // 云元数据 169.254.169.254 已被 isLinkLocalAddress 覆盖，这里显式再兜一层日志更清楚
                if (a == 169 && b == 254) return "链路本地/云元数据地址 $a.$b.$c.$d"
                // 100.64/10 运营商级 NAT（CGNAT），也归内网范畴
                if (a == 100 && b in 64..127) return "CGNAT 保留地址 $a.$b.$c.$d"
            }
        }
        return null
    }

    /** 判 IPv6 是否为 IPv4-mapped (::ffff:x.x.x.x)：前 10 字节全 0，第 11、12 字节为 0xff。 */
    private fun isV4Mapped(ip: ByteArray): Boolean {
        if (ip.size != 16) return false
        for (i in 0..9) if (ip[i].toInt() != 0) return false
        return (ip[10].toInt() and 0xff) == 0xff && (ip[11].toInt() and 0xff) == 0xff
    }
}

// 打包/解包 zip —— 一个工具两用(action=zip/unzip)，全程限沙盒内；unzip 逐条经沙盒解析防 zip-slip。
class FileArchiveTool(private val context: Context) : Tool {
    override val name = "file_archive"
    override val description = "打包/解包 zip。action=zip：把 path(文件或目录)压成 dest.zip；action=unzip：把 path(.zip)解到 dest 目录。都用相对工作目录路径。"
    override val permissionLevel = AndroidPermissionLevel.ACCESSIBILITY
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("zip", "unzip"))); put("description", "zip=压缩 / unzip=解压") })
            put("path", JSONObject().apply { put("type", "string"); put("description", "源：zip 时=要压的文件/目录；unzip 时=.zip 文件") })
            put("dest", JSONObject().apply { put("type", "string"); put("description", "目标：zip 时=输出 .zip 路径；unzip 时=解压到的目录") })
        })
        put("required", JSONArray(listOf("action", "path", "dest")))
    }
    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val destRel = params.optString("dest", "")
        val src = AiWorkspace.resolve(context, params.optString("path", "")) ?: return@withContext AiWorkspace.deny()
        val dst = AiWorkspace.resolve(context, destRel) ?: return@withContext AiWorkspace.deny()
        try {
            when (params.optString("action")) {
                "zip" -> {
                    if (!src.exists()) return@withContext ToolResult("源不存在: ${src.name}", isError = true)
                    dst.parentFile?.mkdirs()
                    var n = 0
                    val base = if (src.isDirectory) src else src.parentFile!!
                    java.util.zip.ZipOutputStream(dst.outputStream().buffered()).use { zos ->
                        src.walkTopDown().filter { it.isFile }.forEach { f ->
                            zos.putNextEntry(java.util.zip.ZipEntry(f.relativeTo(base).path.replace('\\', '/')))
                            f.inputStream().use { it.copyTo(zos) }; zos.closeEntry(); n++
                        }
                    }
                    ToolResult("已压缩 $n 个文件 → ${dst.name}")
                }
                "unzip" -> {
                    if (!src.exists()) return@withContext ToolResult("zip 不存在: ${src.name}", isError = true)
                    dst.mkdirs()
                    var n = 0
                    java.util.zip.ZipInputStream(src.inputStream().buffered()).use { zis ->
                        var e = zis.nextEntry
                        while (e != null) {
                            val out = AiWorkspace.resolve(context, File(destRel, e.name).path)   // 逐条经沙盒解析，越界(zip-slip)的条目直接跳过
                            if (out != null) { if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { zis.copyTo(it) }; n++ } }
                            e = zis.nextEntry
                        }
                    }
                    ToolResult("已解压 $n 个文件 → ${dst.name}/")
                }
                else -> ToolResult("action 必须是 zip 或 unzip", isError = true)
            }
        } catch (e: Exception) { ToolResult("归档失败: ${e.message}", isError = true) }
    }
}

// 文生图 —— 三家后端（OpenAI 兼容 / 通义万相 / 智谱 CogView，见 [ImageGenProviders]），
// 都读同一份「文生图」设置，返回可直接在聊天渲染的图片。
class GenerateImageTool(private val context: Context) : Tool {
    override val name = "generate_image"
    override val description = "文生图：按文字描述生成图片。支持 OpenAI 兼容接口、通义万相(阿里云百炼)、智谱 CogView 三家，" +
        "默认按『文生图』设置里填的地址自动认是哪家，也可以用 provider 指定。需先在设置里填 key。返回的图片能直接在聊天里显示。"
    // 模型侧英文（见 Tool.llmDescription）
    override val llmDescription = "Generate an image from a text description. Backends: OpenAI-compatible, Qwen/Wanx (Aliyun DashScope) and Zhipu CogView; " +
        "picked automatically from the user's configured endpoint unless you set provider. Needs an API key configured in settings. The result renders inline in chat."
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("prompt", JSONObject().apply { put("type", "string"); put("description", "What to draw. Be specific: subject, style, composition, lighting") })
            put("provider", JSONObject().apply {
                put("type", "string"); put("enum", JSONArray(listOf("auto", "openai", "qwen", "zhipu")))
                put("description", "Which backend. Default auto = follow the user's settings. Only override it when the user asks for a specific one")
            })
        })
        put("required", JSONArray(listOf("prompt")))
    }
    override suspend fun execute(params: JSONObject): ToolResult {
        val prompt = params.optString("prompt", "").trim()
        if (prompt.isBlank()) return ToolResult("请提供 prompt（图片描述）", isError = true)
        val provider = ImageGenProviders.resolve(context, params.optString("provider", ""))
        val (uri, err) = ImageGenProviders.generate(context, prompt, provider)
        if (uri != null) return ToolResult("已生成图片（${ImageGenProviders.LABELS[provider] ?: provider}）：\n![]($uri)")
        // 失败时把「另外两家怎么配」一并说出来：多数失败其实是这家没 key，而用户可能有另一家的
        val others = ImageGenProviders.LABELS.filterKeys { it != provider }.values.joinToString("、")
        return ToolResult("生成失败：${err ?: "未知错误"}\n（当前用的是 ${ImageGenProviders.LABELS[provider] ?: provider}；" +
            "本机还支持 $others，在「设置 → 文生图」换个地址和 key 就能切）", isError = true)
    }
}
