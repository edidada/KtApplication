package com.example.ktapplication.vehicle.can

import com.example.ktapplication.core.MonotonicClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * CAN 总线接入层。
 *
 * 真机上座舱 App 拿原始帧有三条路，接口设计成能覆盖全部三种：
 *  1. Android Automotive 的 `CarPropertyManager`（走 VHAL，拿到的是**属性**而不是帧，
 *     需要把 property id 映射成信号，见 carservice 包）；
 *  2. 车机厂私有 native service / SocketCAN（`/dev/can0`，通过 JNI 或 Unix socket 收帧，
 *     这是国内定制座舱最常见的做法，也是能读到原始 DBC 帧的唯一途径）；
 *  3. 诊断域 UDS over CAN（只做读写配置，不承担周期信号）。
 */
interface CanBusClient {

    val availableBuses: Set<CanBus>

    /** 冷启动到总线就绪之间的帧会丢弃，实现里要用 conflate 或 BufferOverflow.DROP_OLDEST。 */
    val frames: Flow<CanFrame>

    /** 打开设备节点 / 注册回调。幂等：重复调用不会建立两条链路。 */
    suspend fun connect(): Boolean

    fun disconnect()

    val isConnected: Boolean
}

/** 发帧节拍抽象：真实运行用 [DelayFramePacer]，单测用 [ImmediateFramePacer] 免等待。 */
interface FramePacer {
    suspend fun awaitTick(tickMillis: Long)
}

object DelayFramePacer : FramePacer {
    override suspend fun awaitTick(tickMillis: Long) {
        delay(tickMillis)
    }
}

object ImmediateFramePacer : FramePacer {
    override suspend fun awaitTick(tickMillis: Long) = Unit
}

/**
 * 车辆动力学仿真器：近似纯函数 `虚拟时间 → 帧列表`。
 *
 * 把"信号怎么变"和"什么时候发帧"拆开后，单测可以完全不依赖协程时间就验证解码链路，
 * 也能把同一段仿真喂给 Compose 预览、性能压测（1 万帧快速回放）和真机 mock 客户端。
 */
class VehicleDynamicsSimulator(
    private val startMillis: Long = 0L,
) {

    /** 每条报文的发送周期；未列出的报文按事件型处理，只在状态翻转时发。 */
    private val framePeriods: Map<Pair<CanBus, Int>, Long> = mapOf(
        (CanBus.CHASSIS to 0x0A0) to 20L,
        (CanBus.CHASSIS to 0x0B4) to 20L,
        (CanBus.CHASSIS to 0x330) to 1000L,
        (CanBus.POWERTRAIN to 0x2C0) to 100L,
        (CanBus.POWERTRAIN to 0x2C4) to 20L,
        (CanBus.POWERTRAIN to 0x150) to 50L,
        (CanBus.POWERTRAIN to 0x2D0) to 1000L,
        (CanBus.POWERTRAIN to 0x2E0) to 500L,
        (CanBus.BODY to 0x0F0) to 100L,
        (CanBus.BODY to 0x1E0) to 200L,
        (CanBus.BODY to 0x340) to 1000L,
        (CanBus.INFOTAINMENT to 0x3A0) to 1000L,
    )

    val supportedFrames: Set<Pair<CanBus, Int>> get() = framePeriods.keys

    /**
     * 返回 [elapsedMillis] 这一 tick 应当发出的周期帧。
     * 事件型信号（门锁、车门）由 [eventFramesAt] 单独产生，避免周期帧覆盖掉状态。
     */
    fun periodicFramesAt(elapsedMillis: Long): List<CanFrame> {
        require(elapsedMillis >= startMillis) { "仿真时间不能回退" }
        val frames = ArrayList<CanFrame>(framePeriods.size)
        for ((key, period) in framePeriods) {
            if ((elapsedMillis - startMillis) % period != 0L) continue
            frames += buildFrame(key.first, key.second, elapsedMillis)
        }
        return frames
    }

    /** 门锁/车门的事件翻转点：15s 上锁，30s 解锁并开左前门，45s 关门并半锁。 */
    fun eventFramesAt(elapsedMillis: Long): List<CanFrame> {
        val lockRaw: Int
        val doorOpenFl: Boolean
        when (elapsedMillis) {
            15_000L -> { lockRaw = 1; doorOpenFl = false }
            30_000L -> { lockRaw = 0; doorOpenFl = true }
            45_000L -> { lockRaw = 2; doorOpenFl = false }
            else -> return emptyList()
        }
        var frame = CanFrame.of(
            bus = CanBus.BODY,
            id = 0x0F0,
            hexBytes = "00 00 00 00 00 00 00 00",
            receivedAtMillis = elapsedMillis,
        )
        frame = frame.copy(dlc = 8)
        frame = CanSignalEncoder.writeSignal(frame, CanSignalCatalog.LOCK_STATE, lockRaw.toDouble())
        frame = CanSignalEncoder.writeSignal(frame, CanSignalCatalog.DOOR_AJAR_FL, if (doorOpenFl) 1.0 else 0.0)
        frame = CanSignalEncoder.writeSignal(frame, CanSignalCatalog.DOOR_AJAR_FR, 0.0)
        frame = CanSignalEncoder.writeSignal(frame, CanSignalCatalog.DOOR_AJAR_RL, 0.0)
        frame = CanSignalEncoder.writeSignal(frame, CanSignalCatalog.DOOR_AJAR_RR, 0.0)
        frame = CanSignalEncoder.writeSignal(frame, CanSignalCatalog.SEATBELT_BUCKLED_DRIVER, 1.0)
        return listOf(frame)
    }

    private fun buildFrame(bus: CanBus, canId: Int, elapsedMillis: Long): CanFrame {
        var frame = CanFrame(
            bus = bus,
            id = canId,
            dlc = CanFrame.MAX_CLASSIC_DLC,
            data = ByteArray(CanFrame.MAX_CLASSIC_DLC),
            receivedAtMillis = elapsedMillis,
        )

        fun put(definition: CanSignalDefinition, physical: Double) {
            frame = CanSignalEncoder.writeSignal(frame, definition, physical)
        }

        val seconds = (elapsedMillis - startMillis) / 1000.0
        val speed = speedProfile(seconds)
        val soc = (86.0 - seconds * 0.02).coerceAtLeast(12.0)

        when {
            bus == CanBus.CHASSIS && canId == 0x0A0 -> {
                put(CanSignalCatalog.SPEED_KMH, speed)
                put(CanSignalCatalog.STEERING_ANGLE_DEG, steeringProfile(seconds))
            }

            bus == CanBus.CHASSIS && canId == 0x0B4 -> put(CanSignalCatalog.WHEEL_SPEED_FL_KMH, speed)
            bus == CanBus.CHASSIS && canId == 0x330 ->
                put(CanSignalCatalog.TYRE_PRESSURE_FL_KPA, 235.0 + kotlin.math.sin(seconds / 30.0) * 3)

            bus == CanBus.POWERTRAIN && canId == 0x2C0 -> {
                put(CanSignalCatalog.PACK_SOC_PERCENT, soc)
                put(CanSignalCatalog.PACK_VOLTAGE_V, 380.0 + soc * 0.35)
            }

            bus == CanBus.POWERTRAIN && canId == 0x2C4 -> {
                put(CanSignalCatalog.PACK_CURRENT_A, currentProfile(speed, seconds))
                put(CanSignalCatalog.MOTOR_RPM, speed * 90.0)
            }

            bus == CanBus.POWERTRAIN && canId == 0x150 -> put(CanSignalCatalog.GEAR_POSITION, gearProfile(seconds).toDouble())
            bus == CanBus.POWERTRAIN && canId == 0x2D0 -> put(CanSignalCatalog.EV_RANGE_KM, soc * 3.4)
            bus == CanBus.POWERTRAIN && canId == 0x2E0 -> {
                put(CanSignalCatalog.CHARGE_PLUGGED, 0.0)
                put(CanSignalCatalog.CHARGE_POWER_KW, 0.0)
            }

            bus == CanBus.BODY && canId == 0x0F0 -> {
                put(CanSignalCatalog.SEATBELT_BUCKLED_DRIVER, 1.0)
                put(CanSignalCatalog.LOCK_STATE, bodyLockStateAt(seconds).toDouble())
                put(CanSignalCatalog.DOOR_AJAR_FL, if (seconds >= 30 && seconds < 45) 1.0 else 0.0)
                put(CanSignalCatalog.DOOR_AJAR_FR, 0.0)
                put(CanSignalCatalog.DOOR_AJAR_RL, 0.0)
                put(CanSignalCatalog.DOOR_AJAR_RR, 0.0)
            }

            bus == CanBus.BODY && canId == 0x1E0 -> {
                put(CanSignalCatalog.HVAC_TEMP_DRIVER_C, 22.0)
                put(CanSignalCatalog.HVAC_TEMP_PASSENGER_C, if (seconds > 60) 24.5 else 22.0)
                put(CanSignalCatalog.HVAC_FAN_LEVEL, 3.0)
            }

            bus == CanBus.BODY && canId == 0x340 ->
                put(CanSignalCatalog.LOW_BATTERY_POWER_C, 12.4 + kotlin.math.sin(seconds / 60.0) * 0.2)

            bus == CanBus.INFOTAINMENT && canId == 0x3A0 ->
                put(CanSignalCatalog.ODOMETER_KM, 23_456.0 + seconds * 0.01)
        }
        return frame
    }

    private fun bodyLockStateAt(seconds: Double): Int = when {
        seconds < 15 -> 0
        seconds < 30 -> 1
        seconds < 45 -> 0
        else -> 2
    }

    /** 起步加速到 90km/h 后带波动巡航，模拟真实车速曲线。 */
    private fun speedProfile(seconds: Double): Double = when {
        seconds < 10 -> seconds * 6.0
        seconds < 60 -> 60.0 + (seconds - 10) * 0.6
        else -> 90.0 + kotlin.math.sin(seconds / 8.0) * 4.0
    }

    private fun steeringProfile(seconds: Double): Double = kotlin.math.sin(seconds / 12.0) * 45.0

    private fun currentProfile(speed: Double, seconds: Double): Double =
        if (seconds in 10.0..12.0) 120.0 else -(speed / 10.0 + 2.0 + kotlin.math.sin(seconds) * 1.5)

    private fun gearProfile(seconds: Double): Int = when {
        seconds < 3 -> 0   // P
        seconds < 6 -> 1   // R 出库倒车
        seconds < 8 -> 2   // N
        else -> 3          // D
    }
}

/**
 * Mock 总线：把仿真器按节拍喂给 [CanSignalHub]，用于本地跑通和 Compose 预览。
 *
 * 真机替换点：把 [tickedFrames] 换成读 `/dev/can0`（SocketCAN 的 `can_frame` 布局是
 * can_id(4) / can_dlc(1) / __pad,__res0,len(3) / data(8)，还要过滤 ERRF 帧和
 * 只读模式下的回环帧）或者 CarService 的属性回调转发。
 */
class MockCanBusClient(
    private val clock: MonotonicClock,
    private val simulator: VehicleDynamicsSimulator = VehicleDynamicsSimulator(),
    private val tickMillis: Long = 20L,
    private val pacer: FramePacer = DelayFramePacer,
    /** 单次采集的 tick 上限；-1 表示一直跑到协程取消。 */
    private val maxTicks: Int = -1,
) : CanBusClient {

    override val availableBuses: Set<CanBus> = CanSignalCatalog.buses

    private var connected = false

    /** 连接时刻的单调时间，仿真相对时间要叠加在它上面才能得到真实 receivedAt。 */
    private var connectedAtMillis: Long = 0L

    override val isConnected: Boolean get() = connected

    override suspend fun connect(): Boolean {
        if (connected) return true
        connectedAtMillis = clock.nowMillis()
        connected = true
        return true
    }

    override fun disconnect() {
        connected = false
    }

    override val frames: Flow<CanFrame> = flow {
        tickedFrames().collect { emit(it) }
    }

    /** 冷启动时先补一帧"当前状态"，避免 UI 出现 3 秒 "--"。 */
    suspend fun startAndPrime(): List<CanFrame> {
        connect()
        return simulator.periodicFramesAt(0L).map { it.copy(receivedAtMillis = clock.nowMillis()) }
    }

    fun tickedFrames(): Flow<CanFrame> = flow {
        if (!connected) connect()
        var tick = 0L
        while (maxTicks < 0 || tick < maxTicks) {
            val elapsed = tick * tickMillis
            val absolute = connectedAtMillis + elapsed
            simulator.periodicFramesAt(elapsed).forEach { frame ->
                emit(frame.copy(receivedAtMillis = absolute))
            }
            simulator.eventFramesAt(elapsed).forEach { frame ->
                emit(frame.copy(receivedAtMillis = absolute))
            }
            tick++
            pacer.awaitTick(tickMillis)
        }
    }
}
