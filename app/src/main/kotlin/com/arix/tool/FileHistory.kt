package com.arix.tool

import android.content.Context
import com.arix.app.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

// ============================================================
// FileHistory —— AI 文件改动的「可回放 / 可撤销」账本
//
// 为什么要有它：AI 改文件对用户是黑箱——改了哪些、改成什么样、能不能退回来，
// 用户看不见也管不了。这里在**每次写操作发生之前**留一份改动前快照，
// 事后既能看 diff（改了什么），也能一键回到改动前（后悔药）。
//
// 为什么存在工作区之外（filesDir/ai_file_history，而非 ai_workspace 里）：
// 快照要是放进沙盒，AI 自己就能 file_list 看见它、file_edit 改它、file_delete 删它——
// 「后悔药」被吃药的人自己保管，等于没有；而且会污染 AI 的目录列表/搜索结果。
// AiWorkspace.resolve 把 AI 锁死在 ai_workspace 内，放在它外面 = AI 天然够不着。
// ============================================================

/** 一条改动记录。快照本体存在 blobs/ 下，这里只留元信息（索引要能整体读进内存，必须小）。 */
data class FileChange(
    val id: String,
    val time: Long,
    /** 操作类型，见 FileHistory.OP_*。 */
    val op: String,
    /** 目标相对路径（move 时为源路径）。 */
    val path: String,
    /** move 的目标相对路径，其余为 null。 */
    val toPath: String?,
    /** 改动前快照的 blob 文件名；null = 没存（要么原本就不存在，要么见 snapshotSkipped）。 */
    val beforeBlob: String?,
    /** 改动前目标是否已存在。false = 这是一次「新建」，回滚 = 删掉它。 */
    val beforeExisted: Boolean,
    val beforeSize: Long,
    /** 改动后内容的 SHA-256；null = 改动后不存在（删除）或是目录。用于回滚前的冲突检测。 */
    val afterHash: String?,
    val afterSize: Long,
    /** 非 null = 未存快照的原因（过大 / 目录 / 读失败）。诚实标注：这类记录不可回滚内容。 */
    val snapshotSkipped: String?,
    val binary: Boolean,
    /** 供 UI 展开查看的 diff 文本（已截断）。 */
    val diffPreview: String,
    val conversationId: Long?,
) {
    /** 内容型回滚是否可行。move 另有分支（移回去不需要快照），故单独判断。 */
    val revertable: Boolean
        get() = op == FileHistory.OP_MOVE || snapshotSkipped == null

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("time", time); put("op", op); put("path", path)
        put("toPath", toPath ?: JSONObject.NULL)
        put("beforeBlob", beforeBlob ?: JSONObject.NULL)
        put("beforeExisted", beforeExisted); put("beforeSize", beforeSize)
        put("afterHash", afterHash ?: JSONObject.NULL); put("afterSize", afterSize)
        put("snapshotSkipped", snapshotSkipped ?: JSONObject.NULL)
        put("binary", binary); put("diffPreview", diffPreview)
        put("conversationId", conversationId ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): FileChange? {
            val id = o.optString("id", "").ifBlank { return null }
            return FileChange(
                id = id,
                time = o.optLong("time"),
                op = o.optString("op", FileHistory.OP_WRITE),
                path = o.optString("path", ""),
                toPath = o.optString("toPath", "").ifBlank { null },
                beforeBlob = o.optString("beforeBlob", "").ifBlank { null },
                beforeExisted = o.optBoolean("beforeExisted", false),
                beforeSize = o.optLong("beforeSize"),
                afterHash = o.optString("afterHash", "").ifBlank { null },
                afterSize = o.optLong("afterSize"),
                snapshotSkipped = o.optString("snapshotSkipped", "").ifBlank { null },
                binary = o.optBoolean("binary", false),
                diffPreview = o.optString("diffPreview", ""),
                conversationId = if (o.isNull("conversationId")) null else o.optLong("conversationId"),
            )
        }
    }
}

/** 回滚结果。needsConfirm = 检测到文件已被改过，要用户确认才继续（不闷头覆盖）。 */
data class RevertResult(val ok: Boolean, val message: String, val needsConfirm: Boolean = false)

/**
 * 改动前抓取的现场。两段式（capture 改动前 / commit 改动后）是必须的：
 * 快照只能在文件被覆盖之前读，而 diff 和冲突检测用的哈希只能在改完之后算。
 */
class PendingChange internal constructor(
    internal val relPath: String,
    internal val existed: Boolean,
    internal val bytes: ByteArray?,
    internal val skipReason: String?,
    internal val size: Long,
)

object FileHistory {
    const val OP_WRITE = "file_write"
    const val OP_EDIT = "file_edit"
    const val OP_DELETE = "file_delete"
    const val OP_MOVE = "file_move"
    const val OP_REVERT = "revert"

    /** 单文件快照上限 1MB：手表存储紧张，超过的只记元信息并标明不可回滚，不静默假装能退。 */
    const val SNAPSHOT_CAP = 1L * 1024 * 1024
    /** 快照总量上限 20MB，超了按时间淘汰最老的。 */
    private const val TOTAL_CAP = 20L * 1024 * 1024
    /** 条数上限，同样按时间淘汰。 */
    private const val MAX_RECORDS = 100
    /** diff 预览截断长度：索引要整体读进内存，不能让一条巨型 diff 把它撑大。 */
    private const val DIFF_CAP = 2000

    private const val DIR = "ai_file_history"
    private const val INDEX = "index.json"

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    private fun blobDir(context: Context): File =
        File(dir(context), "blobs").apply { if (!exists()) mkdirs() }

    // ---------------- 索引读写（线程安全）----------------
    // 读也在锁内：多个工具可能并发写文件，索引是「读-改-写」序列，读到旧快照会丢记录。

    @Synchronized
    private fun readIndexLocked(context: Context): MutableList<FileChange> {
        val f = File(dir(context), INDEX)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONObject(f.readText()).optJSONArray("records") ?: JSONArray()
            val out = mutableListOf<FileChange>()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { FileChange.fromJson(it)?.let(out::add) }
            out
        } catch (e: Exception) {
            mutableListOf()   // 索引损坏不该让文件工具整体失效，退化成「没有历史」
        }
    }

    @Synchronized
    private fun writeIndexLocked(context: Context, list: List<FileChange>) {
        val root = JSONObject().apply {
            put("records", JSONArray().apply { list.forEach { put(it.toJson()) } })
        }
        try { File(dir(context), INDEX).writeText(root.toString()) } catch (_: Exception) {}
    }

    /** 追加一条并按上限淘汰。整个「读-改-写-删 blob」必须在同一把锁内，否则并发下会淘汰掉别人刚写的快照。 */
    @Synchronized
    private fun appendLocked(context: Context, rec: FileChange) {
        val list = readIndexLocked(context)
        list.add(rec)
        val blobs = blobDir(context)
        var total = list.sumOf { r -> r.beforeBlob?.let { File(blobs, it).length() } ?: 0L }
        while (list.size > MAX_RECORDS || (total > TOTAL_CAP && list.size > 1)) {
            val old = list.removeAt(0)
            old.beforeBlob?.let { name ->
                val bf = File(blobs, name)
                total -= bf.length()
                try { bf.delete() } catch (_: Exception) {}
            }
        }
        writeIndexLocked(context, list)
    }

    // ---------------- 对外读取 ----------------

    /** 全部记录，时间倒序（UI 直接用）。 */
    suspend fun all(context: Context): List<FileChange> = withContext(Dispatchers.IO) {
        readIndexLocked(context).asReversed().toList()
    }

    /** 快照占用的总字节，给 UI 显示「用了多少」。 */
    suspend fun usedBytes(context: Context): Long = withContext(Dispatchers.IO) {
        blobDir(context).listFiles()?.sumOf { it.length() } ?: 0L
    }

    /** 清空全部历史（用户主动清理存储用）。 */
    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        clearLocked(context)
    }

    @Synchronized
    private fun clearLocked(context: Context) {
        try { blobDir(context).listFiles()?.forEach { it.delete() } } catch (_: Exception) {}
        writeIndexLocked(context, emptyList())
    }

    // ---------------- 埋点：改动前 capture / 改动后 commit ----------------

    /**
     * 改动前抓现场。绝不抛异常：记历史失败也不该让 AI 的文件操作跟着失败。
     * [file] 必须是已经过 AiWorkspace.resolve 的沙盒内文件。
     */
    suspend fun capture(context: Context, file: File): PendingChange = withContext(Dispatchers.IO) {
        val rel = relOf(context, file)
        try {
            when {
                !file.exists() -> PendingChange(rel, existed = false, bytes = null, skipReason = null, size = 0)
                // skipReason 会被原样写进索引 JSON 并直接显示在改动历史页，所以必须在这里就翻译好。
                // 代价是语言在记录生成那一刻被定死（事后切语言，老记录仍是旧语言）——
                // 要根治得改成存错误码、显示时再翻，那要动 UI 层，超出本次范围；比永远只有中文已是改善。
                file.isDirectory -> PendingChange(rel, true, null, tr("目录未存快照，内容不可回滚"), 0)
                file.length() > SNAPSHOT_CAP ->
                    PendingChange(rel, true, null, tr("文件过大（%s），未存快照，不可回滚").format(fmt(file.length())), file.length())
                else -> PendingChange(rel, true, readBounded(file), null, file.length())
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            PendingChange(rel, file.exists(), null, tr("快照失败（%s），不可回滚").format(e.message ?: ""), 0)
        }
    }

    /**
     * 改动后落账。[after] 是改完之后该看的位置（move 时=目标文件；delete 时传原路径即可，它已不存在）。
     * 同样吞掉自身异常，只重抛取消。
     */
    suspend fun commit(
        context: Context,
        pending: PendingChange,
        op: String,
        after: File,
        toPath: String? = null,
    ) = withContext(Dispatchers.IO) {
        try {
            val id = "${System.currentTimeMillis()}_${(0..0xFFFF).random().toString(16)}"
            var blobName: String? = null
            if (pending.bytes != null) {
                blobName = "$id.bin"
                try { File(blobDir(context), blobName).writeBytes(pending.bytes) }
                catch (e: Exception) { blobName = null }
            }
            val binary = pending.bytes?.let { isBinary(it) } ?: false
            val afterExists = after.exists() && after.isFile
            val rec = FileChange(
                id = id,
                time = System.currentTimeMillis(),
                op = op,
                path = pending.relPath,
                toPath = toPath,
                beforeBlob = blobName,
                beforeExisted = pending.existed,
                beforeSize = pending.size,
                afterHash = if (afterExists) hashOf(after) else null,
                afterSize = if (afterExists) after.length() else 0,
                snapshotSkipped = pending.skipReason ?: if (pending.existed && blobName == null) tr("快照写入失败，不可回滚") else null,
                binary = binary,
                diffPreview = buildPreview(pending, op, after, toPath, binary),
                conversationId = ActiveChatContext.conversationId,
            )
            appendLocked(context, rec)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
        }
    }

    // ---------------- 回滚 ----------------

    /**
     * 把一条记录回滚到改动前。
     * [confirmed]=false 时若发现文件当前内容已不是「改动后」的样子（用户或别的操作动过了），
     * 只警告不动手——闷头覆盖会吃掉用户自己的修改。
     * 回滚本身也记一条新历史，所以「撤销回滚」同样走这里。
     */
    suspend fun revert(context: Context, id: String, confirmed: Boolean = false): RevertResult =
        withContext(Dispatchers.IO) {
            try {
                val rec = readIndexLocked(context).firstOrNull { it.id == id }
                    ?: return@withContext RevertResult(false, tr("记录已不存在（可能已被淘汰）"))
                if (rec.op == OP_MOVE) revertMove(context, rec, confirmed)
                else revertContent(context, rec, confirmed)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                RevertResult(false, tr("回滚失败：%s").format(e.message ?: ""))
            }
        }

    /** move 的回滚 = 移回去，不需要快照（文件本体还在，只是换了位置）。 */
    private suspend fun revertMove(context: Context, rec: FileChange, confirmed: Boolean): RevertResult {
        val toRel = rec.toPath ?: return RevertResult(false, tr("记录缺少目标路径，无法回滚"))
        // 一律重走沙盒解析：历史里的路径也可能被篡改，回滚不能成为越狱通道
        val dst = AiWorkspace.resolve(context, toRel) ?: return RevertResult(false, tr("路径越界，拒绝回滚"))
        val src = AiWorkspace.resolve(context, rec.path) ?: return RevertResult(false, tr("路径越界，拒绝回滚"))
        if (!dst.exists()) return RevertResult(false, tr("「%s」已不存在，无法移回").format(toRel))
        if (!confirmed) {
            val h = if (dst.isFile) hashOf(dst) else null
            if (rec.afterHash != null && h != rec.afterHash)
                return RevertResult(false, tr("「%s」在这次移动之后又被改过。回滚会把它移回原位，确定继续？").format(toRel), needsConfirm = true)
            if (src.exists())
                return RevertResult(false, tr("原位置「%s」现在已有文件，移回会覆盖它。确定继续？").format(rec.path), needsConfirm = true)
        }
        val pending = capture(context, src)
        src.parentFile?.mkdirs()
        if (!dst.renameTo(src)) return RevertResult(false, tr("移回失败"))
        // 记成一条 move（源=当前位置、目标=原位置），于是「撤销这次回滚」天然还是一次移动
        commit(context, pending, OP_MOVE, src, toPath = rec.path)
        return RevertResult(true, tr("已移回 %s").format(rec.path))
    }

    /** write / edit / delete / revert 的回滚：恢复快照，或删掉「本来不存在」的新建文件。 */
    private suspend fun revertContent(context: Context, rec: FileChange, confirmed: Boolean): RevertResult {
        if (rec.snapshotSkipped != null) return RevertResult(false, tr("该记录未存快照（%s），无法回滚").format(rec.snapshotSkipped))
        val f = AiWorkspace.resolve(context, rec.path) ?: return RevertResult(false, tr("路径越界，拒绝回滚"))

        if (!confirmed) {
            val cur = if (f.exists() && f.isFile) hashOf(f) else null
            if (cur != rec.afterHash) {
                val what = when {
                    rec.afterHash == null && cur != null -> tr("「%s」在这次删除之后又被重新创建了").format(rec.path)
                    cur == null -> tr("「%s」现在已不存在").format(rec.path)
                    else -> tr("「%s」在这次改动之后又被改过").format(rec.path)
                }
                // 整句带 %s 而非「拼前半句 + 固定后缀」：后缀以句号开头，做成独立词条会被
                // 各语言的标点习惯和译者的 trim 弄坏，也没法让语序不同的语言把它挪到前面。
                return RevertResult(false, tr("%s。回滚会覆盖当前内容，确定继续？").format(what), needsConfirm = true)
            }
        }

        val pending = capture(context, f)
        if (rec.beforeExisted) {
            val blob = rec.beforeBlob?.let { File(blobDir(context), it) }
            if (blob == null || !blob.exists()) return RevertResult(false, tr("快照文件已丢失，无法回滚"))
            f.parentFile?.mkdirs()
            f.writeBytes(blob.readBytes())   // 快照上限 1MB，整体读安全
        } else {
            // 改动前本就不存在 = 那次是「新建」，回滚就是删掉
            if (f.exists() && !f.deleteRecursively()) return RevertResult(false, tr("删除失败"))
        }
        commit(context, pending, OP_REVERT, f)
        return RevertResult(true, if (rec.beforeExisted) tr("已恢复 %s").format(rec.path)
                                  else tr("已删除 %s（该文件原本不存在）").format(rec.path))
    }

    // ---------------- 工具函数 ----------------

    /** 相对工作区的路径，给 UI 和回滚用（绝对路径既难看又没法跨安装复用）。 */
    internal fun relOf(context: Context, file: File): String = try {
        val base = AiWorkspace.root(context).canonicalFile.path
        val p = file.canonicalFile.path
        if (p.startsWith(base + File.separator)) p.substring(base.length + 1) else file.name
    } catch (e: Exception) { file.name }

    /**
     * 有界读取：只读到 cap+1 字节就停。整篇 readBytes() 遇到几百 MB 的文件会 OOM，
     * 而 OutOfMemoryError 是 Error，上面的 catch(Exception) 根本拦不住，直接崩 app——只能从源头限量。
     * 超限返回 null（调用方据此标注「未存快照」）。
     */
    private fun readBounded(file: File, cap: Long = SNAPSHOT_CAP): ByteArray? {
        val buf = ByteArray(8192)
        val out = java.io.ByteArrayOutputStream()
        file.inputStream().buffered().use { ins ->
            while (out.size() <= cap) {
                val n = ins.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        return if (out.size() > cap) null else out.toByteArray()
    }

    /** 流式算 SHA-256：定长缓冲，多大的文件都不会把内存吃穿。 */
    private fun hashOf(file: File): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(8192)
        file.inputStream().buffered().use { ins ->
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { null }

    /** 含 NUL 字节基本可判定为二进制：文本 diff 对它没意义，只记大小变化。 */
    private fun isBinary(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, 8000)
        for (i in 0 until n) if (bytes[i] == 0.toByte()) return true
        return false
    }

    private fun buildPreview(pending: PendingChange, op: String, after: File, toPath: String?, binary: Boolean): String {
        if (op == OP_MOVE) return "${pending.relPath}  →  ${toPath ?: "?"}"
        if (pending.skipReason != null) return "（${pending.skipReason}）"
        // 同 skipReason：diffPreview 也是存进索引后直接上屏的，只能在生成时翻译
        if (binary) return tr("（二进制文件，不显示 diff）")
        val before = pending.bytes?.toString(Charsets.UTF_8) ?: ""
        val afterText = when {
            !after.exists() || !after.isFile -> ""      // 删除：after 为空，diff 全是删除行
            after.length() > SNAPSHOT_CAP -> return tr("（改动后文件过大，未生成 diff）")
            else -> readBounded(after)?.toString(Charsets.UTF_8) ?: return tr("（改动后文件过大，未生成 diff）")
        }
        if (!pending.existed) {
            // 新建：没有「前」，diff 全是加号行没信息量，直接给内容头部更实用
            val head = afterText.lineSequence().take(20).joinToString("\n")
            return (tr("（新建文件）") + "\n" + head).take(DIFF_CAP)
        }
        return lineDiff(before, afterText).take(DIFF_CAP)
    }

    private fun fmt(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "${b / 1024} KB"
        else -> "%.1f MB".format(b.toDouble() / (1024 * 1024))
    }
}
