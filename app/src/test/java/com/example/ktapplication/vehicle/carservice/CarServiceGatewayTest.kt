package com.example.ktapplication.vehicle.carservice

import com.example.ktapplication.core.DomainError
import com.example.ktapplication.core.FakeMonotonicClock
import com.example.ktapplication.core.Outcome
import com.example.ktapplication.core.SingleDispatcherAppDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CarService 接入层单测：退避曲线、连接状态机、订阅引用计数、缓存陈旧判定、写入安全规则。
 *
 * 这几段逻辑在真机上最难复现（binder 什么时候死、VHAL 什么时候回值都不由 App 决定），
 * 所以把它们做成可注入时钟/随机源/Registrar 的纯逻辑，用虚拟时间跑完。
 */
class CarServiceGatewayTest {

    private fun sample(
        property: CarVehicleProperty = CarVehicleProperty.PERF_VEHICLE_SPEED,
        value: PropertyValue,
        areaId: Int = GLOBAL_AREA_ID,
        at: Long = 10L,
        status: PropertyStatus = PropertyStatus.AVAILABLE,
    ) = CarPropertySample(property, areaId, value, status, at)

    private class RecordingRegistrar : PropertySubscriptionRegistry.Registrar {
        val registerCalls = mutableListOf<Pair<CarVehicleProperty, Int>>()
        val unregisterCalls = mutableListOf<Pair<CarVehicleProperty, Int>>()
        var registerResult = true
        var writeError: String? = null
        val written = mutableMapOf<Pair<CarVehicleProperty, Int>, PropertyValue>()

        override suspend fun register(property: CarVehicleProperty, areaId: Int): Boolean {
            registerCalls.add(property to areaId)
            return registerResult
        }

        override suspend fun unregister(property: CarVehicleProperty, areaId: Int) {
            unregisterCalls.add(property to areaId)
        }

        override suspend fun readSample(property: CarVehicleProperty, areaId: Int): CarPropertySample? {
            val value = written[property to areaId] ?: return null
            return CarPropertySample(property, areaId, value, PropertyStatus.AVAILABLE, 5L)
        }

        override suspend fun write(property: CarVehicleProperty, areaId: Int, value: PropertyValue): String? {
            writeError?.let { return it }
            written[property to areaId] = value
            return null
        }
    }

    private fun testScope(dispatcher: CoroutineDispatcher) = CoroutineScope(SupervisorJob() + dispatcher)

    // ---------------- 重连退避 ----------------

    @Test
    fun `退避是指数增长并封顶`() {
        val noJitter = ReconnectBackoff(initialDelayMillis = 1_000L, maxDelayMillis = 30_000L, jitterRatio = 0.0)
        assertEquals(1_000L, noJitter.delayBefore(0))
        assertEquals(2_000L, noJitter.delayBefore(1))
        assertEquals(4_000L, noJitter.delayBefore(2))
        // 2^5 * 1000 = 32000 → 封顶 30000
        assertEquals(30_000L, noJitter.delayBefore(5))
        assertEquals(30_000L, noJitter.delayBefore(20))
    }

    @Test
    fun `抖动必须落在声明的区间内`() {
        // jitterRatio 0.2 → 区间 [0.8, 1.2) * 计算值；random 可注入所以区间是硬断言
        fun delayWith(random: Double) =
            ReconnectBackoff(initialDelayMillis = 1_000L, jitterRatio = 0.2, random = { random }).delayBefore(0)

        assertEquals(800L, delayWith(0.0))
        assertEquals(1_000L, delayWith(0.5))
        assertTrue(delayWith(0.9999) < 1_200L)
        assertTrue(delayWith(0.0) < delayWith(0.9999))
        // 纯函数版给同样的结果，实现与规格不脱节
        assertEquals(1_200L, ReconnectBackoff.computeDelay(1, 1_000L, 30_000L, 2.0, 0.6))
    }

    @Test
    fun `重试有预算不会无限打系统服务`() {
        val backoff = ReconnectBackoff()
        assertFalse(backoff.shouldGiveUp(11))
        assertTrue(backoff.shouldGiveUp(12))
        assertTrue(backoff.shouldGiveUp(3, giveUpAfterAttempts = 3))
    }

    @Test
    fun `退避不接受负的尝试次数`() {
        assertTrue(runCatching { ReconnectBackoff().delayBefore(-1) }.isFailure)
    }

    // ---------------- 连接状态机 ----------------

    @Test
    fun `连接状态机按事件推进并记录轨迹`() {
        val clock = FakeMonotonicClock(5_000L)
        val transitions = mutableListOf<CarServiceState>()
        val coordinator = CarConnectionCoordinator(
            backoff = ReconnectBackoff(initialDelayMillis = 1_000L, maxDelayMillis = 30_000L, jitterRatio = 0.0),
            nowMillis = { clock.nowMillis() },
            onStateChange = { transitions.add(it) },
        )

        coordinator.onConnectRequested()
        assertTrue(coordinator.currentState is CarServiceState.Connecting)

        coordinator.onReady(setOf(CarVehicleProperty.PERF_VEHICLE_SPEED), "BEV")
        val ready = coordinator.currentState as CarServiceState.Ready
        assertEquals(setOf(CarVehicleProperty.PERF_VEHICLE_SPEED), ready.supportedProperties)
        assertEquals("BEV", ready.carType)
        assertEquals(5_000L, ready.connectedAtMillis)
        assertTrue(coordinator.currentState.isReady)

        // Ready 之后再点连接不应该把状态打回 Connecting
        coordinator.onConnectRequested()
        assertTrue(coordinator.currentState is CarServiceState.Ready)

        clock.set(9_000L)
        assertEquals(1_000L, coordinator.onConnectionLost("binderDied"))
        val reconnecting = coordinator.currentState as CarServiceState.Reconnecting
        assertEquals(1, reconnecting.attempt)
        assertEquals("binderDied", reconnecting.cause)
        assertEquals("车辆服务断开，第 1 次重连（binderDied）", reconnecting.userMessage)

        // 连续失败到预算后转 Unsupported，停在终态等外部触发
        var lastDelay = 0L
        repeat(11) { lastDelay = coordinator.onConnectionLost("binderDied") }
        assertTrue(coordinator.currentState is CarServiceState.Unsupported)
        assertTrue((coordinator.currentState as CarServiceState.Unsupported).reason.contains("重连 12 次"))
        assertEquals(30_000L, lastDelay)

        coordinator.reset()
        assertTrue(coordinator.currentState is CarServiceState.Disconnected)
        assertEquals("Connecting", transitions.first()::class.simpleName)
        assertEquals("Disconnected", transitions.last()::class.simpleName)
        assertEquals(11, transitions.count { it is CarServiceState.Reconnecting })
        assertEquals(15, transitions.size)
        assertTrue(coordinator.debugTransitions().contains("Disconnected"))
    }

    @Test
    fun `不支持 car framework 时直接 Unsupported`() {
        val transitions = mutableListOf<CarServiceState>()
        val coordinator = CarConnectionCoordinator(nowMillis = { 0L }, onStateChange = { transitions.add(it) })
        coordinator.onUnsupported("android.car.Car 不存在")
        assertTrue(coordinator.currentState is CarServiceState.Unsupported)
        assertTrue(coordinator.currentState.userMessage.contains("本机无车辆服务"))
    }

    // ---------------- 属性值类型收敛 ----------------

    @Test
    fun `写入前把值收敛成 VHAL 期望的类型`() {
        assertEquals(PropertyValue.Bool(true), PropertyValue.Float(1f).coercedTo(PropertyValueType.BOOLEAN))
        assertEquals(PropertyValue.Bool(false), PropertyValue.Int32(0).coercedTo(PropertyValueType.BOOLEAN))
        assertEquals(PropertyValue.Int32(22), PropertyValue.Float(22.7f).coercedTo(PropertyValueType.INT32))
        assertEquals(PropertyValue.Float(22.7f), PropertyValue.Double(22.7).coercedTo(PropertyValueType.FLOAT))
        assertEquals(PropertyValue.Str("22.7"), PropertyValue.Double(22.7).coercedTo(PropertyValueType.STRING))
        val array = PropertyValue.FloatArray(floatArrayOf(1f, 2f))
            .coercedTo(PropertyValueType.FLOAT_ARRAY) as PropertyValue.FloatArray
        assertTrue(array.value.contentEquals(floatArrayOf(1f, 2f)))
        // 字符串属性拿到 "22.5" 时仍应按数值解析
        assertEquals(12.5, PropertyValue.Str("12.5").asDoubleOrNull()!!, 1e-9)
        assertEquals(12, PropertyValue.Str("12.5").asIntOrNull())
        assertTrue(PropertyValue.Str("1").asBooleanOrNull()!!)
        assertNull(PropertyValue.Str("abc").asDoubleOrNull())
    }

    @Test
    fun `属性枚举与 VHAL 常量的对应关系`() {
        assertEquals("PERF_VEHICLE_SPEED", CarVehicleProperty.PERF_VEHICLE_SPEED.androidCarFieldName)
        assertEquals(CarVehicleProperty.DRIVETRAIN_RANGE, CarVehicleProperty.byCarFieldName("DRIVETRAIN_RANGE"))
        assertNull(CarVehicleProperty.byCarFieldName("NOT_A_REAL_PROPERTY"))
        // 档位改名：先查新常量，再回落旧常量
        assertEquals(
            listOf(CarVehicleProperty.DRIVETRAIN_GEAR_SELECTED, CarVehicleProperty.GEAR_SELECTION),
            CarVehicleProperty.gearCandidates(),
        )
        assertTrue(CarVehicleProperty.PERF_VEHICLE_SPEED.accessRestricted)
        assertFalse(CarVehicleProperty.EV_BATTERY_LEVEL.accessRestricted)
        assertEquals(PropertyRate.FAST, CarVehicleProperty.PERF_VEHICLE_SPEED.rate)
        assertEquals(PropertyChangeMode.CONTINUOUS_POLLING, CarVehicleProperty.PERF_VEHICLE_SPEED.changeMode)
        assertTrue(CarVehicleProperty.HVAC_TEMPERATURE_SET.areaScoped)
    }

    // ---------------- 订阅共享与引用计数 ----------------

    @Test
    fun `多个订阅者只注册一次且都能收到同一帧`() = runTest {
        val dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
        val scope = testScope(dispatcher)
        val registrar = RecordingRegistrar()
        val registry = PropertySubscriptionRegistry(
            FakeMonotonicClock(0L), SingleDispatcherAppDispatchers(dispatcher), scope, 1_000L, registrar,
        )

        val gauge = mutableListOf<CarPropertySample>()
        val voice = mutableListOf<CarPropertySample>()
        val jobA = scope.launch { registry.subscribe(CarVehicleProperty.PERF_VEHICLE_SPEED).collect { gauge.add(it) } }
        val jobB = scope.launch { registry.subscribe(CarVehicleProperty.PERF_VEHICLE_SPEED).collect { voice.add(it) } }
        runCurrent()

        assertEquals(1, registrar.registerCalls.size)
        assertEquals(1, registry.activeRegistrationCount)

        registry.publish(sample(value = PropertyValue.Float(30f), at = 10L))
        runCurrent()
        // Channel 分发型实现的典型 bug 是两个订阅者各收到一半；这里必须都收到
        assertEquals(listOf(30.0), gauge.map { it.numericValue })
        assertEquals(listOf(30.0), voice.map { it.numericValue })

        // 还剩一个订阅者时不能注销，否则另一个页面就再也收不到回调
        jobA.cancel()
        runCurrent()
        registry.release(CarVehicleProperty.PERF_VEHICLE_SPEED)
        advanceTimeBy(1_001L)
        assertEquals(0, registrar.unregisterCalls.size)
        assertEquals(1, registry.activeRegistrationCount)

        jobB.cancel()
        runCurrent()
        registry.release(CarVehicleProperty.PERF_VEHICLE_SPEED)
        advanceTimeBy(1_001L)
        assertEquals(listOf(CarVehicleProperty.PERF_VEHICLE_SPEED to GLOBAL_AREA_ID), registrar.unregisterCalls)
        assertEquals(0, registry.activeRegistrationCount)
        scope.cancel()
    }

    @Test
    fun `页面快速切换时延时注销避免来回注册`() = runTest {
        val dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
        val scope = testScope(dispatcher)
        val registrar = RecordingRegistrar()
        val registry = PropertySubscriptionRegistry(
            FakeMonotonicClock(0L), SingleDispatcherAppDispatchers(dispatcher), scope, 1_000L, registrar,
        )
        val property = CarVehicleProperty.HVAC_TEMPERATURE_SET

        val firstJob = scope.launch { registry.subscribe(property).collect { } }
        runCurrent()
        firstJob.cancel()
        runCurrent()
        registry.release(property)
        advanceTimeBy(500L)
        // 空窗期又来了新订阅：取消注销，复用同一条注册
        val secondJob = scope.launch { registry.subscribe(property).collect { } }
        runCurrent()
        advanceTimeBy(2_000L)
        assertEquals(1, registrar.registerCalls.size)
        assertEquals(0, registrar.unregisterCalls.size)
        secondJob.cancel()
        scope.cancel()
    }

    @Test
    fun `注册失败立刻回 NOT_AVAILABLE 样本而不是让 UI 一直转圈`() = runTest {
        val dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
        val scope = testScope(dispatcher)
        val registrar = RecordingRegistrar().apply { registerResult = false }
        val registry = PropertySubscriptionRegistry(
            FakeMonotonicClock(77L), SingleDispatcherAppDispatchers(dispatcher), scope, 1_000L, registrar,
        )

        val received = mutableListOf<CarPropertySample>()
        val job = scope.launch { registry.subscribe(CarVehicleProperty.ENGINE_RPM).collect { received.add(it) } }
        runCurrent()

        assertEquals(1, received.size)
        assertEquals(PropertyStatus.NOT_AVAILABLE, received.first().status)
        assertFalse(received.first().isUsable)
        assertEquals(77L, received.first().timestampMillis)
        job.cancel()
        scope.cancel()
    }

    @Test
    fun `区域不同的订阅是两条独立注册`() = runTest {
        val dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
        val scope = testScope(dispatcher)
        val registrar = RecordingRegistrar()
        val registry = PropertySubscriptionRegistry(
            FakeMonotonicClock(0L), SingleDispatcherAppDispatchers(dispatcher), scope, 1_000L, registrar,
        )
        val jobs = listOf(1, 2).map { area ->
            scope.launch { registry.subscribe(CarVehicleProperty.HVAC_TEMPERATURE_SET, area).first() }
        }
        runCurrent()
        assertEquals(2, registrar.registerCalls.size)
        assertEquals(2, registry.activeRegistrationCount)
        jobs.forEach { it.cancel() }
        scope.cancel()
    }

    // ---------------- 属性缓存 ----------------

    @Test
    fun `缓存只接受更晚时间戳的样本`() {
        val clock = FakeMonotonicClock(10_000L)
        val cache = PropertyCache(clock)
        cache.put(sample(value = PropertyValue.Float(90f), at = 9_000L))
        cache.put(sample(value = PropertyValue.Float(0f), at = 8_000L))  // 乱序旧值必须被丢掉
        assertEquals(90.0, cache.numeric(CarVehicleProperty.PERF_VEHICLE_SPEED)!!, 1e-6)

        cache.put(sample(value = PropertyValue.Float(91f), at = 9_500L))
        assertEquals(91.0, cache.numeric(CarVehicleProperty.PERF_VEHICLE_SPEED)!!, 1e-6)
        assertNull(cache.get(CarVehicleProperty.ODOMETER))
    }

    @Test
    fun `缓存过期按 UNAVAILABLE 处理`() {
        val clock = FakeMonotonicClock(10_000L)
        val cache = PropertyCache(clock)
        cache.put(sample(value = PropertyValue.Float(90f), at = 9_000L))
        assertEquals(90.0, cache.numeric(CarVehicleProperty.PERF_VEHICLE_SPEED)!!, 1e-6)

        clock.set(12_001L)  // 超过默认 2s 新鲜窗口
        assertNull(cache.numeric(CarVehicleProperty.PERF_VEHICLE_SPEED))
        val stale = cache.freshOf(CarVehicleProperty.PERF_VEHICLE_SPEED)!!
        assertEquals(PropertyStatus.UNAVAILABLE, stale.status)
        // 值本身还在，供诊断页显示"最后一次可信值"
        assertEquals(90.0, stale.value!!.asDoubleOrNull()!!, 1e-6)

        cache.clear()
        assertNull(cache.get(CarVehicleProperty.PERF_VEHICLE_SPEED))
    }

    @Test
    fun `不可用样本没有数值`() {
        val unavailable = CarPropertySample.unavailable(CarVehicleProperty.DOOR_LOCK, 1, 5L)
        assertFalse(unavailable.isUsable)
        assertNull(unavailable.numericValue)
        assertEquals(PropertyStatus.NOT_AVAILABLE, unavailable.status)
    }

    // ---------------- Mock 网关行为（可执行规格） ----------------

    @Test
    fun `行驶中禁止通过 CarService 解锁车门`() = runTest {
        val clock = FakeMonotonicClock(10_000L)
        val dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
        val scope = testScope(dispatcher)
        val gateway = MockCarServiceGateway(
            clock, SingleDispatcherAppDispatchers(dispatcher), scope, connectDelayMillis = 100L,
        )

        assertTrue(gateway.connect() is CarServiceState.Ready)

        gateway.injectProperty(CarVehicleProperty.PERF_VEHICLE_SPEED, PropertyValue.Float(35f))
        val whileMoving = gateway.write(CarVehicleProperty.DOOR_LOCK, PropertyValue.Bool(false))
        assertTrue(whileMoving is Outcome.Failure)
        val error = whileMoving.errorOrNull()
        assertTrue(error is DomainError.Rejected)
        assertTrue(error!!.userMessage.contains("VEHICLE_MOVING"))

        gateway.injectProperty(CarVehicleProperty.PERF_VEHICLE_SPEED, PropertyValue.Float(0f))
        assertTrue(gateway.write(CarVehicleProperty.DOOR_LOCK, PropertyValue.Bool(false)).isSuccess)
        val readBack = gateway.read(CarVehicleProperty.DOOR_LOCK).getOrNull()
        assertEquals(false, (readBack?.value as PropertyValue.Bool).value)

        gateway.disconnect()
        assertTrue(gateway.state.value is CarServiceState.Disconnected)
        scope.cancel()
    }

    @Test
    fun `EV 车型没有 ENGINE_RPM 时按不支持处理而不是 0`() = runTest {
        val clock = FakeMonotonicClock(10_000L)
        val dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
        val scope = testScope(dispatcher)
        val gateway = MockCarServiceGateway(
            clock, SingleDispatcherAppDispatchers(dispatcher), scope, connectDelayMillis = 50L,
        )
        gateway.connect()

        assertFalse(gateway.supports(CarVehicleProperty.ENGINE_RPM))
        val failure = gateway.read(CarVehicleProperty.ENGINE_RPM)
        assertTrue(failure is Outcome.Failure)
        // ENGINE_RPM 是 ACCESS_RESTRICTED 属性，按权限不足而不是"值为 0"报告
        assertEquals(403, (failure.errorOrNull() as DomainError.Rejected).code)

        val speed = gateway.read(CarVehicleProperty.PERF_VEHICLE_SPEED).getOrNull()
        assertNotNull(speed)
        assertEquals(CarVehicleProperty.PERF_VEHICLE_SPEED, speed!!.property)
        assertTrue(gateway.supports(CarVehicleProperty.PERF_VEHICLE_SPEED))
        scope.cancel()
    }

    @Test
    fun `订阅会先给缓存值`() = runTest {
        val clock = FakeMonotonicClock(10_000L)
        val dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
        val scope = testScope(dispatcher)
        val gateway = MockCarServiceGateway(
            clock, SingleDispatcherAppDispatchers(dispatcher), scope, connectDelayMillis = 0L,
            supportedProperties = setOf(CarVehicleProperty.EV_BATTERY_LEVEL),
        )
        gateway.connect()
        gateway.injectProperty(CarVehicleProperty.EV_BATTERY_LEVEL, PropertyValue.Float(55f))

        val firstSample = gateway.subscribe(CarVehicleProperty.EV_BATTERY_LEVEL).first()
        assertEquals(55.0, firstSample.numericValue!!, 1e-6)
        scope.cancel()
    }

    @Test
    fun `CarService 死亡后退避重连且期间缓存仍可读`() = runTest {
        val clock = FakeMonotonicClock(10_000L)
        val dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
        val scope = testScope(dispatcher)
        val gateway = MockCarServiceGateway(
            clock, SingleDispatcherAppDispatchers(dispatcher), scope,
            connectDelayMillis = 100L,
            supportedProperties = setOf(CarVehicleProperty.PERF_VEHICLE_SPEED, CarVehicleProperty.EV_BATTERY_LEVEL),
        )
        gateway.connect()
        runCurrent()
        assertTrue(gateway.state.value.isReady)

        gateway.simulateCarServiceDeath("vhal crash")
        val reconnecting = gateway.state.value as CarServiceState.Reconnecting
        assertEquals(1, reconnecting.attempt)

        // 断连期间 UI 不该突然全变 "--"：旧缓存仍然读得到
        gateway.injectProperty(CarVehicleProperty.EV_BATTERY_LEVEL, PropertyValue.Float(55f))
        assertEquals(55.0, gateway.subscribe(CarVehicleProperty.EV_BATTERY_LEVEL).first().numericValue!!, 1e-6)

        advanceTimeBy(2_000L)
        assertTrue("状态应为 Ready，实际 ${gateway.state.value}", gateway.state.value.isReady)
        scope.cancel()
    }

    @Test
    fun `区域能力决定 UI 画几个控件`() = runTest {
        val dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
        val scope = testScope(dispatcher)
        val gateway = MockCarServiceGateway(
            FakeMonotonicClock(0L), SingleDispatcherAppDispatchers(dispatcher), scope, connectDelayMillis = 0L,
        )

        assertEquals(listOf(1, 2), gateway.areasOf(CarVehicleProperty.HVAC_TEMPERATURE_SET))
        assertEquals(listOf(1, 2, 4, 8), gateway.areasOf(CarVehicleProperty.DOOR_LOCK))
        assertEquals(listOf(GLOBAL_AREA_ID), gateway.areasOf(CarVehicleProperty.PERF_VEHICLE_SPEED))
        assertEquals(listOf(GLOBAL_AREA_ID), gateway.areasOf(CarVehicleProperty.CHARGE_STATE))
        scope.cancel()
    }
}
