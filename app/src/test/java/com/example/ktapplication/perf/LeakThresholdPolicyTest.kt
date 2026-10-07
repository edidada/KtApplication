package com.example.ktapplication.perf

import com.example.ktapplication.core.FakeMonotonicClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 泄漏判定策略回归。
 *
 * 车机仪表进程是常驻的：一次误报 = 一条 OEM 工单 + 一轮无效排查。
 * 三条件（已 GC 加测、GC 已落地、存活超阈值）缺任何一个都不得判泄漏，这里逐条锁死。
 */
class LeakThresholdPolicyTest {

    private val threshold = 5_000L

    @Test
    fun `未做 GC 加测时存活不算泄漏`() {
        // 对象只是还没被回收（可能销毁路径刚跑完），没有 GC 参与 Observation 毫无信息量。
        assertFalse(LeakThresholdPolicy.isLeaked(ageMillis = 600_000, gcTriggered = false, thresholdMillis = threshold, gcSettled = false))
    }

    @Test
    fun `GC 刚触发还没落地不算泄漏`() {
        // ART 并发 GC 的标记+清除需要时间，加测后立刻查队列会把"正在被回收"误判成泄漏。
        assertFalse(LeakThresholdPolicy.isLeaked(ageMillis = 600_000, gcTriggered = true, thresholdMillis = threshold, gcSettled = false))
    }

    @Test
    fun `存活未达阈值不报`() {
        // 5s 内的滞留是 HMI 动画收尾/协程取消的正常延迟，报出来只会制造噪音。
        assertFalse(LeakThresholdPolicy.isLeaked(ageMillis = 4_999, gcTriggered = true, thresholdMillis = threshold, gcSettled = true))
    }

    @Test
    fun `三条件齐备判泄漏`() {
        // 场景：导航页销毁 10 分钟，GC 加测也已落地，弱引用仍未入队 -> 有强引用链持有 Activity。
        assertTrue(LeakThresholdPolicy.isLeaked(ageMillis = 600_000, gcTriggered = true, thresholdMillis = threshold, gcSettled = true))
        // 恰好等于阈值也成立（>= 语义）。
        assertTrue(LeakThresholdPolicy.isLeaked(ageMillis = 5_000, gcTriggered = true, thresholdMillis = threshold, gcSettled = true))
    }

    @Test
    fun `措辞保持嫌疑级并携带 tag`() {
        // 车机 ROM 可能忽略 System.gc()，所以结论文本必须可审计地写明"嫌疑"，
        // 并且带 tag 方便对着自家代码找挂载点。
        val reason = LeakThresholdPolicy.suspicionReason("NavActivity", 600_000)
        assertTrue(reason.contains("嫌疑"))
        assertTrue(reason.contains("NavActivity"))
        assertTrue(reason.contains("ReferenceQueue"))
    }
}

/**
 * LeakWatcher 观察状态机的 JVM 行为测试。
 *
 * WeakReference + ReferenceQueue 是纯 JVM 机制，本类零 android 依赖，
 * 所以"强引用仍持有 -> 判泄漏"这条主链路可以在 JVM 上确定性地测：
 * 被测对象被测试变量强引用着，任何一次真 GC 都不可能回收它，判定必然成立。
 * （反向"回收后自动出名单"依赖 GC 时机，JVM 上也不确定，留给 instrumentation 测试。）
 */
class LeakWatcherJvmTest {

    @Test
    fun `强引用存活的观察对象在 GC 加测落地后被报告`() {
        val clock = FakeMonotonicClock(0L)
        val watcher = LeakWatcher(clock = clock, retainThresholdMillis = 5_000L, gcSettleDelayMillis = 2_000L)
        // 模拟"仪表页销毁但被静态回调持有"：测试局部变量就是那条强引用链。
        val held = Any()
        watcher.watch("ClusterScreen", held)

        clock.set(6_000L)
        watcher.sweep()   // 第一轮：仅登记 GC 加测时刻，不该出报告
        assertTrue(watcher.leakReports.value.isEmpty())

        clock.set(8_500L)
        watcher.sweep()   // 加测已落地(2500ms >= 2000ms)且超阈值 -> 报告
        val report = watcher.leakReports.value.single()
        assertEquals("ClusterScreen", report.tag)
        assertEquals(0L, report.firstSeenAtMillis)
        assertEquals(8_500L, report.retainedSinceMillis)
        assertTrue(watcher.latest.value is MonitorState.Breach)

        clock.set(12_000L)
        watcher.sweep()   // 同一 tag 只报一次，避免常驻进程被同一条泄漏刷屏
        assertEquals(1, watcher.leakReports.value.size)
        // JVM 的 GC 活性分析以"未来还会不会读"为准：最后再读一次 held，
        // 保证局部变量槽在整个方法体内都是 live 的，否则 sweep 里的 System.gc()
        // 可能把测试自己当成"泄漏者"先回收掉，测试变 flaky（泄漏链的模拟就不成立了）。
        assertNotNull(held)
    }

    @Test
    fun `unwatch 后对象彻底离开观察名单`() {
        // 场景：预期内还要活很久的对象（如缓存的导航引擎句柄）不该被 watch 到底，
        // 业务侧要能显式退出观察，否则会有大量"合法的长期存活"混进报警。
        val clock = FakeMonotonicClock(0L)
        val watcher = LeakWatcher(clock = clock, retainThresholdMillis = 5_000L, gcSettleDelayMillis = 2_000L)
        val held = Any()
        watcher.watch("NavEngine", held)
        watcher.unwatch("NavEngine")
        assertTrue(watcher.watchedTags().isEmpty())
        clock.set(100_000L)
        watcher.sweep()
        assertTrue(watcher.leakReports.value.isEmpty())
    }

    @Test
    fun `阈值内不长记性 - 未到期不打扰 GC`() {
        // 车机后台常开的第一戒律：没到阈值绝不调 System.gc()。
        // 这里用"未产生加测登记"来间接断言（gcTriggeredAt 只在 age>=threshold 时写入，
        // 未写入则第二轮 sweep 也不会走到报告分支）。
        val clock = FakeMonotonicClock(0L)
        val watcher = LeakWatcher(clock = clock, retainThresholdMillis = 5_000L, gcSettleDelayMillis = 2_000L)
        val held = Any()
        watcher.watch("Splash", held)
        clock.set(4_999L)
        watcher.sweep()   // age < threshold：既不加测也不报告
        assertTrue(watcher.leakReports.value.isEmpty())
        assertEquals(setOf("Splash"), watcher.watchedTags())
    }
}
