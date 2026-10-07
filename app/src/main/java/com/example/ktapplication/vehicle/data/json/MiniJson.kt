package com.example.ktapplication.vehicle.data.json

/**
 * 极简 JSON 解析/序列化。
 *
 * 为什么项目里不直接用 org.json（Android framework 自带）？
 *  1. **JVM 单测里 org.json 是 android.jar 的桩实现**。单元测试跑在
 *     `testOptions.unitTests.returnDefaultValues = true` 下，org.json 的
 *     JSONObject/JSONArray 所有方法都返回默认值（null/0/false）而**不会真的解析**，
 *     于是 TSP 报文 → 快照的映射逻辑完全无法被单测覆盖 —— 而这恰恰是车联网数据层
 *     最需要测试的部分（字段缺失、类型漂移、陈旧值判断）。
 *  2. 项目定位零第三方依赖：不引 kotlinx.serialization / Moshi / Gson，
 *     所以只能手写。TSP 报文结构非常扁平（一层 data + 少量嵌套对象），
 *     解析器只需支持 RFC 8259 核心子集，代码量和维护成本都可控。
 *  3. 手写解析对畸形输入的行为完全可控：抛 [JsonParseException] 并带位置信息，
 *     便于线上诊断"协议版本不匹配"类问题，而 org.json 的异常语义在桩环境下不可测。
 *
 * 明确不支持：JSON5（无引号 key、注释、尾逗号）、NaN/Infinity 字面量 —— TSP 网关不会产生这些。
 */

/** 解析失败。detail 带行列位置的上下文，直接进 Malformed(domainError) 给诊断页展示。 */
class JsonParseException(val detail: String) : Exception(detail)

/** JSON 值模型。用 sealed class 而不是 Any，强制调用方穷尽分支，避免 ClassCastException 满天飞。 */
sealed class JsonValue

class JObject(val members: Map<String, JsonValue>) : JsonValue() {
    fun get(key: String): JsonValue? = members[key]
    val size: Int get() = members.size
}

class JArray(val elements: List<JsonValue>) : JsonValue() {
    val size: Int get() = elements.size
    operator fun get(index: Int): JsonValue? = elements.getOrNull(index)
}

/**
 * 数字同时保留 [rawText] 与 [value]：
 * 全部按 Double 解析会丢精度（VIN 里混入长数字串、服务端消息号可达 19 位 long），
 * 需要精确整数的地方用 [asLongOrNull] 从原始文本重解析。
 */
class JNumber(val value: Double, val rawText: String) : JsonValue() {
    fun asLongOrNull(): Long? = rawText.toLongOrNull()
}

class JBool(val value: Boolean) : JsonValue()
object JNull : JsonValue()
class JString(val value: String) : JsonValue()

/** 便捷工厂，测试与序列化路径复用。 */
fun jObj(vararg pairs: Pair<String, JsonValue>): JObject = JObject(pairs.toMap())
fun jArr(vararg values: JsonValue): JArray = JArray(values.toList())
fun jStr(value: String): JString = JString(value)
fun jNum(value: Number): JNumber = JNumber(value.toDouble(), value.toString())
fun jBool(value: Boolean): JBool = JBool(value)

// ---- 取值扩展：类型不符一律返回 null，绝不抛。 ----
// TSP 不同车型/年款字段集合不一致，甚至同一字段有时回 number 有时回 string
// （车辆下电后云端缓存重放的老数据常见），解析层"宽容取值 + null 语义"是把
// 可信度判断留给 repository 层的前提；在这一层崩溃就等于把协议脏数据升级成 App 崩溃。

fun JObject.optString(key: String): String? = when (val v = members[key]) {
    is JString -> v.value
    is JNumber -> v.rawText
    is JBool -> v.value.toString()
    else -> null
}

fun JObject.optDouble(key: String): Double? = when (val v = members[key]) {
    is JNumber -> v.value
    // 网关偶尔把数值序列化成字符串（"72.5"），能转就转，转不了才放弃。
    is JString -> v.value.toDoubleOrNull()
    else -> null
}

fun JObject.optInt(key: String): Int? = when (val v = members[key]) {
    is JNumber -> v.value.toInt()
    is JString -> v.value.toIntOrNull() ?: v.value.toDoubleOrNull()?.toInt()
    else -> null
}

fun JObject.optLong(key: String): Long? = when (val v = members[key]) {
    is JNumber -> v.asLongOrNull() ?: v.value.toLong()
    is JString -> v.value.toLongOrNull() ?: v.value.toDoubleOrNull()?.toLong()
    else -> null
}

fun JObject.optBoolean(key: String): Boolean? = when (val v = members[key]) {
    is JBool -> v.value
    is JNumber -> if (v.value == 0.0) false else true
    is JString -> v.value.equals("true", ignoreCase = true) || v.value == "1"
    else -> null
}

fun JObject.optObject(key: String): JObject? = members[key] as? JObject

fun JObject.optArray(key: String): JArray? = members[key] as? JArray

// ---- 递归下降解析 ----

object MiniJson {

    /** 解析整个文本；前后允许多余空白，不允许多个顶层值。 */
    fun parse(text: String): JsonValue = Parser(text).parseDocument()

    /** 期望顶层是对象；网关回数组或标量视为协议异常。 */
    fun parseObject(text: String): JObject = when (val v = parse(text)) {
        is JObject -> v
        else -> throw JsonParseException("期望顶层 JSON 对象，实际是 ${v::class.simpleName}")
    }

    /** 不抛异常的入口：给"允许局部容错"的场景（比如队列里一行损坏记录）使用。 */
    fun tryParse(text: String): JsonValue? = try {
        parse(text)
    } catch (_: JsonParseException) {
        null
    }
}

private class Parser(private val text: String) {
    private var pos = 0

    fun parseDocument(): JsonValue {
        skipWhitespace()
        val value = parseValue()
        skipWhitespace()
        if (pos < text.length) fail("顶层值之后还有多余内容")
        return value
    }

    private fun parseValue(): JsonValue {
        skipWhitespace()
        if (pos >= text.length) fail("内容提前结束")
        return when (val c = text[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JString(parseString())
            't' -> expectLiteral("true").let { JBool(true) }
            'f' -> expectLiteral("false").let { JBool(false) }
            'n' -> expectLiteral("null").let { JNull }
            else -> if (c == '-' || c.isDigit()) parseNumber() else fail("无法识别的字符 '$c'")
        }
    }

    private fun parseObject(): JObject {
        consume('{')
        val members = LinkedHashMap<String, JsonValue>()
        skipWhitespace()
        if (pos < text.length && text[pos] == '}') {
            pos++
            return JObject(members)
        }
        while (true) {
            skipWhitespace()
            if (pos >= text.length) fail("对象未闭合")
            if (text[pos] != '"') fail("对象的 key 必须是带引号的字符串")
            val key = parseString()
            skipWhitespace()
            if (pos >= text.length || text[pos] != ':') fail("key 之后缺少冒号")
            pos++
            members[key] = parseValue()
            skipWhitespace()
            if (pos >= text.length) fail("对象未闭合")
            when (text[pos]) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return JObject(members)
                }
                else -> fail("对象成员之间缺少逗号")
            }
        }
    }

    private fun parseArray(): JArray {
        consume('[')
        val elements = ArrayList<JsonValue>()
        skipWhitespace()
        if (pos < text.length && text[pos] == ']') {
            pos++
            return JArray(elements)
        }
        while (true) {
            elements += parseValue()
            skipWhitespace()
            if (pos >= text.length) fail("数组未闭合")
            when (text[pos]) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return JArray(elements)
                }
                else -> fail("数组元素之间缺少逗号")
            }
        }
    }

    private fun parseString(): String {
        consume('"')
        val sb = StringBuilder()
        while (true) {
            if (pos >= text.length) fail("字符串未闭合")
            when (val c = text[pos]) {
                '"' -> {
                    pos++
                    return sb.toString()
                }
                '\\' -> {
                    pos++
                    if (pos >= text.length) fail("转义符后缺少内容")
                    when (val e = text[pos]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            pos++
                            val hex = text.substring(pos, minOf(pos + 4, text.length))
                            if (hex.length < 4) fail("\\u 转义不足 4 位十六进制")
                            val code = hex.toIntOrNull(16) ?: fail("\\u 转义含非法十六进制字符")
                            sb.append(code.toChar())
                            pos += 3 // 循环再 ++ 后正好跳过 4 位
                        }
                        else -> fail("非法转义 '\\$e'")
                    }
                    pos++
                }
                else -> {
                    // 控制字符（<0x20）裸出现在字符串里是非法 JSON：
                    // 常见于中间层网关没转义直接塞了换行，必须报错而不是吞掉，
                    // 否则报文错位这种严重问题会被静默掩盖。
                    if (c < '\u0020') fail("字符串内含裸控制字符 0x${c.code.toString(16)}")
                    sb.append(c)
                    pos++
                }
            }
        }
    }

    private fun parseNumber(): JNumber {
        val start = pos
        if (pos < text.length && (text[pos] == '-' || text[pos] == '+')) {
            if (text[pos] == '+') fail("数字不允许 '+' 前缀")
            pos++
        }
        while (pos < text.length && text[pos].isDigit()) pos++
        if (pos < text.length && text[pos] == '.') {
            pos++
            if (pos >= text.length || !text[pos].isDigit()) fail("小数点后缺少数字")
            while (pos < text.length && text[pos].isDigit()) pos++
        }
        if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
            pos++
            if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
            if (pos >= text.length || !text[pos].isDigit()) fail("指数部分缺少数字")
            while (pos < text.length && text[pos].isDigit()) pos++
        }
        val raw = text.substring(start, pos)
        val value = raw.toDoubleOrNull() ?: fail("数字 '$raw' 无法转换为 Double")
        return JNumber(value, raw)
    }

    private fun expectLiteral(literal: String): Unit {
        if (!text.startsWith(literal, pos)) fail("期望字面量 $literal")
        pos += literal.length
    }

    private fun consume(expected: Char) {
        if (pos >= text.length || text[pos] != expected) fail("期望字符 '$expected'")
        pos++
    }

    private fun skipWhitespace() {
        while (pos < text.length && text[pos].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) pos++
    }

    private fun fail(detail: String): Nothing {
        // 带偏移量和附近片段：诊断线上报文时，"哪里错了"比"错了"有用得多。
        val contextStart = maxOf(0, pos - 20)
        val snippet = text.substring(contextStart, minOf(text.length, pos + 20)).replace('\n', '↵')
        throw JsonParseException("位置 $pos: $detail；上下文: …$snippet…")
    }
}

// ---- 序列化（写路径只需要产出紧凑合法 JSON）----

/**
 * 写出 JSON 文本。给离线队列持久化和请求体构造用。
 * 没有 pretty-print：落盘一行一条记录，紧凑格式能减少小文件 IO 次数。
 */
fun JsonValue.toJson(): String {
    val sb = StringBuilder()
    writeTo(sb)
    return sb.toString()
}

private fun JsonValue.writeTo(sb: StringBuilder) {
    when (this) {
        is JObject -> {
            sb.append('{')
            var first = true
            for ((key, value) in members) {
                if (!first) sb.append(',')
                first = false
                writeString(key, sb)
                sb.append(':')
                value.writeTo(sb)
            }
            sb.append('}')
        }
        is JArray -> {
            sb.append('[')
            elements.forEachIndexed { index, value ->
                if (index > 0) sb.append(',')
                value.writeTo(sb)
            }
            sb.append(']')
        }
        is JString -> writeString(value, sb)
        is JNumber -> sb.append(rawText)
        is JBool -> sb.append(if (value) "true" else "false")
        JNull -> sb.append("null")
    }
}

private fun writeString(value: String, sb: StringBuilder) {
    sb.append('"')
    for (c in value) {
        when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c == '\b' -> sb.append("\\b")
            c == '\u000C' -> sb.append("\\f")
            c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> sb.append(c)
        }
    }
    sb.append('"')
}
