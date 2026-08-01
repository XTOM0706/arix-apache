package com.arix.tool

import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * [ToolArgValidator.validate] —— 踩过的坑：模型幻觉出的坏参数被 `optInt`/`optString`
 * 静默兜成默认值（tap 到 (0,0)、direction 乱走），做成了**错的操作**，比报错还糟。
 *
 * 这里同时钉住它的两面：
 *  - 高置信违规要挡（缺必填 / 枚举外 / 把对象数组塞进标量位 / 越界）；
 *  - **字符串化的标量必须放行**——内联工具调用故意把数值保留成字符串（"0123" 防丢前导零），
 *    很多模型也把标量序列化成字符串，严格判会大面积误杀。这条千万别测反。
 *
 * ⚠ 依赖真实 org.json 实现，说明见 [com.arix.app.MemEdgesTest]。
 */
class ToolArgValidatorTest {

    @Before
    fun requireRealJson() {
        val usable = try {
            JSONObject("""{"a":1}""").getInt("a") == 1
        } catch (t: Throwable) {
            false
        }
        Assume.assumeTrue(
            "需要真实 org.json 实现：请在 logic/build.gradle.kts 加 " +
                "testImplementation(\"com.vaadin.external.google:android-json:0.0.20131108.vaadin1\")",
            usable,
        )
    }

    private fun j(s: String) = JSONObject(s)

    /**
     * 真实 tap 工具的形状：x/y 必填整数。
     * 写成 getter 而不是字段：字段初始化发生在 @Before 之前，org.json 不可用时会绕过上面的 Assume 直接报错。
     */
    private val tapSchema: JSONObject
        get() = j("""{"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"}},"required":["x","y"]}""")

    // ---------------- 该挡的 ----------------

    @Test
    fun 缺必填字段被挡() {
        val why = ToolArgValidator.validate(tapSchema, j("""{"y":100}"""))
        assertNotNull("缺 x 必须挡下来，否则会 tap 到 x=0", why)
        assertTrue(why!!.contains("缺少必填字段"))
        assertTrue("要说清缺的是哪个字段：$why", why.contains("x"))
    }

    @Test
    fun 必填字段给null等于没给() {
        assertNotNull(ToolArgValidator.validate(tapSchema, j("""{"x":null,"y":100}""")))
    }

    @Test
    fun 必填字段给空串等于没给() {
        assertNotNull(ToolArgValidator.validate(tapSchema, j("""{"x":"","y":100}""")))
    }

    @Test
    fun 枚举外的取值被挡() {
        val schema = j("""{"type":"object","properties":{"direction":{"type":"string","enum":["up","down"]}}}""")
        val why = ToolArgValidator.validate(schema, j("""{"direction":"left"}"""))
        assertNotNull("枚举外必须挡，否则方向会乱走", why)
        assertTrue(why!!.contains("取值不在允许集合中"))
        assertTrue("要把允许集合告诉模型：$why", why.contains("up") && why.contains("down"))
    }

    @Test
    fun 对象位收到标量被挡() {
        val schema = j("""{"type":"object","properties":{"cfg":{"type":"object"}}}""")
        val why = ToolArgValidator.validate(schema, j("""{"cfg":5}"""))
        assertNotNull(why)
        assertTrue(why!!.contains("应为对象"))
    }

    @Test
    fun 数组位收到标量被挡() {
        val schema = j("""{"type":"object","properties":{"items":{"type":"array"}}}""")
        val why = ToolArgValidator.validate(schema, j("""{"items":7}"""))
        assertNotNull(why)
        assertTrue(why!!.contains("应为数组"))
    }

    @Test
    fun 标量位收到对象或数组被挡() {
        val schema = j("""{"type":"object","properties":{"x":{"type":"integer"}}}""")
        assertNotNull(ToolArgValidator.validate(schema, j("""{"x":{"a":1}}""")))
        assertNotNull(ToolArgValidator.validate(schema, j("""{"x":[1,2]}""")))
    }

    @Test
    fun 数值越界被挡() {
        val schema = j("""{"type":"object","properties":{"n":{"type":"integer","minimum":0,"maximum":100}}}""")
        assertNull(ToolArgValidator.validate(schema, j("""{"n":50}""")))
        assertTrue(ToolArgValidator.validate(schema, j("""{"n":200}"""))!!.contains("不能大于"))
        assertTrue(ToolArgValidator.validate(schema, j("""{"n":-1}"""))!!.contains("不能小于"))
    }

    @Test
    fun 字符串形数字也要判上下界() {
        val schema = j("""{"type":"object","properties":{"n":{"type":"integer","minimum":0,"maximum":100}}}""")
        assertNotNull("\"200\" 能解析成数字，就该按数字判", ToolArgValidator.validate(schema, j("""{"n":"200"}""")))
        assertNull(ToolArgValidator.validate(schema, j("""{"n":"50"}""")))
    }

    @Test
    fun 字符串长度越界被挡() {
        val schema = j("""{"type":"object","properties":{"s":{"type":"string","minLength":2,"maxLength":4}}}""")
        assertTrue(ToolArgValidator.validate(schema, j("""{"s":"abcde"}"""))!!.contains("长度不能超过"))
        assertTrue(ToolArgValidator.validate(schema, j("""{"s":"a"}"""))!!.contains("长度不能少于"))
        assertNull(ToolArgValidator.validate(schema, j("""{"s":"abc"}""")))
    }

    @Test
    fun 数组项数越界被挡() {
        val schema = j("""{"type":"object","properties":{"ids":{"type":"array","minItems":2,"maxItems":3}}}""")
        assertTrue(ToolArgValidator.validate(schema, j("""{"ids":[1]}"""))!!.contains("至少要 2 项"))
        assertTrue(ToolArgValidator.validate(schema, j("""{"ids":[1,2,3,4]}"""))!!.contains("最多 3 项"))
        assertNull(ToolArgValidator.validate(schema, j("""{"ids":[1,2]}""")))
    }

    @Test
    fun 嵌套字段的错误要报出完整路径() {
        val schema = j(
            """{"type":"object","properties":{"opts":{"type":"object","properties":{"mode":{"type":"string","enum":["a","b"]}}}}}"""
        )
        val why = ToolArgValidator.validate(schema, j("""{"opts":{"mode":"z"}}"""))
        assertNotNull(why)
        assertTrue("路径要能定位到具体字段：$why", why!!.contains("参数.opts.mode"))
    }

    @Test
    fun 数组元素的错误也能报出来() {
        val schema = j(
            """{"type":"object","properties":{"list":{"type":"array","items":{"type":"string","enum":["a","b"]}}}}"""
        )
        val why = ToolArgValidator.validate(schema, j("""{"list":["a","zzz"]}"""))
        assertNotNull(why)
        assertTrue("$why", why!!.contains("参数.list[1]"))
    }

    // ---------------- 该放行的（刻意宽容，别改严） ----------------

    @Test
    fun 字符串化的整数要放行() {
        // 内联工具调用故意把数值保留成字符串（"0123" 防丢前导零），照严格 schema 判会大面积误杀
        assertNull(ToolArgValidator.validate(tapSchema, j("""{"x":"120","y":"340"}""")))
    }

    @Test
    fun 字符串化的布尔与浮点要放行() {
        val schema = j("""{"type":"object","properties":{"on":{"type":"boolean"},"r":{"type":"number"}}}""")
        assertNull(ToolArgValidator.validate(schema, j("""{"on":"true","r":"1.5"}""")))
    }

    @Test
    fun 字符串化的对象与数组要放行() {
        val schema = j("""{"type":"object","properties":{"cfg":{"type":"object"},"ids":{"type":"array"}}}""")
        assertNull(
            "object/array 位收到序列化后的字符串要放行，下游工具会自行解析",
            ToolArgValidator.validate(schema, j("""{"cfg":"{\"a\":1}","ids":"[1,2]"}""")),
        )
    }

    @Test
    fun 数值枚举收到字符串化取值要放行() {
        val schema = j("""{"type":"object","properties":{"n":{"type":"integer","enum":[1,2,3]}}}""")
        assertNull(ToolArgValidator.validate(schema, j("""{"n":"2"}""")))
        assertNull(ToolArgValidator.validate(schema, j("""{"n":2}""")))
        assertNotNull(ToolArgValidator.validate(schema, j("""{"n":"9"}""")))
    }

    @Test
    fun 无法解析成数字的字符串不判上下界() {
        val schema = j("""{"type":"object","properties":{"n":{"type":"integer","minimum":0,"maximum":100}}}""")
        assertNull("判不定就放行，别拿一次误判去跟 AI 说你参数错了", ToolArgValidator.validate(schema, j("""{"n":"auto"}""")))
    }

    @Test
    fun schema缺失一律放行() {
        assertNull(ToolArgValidator.validate(null, j("""{"随便":"什么"}""")))
    }

    @Test
    fun 未在properties里声明的额外字段放行() {
        assertNull(ToolArgValidator.validate(tapSchema, j("""{"x":1,"y":2,"没声明过的":"值"}""")))
    }

    @Test
    fun 合法参数返回null() {
        assertNull(ToolArgValidator.validate(tapSchema, j("""{"x":100,"y":200}""")))
    }

    @Test
    fun 空schema对象放行任何参数() {
        assertNull(ToolArgValidator.validate(j("{}"), j("""{"a":1,"b":[1,2]}""")))
    }
}
