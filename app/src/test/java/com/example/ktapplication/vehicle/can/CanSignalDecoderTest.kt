package com.example.ktapplication.vehicle.can

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CAN 解码单测。
 *
 * 期望值全部是**手算出来的**（注释里写了算式），不是拿实现跑一遍再把结果抄成断言 —— 后者
 * 只能证明"代码等于自己"，证明不了代码等于 DBC 语义。
 */
class CanSignalDecoderTest {

    private fun decode(hex: String, definition: CanSignalDefinition, at: Long = 0L): DecodedSignal =
        CanSignalDecoder.decode(CanFrame.of(definition.bus, definition.canId, hex, at), definition)

    // ---------- Motorola（大端 / AB-CD）----------

    @Test
    fun `motorola 16bit 车速 23 28 解成 90 kmh`() {
        // 0x2328 = 9000 → 9000 * 0.01 = 90.0
        val signal = decode("23 28", CanSignalCatalog.SPEED_KMH)
        assertEquals(SignalQuality.VALID, signal.quality)
        assertEquals(9000L, signal.rawValue)
        assertEquals(90.0, signal.physicalValue!!, 1e-9)
    }

    @Test
    fun `motorola 跨字节信号不能按小端连续读`() {
        // 同一帧里 byte2-3 是转角：0xFE3E = 65086，16bit 补码 → 65086-65536 = -450 → -45.0°
        val frame = CanFrame.of(CanBus.CHASSIS, 0x0A0, "23 28 FE 3E")
        val speed = CanSignalDecoder.decode(frame, CanSignalCatalog.SPEED_KMH)
        val steering = CanSignalDecoder.decode(frame, CanSignalCatalog.STEERING_ANGLE_DEG)
        assertEquals(90.0, speed.physicalValue!!, 1e-9)
        assertEquals(-450L, steering.rawValue)
        assertEquals(-45.0, steering.physicalValue!!, 1e-9)
    }

    @Test
    fun `12bit 起始位 7 的信号横跨 byte0 与 byte1`() {
        // byte0=0x24 → 高 8 位 36；byte1=0xA0 的高 4 位 1010 → 拼接 0b001001001010 = 586 → 293 km
        val signal = decode("24 A0", CanSignalCatalog.EV_RANGE_KM)
        assertEquals(586L, signal.rawValue)
        assertEquals(293.0, signal.physicalValue!!, 1e-9)
    }

    @Test
    fun `32bit 总里程跨 4 字节`() {
        // 0x00039447 = 234567 → * 0.1 = 23456.7 km
        val signal = decode("00 03 94 47", CanSignalCatalog.ODOMETER_KM)
        assertEquals(234567L, signal.rawValue)
        assertEquals(23456.7, signal.physicalValue!!, 1e-6)
    }

    // ---------- Intel（小端 / AA-BB）----------

    @Test
    fun `intel 13bit 轮速从 bit0 起跨字节`() {
        // byte0=0x08, byte1=0x07 → 0x07*256 + 0x08 = 1800 → * 0.05 = 90.0
        val signal = decode("08 07", CanSignalCatalog.WHEEL_SPEED_FL_KMH)
        assertEquals(1800L, signal.rawValue)
        assertEquals(90.0, signal.physicalValue!!, 1e-9)
    }

    @Test
    fun `intel 3bit 档位从 bit3 起只取三位`() {
        // 0x08 = 0b0000_1000 → bit3..5 = 001 → 1 = R
        assertEquals(1L, decode("08", CanSignalCatalog.GEAR_POSITION).rawValue)
        // 0x10 = 0b0001_0000 → bit3..5 = 010 → 2 = N
        assertEquals(2L, decode("10", CanSignalCatalog.GEAR_POSITION).rawValue)
        // 0x18 = 0b0001_1000 → bit3..5 = 011 → 3 = D
        assertEquals(3L, decode("18", CanSignalCatalog.GEAR_POSITION).rawValue)
    }

    @Test
    fun `单 bit 门状态按位号取`() {
        // 0x05 = 0b0000_0101 → bit0=1(左前开) bit1=0(右前关)
        val frame = CanFrame.of(CanBus.BODY, 0x0F0, "05 00 00 00 00 00 00 00")
        assertEquals(1L, CanSignalDecoder.decode(frame, CanSignalCatalog.DOOR_AJAR_FL).rawValue)
        assertEquals(0L, CanSignalDecoder.decode(frame, CanSignalCatalog.DOOR_AJAR_FR).rawValue)
        // bit4-5 = 0b00 → 中控锁 0（已解锁）
        assertEquals(0L, CanSignalDecoder.decode(frame, CanSignalCatalog.LOCK_STATE).rawValue)
    }

    // ---------- 无效值 / 超量程 / 解析错误 ----------

    @Test
    fun `全 1 位模式判为无效而不是 100 多`() {
        // SOC 定义里显式声明 0xFF 无效；不声明时 implicitInvalidRawPattern 也会推出 255
        val signal = decode("FF", CanSignalCatalog.PACK_SOC_PERCENT)
        assertEquals(SignalQuality.INVALID_PATTERN, signal.quality)
        assertNull(signal.physicalValue)
        assertEquals(255L, signal.implicitInvalidRawPatternValue())
    }

    @Test
    fun `档位 7 是无效码`() {
        // 0x38 = 0b0011_1000 → bit3..5 = 111 = 7 → 显式 invalidRawPattern
        assertEquals(SignalQuality.INVALID_PATTERN, decode("38", CanSignalCatalog.GEAR_POSITION).quality)
    }

    @Test
    fun `超出 DBC 量程判 OUT_OF_RANGE 而不是裁边界值`() {
        // 0x9C40 = 40000 → 400 km/h > max 240
        val signal = decode("9C 40", CanSignalCatalog.SPEED_KMH)
        assertEquals(SignalQuality.OUT_OF_RANGE, signal.quality)
        assertEquals(400.0, signal.physicalValue!!, 1e-9)
    }

    @Test
    fun `温度信号低于下限同样判超量程`() {
        // 0x46 = 70 → 70*0.5-40 = -5 ℃ < min 14（空调面板不该显示 -5）
        val signal = decode("46", CanSignalCatalog.HVAC_TEMP_DRIVER_C)
        assertEquals(SignalQuality.OUT_OF_RANGE, signal.quality)
        assertEquals(-5.0, signal.physicalValue!!, 1e-9)
    }

    @Test
    fun `DLC 不足时报 DECODE_ERROR 而不是当成 0`() {
        // 车速要 2 字节，只给 1 字节：必须区分"没收到"和"值是 0"
        val frame = CanFrame.of(CanBus.CHASSIS, 0x0A0, "23")
        val signal = CanSignalDecoder.decode(frame, CanSignalCatalog.SPEED_KMH)
        assertEquals(SignalQuality.DECODE_ERROR, signal.quality)
        assertNull(signal.rawValue)
        assertNull(CanSignalDecoder.readRawBits(frame, CanSignalCatalog.SPEED_KMH))
        // 同样的首字节，DLC 补齐后就能读到 0x2300 的高位
        assertEquals(0x2300L, CanSignalDecoder.readRawBits(CanFrame.of(CanBus.CHASSIS, 0x0A0, "23 00"), CanSignalCatalog.SPEED_KMH))
    }

    // ---------- 有符号 + offset ----------

    @Test
    fun `带 offset 的有符号电流`() {
        // 0x4CF4 = 19700 → 1970.0 - 2000 = -30 A（放电）
        assertEquals(-30.0, decode("4C F4", CanSignalCatalog.PACK_CURRENT_A).physicalValue!!, 1e-9)
        // 0x52D0 = 21200 → 2120.0 - 2000 = +120 A（充电）
        assertEquals(120.0, decode("52 D0", CanSignalCatalog.PACK_CURRENT_A).physicalValue!!, 1e-9)
    }

    @Test
    fun `补码符号扩展不会把负值解成大正数`() {
        // 0xFFFF... 全 1 会被无效码拦掉，所以用 0x8000：-32768 → -3276.8°（超量程但符号必须对）。
        // 转角定义在 startBit=23（byte2 的 bit7 起，MSB first 走到 byte3），所以帧必须给满 4 字节，
        // 只写 2 字节会因 DLC 不足直接解不出位域。
        val signal = decode("00 00 80 00", CanSignalCatalog.STEERING_ANGLE_DEG)
        assertEquals(-32768L, signal.rawValue)
        assertEquals(SignalQuality.OUT_OF_RANGE, signal.quality)
    }

    @Test
    fun `温度用无符号加负 offset 才可能到 32 度`() {
        // 0x7C = 124 → 22.0 ℃
        assertEquals(22.0, decode("7C", CanSignalCatalog.HVAC_TEMP_DRIVER_C).physicalValue!!, 1e-9)
        // 0x90 = 144 → 32.0 ℃：若按 8bit 补码建模，这里会变成 -96 ℃ 的假故障
        val hot = decode("90", CanSignalCatalog.HVAC_TEMP_DRIVER_C)
        assertEquals(32.0, hot.physicalValue!!, 1e-9)
        assertEquals(SignalQuality.VALID, hot.quality)
    }

    @Test
    fun `胎压与小电瓶电压的系数换算`() {
        // 0x3A = 58 → *4 = 232 kPa
        assertEquals(232.0, decode("3A", CanSignalCatalog.TYRE_PRESSURE_FL_KPA).physicalValue!!, 1e-9)
        // 0xF8 = 248 → *0.05 = 12.4 V
        assertEquals(12.4, decode("F8", CanSignalCatalog.LOW_BATTERY_POWER_C).physicalValue!!, 1e-9)
        // 0xFF = 胎压无效码（传感器未配对）
        assertEquals(SignalQuality.INVALID_PATTERN, decode("FF", CanSignalCatalog.TYRE_PRESSURE_FL_KPA).quality)
    }

    // ---------- 整帧解码 ----------

    @Test
    fun `一帧内多个信号各自解出`() {
        // 0x1E0：byte0 主驾温度 22℃，byte1 副驾 32℃，byte2 bit0..2 风量 5
        val frame = CanFrame.of(CanBus.BODY, 0x1E0, "7C 90 05 00 00 00 00 00")
        val decoded = CanSignalDecoder.decodeFrame(frame, CanSignalCatalog.definitionsFor(frame))
        assertEquals(22.0, decoded.getValue("HVAC_T_DRV").physicalValue!!, 1e-9)
        assertEquals(32.0, decoded.getValue("HVAC_T_PSG").physicalValue!!, 1e-9)
        assertEquals(5L, decoded.getValue("HVAC_FAN").rawValue)
        assertEquals(3, decoded.size)
    }

    @Test
    fun `未知报文 ID 解出空集合而不是崩溃`() {
        val frame = CanFrame.of(CanBus.DIAGNOSTIC, 0x7DF, "01 0C 00 00 00 00 00 00")
        assertTrue(CanSignalDecoder.decodeFrame(frame, CanSignalCatalog.all).isEmpty())
    }

    @Test
    fun `obd 大端两字节读法`() {
        // PID 0x0C 转速 = (A*256+B)/4：0x07D0 = 2000 → 500 rpm
        val frame = CanFrame.of(CanBus.DIAGNOSTIC, 0x7E8, "04 41 07 D0 00 00 00 00")
        assertEquals(2000, CanSignalDecoder.readBigEndianUInt16(frame, 2))
        assertEquals(500.0, CanSignalDecoder.readBigEndianUInt16(frame, 2)!! / 4.0, 1e-9)
        assertNull(CanSignalDecoder.readBigEndianUInt16(frame, 7))
    }

    @Test
    fun `帧的非法构造必须被拦住`() {
        val failures = listOf(
            runCatching { CanFrame.of(CanBus.BODY, 0x1FFF, "00") },           // 标准帧 ID 越界
            runCatching { CanFrame(CanBus.BODY, 0x100, 9, ByteArray(9), 0L) }, // DLC 越界
            runCatching { CanFrame(CanBus.BODY, 0x100, 4, ByteArray(2), 0L) }, // payload 短于 DLC
        )
        assertTrue(failures.all { it.isFailure })
    }

    private fun DecodedSignal.implicitInvalidRawPatternValue(): Long =
        definition.implicitInvalidRawPattern
}
