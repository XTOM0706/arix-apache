package com.arix.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import com.arix.data.db.AppDatabase
import com.arix.tool.ImportExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.spec.SecretKeySpec

// ============================================================
// GitHub 私有仓库云备份 —— 把 FullBackup 的整包 zip 推到用户自己的【私有】仓库（PAT 授权），
// 换机/重装再从仓库拉回覆盖。高隐私：仓库强制 private、token 只存本机、走用户自己的账号。
// 用 GitHub REST Contents API + HttpURLConnection（app 模块无 okhttp，与 FileTools 一致，无新依赖）。
// ============================================================
object GitHubBackup {
    private const val PREFS = "xtom_github_backup"
    private const val API = "https://api.github.com"
    /**
     * 以前的固定文件名（每次覆盖，云端永远只有一份）。**现在只在恢复时作为兜底候选**：
     * 新备份一律写 `arix-backup-<UTC时间戳>[.enc].zip`，见 [BackupPolicy]。
     * 一次坏数据同步上去就把上一份好的覆盖掉——那正是要改掉的东西。
     */
    private const val PATH = BackupPolicy.FIXED_NAME
    /** 更名前旧包传的文件名。恢复时找不到新名就试它一次，别让老用户仓库里的备份看起来"没了"。 */
    private const val LEGACY_PATH = BackupPolicy.LEGACY_FIXED_NAME

    data class Settings(val token: String, val repo: String, val branch: String)

    fun settings(c: Context): Settings {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // 没单独填 token 就用全局 GitHub 登录的 token（一处登录、备份直接可用）
        val tok = (p.getString("token", "") ?: "").ifBlank { GitHubAccount.token(c) }
        return Settings(
            tok,
            p.getString("repo", "arix-backup") ?: "arix-backup",
            p.getString("branch", "main") ?: "main",
        )
    }

    fun save(c: Context, token: String, repo: String, branch: String) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("token", token.trim())
            .putString("repo", repo.trim().ifBlank { "arix-backup" })
            .putString("branch", branch.trim().ifBlank { "main" })
            .apply()
    }

    private fun conn(url: String, method: String, token: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Arix")
            connectTimeout = 20000; readTimeout = 60000
        }

    private fun HttpURLConnection.writeJson(json: String) {
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
    }

    private fun HttpURLConnection.text(): String =
        try { (if (responseCode in 200..299) inputStream else errorStream)?.bufferedReader()?.use { it.readText() } ?: "" }
        catch (_: Exception) { "" }

    /** 校验 token 并取用户名（owner）。 */
    private fun login(token: String): String {
        val c = conn("$API/user", "GET", token)
        val t = c.text()
        if (c.responseCode !in 200..299) throw Exception("Token 无效或权限不足（HTTP ${c.responseCode}）")
        return JSONObject(t).getString("login")
    }

    /** 确保仓库存在且【私有】；返回它的默认分支名（兼容 master/main）。已存在但公开则拒绝，保护密钥。 */
    private fun resolveRepo(token: String, owner: String, repo: String): String {
        val g = conn("$API/repos/$owner/$repo", "GET", token)
        val code = g.responseCode; val body = g.text()
        when (code) {
            200 -> {
                val o = JSONObject(body)
                if (!o.optBoolean("private", false))
                    throw Exception("仓库 $owner/$repo 是【公开】的！备份含密钥，为安全请把它改成私有，或换一个不存在的仓库名让 App 自动建私有仓库。")
                return o.optString("default_branch", "main").ifBlank { "main" }
            }
            404 -> {
                val p = conn("$API/user/repos", "POST", token)
                p.writeJson(JSONObject().put("name", repo).put("private", true).put("auto_init", true).toString())
                val pc = p.responseCode; val pb = p.text()
                if (pc !in 200..299) throw Exception("创建私有仓库失败（HTTP $pc）：${pb.take(160)}")
                return JSONObject(pb).optString("default_branch", "main").ifBlank { "main" }
            }
            else -> throw Exception("检查仓库失败（HTTP $code）。确认 Token 有 repo 权限。")
        }
    }

    /** 只读解析：仓库存在返回默认分支，不存在返回 null（绝不创建）——给对比/恢复用，别因为查看就建仓库。 */
    private fun resolveRepoReadOnly(token: String, owner: String, repo: String): String? {
        val g = conn("$API/repos/$owner/$repo", "GET", token)
        val code = g.responseCode; val body = g.text()
        if (code != 200) return null
        val o = JSONObject(body)
        if (!o.optBoolean("private", false)) throw Exception("仓库 $owner/$repo 是公开的，为安全已中止。")
        return o.optString("default_branch", "main").ifBlank { "main" }
    }

    /** 用户没自定义分支就跟随仓库默认分支（兼容 master）。 */
    private fun effectiveBranch(userBranch: String, repoDefault: String) =
        if (userBranch.isNotBlank() && userBranch != "main") userBranch else repoDefault

    /** 下载回来的字节必须是【完整可解析的备份】才允许覆盖恢复——防网络劫持/截断把本地数据毁掉。 */
    private fun validBackupZip(b: ByteArray): Boolean = FullBackup.isRestorableBlob(b)

    /** 列出仓库根目录里属于我们的备份文件（含老的固定文件名那份）。失败一律返回空表，不打扰调用方。 */
    private fun listRepoVersions(s: Settings, owner: String, branch: String): List<CloudBackupVersion> = try {
        val c = conn("$API/repos/$owner/${s.repo}/contents/?ref=$branch", "GET", s.token)
        if (c.responseCode != 200) { c.text(); emptyList<CloudBackupVersion>() } else {
            val arr = JSONArray(c.text())
            val out = ArrayList<CloudBackupVersion>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("type") != "file") continue
                // GitHub 的 contents 列表不给时间，时间只能从文件名里的时间戳来（老的固定名那份就只能是 0）
                BackupPolicy.describe(o.optString("name"), o.optLong("size", -1L), 0L)?.let { out.add(it) }
            }
            out.sortedWith(BackupPolicy.NEWEST_FIRST)
        }
    } catch (ce: CancellationException) { throw ce } catch (_: Exception) { emptyList() }

    /** 删仓库里一个文件（Contents API 要求带 sha）。 */
    private fun deletePath(s: Settings, owner: String, branch: String, path: String): Boolean = try {
        val sha = shaOf(s.token, owner, s.repo, branch, path)
        if (sha == null) false else {
            val d = conn("$API/repos/$owner/${s.repo}/contents/$path", "DELETE", s.token)
            d.writeJson(JSONObject().put("message", "Arix backup prune").put("sha", sha).put("branch", branch).toString())
            val code = d.responseCode; d.text(); code in 200..299
        }
    } catch (ce: CancellationException) { throw ce } catch (_: Exception) { false }

    /**
     * 保留最近 N 份，多出来的删掉。只在**上传成功之后**调用——先删后传的话，网络断在中间就等于
     * 「旧的删了、新的没上去」。老的固定文件名那份不在清理范围内（见 [BackupPolicy.toPrune]）。
     */
    private fun pruneOld(context: Context, s: Settings, owner: String, branch: String): Int = try {
        var n = 0
        BackupPolicy.toPrune(listRepoVersions(s, owner, branch), BackupPolicy.keepVersions(context))
            .forEach { if (deletePath(s, owner, branch, it.id)) n++ }
        n
    } catch (ce: CancellationException) { throw ce } catch (_: Exception) { 0 }

    /** 列出云端历史版本（时间 / 大小 / 是否加密），给 UI 做版本选择。不存在或读不到则空表。 */
    suspend fun listVersions(context: Context): List<CloudBackupVersion> = withContext(Dispatchers.IO) {
        val s = settings(context); if (s.token.isBlank()) return@withContext emptyList()
        try {
            val owner = login(s.token)
            val def = resolveRepoReadOnly(s.token, owner, s.repo) ?: return@withContext emptyList()
            listRepoVersions(s, owner, effectiveBranch(s.branch, def))
        } catch (ce: CancellationException) { throw ce } catch (_: Exception) { emptyList() }
    }

    /** 删掉云端某一份历史版本（id 取自 [listVersions]）。给 UI 的「删除这份」用。 */
    suspend fun deleteVersion(context: Context, id: String): String = withContext(Dispatchers.IO) {
        val s = settings(context); if (s.token.isBlank()) return@withContext "请先填 GitHub Token"
        try {
            val owner = login(s.token)
            val def = resolveRepoReadOnly(s.token, owner, s.repo) ?: return@withContext "云端仓库还不存在"
            if (deletePath(s, owner, effectiveBranch(s.branch, def), id)) "已删除 $id" else "删除失败：$id"
        } catch (ce: CancellationException) { throw ce } catch (e: Exception) { "删除失败：${e.message}" }
    }

    /**
     * 备份：打全量 zip →（设了口令就加密）→ base64 → PUT 成一份**带时间戳的新文件**，再清理超出保留数的旧版本。
     * @param passphrase null=按设置走（设了备份口令就加密），""=强制明文。
     */
    suspend fun backup(context: Context, passphrase: String? = null): String = withContext(Dispatchers.IO) {
        val s = settings(context)
        if (s.token.isBlank()) return@withContext "请先填 GitHub Token"
        try {
            val owner = login(s.token)
            val branch = effectiveBranch(s.branch, resolveRepo(s.token, owner, s.repo))
            val pass = BackupCrypto.resolve(context, passphrase)
            val bytes = ByteArrayOutputStream().also { FullBackup.exportTo(context, it, pass) }.toByteArray()
            // Contents API 走 base64 塞进 JSON，太大既超限又易 OOM（手表堆小）——提前拦，指去本地备份
            if (bytes.size > 25 * 1024 * 1024) return@withContext "备份 ${bytes.size / 1024 / 1024}MB 太大，GitHub 方式不合适（易超限/内存溢出）。请改用本地「备份全部数据」。"
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val path = BackupPolicy.newName(pass.isNotEmpty())
            val put = conn("$API/repos/$owner/${s.repo}/contents/$path", "PUT", s.token)
            // 新名字理论上不会已存在（精确到秒 + 保留策略），万一同秒重传由下面的 409 分支提示重试
            put.writeJson(JSONObject().put("message", "Arix backup").put("content", b64).put("branch", branch).toString())
            val code = put.responseCode; val resp = put.text()   // text() 会读干净流，避免连接泄漏
            when {
                code in 200..299 -> {
                    setLastBackup(context)
                    val pruned = pruneOld(context, s, owner, branch)
                    "备份成功 · 已推送到私有仓库 $owner/${s.repo}@$branch（${bytes.size / 1024} KB" +
                        (if (pass.isNotEmpty()) " · 已加密" else "") + "）" +
                        (if (pruned > 0) "，清理 $pruned 份旧版本" else "")
                }
                code == 409 -> "备份冲突（另一台设备刚备份过），请重试一次"
                else -> "备份失败（HTTP $code）：${resp.take(160)}"
            }
        } catch (ce: CancellationException) { throw ce
        } catch (e: OutOfMemoryError) { com.arix.app.AppLog.e("Backup", "GitHub 备份内存不足", e); "备份失败：数据太大内存不足，请改用本地全量备份"
        } catch (e: Exception) { com.arix.app.AppLog.e("Backup", "GitHub 备份失败", e); "备份失败：${e.message}" }
    }

    /**
     * 恢复：raw 下载字节 → 校验完整性 → FullBackup 覆盖恢复（成功后 UI 触发重启）。
     * @param versionId 取自 [listVersions]；不传 = 用最新的一份（保持老行为）。
     * @param passphrase 加密包才用得上；不传 = 用本机保存的备份口令。口令不对会返回明确提示且不动本地数据。
     */
    suspend fun restore(context: Context, versionId: String? = null, passphrase: String? = null): String = withContext(Dispatchers.IO) {
        val s = settings(context)
        if (s.token.isBlank()) return@withContext "请先填 GitHub Token"
        try {
            val owner = login(s.token)
            val def = resolveRepoReadOnly(s.token, owner, s.repo) ?: return@withContext "云端仓库还不存在，先备份一次"
            val branch = effectiveBranch(s.branch, def)
            fun fetch(path: String) = conn("$API/repos/$owner/${s.repo}/contents/$path?ref=$branch", "GET", s.token)
                .apply { setRequestProperty("Accept", "application/vnd.github.raw") }   // 直接取原始字节，兼容大文件
            // 候选顺序：指定版本 > 最新的带时间戳版本 > 老的固定文件名 > 更名前的固定文件名。
            // 后两个是向后兼容：升级前用户仓库里就那一份，不试它等于告诉人家"备份没了"。
            val candidates = when {
                versionId != null -> listOf(versionId)
                else -> (listRepoVersions(s, owner, branch).map { it.id } + PATH + LEGACY_PATH).distinct()
            }
            var c: java.net.HttpURLConnection? = null
            var code = 404
            for (p in candidates) {
                val t = fetch(p); code = t.responseCode
                if (code == 404) { runCatching { t.text() }; continue }
                c = t; break
            }
            if (c == null) return@withContext "仓库里还没有备份文件（先备份一次）"
            if (code !in 200..299) { c.text(); return@withContext "拉取失败（HTTP $code）" }
            val bytes = c.inputStream.use { it.readBytes() }
            // 关键：删本地库之前先验证完整（明文验 zip，加密包验包头 + 解密时验 GCM 标签），损坏就中止、绝不动本地数据
            if (!validBackupZip(bytes)) return@withContext "下载的备份无效或不完整（可能网络被劫持/中断），已中止，未改动本地数据。"
            FullBackup.importFrom(context, ByteArrayInputStream(bytes), passphrase)
            setLastBackup(context)
            "恢复成功，正在重启应用…"
        } catch (ce: CancellationException) { throw ce
        } catch (e: BackupPassphraseException) { "恢复失败：${e.message}"   // 已是「口令不对」这类明确话术
        } catch (e: Exception) { "恢复失败：${e.message}" }
    }

    // ========================================================
    // 分块备份/恢复/对比 —— 每个分块存成仓库里一个 JSON 文件(block_X.json)，另存 manifest.json 记录 条数/大小/时间。
    // 恢复=合并进本地(保留双方，非破坏；记忆按标题去重合并)。整包 zip 那套(backup/restore)才是「云端整体覆盖」。
    //
    // 【加密与分块的取舍】选的是「**先分块、每块各自独立加密**」，不是「整包加密后再切块」。理由：
    //  1. 增量同步判「变没变」用的是**本地明文** JSON 的 SHA-256（blk_hash_*）与本次导出的明文比对，
    //     压根不碰云端字节 —— 所以每块独立加密对增量比对是零影响。反过来，如果先把整包加密再切块，
    //     GCM 是流式的，任何一个字节变化都会让它之后的**全部**块的密文变掉，等于每次都要全量重传，
    //     增量同步直接失效（这正是要求里"不能破坏分块同步"要防的事）。
    //  2. 每块自带盐/IV、自成一体，某一块坏了不影响其它块还能恢复。
    //  3. 一轮同步内多块共用一次 PBKDF2 派生（盐一轮一换、IV 每块一换），不会因为加密就慢 N 倍。
    // manifest.json 保持**明文**：里面只有条数/字节数/时间，没有任何用户内容；而「本地 vs 云端对比」
    // 这个页面必须在没输口令时也能用。manifest 里的 size 记的也是**明文长度**，否则对比页会永远显示不一致。
    // ========================================================
    val BLOCKS = listOf("configs" to "配置", "cards" to "角色卡", "worlds" to "世界书", "memories" to "记忆", "conversations" to "对话")
    private fun blockFile(b: String) = "block_$b.json"
    /** 加密分块的文件名。用不同后缀，是为了让"云端到底是明文还是密文"从文件名就能看出来、也便于删掉明文旧版。 */
    private fun blockFileEnc(b: String) = "block_$b.enc"

    data class BlockCompare(
        val block: String, val label: String,
        val localCount: Int, val localSize: Int,
        val cloudCount: Int, val cloudSize: Int, val cloudTs: Long, val cloudExists: Boolean,
    )

    /** 把某个分块的全部本地条目导成 JSON（与各自 ImportExport 导入格式对得上，可回灌）。 */
    suspend fun exportBlock(context: Context, block: String): String {
        val db = AppDatabase.getInstance(context)
        return when (block) {
            "configs" -> JSONObject().put("configs", JSONArray().also { arr ->
                db.apiConfigDao().getAll().first().forEach { arr.put(JSONObject(ImportExport.exportConfig(it))) } }).toString()
            "cards" -> JSONObject().put("cards", JSONArray().also { arr ->
                db.characterCardDao().getAll().first().forEach { arr.put(JSONObject(ImportExport.exportCharacterCard(it))) } }).toString()
            "worlds" -> JSONObject().put("worlds", JSONArray().also { arr ->
                WorldTreeStore.all(context).forEach { arr.put(JSONObject(ImportExport.exportWorldBook(it.name, it.description, it.content))) } }).toString()
            "memories" -> ImportExport.exportMemories(context, null)
            "conversations" -> JSONObject().put("conversations", JSONArray().also { arr ->
                (db.conversationDao().getActiveSummaries().first() + db.conversationDao().getArchivedSummaries().first())
                    .forEach { c -> ImportExport.exportConversation(c.id, context)?.let { arr.put(JSONObject(it)) } } }).toString()
            else -> "{}"
        }
    }

    private fun countOf(block: String, json: String): Int = try {
        val o = JSONObject(json)
        val key = when (block) { "configs" -> "configs"; "cards" -> "cards"; "worlds" -> "worlds"; "conversations" -> "conversations"; else -> "memories" }
        o.optJSONArray(key)?.length() ?: 0
    } catch (_: Exception) { 0 }

    /**
     * 把某个分块的云端 JSON 合并进本地，返回新增条数。
     * 幂等：按自然键跳过本地已存在的（configs=名+地址+模型+用途 / cards、worlds=名 / conversations=标题+条数），
     * 反复恢复不会翻倍。memories 走 upsertByTitle 天然按标题合并。
     * 注：分块是【文本数据】；角色卡头像、附件等文件不在内（那些走整包备份）。
     */
    private suspend fun importBlock(context: Context, block: String, json: String): Int {
        val db = AppDatabase.getInstance(context)
        return when (block) {
            "configs" -> {
                val seen = db.apiConfigDao().getAll().first()
                    .map { "${it.name}|${it.baseUrl}|${it.model}|${it.purpose}" }.toHashSet()
                loopImportDedup(json, "configs", { "${it.optString("name")}|${it.optString("baseUrl", it.optString("base_url"))}|${it.optString("model")}|${it.optString("purpose", "chat")}" }, seen) { ImportExport.importConfig(it, context) }
            }
            "cards" -> {
                val seen = db.characterCardDao().getAll().first().map { it.name }.toHashSet()
                loopImportDedup(json, "cards", { it.optString("name") }, seen) { ImportExport.importCharacterCard(it, context) }
            }
            "worlds" -> {
                val seen = WorldTreeStore.all(context).map { it.name }.toHashSet()
                loopImportDedup(json, "worlds", { it.optString("name") }, seen) { ImportExport.importWorldBook(it, context) }
            }
            "conversations" -> {
                val seen = (db.conversationDao().getActiveSummaries().first() + db.conversationDao().getArchivedSummaries().first()).map { it.title }.toHashSet()
                loopImportDedup(json, "conversations", { it.optString("title") }, seen) { ImportExport.importConversation(it, context) }
            }
            "memories" -> { ImportExport.importMemories(json, context); countOf(block, json) }
            else -> 0
        }
    }

    private suspend fun loopImportDedup(json: String, key: String, keyOf: (JSONObject) -> String, seen: HashSet<String>, imp: suspend (String) -> Unit): Int {
        val a = try { JSONObject(json).optJSONArray(key) } catch (_: Exception) { null } ?: return 0
        var n = 0
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val k = keyOf(o)
            if (k.isNotBlank() && !seen.add(k)) continue   // 本地已有或本批已导过 → 跳过
            try { imp(o.toString()); n++ } catch (_: Exception) {}   // 单条坏数据不影响其余
        }
        return n
    }

    // ---- 仓库文件读写（通用，供分块+manifest 用）----
    private fun shaOf(token: String, owner: String, repo: String, branch: String, path: String): String? {
        val c = conn("$API/repos/$owner/$repo/contents/$path?ref=$branch", "GET", token)
        val t = c.text()
        return if (c.responseCode == 200) JSONObject(t).optString("sha").takeIf { it.isNotBlank() } else null
    }
    private fun putContent(token: String, owner: String, repo: String, branch: String, path: String, bytes: ByteArray): Boolean {
        val put = conn("$API/repos/$owner/$repo/contents/$path", "PUT", token)
        val body = JSONObject().put("message", "Arix backup").put("content", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("branch", branch)
        shaOf(token, owner, repo, branch, path)?.let { body.put("sha", it) }
        put.writeJson(body.toString())
        val code = put.responseCode; put.text()
        return code in 200..299
    }
    private fun getRaw(token: String, owner: String, repo: String, branch: String, path: String): ByteArray? {
        val c = conn("$API/repos/$owner/$repo/contents/$path?ref=$branch", "GET", token)
        c.setRequestProperty("Accept", "application/vnd.github.raw")
        return when (c.responseCode) { in 200..299 -> c.inputStream.use { it.readBytes() }; else -> { c.text(); null } }
    }
    private fun deleteIfExists(s: Settings, owner: String, branch: String, path: String) {
        runCatching { if (shaOf(s.token, owner, s.repo, branch, path) != null) deletePath(s, owner, branch, path) }
    }
    private fun readManifest(token: String, owner: String, repo: String, branch: String): JSONObject =
        getRaw(token, owner, repo, branch, "manifest.json")?.let { try { JSONObject(String(it)) } catch (_: Exception) { JSONObject() } } ?: JSONObject()

    /**
     * 取某个分块的明文 JSON：先找加密版(.enc)，没有再找明文版(.json)。
     * 明文版也要过一次魔数探测——云端可能停在"文件名还是 .json、内容已经是密文"的中间态（比如上传成功但删明文那步失败）。
     */
    private fun getBlockJson(s: Settings, owner: String, branch: String, b: String, pass: String?): String? {
        getRaw(s.token, owner, s.repo, branch, blockFileEnc(b))?.let { return String(BackupCrypto.decrypt(it, pass)) }
        val raw = getRaw(s.token, owner, s.repo, branch, blockFile(b)) ?: return null
        return String(BackupCrypto.openBlob(raw, pass))
    }

    /**
     * 上传一个分块（设了口令就加密），成功则更新本地指纹与 manifest。
     * @param key 本轮同步已派生好的密钥；null=不加密。
     */
    private fun putBlock(
        context: Context, s: Settings, owner: String, branch: String, b: String, json: String,
        key: SecretKeySpec?, salt: ByteArray?, manifest: JSONObject,
    ): Boolean {
        val plain = json.toByteArray()
        val encrypted = key != null && salt != null
        val bytes = if (encrypted) BackupCrypto.encryptWith(plain, key!!, salt!!, BackupCrypto.ITERATIONS) else plain
        val path = if (encrypted) blockFileEnc(b) else blockFile(b)
        if (!putContent(s.token, owner, s.repo, branch, path, bytes)) return false
        // ⚠ 指纹永远算【明文】：密文因为 IV 随机每次都不同，拿它比对等于「每次都变了」，增量同步就废了
        setBlockHash(context, b, sha256(json))
        // 同一块的另一种形态留在云上 = 加密白加（明文还留着）/ 或恢复时读到过期旧版本 → 删掉。
        // 只在「加密状态翻转的那一次」才发这个删除请求：稳态下增量同步每块本来只有 2 个请求，
        // 无脑每次都探一下会白白多 50% 的请求量（后台任务，能省则省）。
        if (blockEnc(context, b) != encrypted) {
            deleteIfExists(s, owner, branch, if (encrypted) blockFile(b) else blockFileEnc(b))
            setBlockEnc(context, b, encrypted)
        }
        // size 记明文长度：对比页拿它和本地明文比，记密文长度会永远显示不一致
        manifest.put(b, JSONObject().put("count", countOf(b, json)).put("size", plain.size)
            .put("ts", System.currentTimeMillis()).put("enc", encrypted))
        return true
    }

    /** 备份选中的分块（各自一个文件）+ 更新 manifest。 */
    suspend fun backupBlocks(context: Context, blocks: Set<String>, passphrase: String? = null): String = withContext(Dispatchers.IO) {
        val s = settings(context); if (s.token.isBlank()) return@withContext "请先填 GitHub Token"
        if (blocks.isEmpty()) return@withContext "没选要备份的分块"
        try {
            val owner = login(s.token)
            val branch = effectiveBranch(s.branch, resolveRepo(s.token, owner, s.repo))
            val manifest = readManifest(s.token, owner, s.repo, branch)
            // 一轮只派生一次密钥：PBKDF2 十几万轮在手表上不便宜，每块都派生就是 N 倍
            val pass = BackupCrypto.resolve(context, passphrase)
            val salt = if (pass.isNotEmpty()) BackupCrypto.newSalt() else null
            val key = salt?.let { BackupCrypto.deriveKey(pass, it, BackupCrypto.ITERATIONS) }
            var ok = 0
            for (b in blocks) {
                if (putBlock(context, s, owner, branch, b, exportBlock(context, b), key, salt, manifest)) ok++
            }
            putContent(s.token, owner, s.repo, branch, "manifest.json", manifest.toString().toByteArray())
            setLastBackup(context)
            "已备份 $ok/${blocks.size} 个分块到私有仓库 $owner/${s.repo}@$branch" + (if (key != null) "（已加密）" else "")
        } catch (ce: CancellationException) { throw ce
        } catch (e: Exception) { "分块备份失败：${e.message}" }
    }

    /** 恢复选中的分块：合并进本地（保留双方，非破坏）。加密分块需要口令，口令不对会直接报出来。 */
    suspend fun restoreBlocks(context: Context, blocks: Set<String>, passphrase: String? = null): String = withContext(Dispatchers.IO) {
        val s = settings(context); if (s.token.isBlank()) return@withContext "请先填 GitHub Token"
        if (blocks.isEmpty()) return@withContext "没选要恢复的分块"
        try {
            val owner = login(s.token)
            val def = resolveRepoReadOnly(s.token, owner, s.repo) ?: return@withContext "云端仓库还不存在，先备份一次"
            val branch = effectiveBranch(s.branch, def)
            val pass = BackupCrypto.resolve(context, passphrase)
            val parts = ArrayList<String>()
            for (b in blocks) {
                val json = getBlockJson(s, owner, branch, b, pass)
                if (json == null) { parts.add("${label(b)}:云端无"); continue }
                val n = importBlock(context, b, json)
                parts.add("${label(b)}:合并 $n 条")
            }
            "恢复完成 · " + parts.joinToString("，")
        } catch (ce: CancellationException) { throw ce
        } catch (e: BackupPassphraseException) { "分块恢复失败：${e.message}"
        } catch (e: Exception) { "分块恢复失败：${e.message}" }
    }

    /** 对比本地 vs 云端（条数/大小/云端备份时间）。云端读 manifest，快。 */
    suspend fun compareBlocks(context: Context): List<BlockCompare> = withContext(Dispatchers.IO) {
        val s = settings(context); if (s.token.isBlank()) return@withContext emptyList()
        try {
            val owner = login(s.token)
            val def = resolveRepoReadOnly(s.token, owner, s.repo)   // 对比不建仓库；不存在则云端全为「无」
            val manifest = if (def != null) readManifest(s.token, owner, s.repo, effectiveBranch(s.branch, def)) else JSONObject()
            BLOCKS.map { (b, lbl) ->
                val localJson = exportBlock(context, b)
                val mo = manifest.optJSONObject(b)
                BlockCompare(b, lbl, countOf(b, localJson), localJson.toByteArray().size,
                    mo?.optInt("count") ?: 0, mo?.optInt("size") ?: 0, mo?.optLong("ts") ?: 0L, mo != null)
            }
        } catch (ce: CancellationException) { throw ce } catch (_: Exception) { emptyList() }
    }

    private fun label(b: String) = BLOCKS.firstOrNull { it.first == b }?.second ?: b

    // ---- 联网自动备份 ----
    fun autoSync(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("auto_sync", false)
    fun setAutoSync(c: Context, v: Boolean) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("auto_sync", v).apply()
    fun intervalHours(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("interval_h", 12).coerceIn(1, 168)
    fun setIntervalHours(c: Context, v: Int) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("interval_h", v.coerceIn(1, 168)).apply()
    fun lastBackupTs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("last_backup", 0L)
    private fun setLastBackup(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("last_backup", System.currentTimeMillis()).apply()

    private fun isOnline(c: Context): Boolean = try {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) { false }

    /** 应用启动时调：开了自动同步、联网、且距上次备份超过间隔，就后台整包备份一次。 */
    suspend fun maybeAutoBackup(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!autoSync(context)) return@withContext false
        if (settings(context).token.isBlank()) return@withContext false
        if (System.currentTimeMillis() - lastBackupTs(context) < intervalHours(context) * 3600_000L) return@withContext false
        if (!isOnline(context)) return@withContext false
        backup(context).startsWith("备份成功")   // backup() 内部已 setLastBackup
    }

    // ========================================================
    // 实时 / 增量同步 —— 数据变更后【防抖】只把内容变化的分块(block_*.json)推上去 + 更新 manifest。
    // 与上面「联网自动备份」(整包 zip, 按小时间隔) 并列、互不干扰：实时同步只碰分块文件，
    // 不重置整包备份的 last_backup 计时，整包周期快照照常跑（附件/头像等非文本数据仍靠整包）。
    // 判定变化 = 每块导出 JSON 的 SHA-256 与上次上传记录比对，一致就不发请求。
    // ========================================================
    private val rtScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var rtJob: Job? = null

    fun realtime(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("realtime", false)
    fun setRealtime(c: Context, v: Boolean) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("realtime", v).apply()
    fun debounceSeconds(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("rt_debounce_s", 45).coerceIn(5, 3600)
    fun setDebounceSeconds(c: Context, v: Int) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("rt_debounce_s", v.coerceIn(5, 3600)).apply()
    fun rtLastTs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("rt_last", 0L)
    private fun setRtLast(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("rt_last", System.currentTimeMillis()).apply()
    private fun blockHash(c: Context, b: String) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("blk_hash_$b", "") ?: ""
    private fun setBlockHash(c: Context, b: String, h: String) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("blk_hash_$b", h).apply()
    /** 上次这块传上去的是不是密文——只用来决定「要不要顺手删掉另一种形态」，省掉稳态下的额外请求。 */
    private fun blockEnc(c: Context, b: String) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("blk_enc_$b", false)
    private fun setBlockEnc(c: Context, b: String, v: Boolean) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("blk_enc_$b", v).apply()

    private fun sha256(s: String): String = try {
        java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { s.length.toString() }   // 摘要不可用就退化到长度，只影响「是否重传」的精度，不影响正确性

    /**
     * 数据变更后调用（发消息/存卡/改记忆等之后）：开了实时同步就【防抖】调度一次增量同步。
     * 重复调用会重置防抖计时——最后一次变更后静默 debounce 秒才真正上传，把一连串改动合成一次，避免频繁上传。
     * 未开实时 / 未填 token 直接忽略；非阻塞、可从任意线程调。
     */
    fun notifyChanged(context: Context) {
        val app = context.applicationContext
        if (!realtime(app) || settings(app).token.isBlank()) return
        val delayMs = debounceSeconds(app) * 1000L
        rtJob?.cancel()
        rtJob = rtScope.launch {
            try {
                delay(delayMs)
                syncChanged(app)
            } catch (_: CancellationException) { /* 被后续变更重置，正常 */
            } catch (_: Exception) { /* 后台静默，不打扰用户 */ }
        }
    }

    /**
     * 增量同步：本地先算各分块 hash，只挑出变化的 PUT 上去 + 更新 manifest；无变化则一个请求都不发。
     * 返回简报，可给「立即增量同步」按钮直接调（suspend）。
     */
    suspend fun syncChanged(context: Context): String = withContext(Dispatchers.IO) {
        val s = settings(context); if (s.token.isBlank()) return@withContext "未配置 Token"
        if (!isOnline(context)) return@withContext "离线，已跳过"
        val local = BLOCKS.map { it.first to exportBlock(context, it.first) }               // 本地导出（DB 读取）
        // 变化判定跑在【明文】上，与加不加密完全无关：所以开了加密也不会退化成"每次全量重传"。
        // （密文的字节每次都不同——IV 是随机的——拿密文比对必然次次都"变了"。）
        val changed = local.filter { (b, json) -> sha256(json) != blockHash(context, b) }   // 只留内容变了的块
        if (changed.isEmpty()) return@withContext "无变化，未上传"
        try {
            val owner = login(s.token)
            val branch = effectiveBranch(s.branch, resolveRepo(s.token, owner, s.repo))
            val manifest = readManifest(s.token, owner, s.repo, branch)
            // 派生一次给本轮所有块用；没设口令时 key=null，整条路径与以前一致（零额外开销）
            val pass = BackupCrypto.resolve(context, null)
            val salt = if (pass.isNotEmpty()) BackupCrypto.newSalt() else null
            val key = salt?.let { BackupCrypto.deriveKey(pass, it, BackupCrypto.ITERATIONS) }
            var ok = 0
            for ((b, json) in changed) {
                if (putBlock(context, s, owner, branch, b, json, key, salt, manifest)) ok++
            }
            if (ok > 0) putContent(s.token, owner, s.repo, branch, "manifest.json", manifest.toString().toByteArray())
            setRtLast(context)
            "增量同步 $ok 个变化分块 → $owner/${s.repo}@$branch"
        } catch (ce: CancellationException) { throw ce
        } catch (e: Exception) { "增量同步失败：${e.message}" }
    }
}
