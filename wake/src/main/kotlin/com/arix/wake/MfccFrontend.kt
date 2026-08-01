/*
 * Copyright 2025-2026 Arix.
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Clean-room original implementation for the Arix wake module.
 * Not derived from any GPL/LGPL-licensed source. Implements standard public-domain
 * speech-DSP (pre-emphasis, Hann window, radix-2 FFT, mel filterbank, DCT-II MFCC,
 * deltas). Only requirement is internal consistency: enrollment and detection use
 * this same frontend, so the feature space matches without any external spec.
 */

package com.arix.wake

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min

/**
 * MFCC 特征前端：PCM16 → 每帧 [numMfcc]×3(MFCC+Δ+ΔΔ) 展平序列。见 DESIGN-WAKE.md §5/§6。
 * 供 clean-room 录入([WakeEnrollment]) 与嵌入原型判决([EmbeddingPrototypeDetector]) 共用。
 */
internal object MfccFrontend {

    data class Config(
        val sampleRate: Int = 16000,
        val frameLen: Int = 400,   // 25ms
        val hop: Int = 160,        // 10ms
        val fftSize: Int = 512,
        val melBins: Int = 40,
        val numMfcc: Int = 13,
        val maxFrames: Int = 64,
        val fMin: Float = 20f,
        val fMax: Float = 8000f,
        val preEmphasis: Float = 0.97f,
    )

    private val cfg = Config()
    val featureDim: Int = cfg.numMfcc * 3

    /** 输出展平特征序列，长度 = frames × [featureDim]；无有效帧返回空数组。 */
    fun extract(pcm: ShortArray, length: Int): FloatArray {
        val n = min(length, pcm.size)
        if (n < cfg.frameLen) return FloatArray(0)

        // 归一化 + 预加重
        val sig = FloatArray(n)
        var prev = 0f
        for (i in 0 until n) {
            val x = pcm[i] / 32768f
            sig[i] = x - cfg.preEmphasis * prev
            prev = pcm[i] / 32768f
        }

        val mel = melFilterbank()
        val window = hann(cfg.frameLen)
        val half = cfg.fftSize / 2

        val frames = ArrayList<FloatArray>() // 每帧 numMfcc 维 MFCC
        var start = 0
        while (start + cfg.frameLen <= n && frames.size < cfg.maxFrames) {
            val re = FloatArray(cfg.fftSize)
            val im = FloatArray(cfg.fftSize)
            for (i in 0 until cfg.frameLen) re[i] = sig[start + i] * window[i]
            fft(re, im)

            val power = FloatArray(half + 1)
            for (k in 0..half) power[k] = re[k] * re[k] + im[k] * im[k]

            val logMel = FloatArray(cfg.melBins)
            for (m in 0 until cfg.melBins) {
                var e = 0f
                val w = mel[m]
                val upto = min(w.size, power.size)
                for (k in 0 until upto) e += w[k] * power[k]
                logMel[m] = ln(e + 1e-10f)
            }
            frames.add(dct(logMel, cfg.numMfcc))
            start += cfg.hop
        }
        if (frames.isEmpty()) return FloatArray(0)

        val mfcc = frames.toTypedArray()
        // CMVN(倒谱均值归一化)：逐系数减去整段均值 → 抹掉说话人/信道的平均频谱包络，只留相对轨迹(=内容)，
        // 削弱「认声音」、突出「认内容」，降同人不同词误触发。deltas 是差分本就与均值无关，仅作用于静态块。
        val nf = mfcc.size
        if (nf > 0) {
            val mean = FloatArray(cfg.numMfcc)
            for (t in 0 until nf) for (i in 0 until cfg.numMfcc) mean[i] += mfcc[t][i]
            for (i in 0 until cfg.numMfcc) mean[i] /= nf
            for (t in 0 until nf) for (i in 0 until cfg.numMfcc) mfcc[t][i] -= mean[i]
        }
        val d1 = delta(mfcc)
        val d2 = delta(d1)

        val out = FloatArray(frames.size * featureDim)
        var idx = 0
        for (t in frames.indices) {
            for (i in 0 until cfg.numMfcc) out[idx++] = mfcc[t][i]
            for (i in 0 until cfg.numMfcc) out[idx++] = d1[t][i]
            for (i in 0 until cfg.numMfcc) out[idx++] = d2[t][i]
        }
        return out
    }

    private fun hann(len: Int): FloatArray =
        FloatArray(len) { i -> 0.5f - 0.5f * cos(2.0 * Math.PI * i / (len - 1)).toFloat() }

    private fun hzToMel(hz: Float): Float = 2595f * log10(1f + hz / 700f)
    private fun melToHz(mel: Float): Float = 700f * (Math.pow(10.0, (mel / 2595f).toDouble()).toFloat() - 1f)

    /** 三角 mel 滤波器组权重：melBins × (fftSize/2+1)。 */
    private fun melFilterbank(): Array<FloatArray> {
        val half = cfg.fftSize / 2
        val melLo = hzToMel(cfg.fMin)
        val melHi = hzToMel(cfg.fMax)
        val points = FloatArray(cfg.melBins + 2) { melToHz(melLo + (melHi - melLo) * it / (cfg.melBins + 1)) }
        val bin = IntArray(points.size) { ((cfg.fftSize + 1) * points[it] / cfg.sampleRate).toInt().coerceIn(0, half) }
        return Array(cfg.melBins) { m ->
            val w = FloatArray(half + 1)
            val lo = bin[m]; val ctr = bin[m + 1]; val hi = bin[m + 2]
            for (k in lo until ctr) if (ctr > lo) w[k] = (k - lo).toFloat() / (ctr - lo)
            for (k in ctr until hi) if (hi > ctr) w[k] = (hi - k).toFloat() / (hi - ctr)
            w
        }
    }

    /** DCT-II 取前 numMfcc 个系数。 */
    private fun dct(logMel: FloatArray, numMfcc: Int): FloatArray {
        val m = logMel.size
        val out = FloatArray(numMfcc)
        for (k in 0 until numMfcc) {
            var s = 0f
            for (i in 0 until m) s += logMel[i] * cos(Math.PI * k * (i + 0.5) / m).toFloat()
            out[k] = s
        }
        return out
    }

    /** 一阶差分（centered，边缘复制）。 */
    private fun delta(x: Array<FloatArray>): Array<FloatArray> {
        val t = x.size
        if (t == 0) return x
        val d = x[0].size
        return Array(t) { i ->
            val prev = x[if (i > 0) i - 1 else 0]
            val next = x[if (i < t - 1) i + 1 else t - 1]
            FloatArray(d) { j -> (next[j] - prev[j]) / 2f }
        }
    }

    /** 就地 radix-2 Cooley-Tukey FFT（fftSize 必须是 2 的幂）。 */
    private fun fft(re: FloatArray, im: FloatArray) {
        val nn = re.size
        var j = 0
        for (i in 1 until nn) {
            var bit = nn shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= nn) {
            val ang = -2.0 * Math.PI / len
            val wr = cos(ang).toFloat()
            val wi = kotlin.math.sin(ang).toFloat()
            var i = 0
            while (i < nn) {
                var curR = 1f; var curI = 0f
                val h = len / 2
                for (k in 0 until h) {
                    val br = re[i + k + h] * curR - im[i + k + h] * curI
                    val bi = re[i + k + h] * curI + im[i + k + h] * curR
                    val ar = re[i + k]; val ai = im[i + k]
                    re[i + k] = ar + br; im[i + k] = ai + bi
                    re[i + k + h] = ar - br; im[i + k + h] = ai - bi
                    val ncr = curR * wr - curI * wi
                    curI = curR * wi + curI * wr
                    curR = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
