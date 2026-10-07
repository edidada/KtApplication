package com.example.ktapplication.vehicle.domain.repo

import com.example.ktapplication.core.FakeMonotonicClock
import com.example.ktapplication.vehicle.can.CanBus
import com.example.ktapplication.vehicle.can.CanSignalCatalog
import com.example.ktapplication.vehicle.can.CanSignalDecoder
import com.example.ktapplication.vehicle.can.CanSignalDefinition
import com.example.ktapplication.vehicle.can.CanSignalEncoder
import com.example.ktapplication.vehicle.can.DecodedSignal
import com.example.ktapplication.vehicle.can.VehicleCanSnapshot
import com.example.ktapplication.vehicle.carservice.CarPropertySample
import com.example.ktapplication.vehicle.carservice.CarVehicleProperty
import com.example.ktapplication.vehicle.carservice.GLOBAL_AREA_ID
import com.example.ktapplication.vehicle.carservice.PropertyStatus
import com.example.ktapplication.vehicle.carservice.PropertyValue
import com.example.ktapplication.vehicle.data.TspStatusSnapshot
import com.example.ktapplication.vehicle.domain.model.DataFreshness
import com.example.ktapplication.vehicle.domain.model.DoorPosition
import com.example.ktapplication.vehicle.domain.model.DoorState
import com.example.ktapplication.vehicle.domain.model.GearPosition
import com.example.ktapplication.vehicle.domain.model.ValueSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三路车况合并单测 —— 车联网 App 的核心业务规则都在这里。
 *
 * 断言目标是"优先级 + 新鲜度"这对组合：既要证明 CAN 在场时云端插不进来，
 * 也要证明车辆下电 CAN 静默之后云端能接管（否则远程车况页永远显示旧值）。
 */
class VehicleStatusAggregatorTest {

    private val clock = FakeMonotonicClock(100_000L)
    private val aggregator = VehicleStatusAggregator(clock, "LVSHCDAAXFA000123")
    private val speed = CanSignalCatalog.SPEED_KMH
    private val soc = CanSignalCatalog.PACK_SOC_PERCENT

    /** 用编码器造真帧再解码，测试里不出现手写位模式。 */
    private fun canSnapshot(
        publishedAt: Long,
        signalAt: Long,
        vararg pairs: Pair<CanSignalDefinition, Double>,
    ): VehicleCanSnapshot {
        val signals = LinkedHashMap<String, DecodedSignal>()
        pairs.forEach { (definition, physical) ->
            val frame = CanSignalEncoder.buildFrame(definition, listOf(physical), signalAt)
            signals[definition.name] = CanSignalDecoder.decode(frame, definition)
        }
        return VehicleCanSnapshot(signals, publishedAt)
    }

    /**
     * 喂一帧 CAN 快照给聚合器。
     *
     * vararg 放在最前：绝大多数用例只关心"信号 → 物理值"这一对，时刻用默认值就够；
     * 需要卡新鲜度阈值的用例再显式传 [publishedAt]/[signalAt]。
     */
    private fun applyCan(
        vararg pairs: Pair<CanSignalDefinition, Double>,
        publishedAt: Long = 100_000L,
        signalAt: Long = publishedAt - 10,
    ) = aggregator.applyCanSnapshot(
        snapshot = canSnapshot(publishedAt, signalAt, *pairs),
        busState = "STREAMING",
        frameRateHz = 50.0,
        dropRates = mapOf("CHASSIS" to 0.5),
    )

    private fun tspSnapshot(
        reported: Long = 100_000L,
        received: Long = reported,
        soc: Double? = null,
        speed: Double? = null,
        lockRaw: Int? = null,
        doors: Map<String, Int> = emptyMap(),
        latitude: Double? = null,
        longitude: Double? = null,
    ) = TspStatusSnapshot(
        vehicleId = "LVSHCDAAXFA000123",
        reportedAtMillis = reported,
        receivedAtMillis = received,
        stateOfChargePercent = soc,
        speedKilometresPerHour = speed,
        lockRaw = lockRaw,
        doorsRaw = doors,
        latitude = latitude,
        longitude = longitude,
        dataOrigin = "VEHICLE",
    )

    @Test
    fun `三路都没有值时不产出车况`() {
        assertNull(aggregator.build())
    }

    @Test
    fun `CAN 在场时优先于云端即使云端时间戳更新`() {
        applyCan(pairs = *arrayOf(speed to 90.0, soc to 80.0))
        aggregator.applyTspSnapshot(tspSnapshot(reported = 120_000L, received = 120_000L, soc = 60.0, speed = 0.0))

        assertEquals(ValueSource.CAN_BUS, aggregator.sourceOf(VehicleStatusAggregator.FieldKey.SPEED))
        val status = aggregator.build()
        assertNotNull(status)
        // 不做平均：90 与 0 取优先级高的 90，而不是 45
        assertEquals(90.0, status!!.speedKilometresPerHour.value!!, 1e-9)
        assertEquals(80.0, status.stateOfChargePercent.value!!, 1e-9)
        assertEquals(DataFreshness.FRESH, status.stateOfChargePercent.freshness)
    }

    @Test
    fun `CAN 陈旧后云端接管`() {
        // 信号时间比快照发布时间早 5s，超过 canStaleMillis 1s → 判陈旧
        applyCan(speed to 90.0, publishedAt = 100_000L, signalAt = 95_000L)
        assertEquals(
            DataFreshness.STALE,
            aggregator.build()!!.speedKilometresPerHour.freshness,
        )

        aggregator.applyTspSnapshot(tspSnapshot(reported = 99_000L, received = 100_000L, speed = 0.0))
        assertEquals(ValueSource.TSP_CLOUD, aggregator.sourceOf(VehicleStatusAggregator.FieldKey.SPEED))
        assertEquals(0.0, aggregator.build()!!.speedKilometresPerHour.value!!, 1e-9)
    }

    @Test
    fun `同源乱序时旧时间戳不能覆盖新值`() {
        applyCan(speed to 90.0, publishedAt = 100_000L, signalAt = 99_990L)
        // 晚到的旧帧（比如网关重传）必须被丢弃
        applyCan(speed to 30.0, publishedAt = 100_100L, signalAt = 90_000L)
        assertEquals(90.0, aggregator.build()!!.speedKilometresPerHour.value!!, 1e-9)
        assertEquals(ValueSource.CAN_BUS, aggregator.sourceOf(VehicleStatusAggregator.FieldKey.SPEED))
    }

    @Test
    fun `云端上报时间过旧时判陈旧且 updatedAt 用数据时间不是当前时间`() {
        clock.set(300_000L)
        aggregator.applyTspSnapshot(tspSnapshot(reported = 100_000L, received = 100_000L, soc = 55.0))
        val status = aggregator.build()!!
        assertEquals(DataFreshness.STALE, status.stateOfChargePercent.freshness)
        assertEquals(100_000L, status.updatedAtMillis)
        assertTrue(status.updatedAtMillis + MergePolicy().cloudStaleMillis < clock.nowMillis())
    }

    @Test
    fun `CarService 值在 CAN 缺失时生效`() {
        aggregator.applyCarSample(
            CarPropertySample(
                property = CarVehicleProperty.EV_BATTERY_LEVEL,
                areaId = GLOBAL_AREA_ID,
                value = PropertyValue.Float(77f),
                status = PropertyStatus.AVAILABLE,
                timestampMillis = 99_000L,
            ),
            gatewayState = "Ready",
        )
        assertEquals(77.0, aggregator.build()!!.stateOfChargePercent.value!!, 1e-6)
        assertEquals(ValueSource.CAR_SERVICE, aggregator.sourceOf(VehicleStatusAggregator.FieldKey.SOC))

        applyCan(soc to 80.0)
        assertEquals(ValueSource.CAN_BUS, aggregator.sourceOf(VehicleStatusAggregator.FieldKey.SOC))
        assertEquals(80.0, aggregator.build()!!.stateOfChargePercent.value!!, 1e-9)
    }

    @Test
    fun `VHAL 档位编码与 DBC 不同序`() {
        aggregator.applyCarSample(
            CarPropertySample(
                CarVehicleProperty.GEAR_SELECTION, GLOBAL_AREA_ID,
                PropertyValue.Int32(1), PropertyStatus.AVAILABLE, 99_000L,
            ),
            "Ready",
        )
        // AAOS GearDisplayPosition 的 1 是 D；若按 DBC 的 fromRaw 会错解成 R
        assertEquals(GearPosition.DRIVE, aggregator.build()!!.gear.value)
        assertEquals(GearPosition.REVERSE, GearPosition.fromRaw(1))

        aggregator.applyCanSnapshot(
            canSnapshot(100_000L, 99_990L, CanSignalCatalog.GEAR_POSITION to 0.0),
            "STREAMING", 50.0, emptyMap(),
        )
        // DBC 的 0 才是 P
        assertEquals(GearPosition.PARK, aggregator.build()!!.gear.value)
    }

    @Test
    fun `不可用样本不会污染字段`() {
        aggregator.applyCarSample(
            CarPropertySample.unavailable(CarVehicleProperty.PERF_VEHICLE_SPEED, GLOBAL_AREA_ID, 99_000L),
            "Ready",
        )
        val status = aggregator.build()
        assertNotNull(status)
        assertNull(status!!.speedKilometresPerHour.value)
        assertEquals(DataFreshness.UNAVAILABLE, status.speedKilometresPerHour.freshness)
        assertEquals(ValueSource.CAR_SERVICE, status.speedKilometresPerHour.source)
    }

    @Test
    fun `车门：CAN 有值时以 CAN 为准，缺失时云端补位`() {
        aggregator.applyTspSnapshot(tspSnapshot(doors = mapOf("FRONT_LEFT" to 1)))
        var status = aggregator.build()!!
        assertEquals(DoorState.OPEN, status.doors.getValue(DoorPosition.FRONT_LEFT).value)
        assertEquals(ValueSource.TSP_CLOUD, status.doors.getValue(DoorPosition.FRONT_LEFT).source)

        applyCan(CanSignalCatalog.DOOR_AJAR_FL to 0.0)
        status = aggregator.build()!!
        assertEquals(DoorState.CLOSED, status.doors.getValue(DoorPosition.FRONT_LEFT).value)
        assertEquals(ValueSource.CAN_BUS, status.doors.getValue(DoorPosition.FRONT_LEFT).source)
        assertTrue(status.allDoorsClosed)
    }

    @Test
    fun `云端门位置短码必须认得，未知 key 原样丢弃`() {
        // TSP 报文用 FL/FR/RL/RR/TRUNK 短码：只认全名的实现会让四个门集体"状态未知"，
        // 而字段本身是有值的，联调时极难发现。
        aggregator.applyTspSnapshot(
            tspSnapshot(doors = mapOf("FL" to 1, "FR" to 0, "RL" to 2, "RR" to 7, "TRUNK" to 0, "HOOD" to 1)),
        )
        val status = aggregator.build()!!
        assertEquals(DoorState.OPEN, status.doors.getValue(DoorPosition.FRONT_LEFT).value)
        assertEquals(DoorState.CLOSED, status.doors.getValue(DoorPosition.FRONT_RIGHT).value)
        assertEquals(DoorState.AJAR, status.doors.getValue(DoorPosition.REAR_LEFT).value)
        assertEquals(DoorState.UNKNOWN, status.doors.getValue(DoorPosition.REAR_RIGHT).value)
        assertEquals(DoorState.CLOSED, status.doors.getValue(DoorPosition.TRUNK).value)
        // 引擎盖不在模型里：不能因为报文多一个 key 就把整车状态判脏。
        assertFalse(status.doors.keys.any { it.name == "HOOD" })
        assertEquals(5, status.doors.size)
    }

    /** 独立建一个聚合器喂 CAN，用来验证"某一路缺席"的场景。 */
    private fun aggregatorWithCan(vararg pairs: Pair<CanSignalDefinition, Double>): VehicleStatusAggregator =
        VehicleStatusAggregator(clock, "X").apply {
            applyCanSnapshot(
                canSnapshot(100_000L, 99_990L, *pairs),
                "STREAMING", 50.0, emptyMap(),
            )
        }

    @Test
    fun `位置只在经纬度都有时可用`() {
        aggregator.applyTspSnapshot(tspSnapshot(latitude = 31.2304, longitude = 121.4737))
        val status = aggregator.build()!!
        assertNotNull(status.location)
        assertTrue(status.location!!.isUsable)
        assertEquals(31.2304, status.location!!.latitude.value!!, 1e-9)
        assertEquals(ValueSource.TSP_CLOUD, status.location!!.latitude.source)

        val noPosition = aggregatorWithCan(soc to 80.0).build()!!
        assertNull(noPosition.location)
    }

    @Test
    fun `充电判定看插枪与电流而不是看 SOC 上涨`() {
        applyCan(
            CanSignalCatalog.CHARGE_PLUGGED to 1.0,
            CanSignalCatalog.PACK_CURRENT_A to 120.0,
            CanSignalCatalog.CHARGE_POWER_KW to 6.5,
        )
        assertTrue(aggregator.build()!!.charging.isCharging)

        // 插枪但电流为负（车辆边充边放电/能量回收）：不能算在充电
        val discharging = aggregatorWithCan(
            CanSignalCatalog.CHARGE_PLUGGED to 1.0,
            CanSignalCatalog.PACK_CURRENT_A to -30.0,
            CanSignalCatalog.CHARGE_POWER_KW to 6.5,
        ).build()!!
        assertFalse(discharging.charging.isCharging)

        // 有电流但没插枪：属于行驶，不算充电
        val unplugged = aggregatorWithCan(
            CanSignalCatalog.CHARGE_PLUGGED to 0.0,
            CanSignalCatalog.PACK_CURRENT_A to 120.0,
            CanSignalCatalog.CHARGE_POWER_KW to 6.5,
        ).build()!!
        assertFalse(unplugged.charging.isCharging)
    }

    @Test
    fun `链路健康度随输入更新`() {
        applyCan(speed to 90.0)
        val health = aggregator.build()!!.signalHealth
        assertEquals("STREAMING", health.canBusState)
        assertEquals(50.0, health.frameRateHz, 1e-9)
        assertEquals(mapOf("CHASSIS" to 0.5), health.busDropRatesPercent)
        aggregator.markCloudOffline()
        assertEquals("OFFLINE", aggregator.build()!!.signalHealth.cloudState)
    }

    @Test
    fun `无效信号让字段变不可用而不是继续显示上一个可信值`() {
        applyCan(soc to 80.0)
        assertEquals(80.0, aggregator.build()!!.stateOfChargePercent.value!!, 1e-9)

        // 物理值 102% 对应的原始码正是 0xFF（无效码）：字段必须降级
        applyCan(soc to 102.0)
        val status = aggregator.build()!!
        assertEquals(DataFreshness.UNAVAILABLE, status.stateOfChargePercent.freshness)
        assertFalse(status.stateOfChargePercent.isUsable)
        // 值保留给诊断页看"最后一次可信值"，但业务侧 orDefault 必须走默认值
        assertEquals(80.0, status.stateOfChargePercent.value!!, 1e-9)
        assertEquals(0.0, status.stateOfChargePercent.orDefault(0.0), 1e-9)

        // 云端此时可以顶上（优先级低但新鲜）
        aggregator.applyTspSnapshot(tspSnapshot(soc = 55.0))
        val merged = aggregator.build()!!
        assertEquals(ValueSource.TSP_CLOUD, merged.stateOfChargePercent.source)
        assertEquals(55.0, merged.stateOfChargePercent.value!!, 1e-9)
    }
}
