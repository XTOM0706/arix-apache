package com.arix.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

object WaifuProcessor {

    private val SENTENCE_END = Regex("""[。！？.!?\n]""")

    fun flowSentences(fullText: String, delayMs: Int = 250): Flow<String> = flow {
        var emitted = 0
        SENTENCE_END.findAll(fullText).forEach { match ->
            val end = match.range.last + 1
            if (end > emitted) {
                val sentence = fullText.substring(emitted, end)
                emitted = end
                emit(sentence)
                delay(delayMs.toLong())
            }
        }
        if (emitted < fullText.length) emit(fullText.substring(emitted))
    }
}
