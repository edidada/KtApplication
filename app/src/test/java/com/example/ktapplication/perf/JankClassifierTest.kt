package com.example.ktapplication.perf

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 掉帧预算与归因规则的回归。
 *
 * 预算 = 1000/刷新率 先整除再乘 0.8（给系统留 20% 余量），归因顺序"先排除自己再怀疑系统"。
 * 车机屏从 60Hz 换 90Hz 是真实项目事故点：预算变了但判定代码没变，
 * 会用 60Hz 的尺子量 90Hz 的帧，把正常帧全报成 jank。这里的 60/90/120/非法值必须锁死。
 */
class JankClassifierTest {

    @Test
    fun `60Hz 预算`() {
        // 1000/60 = 16ms（整除），*0.8 = 12.8 -> 12ms。中控 60Hz 的标准帧预算。
        assertEquals(12L, JankClassifier.frameBudgetMillis(60))
    }

    @Test
    fun `90Hz 预算`() {
        // 1000/90 = 11ms，*0.8 = 8.8 -> 8ms。高刷仪表必须收紧预算，否则 12ms 的帧已经在 90Hz 上丢一帧。
        assertEquals(8L, JankClassifier.frameBudgetMillis(90))
    }

    @Test
    fun `非法刷新率退回 60Hz 口径`() {
        // 车载场景：Display.getRefreshRate() 在部分车机多屏/待机唤醒瞬间返回 0 或异常值，
        // 预算计算不能崩，退回默认 16ms（1000/60 整除值）。
        assertEquals(16L, JankClassifier.frameBudgetMillis(0))
        assertEquals(16L, JankClassifier.frameBudgetMillis(-120))
    }

    @Test
    fun `耗时恰好等于预算不算 jank`() {
        // 边界语义：<= 预算即达标。60Hz 下 12ms 帧是健康帧。
        assertEquals(JankCause.NONE, JankClassifier.classify(FrameTiming(durationMillis = 12L), 60))
        // 90Hz 下 8ms 同理。
        assertEquals(JankCause.NONE, JankClassifier.classify(FrameTiming(durationMillis = 8L), 90))
    }

    @Test
    fun `归因 APP - 主线程阶段超预算`() {
        // 车载场景：地图图层 measure/layout 重，主线程阶段(userInput+anim+layout+draw)吃掉
        // 预算 60% 以上 -> 整改方向是自己减主线程工作量。
        val timing = FrameTiming(durationMillis = 40, userInputMillis = 10, layoutMeasureMillis = 12)
        assertEquals(JankCause.APP, JankClassifier.classify(timing, 60))
    }

    @Test
    fun `归因 RENDER - 渲染线程与命令提交超预算`() {
        // 车载场景：Unity 仪表/HMI 混排时 RenderThread 变重（Shader 编译、纹理上传），
        // 主线程空闲但 sync+commandIssue 超一半预算。
        val timing = FrameTiming(durationMillis = 40, syncMillis = 10, commandIssueMillis = 2)
        assertEquals(JankCause.RENDER, JankClassifier.classify(timing, 60))
    }

    @Test
    fun `归因 DISPLAY - swap 等待`() {
        // 车载场景：三屏共享 SurfaceFlinger 合成，swap 缓冲排队变长；
        // 自己两路阶段都干净、只有 swap 慢 -> 问题在系统合成侧。
        val timing = FrameTiming(durationMillis = 40, swapMillis = 8)
        assertEquals(JankCause.DISPLAY, JankClassifier.classify(timing, 60))
    }

    @Test
    fun `归因 UNKNOWN - 各阶段都在预算内但整体超时`() {
        // 车载场景：CPU 被 ADAS/车控进程抢占、温控降频。阶段归因合不上账，
        // 上报时要走"系统侧协查"而不是自查——这正是 UNKNOWN 存在的意义。
        val timing = FrameTiming(durationMillis = 40, userInputMillis = 2, swapMillis = 1)
        assertEquals(JankCause.UNKNOWN, JankClassifier.classify(timing, 60))
    }

    @Test
    fun `归因优先级 - APP 压过 RENDER`() {
        // 判定顺序"先排除自己"：appPhase 与 renderPhase 同时超线时，结论归 APP，
        // 因为主线程卡连累 sync 变慢在实车上是更常见的因果链。
        val timing = FrameTiming(durationMillis = 50, drawMillis = 20, syncMillis = 20)
        assertEquals(JankCause.APP, JankClassifier.classify(timing, 60))
    }

    @Test
    fun `90Hz 下同一帧从达标变 jank 的翻转点`() {
        // 10ms 帧在 60Hz 预算(12)内是健康帧，在 90Hz 预算(8)外就是丢帧。
        // 锁死这个翻转，防止高刷车型上线后 jank 率统计口径漂移。
        val timing = FrameTiming(durationMillis = 10, userInputMillis = 7)
        assertEquals(JankCause.NONE, JankClassifier.classify(timing, 60))
        assertEquals(JankCause.APP, JankClassifier.classify(timing, 90))
    }
}
