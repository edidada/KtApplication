package com.example.ktapplication.vehicle.can

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 编解码可逆性单测。
 *
 * 这是整个 CAN 模块最有价值的一组断言：位运算实现只要有一处写错（尤其是 Motorola 的
 * 锯齿方向），编码→解码就不可能回到原值。它覆盖信号表里的**每一条**定义，
 * 新增信号时无需再写针对性用例。
 */
class CanSignalRoundTripTest {

    /** 覆盖 0、单 bit、跨字节边界、补码符号位、接近全 1 的典型位模式。 */
    private fun probeRawValues(definition: CanSignalDefinition): List<Long> {
        val mask = definition.rawBitMask
        val candidates = longArrayOf(
            0L, 1L, 2L, 3L, 4L, 7L, 8L, 15L, 16L, 31L, 32L, 63L, 64L, 127L, 128L,
            255L, 256L, 511L, 1000L, 1023L, 1024L, 2047L, 4095L, 4096L, 8191L,
            16383L, 16384L, 32767L, 32768L, 65535L,
        ).map { it and mask }.distinct()
        // 全 1 会被判无效码，单独走 invalid 分支断言
        return candidates
    }

    @Test
    fun `每条信号表定义都能编码后再解码还原`() {
        CanSignalCatalog.all.forEach { definition ->
            val base = CanSignalEncoder.buildFrame(definition, emptyList(), receivedAtMillis = 0L)
            probeRawValues(definition).forEach { raw ->
                val written = CanSignalEncoder.writeRawBits(base, definition, raw)
                val decoded = CanSignalDecoder.decode(written, definition)
                val expectedSigned = CanSignalDecoder.signExtend(raw, definition)
                val expectedPhysical = expectedSigned.toDouble() * definition.factor + definition.offset

                if (raw == definition.implicitInvalidRawPattern && definition.bitLength < 64) {
                    assertEquals("${definition.name} 的全 1 码应判无效", SignalQuality.INVALID_PATTERN, decoded.quality)
                    return@forEach
                }

                assertNotNull("${definition.name} 应解出值", decoded.physicalValue)
                assertEquals("${definition.name} 原始值不守恒", expectedSigned, decoded.rawValue)
                assertEquals("${definition.name} 物理值不守恒", expectedPhysical, decoded.physicalValue!!, 1e-9)

                val inRange = (definition.minValue == null || expectedPhysical >= definition.minValue) &&
                    (definition.maxValue == null || expectedPhysical <= definition.maxValue)
                assertEquals(
                    "${definition.name} 量程判定错误：$expectedPhysical",
                    if (inRange) SignalQuality.VALID else SignalQuality.OUT_OF_RANGE,
                    decoded.quality,
                )
            }
        }
    }

    @Test
    fun `物理值写入后按显示精度回读`() {
        val cases = listOf(
            CanSignalCatalog.SPEED_KMH to 90.0,
            CanSignalCatalog.STEERING_ANGLE_DEG to -45.0,
            CanSignalCatalog.WHEEL_SPEED_FL_KMH to 90.0,
            CanSignalCatalog.PACK_SOC_PERCENT to 80.0,
            CanSignalCatalog.PACK_VOLTAGE_V to 399.0,
            CanSignalCatalog.PACK_CURRENT_A to -30.0,
            CanSignalCatalog.MOTOR_RPM to 3000.0,
            CanSignalCatalog.GEAR_POSITION to 3.0,
            CanSignalCatalog.EV_RANGE_KM to 293.0,
            CanSignalCatalog.ODOMETER_KM to 23456.7,
            CanSignalCatalog.HVAC_TEMP_DRIVER_C to 22.0,
            CanSignalCatalog.HVAC_TEMP_PASSENGER_C to 26.0,
            CanSignalCatalog.HVAC_FAN_LEVEL to 4.0,
            CanSignalCatalog.CHARGE_POWER_KW to 6.5,
            CanSignalCatalog.TYRE_PRESSURE_FL_KPA to 232.0,
            CanSignalCatalog.LOW_BATTERY_POWER_C to 12.4,
        )
        cases.forEach { (definition, physical) ->
            val frame = CanSignalEncoder.buildFrame(definition, listOf(physical), receivedAtMillis = 1_000L)
            val decoded = CanSignalDecoder.decode(frame, definition)
            assertEquals(SignalQuality.VALID, decoded.quality)
            // 量化误差上限是半个 factor
            assertEquals(physical, decoded.physicalValue!!, definition.factor / 2.0 + 1e-9)
            assertEquals(1_000L, decoded.receivedAtMillis)
        }
    }

    @Test
    fun `同一帧内叠加多个信号互不覆盖`() {
        val speedFrame = CanSignalEncoder.buildFrame(CanSignalCatalog.SPEED_KMH, listOf(90.0), 0L)
        val combined = CanSignalEncoder.writeSignal(speedFrame, CanSignalCatalog.STEERING_ANGLE_DEG, -45.0)

        // 写入 16bit 转角后 DLC 必须被撑到 4 字节，否则读不到
        assertEquals(4, combined.dlc)
        assertEquals(90.0, CanSignalDecoder.decode(combined, CanSignalCatalog.SPEED_KMH).physicalValue!!, 1e-9)
        assertEquals(-45.0, CanSignalDecoder.decode(combined, CanSignalCatalog.STEERING_ANGLE_DEG).physicalValue!!, 1e-9)
        // 与手算报文 "23 28 FE 3E" 完全一致
        assertEquals("23 28 FE 3E 00 00 00 00", combined.data.joinToString(" ") { "%02X".format(it) })
    }

    @Test
    fun `位长不足 8 的信号只动自己那几位`() {
        val locked = CanSignalEncoder.buildFrame(CanSignalCatalog.LOCK_STATE, listOf(1.0), 0L)
        val doorOpen = CanSignalEncoder.writeSignal(locked, CanSignalCatalog.DOOR_AJAR_FL, 1.0)
        // CENTRAL_LOCK=1（bit4）+ DOOR_FL 开（bit0） → 0b0001_0001 = 0x11
        assertEquals(0x11.toByte(), doorOpen.data[0])
        assertEquals(1L, CanSignalDecoder.decode(doorOpen, CanSignalCatalog.LOCK_STATE).rawValue)
        assertEquals(1L, CanSignalDecoder.decode(doorOpen, CanSignalCatalog.DOOR_AJAR_FL).rawValue)
    }
}
