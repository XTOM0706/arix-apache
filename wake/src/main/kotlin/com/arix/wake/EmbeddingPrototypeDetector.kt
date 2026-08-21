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

/**
 * 自定义唤醒词判决器（段式）：把语音段用 clean-room [MfccFrontend] 取特征，与用户录入的
 * 特征原型([WakeEnrollment]) 做 DTW 余弦相似度比对，过阈值即命中。见 DESIGN-WAKE.md §5。
 *
 * 说话人相关。当前"嵌入"= MFCC 特征序列原型；后续可在同一接口下换成神经语音嵌入而不动上层。
 * 同时兼作 KWS 主路径在模型缺失时的 clean-room 回退（用户若录过自定义词即可用）。
 */
internal class EmbeddingPrototypeDetector(
    context: Context,
    private val simThreshold: Float,
) : WakeDetector {

    private companion object {
        private const val TAG = "EmbeddingPrototype"
        // 长度门：段/原型 帧数比越界（多为整句说话/杂音，与唤醒词时长差太远）直接否——杀误触发大头。
        private const val LENGTH_MIN = 0.65f
        private const val LENGTH_MAX = 1.5f
    }

    override val id: String = "prototype"

    private val enrollment = WakeEnrollment(context)
    private val featureDim = MfccFrontend.featureDim

    // 原生 short 缓冲，避免 ArrayList<Short> 每样本装箱（语音段可达 25k+ 对象，A55 上 GC 可见）。
    private var segment = ShortArray(16000)
    private var segmentLen = 0

    // 所有启用中的模板原型（名字只为日志好读）。多条 = 同一唤醒词的不同环境/语气各录一条，或干脆多个唤醒词。
    private var prototypes: List<Pair<String, Array<FloatArray>>>? = null
    private var loadedVersion = -1

    override fun reset() {
        segmentLen = 0
    }

    override fun accept(pcm: ShortArray, length: Int): WakeDetection? {
        val n = minOf(length, pcm.size)
        if (n <= 0) return null
        if (segmentLen + n > segment.size) {
            segment = segment.copyOf(maxOf(segment.size * 2, segmentLen + n))
        }
        System.arraycopy(pcm, 0, segment, segmentLen, n)
        segmentLen += n
        return null // 段式：不在流式阶段出分
    }

    override fun onSegmentEnd(): WakeDetection? {
        val protos = ensurePrototypes()
        if (protos.isEmpty()) { WakeLog.d("判决: 没有启用中的唤醒词模板，无法比对"); return null }
        if (segmentLen <= 0) return null

        val pcm = if (segmentLen == segment.size) segment else segment.copyOf(segmentLen)
        val feat = MfccFrontend.extract(pcm, pcm.size)
        if (feat.isEmpty() || feat.size % featureDim != 0) { WakeLog.d("判决: 特征无效"); return null }

        val featSeq = DtwMatcher.reshapeNormalize(feat, featureDim)
        if (featSeq.isEmpty()) return null

        // 逐条模板比，取最高分：命中任意一条即算唤醒。长度门按**每条**自己的帧数算——
        // 不同模板长短本就不同（两字的短唤醒词 vs 一整句），拿一个门套所有模板会误杀。
        var bestSim = 0f
        var bestName: String? = null
        for ((name, proto) in protos) {
            val ratio = featSeq.size.toFloat() / proto.size.toFloat()
            if (ratio < LENGTH_MIN || ratio > LENGTH_MAX) {
                WakeLog.d("判决[%s]: 段长比 %.2f 越界[%.2f,%.2f]（帧 %d/%d）→ 跳过".format(name, ratio, LENGTH_MIN, LENGTH_MAX, featSeq.size, proto.size))
                continue
            }
            val sim = DtwMatcher.similarity(featSeq, proto)
            WakeLog.d("判决[%s]: 相似度 %.3f".format(name, sim))
            if (sim > bestSim) { bestSim = sim; bestName = name }
        }

        if (bestName == null) { WakeLog.d("判决: 所有模板都被长度门挡下（多为整句/杂音）→ 否"); return null }
        Log.d(TAG, "best=$bestName sim=$bestSim threshold=$simThreshold frames=${featSeq.size} of ${protos.size} templates")
        val hit = bestSim >= simThreshold
        WakeLog.d("判决: 最佳[%s] %.3f vs 阈值 %.2f → %s".format(bestName, bestSim, simThreshold, if (hit) "命中✓" else "未命中"))
        return if (hit) WakeDetection(detectorId = id, score = bestSim) else null
    }

    override fun close() {
        segmentLen = 0
    }

    /** 版本号变了（用户增删/改名/启停了模板）就重新加载——设置页改完当场生效，不用重启唤醒服务。 */
    private fun ensurePrototypes(): List<Pair<String, Array<FloatArray>>> {
        val v = WakeTemplateStore.version
        if (loadedVersion != v) {
            prototypes = enrollment.templates.enabled().mapNotNull { t ->
                val flat = enrollment.templates.loadProto(t.id)
                if (flat != null && flat.isNotEmpty() && flat.size % featureDim == 0)
                    t.name to DtwMatcher.reshapeNormalize(flat, featureDim)
                else null   // 单条坏了跳过，别拖累其它模板
            }
            loadedVersion = v
            WakeLog.d("加载唤醒词模板: ${prototypes?.size ?: 0} 条启用")
        }
        return prototypes.orEmpty()
    }
}
