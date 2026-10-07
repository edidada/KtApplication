package com.example.ktapplication.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * 业务层重试策略。
 *
 * 仓库里已有两个"看起来像重试"的东西，先说清分层，避免同一件事三处各一套规则：
 * - `vehicle.data.HttpPolicy`：**传输层**。只看 HTTP 方法、状态码、是否带幂等键，
 *   决定"这个 socket 请求能不能原样再发一次"。
 * - `vehicle.carservice.ReconnectBackoff`：**链路层**。CarService 断连后的重连节奏，
 *   不涉及任何业务语义。
 * - 本文件：**领域层**。它拿到的已经是 [Outcome] + [DomainError]，处理的是"车辆到底做没做"。
 *
 * 领域层最重要的一条硬规则：**[DomainError.ResultUnknown] 永不重试**。
 * 超时的含义是"指令可能已经被车辆执行，只是回执丢了"。此时自动重发，
 * 用户会得到"解锁执行了两次"或者"设置完温度又被覆盖"的结果，
 * 而这在车机上属于安全事故级别的问题 —— 正确的做法是去**查证**状态，不是再发一次。
 * 同理 [DomainError.Rejected]（车辆明确拒绝，重试只是骚扰执行器）、
 * [DomainError.Unsupported]、[DomainError.Malformed]、[DomainError.SignalUnreliable] 都不该重试；
 * 只有 [DomainError.ConnectionUnavailable] 这类"根本没送到"的才值得退避重试。
 */
data class RetryPolicy(
    val maxAttempts: Int = 2,
    val baseDelay: Duration = 200.milliseconds,
    val maxDelay: Duration = 2.seconds,
    /** 抖动比例。真实网络重试必须打散，否则弱网恢复瞬间一批车机同时重发，把自己 DDoS 了。 */
    val jitterRatio: Double = 0.2,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts 至少 1 次，否则等于永不执行" }
        require(baseDelay >= Duration.ZERO) { "baseDelay 不能为负" }
        require(maxDelay >= baseDelay) { "maxDelay 必须不小于 baseDelay" }
        require(jitterRatio in 0.0..1.0) { "jitterRatio 必须在 0..1" }
    }

    /**
     * 第 [attempt] 次失败之后应该等多久（attempt 从 1 开始）。
     *
     * @param jitterUnit 归一化抖动量（0..1），由调用方提供随机数。
     *   这里刻意不自己调 Random：策略的时序输出必须完全可复现，
     *   否则"退避是否触顶""抖动是否超上限"这类断言在单测里就是掷骰子。
     */
    fun delayAfter(attempt: Int, jitterUnit: Double = 0.0): Duration {
        require(attempt >= 1) { "attempt 从 1 开始" }
        val exponent = (attempt - 1).coerceAtMost(MAX_SHIFT)
        val doubled = baseDelay.inWholeMilliseconds shl exponent
        val capped = doubled.coerceAtMost(maxDelay.inWholeMilliseconds)
        val jittered = capped + (capped * jitterRatio * jitterUnit.coerceIn(0.0, 1.0)).toLong()
        // 抖动只能把等待时间往上抬，且抬不过 maxDelay 的两倍；
        // 触顶后再无限制叠加抖动会让"最坏等待时间"不可预算，上层超时预算就没法算。
        return jittered.coerceAtMost(maxDelay.inWholeMilliseconds * 2).milliseconds
    }

    /** 这个错误值不值得再试一次。 */
    fun isRetryable(error: DomainError): Boolean = when (error) {
        is DomainError.ConnectionUnavailable -> true
        is DomainError.ResultUnknown -> false
        is DomainError.Rejected -> false
        is DomainError.Unsupported -> false
        is DomainError.Malformed -> false
        is DomainError.SignalUnreliable -> false
    }

    companion object {
        private const val MAX_SHIFT = 10

        /** 控车指令：最多两次，退避保守。真实车辆执行慢，重试太急只会撞在执行器上。 */
        val remoteCommand = RetryPolicy(maxAttempts = 2, baseDelay = 300.milliseconds, maxDelay = 1.seconds)

        /** 车况同步：可以激进一些，读操作没有副作用。 */
        val statusSync = RetryPolicy(maxAttempts = 3, baseDelay = 250.milliseconds, maxDelay = 4.seconds)

        /** 关掉重试，用于"只发一次、失败就如实呈现"的场景。 */
        val none = RetryPolicy(maxAttempts = 1)
    }
}

/**
 * 按 [policy] 执行 [block]，失败时退避重试。
 *
 * 契约：
 * - [block] 收到从 1 开始的 attempt，方便日志区分"第几次"，也让调用方能自己换幂等键。
 * - 不可重试的错误**立刻返回**，不再消耗剩余次数。
 * - 本函数不抛业务异常（协程取消除外）：领域层失败一律是 [Outcome.Failure]，
 *   这样上层 ViewModel 只需要处理一条通路，不会出现"catch 了异常但漏了 Failure 分支"。
 */
suspend fun <T> retryOn(
    policy: RetryPolicy,
    jitter: (attempt: Int) -> Double = { 0.0 },
    onRetry: (attempt: Int, error: DomainError) -> Unit = { _, _ -> },
    block: suspend (attempt: Int) -> Outcome<T>,
): Outcome<T> {
    var lastFailure: Outcome.Failure? = null
    for (attempt in 1..policy.maxAttempts) {
        when (val current = block(attempt)) {
            is Outcome.Success -> return current
            is Outcome.Failure -> {
                lastFailure = current
                if (attempt >= policy.maxAttempts) return current
                if (!policy.isRetryable(current.error)) return current
                onRetry(attempt, current.error)
                delay(policy.delayAfter(attempt, jitter(attempt)).inWholeMilliseconds)
            }
        }
    }
    // 循环正常走完只可能是 maxAttempts 非法，init 已经 require 过；
    // 这里保留兜底而不是 error()：领域函数宣称"不抛"，就必须真的不抛。
    return lastFailure ?: Outcome.failure(
        DomainError.ConnectionUnavailable("重试策略未产生任何结果（attempt=${policy.maxAttempts}）")
    )
}
