package com.example.ktapplication.vehicle.can

/**
 * 字节序。
 *
 * DBC 里叫 "Byte Order"：
 *  - 1 = Motorola / 大端（业内也叫 AB CD 顺序），乘用车车身信号最常见；
 *  - 0 = Intel / 小端（AA BB 顺序），动力域和 OBD-II 诊断常见。
 *
 * startBit 在两种顺序下含义不同：它分别是信号的 LSB（Intel）和 MSB（Motorola）位置，
 * 位置编号统一使用 DBC 的锯齿编号：byte[i] 的 bit 从低位到高位记作 i*8 .. i*8+7。
 */
enum class ByteOrder { INTEL, MOTOROLA }

/** 信号在总线上的可信度，直接决定 UI 是显示数值、显示 "--" 还是灰掉。 */
enum class SignalQuality {
    /** 正常。 */
    VALID,

    /** 报文里就是无效码（常见为全 1，例如 0xFF / 0x3FF）。 */
    INVALID_PATTERN,

    /** 解码成功但超出定义量程，一般是传感器故障或标定错误。 */
    OUT_OF_RANGE,

    /** 从没收到过这帧，信号尚未建立。 */
    NOT_AVAILABLE,

    /** 曾经有效，但超过 DBC timeout 没刷新，座舱必须把它当"陈旧"处理而不是继续显示旧值。 */
    STALE,

    /** DLC 不足或位域越出 payload，属于解析层错误。 */
    DECODE_ERROR,
}

/**
 * 一条信号的解码规则，等价于 DBC 里 SIGNAL_ 一行加上注释属性。
 */
data class CanSignalDefinition(
    val name: String,
    val bus: CanBus,
    /** 所属报文 ID。 */
    val canId: Int,
    /** 起始位，锯齿编号，见 [ByteOrder]。 */
    val startBit: Int,
    /** 位长，1..64。 */
    val bitLength: Int,
    val byteOrder: ByteOrder,
    /** 有符号位域用二进制补码表示。 */
    val signed: Boolean = false,
    /** 物理值 = 原始值 * factor + offset。 */
    val factor: Double = 1.0,
    val offset: Double = 0.0,
    val unit: String = "",
    val minValue: Double? = null,
    val maxValue: Double? = null,
    /** 无效原始值，来自 DBC 的 GenSigStartValue/GenMsgSendType 约定，典型如 1023。 */
    val invalidRawPattern: Long? = null,
    /** 上电默认值，车机上电瞬间不能显示 0 km/h 误导用户。 */
    val initialPhysicalValue: Double? = null,
    /** 期望发送周期，用于超时与丢帧率估算；null 表示事件型信号。 */
    val expectedPeriodMillis: Long? = null,
    /** 超时时间，通常为 2~3 个周期；超过即判 [SignalQuality.STALE]。 */
    val timeoutMillis: Long = expectedPeriodMillis?.let { it * 3 } ?: 0L,
    val description: String = "",
) {
    init {
        require(bitLength in 1..64) { "$name 位长非法：$bitLength" }
        require(startBit in 0..63) { "$name 起始位非法：$startBit" }
        require(factor > 0) { "$name factor 必须为正，方向请通过 offset 表达" }
    }

    /**
     * 原始值全 1 是行业惯用的无效码，没显式声明时也自动推出来。
     *
     * 自动推导只对 **≥2bit** 成立：1bit 布尔量（门开、插枪、上锁、安全带）的合法取值就是 0/1，
     * 把"全 1"当无效码等于让这些事件型信号永远不可信 —— 真车上的表现是"门永远显示关"、
     * "插枪状态永远读不出来"，而 DBC 里也确实不会给 1bit 信号配 invalid 码。
     * 1bit 时返回 -1：位域掩码后 raw 只会是 0/1，永远匹配不上，等价于"该信号没有隐式无效码"。
     * 显式声明的 [invalidRawPattern] 仍然拥有绝对话语权。
     */
    val implicitInvalidRawPattern: Long
        get() = invalidRawPattern ?: if (bitLength < 2 || bitLength >= 64) -1L else (1L shl bitLength) - 1

    val rawBitMask: Long
        get() = if (bitLength >= 64) -1L else (1L shl bitLength) - 1
}

/** 一次解码结果：物理值 + 可信度 + 时间戳。 */
data class DecodedSignal(
    val definition: CanSignalDefinition,
    /** 按 factor/offset 换算后的工程值；不可信时可能为 null。 */
    val physicalValue: Double?,
    /** 位域里读出来的原始整数（已完成符号扩展）。 */
    val rawValue: Long?,
    val quality: SignalQuality,
    val receivedAtMillis: Long,
) {
    val displayValue: String
        get() = if (physicalValue == null || quality != SignalQuality.VALID) "--" else {
            val digits = when {
                definition.factor >= 1.0 -> 0
                definition.factor >= 0.1 -> 1
                else -> 2
            }
            String.format(java.util.Locale.ROOT, "%.${digits}f", physicalValue)
        }

    val displayWithUnit: String
        get() = if (displayValue == "--") displayValue else "$displayValue${definition.unit}"

    fun isStaleAt(nowMillis: Long): Boolean =
        definition.timeoutMillis > 0 && nowMillis - receivedAtMillis > definition.timeoutMillis

    companion object {
        fun notAvailable(definition: CanSignalDefinition, nowMillis: Long): DecodedSignal =
            DecodedSignal(
                definition = definition,
                physicalValue = definition.initialPhysicalValue,
                rawValue = null,
                quality = SignalQuality.NOT_AVAILABLE,
                receivedAtMillis = nowMillis,
            )
    }
}
