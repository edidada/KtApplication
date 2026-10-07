package com.example.ktapplication.vehicle.carservice

import com.example.ktapplication.core.AppDispatchers
import com.example.ktapplication.core.MonotonicClock
import com.example.ktapplication.core.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * CarService 网段的 Mock 实现：在没有车机 framework 的开发机/模拟器上把整条链路跑通。
 *
 * 它同时充当接口行为的**可执行规格**：什么时候发 NOT_AVAILABLE、断连时缓存怎么处理、
 * 写入失败返回什么。真机实现只要行为一致，上层就无需改动。
 *
 * 刻意保留了一个"EV 车型没有 ENGINE_RPM"的不支持属性，用来验证上层的 CAN 回落逻辑
 * —— 真实项目里最容易出现的问题就是"属性不存在"被当成"值为 0"。
 */
class MockCarServiceGateway(
    private val clock: MonotonicClock,
    private val dispatchers: AppDispatchers,
    private val scope: CoroutineScope,
    private val vehicleId: String = "LVSHCDAAXFA000123",
    /** 模拟连接耗时，真机上 CarService 绑定大约 300~1500ms。 */
    private val connectDelayMillis: Long = 400L,
    private val supportedProperties: Set<CarVehicleProperty> = CarVehicleProperty.entries
        .filter { it != CarVehicleProperty.ENGINE_RPM && it != CarVehicleProperty.LOW_VOLTAGE_BATTERY_LEVEL }
        .filterNot { it == CarVehicleProperty.DRIVETRAIN_GEAR_SELECTED }
        .toSet(),
) : CarServiceGateway, PropertySubscriptionRegistry.Registrar {

    private val _state = MutableStateFlow<CarServiceState>(CarServiceState.Disconnected)
    override val state: StateFlow<CarServiceState> = _state.asStateFlow()

    private val registry = PropertySubscriptionRegistry(clock, dispatchers, scope, registrar = this)
    private val cache = PropertyCache(clock)
    private val coordinator = CarConnectionCoordinator(nowMillis = { clock.nowMillis() }, onStateChange = { _state.value = it })

    /** 内部"真实"车辆状态，写入会改它，订阅流会回读它。 */
    private val propertyValues = mutableMapOf<CarVehicleProperty, PropertyValue>()
    private var tickJob: kotlinx.coroutines.Job? = null
    private var reconnectJob: kotlinx.coroutines.Job? = null
    private var connectStartedAtMillis = 0L

    private fun defaults(): Map<CarVehicleProperty, PropertyValue> = mapOf(
        CarVehicleProperty.PERF_VEHICLE_SPEED to PropertyValue.Float(0f),
        CarVehicleProperty.EV_BATTERY_LEVEL to PropertyValue.Float(86f),
        CarVehicleProperty.DRIVETRAIN_RANGE to PropertyValue.Float(293f),
        CarVehicleProperty.ODOMETER to PropertyValue.Float(23_456.7f),
        // AAOS GearDisplayPosition：0=N 1=D 2=L 3=R 4=P，默认 P 挡。
        CarVehicleProperty.GEAR_SELECTION to PropertyValue.Int32(4),
        CarVehicleProperty.HVAC_TEMPERATURE_SET to PropertyValue.Float(22f),
        CarVehicleProperty.HVAC_FAN_SPEED to PropertyValue.Int32(3),
        CarVehicleProperty.HVAC_POWER_ON to PropertyValue.Bool(true),
        CarVehicleProperty.DOOR_LOCK to PropertyValue.Bool(false),
        CarVehicleProperty.DOOR_POS to PropertyValue.Int32(0),
        CarVehicleProperty.TRUNK_STATE to PropertyValue.Int32(0),
        CarVehicleProperty.SEATBELT_BUCKLED to PropertyValue.Int32(1),
        CarVehicleProperty.CHARGE_PORT_SWITCH to PropertyValue.Bool(false),
        CarVehicleProperty.CHARGE_STATE to PropertyValue.Int32(0),
        CarVehicleProperty.BMS_CHARGE_CURRENT to PropertyValue.Float(0f),
    )

    override suspend fun connect(): CarServiceState {
        when (val current = _state.value) {
            is CarServiceState.Ready -> return current
            is CarServiceState.Unsupported -> return current
            is CarServiceState.Connecting -> {
                // 连接进行中：等到就绪或失败，避免调用方重复发起绑定。
                while (_state.value is CarServiceState.Connecting && scope.isActive) delay(50)
                return _state.value
            }
            else -> Unit
        }
        coordinator.onConnectRequested()
        connectStartedAtMillis = clock.nowMillis()
        propertyValues.putAll(defaults())
        delay(connectDelayMillis)
        coordinator.onReady(supportedProperties, carType = "BEV")
        startPropertyTicker()
        return _state.value
    }

    override fun supports(property: CarVehicleProperty): Boolean = supportedProperties.contains(property)

    override fun unsubscribe(property: CarVehicleProperty, areaId: Int) {
        registry.release(property, areaId)
    }

    override fun subscribe(property: CarVehicleProperty, areaId: Int): Flow<CarPropertySample> {
        // 先把缓存里的最后一个可信值发出去，UI 不必等下一个回调周期才有内容。
        val cached = cache.get(property, areaId)
        val stream = registry.subscribe(property, areaId)
        return if (cached == null) stream else kotlinx.coroutines.flow.flow {
            emit(cached)
            stream.collect { emit(it) }
        }
    }

    override suspend fun read(property: CarVehicleProperty, areaId: Int): Outcome<CarPropertySample> {
        if (!supports(property)) {
            return Outcome.failure(CarPropertyErrors.fromRegisterFailure(property, "VHAL 未声明该属性"))
        }
        val sample = registry.readOnce(property, areaId)
            ?: CarPropertySample.unavailable(property, areaId, clock.nowMillis())
        return Outcome.success(sample)
    }

    override suspend fun write(property: CarVehicleProperty, value: PropertyValue, areaId: Int): Outcome<Unit> {
        if (!supports(property)) return Outcome.failure(CarPropertyErrors.fromRegisterFailure(property, "VHAL 未声明该属性"))
        val error = registry.write(property, areaId, value)
        return if (error == null) Outcome.success(Unit) else Outcome.failure(CarPropertyErrors.fromWriteFailure(property, error))
    }

    override fun areasOf(property: CarVehicleProperty): List<Int> = when {
        !property.areaScoped -> listOf(GLOBAL_AREA_ID)
        property == CarVehicleProperty.HVAC_TEMPERATURE_SET -> listOf(1, 2) // 双区空调
        property == CarVehicleProperty.DOOR_LOCK || property == CarVehicleProperty.DOOR_POS -> listOf(1, 2, 4, 8)
        else -> listOf(GLOBAL_AREA_ID)
    }

    override fun disconnect() {
        tickJob?.cancel()
        reconnectJob?.cancel()
        registry.releaseAll()
        coordinator.reset()
    }

    /**
     * 模拟 CarService 进程死亡（真机上 VHAL 崩溃、OTA 重启都会触发）。
     *
     * 验证点：状态进入 Reconnecting、旧缓存仍然可读（UI 不会突然全变 "--"）、
     * 退避到时间后自动恢复 READY。
     */
    fun simulateCarServiceDeath(cause: String = "binderDied") {
        val nextDelay = coordinator.onConnectionLost(cause)
        tickJob?.cancel()
        reconnectJob?.cancel()
        reconnectJob = scope.launch(dispatchers.io) {
            delay(nextDelay)
            if (!isActive) return@launch
            coordinator.onConnectRequested()
            delay(connectDelayMillis / 2)
            coordinator.onReady(supportedProperties, carType = "BEV")
            startPropertyTicker()
        }
    }

    /** 测试钩子：直接把某个属性"推"给所有订阅者。 */
    fun injectProperty(property: CarVehicleProperty, value: PropertyValue, areaId: Int = GLOBAL_AREA_ID) {
        propertyValues[property] = value
        publishSample(CarPropertySample(property, areaId, value, PropertyStatus.AVAILABLE, clock.nowMillis()))
    }

    private fun publishSample(sample: CarPropertySample) {
        cache.put(sample)
        registry.publish(sample)
    }

    private fun startPropertyTicker() {
        tickJob?.cancel()
        tickJob = scope.launch(dispatchers.io) {
            var tick = 0L
            while (isActive) {
                val elapsed = clock.nowMillis() - connectStartedAtMillis
                val seconds = elapsed / 1000.0
                val speed = when {
                    seconds < 10 -> seconds * 6.0
                    seconds < 60 -> 60.0 + (seconds - 10) * 0.6
                    else -> 90.0 + kotlin.math.sin(seconds / 8.0) * 4.0
                }
                publishSample(sample(CarVehicleProperty.PERF_VEHICLE_SPEED, PropertyValue.Float(speed.toFloat())))
                publishSample(
                    sample(
                        CarVehicleProperty.EV_BATTERY_LEVEL,
                        PropertyValue.Float((86.0 - seconds * 0.02).coerceAtLeast(12.0).toFloat()),
                    ),
                )
                publishSample(sample(CarVehicleProperty.GEAR_SELECTION, PropertyValue.Int32(gearAt(seconds))))
                if (tick % 10 == 0L) {
                    // 低频属性按 1s 节奏发，模拟 ON_CHANGE 与 CONTINUOUS 的差别。
                    // DRIVETRAIN_RANGE 由 SOC 反推（每 1% 约 3.4km，86% ≈ 293km 与初始值对齐）：
                    // 先全程 Double 做单位换算，最后整体收窄成 Float —— VHAL 的 FLOAT 属性只接受
                    // kotlin.Float，中间量留在 Double（或像之前那样 toFloat() 后又乘 Double 的 3.4）
                    // 会把它重新抬回 Double，落到 PropertyValue.Float(…) 上就是类型不匹配。
                    publishSample(sample(CarVehicleProperty.DRIVETRAIN_RANGE, PropertyValue.Float(((86.0 - seconds * 0.02).coerceAtLeast(12.0) * 3.4).toFloat())))
                    // ODOMETER 累计里程 = 基数 23456.7km + 行驶距离（约 0.01km/s）；同样在末尾收窄成 Float。
                    publishSample(sample(CarVehicleProperty.ODOMETER, PropertyValue.Float((23_456.7 + seconds * 0.01).toFloat())))
                }
                tick++
                delay(200)
            }
        }
    }

    /**
     * 上电后的档位变化：P → R（出库）→ N → D。
     *
     * 取值用的是 VHAL 的 GearDisplayPosition 编码（4=P 3=R 0=N 1=D），与 CAN 侧 DBC 的
     * 0=P 1=R 2=N 3=D 不同序 —— 聚合器按来源分别映射，这里刻意保持 VHAL 语义，
     * 让 Mock 成为"真实上游会给什么"的可执行说明。
     */
    private fun gearAt(seconds: Double): Int = when {
        seconds < 3 -> 4
        seconds < 6 -> 3
        seconds < 8 -> 0
        else -> 1
    }

    private fun sample(property: CarVehicleProperty, value: PropertyValue, areaId: Int = GLOBAL_AREA_ID) =
        CarPropertySample(property, areaId, value, PropertyStatus.AVAILABLE, clock.nowMillis())

    // ---- Registrar ----

    override suspend fun register(property: CarVehicleProperty, areaId: Int): Boolean = supports(property)

    override suspend fun unregister(property: CarVehicleProperty, areaId: Int) = Unit

    override suspend fun readSample(property: CarVehicleProperty, areaId: Int): CarPropertySample? {
        val value = propertyValues[property] ?: return null
        return CarPropertySample(property, areaId, value, PropertyStatus.AVAILABLE, clock.nowMillis())
    }

    /** 返回 null 表示写入成功，否则返回失败原因 —— 与真机实现保持一致的错误语义。 */
    override suspend fun write(property: CarVehicleProperty, areaId: Int, value: PropertyValue): String? {
        val coerced = value.coercedTo(property.valueType)
        // 真机上 VHAL 会因为"车速非零时禁止解锁"直接拒绝写入，Mock 必须复现这条规则，
        // 否则上真机后才会发现 UI 少了一层保护。
        if (property == CarVehicleProperty.DOOR_LOCK && coerced is PropertyValue.Bool && !coerced.value) {
            val speed = propertyValues[CarVehicleProperty.PERF_VEHICLE_SPEED]?.asDoubleOrNull() ?: 0.0
            if (speed > 1.0) return "VEHICLE_MOVING: ${"%.1f".format(speed)} km/h 时禁止解锁"
        }
        propertyValues[property] = coerced
        publishSample(CarPropertySample(property, areaId, coerced, PropertyStatus.AVAILABLE, clock.nowMillis()))
        return null
    }
}
