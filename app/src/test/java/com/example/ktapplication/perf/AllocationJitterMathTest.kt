package com.example.ktapplication.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * readDebugCount 快照 -> 抖动报告的纯计算回归。
 *
 * 关键约定（CounterIndex.DEFAULT）：0=DEBUG_ALLOCATIONS 分配次数、1=DEBUG_FILLED_DATA_BYTES
 * 分配字节、2=DEBUG_GC_COUNT、3=DEBUG_LOCK_CONTENTION_COUNT、4=DEBUG_BLOCKING_GC_COUNT。
 * 报告输出窗口内差值而不是累计值——车机开机几十小时不关机，累计值毫无可读性。
 */
class AllocationJitterMathTest {

    /** 构造一个与 DEFAULT 下标对齐的计数器快照。 */
    private fun snapshot(allocations: Long, bytes: Long, gc: Long, contention: Long, blockingGc: Long) =
        longArrayOf(allocations, bytes, gc, contention, blockingGc)

    @Test
    fun `差值换算`() {
        val before = snapshot(allocations = 100, bytes = 20_480, gc = 3, contention = 1, blockingGc = 0)
        val after = snapshot(allocations = 1_100, bytes = 6_020_480, gc = 5, contention = 9, blockingGc = 1)
        val report = AllocationJitterMath.report(windowMillis = 1_000, before = before, after = after)
        assertEquals(1_000L, report.windowMillis)
        assertEquals(1_000L, report.allocations)
        // 6_000_000 字节 / 1000ms 窗口 -> 6MB/s，越过车机 5MB/s 告警线（低端平台一代 GC 开始抢帧的量级）。
        assertEquals(6_000_000L, report.bytesAllocated)
        assertEquals(6_000_000L, report.bytesAllocatedPerSecond)
        assertEquals(2, report.gcCount)
        assertEquals(1, report.blockingGcCount)
        assertTrue(report.isJittering)
    }

    @Test
    fun `低于告警线不误报`() {
        // 健康场景：正常 HMI 渲染每秒 1MB 级分配、1 次并发 GC（不阻塞）。
        // 这条必须稳定为 false，否则诊断页会被"匀速呼吸"的正常分配刷屏。
        val before = snapshot(0, 0, 0, 0, 0)
        val after = snapshot(allocations = 8_000, bytes = 1_000_000, gc = 1, contention = 2, blockingGc = 0)
        val report = AllocationJitterMath.report(1_000, before, after)
        assertEquals(1_000_000L, report.bytesAllocatedPerSecond)
        assertFalse(report.isJittering)
    }

    @Test
    fun `阻塞 GC 超限即使字节不高也报警`() {
        // 车载场景：大对象（位图缓存/地图瓦片）触发 blocking GC，
        // 分配字节速率没到线，但 24h 挂机后每窗口 4 次阻塞 GC 就是明显的前台掉帧源。
        val before = snapshot(0, 0, 0, 0, 0)
        val after = snapshot(allocations = 100, bytes = 500_000, gc = 4, contention = 0, blockingGc = 4)
        val report = AllocationJitterMath.report(1_000, before, after)
        assertEquals(4, report.blockingGcCount)
        assertTrue(report.isJittering)
    }

    @Test
    fun `计数器重置时差值钳为零不报负数`() {
        // 有人（调试工具/系统探针）resetDebugCounters 后 after < before。
        // 若差值为负，bytesAllocatedPerSecond 会输出负速率，把"重置"渲染成"内存魔法回收"。
        val before = snapshot(500, 999_999, 7, 2, 1)
        val after = snapshot(100, 0, 0, 0, 0)
        val report = AllocationJitterMath.report(1_000, before, after)
        assertEquals(0L, report.allocations)
        assertEquals(0L, report.bytesAllocated)
        assertEquals(0, report.gcCount)
        assertEquals(0, report.blockingGcCount)
    }

    @Test
    fun `下标越界按缺失项记零`() {
        // ROM 定制导致 Debug 计数器数组比预期短（老分支没有 blocking GC 计数）时：
        // 存在的下标照常差值，越界下标记 0，绝不能 ArrayIndexOutOfBoundsException 拖崩后台协程。
        val short = longArrayOf(10, 20)
        val report = AllocationJitterMath.report(1_000, longArrayOf(5, 100), short)
        assertEquals(5L, report.allocations)      // index 0 存在
        assertEquals(0L, report.bytesAllocated)   // 100 -> 20 是负差值，钳 0
        // 整条数组只有两项：gc/lock/blocking（index 2..4）全部越界记 0。
        assertEquals(0, report.gcCount)
        assertEquals(0, report.blockingGcCount)
    }

    @Test
    fun `delta 越界与正常分支`() {
        val before = longArrayOf(1, 2, 3)
        val after = longArrayOf(10, 20, 30)
        assertEquals(9L, AllocationJitterMath.delta(before, after, 0))
        assertEquals(27L, AllocationJitterMath.delta(before, after, 2))
        assertEquals(0L, AllocationJitterMath.delta(before, after, -1))
        assertEquals(0L, AllocationJitterMath.delta(before, after, 99))
    }

    @Test
    fun `零窗口防御`() {
        // windowMillis<=0 的脏输入不该产出天文数字速率（模型里已有防御，这里锁行为）。
        val report = AllocationJitterReport(0, 0, 9_999_999, 0, 0)
        assertEquals(0L, report.bytesAllocatedPerSecond)
    }
}
