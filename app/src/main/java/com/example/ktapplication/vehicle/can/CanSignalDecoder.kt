package com.example.ktapplication.vehicle.can

import kotlin.math.roundToLong

/**
 * CAN 信号解码器：纯位运算，不依赖任何 Android API，所以能在 JVM 单测里逐位验证。
 *
 * 面试里"CAN 信号解析"最常追问的三个点都在这里显式实现了：
 *  1. Motorola / Intel 两种字节序下起始位语义不同（一个是 MSB 一个是 LSB）；
 *  2. 有符号位域要做补码符号扩展，否则 -40℃ 会被解成 4092；
 *  3. 物理值 = 原始值 * factor + offset，且全 1 位模式要当无效值丢弃。
 */
object CanSignalDecoder {

    /** 解出一帧里所有属于该 canId 的信号。返回顺序与 [definitions] 一致。 */
    fun decodeFrame(frame: CanFrame, definitions: List<CanSignalDefinition>): Map<String, DecodedSignal> {
        val result = LinkedHashMap<String, DecodedSignal>(definitions.size)
        for (definition in definitions) {
            if (definition.canId != frame.id || definition.bus != frame.bus) continue
            result[definition.name] = decode(frame, definition)
        }
        return result
    }

    fun decode(frame: CanFrame, definition: CanSignalDefinition): DecodedSignal {
        val raw = readRawBits(frame, definition)
            ?: return DecodedSignal(
                definition = definition,
                physicalValue = definition.initialPhysicalValue,
                rawValue = null,
                quality = SignalQuality.DECODE_ERROR,
                receivedAtMillis = frame.receivedAtMillis,
            )

        if (raw == definition.implicitInvalidRawPattern && definition.bitLength < 64) {
            return DecodedSignal(
                definition = definition,
                physicalValue = null,
                rawValue = raw,
                quality = SignalQuality.INVALID_PATTERN,
                receivedAtMillis = frame.receivedAtMillis,
            )
        }

        val signedRaw = signExtend(raw, definition)
        val physical = rawToPhysical(signedRaw, definition)
        val quality = when {
            definition.minValue != null && physical < definition.minValue -> SignalQuality.OUT_OF_RANGE
            definition.maxValue != null && physical > definition.maxValue -> SignalQuality.OUT_OF_RANGE
            else -> SignalQuality.VALID
        }
        return DecodedSignal(
            definition = definition,
            physicalValue = physical,
            rawValue = signedRaw,
            quality = quality,
            receivedAtMillis = frame.receivedAtMillis,
        )
    }

    /**
     * 位域抽取，返回未做符号扩展的无符号整数。
     *
     * 越界（DLC 不足、位域超出 payload）返回 null，让调用方能区分"值是 0"和"没收到"。
     */
    fun readRawBits(frame: CanFrame, definition: CanSignalDefinition): Long? {
        val byteLimit = frame.dlc
        return when (definition.byteOrder) {
            ByteOrder.INTEL -> readIntelBits(frame.data, byteLimit, definition.startBit, definition.bitLength)
            ByteOrder.MOTOROLA -> readMotorolaBits(frame.data, byteLimit, definition.startBit, definition.bitLength)
        }?.and(definition.rawBitMask)
    }

    /** Intel：起始位是 LSB，位号线性向上增长。 */
    private fun readIntelBits(data: ByteArray, byteLimit: Int, startBit: Int, bitLength: Int): Long? {
        var value = 0L
        for (index in 0 until bitLength) {
            val linear = startBit + index
            val byte = linear / 8
            if (byte >= byteLimit) return null
            val bit = (data[byte].toInt() shr (linear % 8)) and 1
            value = value or (bit.toLong() shl index)
        }
        return value
    }

    /**
     * Motorola：起始位是 MSB。
     *
     * 在同一字节内从高位往低位走，走到 bit0 后跳到下一字节的 bit7 —— 这就是 DBC 的
     * "锯齿"排布。跨字节信号（比如 16bit 的车速）如果按小端连续读就会把高低字节读反。
     */
    private fun readMotorolaBits(data: ByteArray, byteLimit: Int, startBit: Int, bitLength: Int): Long? {
        var value = 0L
        var byte = startBit / 8
        var offset = startBit % 8
        for (index in 0 until bitLength) {
            if (byte >= byteLimit) return null
            val bit = (data[byte].toInt() shr offset) and 1
            value = (value shl 1) or bit.toLong()
            offset -= 1
            if (offset < 0) {
                offset = 7
                byte += 1
            }
        }
        // 上面是 MSB first 逐位左移，最后一次循环多移了一次高位吗？没有：先 or 再移位计数由
        // 下一次 shl 完成，共 bitLength 位，结果正好是 MSB 在高位。
        return value
    }

    /** 补码符号扩展：把 bitLength 位的值还原成有符号 Long。 */
    fun signExtend(raw: Long, definition: CanSignalDefinition): Long {
        if (!definition.signed || definition.bitLength >= 64) return raw
        val signBit = 1L shl (definition.bitLength - 1)
        return if (raw and signBit != 0L) raw or (-1L shl definition.bitLength) else raw
    }

    fun rawToPhysical(signedRaw: Long, definition: CanSignalDefinition): Double =
        signedRaw.toDouble() * definition.factor + definition.offset

    fun physicalToRaw(physical: Double, definition: CanSignalDefinition): Long {
        val raw = ((physical - definition.offset) / definition.factor).roundToLong()
        require(definition.bitLength >= 64 || raw in -(1L shl (definition.bitLength - 1))..(1L shl definition.bitLength) - 1) {
            "${definition.name} 的物理值 $physical 换算原始值 $raw 超出 ${definition.bitLength}bit 范围"
        }
        return if (definition.bitLength >= 64) raw else raw and definition.rawBitMask
    }

    /**
     * OBD-II / UDS 常用的 2 字节大端数值（如 PID 0x0C 发动机转速）走的是纯网络字节序，
     * 不是 DBC 锯齿编号，单独提供一个快捷读法避免和 [readMotorolaBits] 混用。
     */
    fun readBigEndianUInt16(frame: CanFrame, byteOffset: Int): Int? {
        if (byteOffset + 1 >= frame.dlc) return null
        return ((frame.data[byteOffset].toInt() and 0xFF) shl 8) or (frame.data[byteOffset + 1].toInt() and 0xFF)
    }
}
