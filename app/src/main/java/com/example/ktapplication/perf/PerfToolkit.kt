package com.example.ktapplication.perf

import android.content.Context
import com.example.ktapplication.core.AppDispatchers
import com.example.ktapplication.core.MonotonicClock
import com.example.ktapplication.core.ProductionAppDispatchers
import com.example.ktapplication.core.SystemMonotonicClock
import com.example.ktapplication.core.SystemWallClock
import com.example.ktapplication.core.WallClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 车机性能与稳定性工具箱的门面（façade）。
 *
 * 组装全部探针并聚合成 [PerfSnapshot]，UI 的诊断页只订阅 [snapshot] 一个流即可。
 * 设计取舍：
 *  - **不做 combine 嵌套**：PerfSnapshot 有 9 个来源字段，combine 的同构 vararg 会强制
 *    把不同类型擦成 Any?，异构重载只到 5 路。这里改为"每源一个轻量收集协程 + 原子更新快照"，
 *    任何一个探针出数都只触碰自己那个字段，读侧永远看到一致快照（MutableStateFlow.update 是 CAS）。
 *  - 所有协程都挂在调用方传入的 scope：车机上诊断页可以后台常驻，但生命周期归 Application scope 管，
 *    [stop] 取消全部收集与探针循环，不留尾巴。
 *  - CAN 相关字段（canFrameRateHz/canBusSilent）不探（那是 vehicle 包的职责），由调用方注入，
 *    工具箱只做展示聚合。
 */
class PerfToolkit(
    context: Context,
    clock: MonotonicClock = SystemMonotonicClock,
    private val wallClock: WallClock = SystemWallClock,
    dispatchers: AppDispatchers = ProductionAppDispatchers,
    refreshRateHz: Int = 60,
    declaredForegroundServiceTypes: List<String> = emptyList(),
    canFrameRateHz: Double? = null,
    canBusSilent: List<String> = emptyList(),
) {

    val anrWatcher = AnrWatcher(context, clock, dispatchers)
    val frameMetrics = FrameMetricsMonitor(clock, dispatchers, refreshRateHz)
    val memoryProbe = MemoryProbe(context, clock, dispatchers)
    val allocationJitter = AllocationJitterDetector(clock, dispatchers)
    val leakWatcher = LeakWatcher(clock, dispatchers)
    val startupTracer = StartupTracer(clock, dispatchers)
    val keepAliveInspector = KeepAliveInspector(context, clock, dispatchers, declaredForegroundServiceTypes)

    /** 供 UI 逐探针展示状态角标；顺序即诊断页展示顺序。 */
    val monitors: List<PerfMonitor> = listOf(
        anrWatcher, frameMetrics, memoryProbe, allocationJitter, leakWatcher, startupTracer, keepAliveInspector,
    )

    private val _snapshot = MutableStateFlow(
        PerfSnapshot(
            capturedAtMillis = wallClock.nowMillis(),
            anr = null, frame = null, memory = null, jitter = null,
            leaks = emptyList(), keepAlive = null, startup = null,
            canFrameRateHz = canFrameRateHz, canBusSilent = canBusSilent,
        )
    )
    val snapshot: StateFlow<PerfSnapshot> = _snapshot

    private var collectJobs: List<Job> = emptyList()

    fun start(scope: CoroutineScope) {
        if (collectJobs.isEmpty()) {
            // 每个报告源一个收集协程：report 变化 -> CAS 合并进快照。
            collectJobs = listOf(
                scope.launch { anrWatcher.anrReport.collect { report -> update { copy(anr = report) } } },
                scope.launch { frameMetrics.frameReport.collect { report -> update { copy(frame = report) } } },
                scope.launch { memoryProbe.memoryReport.collect { report -> update { copy(memory = report) } } },
                scope.launch { allocationJitter.jitterReport.collect { report -> update { copy(jitter = report) } } },
                scope.launch { leakWatcher.leakReports.collect { reports -> update { copy(leaks = reports) } } },
                scope.launch { startupTracer.startupReport.collect { report -> update { copy(startup = report) } } },
                scope.launch { keepAliveInspector.keepAliveReport.collect { report -> update { copy(keepAlive = report) } } },
            )
        }
        monitors.forEach { it.start(scope) }
    }

    fun stop() {
        collectJobs.forEach { it.cancel() }
        collectJobs = emptyList()
        monitors.forEach { it.stop() }
    }

    /**
     * 注入 CAN 侧指标。
     *
     * 工具箱不反向依赖 vehicle 包（探针必须能独立测试），所以帧率与静默总线由装配层推给它。
     * 用 CAS 更新而不是重建快照：诊断页随时可能在读，读侧必须看到一致状态。
     */
    fun updateCanMetrics(frameRateHz: Double?, silentBuses: List<String>) {
        _snapshot.update { current ->
            current.copy(canFrameRateHz = frameRateHz, canBusSilent = silentBuses)
        }
    }

    private inline fun update(transform: PerfSnapshot.() -> PerfSnapshot) {
        _snapshot.update { current -> current.transform() }
    }
}

/**
 * 快照 -> 人类可读多行诊断文本。
 *
 * 为什么值得单独做成纯函数：车机现场问题最终都要变成"发微信的一段文字"
 * （拷贝日志链路在实车上经常是坏的），一份能直接贴进工单的纯文本报告是最后一道取证通道。
 * 格式约束：一行一指标、缺数据显式写"无"，避免"少了一行"被读成"没问题"。
 */
object PerfReportFormatter {

    fun format(snapshot: PerfSnapshot): String = buildString {
        appendLine("== 车机性能诊断快照 @ ${snapshot.capturedAtMillis}ms ==")
        appendLine("总体等级: ${snapshot.overallSeverity.name}")
        appendLine()

        appendLine("[ANR]")
        val anr = snapshot.anr
        if (anr == null) {
            appendLine("  无 ANR 记录")
        } else {
            appendLine("  等级=${anr.level.displayName} 阻塞>=${anr.blockedDurationMillis}ms @${anr.detectedAtMillis}ms")
            appendLine("  帧内阻塞=${anr.wasRendering} 内存压力=${anr.memoryPressure}")
            appendLine("  主线程栈: ${anr.mainThreadStack?.lineSequence()?.take(8)?.joinToString(" / ") ?: "未捕获(低于dump阈值或反射失败)"}")
        }
        appendLine()

        appendLine("[帧率]")
        val frame = snapshot.frame
        if (frame == null) {
            appendLine("  无帧数据（未 attach Activity 或未到首个窗口）")
        } else {
            val breakdown = frame.causeBreakdown.entries.joinToString(",") { "${it.key.name}:${it.value}" }.ifEmpty { "无" }
            appendLine("  窗口=${frame.windowMillis}ms 帧数=${frame.totalFrames} jank=${frame.jankedFrames}(${formatPercent(frame.jankRatePercent)}) slow=${frame.slowFrames}")
            appendLine("  p50=${frame.p50FrameMillis}ms p95=${frame.p95FrameMillis}ms p99=${frame.p99FrameMillis}ms worst=${frame.worstFrameMillis}ms")
            appendLine("  归因: $breakdown 达标=${frame.meetsAcceptance}")
        }
        appendLine()

        appendLine("[内存]")
        val memory = snapshot.memory
        if (memory == null) {
            appendLine("  无内存采样")
        } else {
            appendLine("  Java堆=${formatBytes(memory.javaHeapUsedBytes)}/${formatBytes(memory.javaHeapMaxBytes)} native=${formatBytes(memory.nativeAllocatedBytes)} PSS=${memory.totalPssKb ?: 0}KB")
            appendLine("  窗口GC=${memory.gcCountSinceLastSample}次 堆增速=${formatBytes(memory.heapGrowthBytesPerMinute)}/min 压力=${memory.isUnderPressure}")
        }
        appendLine()

        appendLine("[分配抖动]")
        val jitter = snapshot.jitter
        if (jitter == null) {
            appendLine("  无抖动数据")
        } else {
            appendLine("  ${formatBytes(jitter.bytesAllocatedPerSecond)}/s 分配${jitter.allocations}个 GC=${jitter.gcCount}(阻塞${jitter.blockingGcCount}) 抖动中=${jitter.isJittering}")
        }
        appendLine()

        appendLine("[泄漏]")
        if (snapshot.leaks.isEmpty()) {
            appendLine("  无疑似泄漏")
        } else {
            snapshot.leaks.forEach { leak ->
                appendLine("  ${leak.tag} (${leak.className}) 已滞留 ${leak.retainedMinutes.toInt()}min：${leak.suspicionReason}")
            }
        }
        appendLine()

        appendLine("[启动]")
        val startup = snapshot.startup
        if (startup == null) {
            appendLine("  未收口（finish 未调用）")
        } else {
            appendLine("  首帧=${startup.totalMillis}ms 座舱预算达标=${startup.meetsCockpitBudget} 最慢阶段=${startup.slowestPhase?.name ?: "无"}(${startup.slowestPhase?.durationMillis ?: 0}ms)")
            startup.phases.forEach { appendLine("    - ${it.name}: ${it.durationMillis}ms") }
        }
        appendLine()

        appendLine("[保活]")
        val keepAlive = snapshot.keepAlive
        if (keepAlive == null) {
            appendLine("  无保活自检")
        } else {
            // List.ifEmpty 要求同类型返回值，空列表转文案必须手写分支。
            val fgsText = if (keepAlive.foregroundServiceTypes.isEmpty()) "无" else keepAlive.foregroundServiceTypes.joinToString("+")
            appendLine("  重要性=${keepAlive.processImportance.description} FGS类型=$fgsText 电池优化豁免=${keepAlive.batteryOptimizationIgnored} 待办Job=${keepAlive.scheduledJobs}")
            if (keepAlive.riskNotes.isEmpty()) appendLine("  风险: 无") else keepAlive.riskNotes.forEach { appendLine("  风险: $it") }
        }
        appendLine()

        appendLine("[CAN]")
        val busText = if (snapshot.canBusSilent.isEmpty()) "无" else snapshot.canBusSilent.joinToString("+")
        appendLine("  帧率=${snapshot.canFrameRateHz ?: Double.NaN}Hz 静默总线=$busText")
    }.trimEnd()

    /** 百分比固定一位小数；整数运算实现，避免 locale 差异（部分车机 ROM 是德语环境，String.format 会输出逗号小数）。 */
    private fun formatPercent(value: Double): String {
        val tenth = (value * 10.0).toLong().coerceAtLeast(0L)
        return "${tenth / 10}.${tenth % 10}%"
    }

    /** 字节数按十进制单位截断到人类可读写法；纯整数运算（locale 安全）。负数理论上不该出现，原样带符号按 B 输出。 */
    private fun formatBytes(bytes: Long): String = when {
        bytes < 0 -> "${bytes}B"
        bytes >= 1_000_000_000L -> "${bytes / 1_000_000_000L}GB"
        bytes >= 1_000_000L -> "${bytes / 1_000_000L}MB"
        bytes >= 1_000L -> "${bytes / 1_000L}KB"
        else -> "${bytes}B"
    }
}
