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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 一条唤醒词模板的元信息。特征原型本体另存二进制，见 [WakeTemplateStore]。 */
data class WakeTemplate(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val createdAt: Long,
)

/**
 * 多唤醒词模板仓库：索引 `wake_templates.json` + 每条模板一个 `wake_proto_<id>.bin`。
 *
 * 之所以要多条：一个人在不同环境/语气下声音差别不小，同一个唤醒词录几条能显著提高命中率；
 * 也允许录多个不同的唤醒词。判决器拿所有 **已启用** 的原型逐个比，取最高分（见 [EmbeddingPrototypeDetector]）。
 *
 * 旧版只存单个 `wake_prototype.bin`，[migrateLegacy] 会把它收编成一条模板，老用户不用重录。
 *
 * 线程安全：写操作 @Synchronized，且读-改-写全在锁内（并发录入/删除时别互相覆盖索引）。
 */
class WakeTemplateStore(context: Context) {

    companion object {
        private const val TAG = "WakeTemplateStore"
        private const val INDEX_FILE = "wake_templates.json"
        private const val PROTO_PREFIX = "wake_proto_"
        private const val LEGACY_FILE = "wake_prototype.bin"

        /**
         * 模板增删改/启停的版本号。判决器缓存了原型，靠比对这个数发现变更并重载——
         * 免得从设置页把引用一层层传到 WakeService 里那个埋在引擎深处的判决器实例。
         * UI 与 WakeService 同进程，静态计数够用。
         */
        private val versionRef = java.util.concurrent.atomic.AtomicInteger(0)
        val version: Int get() = versionRef.get()
    }

    private fun bump() = versionRef.incrementAndGet()

    private val appContext = context.applicationContext
    private fun indexFile() = File(appContext.filesDir, INDEX_FILE)
    private fun protoFile(id: String) = File(appContext.filesDir, "$PROTO_PREFIX$id.bin")

    /** 全部模板，按录入时间正序。 */
    @Synchronized
    fun list(): List<WakeTemplate> = readIndex()

    /** 已启用且原型文件确实还在的模板。 */
    @Synchronized
    fun enabled(): List<WakeTemplate> = readIndex().filter { it.enabled && protoFile(it.id).exists() }

    @Synchronized
    fun hasAnyEnabled(): Boolean = enabled().isNotEmpty()

    /** 新增一条模板，返回其 id。[name] 空则自动起名「唤醒词 N」。 */
    @Synchronized
    fun add(proto: FloatArray, name: String): String? {
        val id = System.currentTimeMillis().toString(36) + (0..999).random().toString(36)
        if (!writeProto(id, proto)) return null
        val cur = readIndex().toMutableList()
        val finalName = name.trim().ifBlank { "唤醒词 ${cur.size + 1}" }
        cur.add(WakeTemplate(id = id, name = finalName, enabled = true, createdAt = System.currentTimeMillis()))
        writeIndex(cur)
        bump()
        return id
    }

    @Synchronized
    fun rename(id: String, name: String) {
        val n = name.trim().ifBlank { return }
        writeIndex(readIndex().map { if (it.id == id) it.copy(name = n) else it })
        bump()
    }

    @Synchronized
    fun setEnabled(id: String, on: Boolean) {
        writeIndex(readIndex().map { if (it.id == id) it.copy(enabled = on) else it })
        bump()
    }

    @Synchronized
    fun delete(id: String) {
        try { protoFile(id).delete() } catch (_: Exception) {}
        writeIndex(readIndex().filter { it.id != id })
        bump()
    }

    @Synchronized
    fun clearAll() {
        readIndex().forEach { try { protoFile(it.id).delete() } catch (_: Exception) {} }
        try { indexFile().delete() } catch (_: Exception) {}
        bump()
    }

    /** 读某条模板的特征原型（扁平 float 数组，供 DTW 比对）。 */
    @Synchronized
    fun loadProto(id: String): FloatArray? = readProto(protoFile(id))

    /**
     * 旧版单原型 → 收编成一条模板。已有索引则不动。返回是否真的迁移了。
     * 迁移后保留旧文件不删：万一用户回退到旧版本还能用，且它只有几 KB。
     */
    @Synchronized
    fun migrateLegacy(): Boolean {
        if (indexFile().exists()) return false
        val legacy = File(appContext.filesDir, LEGACY_FILE)
        if (!legacy.exists()) return false
        val proto = readProto(legacy) ?: return false
        val id = "legacy"
        if (!writeProto(id, proto)) return false
        writeIndex(listOf(WakeTemplate(id = id, name = "唤醒词 1", enabled = true, createdAt = legacy.lastModified())))
        bump()
        Log.d(TAG, "migrated legacy prototype -> template $id")
        return true
    }

    private fun readProto(f: File): FloatArray? {
        if (!f.exists()) return null
        return try {
            val bytes = f.readBytes()
            if (bytes.isEmpty() || bytes.size % 4 != 0) return null
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asFloatBuffer()
            FloatArray(bytes.size / 4).also { fb.get(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeProto(id: String, proto: FloatArray): Boolean = try {
        val buf = ByteBuffer.allocate(proto.size * 4).order(ByteOrder.nativeOrder())
        buf.asFloatBuffer().put(proto)
        protoFile(id).writeBytes(buf.array())
        true
    } catch (e: Exception) {
        Log.e(TAG, "write proto failed: ${e.message}")
        false
    }

    private fun readIndex(): List<WakeTemplate> {
        val f = indexFile()
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            // 逐条容错：单条坏了不该把整张表抹掉（同 DiaryStore 的做法）
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val o = arr.getJSONObject(i)
                    val id = o.getString("id")
                    if (id.isBlank()) null
                    else WakeTemplate(
                        id = id,
                        name = o.optString("name", "唤醒词"),
                        enabled = o.optBoolean("enabled", true),
                        createdAt = o.optLong("createdAt", 0L),
                    )
                } catch (_: Exception) { null }
            }.sortedBy { it.createdAt }
        } catch (e: Exception) {
            Log.e(TAG, "read index failed: ${e.message}")
            emptyList()
        }
    }

    private fun writeIndex(list: List<WakeTemplate>) {
        try {
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id); put("name", it.name)
                    put("enabled", it.enabled); put("createdAt", it.createdAt)
                })
            }
            indexFile().writeText(arr.toString())
        } catch (e: Exception) {
            Log.e(TAG, "write index failed: ${e.message}")
        }
    }
}
