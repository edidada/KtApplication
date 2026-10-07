package com.example.ktapplication.vehicle.domain.model

import com.example.ktapplication.vehicle.can.SignalQuality

/**
 * 车辆状态领域模型。
 *
 * 设计原则：**每个字段都自带可信度**。CAN/CarService/云端三条链路给同一个字段的
 * 新鲜度差别很大（车速 20ms、SOC 100ms、云端续航上报分钟级），如果不带质量标记，
 * UI 就只能"全都显示"，在实车上会造成"看起来能开但实际上是 3 分钟前的值"的问题。
 */

enum class GearPosition(val rawValue: Int, val displayName: String) {
    PARK(0, "P"),
    REVERSE(1, "R"),
    NEUTRAL(2, "N"),
    DRIVE(3, "D"),
    UNKNOWN(7, "--"),
    ;

    companion object {
        /** DBC 里最常见的编码：0=P 1=R 2=N 3=D，7/全 1 为无效。 */
        fun fromRaw(raw: Int?): GearPosition =
            entries.firstOrNull { it.rawValue == raw } ?: UNKNOWN

        /**
         * AAOS `VehicleProperty.GearDisplayPosition` 的编码：0=N 1=D 2=L 3=R 4=P。
         *
         * 这套顺序和 DBC 完全不一样，**不能复用 [fromRaw]**：VHAL 报 0（空挡）时按 DBC 解会
         * 得到 P 挡，于是"远程充电必须驻车"这类安全前置条件被错误放行 —— 属于会出事故的
         * 那种错位，所以两条链路各用一套映射，并在注释里写清来源。
         * 2=L（低速挡）本模型没建，按 UNKNOWN 处理比硬塞成 D 安全。
         */
        fun fromCarServiceRaw(raw: Int?): GearPosition = when (raw) {
            0 -> NEUTRAL
            1 -> DRIVE
            3 -> REVERSE
            4 -> PARK
            else -> UNKNOWN
        }
    }
}

enum class DoorState(val displayName: String) {
    CLOSED("已关"),
    AJAR("未关严"),
    OPEN("开启"),
    UNKNOWN("未知"),
}

enum class LockState(val rawValue: Int, val displayName: String) {
    UNLOCKED(0, "已解锁"),
    LOCKED(1, "已上锁"),
    PARTIAL_LOCKED(2, "部分上锁"),
    UNKNOWN(3, "未知"),
    ;

    companion object {
        fun fromRaw(raw: Int?): LockState = entries.firstOrNull { it.rawValue == raw } ?: UNKNOWN
    }
}

enum class SeatbeltState(val rawValue: Int, val displayName: String) {
    UNBUCKLED(0, "未系"),
    BUCKLED(1, "已系"),
    NOT_INSTALLED(2, "未安装"),
    UNKNOWN(3, "未知"),
    ;

    companion object {
        fun fromRaw(raw: Int?): SeatbeltState = entries.firstOrNull { it.rawValue == raw } ?: UNKNOWN
    }
}

enum class DoorPosition(
    val displayName: String,
    /** TSP 侧的门位置短码，见 [fromCloudKey]。 */
    private val cloudAliases: List<String>,
) {
    FRONT_LEFT("左前门", listOf("FL", "LF")),
    FRONT_RIGHT("右前门", listOf("FR", "RF")),
    REAR_LEFT("左后门", listOf("RL", "LR")),
    REAR_RIGHT("右后门", listOf("RR", "RH")),
    TRUNK("尾门", listOf("TR", "BOOT", "LUGGAGE")),
    ;

    /**
     * 把云端门位置 key 映射到枚举。
     *
     * TSP 报文普遍用 FL/FR/RL/RR/TRUNK 短码，而信号表与 App 内部用全名。
     * 如果只按全名匹配，四个车门的云端数据会**静默丢掉**（表现为"远程看车永远显示门状态未知"），
     * 这类问题在联调期最难发现，因为字段本身是有值的。
     */
    companion object {
        fun fromCloudKey(raw: String): DoorPosition? {
            val key = raw.trim().uppercase()
            if (key.isEmpty()) return null
            return entries.firstOrNull { it.name == key || it.cloudAliases.contains(key) }
                ?: entries.firstOrNull { it.name.startsWith(key) }
        }
    }
}

/** 数据来源，用于诊断页展示"这个值到底是从哪来的"。 */
enum class ValueSource { CAN_BUS, CAR_SERVICE, TSP_CLOUD, LOCAL_CACHE, SIMULATED }

/** 字段级新鲜度，UI 依据它决定是否灰显或显示 "--"。 */
enum class DataFreshness {
    FRESH,
    STALE,
    UNAVAILABLE,
    ;

    companion object {
        fun of(quality: SignalQuality, ageMillis: Long, thresholdMillis: Long): DataFreshness = when {
            quality == SignalQuality.NOT_AVAILABLE -> UNAVAILABLE
            quality == SignalQuality.STALE || ageMillis > thresholdMillis -> STALE
            quality != SignalQuality.VALID -> STALE
            else -> FRESH
        }
    }
}

/** 带来源与新鲜度的字段包装。 */
data class Telemetry<T>(
    val value: T?,
    val source: ValueSource,
    val updatedAtMillis: Long,
    val freshness: DataFreshness = DataFreshness.FRESH,
) {
    val isUsable: Boolean get() = freshness != DataFreshness.UNAVAILABLE && value != null

    /**
     * 取业务值。
     *
     * 注意这里**先看新鲜度再看值**：不可信字段（无效码、超时、从未建立）一律走 default 分支。
     * 如果只写 `value ?: default`，"车速拿不到"就会被当成"车速 0"，
     * 于是"行驶中禁止解锁"这类安全前置条件被绕过 —— 车机上这是会出事故的错误默认值。
     */
    fun orDefault(default: T): T = if (isUsable) value ?: default else default

    fun <R> map(transform: (T) -> R): Telemetry<R> = Telemetry(
        value = value?.let(transform),
        source = source,
        updatedAtMillis = updatedAtMillis,
        freshness = freshness,
    )

    companion object {
        fun <T : Any> fresh(value: T, source: ValueSource, nowMillis: Long): Telemetry<T> =
            Telemetry(value, source, nowMillis, DataFreshness.FRESH)

        /** 车机上"取不到值"是常态（下电后 CAN 静默、云端尚未上报）。用 null + freshness 表达，
         *  而不是塞 0 —— 仪表显示 0 km/h 在实车上是会被判严重问题的错误。 */
        fun <T : Any> missing(source: ValueSource, nowMillis: Long): Telemetry<T> =
            Telemetry(null, source, nowMillis, DataFreshness.UNAVAILABLE)
    }
}

data class ClimateStatus(
    val driverTargetCelsius: Telemetry<Double>,
    val passengerTargetCelsius: Telemetry<Double>,
    val fanLevel: Telemetry<Int>,
    val compressorOn: Boolean,
    val recirculation: Boolean,
)

data class ChargingStatus(
    val pluggedIn: Telemetry<Boolean>,
    val powerKilowatts: Telemetry<Double>,
    val currentAmperes: Telemetry<Double>,
    val packVoltageVolts: Telemetry<Double>,
    val chargePortLocked: Boolean,
) {
    /** 电流为正且插枪才认为在充电；只看 SOC 上升会误判（行驶中能量回收也会涨）。 */
    val isCharging: Boolean
        get() = pluggedIn.value == true &&
            currentAmperes.orDefault(0.0) > 0.5 &&
            powerKilowatts.orDefault(0.0) > 0.1
}

data class TyreStatus(
    val pressureKilopascals: Telemetry<Double>,
    val temperatureCelsius: Telemetry<Double>? = null,
)

/**
 * 位置信息。
 *
 * 车机上的定位有三个来源：整车 TBOX 通过 CAN/CarService 上报（最可信，因为车外天线）、
 * 座舱自身 GNSS、以及云端缓存。accuracyMeters 必须带上，远程寻车功能在没有精度信息时
 * 不应该画出一个看起来非常精确的图钉。
 */
data class VehicleLocation(
    val latitude: Telemetry<Double>,
    val longitude: Telemetry<Double>,
    val accuracyMeters: Float,
    /** true 表示来自整车（TBOX），false 表示座舱自身定位，后者在地下车库会漂。 */
    val fromVehicle: Boolean,
) {
    val isUsable: Boolean
        get() = latitude.isUsable && longitude.isUsable &&
            (latitude.value ?: 0.0) != 0.0 && (longitude.value ?: 0.0) != 0.0
}

/**
 * 一次车况合并后的完整快照。
 *
 * [updatedAtMillis] 是"最新可信数据"的时间而不是当前时间 —— 判断车况是否过期
 * 必须用这个值，否则断网时页面看起来永远是新鲜的。
 */
data class VehicleStatus(
    val vehicleId: String,
    val speedKilometresPerHour: Telemetry<Double>,
    val stateOfChargePercent: Telemetry<Double>,
    val remainingRangeKilometres: Telemetry<Double>,
    val odometerKilometres: Telemetry<Double>,
    val gear: Telemetry<GearPosition>,
    val lockState: Telemetry<LockState>,
    val doors: Map<DoorPosition, Telemetry<DoorState>>,
    val driverSeatbelt: Telemetry<SeatbeltState>,
    val climate: ClimateStatus,
    val charging: ChargingStatus,
    val lowBatteryVolts: Telemetry<Double>,
    val tyres: Map<DoorPosition, TyreStatus> = emptyMap(),
    val location: VehicleLocation? = null,
    val updatedAtMillis: Long,
    val signalHealth: SignalHealth,
) {
    val allDoorsClosed: Boolean
        get() = doors.isNotEmpty() && doors.values.all { it.value == DoorState.CLOSED }

    val anyDoorOpen: Boolean get() = doors.values.any { it.value != DoorState.CLOSED }

    val isParking: Boolean get() = gear.value == GearPosition.PARK

    /** 供远程控车前置校验：车速非零时不允许解锁/开充电口。 */
    val canAcceptUnsafeCommand: Boolean get() = (speedKilometresPerHour.value ?: 0.0) < 1.0
}

/** 三条链路各自的健康度，诊断页直接展示。 */
data class SignalHealth(
    val canBusState: String,
    val carServiceState: String,
    val cloudState: String,
    val staleSignals: List<String>,
    val frameRateHz: Double,
    val busDropRatesPercent: Map<String, Double>,
)
