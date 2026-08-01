package com.arix.app

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ============================================================
// I18n —— 运行时多语言（以「中文原串」作 key 的翻译目录）。
//
// 用法：把**用户可见的界面文字**从 "中文" 改成 tr("中文") 即可。
//   Text(tr("保存"))  ·  Toast(..., tr("已导入"))  ·  label = { Text(tr("名称")) }
//
// 机制：中文(ZH)=直接返回原串（零风险，不破坏中文）；其它语言查 I18nStrings.get(lang, 原串)，缺译回退中文。
// 切换即时生效不重启：XtomTheme 订阅 lang 流，改语言全 App 重组，tr() 用新语言重新求值。
//
// 翻译表流水线（见仓库 I18N-HANDOFF.md）：
//   tools/i18n_extract.py 抽出全部 UI 串 → i18n/i18n_table.json（完整表）
//   → opencode 把每个串翻成各语言（只填表、不碰代码）
//   → tools/i18n_embed.py 把译文表生成 I18nStrings.kt（本文件的 MAP）
//
// ⚠️ 只包**界面文字**。绝不要包：喂给 LLM 的提示词、工具 description/参数、记忆注入文本、
//    JSON 字段名、日志/调试串 —— 那些翻译会改变 AI 行为或破坏协议。
// ============================================================
object I18n {
    // 支持语言：ZH 为基准(源语言)。全语言覆盖——加语言只需在这里加一项 + 翻译表补该 code 列。
    // label 用各语言母语名（选择器直观）。ar/fa/he 为 RTL，字串已译，版式 RTL 打磨另议。
    enum class Lang(val code: String, val label: String) {
        ZH("zh", "中文"),
        ZH_TW("zh-TW", "繁體中文"),
        EN("en", "English"),
        ES("es", "Español"),
        PT("pt", "Português"),
        FR("fr", "Français"),
        DE("de", "Deutsch"),
        IT("it", "Italiano"),
        NL("nl", "Nederlands"),
        RU("ru", "Русский"),
        UK("uk", "Українська"),
        PL("pl", "Polski"),
        CS("cs", "Čeština"),
        RO("ro", "Română"),
        EL("el", "Ελληνικά"),
        HU("hu", "Magyar"),
        SV("sv", "Svenska"),
        DA("da", "Dansk"),
        FI("fi", "Suomi"),
        NB("nb", "Norsk"),
        TR("tr", "Türkçe"),
        AR("ar", "العربية"),
        FA("fa", "فارسی"),
        HE("he", "עברית"),
        HI("hi", "हिन्दी"),
        BN("bn", "বাংলা"),
        TA("ta", "தமிழ்"),
        JA("ja", "日本語"),
        KO("ko", "한국어"),
        TH("th", "ไทย"),
        VI("vi", "Tiếng Việt"),
        ID("id", "Bahasa Indonesia"),
        MS("ms", "Bahasa Melayu"),
        TL("tl", "Filipino");

        companion object {
            fun fromCode(code: String?): Lang = entries.firstOrNull { it.code == code } ?: ZH

            /** 首启（未手动选过语言）时按系统语言映射到最接近的支持语言；无匹配回落中文。 */
            fun fromSystem(): Lang {
                val loc = java.util.Locale.getDefault()
                val lang = loc.language.lowercase()
                val country = loc.country.uppercase()
                val script = (loc.script ?: "").lowercase()
                if (lang == "zh") {
                    return if (country in setOf("TW", "HK", "MO") || script == "hant") ZH_TW else ZH
                }
                return mapOf(
                    "en" to EN, "es" to ES, "pt" to PT, "fr" to FR, "de" to DE, "it" to IT,
                    "nl" to NL, "ru" to RU, "uk" to UK, "pl" to PL, "cs" to CS, "ro" to RO,
                    "el" to EL, "hu" to HU, "sv" to SV, "da" to DA, "fi" to FI, "nb" to NB,
                    "no" to NB, "tr" to TR, "ar" to AR, "fa" to FA, "he" to HE, "iw" to HE,
                    "hi" to HI, "bn" to BN, "ta" to TA, "ja" to JA, "ko" to KO, "th" to TH,
                    "vi" to VI, "id" to ID, "in" to ID, "ms" to MS, "tl" to TL, "fil" to TL,
                )[lang] ?: ZH
            }
        }
    }

    private const val PREFS = "xtom_i18n"
    private const val KEY = "lang"

    private val _lang = MutableStateFlow(Lang.ZH)
    val lang: StateFlow<Lang> = _lang.asStateFlow()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 首帧装载已保存语言；没手动选过（首次启动）则按系统语言映射。XtomTheme 调用。 */
    fun load(context: Context) {
        val saved = prefs(context).getString(KEY, null)
        _lang.value = if (saved == null) Lang.fromSystem() else Lang.fromCode(saved)
    }

    fun set(context: Context, l: Lang) {
        _lang.value = l
        prefs(context).edit().putString(KEY, l.code).apply()
    }

    /** 翻译：中文原串 → 当前语言。基准中文直接返回；其它语言缺译回退中文。 */
    fun tr(zh: String): String {
        val l = _lang.value
        if (l == Lang.ZH) return zh
        return I18nStrings.get(l.code, zh) ?: zh
    }
}

/** 顶层便捷函数：tr("中文")。可在 Composable 与普通代码里通用。 */
fun tr(zh: String): String = I18n.tr(zh)

/** 当前语言的 CompositionLocal（XtomTheme 提供；驱动界面随切换重组）。 */
val LocalLang = staticCompositionLocalOf { I18n.Lang.ZH }
