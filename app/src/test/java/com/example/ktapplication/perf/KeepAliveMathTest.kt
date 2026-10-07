package com.example.ktapplication.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保活合规判定的纯规则回归。
 *
 * 每条 riskNote 对应一个真实的"被系统杀"场景，规则改动必须过这里的用例，
 * 因为现场排查完全依赖这份文本的准确性。
 */
class KeepAliveMathTest {

    @Test
    fun `importance 映射 - 只能看自己是设计约束不是 bug`() {
        // getRunningAppProcesses 在 Android 8+ 只返回本进程，这里把 framework 常量映射到模型枚举。
        assertEquals(ProcessImportance.FOREGROUND, KeepAliveMath.toProcessImportance(KeepAliveMath.IMPORTANCE_FOREGROUND))
        assertEquals(ProcessImportance.VISIBLE, KeepAliveMath.toProcessImportance(KeepAliveMath.IMPORTANCE_VISIBLE))
        assertEquals(ProcessImportance.SERVICE, KeepAliveMath.toProcessImportance(KeepAliveMath.IMPORTANCE_SERVICE))
        assertEquals(ProcessImportance.CACHED, KeepAliveMath.toProcessImportance(KeepAliveMath.IMPORTANCE_CACHED))
        // 取不到（权限受限/OEM 魔改值）明确 UNKNOWN，绝不猜测档位。
        assertEquals(ProcessImportance.UNKNOWN, KeepAliveMath.toProcessImportance(null))
        assertEquals(ProcessImportance.UNKNOWN, KeepAliveMath.toProcessImportance(1000)) // GONE
        assertEquals(ProcessImportance.UNKNOWN, KeepAliveMath.toProcessImportance(777))  // 无此档位
    }

    @Test
    fun `Android 12 起未声明 FGS 类型必须报警`() {
        // 升级座舱平台到新 Android 时的第一批崩溃：startForegroundService 直接抛异常。
        val notes = KeepAliveMath.riskNotes(
            sdkInt = 31, declaredForegroundServiceTypes = emptyList(),
            batteryOptimizationIgnored = true, scheduledJobs = 1,
            processImportance = ProcessImportance.FOREGROUND,
        )
        assertTrue(notes.any { it.contains("MissingForegroundServiceTypeException") })
    }

    @Test
    fun `Android 14 起 dataSync 类型有 6 小时限额`() {
        // TSP 远程控车的常驻 dataSync 同步被静默停止后，表现为"手机控车没反应"，极难远程定位。
        val notes = KeepAliveMath.riskNotes(
            sdkInt = 34, declaredForegroundServiceTypes = listOf("dataSync"),
            batteryOptimizationIgnored = true, scheduledJobs = 1,
            processImportance = ProcessImportance.FOREGROUND,
        )
        assertTrue(notes.any { it.contains("dataSync") && it.contains("限额") })
    }

    @Test
    fun `电池优化状态取不到要单独成条`() {
        // null（无权限）与 false（确实没豁免）是两种整改动作：前者找系统方，后者改代码。
        val notes = KeepAliveMath.riskNotes(
            sdkInt = 30, declaredForegroundServiceTypes = listOf("mediaPlayback"),
            batteryOptimizationIgnored = null, scheduledJobs = 3,
            processImportance = ProcessImportance.FOREGROUND,
        )
        assertTrue(notes.any { it.contains("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS") })
    }

    @Test
    fun `无任何风险时输出空列表`() {
        // 理想终态：声明了座舱豁免类型、在白名单、有兜底 Job、进程 foreground。
        // 空列表意味着 latest 状态是 Running 而不是 Breach，UI 不闪红。
        val notes = KeepAliveMath.riskNotes(
            sdkInt = 33, declaredForegroundServiceTypes = listOf("mediaPlayback"),
            batteryOptimizationIgnored = true, scheduledJobs = 2,
            processImportance = ProcessImportance.FOREGROUND,
        )
        assertEquals(emptyList<String>(), notes)
    }

    @Test
    fun `buildReport 把未知豁免折叠为 false 但风险保留 null 语义`() {
        // 模型字段是 Boolean（无法表达"不知道"），折叠成 false 展示；
        // 而"不知道"本身已通过 riskNotes 单独送达，信息不丢失。
        val report = KeepAliveMath.buildReport(
            sdkInt = 30, declaredForegroundServiceTypes = listOf("location"),
            processImportance = ProcessImportance.SERVICE,
            batteryOptimizationIgnored = null, scheduledJobs = 1,
        )
        assertEquals(false, report.batteryOptimizationIgnored)
        assertTrue(report.riskNotes.any { it.contains("电池优化白名单状态未知") })
    }
}
