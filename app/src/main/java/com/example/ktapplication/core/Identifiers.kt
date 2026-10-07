package com.example.ktapplication.core

/**
 * 可注入的 ID 生成器。
 *
 * 远程控车指令必须带全局唯一 ID：TSP 侧靠它做幂等（用户连按两次解锁不能变成两次执行），
 * App 侧靠它做重试去重。如果代码里到处直接写 UUID.randomUUID()，单测就无法断言幂等键，
 * 所以把它抽成接口。
 */
interface IdGenerator {
    fun next(): String
}

object UuidIdGenerator : IdGenerator {
    override fun next(): String = java.util.UUID.randomUUID().toString().replace("-", "")
}

/** 测试用：完全可预测，方便断言 idempotencyKey 与指令顺序。 */
class SequentialIdGenerator(private val prefix: String = "cmd", private var counter: Long = 0L) : IdGenerator {
    override fun next(): String = "$prefix-${counter++}"
}

/** 时间 + 自增的组合，接近真机上"跨进程也不会撞"的实用做法。 */
class TimestampedIdGenerator(
    private val clock: MonotonicClock,
    private val prefix: String = "cmd",
) : IdGenerator {
    private var sequence = 0

    @Synchronized
    override fun next(): String {
        sequence = (sequence + 1) % 1000
        return "$prefix-${clock.nowMillis()}-$sequence"
    }
}
