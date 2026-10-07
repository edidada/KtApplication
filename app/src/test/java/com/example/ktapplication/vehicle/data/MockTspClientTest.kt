package com.example.ktapplication.vehicle.data

import com.example.ktapplication.core.DomainError
import com.example.ktapplication.core.FakeMonotonicClock
import com.example.ktapplication.core.Outcome
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MockTspClientTest {

    private fun snapshot(soc: Double) = TspStatusSnapshot(
        vehicleId = "V1",
        reportedAtMillis = 1_000L,
        receivedAtMillis = 0L, // Mock 会盖接收时间戳，构造侧留 0。
        stateOfChargePercent = soc,
    )

    private fun command(id: String) = TspCommandRequest(
        commandId = id,
        vehicleId = "V1",
        actionCode = "DOOR_LOCK",
        parameters = emptyMap(),
        issuedAtMillis = 1_700_000_000_000L,
        idempotencyKey = "idem-$id",
    )

    @Test
    fun scriptedResponsesAreConsumedInOrder() = runTest {
        val client = MockTspClient()
        client.enqueueStatus(snapshot(10.0)).enqueueStatus(snapshot(20.0))
        client.enqueueAck(TspCommandAck.Accepted("SM-1"))
            .enqueueAck(TspCommandAck.Rejected(4001, "移动中", retryable = false))
        client.enqueueResult(TspCommandResult.Pending(null)).enqueueResult(TspCommandResult.Executed)

        assertEquals(10.0, (client.fetchStatus("V1") as Outcome.Success).value.stateOfChargePercent ?: 0.0, 1e-9)
        assertEquals(20.0, (client.fetchStatus("V1") as Outcome.Success).value.stateOfChargePercent ?: 0.0, 1e-9)

        assertEquals(TspCommandAck.Accepted("SM-1"), (client.submitCommand(command("c1")) as Outcome.Success).value)
        assertTrue((client.submitCommand(command("c2")) as Outcome.Success).value is TspCommandAck.Rejected)

        assertEquals(TspCommandResult.Pending(null), (client.queryCommandResult("c1") as Outcome.Success).value)
        assertEquals(TspCommandResult.Executed, (client.queryCommandResult("c1") as Outcome.Success).value)
        // 结果队列耗尽：回 Unknown（语义是"云端没结论"），不会抛。
        assertEquals(TspCommandResult.Unknown, (client.queryCommandResult("c1") as Outcome.Success).value)
        // 状态队列耗尽：明确失败而不是回一条假数据。
        assertFalse(client.fetchStatus("V1").isSuccess)
    }

    @Test
    fun receivedTimestampComesFromInjectedClock() = runTest {
        val clock = FakeMonotonicClock(startMillis = 777L)
        val client = MockTspClient(clock = clock)
        client.enqueueStatus(snapshot(50.0))
        assertEquals(777L, (client.fetchStatus("V1") as Outcome.Success).value.receivedAtMillis)
    }

    @Test
    fun failureRateOneFailsEverythingReproducibly() = runTest {
        // 固定种子 + failureRate=1.0：所有正常路径都必须失败，且两次运行模式一致。
        val patternA = runFaultyPattern(seed = 12345L)
        val patternB = runFaultyPattern(seed = 12345L)
        assertEquals(patternA, patternB)
        assertTrue("failureRate=1.0 应全失败: $patternA", patternA.none { it })

        // 中间比例 + 固定种子：成功/失败序列逐位可复现（回归定位全靠这个）。
        val halfA = runFaultyPattern(seed = 99L, rate = 0.5, rounds = 40)
        val halfB = runFaultyPattern(seed = 99L, rate = 0.5, rounds = 40)
        assertEquals(halfA, halfB)
        assertTrue("0.5 故障率应混合成功与失败: $halfA", halfA.any { it } && halfA.any { !it })
    }

    private suspend fun runFaultyPattern(seed: Long, rate: Double = 1.0, rounds: Int = 8): List<Boolean> {
        val client = MockTspClient(failureRate = rate, randomSeed = seed)
        val pattern = ArrayList<Boolean>(rounds)
        repeat(rounds) { index ->
            client.enqueueStatus(snapshot(index.toDouble()))
            pattern += client.fetchStatus("V1").isSuccess
        }
        return pattern
    }

    @Test
    fun offlineModeShortCircuitsWithConnectionUnavailable() = runTest {
        val client = MockTspClient()
        client.enqueueStatus(snapshot(30.0))
        client.online = false
        val result = client.fetchStatus("V1")
        val error = (result as Outcome.Failure).error
        assertTrue(error is DomainError.ConnectionUnavailable)
        assertEquals(TspConnectivity.OFFLINE, client.connectivity.value)

        client.online = true
        assertEquals(TspConnectivity.ONLINE, client.connectivity.value)
        // 恢复在线后脚本队列还在（离线只拦截，不消费）。
        val back = client.fetchStatus("V1") as Outcome.Success
        assertEquals(30.0, back.value.stateOfChargePercent ?: 0.0, 1e-9)
        assertEquals(TspConnectivity.ONLINE, client.probeConnectivity())
    }

    @Test
    fun virtualTimeAppliesInjectedLatency() = runTest {
        val client = MockTspClient(responseDelayMillis = 800L)
        client.enqueueStatus(snapshot(10.0))
        val start = testScheduler.currentTime
        client.fetchStatus("V1")
        // 虚拟时间被推进了整整一个 RTT：慢网路径不需要真 sleep 就能测。
        assertEquals(800L, testScheduler.currentTime - start)

        // delay=0 的 Mock 完全不消耗虚拟时间。
        val instant = MockTspClient(responseDelayMillis = 0L).apply { enqueueStatus(snapshot(10.0)) }
        val before = testScheduler.currentTime
        instant.fetchStatus("V1")
        assertEquals(before, testScheduler.currentTime)

        // 慢链路下的控车受理同样被虚拟时间覆盖。
        val slow = MockTspClient(responseDelayMillis = 20_000L).apply { enqueueAck(TspCommandAck.Accepted("SM")) }
        val slowStart = testScheduler.currentTime
        slow.submitCommand(command("c1"))
        assertEquals(20_000L, testScheduler.currentTime - slowStart)
    }

    @Test
    fun pushScriptEmitsInOrderWithInterval() = runTest {
        val client = MockTspClient()
        client.pushIntervalMillis = 0L
        client.pushScript = listOf(
            TspPushEvent.Heartbeat(1L),
            TspPushEvent.Alert(5, "胎压", AlertLevel.CRITICAL),
            TspPushEvent.SessionExpired,
        )
        val events = client.pushEvents.take(3).toList()
        assertEquals(client.pushScript, events)
    }

    @Test
    fun demoVehicleBuildsDecliningSocWithOccasionalCharging() = runTest {
        val clock = FakeMonotonicClock(startMillis = 0L)
        val client = MockTspClient(clock = clock).asDemoVehicle(vehicleId = "DEMO-1", clock = clock, demoTickCount = 60)
        val first = (client.fetchStatus("DEMO-1") as Outcome.Success).value
        assertEquals("DEMO-1", first.vehicleId)
        val socs = (0 until 30).mapNotNull {
            (client.fetchStatus("DEMO-1") as? Outcome.Success)?.value?.stateOfChargePercent
        }
        // 非充电段整体趋势向下；推送脚本长度与状态队列一致，可被上层按 5s 间隔消费。
        assertEquals(30, socs.size)
        assertTrue("demo 车辆 SOC 应出现波动/下降段: $socs", socs.last() != socs.first())
        assertEquals(60, client.pushScript.size)
    }
}
