package com.example.ktapplication.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流控工具的行为用例。
 *
 * 全部用 [runTest] 的虚拟时间 + [FakeMonotonicClock]，没有一个 sleep：
 * 节流/单飞这类逻辑一旦靠 sleep 测，就会变成"CI 上偶发红灯"的来源，
 * 而车机项目里这种测试最后会被直接删掉 —— 等于没有测试。
 */
class FlowControlTest {

    @Test
    fun sameKeyWhileInFlightGoesToBusyBranch() = runTest {
        val flight = SingleFlight()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        val holder = async {
            flight.runOnce("UNLOCK_DOORS:", onBusy = { "busy" }) {
                entered.complete(Unit)
                gate.await()
                "done"
            }
        }
        entered.await()
        assertTrue(flight.isBusy("UNLOCK_DOORS:"))
        assertEquals(setOf("UNLOCK_DOORS:"), flight.busyKeys)

        // 第二次点击：不排队、不挂起，直接拿到降级结果。
        var secondRan = false
        val second = flight.runOnce("UNLOCK_DOORS:", onBusy = { "busy" }) { secondRan = true; "second" }
        assertEquals("busy", second)
        assertFalse("被挡住的调用绝不能执行副作用", secondRan)

        // 不同 key 不受影响（解锁在跑不该挡住鸣笛）。
        assertEquals("ok", flight.runOnce("HONK_HORN:", onBusy = { "busy" }) { "ok" })

        gate.complete(Unit)
        assertEquals("done", holder.await())
        assertFalse(flight.isBusy("UNLOCK_DOORS:"))
        // 释放后同一 key 必须能再次下发，否则用户"这次没反应，再点一次"永远无效。
        assertEquals("again", flight.runOnce("UNLOCK_DOORS:", onBusy = { "busy" }) { "again" })
    }

    @Test
    fun cancelledHolderReleasesTheKey() = runTest {
        val flight = SingleFlight()
        val entered = CompletableDeferred<Unit>()
        val holder = launch {
            flight.runOnce("HONK_HORN:", onBusy = { "busy" }) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        assertTrue(flight.isBusy("HONK_HORN:"))
        holder.cancelAndJoin()

        // 这条断言就是当初把 Mutex 换成 synchronized 的原因：
        // Mutex.withLock 在已取消的协程里会立刻抛 CancellationException，finally 的释放走不到，
        // key 永久占用，功能"突然再也不响应"且日志无痕。
        assertFalse("持有者被取消后必须释放占用", flight.isBusy("HONK_HORN:"))
        assertEquals("ok", flight.runOnce("HONK_HORN:", onBusy = { "busy" }) { "ok" })
    }

    @Test
    fun failingBlockAlsoReleasesTheKey() = runTest {
        val flight = SingleFlight()
        // runCatching 是 inline，能包住挂起调用；这里要的是"异常不吞掉释放"。
        val outcome = runCatching {
            flight.runOnce("START_CLIMATE:", onBusy = { "busy" }) {
                error("执行器无响应")
            }
        }
        assertTrue(outcome.isFailure)
        assertFalse(flight.isBusy("START_CLIMATE:"))
        assertEquals("ok", flight.runOnce("START_CLIMATE:", onBusy = { "busy" }) { "ok" })
    }

    @Test
    fun throttleFirstKeepsLeadingEdgeOnly() = runTest {
        val clock = FakeMonotonicClock(startMillis = 1_000L)
        // 每 500ms 一次点击，窗口 1200ms：只应放行第 0 次（首击）和第 3 次（距上次 1500ms）。
        val clicks = flow {
            repeat(5) { index ->
                if (index > 0) clock.advance(500)
                emit(index)
            }
        }
        val passed = clicks.throttleFirst(windowMillis = 1_200L, clock = clock).toList()
        assertEquals(listOf(0, 3), passed)
    }

    @Test
    fun throttleFirstPassesFirstElementEvenAtClockZero() = runTest {
        // 回归用例：早期写法用 Long.MIN_VALUE 当"上次放行时间"，减法溢出后首次也可能被吞。
        val clock = FakeMonotonicClock(startMillis = 0L)
        val passed = flowOf(1, 2, 3).throttleFirst(windowMillis = 1_000L, clock = clock).toList()
        assertEquals(listOf(1), passed)
    }

    @Test
    fun throttleFirstWithZeroWindowPassesEverything() = runTest {
        val clock = FakeMonotonicClock(0L)
        val passed = flowOf(1, 2, 3).throttleFirst(windowMillis = 0L, clock = clock).toList()
        assertEquals(listOf(1, 2, 3), passed)
    }

    @Test
    fun throttleFirstRejectsNegativeWindow() {
        assertThrows(IllegalArgumentException::class.java) {
            flowOf(1).throttleFirst(windowMillis = -1L, clock = FakeMonotonicClock(0L))
        }
    }

    @Test
    fun leadingThrottleIsPerKeyAndResettable() {
        val clock = FakeMonotonicClock(startMillis = 0L)
        val throttle = LeadingThrottle(windowMillis = 1_000L, clock = clock)

        assertTrue(throttle.allow("refresh"))
        assertFalse(throttle.allow("refresh"))
        // 另一个目标独立计时，不被"刷新"的窗口连坐。
        assertTrue(throttle.allow("locate"))

        clock.advance(999)
        assertFalse(throttle.allow("refresh"))
        clock.advance(1)
        assertTrue("窗口过期后必须重新放行", throttle.allow("refresh"))

        throttle.reset("refresh")
        assertTrue("reset 之后允许立即再来一次（下拉刷新完成时要清窗口）", throttle.allow("refresh"))

        assertThrows(IllegalArgumentException::class.java) { LeadingThrottle(-5L, clock) }
    }
}
