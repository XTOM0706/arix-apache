/*
 * Copyright 2025-2026 Arix.
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Clean-room original implementation for the Arix wake module.
 * Not derived from any GPL/LGPL-licensed source. This is generic ONNX Runtime
 * plumbing around the Silero VAD model (the model itself is MIT-licensed and
 * bundled unchanged); the class adapts to the model's documented I/O by reading
 * its input/output metadata at load time.
 */

package com.arix.wake

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Silero VAD 封装（L1）：输入一帧 PCM16，返回语音概率 [0,1]。
 * 见 DESIGN-WAKE.md §3.2。仅在 L0 能量门放行的帧上调用，减少推理次数。
 *
 * 通过读取模型输入/输出元数据自适应不同 Silero 变体（state 版 / h,c 版；带/不带 sr）。
 */
internal class SileroVad(
    context: Context,
    private val sampleRate: Int = 16000,
    private val frameSize: Int = 512,
    modelAssetPath: String = "models/silero_vad.onnx",
) : AutoCloseable {

    private companion object {
        private const val TAG = "SileroVad"

        fun assetToCache(context: Context, assetPath: String): File {
            val out = File(context.cacheDir, assetPath)
            if (out.exists()) return out
            out.parentFile?.let { if (!it.exists()) it.mkdirs() }
            context.assets.open(assetPath).use { input ->
                FileOutputStream(out).use { input.copyTo(it) }
            }
            return out
        }

        fun normalizeShape(shape: LongArray?, fallback: LongArray): LongArray {
            if (shape == null || shape.isEmpty()) return fallback
            return LongArray(shape.size) { i -> if (shape[i] <= 0) 1L else shape[i] }
        }

        /** 递归取标量 float（模型输出可能嵌套数组）。 */
        fun scalar(value: Any?): Float = when (value) {
            is FloatArray -> value.firstOrNull() ?: 0f
            is Array<*> -> scalar(value.firstOrNull())
            else -> 0f
        }

        /** 递归展平成 FloatArray；长度不符返回 null。 */
        fun flatFloats(value: Any?, expected: Int): FloatArray? {
            val acc = ArrayList<Float>(expected)
            fun walk(v: Any?) {
                when (v) {
                    is FloatArray -> v.forEach { acc.add(it) }
                    is Array<*> -> v.forEach { walk(it) }
                }
            }
            walk(value)
            return if (acc.size == expected) acc.toFloatArray() else null
        }
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    private val audioInput: String
    private val srInput: String?
    private val stateInput: String?
    private val hInput: String?
    private val cInput: String?

    private val windowSize: Int
    private val contextSize: Int

    private val audioShape: LongArray
    private val srShape: LongArray
    private val stateShape: LongArray
    private val hShape: LongArray
    private val cShape: LongArray

    private var prevContext = FloatArray(0)
    private var state = FloatArray(2 * 128)
    private var h = FloatArray(2 * 64)
    private var c = FloatArray(2 * 64)

    init {
        val modelFile = assetToCache(context.applicationContext, modelAssetPath)
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(modelFile.absolutePath, opts)

        val names = session.inputNames.toSet()
        audioInput = when {
            "input" in names -> "input"
            "audio" in names -> "audio"
            "x" in names -> "x"
            else -> session.inputNames.firstOrNull() ?: "input"
        }
        srInput = when {
            "sr" in names -> "sr"
            "sample_rate" in names -> "sample_rate"
            else -> null
        }
        stateInput = if ("state" in names) "state" else null
        hInput = if ("h" in names) "h" else null
        cInput = if ("c" in names) "c" else null

        fun infoOf(name: String): TensorInfo? = session.inputInfo[name]?.info as? TensorInfo

        val audioModelShape = infoOf(audioInput)?.shape
        val lastDim = audioModelShape?.lastOrNull()?.toInt() ?: -1
        windowSize = when {
            lastDim > 0 -> lastDim
            sampleRate == 16000 && frameSize == 512 -> 512 + 64
            sampleRate == 8000 && frameSize == 256 -> 256 + 32
            else -> frameSize
        }
        contextSize = (windowSize - frameSize).coerceAtLeast(0)
        prevContext = FloatArray(contextSize)

        audioShape = when (audioModelShape?.size) {
            1 -> longArrayOf(windowSize.toLong())
            else -> longArrayOf(1, windowSize.toLong())
        }
        srShape = when (srInput?.let { infoOf(it)?.shape?.size }) {
            null -> longArrayOf(1)
            0 -> longArrayOf()
            else -> normalizeShape(infoOf(srInput)?.shape, longArrayOf(1))
        }
        stateShape = normalizeShape(stateInput?.let { infoOf(it)?.shape }, longArrayOf(2, 1, 128))
        hShape = normalizeShape(hInput?.let { infoOf(it)?.shape }, longArrayOf(2, 1, 64))
        cShape = normalizeShape(cInput?.let { infoOf(it)?.shape }, longArrayOf(2, 1, 64))

        Log.d(TAG, "loaded silero vad window=$windowSize context=$contextSize inputs=${session.inputNames}")
    }

    fun reset() {
        prevContext = FloatArray(contextSize)
        state = FloatArray(2 * 128)
        h = FloatArray(2 * 64)
        c = FloatArray(2 * 64)
    }

    /** 一帧 [frameSize] 样本 PCM16 的语音概率。 */
    fun probability(frame: ShortArray, length: Int): Float {
        val n = minOf(length, frameSize)
        val audio = FloatArray(frameSize)
        for (i in 0 until n) audio[i] = frame[i] / 32768f

        val modelInput = if (contextSize > 0) {
            val buf = FloatArray(contextSize + frameSize)
            if (prevContext.size == contextSize) System.arraycopy(prevContext, 0, buf, 0, contextSize)
            System.arraycopy(audio, 0, buf, contextSize, frameSize)
            buf
        } else {
            audio
        }

        val prob = runModel(modelInput)

        if (contextSize > 0) {
            prevContext = modelInput.copyOfRange(modelInput.size - contextSize, modelInput.size)
        }
        return prob
    }

    private fun runModel(audioData: FloatArray): Float {
        val toClose = ArrayList<AutoCloseable>(4)
        try {
            val inputs = LinkedHashMap<String, OnnxTensor>()

            val audioTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(audioData), audioShape)
            toClose.add(audioTensor)
            inputs[audioInput] = audioTensor

            srInput?.let { name ->
                val t = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(sampleRate.toLong())), srShape)
                toClose.add(t)
                inputs[name] = t
            }

            if (stateInput != null) {
                val t = OnnxTensor.createTensor(env, FloatBuffer.wrap(state), stateShape)
                toClose.add(t)
                inputs[stateInput] = t
            } else if (hInput != null && cInput != null) {
                val ht = OnnxTensor.createTensor(env, FloatBuffer.wrap(h), hShape)
                toClose.add(ht)
                inputs[hInput] = ht
                val ct = OnnxTensor.createTensor(env, FloatBuffer.wrap(c), cShape)
                toClose.add(ct)
                inputs[cInput] = ct
            }

            session.run(inputs).use { out ->
                val prob = scalar(out[0].value)
                if (stateInput != null && out.size() >= 2) {
                    flatFloats(out[1].value, 2 * 128)?.let { state = it }
                } else if (hInput != null && cInput != null && out.size() >= 3) {
                    flatFloats(out[1].value, 2 * 64)?.let { h = it }
                    flatFloats(out[2].value, 2 * 64)?.let { c = it }
                }
                return prob
            }
        } finally {
            for (i in toClose.indices.reversed()) {
                try {
                    toClose[i].close()
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun close() {
        try {
            session.close()
        } catch (_: Exception) {
        }
    }
}
