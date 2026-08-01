/*
 * Copyright 2025-2026 Arix.
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Clean-room original implementation for the Arix wake module.
 * Not derived from any GPL/LGPL-licensed source. Standard banded dynamic time
 * warping with cosine distance (textbook algorithm).
 */

package com.arix.wake

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 带状 DTW + 余弦距离相似度。见 DESIGN-WAKE.md §5。 */
internal object DtwMatcher {

    const val DEFAULT_BAND = 4

    /** 展平特征序列 → 每帧 L2 归一化的帧序列。 */
    fun reshapeNormalize(flat: FloatArray, dim: Int): Array<FloatArray> {
        if (dim <= 0) return emptyArray()
        val frames = flat.size / dim
        if (frames == 0) return emptyArray()
        return Array(frames) { t ->
            val row = FloatArray(dim) { i -> flat[t * dim + i] }
            var norm = 0f
            for (v in row) norm += v * v
            norm = sqrt(max(1e-10f, norm))
            for (i in row.indices) row[i] /= norm
            row
        }
    }

    /** 两个归一化帧序列的 DTW 余弦相似度，归一到 [0,1]（越大越像）。 */
    fun similarity(a: Array<FloatArray>, b: Array<FloatArray>, band: Int = DEFAULT_BAND): Float {
        val n = a.size
        val m = b.size
        if (n == 0 || m == 0) return 0f
        val w = max(band, abs(n - m))

        var prev = FloatArray(m + 1) { Float.POSITIVE_INFINITY }
        var curr = FloatArray(m + 1) { Float.POSITIVE_INFINITY }
        prev[0] = 0f

        for (i in 1..n) {
            java.util.Arrays.fill(curr, Float.POSITIVE_INFINITY)
            val jStart = max(1, i - w)
            val jEnd = min(m, i + w)
            for (j in jStart..jEnd) {
                val cost = cosineDistance(a[i - 1], b[j - 1])
                val best = min(prev[j], min(curr[j - 1], prev[j - 1]))
                curr[j] = cost + best
            }
            val tmp = prev; prev = curr; curr = tmp
        }

        val avgCost = prev[m] / max(1f, (n + m).toFloat())
        return (1f - avgCost / 2f).coerceIn(0f, 1f)
    }

    private fun cosineDistance(a: FloatArray, b: FloatArray): Float {
        val n = min(a.size, b.size)
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in 0 until n) {
            dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]
        }
        val denom = sqrt(max(1e-10f, na)) * sqrt(max(1e-10f, nb))
        val cos = (dot / denom).coerceIn(-1f, 1f)
        return 1f - cos
    }
}
