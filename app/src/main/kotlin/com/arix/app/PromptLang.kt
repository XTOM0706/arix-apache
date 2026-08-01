package com.arix.app

// 系统提示的语言指令：让 AI 按界面语言回复。
// 界面是中文(ZH)时返回空——本项目中文优先，默认即中文，不必多此一句。
// 其它语言追加一句「用<该语言>回复」，用英文写指令（模型对指令语言不敏感），
// 语言名用母语名(endonym，如 Deutsch/日本語/繁體中文)——模型都认得，且避免再维护一份英文名表。
// 只注入到「对话/唤醒/日记/主动消息」这类真·对话提示；翻译、角色卡生成等元任务不注入（各有自己的任务语义）。
object PromptLang {
    fun directive(): String {
        val l = I18n.lang.value
        if (l == I18n.Lang.ZH) return ""
        return "\n\n[Language] Reply to the user in ${l.label} by default. If the user clearly writes in another language, follow the user's language instead."
    }

    /**
     * 按「提示词语言」设置取中/英版本（PromptLangPrefs，XtomApp 启动时载入进程镜像）。
     * 给模型看的提示词用这个：中文=默认、英文=省 token。界面文案仍走 tr()，别混用。
     * 提示词构造点可能没有 Context，故这里不带 context 参数。
     */
    fun pick(zh: String, en: String): String =
        if (PromptLangPrefs.isEn()) en else zh
}

