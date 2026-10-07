package com.example.ktapplication.core

/**
 * 时钟抽象。
 *
 * 车载逻辑几乎全是"时间相关"的：CAN 信号超时判定、远程控车指令 8s 超时、
 * 蓝牙重连退避、启动耗时打点。这些代码如果在 JVM 单测里依赖 System.currentTimeMillis()
 * 就只能靠 sleep 来测，慢且不稳定。统一走接口后可用 [FakeMonotonicClock] 直接拨表。
 */

/** 单调时钟：只用于测时长，不受用户改系统时间影响（车机休眠唤醒后墙上时间可能跳变）。 */
interface MonotonicClock {
    fun nowMillis(): Long
}

/** 墙上时钟：用于给上报数据、离线队列打业务时间戳。 */
interface WallClock {
    fun nowMillis(): Long
}

object SystemMonotonicClock : MonotonicClock {
    override fun nowMillis(): Long = android.os.SystemClock.elapsedRealtime()
}

object SystemWallClock : WallClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/** 可在测试里手动推进的时钟；[advance] 是唯一让虚拟时间前进的入口。 */
class FakeMonotonicClock(startMillis: Long = 0L) : MonotonicClock {
    private var current = startMillis

    override fun nowMillis(): Long = current

    fun advance(millis: Long) {
        require(millis >= 0) { "单调时钟不允许回拨" }
        current += millis
    }

    fun set(millis: Long) {
        current = millis
    }
}

class FakeWallClock(startMillis: Long = 1_700_000_000_000L) : WallClock {
    private var current = startMillis

    override fun nowMillis(): Long = current

    fun advance(millis: Long) {
        current += millis
    }
}
