package com.example.ktapplication.perf

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * StallLevel.classify 边界值回归。
 *
 * 分级线来自车机验收标准（100ms 起算用户可感知，300ms 是仪表刷新链路的容忍上限，
 * 5s 是 ANR 输入事件超时下限），单测必须锁死边界，防止后人"顺手调阈值"造成误报率漂移。
 */
class StallLevelTest {

    @Test
    fun `零与负输入都归为正常`() {
        // 车载场景：单调时钟理论上不回拨，但探针重启/时钟源切换可能算出 <=0 的差值，必须兜底 NORMAL。
        assertEquals(StallLevel.NORMAL, StallLevel.classify(0))
        assertEquals(StallLevel.NORMAL, StallLevel.classify(-1))
    }

    @Test
    fun `轻微卡顿区间边界`() {
        assertEquals(StallLevel.NORMAL, StallLevel.classify(99))
        // 恰好 100ms 进入 SLIGHT：滑动列表一帧超时 6 倍以上，验收报告开始记数。
        assertEquals(StallLevel.SLIGHT, StallLevel.classify(100))
        assertEquals(StallLevel.SLIGHT, StallLevel.classify(299))
    }

    @Test
    fun `明显卡顿区间边界`() {
        // 300ms：车机 HMI 点击反馈的容忍上限，超过即"点了没反应"。
        assertEquals(StallLevel.NOTICEABLE, StallLevel.classify(300))
        assertEquals(StallLevel.NOTICEABLE, StallLevel.classify(699))
    }

    @Test
    fun `严重卡顿区间边界`() {
        // 700ms：仪表 3 秒不出数事故链路上的第一档预警线。
        assertEquals(StallLevel.SEVERE, StallLevel.classify(700))
        assertEquals(StallLevel.SEVERE, StallLevel.classify(4_999))
    }

    @Test
    fun `疑似ANR下限`() {
        // 5s 起系统随时可能弹 ANR 对话框（座舱上是不可接受的用户体验），探针要早一步拿到栈。
        assertEquals(StallLevel.ANR_SUSPECTED, StallLevel.classify(5_000))
        assertEquals(StallLevel.ANR_SUSPECTED, StallLevel.classify(120_000))
    }
}
