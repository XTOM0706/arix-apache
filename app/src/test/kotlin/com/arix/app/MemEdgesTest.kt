package com.arix.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * [MemEdges] 的 parse/serialize 往返，以及**老数据不炸**（relatedMeta 这一列是后加的，
 * 老记忆里它是空串；导入/备份还原也可能塞进脏 JSON）。缺项一律回退 related/1.0。
 *
 * ⚠ 本文件依赖**真实的 org.json 实现**。JVM 单测跑的是 AGP 生成的 mockable android.jar，
 * 里面 org.json.* 的方法体一律 `throw RuntimeException("Method ... not mocked.")`。
 * 想让这些用例真正跑起来，需要在 app/build.gradle.kts 加一条**只在测试期生效**的依赖：
 *     testImplementation("com.vaadin.external.google:android-json:0.0.20131108.vaadin1")
 * （这就是 AOSP 那份 JSON 实现，Apache-2.0，避开 org.json:json 那条 "Good, not Evil" 的
 *   JSON License —— 本项目 cloudapi/wake 的注释里已经明确拒绝过后者。）
 * 没有这条依赖时，下面的用例会被 Assume 跳过而不是给出误导性的红/绿。
 */
class MemEdgesTest {

    @Before
    fun requireRealJson() {
        val usable = try {
            JSONObject("""{"a":1}""").getInt("a") == 1
        } catch (t: Throwable) {
            false
        }
        Assume.assumeTrue(
            "需要真实 org.json 实现：请在 app/build.gradle.kts 加 " +
                "testImplementation(\"com.vaadin.external.google:android-json:0.0.20131108.vaadin1\")",
            usable,
        )
    }

    @Test
    fun 空map序列化成空串() {
        assertEquals("", MemEdges.serialize(emptyMap()))
    }

    @Test
    fun 序列化再解析应还原原样() {
        val src = mapOf(
            1L to MemEdge("causes", 0.5f),
            2L to MemEdge("part_of", 1.0f),
            30L to MemEdge("related", 0.25f),
        )
        val back = MemEdges.parse(MemEdges.serialize(src))
        assertEquals(src, back.toMap())
    }

    @Test
    fun 空串是老数据不应该炸() {
        assertTrue(MemEdges.parse("").isEmpty())
        assertTrue(MemEdges.parse("   ").isEmpty())
    }

    @Test
    fun 脏JSON不应该炸() {
        assertTrue(MemEdges.parse("这不是 JSON").isEmpty())
        assertTrue(MemEdges.parse("{ 半截").isEmpty())
        assertTrue(MemEdges.parse("[1,2,3]").isEmpty())
    }

    @Test
    fun 缺字段的边回退成related与1点0() {
        val out = MemEdges.parse("""{"7":{}}""")
        assertEquals(MemEdge("related", 1.0f), out[7L])
    }

    @Test
    fun 空白type也回退成related() {
        val out = MemEdges.parse("""{"7":{"t":"   ","w":0.5}}""")
        assertEquals(MemEdge("related", 0.5f), out[7L])
    }

    @Test
    fun 权重为0不应被当成缺失而回退() {
        val out = MemEdges.parse("""{"7":{"t":"causes","w":0}}""")
        assertEquals(MemEdge("causes", 0.0f), out[7L])
    }

    @Test
    fun 非数字key被跳过() {
        val out = MemEdges.parse("""{"abc":{"t":"causes"},"9":{"t":"part_of"}}""")
        assertEquals(setOf(9L), out.keys)
    }

    @Test
    fun 值不是对象的项被跳过() {
        val out = MemEdges.parse("""{"7":"oops","9":{"t":"causes","w":1.0}}""")
        assertEquals(setOf(9L), out.keys)
        assertEquals(MemEdge("causes", 1.0f), out[9L])
    }

    @Test
    fun 负数id也能往返() {
        val src = mapOf(-3L to MemEdge("blocks", 0.75f))
        assertEquals(src, MemEdges.parse(MemEdges.serialize(src)).toMap())
    }
}
