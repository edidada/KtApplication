package com.example.ktapplication.vehicle.can

import com.example.ktapplication.core.AppDispatchers
import com.example.ktapplication.core.MonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

/** CAN 链路状态，直接决定座舱要不要显示"信号丢失"提示。 */
enum class CanBusConnectionState { DISCONNECTED, CONNECTING, STREAMING, BUS_OFF }

/**
 * 信号中心：原始帧 → 解码 → 带质量标记的 StateFlow。
 *
 * 三个车载特有的设计点：
 *
 * 1. **收帧侧用 DROP_OLDEST 有界缓冲**。底盘域 20ms 周期帧叠加车身域突发时，
 *    如果解码协程被 IO 抖动拖慢，无界缓冲会让 UI 显示 2 秒前的车速 —— 这在行车中是安全问题。
 *    座舱宁可丢帧（并计入丢帧率上报），也不能显示过期值。
 * 2. **单条信号即时更新，快照按 50ms 节流发布**。车速 StateFlow 每 20ms 更新一次没问题，
 *    但如果把它拼成一个大 data class 直接 setState，Compose 会跟着 50Hz 重组，
 *    低端车机（双核 A53 + 共享 GPU）会直接掉帧。快照节流到 20Hz 是仪表和中控共同的折中值。
 * 3. **超时判定独立于收帧**。DBC 的 timeout 语义是"信号不再刷新就不可信"，
 *    由后台 sweep 主动把值降级成 [SignalQuality.STALE]，而不是等下一帧到来才发现。
 */
class CanSignalHub(
    private val client: CanBusClient,
    private val clock: MonotonicClock,
    private val dispatchers: AppDispatchers,
    private val definitions: List<CanSignalDefinition> = CanSignalCatalog.all,
    private val staleSweepPeriodMillis: Long = 200L,
    private val snapshotPublishPeriodMillis: Long = 50L,
) {

    private val definitionsByName: Map<String, CanSignalDefinition> = definitions.associateBy { it.name }

    /**
     * 本枢纽自己的 (bus, id) → 信号表索引。
     *
     * 不能直接用 [CanSignalCatalog.definitionsFor]：信号表在真机上是按车型下发/生成的，
     * 传入自定义 definitions 时如果还查全局目录，就会解出根本不存在的信号或漏解真有的信号。
     */
    private val definitionsByFrame: Map<Pair<CanBus, Int>, List<CanSignalDefinition>> =
        definitions.groupBy { it.bus to it.canId }

    /** 每条信号一个 StateFlow：订阅方按需取用，不做全局大对象。 */
    private val signalFlows: Map<String, MutableStateFlow<DecodedSignal>> = definitions
        .associate { definition ->
            definition.name to MutableStateFlow(
                DecodedSignal.notAvailable(definition, clock.nowMillis())
            )
        }

    private val _busHealth = MutableStateFlow(
        definitions.map { it.bus }.distinct().associateWith { bus ->
            BusHealth(bus = bus, framesReceived = 0, framesDropped = 0, signalsTimedOut = 0, lastFrameAtMillis = null)
        },
    )
    val busHealth: StateFlow<Map<CanBus, BusHealth>> = _busHealth.asStateFlow()

    private val _connectionState = MutableStateFlow(CanBusConnectionState.DISCONNECTED)
    val connectionState: StateFlow<CanBusConnectionState> = _connectionState.asStateFlow()

    private val _frameRateHz = MutableStateFlow(0.0)
    val frameRateHz: StateFlow<Double> = _frameRateHz.asStateFlow()

    private val _snapshot = MutableStateFlow(
        VehicleCanSnapshot(
            signals = signalFlows.mapValues { it.value.value },
            publishedAtMillis = clock.nowMillis(),
        ),
    )

    /** 20Hz 节流后的整車快照，UI 只订阅这一个流。 */
    val snapshot: StateFlow<VehicleCanSnapshot> = _snapshot.asStateFlow()

    /** 原始帧旁路：日志、回放、诊断上报用；多订阅者共享，容量刻意很小。 */
    private val _rawFrames = MutableSharedFlow<CanFrame>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val rawFrames: SharedFlow<CanFrame> = _rawFrames.asSharedFlow()

    private val receivedFrameCounts = HashMap<CanBus, Long>()
    private val rateWindow = ArrayDeque<Long>()
    private var lastSnapshotPublishAt = 0L
    private var started = false

    fun signalDefinition(name: String): CanSignalDefinition? = definitionsByName[name]

    fun signal(name: String): StateFlow<DecodedSignal> =
        requireNotNull(signalFlows[name]) { "未注册的信号：$name" }

    fun current(name: String): DecodedSignal = signal(name).value

    /** 类型安全的快捷读取：信号无效/陈旧时返回 null，调用方必须处理"没有值"。 */
    fun readPhysical(name: String): Double? = current(name).let { signal ->
        if (signal.qualityIsValid()) signal.physicalValue else null
    }

    fun readRaw(name: String): Long? = current(name).let { signal ->
        if (signal.qualityIsValid()) signal.rawValue else null
    }

    fun readInt(name: String): Int? = readRaw(name)?.toInt()

    fun readBoolean(name: String): Boolean? = readRaw(name)?.let { it != 0L }

    fun start(scope: CoroutineScope): Job = scope.launch(dispatchers.computation) {
        if (started) return@launch
        started = true
        _connectionState.value = CanBusConnectionState.CONNECTING
        if (!client.connect()) {
            _connectionState.value = CanBusConnectionState.DISCONNECTED
            return@launch
        }
        _connectionState.value = CanBusConnectionState.STREAMING

        launch(dispatchers.computation) { sweepStaleSignals() }
        launch(dispatchers.computation) { publishSnapshots() }

        // buffer + DROP_OLDEST 是这条链路的关键：见类注释第 1 点。
        client.frames
            .buffer(capacity = 64)
            .collect { frame ->
                handleFrame(frame)
            }
        _connectionState.value = CanBusConnectionState.DISCONNECTED
    }

    fun stop() {
        client.disconnect()
        _connectionState.value = CanBusConnectionState.DISCONNECTED
        started = false
    }

    /** 同步解码入口，供回放/单测直接喂帧，不经过协程。 */
    fun ingest(frame: CanFrame) {
        handleFrame(frame)
    }

    private fun handleFrame(frame: CanFrame) {
        val now = frame.receivedAtMillis
        receivedFrameCounts[frame.bus] = (receivedFrameCounts[frame.bus] ?: 0L) + 1
        trackRate(now)
        _rawFrames.tryEmit(frame)

        val definitions = definitionsByFrame[frame.bus to frame.id].orEmpty()
        if (definitions.isEmpty()) {
            // 未在本机信号表里的帧仍要计数：座舱常见的"某信号没定义"排查就是从这里开始的。
            updateBusHealth(frame.bus, now)
            return
        }
        for (definition in definitions) {
            val decoded = CanSignalDecoder.decode(frame, definition)
            signalFlows.getValue(definition.name).value = decoded
        }
        updateBusHealth(frame.bus, now)
    }

    private fun trackRate(nowMillis: Long) {
        rateWindow.addLast(nowMillis)
        while (rateWindow.isNotEmpty() && nowMillis - rateWindow.first() > 1000L) {
            rateWindow.removeFirst()
        }
        _frameRateHz.value = rateWindow.size.toDouble()
    }

    private fun updateBusHealth(bus: CanBus, lastFrameAtMillis: Long) {
        val current = _busHealth.value[bus] ?: return
        val expected = kotlin.math.round(expectedFrameCounts[bus] ?: 0.0).toLong()
        val received = receivedFrameCounts[bus] ?: 0L
        _busHealth.value = _busHealth.value.toMutableMap().apply {
            put(
                bus,
                current.copy(
                    framesReceived = received,
                    framesDropped = max(0L, expected - received),
                    lastFrameAtMillis = lastFrameAtMillis,
                    signalsTimedOut = countTimedOutSignals(),
                ),
            )
        }
    }

    private suspend fun sweepStaleSignals() {
        while (true) {
            kotlinx.coroutines.delay(staleSweepPeriodMillis)
            val now = clock.nowMillis()
            signalFlows.values.forEach { flow ->
                val value = flow.value
                if (value.quality == SignalQuality.VALID && value.isStaleAt(now)) {
                    flow.value = value.copy(quality = SignalQuality.STALE)
                }
            }
            // 期望帧数按 DBC 周期累加，用于丢帧率估算；用 Double 累加避免慢周期信号
            // （1Hz 信号在 200ms 窗口里只该收 0.2 帧）被取整成 0 而永远统计不到丢帧。
            expectedFramesPerSecond.forEach { (bus, perSecond) ->
                val added = perSecond * staleSweepPeriodMillis / 1000.0
                expectedFrameCounts[bus] = (expectedFrameCounts[bus] ?: 0.0) + added
            }
            _busHealth.value.forEach { (bus, health) ->
                updateBusHealth(bus, health.lastFrameAtMillis ?: now)
            }
        }
    }

    private suspend fun publishSnapshots() {
        while (true) {
            kotlinx.coroutines.delay(snapshotPublishPeriodMillis)
            val now = clock.nowMillis()
            lastSnapshotPublishAt = now
            _snapshot.value = VehicleCanSnapshot(
                signals = signalFlows.mapValues { it.value.value },
                publishedAtMillis = now,
            )
        }
    }

    private fun countTimedOutSignals(): Int =
        signalFlows.values.count { it.value.quality == SignalQuality.STALE }

    /** 每条总线的理论帧率（周期帧求和），用于估算"这一秒本该收到多少帧"。 */
    private val expectedFramesPerSecond: Map<CanBus, Double> by lazy {
        definitionsByName.values
            .filter { it.expectedPeriodMillis != null }
            .groupBy { it.bus }
            .mapValues { (_, defs) -> defs.sumOf { 1000.0 / it.expectedPeriodMillis!! } }
    }

    private val expectedFrameCounts = HashMap<CanBus, Double>()
}

/**
 * 某一时刻的整车信号快照。
 *
 * 保持"信号名 → 值"的弱类型形态是有意的：DBC 会变、车型会有增减，
 * 强类型大字段每次改信号表都要动 UI 层。类型收敛放在 domain 层的 mapper 里做。
 */
data class VehicleCanSnapshot(
    val signals: Map<String, DecodedSignal>,
    val publishedAtMillis: Long,
) {
    operator fun get(name: String): DecodedSignal? = signals[name]

    fun physical(name: String): Double? = signals[name]?.takeIf { it.qualityIsValid() }?.physicalValue

    fun raw(name: String): Long? = signals[name]?.takeIf { it.qualityIsValid() }?.rawValue

    fun int(name: String): Int? = raw(name)?.toInt()

    fun boolean(name: String): Boolean? = raw(name)?.let { it != 0L }

    /** 快照整体新鲜度：只要发布时刻在阈值内就认为可用。 */
    fun isFresh(nowMillis: Long, thresholdMillis: Long = 1_000): Boolean =
        nowMillis - publishedAtMillis <= thresholdMillis

    val unreliableSignals: List<String>
        get() = signals.values.filter { it.quality != SignalQuality.VALID }.map { it.definition.name }.sorted()
}

private fun DecodedSignal.qualityIsValid(): Boolean = quality == SignalQuality.VALID
