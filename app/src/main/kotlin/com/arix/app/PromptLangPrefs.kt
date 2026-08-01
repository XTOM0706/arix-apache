package com.arix.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 提示词语言：给模型看的提示词用中文还是英文。
 *
 * 中文提示词在非中文语料模型的词表里要交「中文税」（实测同义句子省 28~43%），
 * 所以给模型看的结构化提示词尽量英文；但有些用户/模型更吃中文，就留中文。
 * 默认中文 = 不改变现有行为；选英文后，系统提示/元任务提示词等改用英文版。
 *
 * 这只影响「喂给模型」的文本；界面语言走 I18n，两者独立。
 */
object PromptLangPrefs {
    private const val PREFS = "xtom_prompt_lang"
    private const val KEY = "prompt_lang"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    const val ZH = "zh"
    const val EN = "en"

    /** 变更信号：任一 set 后自增。设置页订阅它即时刷新。 */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()
    private fun bump() { _version.value = _version.value + 1 }

    // 进程级镜像：提示词在大量非 Composable 函数里构造（压缩/抽取/审批…），它们拿不到 Context，
    // 所以这里缓存一份当前值，XtomApp.onCreate 载入、set() 同步更新。pick() 直接读它。
    private val _current = MutableStateFlow(ZH)
    fun current(): String = _current.value
    fun isEn(): Boolean = _current.value == EN

    /** XtomApp.onCreate 调一次：把磁盘值读进进程镜像。 */
    fun init(context: Context) {
        _current.value = p(context).getString(KEY, ZH) ?: ZH
    }

    fun get(c: Context): String = p(c).getString(KEY, ZH) ?: ZH
    fun set(c: Context, v: String) {
        val nv = if (v == EN) EN else ZH
        _current.value = nv
        p(c).edit().putString(KEY, nv).apply()
        bump()
    }
}
