package com.example.ktapplication.vehicle.data

import com.example.ktapplication.vehicle.data.json.JArray
import com.example.ktapplication.vehicle.data.json.JNumber
import com.example.ktapplication.vehicle.data.json.JObject
import com.example.ktapplication.vehicle.data.json.JString
import com.example.ktapplication.vehicle.data.json.JsonParseException
import com.example.ktapplication.vehicle.data.json.MiniJson
import com.example.ktapplication.vehicle.data.json.jNum
import com.example.ktapplication.vehicle.data.json.jObj
import com.example.ktapplication.vehicle.data.json.jStr
import com.example.ktapplication.vehicle.data.json.optArray
import com.example.ktapplication.vehicle.data.json.optBoolean
import com.example.ktapplication.vehicle.data.json.optDouble
import com.example.ktapplication.vehicle.data.json.optInt
import com.example.ktapplication.vehicle.data.json.optObject
import com.example.ktapplication.vehicle.data.json.optString
import com.example.ktapplication.vehicle.data.json.toJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MiniJsonTest {

    @Test
    fun nestedObjectAndArray() {
        val text = """
            {"a":{"b":[1,"two",true,null,{"c":"deep"}]},"arr":[]}
        """.trimIndent()
        val root = MiniJson.parseObject(text)
        val a = root.optObject("a") ?: error("缺 a")
        val b = a.optArray("b") ?: error("缺 b")
        assertEquals(5, b.size)
        assertEquals(1.0, (b[0] as JNumber).value, 1e-9)
        assertEquals("two", (b[1] as JString).value)
        val inner = (b[4] as JObject).optString("c")
        assertEquals("deep", inner)
        assertEquals(0, root.optArray("arr")?.size)
    }

    @Test
    fun escapesAndUnicode() {
        val root = MiniJson.parseObject("""{"s":"line\nbreak\ttab \"quoted\" back\\slash é中"}""")
        // é = é，中 = 中：TSP 告警文案里的车名/地名可能就是非 ASCII。
        assertEquals("line\nbreak\ttab \"quoted\" back\\slash é中", root.optString("s"))

        // JSON 源文本含 \u 转义（Kotlin 里写成 \\u），走解析器的 unicode 分支。
        val unicode = MiniJson.parseObject("{\"s\":\"\\u00e9\\u4e2d\"}")
        assertEquals("é中", unicode.optString("s"))
    }

    @Test
    fun numbersKeepRawTextForLongIntegers() {
        val root = MiniJson.parseObject("""{"big":1700000000000,"pi":3.14,"neg":-42,"sci":1.5e3}""")
        // 直接取 JNumber 验证 rawText 保住精确整数（服务端消息号可达 19 位 long）。
        val big = root.get("big") as JNumber
        assertEquals("1700000000000", big.rawText)
        assertEquals(1_700_000_000_000L, big.asLongOrNull())
        assertEquals(3.14, root.optDouble("pi") ?: error("缺 pi"), 1e-9)
        assertEquals(-42, root.optInt("neg"))
        assertEquals(1500.0, root.optDouble("sci") ?: error("缺 sci"), 1e-9)
    }

    @Test
    fun malformedInputThrowsWithPosition() {
        val badCases = listOf(
            "{",                // 未闭合对象
            "{\"a\" 1}",        // 缺冒号
            "[1,2,]",           // 尾逗号（JSON5 特性，明确不支持）
            "{'a':1}",          // 单引号 key
            "{\"a\":1} extra",  // 顶层多余内容
            "{\"a\":1x}",       // 数字后非法字符
            "\"bare",           // 字符串未闭合
            "{\"a\":tru}",      // 字面量拼错
        )
        for (bad in badCases) {
            try {
                MiniJson.parse(bad)
                fail("应抛 JsonParseException: $bad")
            } catch (e: JsonParseException) {
                // detail 必须带位置信息，线上诊断报文才有抓手。
                assertTrue("detail 应含位置: ${e.detail}", e.detail.contains("位置"))
            }
        }
    }

    @Test
    fun missingOrWrongTypedFieldsYieldNullNotCrash() {
        val root = MiniJson.parseObject("""{"num":1,"str":"s"}""")
        assertNull(root.optString("absent"))
        assertNull(root.optObject("num"))
        assertNull(root.optArray("str"))
        assertNull(root.optBoolean("absent"))
        // 宽容转换：数值字段回成字符串也要能取到（网关常见风格）。
        val lenient = MiniJson.parseObject("""{"soc":"72.5","flag":"1"}""")
        assertEquals(72.5, lenient.optDouble("soc") ?: error("缺 soc"), 1e-9)
        assertEquals(true, lenient.optBoolean("flag"))
    }

    @Test
    fun serializationRoundTrip() {
        val obj = jObj(
            "k" to jStr("v\"1"),
            "n" to jNum(12),
        )
        val reparsed = MiniJson.parseObject(obj.toJson())
        assertEquals("v\"1", reparsed.optString("k"))
        assertEquals(12, reparsed.optInt("n"))
    }

    @Test
    fun topLevelArrayParses() {
        val arr = MiniJson.parse("""[1,"a",false]""") as JArray
        assertEquals(3, arr.size)
        assertEquals(false, (arr[2] as com.example.ktapplication.vehicle.data.json.JBool).value)
    }
}
