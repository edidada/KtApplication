package com.example.ktapplication.vehicle.data

import com.example.ktapplication.core.DomainError
import com.example.ktapplication.core.MonotonicClock
import com.example.ktapplication.core.Outcome
import com.example.ktapplication.core.SystemMonotonicClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

/**
 * 可编排的假 TSP 客户端：本地跑通、预览与 JVM 单测共用。
 *
 * 设计取舍：
 *  - 响应按"脚本队列"消费（enqueueStatus/Ack/Result），测时序时不需要任何 mock 框架；
 *  - 延迟用 [delay] 而不是 sleep：runTest 的虚拟时间可以直接跨过 8s 控车超时，
 *    [clock] 只用于给快照打 receivedAt，不参与 sleep —— 真实时间和虚拟时间解耦，
 *    所以 delay=0 时也完全确定；
 *  - 故障注入用**固定种子的 Random**：间歇性丢包必须可复现，
 *    否则"这条用例昨天还是绿的"这种问题无法归因。
 */
class MockTspClient(
    private val clock: MonotonicClock = SystemMonotonicClock,
    /** 每次调用注入故障的概率，0..1。 */
    private val failureRate: Double = 0.0,
    private val randomSeed: Long = 0L,
    /** 模拟 RTT 的固定延迟；runTest 下按虚拟时间推进。 */
    private val responseDelayMillis: Long = 0L,
    /** 链路质量：影响 [connectivity] 展示与 probe 结果。 */
    initialOnline: Boolean = true,
) : TspClient {

    private val random = Random(randomSeed)

    private val _connectivity = MutableStateFlow(
        if (initialOnline) TspConnectivity.ONLINE else TspConnectivity.OFFLINE,
    )
    override val connectivity: StateFlow<TspConnectivity> = _connectivity.asStateFlow()

    /** 离线开关：置 false 后所有请求立即返回 ConnectionUnavailable（模拟地库/隧道）。 */
    var online: Boolean = initialOnline
        set(value) {
            field = value
            _connectivity.value = if (value) TspConnectivity.ONLINE else TspConnectivity.OFFLINE
        }

    private val statusQueue = ArrayDeque<TspStatusSnapshot>()
    private val ackQueue = ArrayDeque<TspCommandAck>()
    private val resultQueue = ArrayDeque<TspCommandResult>()

    /** 供 fetchStatus 依序消费；空队列时按"云端无数据"失败。 */
    fun enqueueStatus(snapshot: TspStatusSnapshot): MockTspClient = apply { statusQueue.addLast(snapshot) }

    fun enqueueAck(ack: TspCommandAck): MockTspClient = apply { ackQueue.addLast(ack) }

    fun enqueueResult(result: TspCommandResult): MockTspClient = apply { resultQueue.addLast(result) }

    /** 推送脚本：collect 时按 [pushIntervalMillis] 间隔依序发射。 */
    var pushScript: List<TspPushEvent> = emptyList()

    var pushIntervalMillis: Long = 1_000L
        set(value) {
            require(value >= 0) { "发射间隔不允许为负" }
            field = value
        }

    override val pushEvents: Flow<TspPushEvent> = flow {
        for (event in pushScript) {
            if (pushIntervalMillis > 0) delay(pushIntervalMillis)
            emit(event)
        }
        // 脚本放完后流自然结束；需要"永不结束"的场景由上层 repeatOnLifecycle 兜住。
    }

    override suspend fun fetchStatus(vehicleId: String): Outcome<TspStatusSnapshot> {
        awaitLatency()
        offlineFailure()?.let { return Outcome.failure(it) }
        if (rollFault()) return Outcome.failure(faultError())
        val next = statusQueue.removeFirstOrNull()
            ?: return Outcome.failure(DomainError.Unsupported("脚本未提供状态响应"))
        // receivedAt 由 Mock 盖戳：脚本里的快照通常是测试数据构造的，
        // 接收时间留 0 会让上层的陈旧判定失真。
        return Outcome.success(next.copy(receivedAtMillis = clock.nowMillis()))
    }

    override suspend fun submitCommand(request: TspCommandRequest): Outcome<TspCommandAck> {
        awaitLatency()
        offlineFailure()?.let { return Outcome.failure(it) }
        if (rollFault()) {
            // 控车的注入故障走 TransportFailure 而不是 Outcome.failure，
            // 与 HttpTspClient 的行为一致：上层按 ack.retryable 决策。
            return Outcome.success(TspCommandAck.TransportFailure("模拟传输故障", retryable = true))
        }
        val next = ackQueue.removeFirstOrNull()
            ?: return Outcome.failure(DomainError.Unsupported("脚本未提供受理响应"))
        return Outcome.success(next)
    }

    override suspend fun queryCommandResult(commandId: String): Outcome<TspCommandResult> {
        awaitLatency()
        offlineFailure()?.let { return Outcome.failure(it) }
        if (rollFault()) return Outcome.failure(faultError())
        val next = resultQueue.removeFirstOrNull()
            ?: return Outcome.success(TspCommandResult.Unknown)
        return Outcome.success(next)
    }

    override suspend fun probeConnectivity(): TspConnectivity =
        if (online) TspConnectivity.ONLINE else TspConnectivity.OFFLINE

    override fun close() {
        statusQueue.clear()
        ackQueue.clear()
        resultQueue.clear()
        pushScript = emptyList()
    }

    private suspend fun awaitLatency() {
        // delay(0) 分支不进入：保留"零延迟 Mock 完全不消耗虚拟时间"的语义，
        // 让不关心时序的用例不受 runTest 调度细节影响。
        if (responseDelayMillis > 0) delay(responseDelayMillis)
    }

    private fun offlineFailure(): DomainError? =
        if (!online) DomainError.ConnectionUnavailable("Mock 处于离线模式") else null

    private fun rollFault(): Boolean = failureRate > 0.0 && random.nextDouble() < failureRate

    private fun faultError(): DomainError = DomainError.ConnectionUnavailable("Mock 注入故障")
}

/**
 * 演示车辆：SOC 缓慢下降 + 偶发充电段 + 每 5s 一条状态推送。
 *
 * 给 Compose 预览 / Demo 页用：App 接上这个客户端就能表演"车况活起来"的效果，
 * 不需要真实 TSP。生成的是**有限脚本**（demoDurationMillis 条），
 * 演示循环播放由上层重新 collect 负责 —— Mock 保持可预测，不做无限流。
 */
fun MockTspClient.asDemoVehicle(
    vehicleId: String,
    clock: MonotonicClock,
    startSocPercent: Double = 82.0,
    demoTickCount: Int = 240,
    intervalMillis: Long = 5_000L,
): MockTspClient {
    var soc = startSocPercent
    var chargingTicksLeft = 0
    var odometer = 23_456.7
    var baseMillis = clock.nowMillis()
    val snapshots = (0 until demoTickCount).map { index ->
        baseMillis += intervalMillis
        val charging = chargingTicksLeft > 0
        if (charging) {
            chargingTicksLeft -= 1
            soc = (soc + 0.8).coerceAtMost(100.0)
            if (soc >= 90.0) chargingTicksLeft = 0
        } else {
            // 静态停放缓慢掉电（车载 T-Box 自身功耗 + 温控唤醒），行驶段掉得更快。
            soc -= if (index % 7 == 0) 0.5 else 0.08
            if (soc <= 35.0 && chargingTicksLeft == 0 && index % 40 == 39) {
                // 低电后"插枪"：演示充电 UI 的自动出现，每 40 拍左右来一段。
                chargingTicksLeft = 12
            }
        }
        val driving = index % 7 == 0
        if (driving) odometer += 4.2
        TspStatusSnapshot(
            vehicleId = vehicleId,
            reportedAtMillis = baseMillis,
            receivedAtMillis = baseMillis,
            stateOfChargePercent = soc.coerceIn(5.0, 100.0),
            remainingRangeKilometres = soc * 4.1,
            odometerKilometres = odometer,
            speedKilometresPerHour = if (driving) 46.0 else 0.0,
            // 档位沿用 DBC 编码（0=P / 3=D），与聚合器的 GearPosition.fromRaw 对齐；
            // 别写 AAOS 的 1=D / 4=P，那会让演示车的档位一直显示 "--" 或"倒挡"。
            gearRaw = if (driving) 3 else 0,
            lockRaw = if (index % 23 == 0) 0 else 1,
            doorsRaw = mapOf("FL" to 0, "FR" to 0, "RL" to 0, "RR" to 0, "TRUNK" to 0),
            driverSeatbeltRaw = if (driving) 1 else 0,
            airConditionerOn = charging || driving,
            targetTemperatureCelsius = 24.0,
            fanLevel = 3,
            pluggedIn = charging,
            chargingPowerKilowatts = if (charging) 6.6 else 0.0,
            chargingCurrentAmperes = if (charging) 29.0 else 0.0,
            packVoltageVolts = 382.0,
            lowBatteryVolts = 12.4,
            latitude = 30.1 + index * 0.0001,
            longitude = 120.2 + index * 0.0001,
            accuracyMeters = 15f,
            dataOrigin = if (index % 11 == 0) "CACHE" else "VEHICLE",
        )
    }
    snapshots.forEach { enqueueStatus(it) }
    pushScript = snapshots.map { TspPushEvent.StatusUpdated(it) }
    pushIntervalMillis = intervalMillis
    return this
}
