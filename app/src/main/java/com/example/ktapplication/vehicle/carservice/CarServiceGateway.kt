package com.example.ktapplication.vehicle.carservice

import com.example.ktapplication.core.DomainError
import com.example.ktapplication.core.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Android Automotive / 车机 CarService 的属性接入层。
 *
 * 与 CAN 层的分工要说清楚（面试常问）：
 *  - CAN 层拿到的是**原始报文**，需要自己按 DBC 解码，只有车机厂私有服务才给；
 *  - CarService 的 `CarPropertyManager` 拿到的是**已解码的属性值**（VHAL 已经做完信号转换），
 *    有 area（主驾/副驾/左后…）概念，有 ACCESS_RESTRICTED / SIGNATURE|PREMISSION 权限分级，
 *    而且**不是所有属性都暴露给三方 App**。
 * 座舱 App 的正确做法是：优先用 CarService 属性，拿不到（属性不支持或无权限）时
 * 回落到私有 CAN 通道，两路都失败才显示"不可用"。这个回落链在 domain 层的 repository 里实现。
 */

/** 属性值类型，对齐 AOSP 的 VehiclePropertyType。 */
enum class PropertyValueType { BOOLEAN, INT32, INT64, FLOAT, DOUBLE, STRING, FLOAT_ARRAY, MIXED }

/** 属性变化模式：CONTINUOUS/ON_CHANGE/ON_SEE 决定了订阅频率和是否值得共享流。 */
enum class PropertyChangeMode { CONTINUOUS_POLLING, ON_CHANGE, ON_REQUEST }

/** 采样频率，对齐 VehiclePropertyRate。 */
enum class PropertyRate(val hz: Float) { UNSET(0f), NORMAL(1f), FAST(10f), SLEEP(0.1f), FASTEST(50f) }

/**
 * 本 App 用到的车辆属性。
 *
 * [androidCarFieldName] 存的是 `android.car.hardware.property.VehiclePropertyIds` 里的常量名，
 * 反射实现按**名字**取值的理由有两个：
 *  1. 不同 Android Automotive 版本（12/13/14/15）里部分常量被移动或改名（例如 GEAR_SELECTION
 *     在 AAOS 12 起被 DRIVETRAIN_GEAR_SELECTED 取代），硬编码 int 值会在升级后静默错位；
 *  2. 本项目没有车机 framework jar，硬编码数值也无从校验。
 * 名称取不到时属性会被标记为不支持，走 CAN 回落，而不是崩溃。
 */
enum class CarVehicleProperty(
    val displayName: String,
    val androidCarFieldName: String,
    val valueType: PropertyValueType,
    val areaScoped: Boolean = false,
    val changeMode: PropertyChangeMode = PropertyChangeMode.ON_CHANGE,
    val rate: PropertyRate = PropertyRate.NORMAL,
    /** ACCESS_RESTRICTED 的属性三方 App 需要系统权限或 OEM 签名。 */
    val accessRestricted: Boolean = false,
) {
    PERF_VEHICLE_SPEED("车速", "PERF_VEHICLE_SPEED", PropertyValueType.FLOAT, changeMode = PropertyChangeMode.CONTINUOUS_POLLING, rate = PropertyRate.FAST, accessRestricted = true),
    ENGINE_RPM("发动机转速", "ENGINE_RPM", PropertyValueType.FLOAT, changeMode = PropertyChangeMode.CONTINUOUS_POLLING, rate = PropertyRate.FAST, accessRestricted = true),
    EV_BATTERY_LEVEL("动力电池 SOC", "EV_BATTERY_LEVEL", PropertyValueType.FLOAT),
    DRIVETRAIN_RANGE("剩余续航", "DRIVETRAIN_RANGE", PropertyValueType.FLOAT),
    ODOMETER("总里程", "ODOMETER", PropertyValueType.FLOAT, accessRestricted = true),
    GEAR_SELECTION("档位", "GEAR_SELECTION", PropertyValueType.INT32),
    DRIVETRAIN_GEAR_SELECTED("档位（AAOS12+）", "DRIVETRAIN_GEAR_SELECTED", PropertyValueType.INT32),
    HVAC_TEMPERATURE_SET("空调温度", "HVAC_TEMPERATURE_SET", PropertyValueType.FLOAT, areaScoped = true),
    HVAC_FAN_SPEED("风量", "HVAC_FAN_SPEED", PropertyValueType.INT32, areaScoped = true),
    HVAC_POWER_ON("空调开关", "HVAC_POWER_ON", PropertyValueType.BOOLEAN, areaScoped = true),
    DOOR_LOCK("门锁", "DOOR_LOCK", PropertyValueType.BOOLEAN, areaScoped = true),
    DOOR_POS("车门开闭", "DOOR_POS", PropertyValueType.INT32, areaScoped = true),
    TRUNK_STATE("后备箱", "TRUNK_STATE", PropertyValueType.INT32, areaScoped = true),
    SEATBELT_BUCKLED("安全带", "SEATBELT_BUCKLED", PropertyValueType.INT32, areaScoped = true),
    CHARGE_PORT_SWITCH("充电口盖", "CHARGE_PORT_SWITCH", PropertyValueType.BOOLEAN),
    CHARGE_STATE("充电状态", "CHARGE_STATE", PropertyValueType.INT32),
    BMS_CHARGE_CURRENT("充电电流", "BMS_CHARGE_CURRENT", PropertyValueType.FLOAT),
    LOW_VOLTAGE_BATTERY_LEVEL("小电瓶电压", "LOW_VOLTAGE_BATTERY_LEVEL", PropertyValueType.FLOAT),
    ;

    companion object {
        /** 兼容版本差异：档位这类改名属性按候选名顺序查找。 */
        fun gearCandidates(): List<CarVehicleProperty> = listOf(DRIVETRAIN_GEAR_SELECTED, GEAR_SELECTION)

        fun byCarFieldName(fieldName: String): CarVehicleProperty? = entries.firstOrNull { it.androidCarFieldName == fieldName }
    }
}

/** 全局区域 id，对齐 VehicleAreaSection/ GLOBAL_AREA 的取值 0。 */
const val GLOBAL_AREA_ID: Int = 0

/**
 * VHAL 属性值。
 *
 * 注意每个分支的 `value` 都写成 `kotlin.X`：嵌套类名 Float/Double/FloatArray 会把
 * 同名标准库类型遮蔽掉，直接写 `val value: Double` 会解析成 `PropertyValue.Double` 自己，
 * 整个密封类的类型推导会连带崩掉（VHAL 侧真实类型就是这七种，不能改名迁就语法）。
 */
sealed class PropertyValue {
    data class Bool(val value: kotlin.Boolean) : PropertyValue()
    data class Int32(val value: kotlin.Int) : PropertyValue()
    data class Int64(val value: kotlin.Long) : PropertyValue()
    data class Float(val value: kotlin.Float) : PropertyValue()
    data class Double(val value: kotlin.Double) : PropertyValue()
    data class Str(val value: kotlin.String) : PropertyValue()
    data class FloatArray(val value: kotlin.FloatArray) : PropertyValue()

    fun asDoubleOrNull(): kotlin.Double? = when (this) {
        is Bool -> if (value) 1.0 else 0.0
        is Int32 -> value.toDouble()
        is Int64 -> value.toDouble()
        is Float -> value.toDouble()
        is Double -> value
        is Str -> value.toDoubleOrNull()
        is FloatArray -> value.firstOrNull()?.toDouble()
    }

    fun asIntOrNull(): kotlin.Int? = asDoubleOrNull()?.toInt()

    fun asStringOrNull(): kotlin.String? = when (this) {
        is Str -> value
        else -> asDoubleOrNull()?.toString()
    }

    fun asBooleanOrNull(): kotlin.Boolean? = when (this) {
        is Bool -> value
        else -> asDoubleOrNull()?.let { it != 0.0 }
    }

    /** 写入前把领域值收敛成 VHAL 期望的类型，避免 setFloatProperty 用在 BOOLEAN 属性上。 */
    fun coercedTo(type: PropertyValueType): PropertyValue = when (type) {
        PropertyValueType.BOOLEAN -> Bool(asBooleanOrNull() ?: false)
        PropertyValueType.INT32 -> Int32(asIntOrNull() ?: 0)
        PropertyValueType.INT64 -> Int64(asDoubleOrNull()?.toLong() ?: 0L)
        PropertyValueType.FLOAT -> Float(asDoubleOrNull()?.toFloat() ?: 0f)
        PropertyValueType.DOUBLE -> Double(asDoubleOrNull() ?: 0.0)
        PropertyValueType.STRING -> Str(asStringOrNull() ?: "")
        PropertyValueType.FLOAT_ARRAY -> FloatArray(
            when (this) {
                is FloatArray -> value
                else -> floatArrayOf(asDoubleOrNull()?.toFloat() ?: 0f)
            },
        )
        PropertyValueType.MIXED -> this
    }
}

/** VHAL 上报的属性状态，对齐 VehiclePropertyStatus。 */
enum class PropertyStatus { AVAILABLE, NOT_AVAILABLE, UNAVAILABLE, ERROR }

data class CarPropertySample(
    val property: CarVehicleProperty,
    val areaId: Int,
    val value: PropertyValue?,
    val status: PropertyStatus,
    val timestampMillis: Long,
) {
    val isUsable: Boolean get() = status == PropertyStatus.AVAILABLE && value != null

    val numericValue: Double? get() = if (isUsable) value?.asDoubleOrNull() else null

    companion object {
        fun unavailable(property: CarVehicleProperty, areaId: Int, timestampMillis: Long, status: PropertyStatus = PropertyStatus.NOT_AVAILABLE) =
            CarPropertySample(property, areaId, null, status, timestampMillis)
    }
}

/**
 * CarService 连接状态。
 *
 * [Reconnecting] 单独存在是因为车机上 CarService 真的会死（车机进程被系统重启、OTA、
 * VHAL 崩溃），Binder 死亡回调必须触发带退避的重连，而不是让 App 停在"无信号"。
 */
sealed class CarServiceState {
    data object Disconnected : CarServiceState()
    data object Connecting : CarServiceState()

    /**
     * @param supportedProperties 由 `getPropertyList()` 实际返回的属性集合，
     *   这是唯一可信的"本机支持什么"来源 —— 绝不能按车型配置猜。
     */
    data class Ready(val supportedProperties: Set<CarVehicleProperty>, val carType: String?, val connectedAtMillis: Long) : CarServiceState()

    data class Reconnecting(val attempt: Int, val cause: String, val nextRetryInMillis: Long) : CarServiceState()

    /** 设备根本没有 car framework（在手机/平板上跑调试包就会走到这里）。 */
    data class Unsupported(val reason: String) : CarServiceState()

    val isReady: Boolean get() = this is Ready

    val userMessage: String
        get() = when (this) {
            is Disconnected -> "未连接车辆服务"
            is Connecting -> "正在连接车辆服务"
            is Ready -> "车辆服务就绪（${supportedProperties.size} 个属性）"
            is Reconnecting -> "车辆服务断开，第 $attempt 次重连（${cause}）"
            is Unsupported -> "本机无车辆服务：$reason"
        }
}

interface CarServiceGateway {

    val state: StateFlow<CarServiceState>

    /** 幂等；重复调用共享同一次连接。返回最终状态。 */
    suspend fun connect(): CarServiceState

    fun supports(property: CarVehicleProperty): Boolean

    /**
     * 属性订阅流。
     *
     * 语义要求：多个订阅者共享同一条 VHAL 回调（见 PropertySubscriptionRegistry），
     * 且只输出 [CarPropertySample] 的最新值 —— 车速这类 50Hz 属性如果原样透传，
     * 会让订阅方的 Compose 重组跟着 50Hz 跑。
     */
    fun subscribe(property: CarVehicleProperty, areaId: Int = GLOBAL_AREA_ID): Flow<CarPropertySample>

    /**
     * 订阅结束时**必须**调用。注册数由引用计数管理，漏调用会让 VHAL 回调只增不减，
     * 表现为 CarService 内存持续上涨，最后系统杀掉整个车机服务 —— 这是实车上真实出现过的事故类型。
     */
    fun unsubscribe(property: CarVehicleProperty, areaId: Int = GLOBAL_AREA_ID)

    suspend fun read(property: CarVehicleProperty, areaId: Int = GLOBAL_AREA_ID): Outcome<CarPropertySample>

    suspend fun write(property: CarVehicleProperty, value: PropertyValue, areaId: Int = GLOBAL_AREA_ID): Outcome<Unit>

    /** 区域能力（比如空调有几区），UI 据此决定画几个温度控件。 */
    fun areasOf(property: CarVehicleProperty): List<Int>

    fun disconnect()
}

/** 写属性的失败原因映射，集中一处便于反射实现复用。 */
object CarPropertyErrors {
    fun fromRegisterFailure(property: CarVehicleProperty, reason: String): DomainError =
        if (property.accessRestricted) {
            DomainError.Rejected(403, "${property.displayName} 需要系统权限（ACCESS_RESTRICTED）：$reason")
        } else {
            DomainError.Unsupported(property.displayName)
        }

    fun fromWriteFailure(property: CarVehicleProperty, reason: String): DomainError =
        DomainError.Rejected(400, "${property.displayName} 写入失败：$reason")

    fun serviceNotReady(): DomainError = DomainError.ConnectionUnavailable("车辆服务未就绪")
}
