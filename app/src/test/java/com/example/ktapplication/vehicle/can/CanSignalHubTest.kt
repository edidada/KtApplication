package com.example.ktapplication.vehicle.can

import com.example.ktapplication.core.FakeMonotonicClock
import com.example.ktapplication.core.SingleDispatcherAppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 信号中心链路单测：收帧 → 解码 → 逐信号更新 → 快照节流 → 超时降级 → 总线健康度。
 *
 * 全部用 [FakeMonotonicClock] + 虚拟时间驱动，没有 sleep，整套跑完不到 100ms。
 */
class CanSignalHubTest {

    private class StubCanBusClient : CanBusClient {
        private val source = MutableSharedFlow<CanFrame>(
            extraBufferCapacity = 128,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        override val availableBuses: Set<CanBus> = CanSignalCatalog.buses
        override val frames: Flow<CanFrame> = source

        private var connected = false
        override val isConnected: Boolean get() = connected
        override suspend fun connect(): Boolean {
            connected = true
            return true
        }

        override fun disconnect() {
            connected = false
        }

        fun emit(frame: CanFrame) {
            assertTrue("桩客户端缓冲区不应丢帧", source.tryEmit(frame))
        }
    }

    private val clock = FakeMonotonicClock(startMillis = 1_000L)

    private fun speedFrame(at: Long, physical: Double = 90.0): CanFrame =
        CanSignalEncoder.buildFrame(CanSignalCatalog.SPEED_KMH, listOf(physical), at)

    private fun socFrame(at: Long, physical: Double = 80.0): CanFrame =
        CanSignalEncoder.buildFrame(CanSignalCatalog.PACK_SOC_PERCENT, listOf(physical), at)

    /**
     * 断言辅助：取不到可信物理值时立刻失败并点名信号。
     *
     * [CanSignalHub.readPhysical] 返回 `Double?` 是刻意的 —— "没有值"和"值是 0"在车上是两回事；
     * 而 JUnit 的 `assertEquals(double, double, delta)` 只接受非空基本类型。
     * 这里用 `?: error(...)` 而不是 `!!`，失败时能看到"哪个信号没给值"，而不是 Kotlin 的 NPE 堆栈。
     */
    private fun CanSignalHub.physicalOf(name: String): Double =
        readPhysical(name) ?: error("$name 没有可信物理值")

    private fun VehicleCanSnapshot.physicalOf(name: String): Double =
        physical(name) ?: error("快照里 $name 没有可信物理值")

    private fun newHub(
        client: StubCanBusClient,
        definitions: List<CanSignalDefinition> = CanSignalCatalog.all,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined,
        staleSweepPeriodMillis: Long = 200L,
        snapshotPublishPeriodMillis: Long = 50L,
    ) = CanSignalHub(
        client = client,
        clock = clock,
        dispatchers = SingleDispatcherAppDispatchers(dispatcher),
        definitions = definitions,
        staleSweepPeriodMillis = staleSweepPeriodMillis,
        snapshotPublishPeriodMillis = snapshotPublishPeriodMillis,
    )

    @Test
    fun `ingest 同步解码后可立即按类型读取`() {
        val hub = newHub(StubCanBusClient())
        assertNull(hub.readPhysical("VEH_SPEED"))

        hub.ingest(speedFrame(at = 1_000L))
        hub.ingest(socFrame(at = 1_000L))

        assertEquals(90.0, hub.physicalOf("VEH_SPEED"), 1e-9)
        assertEquals(80.0, hub.physicalOf("BMS_SOC"), 1e-9)
        assertEquals(SignalQuality.VALID, hub.current("VEH_SPEED").quality)
        assertEquals("VEH_SPEED", hub.signalDefinition("VEH_SPEED")?.name)
        // 尚未建立过的信号必须给"没有值"，不能给 0
        assertNull(hub.readPhysical("ODO"))
        assertNull(hub.readInt("GEAR_SEL"))
    }

    @Test
    fun `无效码信号不对外给值`() {
        val hub = newHub(StubCanBusClient())
        hub.ingest(CanFrame.of(CanBus.POWERTRAIN, 0x2C0, "FF 1F 2C", receivedAtMillis = 1_000L))
        assertEquals(SignalQuality.INVALID_PATTERN, hub.current("BMS_SOC").quality)
        assertNull(hub.readPhysical("BMS_SOC"))
        // 同一帧里的电压信号仍然有效：一帧坏不代表整帧坏
        assertEquals(399.0, hub.physicalOf("BMS_VOLTAGE"), 1e-9)
    }

    @Test
    fun `帧率与总线健康度按到达帧统计`() {
        val hub = newHub(StubCanBusClient())
        repeat(10) { index -> hub.ingest(speedFrame(at = 1_000L + index * 20L)) }

        assertEquals(10.0, hub.frameRateHz.value, 1e-9)
        val chassis = hub.busHealth.value.getValue(CanBus.CHASSIS)
        assertEquals(10L, chassis.framesReceived)
        assertEquals(1_180L, chassis.lastFrameAtMillis)
        assertFalse(chassis.isSilent(1_180L))
        assertTrue(chassis.isSilent(3_181L))

        // 超出 1s 窗口的帧要滑出，否则会虚报帧率
        hub.ingest(speedFrame(at = 2_500L))
        assertTrue(hub.frameRateHz.value <= 2.0)
    }

    @Test
    fun `自定义信号表时只解自己认识的帧`() {
        val hub = newHub(StubCanBusClient(), definitions = listOf(CanSignalCatalog.SPEED_KMH))
        hub.ingest(speedFrame(at = 1_000L))
        hub.ingest(CanFrame.of(CanBus.CHASSIS, 0x0B4, "08 07", receivedAtMillis = 1_000L))
        hub.ingest(CanFrame.of(CanBus.BODY, 0x1E0, "7C 90 05", receivedAtMillis = 1_000L))

        assertEquals(90.0, hub.physicalOf("VEH_SPEED"), 1e-9)
        // 查不在信号表里的名字属于装配错误，hub 用 require(...) 直接抛 IllegalArgumentException；
        // 这里断言具体异常类型，是为了防止有人把它"顺手"改成返回 null —— 那会让信号漏配静默上线。
        assertThrows(IllegalArgumentException::class.java) { hub.current("WHEEL_SPD_FL") }
        // 未定义帧仍然计入总线健康度（排查"某信号没定义"就是从这里开始）
        assertEquals(2L, hub.busHealth.value.getValue(CanBus.CHASSIS).framesReceived)
        assertFalse(hub.busHealth.value.containsKey(CanBus.BODY))
    }

    @Test
    fun `快照按发布周期节流而不是每帧发布`() = runTest {
        val client = StubCanBusClient()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val hub = newHub(client, dispatcher = dispatcher, snapshotPublishPeriodMillis = 50L)

        hub.ingest(speedFrame(at = 1_000L))
        assertEquals(CanBusConnectionState.DISCONNECTED, hub.connectionState.value)
        assertNull(hub.snapshot.value.physical("VEH_SPEED"))

        hub.start(scope)
        runCurrent()
        assertEquals(CanBusConnectionState.STREAMING, hub.connectionState.value)
        // 信号本身即时更新，快照没有到发布点就不会变
        assertEquals(90.0, hub.current("VEH_SPEED").physicalValue!!, 1e-9)
        assertNull(hub.snapshot.value.physical("VEH_SPEED"))

        advanceTimeBy(60L)
        assertEquals(90.0, hub.snapshot.value.physicalOf("VEH_SPEED"), 1e-9)
        assertTrue(hub.snapshot.value.isFresh(1_000L + 60L))

        client.emit(socFrame(at = 1_010L))
        runCurrent()
        // 50Hz 的帧进来，快照最多 20Hz，UI 不会跟着 50Hz 重组
        assertEquals(80.0, hub.current("BMS_SOC").physicalValue!!, 1e-9)
        assertNull(hub.snapshot.value.physical("BMS_SOC"))
        advanceTimeBy(51L)
        assertEquals(80.0, hub.snapshot.value.physicalOf("BMS_SOC"), 1e-9)
        scope.cancel()
    }

    @Test
    fun `sweep 把超时信号降级为 STALE 并停止对外给值`() = runTest {
        val client = StubCanBusClient()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val hub = newHub(client, dispatcher = dispatcher, staleSweepPeriodMillis = 200L)
        hub.start(scope)
        runCurrent()

        client.emit(speedFrame(at = 1_000L))
        runCurrent()
        assertEquals(SignalQuality.VALID, hub.current("VEH_SPEED").quality)

        // 车速帧周期 20ms、timeout 60ms；时钟拨到 1s 后必然超时（总线静默）
        clock.set(2_000L)
        advanceTimeBy(250L)

        assertEquals(SignalQuality.STALE, hub.current("VEH_SPEED").quality)
        assertNull(hub.readPhysical("VEH_SPEED"))
        assertTrue(hub.busHealth.value.getValue(CanBus.CHASSIS).signalsTimedOut >= 1)
        // 陈旧值进快照后 unreliableSignals 要能列出来，供诊断页展示
        advanceTimeBy(60L)
        assertTrue("VEH_SPEED" in hub.snapshot.value.unreliableSignals)
        scope.cancel()
    }

    @Test
    fun `断连后状态回到 DISCONNECTED`() = runTest {
        val client = StubCanBusClient()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val hub = newHub(client, dispatcher = dispatcher)
        hub.start(scope)
        runCurrent()
        assertTrue(client.isConnected)

        hub.stop()
        assertEquals(CanBusConnectionState.DISCONNECTED, hub.connectionState.value)
        assertFalse(client.isConnected)
        assertNotNull(hub.busHealth.value)
        scope.cancel()
    }
}
