package com.arix.app

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * 每页背景图配置：pageKey → 图片 uri（内容区铺该图，页面内容透过半透明遮罩显示在其上）。
 * 裁剪先自动填充居中（ContentScale.Crop），手势裁剪后续再加。
 *
 * **与终端 App 共用同一个图库**（用户要求"两个 App 都能换背景而且不打架"）：
 * 图片统一落 `/sdcard/Arix/backgrounds/`，两个 App 各读各的 key，互不覆盖：
 *   - 主 App 的页面 → `app_<page>.img`
 *   - 终端 App 的页面 → `term_<page>.img`（由终端那边写）
 *   - 通用背景 → `shared_all.img`，**两边都在自己没设专属背景时回退到它**，
 *     所以设一张就能让全套界面统一，也不妨碍某一页单独换。
 * 见终端侧同名实现 terminal/.../ui/PageBackground.kt（两个 App 独立进程、引不到彼此的代码，
 * 只能各写一份读同一个约定目录——改约定时两边都要改）。
 */
object PageBackgroundPrefs {
    private const val PREFS = "xtom_page_bg"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 通用背景的 pageKey（不是真页面，是"没单独设就用这张"）。 */
    const val KEY_ALL = "all"

    /** 共享图库目录：两个 App 都往这儿读写。 */
    fun sharedDir(): File =
        File(android.os.Environment.getExternalStorageDirectory(), "Arix/backgrounds")

    private fun sharedFile(pageKey: String): File =
        File(sharedDir(), if (pageKey == KEY_ALL) "shared_all.img" else "app_$pageKey.img")

    private fun File.asUriString(): String = Uri.fromFile(this).toString()

    /**
     * 解析某页最终用哪张图。顺序：本页专属 → 旧版 SAF uri（老用户不丢配置）→ 通用背景。
     * 返回可直接交给 Coil 的 uri 字符串；没有则 null。
     */
    fun get(c: Context, pageKey: String): String? {
        sharedFile(pageKey).takeIf { it.isFile && it.length() > 0 }?.let { return it.asUriString() }
        p(c).getString("bg_$pageKey", null)?.ifBlank { null }?.let { return it }
        if (pageKey != KEY_ALL) {
            sharedFile(KEY_ALL).takeIf { it.isFile && it.length() > 0 }?.let { return it.asUriString() }
        }
        return null
    }

    /** 这一页有没有**自己专属**的背景（用于设置页显示"已设/未设"，别把通用那张算进来）。 */
    fun hasOwn(c: Context, pageKey: String): Boolean =
        sharedFile(pageKey).let { it.isFile && it.length() > 0 } ||
            !p(c).getString("bg_$pageKey", null).isNullOrBlank()

    /**
     * 设背景：把选中的图**复制进共享目录**（不存 SAF uri —— 那个权限重启后可能失效，
     * 而且终端 App 也读不到别的 App 授的 uri）。复制不成（没存储权限等）就退回旧的存 uri 方式，
     * 至少主 App 自己还能用。
     */
    fun set(c: Context, pageKey: String, uri: String?) {
        if (uri.isNullOrBlank()) {
            runCatching { sharedFile(pageKey).delete() }
            p(c).edit().remove("bg_$pageKey").apply()
            return
        }
        val ok = runCatching { copyIn(c, Uri.parse(uri), sharedFile(pageKey)) }.getOrDefault(false)
        if (ok) p(c).edit().remove("bg_$pageKey").apply()      // 已落地成文件，旧 uri 记录就别留了
        else p(c).edit().putString("bg_$pageKey", uri).apply()
    }

    /** 先写临时文件再改名：另一个 App 正好在读时，不会读到写了一半的图。 */
    private fun copyIn(c: Context, src: Uri, dest: File): Boolean {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        c.contentResolver.openInputStream(src)?.use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        } ?: return false
        if (dest.exists()) dest.delete()
        return tmp.renameTo(dest)
    }

    /** 内容遮罩不透明度（0=全透只见图、1=纯色不见图）。默认 0.82，保证内容可读。 */
    fun scrimAlpha(c: Context): Float = p(c).getFloat("scrim", 0.82f)
    fun setScrimAlpha(c: Context, v: Float) = p(c).edit().putFloat("scrim", v).apply()

    /** 可配背景的主要页面（pageKey to 显示名）。 */
    val PAGES = listOf(
        KEY_ALL to "通用（含终端）",
        "chat" to "聊天", "cards" to "角色卡", "worldtree" to "世界书", "memory" to "记忆",
        "conversations" to "对话管理", "config" to "模型配置", "settings_hub" to "设置", "wake" to "语音唤醒",
    )
}
