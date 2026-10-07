package com.example.ktapplication.perf

import android.annotation.SuppressLint
import android.os.Trace
import com.example.ktapplication.core.AppDispatchers
import com.example.ktapplication.core.MonotonicClock
import com.example.ktapplication.core.ProductionAppDispatchers
import com.example.ktapplication.core.SystemMonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 启动链路打点器。
 *
 * 双通道设计：每个阶段同时
 *  1. 写入 `android.os.Trace.beginSection/endSection` —— 出车前用 Perfetto 抓 oneTrace
 *     能直接和我们的数字对账，避免"探针说 800ms、systrace 说 2s"这种罗生门；
 *  2. 记录 [StartupPhase] —— 落盘/上报用的结构化数据，车机现场往往没有 systrace 条件。
 *
 * Trace 的正确性约束：begin/end 必须**同线程成对**。跨线程 end 会污染 systrace 时间线，
 * 所以 endPhase 只在"begin 与 end 同线程"时才补 Trace.endSection，否则宁可丢这段 trace，
 * 结构化数据仍然完整（取舍：systrace 是辅助证据，不是唯一真相）。
 *
 * 幂等要求来自车机现实：启动链路里同一阶段可能被两条路径各调一次 begin
 * （例如 Activity 重建后 onCreate 又走一遍 Application 级初始化），
 * 探针必须"重复 begin 取首次、重复 end 忽略"，绝不允许因为打点本身抛异常拖慢启动——
 * 打点代码跑在主线程启动关键路径上，它自己就是最需要稳的代码。
 */
class StartupTracer(
    private val clock: MonotonicClock = SystemMonotonicClock,
    @Suppress("UNUSED_PARAMETER") private val dispatchers: AppDispatchers = ProductionAppDispatchers,
) : PerfMonitor {

    override val name: String = "startup"

    private val _latest = MutableStateFlow<MonitorState>(MonitorState.Idle("等待启动打点"))
    override val latest: StateFlow<MonitorState> = _latest

    private val _startupReport = MutableStateFlow<StartupReport?>(null)
    val startupReport: StateFlow<StartupReport?> = _startupReport

    /** 构造时刻近似为进程启动时刻：Application/ DI 容器创建它，与真实进程启动差一个类加载窗口，
     * 车机上可接受（更精确的 proc start time 需要 /proc 或 IActivityManager，均非公开 API）。 */
    private val processStartAtMillis = clock.nowMillis()

    private val lock = Any()
    private val openPhases = LinkedHashMap<String, OpenPhase>()
    private val closedPhases = mutableListOf<StartupPhase>()
    private val tracePoints = LinkedHashMap<String, Long>()
    private var finished = false

    override fun start(scope: CoroutineScope) {
        // 启动探针没有常驻循环：它的窗口就是进程刚起的这几秒，
        // start 只把状态切到 Running，让诊断页能区分"没启动过探针"和"启动中"。
        synchronized(lock) { if (!finished) _latest.value = MonitorState.Running("启动打点中，已闭合 ${closedPhases.size} 段") }
    }

    override fun stop() {
        _latest.value = MonitorState.Idle("启动打点已停止")
    }

    /**
     * 阶段开始。同名阶段重复 begin：保留第一次的起点（幂等，见类头）。
     *
     * `Trace.beginSection/endSection` 是**线程内 LIFO 栈**，配对靠 [endPhase] 在另一个方法里完成，
     * 静态检查器只会看到"begin 之后可能提前 return"（其实那句 return 在 begin 之前，不会漏闭合），
     * 所以这里是误报；用注解说明"配对由本类的阶段状态机保证"，而不是把打点删掉。
     * 真正的代价写在 [endPhase]：跨线程 end 只能放弃补 endSection，宁可少一段 systrace，
     * 也不弹掉别人的段。
     */
    @SuppressLint("UnclosedTrace")
    fun beginPhase(phaseName: String) {
        synchronized(lock) {
            if (finished || openPhases.containsKey(phaseName)) return
            openPhases[phaseName] = OpenPhase(startMillis = clock.nowMillis(), thread = Thread.currentThread())
            runCatching { Trace.beginSection(phaseName) }
        }
    }

    /**
     * 阶段结束。未 begin 的 end、重复的 end 都静默忽略（幂等）；
     * 闭合顺序按 begin 的 FIFO 记录，保证 phases 列表还原启动链路的真实时序。
     */
    fun endPhase(phaseName: String) {
        synchronized(lock) {
            if (finished) return
            val open = openPhases.remove(phaseName) ?: return
            val now = clock.nowMillis()
            closedPhases.add(StartupPhase(phaseName, open.startMillis, now))
            // 跨线程 end 不补 Trace.endSection（会弹掉别的线程的段），只补结构化数据。
            if (open.thread === Thread.currentThread()) {
                runCatching { Trace.endSection() }
            }
            _latest.value = MonitorState.Running("启动打点中，已闭合 ${closedPhases.size} 段")
        }
    }

    /** 单时刻打点（不是阶段）：如 "firstFrame"、"canAttached"，进 StartupReport.tracePoints。 */
    fun tracePoint(pointName: String) {
        synchronized(lock) { if (!finished) tracePoints[pointName] = clock.nowMillis() }
    }

    /**
     * 收口生成报告。未闭合的阶段按当前时刻补齐并带 [UNCLOSED_SUFFIX] 标记——
     * 车机上"阶段没闭合"往往就是卡死点本身（比如卡在等 CAN 服务绑定），
     * 必须让它出现在报告里而不是静默丢弃。
     *
     * @param firstFrameAtMillis 可选：真实首帧的单调时刻（FrameMetrics/Choreographer 回调里取），
     *   传 null 时以 finish 调用时刻代偿。
     */
    fun finish(firstFrameAtMillis: Long? = null): StartupReport {
        synchronized(lock) {
            if (finished) return requireNotNull(_startupReport.value)
            finished = true
            val now = clock.nowMillis()
            // 未闭合阶段：openPhases 里剩下的按 begin 顺序补 [unclosed] 段。
            for ((phaseName, open) in openPhases) {
                closedPhases.add(StartupPhase("$phaseName$UNCLOSED_SUFFIX", open.startMillis, now))
                // trace 段无法在"不知道是否同线程"的安全前提下闭合，留给 systrace 自己超时清理；
                // 报告正确性优先于 trace 美观。
            }
            openPhases.clear()
            val total = (firstFrameAtMillis ?: now) - processStartAtMillis
            val report = StartupMath.report(
                appProcessStartToFirstFrameMillis = total.coerceAtLeast(0L),
                phases = closedPhases.toList(),
                tracePoints = tracePoints.toMap(),
            )
            _startupReport.value = report
            _latest.value = if (report.meetsCockpitBudget) {
                MonitorState.Running("首帧 ${report.totalMillis}ms，满足 2s 座舱预算", detail = report.slowestPhase?.name ?: "")
            } else {
                MonitorState.Breach(
                    summary = "首帧 ${report.totalMillis}ms，超座舱预算",
                    severity = if (report.totalMillis > StartupMath.COCKPIT_BUDGET_MILLIS * 2) Severity.CRITICAL else Severity.WARNING,
                    detail = "最慢阶段：${report.slowestPhase?.name ?: "无"} ${report.slowestPhase?.durationMillis ?: 0}ms",
                )
            }
            return report
        }
    }

    /** 报告是否已生成（Activity 重建等场景避免重复 finish）。 */
    fun isFinished(): Boolean = synchronized(lock) { finished }

    private data class OpenPhase(val startMillis: Long, val thread: Thread)

    companion object {
        const val UNCLOSED_SUFFIX = " [unclosed]"
    }
}

/**
 * 启动报告的纯函数部分：不碰任何 framework API，JVM 单测直接验证
 * 阶段耗时、unclosed 补齐、预算判定。
 */
object StartupMath {

    /** 车机验收线：上电到仪表关键信息可见 <= 2s（与 StartupReport.meetsCockpitBudget 同源）。 */
    const val COCKPIT_BUDGET_MILLIS = 2_000L

    fun report(
        appProcessStartToFirstFrameMillis: Long,
        phases: List<StartupPhase>,
        tracePoints: Map<String, Long>,
    ): StartupReport = StartupReport(
        appProcessStartToFirstFrameMillis = appProcessStartToFirstFrameMillis,
        phases = phases,
        tracePoints = tracePoints,
    )

    /**
     * 把"单点时刻序列"展开成相邻阶段：第 i 段从 points[i] 走到 points[i+1]，
     * 以 name_i 命名（i 时刻发生的动作持续到下一个打点）。
     * 最后一个点没有终点，调用方给 nowMillis，段名带 [unclosed] 标记——
     * 这条规则让"只打了 begin 没打 end"在报告里永远可见。
     */
    fun phasesFromTimestamps(points: List<Pair<String, Long>>, nowMillis: Long): List<StartupPhase> {
        if (points.isEmpty()) return emptyList()
        val sorted = points.sortedBy { it.second }
        val result = mutableListOf<StartupPhase>()
        for (i in 0 until sorted.size - 1) {
            val (name, start) = sorted[i]
            val end = sorted[i + 1].second
            result.add(StartupPhase(name, start, end.coerceAtLeast(start)))
        }
        val (lastName, lastStart) = sorted.last()
        if (sorted.size == 1 || lastStart < nowMillis) {
            result.add(StartupPhase("$lastName${StartupTracer.UNCLOSED_SUFFIX}", lastStart, nowMillis.coerceAtLeast(lastStart)))
        }
        return result
    }

    /** 任意预算线下的达标判定（模型里 meetsCockpitBudget 固定 2s，这里支持车厂自定义指标）。 */
    fun meetsBudget(totalMillis: Long, budgetMillis: Long = COCKPIT_BUDGET_MILLIS): Boolean =
        totalMillis <= budgetMillis
}
