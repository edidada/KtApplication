package com.example.ktapplication.vehicle.can

/**
 * 反向编码：把物理值写回 payload。
 *
 * 座舱 App 一般不直接发 CAN（写总线要经过车身域控制器的权限校验），但有两类场景需要它：
 *  - 测试/工装：注入伪造信号做 HIL 验证；
 *  - 网关转发：把座舱域的控制意图映射成车身域报文。
 * 提供 encoder 也顺带证明 decoder 的位运算实现是可逆的，这是最好的单测断言方式。
 */
object CanSignalEncoder {

    /** 读-改-写：只覆盖目标位域，保留同一帧里其他信号的 bit。 */
    fun writeSignal(frame: CanFrame, definition: CanSignalDefinition, physicalValue: Double): CanFrame {
        val raw = CanSignalDecoder.physicalToRaw(physicalValue, definition)
        return writeRawBits(frame, definition, raw)
    }

    fun writeRawBits(frame: CanFrame, definition: CanSignalDefinition, maskedRaw: Long): CanFrame {
        val payload = frame.data.copyOf()
        when (definition.byteOrder) {
            ByteOrder.INTEL -> writeIntelBits(payload, definition, maskedRaw)
            ByteOrder.MOTOROLA -> writeMotorolaBits(payload, definition, maskedRaw)
        }
        return frame.copy(data = payload, dlc = maxOf(frame.dlc, bytesNeededFor(definition)))
    }

    private fun writeIntelBits(payload: ByteArray, definition: CanSignalDefinition, raw: Long) {
        for (index in 0 until definition.bitLength) {
            val linear = definition.startBit + index
            setBit(payload, linear / 8, linear % 8, (raw shr index) and 1L)
        }
    }

    /**
     * Motorola 写入顺序必须和读取严格镜像：MSB 对应起始位，逐位向低位走、跨字节跳到下一字节 bit7。
     * 这里把 [definition.bitLength] 位当作"高位在前"消费，与读取时的 (value shl 1) or bit 对齐。
     */
    private fun writeMotorolaBits(payload: ByteArray, definition: CanSignalDefinition, raw: Long) {
        var byte = definition.startBit / 8
        var offset = definition.startBit % 8
        for (index in 0 until definition.bitLength) {
            val sourceBit = definition.bitLength - 1 - index
            setBit(payload, byte, offset, (raw shr sourceBit) and 1L)
            offset -= 1
            if (offset < 0) {
                offset = 7
                byte += 1
            }
        }
    }

    private fun setBit(payload: ByteArray, byte: Int, offset: Int, bit: Long) {
        val current = payload[byte].toInt() and 0xFF
        val updated = if (bit == 1L) current or (1 shl offset) else current and (1 shl offset).inv()
        payload[byte] = updated.toByte()
    }

    /** 信号占用的最高字节 + 1，用来把 DLC 撑到能容纳该位域。 */
    private fun bytesNeededFor(definition: CanSignalDefinition): Int {
        val touchedBytes = when (definition.byteOrder) {
            ByteOrder.INTEL -> (definition.startBit + definition.bitLength - 1) / 8 + 1
            ByteOrder.MOTOROLA -> {
                // 从起始位向低位走，跨字节次数 = (位长 - 起始位所在字节内剩余位数) / 8 向上取整
                val bitsInStartByte = definition.startBit % 8 + 1
                if (definition.bitLength <= bitsInStartByte) {
                    definition.startBit / 8 + 1
                } else {
                    val remaining = definition.bitLength - bitsInStartByte
                    definition.startBit / 8 + 1 + ((remaining - 1) / 8 + 1)
                }
            }
        }
        return touchedBytes.coerceAtMost(CanFrame.MAX_CLASSIC_DLC)
    }

    /**
     * 组一帧只包含单条信号的场景（测试常用）。多信号复用同一 ID 时请用 [writeSignal] 反复叠加。
     */
    fun buildFrame(
        definition: CanSignalDefinition,
        physicalValues: List<Double>,
        receivedAtMillis: Long,
        bus: CanBus = definition.bus,
    ): CanFrame {
        var current = CanFrame(
            bus = bus,
            id = definition.canId,
            dlc = bytesNeededFor(definition),
            data = ByteArray(CanFrame.MAX_CLASSIC_DLC),
            receivedAtMillis = receivedAtMillis,
        )
        physicalValues.forEach { value ->
            current = writeSignal(current, definition, value)
        }
        return current
    }
}
