package com.example.ktapplication.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空安全工具箱的边界用例。
 *
 * 每个断言挑的都是"看起来能跑通、实际上把坏数据当前值"的写法：
 * 只要有人把 `toDoubleOrNull()` / `toInt()` 换回去，这里就会红。
 */
class NullSafetyTest {

    @Test
    fun blankStringsAreTreatedAsMissing() {
        assertNull("".blankToNull())
        assertNull("   ".blankToNull())
        assertNull((null as String?).blankToNull())
        // "0" 是有意义的业务值（车速 0 km/h 表示静止），不能被"看起来像空"的规则吃掉。
        assertEquals("0", "0".blankToNull())
    }

    @Test
    fun nanAndInfinityAreNotValidNumbers() {
        // 这是 toDoubleOrNull 的陷阱：NaN / Infinity 都是**合法字面量**，它会解析成功。
        val parsedNaN: Double = requireNotNull("NaN".toDoubleOrNull())
        val parsedInfinity: Double = requireNotNull("Infinity".toDoubleOrNull())
        assertTrue(parsedNaN.isNaN())
        assertTrue(parsedInfinity.isInfinite())
        assertNull("NaN".toFiniteDoubleOrNull())
        assertNull("Infinity".toFiniteDoubleOrNull())
        assertNull("-Infinity".toFiniteDoubleOrNull())
        assertNull("nan".toFiniteFloatOrNull())
        assertEquals(72.5, " 72.5 ".toFiniteDoubleOrNull() ?: error("应解析成功"), 1e-9)
        assertEquals(1000.0, "1e3".toFiniteDoubleOrNull() ?: error("应解析成功"), 1e-9)
        assertNull("".toFiniteDoubleOrNull())
        assertNull((null as String?).toFiniteDoubleOrNull())
    }

    @Test
    fun finiteGuardsWorkOnAlreadyParsedNumbers() {
        // 反射、老 SDK 默认值、除零都可能给出非有限值。
        assertNull(Double.NaN.finiteOrNull())
        assertNull((1.0 / 0.0).finiteOrNull())
        assertEquals(0.0, (0.0 / 1.0).finiteOrNull() ?: error("0 是有限值"), 0.0)
        assertNull(Float.NaN.finiteOrNull())
    }

    @Test
    fun plainIntRejectsEverySneakyForm() {
        assertNull("12.0".toPlainIntOrNull())
        assertNull("0x1F".toPlainIntOrNull())
        assertNull("１２３".toPlainIntOrNull()) // 全角数字
        assertNull("1 2".toPlainIntOrNull())
        assertNull("".toPlainIntOrNull())
        assertNull("  ".toPlainIntOrNull())
        assertNull("abc".toPlainIntOrNull())
        assertEquals(7, "+7".toPlainIntOrNull())
        assertEquals(-7, "-7".toPlainIntOrNull())
        assertEquals(7, "007".toPlainIntOrNull())
        assertEquals(1_500_000_000, "1500000000".toPlainIntOrNull())
        // 越界一律 null：Kotlin 的 toInt() 会饱和成 Int.MAX_VALUE，那是"看起来合法的假数据"。
        assertNull("2147483648".toPlainIntOrNull())
        assertNull("-2147483649".toPlainIntOrNull())
        // 位数截断（11 位以上直接不匹配），防的是把长串当 int 用。
        assertNull("12345678901".toPlainIntOrNull())
        // 量程上限由调用方给：车速字段超过 999 就是协议异常。
        assertEquals(120, "120".toPlainIntOrNull(maxInclusive = 300))
        assertNull("301".toPlainIntOrNull(maxInclusive = 300))
    }

    @Test
    fun doubleToIntDoesNotSaturateSilently() {
        assertNull(Double.NaN.finiteIntOrNull())
        assertNull((1.0 / 0.0).finiteIntOrNull())
        // 裸 toInt() 的两种伪装：NaN→0、越界→±Int.MAX_VALUE。
        assertEquals(0, Double.NaN.toInt())
        assertEquals(Int.MAX_VALUE, 3.0e9.toInt())
        assertNull(3.0e9.finiteIntOrNull())
        assertEquals(22, (22.7).finiteIntOrNull())
        assertEquals(-3, (-3.2).finiteIntOrNull())
        // 截断方向是"朝零取整"，不是四舍五入：2147483646.5 → 2147483646。
        assertEquals(2_147_483_646, (Int.MAX_VALUE.toDouble() - 0.5).finiteIntOrNull())
        // Int 在 double 的 53 位有效数字范围内，所以 Int.MAX_VALUE 能精确表示、精确取回。
        assertEquals(2_147_483_647, (Int.MAX_VALUE.toDouble()).finiteIntOrNull())
        // 真正的越界必须给 null，而不是像裸 toInt() 那样饱和成 Int.MAX_VALUE。
        assertNull((2_147_483_648.0).finiteIntOrNull())
        assertNull((-2_147_483_649.0).finiteIntOrNull())
        assertNull((null as Double?).finiteIntOrNull())
    }

    @Test
    fun averageOfNoSamplesIsNullNotZero() {
        // 空样本的 0 会被诊断页读成"完美流畅"，这是撒谎，不是兜底。
        assertNull(emptyList<Double?>().finiteAverageOrNull())
        assertNull(listOf(null, null).finiteAverageOrNull())
        assertNull(listOf(Double.NaN, Double.POSITIVE_INFINITY).finiteAverageOrNull())
        assertEquals(2.0, listOf(1.0, 2.0, 3.0).finiteAverageOrNull() ?: error("应有均值"), 1e-9)
        // NaN 样本被剔除而不是污染整窗均值（否则一个坏帧就毁掉一屏统计）。
        assertEquals(2.0, listOf(1.0, Double.NaN, 3.0).finiteAverageOrNull() ?: error("应有均值"), 1e-9)
        assertEquals(1.0, listOf(1.0, null).finiteAverageOrNull() ?: error("应有均值"), 1e-9)
    }

    @Test
    fun nullValuedParametersAreDroppedBeforeSend() {
        val payload = mapOf<String, Any?>(
            "temperature" to 22,
            "fanSpeed" to null,
            "seat" to "driver",
        )
        val cleaned = payload.presentEntries()
        assertEquals(2, cleaned.size)
        assertEquals(mapOf("temperature" to 22, "seat" to "driver"), cleaned)
        // 空 map 要给出空 map 而不是 null，请求体组装那边直接 forEach 用。
        assertTrue(emptyMap<String, Int?>().presentEntries().isEmpty())
    }

    @Test
    fun missingRequiredValueBecomesDomainErrorNotException() {
        val present = (5.0).requirePresent("vehicleSpeed")
        assertTrue(present is Outcome.Success)
        assertEquals(5.0, (present as Outcome.Success).value, 1e-9)

        val missing = (null as Double?).requirePresent("vehicleSpeed")
        assertTrue(missing is Outcome.Failure)
        val error = (missing as Outcome.Failure).error
        assertTrue(error is DomainError.SignalUnreliable)
        assertEquals("vehicleSpeed", (error as DomainError.SignalUnreliable).signalName)
        // 文案直接进 UI：车机上不允许因为"没值"而崩溃，只允许提示"信号不可信"。
        assertNotNull(error.userMessage)
        assertTrue(error.userMessage.contains("不可信"))
    }

    @Test
    fun onNullOnlyEvaluatesFallbackWhenNeeded() {
        var fallbackCalls = 0
        val first = "real".onNull { fallbackCalls++; "fallback" }
        assertEquals("real", first)
        assertEquals(0, fallbackCalls)

        val second = (null as String?).onNull { fallbackCalls++; "fallback" }
        assertEquals("fallback", second)
        assertEquals(1, fallbackCalls)
        // 0 与空串都是有效值，不该触发兜底
        assertEquals(0, 0.onNull { 1 })
        assertEquals("", "".onNull { "x" })
    }
}
