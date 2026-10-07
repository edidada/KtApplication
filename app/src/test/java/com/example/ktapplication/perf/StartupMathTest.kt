package com.example.ktapplication.perf

import com.example.ktapplication.core.FakeMonotonicClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动报告纯函数回归。
 *
 * 车机指标"上电到车速显示 <= 2s"直接挂在这个模型上：阶段切分错了，
 * 优化就会砸错地方（比如把 Binder 等待算进 UI 初始化）。
 */
class StartupMathTest {

    @Test
    fun `时间戳序列展开为相邻阶段`() {
        // 打点：进程起(0) -> Application 完成(500) -> 首帧(1500)，now=2000 时首帧后没有新点。
        val phases = StartupMath.phasesFromTimestamps(
            listOf("processStart" to 0L, "appInit" to 500L, "firstFrame" to 1_500L),
            nowMillis = 2_000L,
        )
        assertEquals(3, phases.size)
        assertEquals(500L, phases[0].durationMillis)   // processStart -> appInit
        assertEquals(1_000L, phases[1].durationMillis) // appInit -> firstFrame
        // 末点无终点：补 [unclosed]，让"最后一个打点后发生了什么"在报告里可见。
        assertTrue(phases[2].name.contains("[unclosed]"))
        assertEquals(500L, phases[2].durationMillis)
    }

    @Test
    fun `乱序打点先排序再展开`() {
        // 车机启动多线程打点（DAG 任务框架并发初始化），时间戳到达顺序是乱的。
        val phases = StartupMath.phasesFromTimestamps(
            listOf("late" to 900L, "early" to 100L),
            nowMillis = 1_000L,
        )
        assertEquals("early", phases.first().name)
        assertEquals(800L, phases.first().durationMillis)
    }

    @Test
    fun `now 不晚于末点时不产出假 unclosed 段`() {
        // 首帧时刻即 now（finish 恰好在 firstFrame 打点同毫秒调用），不应出现 0 长尾段。
        val phases = StartupMath.phasesFromTimestamps(
            listOf("a" to 0L, "b" to 1_000L),
            nowMillis = 1_000L,
        )
        assertEquals(1, phases.size)
    }

    @Test
    fun `report 汇总与预算判定`() {
        val report = StartupMath.report(
            appProcessStartToFirstFrameMillis = 1_800L,
            phases = listOf(StartupPhase.of("appInit", 0, 400), StartupPhase.of("firstFrameDraw", 400, 1_400)),
            tracePoints = mapOf("canConnected" to 1_200L),
        )
        assertEquals(1_800L, report.totalMillis)
        assertTrue(report.meetsCockpitBudget)            // 2s 仪表预算内
        assertEquals("firstFrameDraw", report.slowestPhase?.name)
        assertEquals(1_200L, report.tracePoints.getValue("canConnected"))
        assertTrue(StartupMath.meetsBudget(1_800, budgetMillis = 2_000))
        assertFalse(StartupMath.meetsBudget(2_001, budgetMillis = 2_000))
    }

    @Test
    fun `超预算场景`() {
        // 实车黑盒复现：上电 4.5s 才出车速。总时长口径直接判不达标（模型固定 2s 线）。
        val report = StartupMath.report(4_500L, listOf(StartupPhase.of("waitCarService", 0, 4_000)), emptyMap())
        assertFalse(report.meetsCockpitBudget)
        assertEquals("waitCarService", report.slowestPhase?.name)
    }
}

/**
 * StartupTracer 的幂等状态机测试。
 *
 * 只走 FakeMonotonicClock 与 Trace 的 JVM 桩（unitTests.returnDefaultValues=true，
 * Trace.beginSection/endSection 是静态 void，在单测里为无害 no-op），
 * 不触碰任何需要真实 framework 的分支。
 */
class StartupTracerJvmTest {

    @Test
    fun `重复 begin 取首次 重复 end 静默忽略`() {
        val clock = FakeMonotonicClock(1_000L)
        val tracer = StartupTracer(clock)
        clock.set(1_100L)
        tracer.beginPhase("appInit")          // 第一次：记录 1100
        clock.advance(100)
        tracer.beginPhase("appInit")          // 重复 begin：必须被忽略（否则起点被刷成 1200）
        clock.advance(300)                    // now = 1500
        tracer.endPhase("appInit")
        tracer.endPhase("appInit")            // 重复 end：不崩溃、不追加第二段
        tracer.endPhase("neverBegun")         // 未 begin 的 end：忽略

        clock.advance(200)                    // now = 1700
        val report = tracer.finish(firstFrameAtMillis = 1_800L)
        assertEquals(800L, report.totalMillis)                 // 1800 - 进程启动 1000
        val appInit = report.phases.filter { it.name.startsWith("appInit") }
        assertEquals(1, appInit.size)
        assertEquals(400L, appInit.first().durationMillis)     // 1500 - 1100（首次 begin 生效）
    }

    @Test
    fun `未闭合阶段在 finish 时按 now 补齐并标记`() {
        val clock = FakeMonotonicClock(0L)
        val tracer = StartupTracer(clock)
        clock.set(100L)
        tracer.beginPhase("waitCarService")
        clock.set(900L)
        tracer.tracePoint("firstFrame")
        clock.set(1_000L)
        val report = tracer.finish()                             // 未传 firstFrame 时刻：以 now 代偿
        assertEquals(1_000L, report.totalMillis)
        val unclosed = report.phases.single()
        assertTrue(unclosed.name.endsWith("[unclosed]"))
        assertEquals(900L, unclosed.durationMillis)               // 1000 - 100
        assertEquals(900L, report.tracePoints.getValue("firstFrame"))
    }

    @Test
    fun `重复 finish 返回同一报告`() {
        val clock = FakeMonotonicClock(0L)
        val tracer = StartupTracer(clock)
        tracer.beginPhase("a")
        tracer.endPhase("a")
        val first = tracer.finish()
        clock.advance(5_000)
        val second = tracer.finish()
        assertSame(first, second)                                 // 收口后不再漂移：启动指标只能有一个口径
        assertTrue(tracer.isFinished())
    }
}
