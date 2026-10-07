package com.example.ktapplication.core

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 领域层重试策略用例。
 *
 * 时序断言全部走 [runTest] 的虚拟时钟（`currentTime`）：
 * "退避到底等了多久"是策略的核心输出，用 sleep 测既慢又测不准。
 */
class RetryPolicyTest {

    @Test
    fun backoffDoublesThenCaps() {
        val policy = RetryPolicy(maxAttempts = 3, baseDelay = 200.milliseconds, maxDelay = 2.seconds, jitterRatio = 0.0)
        assertEquals(200.milliseconds, policy.delayAfter(1))
        assertEquals(400.milliseconds, policy.delayAfter(2))
        assertEquals(800.milliseconds, policy.delayAfter(3))
        // 指数不能无限左移（长期弱网会把等待推到 Long 溢出），触顶后就是 maxDelay。
        assertEquals(2.seconds, policy.delayAfter(10))
    }

    @Test
    fun jitterOnlyExtendsAndStaysWithinBudget() {
        val policy = RetryPolicy(maxAttempts = 3, baseDelay = 200.milliseconds, maxDelay = 2.seconds, jitterRatio = 0.2)
        assertEquals(200.milliseconds, policy.delayAfter(1, jitterUnit = 0.0))
        assertEquals(240.milliseconds, policy.delayAfter(1, jitterUnit = 1.0))
        // 触顶后叠加抖动，仍被 maxDelay*2 兜住：上层可以按这个最坏值预算总超时。
        assertEquals(2_400.milliseconds, policy.delayAfter(20, jitterUnit = 1.0))
        // 抖动因子越界一律夹回 0..1，负数不能给出"立即重试"，超大数不能给出"等十分钟"。
        assertEquals(240.milliseconds, policy.delayAfter(1, jitterUnit = 5.0))
        assertEquals(200.milliseconds, policy.delayAfter(1, jitterUnit = -1.0))
    }

    @Test
    fun policyConstructorRejectsNonsense() {
        assertThrows(IllegalArgumentException::class.java) { RetryPolicy(maxAttempts = 0) }
        assertThrows(IllegalArgumentException::class.java) { RetryPolicy(jitterRatio = 1.5) }
        assertThrows(IllegalArgumentException::class.java) {
            RetryPolicy(baseDelay = 1.seconds, maxDelay = 200.milliseconds)
        }
        assertThrows(IllegalArgumentException::class.java) { RetryPolicy.remoteCommand.delayAfter(0) }
    }

    @Test
    fun onlyConnectionLossIsRetryable() {
        val policy = RetryPolicy.statusSync
        assertTrue(policy.isRetryable(DomainError.ConnectionUnavailable("TSP 未就绪")))
        // 安全红线：结果未知绝不重试，车辆可能已经执行过一次了。
        assertFalse(policy.isRetryable(DomainError.ResultUnknown("UNLOCK_DOORS", 8_000L)))
        assertFalse(policy.isRetryable(DomainError.Rejected(403, "车速大于 0")))
        assertFalse(policy.isRetryable(DomainError.Unsupported("REMOTE_START")))
        assertFalse(policy.isRetryable(DomainError.Malformed("缺少 data 字段")))
        assertFalse(policy.isRetryable(DomainError.SignalUnreliable("vehicleSpeed", "INVALID")))
    }

    @Test
    fun successOnFirstAttemptDoesNotWait() = runTest {
        var calls = 0
        val outcome = retryOn<String>(RetryPolicy(maxAttempts = 3, baseDelay = 100.milliseconds)) {
            calls += 1
            Outcome.success("ok")
        }
        assertEquals("ok", outcome.getOrNull())
        assertEquals(1, calls)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun connectionFailureRetriesWithBackoff() = runTest {
        val policy = RetryPolicy(maxAttempts = 3, baseDelay = 200.milliseconds, maxDelay = 2.seconds, jitterRatio = 0.0)
        val attempts = mutableListOf<Int>()
        val retries = mutableListOf<Int>()
        val outcome = retryOn<String>(
            policy = policy,
            onRetry = { attempt, error ->
                retries += attempt
                assertTrue(error is DomainError.ConnectionUnavailable)
            },
        ) { attempt ->
            attempts += attempt
            if (attempt < 3) {
                Outcome.failure(DomainError.ConnectionUnavailable("TSP 未就绪"))
            } else {
                Outcome.success("ok")
            }
        }
        assertEquals(listOf(1, 2, 3), attempts)
        assertEquals(listOf(1, 2), retries)
        assertEquals("ok", outcome.getOrNull())
        // 虚拟时间 200 + 400 = 600ms：证明是真按指数退避推进，而不是循环立刻重投。
        assertEquals(600L, testScheduler.currentTime)
    }

    @Test
    fun resultUnknownReturnsImmediatelyWithoutConsumingAttempts() = runTest {
        var calls = 0
        val outcome = retryOn<String>(RetryPolicy(maxAttempts = 5, baseDelay = 200.milliseconds)) {
            calls += 1
            Outcome.failure(DomainError.ResultUnknown("UNLOCK_DOORS", 8_000L))
        }
        assertEquals(1, calls)
        assertEquals(0L, testScheduler.currentTime)
        assertTrue(outcome.errorOrNull() is DomainError.ResultUnknown)
        // UI 拿到的文案是"待确认"，不是"失败"，用户才不会又按一次解锁。
        assertEquals("指令状态待确认，请稍后查看车辆状态", outcome.errorOrNull()?.userMessage)
    }

    @Test
    fun retriesStopAtMaxAttemptsAndReturnLastFailure() = runTest {
        val policy = RetryPolicy(maxAttempts = 2, baseDelay = 250.milliseconds, maxDelay = 1.seconds, jitterRatio = 0.0)
        var calls = 0
        val outcome = retryOn<String>(policy) {
            calls += 1
            Outcome.failure(DomainError.ConnectionUnavailable("第 $calls 次仍未就绪"))
        }
        assertEquals(2, calls)
        // 两次尝试之间只等一次退避；最后一次失败不再 delay（没有下次了）。
        assertEquals(250L, testScheduler.currentTime)
        assertEquals("车机服务未就绪：第 2 次仍未就绪", outcome.errorOrNull()?.userMessage)
    }

    @Test
    fun nonePolicyCallsBlockExactlyOnce() = runTest {
        var calls = 0
        val outcome = retryOn<String>(RetryPolicy.none) {
            calls += 1
            Outcome.failure(DomainError.ConnectionUnavailable("离线"))
        }
        assertEquals(1, calls)
        assertFalse(outcome.isSuccess)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun jitterIsSuppliedByCallerSoTimingIsReproducible() = runTest {
        val policy = RetryPolicy(maxAttempts = 3, baseDelay = 100.milliseconds, maxDelay = 1.seconds, jitterRatio = 0.5)
        var calls = 0
        retryOn<String>(policy, jitter = { attempt -> if (attempt == 1) 1.0 else 0.0 }) {
            calls += 1
            Outcome.failure(DomainError.ConnectionUnavailable("离线"))
        }
        assertEquals(3, calls)
        // 100*(1+0.5)=150，第二次 jitter=0 → 200，合计 350ms：随机性可控才能写死断言。
        assertEquals(350L, testScheduler.currentTime)
    }
}
