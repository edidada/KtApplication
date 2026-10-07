package com.example.ktapplication.vehicle.domain.model

import com.example.ktapplication.core.DomainError
import com.example.ktapplication.vehicle.domain.telemetryOf
import com.example.ktapplication.vehicle.domain.vehicleStatusFixture
import com.example.ktapplication.vehicle.domain.repo.CommandVerificationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 远程控车规则单测。
 *
 * 控车是"错了会出事"的功能，所以这三块必须钉死：
 *  1. 前置条件（什么状态下允许下发动作，未知状态一律按不安全处理）；
 * 2. 幂等键（重试不能变成重复下发，用户连点也不能被云端合并成一条）；
 *  3. 结果核验（指令"完成"的定义是车况真的变了，不是云端回了 200）。
 */
class RemoteControlPolicyTest {

    private val now = 200_000L

    private fun violation(action: RemoteAction, status: VehicleStatus?) =
        RemotePreconditionPolicy.evaluate(action, status, now)

    // ---------------- 前置条件 ----------------

    @Test
    fun `没有车况时只允许刷新这种只读动作`() {
        assertNull(violation(RemoteAction.REFRESH_STATUS, null))
        val error = violation(RemoteAction.LOCK_DOORS, null)
        assertTrue(error is DomainError.ConnectionUnavailable)
        assertTrue(error!!.userMessage.contains("暂无车辆状态"))
    }

    @Test
    fun `车况过期时拒绝下发物理动作`() {
        val stale = vehicleStatusFixture(updatedAt = now - 60_000L, busState = "OFFLINE")
        val error = violation(RemoteAction.UNLOCK_DOORS, stale)
        assertTrue(error is DomainError.SignalUnreliable)
        assertNotNull((error as DomainError.SignalUnreliable).signalName)
        // 只读动作不受影响，用户仍然可以手动刷新
        assertNull(violation(RemoteAction.REFRESH_STATUS, stale))
    }

    @Test
    fun `总线还在推流时不因时间戳判过期`() {
        // CAN 在跑（STREAMING）但字段时间戳很旧：说明车就是静止的，不能拒绝控车
        assertNull(violation(RemoteAction.UNLOCK_DOORS, vehicleStatusFixture(updatedAt = now - 60_000L)))
    }

    @Test
    fun `行驶中禁止解锁与需要静止的动作`() {
        assertNull(violation(RemoteAction.UNLOCK_DOORS, vehicleStatusFixture(speed = 0.0)))
        val moving = violation(RemoteAction.UNLOCK_DOORS, vehicleStatusFixture(speed = 30.0))
        assertTrue(moving is DomainError.Rejected)
        assertEquals(4001, (moving as DomainError.Rejected).code)
        // 1 km/h 容差内的蠕行仍算静止
        assertNull(violation(RemoteAction.VENTILATE_WINDOWS, vehicleStatusFixture(speed = 0.9)))
        assertEquals(4001, violation(RemoteAction.HONK_HORN, vehicleStatusFixture(speed = 5.0)).let { (it as DomainError.Rejected).code })
    }

    @Test
    fun `车速未知时宁可拒绝也不能默认成静止`() {
        // 这是最容易写错的一条：value=null 时 orDefault(0.0) 会让"解锁"被放行
        val unknownSpeed = vehicleStatusFixture().copy(
            speedKilometresPerHour = Telemetry(null, ValueSource.CAN_BUS, now, DataFreshness.UNAVAILABLE),
        )
        val error = violation(RemoteAction.UNLOCK_DOORS, unknownSpeed)
        assertTrue(error is DomainError.SignalUnreliable)
        assertEquals("车速", (error as DomainError.SignalUnreliable).signalName)
    }

    @Test
    fun `充电相关动作的条件`() {
        val parked = vehicleStatusFixture(gear = GearPosition.PARK, soc = 60.0, pluggedIn = true, chargeCurrent = 120.0, chargePower = 6.5)
        assertNull(violation(RemoteAction.START_CHARGE, parked))
        // 非 P 挡：远程启动充电属于安全问题
        assertEquals(4002, (violation(RemoteAction.START_CHARGE, parked.copy(gear = Telemetry(GearPosition.DRIVE, ValueSource.CAN_BUS, now))) as DomainError.Rejected).code)
        // 已充满
        val full = parked.copy(stateOfChargePercent = telemetryOf(100.0, at = now))
        assertEquals(4004, (violation(RemoteAction.START_CHARGE, full) as DomainError.Rejected).code)
        // 没在充电却要停止充电
        val notCharging = vehicleStatusFixture(pluggedIn = false)
        assertEquals(4003, (violation(RemoteAction.STOP_CHARGE, notCharging) as DomainError.Rejected).code)
        // 档位不可信时也拒绝，而不是当成非 P
        val unknownGear = parked.copy(gear = Telemetry(null, ValueSource.CAN_BUS, now, DataFreshness.UNAVAILABLE))
        assertTrue(violation(RemoteAction.START_CHARGE, unknownGear) is DomainError.SignalUnreliable)
    }

    @Test
    fun `设定温度必须落在合法区间`() {
        assertNull(violation(RemoteAction.SET_TARGET_TEMPERATURE, vehicleStatusFixture(targetTemp = 24.0)))
        assertEquals(4005, (violation(RemoteAction.SET_TARGET_TEMPERATURE, vehicleStatusFixture(targetTemp = 5.0)) as DomainError.Rejected).code)
        assertEquals(4005, (violation(RemoteAction.SET_TARGET_TEMPERATURE, vehicleStatusFixture(targetTemp = 33.0)) as DomainError.Rejected).code)
    }

    @Test
    fun `寻车类动作不设车辆状态门槛`() {
        assertNull(violation(RemoteAction.FLASH_LIGHTS, vehicleStatusFixture(speed = 80.0)))
        // 但鸣笛要求静止，避免扰民与误按
        assertEquals(4001, (violation(RemoteAction.HONK_HORN, vehicleStatusFixture(speed = 80.0)) as DomainError.Rejected).code)
    }

    @Test
    fun `每个动作都有超时预算且带物理风险的默认不自动重试`() {
        RemoteAction.entries.forEach { action ->
            assertTrue("${action.name} 预算非法", action.timeoutMillis in 5_000..60_000)
            assertNotNull(action.displayName)
            assertTrue(action.tspCode.isNotEmpty())
        }
        assertFalse(RemoteAction.UNLOCK_DOORS.autoRetryAllowed)
        assertFalse(RemoteAction.UNLOCK_DOORS.idempotent)
        assertFalse(RemoteAction.START_CHARGE.autoRetryAllowed)
        assertFalse(RemoteAction.STOP_CHARGE.idempotent)
        assertTrue(RemoteAction.LOCK_DOORS.autoRetryAllowed)
        assertTrue(RemoteAction.START_CLIMATE.requiresParked == false)
    }

    // ---------------- 幂等键 ----------------

    @Test
    fun `同一分钟内的重复意图落到同一个幂等键`() {
        // 时间桶按绝对分钟切（issuedAt / 60_000）：1_000_000 与 1_019_000 都在第 16 个桶里。
        val base = RemoteCommand.idempotencyKey("V1", RemoteAction.UNLOCK_DOORS, 1_000_000L, emptyMap())
        val again = RemoteCommand.idempotencyKey("V1", RemoteAction.UNLOCK_DOORS, 1_019_000L, emptyMap())
        assertEquals(base, again)

        val differentMinute = RemoteCommand.idempotencyKey("V1", RemoteAction.UNLOCK_DOORS, 1_060_000L, emptyMap())
        assertFalse(base == differentMinute)

        // 桶边界的固有代价：相隔 2s 但跨了整分钟就是两个键。
        // 这里宁可让云端多做一次去重查询，也不能把两次真实意图合成一次 ——
        // 第二次往往是用户看到没反应之后的重发，吞掉它就变成"点了没反应"。
        val acrossBoundary = RemoteCommand.idempotencyKey("V1", RemoteAction.UNLOCK_DOORS, 1_021_000L, emptyMap())
        assertFalse(base == acrossBoundary)
    }

    @Test
    fun `参数顺序不影响幂等键但参数值影响`() {
        val a = RemoteCommand.idempotencyKey("V1", RemoteAction.SET_TARGET_TEMPERATURE, 1_000_000L, mapOf("celsius" to "24", "zone" to "driver"))
        val b = RemoteCommand.idempotencyKey("V1", RemoteAction.SET_TARGET_TEMPERATURE, 1_000_000L, mapOf("zone" to "driver", "celsius" to "24"))
        assertEquals(a, b)
        val c = RemoteCommand.idempotencyKey("V1", RemoteAction.SET_TARGET_TEMPERATURE, 1_000_000L, mapOf("celsius" to "26", "zone" to "driver"))
        assertFalse(a == c)
    }

    @Test
    fun `幂等键不含尝试次数`() {
        val command = RemoteCommand(
            id = "c1", vehicleId = "V1", action = RemoteAction.LOCK_DOORS,
            issuedAtMillis = 1_000_000L,
            idempotencyKey = RemoteCommand.idempotencyKey("V1", RemoteAction.LOCK_DOORS, 1_000_000L, emptyMap()),
        )
        val retried = command.nextAttempt().nextAttempt()
        assertEquals(2, retried.attempt)
        assertEquals(command.idempotencyKey, retried.idempotencyKey)
        assertEquals(command.action, retried.action)
        assertNull(command.parameter("celsius"))
        assertEquals("24", command.copy(parameters = mapOf("celsius" to "24")).parameter("celsius"))
    }

    @Test
    fun `不同车辆与不同动作的键不同`() {
        val lock = RemoteCommand.idempotencyKey("V1", RemoteAction.LOCK_DOORS, 1_000_000L, emptyMap())
        val unlock = RemoteCommand.idempotencyKey("V1", RemoteAction.UNLOCK_DOORS, 1_000_000L, emptyMap())
        val otherVehicle = RemoteCommand.idempotencyKey("V2", RemoteAction.LOCK_DOORS, 1_000_000L, emptyMap())
        assertTrue(lock != unlock && lock != otherVehicle)
    }

    // ---------------- 阶段与进度 ----------------

    @Test
    fun `结果未知不是终态失败但确实是终态且文案不能写失败`() {
        val unknown = CommandStage.ResultUnknown(15_000L)
        assertTrue(unknown.isTerminal)
        assertTrue(unknown.userMessage.contains("待确认"))
        assertFalse(unknown.userMessage.contains("失败"))
        assertTrue(CommandStage.Executed.isTerminal)
        assertFalse(CommandStage.AwaitingVehicleResult.isTerminal)
        assertFalse(CommandStage.Sending(0).isTerminal)
        assertTrue(CommandStage.Rejected(4001, "移动中").userMessage.contains("拒绝"))
        assertTrue(CommandStage.QueuedOffline("无网络").userMessage.contains("排队"))
        assertEquals("车辆执行中", CommandStage.AwaitingVehicleResult.userMessage)
    }

    @Test
    fun `进度对象把阶段映射成领域错误`() {
        val command = RemoteCommand(
            id = "c1", vehicleId = "V1", action = RemoteAction.LOCK_DOORS,
            issuedAtMillis = 1_000_000L, idempotencyKey = "k",
        )
        val progress = CommandProgress(
            command = command,
            stage = CommandStage.Rejected(4001, "车辆仍在移动"),
            startedAtMillis = 1_000_000L,
            updatedAtMillis = 1_008_000L,
            remainingMillis = null,
        )
        assertEquals(8_000L, progress.elapsedMillis)
        assertEquals(DomainError.Rejected(4001, "车辆仍在移动"), progress.errorOrNull())

        val queued = progress.copy(stage = CommandStage.QueuedOffline("无网络"))
        assertTrue(queued.errorOrNull() is DomainError.ConnectionUnavailable)
        val running = progress.copy(stage = CommandStage.Accepted("srv-1"))
        assertNull(running.errorOrNull())
        val unknownResult = progress.copy(stage = CommandStage.ResultUnknown(15_000L))
        assertTrue(unknownResult.errorOrNull() is DomainError.ResultUnknown)
    }

    // ---------------- 结果核验 ----------------

    @Test
    fun `完成判定以车况变化为准`() {
        val locked = vehicleStatusFixture(lock = LockState.LOCKED)
        val unlocked = vehicleStatusFixture(lock = LockState.UNLOCKED)
        assertTrue(CommandVerificationPolicy.effectOf(RemoteAction.LOCK_DOORS).invoke(locked))
        assertFalse(CommandVerificationPolicy.effectOf(RemoteAction.LOCK_DOORS).invoke(unlocked))
        assertTrue(CommandVerificationPolicy.effectOf(RemoteAction.UNLOCK_DOORS).invoke(unlocked))

        // 空调：以压缩机状态为准
        assertTrue(CommandVerificationPolicy.effectOf(RemoteAction.START_CLIMATE).invoke(locked.copy(climate = locked.climate.copy(compressorOn = true))))
        assertTrue(CommandVerificationPolicy.effectOf(RemoteAction.STOP_CLIMATE).invoke(locked))

        // 温度有 0.5℃ 量化误差，允许 ±1℃
        val at24 = vehicleStatusFixture(targetTemp = 24.0)
        val effect = CommandVerificationPolicy.effectOf(RemoteAction.SET_TARGET_TEMPERATURE, mapOf("celsius" to "24"))
        assertTrue(effect.invoke(vehicleStatusFixture(targetTemp = 23.5)))
        assertTrue(effect.invoke(at24))
        assertFalse(effect.invoke(vehicleStatusFixture(targetTemp = 20.0)))
        assertFalse(effect.invoke(vehicleStatusFixture(targetTemp = 28.0)))
        assertFalse(CommandVerificationPolicy.effectOf(RemoteAction.SET_TARGET_TEMPERATURE, emptyMap()).invoke(at24))

        // 瞬时动作（闪灯/鸣笛）车况里不留痕，只能以云端回报为准
        assertTrue(CommandVerificationPolicy.effectOf(RemoteAction.FLASH_LIGHTS).invoke(locked))
    }

    @Test
    fun `充电开关的核验用 isCharging 而不是单看功率`() {
        val charging = vehicleStatusFixture(pluggedIn = true, chargeCurrent = 120.0, chargePower = 6.5)
        val idle = vehicleStatusFixture(pluggedIn = true, chargeCurrent = 0.0, chargePower = 0.0)
        assertTrue(CommandVerificationPolicy.effectOf(RemoteAction.START_CHARGE).invoke(charging))
        assertFalse(CommandVerificationPolicy.effectOf(RemoteAction.START_CHARGE).invoke(idle))
        assertTrue(CommandVerificationPolicy.effectOf(RemoteAction.STOP_CHARGE).invoke(idle))
        // 充电口解锁的判定用"锁已开"
        assertTrue(CommandVerificationPolicy.effectOf(RemoteAction.UNLOCK_CHARGE_PORT).invoke(idle.copy(charging = idle.charging.copy(chargePortLocked = false))))
    }

    @Test
    fun `核验窗口按动作分级`() {
        assertEquals(45_000L, CommandVerificationPolicy.verifyWindowMillis(RemoteAction.START_CLIMATE))
        assertEquals(60_000L, CommandVerificationPolicy.verifyWindowMillis(RemoteAction.START_CHARGE))
        assertEquals(CommandVerificationPolicy.DEFAULT_VERIFY_WINDOW_MILLIS, CommandVerificationPolicy.verifyWindowMillis(RemoteAction.LOCK_DOORS))
    }

    @Test
    fun `云端已受理之后绝不再自动重试`() {
        assertTrue(CommandVerificationPolicy.canRetry(RemoteAction.LOCK_DOORS, attempts = 0, acceptedByServer = false))
        assertTrue(CommandVerificationPolicy.canRetry(RemoteAction.LOCK_DOORS, attempts = 2, acceptedByServer = false, maxAttempts = 3))
        assertFalse(CommandVerificationPolicy.canRetry(RemoteAction.LOCK_DOORS, attempts = 3, acceptedByServer = false))
        // 已受理后再发就是两条真指令：车门会被反复开关
        assertFalse(CommandVerificationPolicy.canRetry(RemoteAction.LOCK_DOORS, attempts = 0, acceptedByServer = true))
        assertFalse(CommandVerificationPolicy.canRetry(RemoteAction.UNLOCK_DOORS, attempts = 0, acceptedByServer = false))
    }

    @Test
    fun `领域错误的用户文案都可用`() {
        val errors: List<DomainError> = listOf(
            DomainError.ConnectionUnavailable("车机未就绪"),
            DomainError.ResultUnknown("解锁", 15_000L),
            DomainError.Rejected(4001, "移动中"),
            DomainError.Unsupported("座椅加热"),
            DomainError.Malformed("缺少 soc 字段"),
            DomainError.SignalUnreliable("车速", "STALE"),
        )
        errors.forEach { assertTrue(it.userMessage.isNotEmpty()) }
        assertTrue(errors.map { it.userMessage }.distinct().size == errors.size)
    }
}
