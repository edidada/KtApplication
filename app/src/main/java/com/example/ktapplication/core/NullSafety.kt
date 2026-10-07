package com.example.ktapplication.core

/**
 * 空安全工具箱。
 *
 * Kotlin 的类型系统只挡住"引用层面的 NPE"，它不解决真正的业务难点：**把"没有值"和
 * "值恰好是 0 / 空串 / NaN" 混为一谈**。车机上这类错误是有后果的：
 *
 *  - 车速取不到却默认 0 km/h，"行驶中禁止解锁"的前置条件就被绕过去了（见 Telemetry.orDefault）；
 *  - 云端把 SOC 序列化成字符串 "NaN"，`String.toDoubleOrNull()` 是**能解析成功**的
 *    （它走 `Double.parseDouble`，而 "NaN"/"Infinity" 都是合法字面量），于是仪表显示 NaN，
 *    或者被下游当成有效值参与告警判定；
 *  - 指令参数里的 null 直接进 JSON，云端按"字段缺失=不修改"解释，用户的设置被静默丢掉。
 *
 * 所以这里的函数只做一件事：**让"未知"一路保持未知**，直到调用方明确决定怎么兜底。
 * 命名统一用 `OrNull` / `present` / `finite`，看到调用点就知道返回 null 是正常分支而不是异常。
 */

/** 空白串按"没给"处理：`""` 和 `"   "` 在协议里与字段缺失等价，不能当成合法取值。 */
fun String?.blankToNull(): String? = this?.takeIf { it.isNotBlank() }

/**
 * 只接受**有限**double：NaN、POSITIVE_INFINITY、NEGATIVE_INFINITY 一律返回 null。
 *
 * 这是 `toDoubleOrNull()` 的替代写法。`"NaN".toDoubleOrNull()` 返回 NaN 而不是 null，
 * 而 NaN 参与任何比较都是 false，于是 `if (soc > 0) ... else 报警` 这种代码会把"传感器坏了"
 * 读成"传感器值为 0"—— 排查时会误导成硬件故障。
 */
fun String?.toFiniteDoubleOrNull(): Double? = this?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }

/** [Float] 版本：`optDouble(...) ?.toFloat()` 之后仍可能是 NaN，统一在这里挡掉。 */
fun String?.toFiniteFloatOrNull(): Float? = this?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() }

/** 已经拿到手的数字也要过一遍：数值来源可能是反射、可能是老 SDK 的默认值 0.0/NaN。 */
fun Double?.finiteOrNull(): Double? = this?.takeIf { it.isFinite() }

fun Float?.finiteOrNull(): Float? = this?.takeIf { it.isFinite() }

/**
 * Double → Int，且只在接受值确实落在 Int 区间时给结果。
 *
 * 不能直接 `someDouble.toInt()`：Kotlin 走的是 Java 的收窄语义，
 * **NaN → 0**、**越界 → 饱和成 ±Int.MAX_VALUE**，两种坏数据都会被伪装成"一个合法整数"。
 * 控车指令里的目标温度、-setSpeed 上限这类参数一旦带上 Int.MAX_VALUE，
 * 车辆执行器会照单全收，属于"解析层不报错、车上出事故"的典型链路。
 */
fun Double?.finiteIntOrNull(): Int? = this?.takeIf {
    it.isFinite() && it >= INT_FLOOR && it <= INT_CEIL
}?.toInt()

/**
 * 十进制整数严格解析：只接受可选正负号加 ASCII 数字，拒绝 `"12.0"`、`"+7"`、`"０"`（全角）、
 * `"0x1F"`、空串与超范围值。
 *
 * 为什么不用 `toIntOrNull()`：它对 `"12.0"`  `"0x1F"` 这类非法写法只是返回 null，
 * 无法区分；而对**超出 Int 范围**的数字串（云端用 long 存车速、把 2147483648 塞进 int 字段）
 * 同样返回 null —— 语义上"太大"和"不是数字"是两种故障，这里统一按不可信处理，
 * 同时用 Long 过渡把溢出挡在 [maxInclusive] 之前，避免调用方拿到一个回绕后的负数。
 *
 * 位数上限只设到 10 位（纯截断输入长度，越界判断仍由数值范围负责），
 * 所以 1_500_000_000 这种合法 Int 是能解析的，不会因为"看着很长"被误杀。
 */
fun String?.toPlainIntOrNull(maxInclusive: Int? = null): Int? {
    val text = this?.trim()?.blankToNull() ?: return null
    if (!PLAIN_INT_PATTERN.matches(text)) return null
    val asLong = text.toLongOrNull() ?: return null
    if (asLong > Int.MAX_VALUE || asLong < Int.MIN_VALUE) return null
    val asInt = asLong.toInt()
    return if (maxInclusive != null && asInt > maxInclusive) null else asInt
}

/**
 * 有限值的平均数：null 元素与 NaN 元素都跳过，**一个都不剩时返回 null**。
 *
 * 返回 null 而不是 0 是重点：诊断页"平均帧耗时 0ms"会被读成"完美流畅"，
 * 而真实情况是这一窗口根本没有采到样本。空集合的 0 是撒谎。
 */
fun Iterable<Double?>.finiteAverageOrNull(): Double? {
    var sum = 0.0
    var count = 0
    for (sample in this) {
        val value = sample?.takeIf { it.isFinite() } ?: continue
        sum += value
        count += 1
    }
    if (count == 0) return null
    return sum / count
}

/**
 * 去掉 value 为 null 的条目，用于组装下发给云端的参数表。
 *
 * 保留 null 的风险在于：远端把"字段存在但为 null"和"字段不存在"解读成两种行为，
 * 于是"只改温度不改风速"的请求会连带把风速重置。宁可少发字段，也不要发 null。
 */
fun <K, V : Any> Map<K, V?>.presentEntries(): Map<K, V> {
    val result = LinkedHashMap<K, V>(entries.size)
    for ((key, value) in this) if (value != null) result[key] = value
    return result
}

/**
 * 把"必填值缺失"升格成领域错误，而不是抛 NPE/ISE。
 *
 * UI 层拿到 [Outcome.Failure] 会显示 [DomainError.userMessage]，抛异常则直接闪退；
 * 车机上闪退一次就要重启 40 秒，稳定性考核按次计账，所以缺值必须走结果通道。
 */
fun <T : Any> T?.requirePresent(signalName: String): Outcome<T> =
    if (this != null) Outcome.success(this)
    else Outcome.failure(DomainError.SignalUnreliable(signalName, "MISSING"))

/**
 * 安全地把可空值喂给只接受非空的函数：`value onNull { fallback() }` 读起来比
 * `value ?: run { fallback() }` 更容易在链式调用里看清"哪一段是兜底"。
 *
 * 刻意做成 inline + 泛型返回：兜底分支不该被装箱或被调用两次。
 */
inline fun <T> T?.onNull(fallback: () -> T): T = this ?: fallback()

private val PLAIN_INT_PATTERN = Regex("[+-]?\\d{1,10}")

private const val INT_FLOOR = -2147483648.0
private const val INT_CEIL = 2147483647.0
