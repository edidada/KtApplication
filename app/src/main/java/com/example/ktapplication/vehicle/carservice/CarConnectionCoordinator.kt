package com.example.ktapplication.vehicle.carservice

import kotlin.math.min
import kotlin.math.pow

/**
 * CarService / 云端 / 蓝牙通用的重连退避策略。
 *
 * 车机上的重连参数和手机 App 不一样，原因有两个：
 *  1. CarService 崩溃后重启需要几秒（VHAL 初始化），太早重连只会拿到 UNSUPPORTED；
 *  2. 车机整车上电是"批量重连"场景 —— 十几个常驻 App 同时按同一条曲线重试会打爆系统服务，
 *     所以必须有 jitter，而且 jitter 要用**可注入的随机源**，否则单测无法断言区间。
 */
class ReconnectBackoff(
    val initialDelayMillis: Long = 1_000L,
    val maxDelayMillis: Long = 30_000L,
    val multiplier: Double = 2.0,
    /** 抖动比例，0.2 表示在计算值上 ±20%。 */
    val jitterRatio: Double = 0.2,
    private val random: () -> Double = { Math.random() },
) {

    /** 第 [attempt] 次（从 0 开始）重连前应等待的毫秒数。 */
    fun delayBefore(attempt: Int): Long {
        require(attempt >= 0) { "attempt 不能为负" }
        val base = initialDelayMillis * multiplier.pow(attempt.toDouble())
        val capped = min(base, maxDelayMillis.toDouble())
        // random() 约定返回 [0,1)，映射成 [1-jitter, 1+jitter) 的缩放因子。
        val scaled = capped * (1.0 - jitterRatio + random() * 2.0 * jitterRatio)
        return scaled.toLong().coerceIn(0L, maxDelayMillis)
    }

    /**
     * 达到上限后不再无限重试。
     *
     * 车机上"永远重试"是隐患：如果 CarService 因为 OTA 被换掉，App 会持续申请 Binder
     * 导致系统日志刷爆。超出预算后应停在 Reconnecting 终态并等外部触发（如 CarService
     * 重新可用的广播）。
     */
    fun shouldGiveUp(attempt: Int, giveUpAfterAttempts: Int = 12): Boolean = attempt >= giveUpAfterAttempts

    /**
     * 纯函数版抖动计算，单测可直接传入固定 random。
     */
    companion object {
        fun computeDelay(attempt: Int, initialDelayMillis: Long, maxDelayMillis: Long, multiplier: Double, jitterFactor: Double): Long {
            val capped = min(initialDelayMillis * multiplier.pow(attempt.toDouble()), maxDelayMillis.toDouble())
            return (capped * jitterFactor).toLong().coerceIn(0L, maxDelayMillis)
        }
    }
}

/**
 * CarService 连接状态机。
 *
 * 把"连接 / binder 死亡 / 重连退避 / 放弃"从网关实现里剥出来，好处是这段最容易出
 * 稳定性问题的逻辑能被纯 JVM 单测覆盖 —— 真机上 binder 死亡回调的时序无法控制，
 * 但状态机的输入（事件 + 时钟）是可控的。
 */
class CarConnectionCoordinator(
    private val backoff: ReconnectBackoff = ReconnectBackoff(),
    private val nowMillis: () -> Long,
    private val onStateChange: (CarServiceState) -> Unit,
) {
    private var attempt = 0
    private var lastEventAtMillis = 0L

    val currentState: CarServiceState get() = state

    private var state: CarServiceState = CarServiceState.Disconnected

    fun onConnectRequested() {
        if (state is CarServiceState.Ready) return
        state = CarServiceState.Connecting
        onStateChange(state)
    }

    fun onReady(supported: Set<CarVehicleProperty>, carType: String?) {
        attempt = 0
        lastEventAtMillis = nowMillis()
        state = CarServiceState.Ready(supported, carType, lastEventAtMillis)
        onStateChange(state)
    }

    fun onUnsupported(reason: String) {
        state = CarServiceState.Unsupported(reason)
        onStateChange(state)
    }

    /** Binder 死亡 / onCarDisconnected / 读属性抛 ServiceException 都走这里。 */
    fun onConnectionLost(cause: String): Long {
        attempt += 1
        val nextDelay = backoff.delayBefore(attempt - 1)
        lastEventAtMillis = nowMillis()
        state = if (backoff.shouldGiveUp(attempt)) {
            CarServiceState.Unsupported("重连 $attempt 次仍失败：$cause")
        } else {
            CarServiceState.Reconnecting(attempt, cause, nextDelay)
        }
        onStateChange(state)
        return nextDelay
    }

    fun reset() {
        attempt = 0
        state = CarServiceState.Disconnected
        onStateChange(state)
    }

    /** 供单测断言：完整序列化的状态文本。 */
    fun debugTransitions(): String = "$state(attempt=$attempt)"
}
