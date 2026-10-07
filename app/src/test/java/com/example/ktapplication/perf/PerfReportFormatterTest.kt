package com.example.ktapplication.perf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯文本诊断报告格式化回归。
 *
 * 这份文本是实车取证的最终交付物（工单/微信里粘贴的就是它），所以断言按"行语义"写：
 * 每个关键指标必须原样出现在文本里，缺数据必须显式写"无"，
 * 防止渲染时漏字段被现场同学读成"该项正常"。
 */
class PerfReportFormatterTest {

    private fun fullSnapshot() = PerfSnapshot(
        capturedAtMillis = 12_345L,
        anr = AnrReport(
            detectedAtMillis = 1_000L,
            blockedDurationMillis = 2_500L,
            level = StallLevel.classify(2_500L),
            mainThreadStack = "at com.foo.ClusterRenderer.draw\n    at com.foo.MainActivity.onCreate",
            wasRendering = true,
            memoryPressure = false,
        ),
        frame = FrameMetricsReport(
            windowMillis = 1_000L, totalFrames = 100L, jankedFrames = 10L, slowFrames = 3L,
            causeBreakdown = mapOf(JankCause.APP to 10L),
            p50FrameMillis = 8L, p95FrameMillis = 20L, p99FrameMillis = 35L, worstFrameMillis = 500L,
        ),
        memory = MemoryReport(
            capturedAtMillis = 1_000L, javaHeapUsedBytes = 10_000_000L, javaHeapMaxBytes = 50_000_000L,
            nativeAllocatedBytes = 3_000_000L, totalPssKb = 200_000L, gcCountSinceLastSample = 1,
            heapGrowthBytesPerMinute = 60_000L,
        ),
        jitter = AllocationJitterReport(windowMillis = 1_000L, allocations = 500L, bytesAllocated = 100_000L, gcCount = 1, blockingGcCount = 0),
        leaks = listOf(LeakReport("NavActivity", "com.x.NavActivity", retainedSinceMillis = 300_000L, firstSeenAtMillis = 900_000L, suspicionReason = "弱引用未入队，嫌疑")),
        keepAlive = KeepAliveReport(
            processImportance = ProcessImportance.FOREGROUND,
            foregroundServiceTypes = listOf("mediaPlayback"),
            batteryOptimizationIgnored = true,
            scheduledJobs = 2,
            riskNotes = listOf("风险X"),
        ),
        startup = StartupReport(1_800L, listOf(StartupPhase.of("init", 0L, 300L)), mapOf("firstFrame" to 1_800L)),
        canFrameRateHz = 50.0,
        canBusSilent = listOf("CAN1"),
    )

    @Test
    fun `全量数据快照逐区块输出`() {
        val text = PerfReportFormatter.format(fullSnapshot())

        assertTrue(text, text.contains("车机性能诊断快照 @ 12345ms"))
        // leaks 非空 -> 模型判 CRITICAL，文本必须带上总体等级。
        assertTrue(text, text.contains("总体等级: CRITICAL"))

        assertTrue(text, text.contains("等级=严重卡顿"))
        assertTrue(text, text.contains("阻塞>=2500ms"))
        assertTrue(text, text.contains("帧内阻塞=true"))
        assertTrue(text, text.contains("com.foo.ClusterRenderer.draw"))

        assertTrue(text, text.contains("窗口=1000ms 帧数=100 jank=10(10.0%) slow=3"))
        assertTrue(text, text.contains("p99=35ms worst=500ms"))
        assertTrue(text, text.contains("归因: APP:10"))

        assertTrue(text, text.contains("Java堆=10MB/50MB"))
        assertTrue(text, text.contains("PSS=200000KB"))
        assertTrue(text, text.contains("堆增速=60KB/min"))

        assertTrue(text, text.contains("100KB/s"))
        assertTrue(text, text.contains("抖动中=false"))

        assertTrue(text, text.contains("NavActivity (com.x.NavActivity) 已滞留 5min"))

        assertTrue(text, text.contains("首帧=1800ms 座舱预算达标=true"))
        assertTrue(text, text.contains("init: 300ms"))

        assertTrue(text, text.contains("重要性=前台，几乎不会被杀"))
        assertTrue(text, text.contains("风险: 风险X"))
        assertTrue(text, text.contains("待办Job=2"))

        assertTrue(text, text.contains("帧率=50.0Hz"))
        assertTrue(text, text.contains("静默总线=CAN1"))
    }

    @Test
    fun `空快照每个区块显式写无`() {
        val empty = PerfSnapshot(
            capturedAtMillis = 0L, anr = null, frame = null, memory = null, jitter = null,
            leaks = emptyList(), keepAlive = null, startup = null, canFrameRateHz = null, canBusSilent = emptyList(),
        )
        val text = PerfReportFormatter.format(empty)
        assertTrue(text, text.contains("总体等级: INFO"))
        assertTrue(text, text.contains("无 ANR 记录"))
        assertTrue(text, text.contains("无帧数据"))
        assertTrue(text, text.contains("无内存采样"))
        assertTrue(text, text.contains("无抖动数据"))
        assertTrue(text, text.contains("无疑似泄漏"))
        assertTrue(text, text.contains("未收口"))
        assertTrue(text, text.contains("无保活自检"))
        assertTrue(text, text.contains("静默总线=无"))
    }

    @Test
    fun `ANR 无栈时不能渲染成空白`() {
        // 低版本反射失败的兜底路径：mainThreadStack=null，文本必须给出可解释的占位而不是空白行。
        val snapshot = fullSnapshot().copy(
            anr = fullSnapshot().anr?.copy(mainThreadStack = null),
            leaks = emptyList(),
            // 同时清掉 frame：否则 CRITICAL 由帧率不达标贡献，会掩盖"仅 ANR 应为 WARNING"的断言。
            frame = null,
        )
        val text = PerfReportFormatter.format(snapshot)
        assertTrue(text, text.contains("未捕获"))
        assertFalse(text, text.contains("总体等级: CRITICAL"))
    }
}
