/*
 * Copyright 2025-2026 Arix.
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Clean-room original implementation for the Arix wake module.
 * Not derived from any GPL/LGPL-licensed source.
 */

package com.arix.wake

import android.content.Context
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * L2 流式 KWS 判决器：跑 microWakeWord 的 INT8 tflite 模型（Apache-2.0）。见 DESIGN-WAKE.md §4。
 *
 * ⚠️ 脚手架（P4）：I/O 走「模型已烘进前端、直接喂 16k 原始音频」的推荐导出方式，量化/反量化
 * 已按张量参数处理，但**确切输入格式（原始音频 vs 40 维特征、步长、是否有状态）必须在拿到
 * 训练好的 hi_xtom.tflite 后按其张量形状校准**。模型缺失时 [create] 返回 null → 工厂回退 DTW。
 */
internal class KwsDetector private constructor(
    private val interpreter: Interpreter,
    private val phrase: String,
    private val threshold: Float,
) : WakeDetector {

    override val id: String = "kws:" + phrase.lowercase().replace(' ', '_')

    private val inputTensor = interpreter.getInputTensor(0)
    private val outputTensor = interpreter.getOutputTensor(0)
    private val inputElems = inputTensor.shape().fold(1) { a, b -> a * b }
    private val inputType = inputTensor.dataType()
    private val outputElems = outputTensor.shape().fold(1) { a, b -> a * b }
    private val outputType = outputTensor.dataType()

    private val inputBuffer =
        ByteBuffer.allocateDirect(inputElems * inputType.byteSize()).order(ByteOrder.nativeOrder())
    private val outputBuffer =
        ByteBuffer.allocateDirect(outputElems * outputType.byteSize()).order(ByteOrder.nativeOrder())

    /** 累积样本，按模型输入长度非重叠地喂入（流式模型内部保持状态）。 */
    private val pending = ArrayList<Short>(inputElems * 2)

    override fun reset() {
        pending.clear()
        // 流式模型状态由 interpreter 持有；如需硬复位可重建 interpreter（此处从简）。
    }

    override fun accept(pcm: ShortArray, length: Int): WakeDetection? {
        val n = minOf(length, pcm.size)
        for (i in 0 until n) pending.add(pcm[i])

        var result: WakeDetection? = null
        while (pending.size >= inputElems) {
            val score = infer(pending, inputElems)
            pending.subList(0, inputElems).clear() // 消费一步
            if (score >= threshold) {
                result = WakeDetection(detectorId = id, score = score, phrase = phrase)
                break
            }
        }
        return result
    }

    override fun onSegmentEnd(): WakeDetection? = null // 流式判决器不在段结束出分

    private fun infer(samples: List<Short>, count: Int): Float {
        val inScale = inputTensor.quantizationParams().scale
        val inZero = inputTensor.quantizationParams().zeroPoint

        inputBuffer.rewind()
        for (i in 0 until count) {
            val f = samples[i] / 32768f
            when (inputType) {
                DataType.FLOAT32 -> inputBuffer.putFloat(f)
                DataType.INT8 -> inputBuffer.put(quantize(f, inScale, inZero, -128, 127))
                DataType.UINT8 -> inputBuffer.put(quantize(f, inScale, inZero, 0, 255))
                else -> inputBuffer.putFloat(f)
            }
        }

        inputBuffer.rewind()
        outputBuffer.rewind()
        interpreter.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()

        val outScale = outputTensor.quantizationParams().scale
        val outZero = outputTensor.quantizationParams().zeroPoint
        var best = 0f
        for (i in 0 until outputElems) {
            val v = when (outputType) {
                DataType.FLOAT32 -> outputBuffer.getFloat()
                DataType.INT8 -> (outputBuffer.get().toInt() - outZero) * outScale
                DataType.UINT8 -> ((outputBuffer.get().toInt() and 0xFF) - outZero) * outScale
                else -> outputBuffer.getFloat()
            }
            if (v > best) best = v
        }
        return best
    }

    private fun quantize(f: Float, scale: Float, zero: Int, lo: Int, hi: Int): Byte {
        val s = if (scale == 0f) 1f else scale
        return (Math.round(f / s) + zero).coerceIn(lo, hi).toByte()
    }

    override fun close() {
        try {
            interpreter.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "KwsDetector"

        /**
         * 加载资源里的 tflite 模型建判决器；模型缺失/加载失败返回 null（工厂据此回退 DTW）。
         */
        fun create(context: Context, assetPath: String, phrase: String, threshold: Float): KwsDetector? {
            return try {
                val bytes = context.applicationContext.assets.open(assetPath).use { it.readBytes() }
                val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
                buf.put(bytes)
                buf.rewind()
                val interp = Interpreter(buf, Interpreter.Options().apply { setNumThreads(1) })
                Log.d(
                    TAG,
                    "loaded KWS $assetPath in=${interp.getInputTensor(0).shape().toList()}/${interp.getInputTensor(0).dataType()} out=${interp.getOutputTensor(0).shape().toList()}"
                )
                KwsDetector(interp, phrase, threshold)
            } catch (t: Throwable) {
                // Throwable 而不是 Exception：tflite 运行时是 compileOnly（不打进包，见 wake/build.gradle.kts），
                // 缺类时抛的 NoClassDefFoundError 是 Error。调用方 WakeEngines 也兜了一层。
                Log.w(TAG, "KWS model/runtime unavailable ($assetPath): ${t.message} — 回退嵌入原型判决器")
                null
            }
        }
    }
}
