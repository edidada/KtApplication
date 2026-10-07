package com.example.ktapplication.perf

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * 性能/稳定性监控的统一契约。
 *
 * 车机对稳定性的要求远高于手机：仪表 3 秒不出数、黑屏、重启都属于事故。
 * 所以这里不是"能抓 bug 就行"，而是要满足三条车载约束：
 *  1. **零第三方依赖**：车机上装不了 LeakCanery/Matrix 这类外部 AAR，只能自研探针；
 *  2. **可后台常开**：探针自身的开销必须是常数级，ANR 监控不能自己先把主线程拖住；
 *  3. **可离线取证**：现场数据要能落到本地文件，因为实车问题往往复现不了，
 *     只能靠用户"把日志拷给我"。
 */
interface PerfMonitor {

    val name: String

    /** 探针自身上报的最近一次结果，UI 的诊断页直接订阅它。 */
    val latest: StateFlow<MonitorState>

    fun start(scope: CoroutineScope)

    fun stop()
}

/** 每个监控项的统一状态封装：要么有数据，要么明确说明为什么没数据。 */
sealed class MonitorState {
    data class Idle(val reason: String) : MonitorState()
    data class Unavailable(val reason: String) : MonitorState()
    data class Running(val summary: String, val detail: String = "") : MonitorState()
    data class Breach(val summary: String, val severity: Severity, val detail: String = "") : MonitorState()

    val isBreach: Boolean get() = this is Breach
}

enum class Severity { INFO, WARNING, CRITICAL }

/** 卡顿/ANR 分级：阈值来自车机验收标准，不是手机上的 16ms 直觉。 */
enum class StallLevel(val minDurationMillis: Long, val displayName: String) {
    NORMAL(0, "正常"),
    SLIGHT(100, "轻微卡顿"),
    NOTICEABLE(300, "明显卡顿"),
    SEVERE(700, "严重卡顿"),
    ANR_SUSPECTED(5_000, "疑似 ANR"),
    ;

    companion object {
        /**
         * 分级规则单独抽成纯函数，方便单测覆盖边界值。
         *
         * 5s 是 Android ANR 的输入事件超时下限，实测车机上从主线程阻塞到系统真正弹 ANR
         * 往往还要再加 broadcast/service 的 10s/20s 预算，所以这里把 5s 定为"疑似"，
         * 让我们比系统早一步拿到栈。
         */
        fun classify(durationMillis: Long): StallLevel =
            entries.lastOrNull { durationMillis >= it.minDurationMillis } ?: NORMAL
    }
}

/**
 * 掉帧归因。
 *
 * FrameMetrics 的 12 个阶段里，UNKNOWN 通常意味着系统侧问题（surfaceflinger、
 * system_server 被车机其他 App 拖累）；APP 高说明我们的 measure/layout/draw 重，
 * RENDER 高说明 GPU/渲染线程忙 —— 这三种情况在车机上的处理方完全不同，
 * 必须区分上报，否则会把自己 App 的问题误报成"系统卡"。
 */
enum class JankCause {
    /** USER_INPUT：事件分发慢，通常是主线程被业务代码占住。 */
    APP,

    /** 渲染线程/Shader 编译/GPU 忙。 */
    RENDER,

    /** Swap 等待，通常是缓冲区不足或 VSync 错过。 */
    DISPLAY,

    /** 各阶段都在预算内但整体超时：外部因素（CPU 被车机其他进程抢占、温控降频）。 */
    UNKNOWN,

    /** 没超时。 */
    NONE,
}

/** 单帧归因所需的全部时间，纯计算部分独立出来便于单测。 */
data class FrameTiming(
    val durationMillis: Long,
    val userInputMillis: Long = 0,
    val animationMillis: Long = 0,
    val layoutMeasureMillis: Long = 0,
    val drawMillis: Long = 0,
    val syncMillis: Long = 0,
    val commandIssueMillis: Long = 0,
    val swapMillis: Long = 0,
) {
    val appPhaseMillis: Long get() = userInputMillis + animationMillis + layoutMeasureMillis + drawMillis
    val renderPhaseMillis: Long get() = syncMillis + commandIssueMillis
}

data class FrameMetricsReport(
    val windowMillis: Long,
    val totalFrames: Long,
    val jankedFrames: Long,
    val slowFrames: Long,
    val causeBreakdown: Map<JankCause, Long>,
    val p50FrameMillis: Long,
    val p95FrameMillis: Long,
    val p99FrameMillis: Long,
    val worstFrameMillis: Long,
) {
    val jankRatePercent: Double
        get() = if (totalFrames == 0L) 0.0 else jankedFrames * 100.0 / totalFrames

    /** 车机验收标准一般要求仪表/中控 jank 率 < 5%（60Hz）。 */
    val meetsAcceptance: Boolean get() = jankRatePercent < 5.0 && worstFrameMillis < 1_000
}

data class AnrReport(
    val detectedAtMillis: Long,
    val blockedDurationMillis: Long,
    val level: StallLevel,
    val mainThreadStack: String?,
    /** 阻塞期间是否有正在执行的 Choreographer 帧，用来区分"启动卡"和"滑动卡"。 */
    val wasRendering: Boolean,
    val memoryPressure: Boolean,
) {
    companion object {
        /**
         * 只有达到这个时长才 dump 主线程栈：
         * dump 本身要 stop-the-world，把阈值定太低会让探针成为新的卡顿源。
         */
        const val DUMP_THRESHOLD_MILLIS: Long = 2_000
    }
}

data class MemoryReport(
    val capturedAtMillis: Long,
    val javaHeapUsedBytes: Long,
    val javaHeapMaxBytes: Long,
    val nativeAllocatedBytes: Long,
    val totalPssKb: Long?,
    /** 已触发 GC 次数与堆增长速率：内存抖动的宏观指标。 */
    val gcCountSinceLastSample: Int,
    val heapGrowthBytesPerMinute: Long,
) {
    val usedRatio: Double
        get() = if (javaHeapMaxBytes == 0L) 0.0 else javaHeapUsedBytes.toDouble() / javaHeapMaxBytes

    /** 超过 80% 就要主动收敛（车机不会像手机那样频繁杀后台，OOM 直接黑屏）。 */
    val isUnderPressure: Boolean get() = usedRatio > 0.8
}

/**
 * 内存抖动（allocation jitter）报告。
 *
 * 抖动本身不致命，致命的是抖动 + 后台常驻：GC 会在滑动/动画期间抢 CPU，表现为周期性掉帧。
 * 定位手段是 Debug.startAllocCounting 拿分配计数与 GC 次数，按窗口算"每秒分配字节数"。
 */
data class AllocationJitterReport(
    val windowMillis: Long,
    val allocations: Long,
    val bytesAllocated: Long,
    val gcCount: Int,
    val blockingGcCount: Int,
) {
    val bytesAllocatedPerSecond: Long
        get() = if (windowMillis <= 0) 0 else bytesAllocated * 1000 / windowMillis

    /** 车机上按 5MB/s 作为告警线：低端平台一代 GC 大约就是这个量级开始抢帧。 */
    val isJittering: Boolean get() = bytesAllocatedPerSecond > 5_000_000L || blockingGcCount > 3
}

data class LeakReport(
    val tag: String,
    val className: String,
    val retainedSinceMillis: Long,
    val firstSeenAtMillis: Long,
    /** 关键：泄漏对象上的弱引用没进 ReferenceQueue，说明它仍被强引用链持有。 */
    val suspicionReason: String,
) {
    val retainedMinutes: Double get() = retainedSinceMillis / 60_000.0
}

/** 启动阶段耗时。车机的"冷启动"包含上电到仪表出图，和 App 冷启动是两个指标。 */
data class StartupReport(
    val appProcessStartToFirstFrameMillis: Long,
    val phases: List<StartupPhase>,
    val tracePoints: Map<String, Long>,
) {
    val totalMillis: Long get() = appProcessStartToFirstFrameMillis

    /** 车机验收常要求仪表关键信息（车速）在 2s 内可见。 */
    val meetsCockpitBudget: Boolean get() = appProcessStartToFirstFrameMillis <= 2_000

    val slowestPhase: StartupPhase? get() = phases.maxByOrNull { it.durationMillis }
}

data class StartupPhase(
    val name: String,
    val startMillis: Long,
    val endMillis: Long,
) {
    val durationMillis: Long get() = (endMillis - startMillis).coerceAtLeast(0)

    companion object {
        fun of(name: String, startMillis: Long, durationMillis: Long) =
            StartupPhase(name, startMillis, startMillis + durationMillis)
    }
}

/**
 * 后台保活自检。
 *
 * 车机上的"保活"和手机上完全两个概念：车机是单一用途设备，座舱 App 是被系统
 * 白名单保护的常驻进程，真正的问题在于 **Android 12+ 的 FGS 类型限制** 和
 * 车机厂自己加的省电策略。这里输出的是"当前保活手段是否还成立"，而不是
 * 教用户怎么写一个开机自启服务。
 */
data class KeepAliveReport(
    val processImportance: ProcessImportance,
    val foregroundServiceTypes: List<String>,
    val batteryOptimizationIgnored: Boolean,
    val scheduledJobs: Int,
    val riskNotes: List<String>,
)

enum class ProcessImportance(val description: String) {
    FOREGROUND("前台，几乎不会被杀"),
    VISIBLE("可见"),
    SERVICE("有服务在跑"),
    CACHED("缓存进程，随时可能被回收"),
    UNKNOWN("取不到（权限受限）"),
}

/** 诊断页展示用的聚合快照。 */
data class PerfSnapshot(
    val capturedAtMillis: Long,
    val anr: AnrReport?,
    val frame: FrameMetricsReport?,
    val memory: MemoryReport?,
    val jitter: AllocationJitterReport?,
    val leaks: List<LeakReport>,
    val keepAlive: KeepAliveReport?,
    val startup: StartupReport?,
    val canFrameRateHz: Double?,
    val canBusSilent: List<String>,
) {
    val overallSeverity: Severity
        get() = when {
            anr?.level == StallLevel.ANR_SUSPECTED -> Severity.CRITICAL
            leaks.isNotEmpty() -> Severity.CRITICAL
            frame != null && !frame.meetsAcceptance -> Severity.CRITICAL
            canBusSilent.isNotEmpty() -> Severity.WARNING
            anr != null -> Severity.WARNING
            jitter?.isJittering == true -> Severity.WARNING
            memory?.isUnderPressure == true -> Severity.WARNING
            startup != null && !startup.meetsCockpitBudget -> Severity.WARNING
            else -> Severity.INFO
        }
}

/**
 * 掉帧归因规则。
 *
 * 判定顺序遵循"先排除自己，再怀疑系统"：主线程阶段超预算归 APP，
 * 渲染线程超预算归 RENDER，swap 等待归 DISPLAY，都合不上就归 UNKNOWN（外部抢占/降频）。
 */
object JankClassifier {

    /** 60Hz 下一帧预算 16.6ms，但要给系统留出约 20% 余量，实际 App 侧预算按 12ms 算。 */
    fun frameBudgetMillis(refreshRateHz: Int): Long =
        if (refreshRateHz <= 0) 16L else 1000L / refreshRateHz * 8 / 10

    fun classify(timing: FrameTiming, refreshRateHz: Int): JankCause {
        val budget = frameBudgetMillis(refreshRateHz)
        if (timing.durationMillis <= budget) return JankCause.NONE
        return when {
            timing.appPhaseMillis >= budget * 0.6 -> JankCause.APP
            timing.renderPhaseMillis >= budget * 0.5 -> JankCause.RENDER
            timing.swapMillis >= budget * 0.4 -> JankCause.DISPLAY
            else -> JankCause.UNKNOWN
        }
    }
}
