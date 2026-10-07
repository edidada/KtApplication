package com.example.ktapplication.vehicle.domain.repo

import com.example.ktapplication.core.AppDispatchers
import com.example.ktapplication.core.MonotonicClock
import com.example.ktapplication.vehicle.can.CanSignalCatalog
import com.example.ktapplication.vehicle.can.VehicleCanSnapshot
import com.example.ktapplication.vehicle.carservice.CarPropertySample
import com.example.ktapplication.vehicle.carservice.CarVehicleProperty
import com.example.ktapplication.vehicle.carservice.PropertyValue
import com.example.ktapplication.vehicle.data.TspStatusSnapshot
import com.example.ktapplication.vehicle.domain.model.ChargingStatus
import com.example.ktapplication.vehicle.domain.model.ClimateStatus
import com.example.ktapplication.vehicle.domain.model.DataFreshness
import com.example.ktapplication.vehicle.domain.model.DoorPosition
import com.example.ktapplication.vehicle.domain.model.DoorState
import com.example.ktapplication.vehicle.domain.model.GearPosition
import com.example.ktapplication.vehicle.domain.model.LockState
import com.example.ktapplication.vehicle.domain.model.SeatbeltState
import com.example.ktapplication.vehicle.domain.model.SignalHealth
import com.example.ktapplication.vehicle.domain.model.Telemetry
import com.example.ktapplication.vehicle.domain.model.ValueSource
import com.example.ktapplication.vehicle.domain.model.VehicleLocation
import com.example.ktapplication.vehicle.domain.model.VehicleStatus

/**
 * 车况合并器：CAN / CarService / TSP 三条链路写进同一份车况。
 *
 * 合并规则（这是车联网 App 最容易被面试追问的业务点）：
 *  1. **同源比时间戳，异源比优先级**：同一条链路晚到覆盖早到；跨链路按
 *     CAN > CAR_SERVICE > TSP > LOCAL_CACHE 的优先级取，但低优先级源只有在
 *     高优先级源"陈旧或缺失"时才能顶上。
 *  2. **云端时间戳不可信**：TSP 的 reportTime 是车辆上报时间，网络重传/云端缓存
 *     会让它比 receivedAt 早几分钟，所以判断陈旧用 max(reported, received) 与本地时钟比较，
 *     并且给云端额外宽限（[MergePolicy.cloudGraceMillis]）。
 *  3. **不做算术平均**：SOC 从 CAN 是 86.4、云端 86 时，取优先级高者而不是 (86.4+86)/2，
 *     平均会把精度差异抹平成"看起来在跳数"。
 */
class VehicleStatusAggregator(
    private val clock: MonotonicClock,
    private val vehicleId: String,
    private val policy: MergePolicy = MergePolicy(),
) {

    /** 字段键：与 UI 展示项一一对应，避免合并逻辑用字符串散落。 */
    enum class FieldKey {
        SPEED, SOC, RANGE, ODOMETER, GEAR, LOCK, SEATBELT,
        HVAC_DRIVER_TEMP, HVAC_PASSENGER_TEMP, HVAC_FAN, HVAC_POWER,
        PLUGGED, CHARGE_POWER, CHARGE_CURRENT, PACK_VOLTAGE, LOW_BATTERY, LOCATION,
    }

    private data class FieldState(val telemetry: Telemetry<Any?>)

    private val fields = LinkedHashMap<FieldKey, FieldState>()
    private val doors = LinkedHashMap<DoorPosition, Telemetry<DoorState>>()
    private var canBusState: String = "DISCONNECTED"
    private var carServiceState: String = "Disconnected"
    private var cloudState: String = "OFFLINE"
    private var frameRateHz: Double = 0.0
    private var busDropRates: Map<String, Double> = emptyMap()
    private var staleSignals: List<String> = emptyList()

    /** 同一字段来自同一源时，是否允许旧时间戳覆盖新值。 */
    /**
     * 同一字段是否接受新值。
     *
     * 三条规则（顺序即优先级）：
     *  1. 优先级更高的源**总是**覆盖 —— CAN 来了就不理云端；
     *  2. 同源比时间戳，晚到覆盖早到（乱序到达的旧值必须丢弃）；
     *  3. 优先级更低的源只有在现有值**已经不新鲜**时才允许顶上，并且必须比现有值更新。
     *     这一条是"车辆下电后 CAN 静默、云端接管"的关键：不是靠优先级，而是靠新鲜度让位。
     */
    private fun accept(incomingSource: ValueSource, incomingTimestamp: Long, current: FieldState?): Boolean {
        if (current == null) return true
        val existing = current.telemetry
        val existingRank = policy.rankOf(existing.source)
        val incomingRank = policy.rankOf(incomingSource)
        return when {
            incomingRank < existingRank -> true
            incomingRank > existingRank ->
                existing.freshness != DataFreshness.FRESH && incomingTimestamp >= effectiveTime(existing)

            else -> incomingTimestamp >= existing.updatedAtMillis
        }
    }

    private fun effectiveTime(telemetry: Telemetry<*>): Long =
        if (telemetry.source == ValueSource.TSP_CLOUD) telemetry.updatedAtMillis + policy.cloudGraceMillis
        else telemetry.updatedAtMillis

    private fun put(key: FieldKey, value: Any?, source: ValueSource, timestamp: Long, freshness: DataFreshness) {
        // 显式写 Telemetry<Any?>：Telemetry<T> 的 value 是 T?，字面推断会把 T 收敛成 Any，
        // 而 Telemetry 是不变的（value 既读又写、还参与 equals），Telemetry<Any> 并不是
        // Telemetry<Any?> 的子类型，塞进 FieldState 就编译不过。
        // 不要改成给 Telemetry 加 out 变参：build() 里 read<T> 会按目标类型反向读回具体 T，
        // 协变只会把这一处的显式标注换成全工程范围的隐式风险。
        val telemetry = Telemetry<Any?>(value, source, timestamp, freshness)
        val current = fields[key]
        if (!accept(source, timestamp, current)) return
        fields[key] = FieldState(telemetry)
    }

    /**
     * 把字段标记为不可用，但保留最后一次可信值。
     *
     * 为什么保留：诊断页要能回答"上一次看到它是多少"，这是排查"仪表突然变 --"的必需信息；
     * 为什么要标记：座舱显示层只认 [DataFreshness]，不标记就会把失效值继续当实时值画出来。
     */
    private fun markUnavailable(key: FieldKey, source: ValueSource, timestamp: Long, freshness: DataFreshness) {
        val existing = fields[key]?.telemetry
        if (existing != null && existing.source == source && timestamp < existing.updatedAtMillis) return
        if (existing != null && policy.rankOf(source) > policy.rankOf(existing.source) &&
            existing.freshness == DataFreshness.FRESH
        ) {
            return
        }
        fields[key] = FieldState(Telemetry(existing?.value, source, timestamp, freshness))
    }

    // ---------------- 三路输入 ----------------

    fun applyCanSnapshot(snapshot: VehicleCanSnapshot, busState: String, frameRateHz: Double, dropRates: Map<String, Double>) {
        this.canBusState = busState
        this.frameRateHz = frameRateHz
        this.busDropRates = dropRates
        this.staleSignals = snapshot.unreliableSignals

        fun field(name: String): Pair<Double?, Long> {
            val signal = snapshot[name] ?: return null to snapshot.publishedAtMillis
            return signal.physicalValue to signal.receivedAtMillis
        }

        fun apply(name: String, key: FieldKey, transform: (Double) -> Any? = { it }) {
            val (physical, timestamp) = field(name)
            val signal = snapshot[name]
            val freshness = freshnessFor(signal?.quality?.name, snapshot.publishedAtMillis - timestamp, policy.canStaleMillis)
            when {
                physical != null -> put(key, transform(physical), ValueSource.CAN_BUS, timestamp, freshness)
                freshness == DataFreshness.UNAVAILABLE ->
                    // 信号显式无效（全 1 码 / 解析错 / 从未建立）：字段要降级，不能继续显示上一个可信值。
                    // 旧值仍然保留在 telemetry.value 里，供诊断页显示"最后一次可信值 + 已失效"。
                    markUnavailable(key, ValueSource.CAN_BUS, timestamp, freshness)

                else -> put(key, null, ValueSource.CAN_BUS, timestamp, freshness)
            }
        }

        apply(CanSignalCatalog.SPEED_KMH.name, FieldKey.SPEED)
        apply(CanSignalCatalog.PACK_SOC_PERCENT.name, FieldKey.SOC)
        apply(CanSignalCatalog.EV_RANGE_KM.name, FieldKey.RANGE)
        apply(CanSignalCatalog.ODOMETER_KM.name, FieldKey.ODOMETER)
        apply(CanSignalCatalog.GEAR_POSITION.name, FieldKey.GEAR) { GearPosition.fromRaw(it.toInt()) }
        apply(CanSignalCatalog.LOCK_STATE.name, FieldKey.LOCK) { LockState.fromRaw(it.toInt()) }
        apply(CanSignalCatalog.SEATBELT_BUCKLED_DRIVER.name, FieldKey.SEATBELT) { SeatbeltState.fromRaw(it.toInt()) }
        apply(CanSignalCatalog.HVAC_TEMP_DRIVER_C.name, FieldKey.HVAC_DRIVER_TEMP)
        apply(CanSignalCatalog.HVAC_TEMP_PASSENGER_C.name, FieldKey.HVAC_PASSENGER_TEMP)
        apply(CanSignalCatalog.HVAC_FAN_LEVEL.name, FieldKey.HVAC_FAN) { it.toInt() }
        apply(CanSignalCatalog.CHARGE_PLUGGED.name, FieldKey.PLUGGED) { it != 0.0 }
        apply(CanSignalCatalog.CHARGE_POWER_KW.name, FieldKey.CHARGE_POWER)
        apply(CanSignalCatalog.PACK_CURRENT_A.name, FieldKey.CHARGE_CURRENT)
        apply(CanSignalCatalog.PACK_VOLTAGE_V.name, FieldKey.PACK_VOLTAGE)
        apply(CanSignalCatalog.LOW_BATTERY_POWER_C.name, FieldKey.LOW_BATTERY)

        // 车门是布尔位信号，单独处理。
        listOf(
            CanSignalCatalog.DOOR_AJAR_FL.name to DoorPosition.FRONT_LEFT,
            CanSignalCatalog.DOOR_AJAR_FR.name to DoorPosition.FRONT_RIGHT,
            CanSignalCatalog.DOOR_AJAR_RL.name to DoorPosition.REAR_LEFT,
            CanSignalCatalog.DOOR_AJAR_RR.name to DoorPosition.REAR_RIGHT,
        ).forEach { (name, position) ->
            val signal = snapshot[name] ?: return@forEach
            val state = when (signal.rawValue) {
                null -> DoorState.UNKNOWN
                0L -> DoorState.CLOSED
                1L -> DoorState.OPEN
                else -> DoorState.AJAR
            }
            doors[position] = Telemetry(
                value = state,
                source = ValueSource.CAN_BUS,
                updatedAtMillis = signal.receivedAtMillis,
                freshness = freshnessFor(signal.quality.name, snapshot.publishedAtMillis - signal.receivedAtMillis, policy.canStaleMillis),
            )
        }
    }

    fun applyCarSample(sample: CarPropertySample, gatewayState: String) {
        this.carServiceState = gatewayState
        val timestamp = sample.timestampMillis
        val freshness = if (!sample.isUsable) DataFreshness.UNAVAILABLE
        else freshnessFor(sample.status.name, clock.nowMillis() - timestamp, policy.carServiceStaleMillis)
        val numeric = sample.value?.asDoubleOrNull()

        when (sample.property) {
            CarVehicleProperty.PERF_VEHICLE_SPEED -> put(FieldKey.SPEED, numeric, ValueSource.CAR_SERVICE, timestamp, freshness)
            CarVehicleProperty.EV_BATTERY_LEVEL -> put(FieldKey.SOC, numeric, ValueSource.CAR_SERVICE, timestamp, freshness)
            CarVehicleProperty.DRIVETRAIN_RANGE -> put(FieldKey.RANGE, numeric, ValueSource.CAR_SERVICE, timestamp, freshness)
            CarVehicleProperty.ODOMETER -> put(FieldKey.ODOMETER, numeric, ValueSource.CAR_SERVICE, timestamp, freshness)
            CarVehicleProperty.GEAR_SELECTION, CarVehicleProperty.DRIVETRAIN_GEAR_SELECTED ->
                // 注意：VHAL 的档位枚举与 DBC 不同序，必须走 fromCarServiceRaw。
                put(FieldKey.GEAR, GearPosition.fromCarServiceRaw(sample.value?.asIntOrNull()), ValueSource.CAR_SERVICE, timestamp, freshness)
            CarVehicleProperty.DOOR_LOCK ->
                put(FieldKey.LOCK, if (sample.value?.asBooleanOrNull() == true) LockState.LOCKED else LockState.UNLOCKED, ValueSource.CAR_SERVICE, timestamp, freshness)
            CarVehicleProperty.HVAC_TEMPERATURE_SET -> put(FieldKey.HVAC_DRIVER_TEMP, numeric, ValueSource.CAR_SERVICE, timestamp, freshness)
            CarVehicleProperty.HVAC_FAN_SPEED -> put(FieldKey.HVAC_FAN, sample.value?.asIntOrNull(), ValueSource.CAR_SERVICE, timestamp, freshness)
            CarVehicleProperty.HVAC_POWER_ON -> put(FieldKey.HVAC_POWER, sample.value?.asBooleanOrNull(), ValueSource.CAR_SERVICE, timestamp, freshness)
            CarVehicleProperty.CHARGE_PORT_SWITCH -> put(FieldKey.PLUGGED, sample.value?.asBooleanOrNull(), ValueSource.CAR_SERVICE, timestamp, freshness)
            CarVehicleProperty.BMS_CHARGE_CURRENT -> put(FieldKey.CHARGE_CURRENT, numeric, ValueSource.CAR_SERVICE, timestamp, freshness)
            CarVehicleProperty.LOW_VOLTAGE_BATTERY_LEVEL -> put(FieldKey.LOW_BATTERY, numeric, ValueSource.CAR_SERVICE, timestamp, freshness)
            CarVehicleProperty.SEATBELT_BUCKLED -> put(FieldKey.SEATBELT, SeatbeltState.fromRaw(sample.value?.asIntOrNull()), ValueSource.CAR_SERVICE, timestamp, freshness)
            else -> Unit
        }
    }

    fun applyTspSnapshot(dto: TspStatusSnapshot) {
        this.cloudState = "ONLINE"
        // 云端上报时间可能早于本地时钟，用 reported 做新鲜度判断，但落库时间戳取更晚的那个。
        val effective = maxOf(dto.reportedAtMillis, dto.receivedAtMillis)
        val freshness = if (effective + policy.cloudStaleMillis < clock.nowMillis()) DataFreshness.STALE else DataFreshness.FRESH

        fun putIfPresent(key: FieldKey, value: Any?) {
            if (value != null) put(key, value, ValueSource.TSP_CLOUD, effective, freshness)
        }

        putIfPresent(FieldKey.SOC, dto.stateOfChargePercent)
        putIfPresent(FieldKey.RANGE, dto.remainingRangeKilometres)
        putIfPresent(FieldKey.ODOMETER, dto.odometerKilometres)
        putIfPresent(FieldKey.SPEED, dto.speedKilometresPerHour)
        dto.gearRaw?.let { putIfPresent(FieldKey.GEAR, GearPosition.fromRaw(it)) }
        dto.lockRaw?.let { putIfPresent(FieldKey.LOCK, LockState.fromRaw(it)) }
        dto.driverSeatbeltRaw?.let { putIfPresent(FieldKey.SEATBELT, SeatbeltState.fromRaw(it)) }
        putIfPresent(FieldKey.HVAC_DRIVER_TEMP, dto.targetTemperatureCelsius)
        putIfPresent(FieldKey.HVAC_FAN, dto.fanLevel)
        putIfPresent(FieldKey.HVAC_POWER, dto.airConditionerOn)
        putIfPresent(FieldKey.PLUGGED, dto.pluggedIn)
        putIfPresent(FieldKey.CHARGE_POWER, dto.chargingPowerKilowatts)
        putIfPresent(FieldKey.CHARGE_CURRENT, dto.chargingCurrentAmperes)
        putIfPresent(FieldKey.PACK_VOLTAGE, dto.packVoltageVolts)
        putIfPresent(FieldKey.LOW_BATTERY, dto.lowBatteryVolts)

        dto.doorsRaw.forEach { (rawPosition, raw) ->
            // 云端短码（FL/FR/…）与内部全名都要认，否则门状态会被静默丢弃。
            val position = DoorPosition.fromCloudKey(rawPosition) ?: return@forEach
            val state = when (raw) {
                0 -> DoorState.CLOSED
                1 -> DoorState.OPEN
                2 -> DoorState.AJAR
                else -> DoorState.UNKNOWN
            }
            val existing = doors[position]
            // 车门这种"事件型"字段：CAN 在场时以 CAN 为准，云端只在 CAN 缺失时补位。
            if (existing == null || existing.source == ValueSource.TSP_CLOUD || existing.freshness != DataFreshness.FRESH) {
                doors[position] = Telemetry(state, ValueSource.TSP_CLOUD, effective, freshness)
            }
        }

        if (dto.latitude != null && dto.longitude != null) {
            put(
                FieldKey.LOCATION,
                VehicleLocation(
                    latitude = Telemetry(dto.latitude, ValueSource.TSP_CLOUD, effective, freshness),
                    longitude = Telemetry(dto.longitude, ValueSource.TSP_CLOUD, effective, freshness),
                    accuracyMeters = dto.accuracyMeters ?: 0f,
                    fromVehicle = dto.dataOrigin?.equals("VEHICLE", ignoreCase = true) ?: false,
                ),
                ValueSource.TSP_CLOUD,
                effective,
                freshness,
            )
        }
    }

    fun markCloudOffline() {
        cloudState = "OFFLINE"
    }

    // ---------------- 输出 ----------------

    fun build(): VehicleStatus? {
        val now = clock.nowMillis()
        fun <T> read(key: FieldKey): Telemetry<T> {
            val telemetry = fields[key]?.telemetry as Telemetry<T>?
            return telemetry ?: Telemetry(null, ValueSource.LOCAL_CACHE, now, DataFreshness.UNAVAILABLE)
        }

        val speed = read<Double>(FieldKey.SPEED)
        val soc = read<Double>(FieldKey.SOC)
        // 三路都没给过任何值时不产出状态：UI 显示"暂无车况"比显示一堆 0 更安全。
        // 判空要把 doors 也算进来：车门是事件型字段，只落 doors 不落 fields，
        // 而云端经常只回一条"门状态变化"报文 —— 只看 speed/soc 会把这种合法报文误判成没数据，
        // 远程车况页就永远停在"暂无车况"，用户以为 App 坏了。
        if (fields.isEmpty() && doors.isEmpty()) return null

        return VehicleStatus(
            vehicleId = vehicleId,
            speedKilometresPerHour = speed,
            stateOfChargePercent = soc,
            remainingRangeKilometres = read(FieldKey.RANGE),
            odometerKilometres = read(FieldKey.ODOMETER),
            gear = read(FieldKey.GEAR) ?: Telemetry(GearPosition.UNKNOWN, ValueSource.LOCAL_CACHE, now, DataFreshness.UNAVAILABLE),
            lockState = read(FieldKey.LOCK) ?: Telemetry(LockState.UNKNOWN, ValueSource.LOCAL_CACHE, now, DataFreshness.UNAVAILABLE),
            doors = doors.toMap(),
            driverSeatbelt = read(FieldKey.SEATBELT) ?: Telemetry(SeatbeltState.UNKNOWN, ValueSource.LOCAL_CACHE, now, DataFreshness.UNAVAILABLE),
            climate = ClimateStatus(
                driverTargetCelsius = read(FieldKey.HVAC_DRIVER_TEMP),
                passengerTargetCelsius = read(FieldKey.HVAC_PASSENGER_TEMP),
                fanLevel = read(FieldKey.HVAC_FAN),
                compressorOn = read<Boolean>(FieldKey.HVAC_POWER).value ?: false,
                recirculation = false,
            ),
            charging = ChargingStatus(
                pluggedIn = read<Boolean>(FieldKey.PLUGGED) ?: Telemetry(false, ValueSource.LOCAL_CACHE, now, DataFreshness.UNAVAILABLE),
                powerKilowatts = read(FieldKey.CHARGE_POWER),
                currentAmperes = read(FieldKey.CHARGE_CURRENT),
                packVoltageVolts = read(FieldKey.PACK_VOLTAGE),
                chargePortLocked = read<Boolean>(FieldKey.PLUGGED).value != true,
            ),
            lowBatteryVolts = read(FieldKey.LOW_BATTERY),
            location = read<VehicleLocation>(FieldKey.LOCATION).value,
            updatedAtMillis = fields.values.maxOfOrNull { it.telemetry.updatedAtMillis } ?: now,
            signalHealth = SignalHealth(
                canBusState = canBusState,
                carServiceState = carServiceState,
                cloudState = cloudState,
                staleSignals = staleSignals,
                frameRateHz = frameRateHz,
                busDropRatesPercent = busDropRates,
            ),
        )
    }

    /** 供单测：直接看某个字段最终来自哪一路。 */
    fun sourceOf(key: FieldKey): ValueSource? = fields[key]?.telemetry?.source

    private fun freshnessFor(qualityName: String?, ageMillis: Long, thresholdMillis: Long): DataFreshness = when (qualityName) {
        null -> DataFreshness.UNAVAILABLE
        "NOT_AVAILABLE" -> DataFreshness.UNAVAILABLE
        "INVALID_PATTERN", "DECODE_ERROR" -> DataFreshness.UNAVAILABLE
        "STALE", "OUT_OF_RANGE" -> DataFreshness.STALE
        else -> if (ageMillis > thresholdMillis) DataFreshness.STALE else DataFreshness.FRESH
    }
}

/**
 * 合并策略。
 *
 * rank 越小优先级越高。这个顺序在真实项目里由架构评审定死：
 * CAN 是整车最原始的真相，CarService 属性是它经过 VHAL 的一级加工，
 * TSP 是分钟级的远端缓存，LOCAL_CACHE 是上次成功结果的残值。
 */
data class MergePolicy(
    val rankOfSource: Map<ValueSource, Int> = mapOf(
        ValueSource.CAN_BUS to 0,
        ValueSource.CAR_SERVICE to 1,
        ValueSource.TSP_CLOUD to 2,
        ValueSource.SIMULATED to 3,
        ValueSource.LOCAL_CACHE to 4,
    ),
    val canStaleMillis: Long = 1_000L,
    val carServiceStaleMillis: Long = 2_000L,
    val cloudStaleMillis: Long = 90_000L,
    /** 云端时间戳允许的额外宽限，避免网络重传把有效值判成陈旧。 */
    val cloudGraceMillis: Long = 30_000L,
) {
    fun rankOf(source: ValueSource): Int = rankOfSource[source] ?: 9
}

/** 车况仓库契约。 */
interface VehicleStatusRepository {
    /** 合并后的整车状态；null 表示三路都没给出可信值。 */
    val status: kotlinx.coroutines.flow.StateFlow<VehicleStatus?>

    /** 三路链路健康度独立暴露，诊断页与状态页共享同一个事实来源。 */
    val health: kotlinx.coroutines.flow.StateFlow<SignalHealth>

    /** 主动拉一次云端状态（下拉刷新、控车后回读）。 */
    suspend fun refreshFromCloud(force: Boolean = false): com.example.ktapplication.core.Outcome<TspStatusSnapshot>

    /** 是否允许在 CAN/CarService 都静默时回落云端（车辆下电后必须允许）。 */
    var cloudFallbackEnabled: Boolean
}

/** PropertyValue 到数值的小工具，聚合器和仓库共用。 */
internal fun PropertyValue?.asNumber(): Double? = this?.asDoubleOrNull()
