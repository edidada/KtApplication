package com.example.ktapplication.vehicle.domain.repo

import com.example.ktapplication.core.AppDispatchers
import com.example.ktapplication.core.MonotonicClock
import com.example.ktapplication.core.Outcome
import com.example.ktapplication.vehicle.can.BusHealth
import com.example.ktapplication.vehicle.can.CanBus
import com.example.ktapplication.vehicle.can.CanBusConnectionState
import com.example.ktapplication.vehicle.can.CanSignalHub
import com.example.ktapplication.vehicle.carservice.CarServiceGateway
import com.example.ktapplication.vehicle.carservice.CarVehicleProperty
import com.example.ktapplication.vehicle.data.TspClient
import com.example.ktapplication.vehicle.data.TspConnectivity
import com.example.ktapplication.vehicle.data.TspPushEvent
import com.example.ktapplication.vehicle.data.TspStatusSnapshot
import com.example.ktapplication.vehicle.domain.model.SignalHealth
import com.example.ktapplication.vehicle.domain.model.VehicleStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 车况仓库实现：把三条链路喂进 [VehicleStatusAggregator] 并对外发布 StateFlow。
 *
 * 车机上的三条链路各有失效模式，这一层要同时处理：
 *  - **CAN**：行驶中一路领先；车辆下电后总线静默（此时必须允许云端兜底）；
 *  - **CarService**：属性可能压根不支持（EV 没有 ENGINE_RPM），也可能因 CarService 重启短暂断连；
 *  - **TSP**：分钟级、有延迟、可能是缓存旧值，但它是唯一下电后还能拿到车况的通道。
 *
 * 云端轮询频率按"是否在车上"切换：行驶中 60s 一次（省流量、避免与 CAN 冲突），
 * 车辆下电/总线静默后 15s 一次（用户就是在车外看车况）。这是远程车况类 App 的实际做法。
 */
class DefaultVehicleStatusRepository(
    private val canHub: CanSignalHub,
    private val carGateway: CarServiceGateway,
    private val tspClient: TspClient,
    private val clock: MonotonicClock,
    private val dispatchers: AppDispatchers,
    private val scope: CoroutineScope,
    private val vehicleId: String,
    private val aggregator: VehicleStatusAggregator = VehicleStatusAggregator(clock, vehicleId),
    private val subscribedProperties: List<CarVehicleProperty> = DEFAULT_CAR_PROPERTIES,
) : VehicleStatusRepository {

    private val _status = MutableStateFlow<VehicleStatus?>(null)
    override val status: StateFlow<VehicleStatus?> = _status.asStateFlow()

    private val _health = MutableStateFlow(
        SignalHealth("DISCONNECTED", "Disconnected", "OFFLINE", emptyList(), 0.0, emptyMap()),
    )
    override val health: StateFlow<SignalHealth> = _health.asStateFlow()

    override var cloudFallbackEnabled: Boolean = true

    private var lastCloudFetchMillis = 0L
    private var cloudPollingStarted = false

    /** 启动所有常驻收集协程；重复调用不会建立第二条链路。 */
    fun start() {
        scope.launch(dispatchers.computation) {
            canHub.snapshot.collect { snapshot ->
                aggregator.applyCanSnapshot(
                    snapshot = snapshot,
                    busState = canHub.connectionState.value.name,
                    frameRateHz = canHub.frameRateHz.value,
                    // 总线健康度按 CanBus 枚举记账，但 SignalHealth.busDropRatesPercent 是给诊断页
                    // 直接渲染的，key 必须是展示名；在这一层转换而不是让 UI 依赖 can 层的枚举，
                    // 是为了让 domain 的诊断模型保持"纯字符串"，UI 与单测都不必再引 CanBus。
                    dropRates = canHub.busHealth.value.dropRatesForDisplay(),
                )
                publish()
            }
        }

        scope.launch(dispatchers.computation) {
            canHub.connectionState.collect { state ->
                if (state == CanBusConnectionState.DISCONNECTED || state == CanBusConnectionState.BUS_OFF) {
                    // 总线静默时必须允许云端兜底，否则用户下车后 App 会显示死数据。
                    cloudFallbackEnabled = true
                    ensureCloudPolling()
                }
                aggregator.applyCanSnapshot(
                    snapshot = canHub.snapshot.value,
                    busState = state.name,
                    frameRateHz = canHub.frameRateHz.value,
                    dropRates = canHub.busHealth.value.dropRatesForDisplay(),
                )
                publish()
            }
        }

        subscribedProperties.forEach { property ->
            scope.launch(dispatchers.io) {
                carGateway.subscribe(property).collect { sample ->
                    aggregator.applyCarSample(sample, carGateway.state.value.toString())
                    publish()
                }
            }
        }

        scope.launch(dispatchers.io) {
            carGateway.state.collect { state ->
                // CarService 重新就绪后主动补一次读，回调是增量的，重启后不会重放旧值。
                if (state is com.example.ktapplication.vehicle.carservice.CarServiceState.Ready) {
                    subscribedProperties.forEach { property ->
                        runCatching { carGateway.read(property) }.onSuccess { outcome ->
                            (outcome as? Outcome.Success)?.value?.let { sample ->
                                aggregator.applyCarSample(sample, state.toString())
                            }
                        }
                    }
                    publish()
                }
            }
        }

        scope.launch(dispatchers.io) {
            tspClient.pushEvents.collect { event ->
                when (event) {
                    is TspPushEvent.StatusUpdated -> {
                        aggregator.applyTspSnapshot(event.snapshot)
                        publish()
                    }
                    is TspPushEvent.SessionExpired -> _health.value = _health.value.copy(cloudState = "AUTH_EXPIRED")
                    is TspPushEvent.Heartbeat -> _health.value = _health.value.copy(cloudState = "ONLINE")
                    else -> Unit
                }
            }
        }

        ensureCloudPolling()
    }

    override suspend fun refreshFromCloud(force: Boolean): Outcome<TspStatusSnapshot> {
        val now = clock.nowMillis()
        if (!force && now - lastCloudFetchMillis < MIN_CLOUD_REFRESH_MILLIS) {
            // 用户高频下拉时不要真打云端：TSP 侧的车况接口通常有按 VIN 的限流，超了会拿到 429。
            return Outcome.success(tspClientLatestSnapshot())
        }
        lastCloudFetchMillis = now
        val outcome = tspClient.fetchStatus(vehicleId)
        when (outcome) {
            is Outcome.Success -> {
                aggregator.applyTspSnapshot(outcome.value)
                publish()
            }
            is Outcome.Failure -> {
                aggregator.markCloudOffline()
                publish()
            }
        }
        return outcome
    }

    private fun ensureCloudPolling() {
        if (cloudPollingStarted) return
        cloudPollingStarted = true
        scope.launch(dispatchers.io) {
            while (isActive) {
                val driving = canHub.connectionState.value == CanBusConnectionState.STREAMING
                val interval = if (driving) POLL_INTERVAL_DRIVING_MILLIS else POLL_INTERVAL_PARKED_MILLIS
                if (cloudFallbackEnabled) {
                    val outcome = runCatching { tspClient.fetchStatus(vehicleId) }.getOrNull()
                    when (outcome) {
                        is Outcome.Success -> {
                            aggregator.applyTspSnapshot(outcome.value)
                            publish()
                        }
                        else -> {
                            aggregator.markCloudOffline()
                            publish()
                        }
                    }
                }
                delay(interval)
            }
        }
    }

    private fun tspClientLatestSnapshot(): TspStatusSnapshot {
        // 频率限制命中时返回上一次缓存，让 UI 仍能收敛（不阻塞下拉动画）。
        val cached = aggregator.build()
        val now = clock.nowMillis()
        return TspStatusSnapshot(
            vehicleId = vehicleId,
            reportedAtMillis = cached?.updatedAtMillis ?: now,
            receivedAtMillis = now,
            stateOfChargePercent = cached?.stateOfChargePercent?.value,
            remainingRangeKilometres = cached?.remainingRangeKilometres?.value,
            odometerKilometres = cached?.odometerKilometres?.value,
            dataOrigin = "CACHE",
        )
    }

    private fun publish() {
        val built = aggregator.build() ?: return
        _status.value = built
        _health.value = built.signalHealth
    }

    companion object {
        /** 只订阅 UI 真正需要的属性：VHAL 回调注册数直接影响 CarService 的稳定性。 */
        val DEFAULT_CAR_PROPERTIES = listOf(
            CarVehicleProperty.PERF_VEHICLE_SPEED,
            CarVehicleProperty.EV_BATTERY_LEVEL,
            CarVehicleProperty.DRIVETRAIN_RANGE,
            CarVehicleProperty.ODOMETER,
            CarVehicleProperty.GEAR_SELECTION,
            CarVehicleProperty.DOOR_LOCK,
            CarVehicleProperty.HVAC_TEMPERATURE_SET,
            CarVehicleProperty.CHARGE_PORT_SWITCH,
        )

        private const val MIN_CLOUD_REFRESH_MILLIS = 3_000L
        private const val POLL_INTERVAL_DRIVING_MILLIS = 60_000L
        private const val POLL_INTERVAL_PARKED_MILLIS = 15_000L
    }
}

/** 连接性变化 → 仓库侧的兜底开关，单独抽出来便于单测断言。 */
object CloudFallbackPolicy {
    fun shouldEnableCloudFallback(busState: CanBusConnectionState, connectivity: TspConnectivity): Boolean =
        busState != CanBusConnectionState.STREAMING && connectivity != TspConnectivity.NOT_PROVISIONED
}

/**
 * `Map<CanBus, BusHealth>` → `Map<展示名, 丢帧率>`。
 *
 * 为什么在仓库这一层转字符串、而不是把 `SignalHealth.busDropRatesPercent` 改成枚举 key：
 *  1. 诊断模型要保持"只含字符串/数值"的形态 —— 它会被诊断页直接渲染、被快照序列化成 JSON，
 *     引 `vehicle.can` 的枚举会让 domain 反向依赖硬件接入层；
 *  2. `CanBus.displayName` 是一一对应的（动力域/底盘域/车身域/座舱域/诊断域），
 *     `mapKeys` 不存在同名折叠丢总线的问题；真出现重名时也要在这里显式处理，
 *     而不是让某一路的丢帧率被另一路静默覆盖。
 */
internal fun Map<CanBus, BusHealth>.dropRatesForDisplay(): Map<String, Double> =
    mapKeys { (bus, _) -> bus.displayName }
        .mapValues { (_, health) -> health.dropRatePercent }
