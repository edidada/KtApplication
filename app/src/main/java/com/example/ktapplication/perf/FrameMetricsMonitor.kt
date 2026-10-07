package com.example.ktapplication.perf

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import com.example.ktapplication.core.AppDispatchers
import com.example.ktapplication.core.MonotonicClock
import com.example.ktapplication.core.ProductionAppDispatchers
import com.example.ktapplication.core.SystemMonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * 基于 `Window.OnFrameMetricsAvailableListener` 的逐帧耗时统计。
 *
 * 注册点必须是 **Window**（`Window.addOnFrameMetricsAvailableListener(listener, handler)` /
 * `removeOnFrameMetricsAvailableListener(listener)`），不能在 View 或 ViewTreeObserver 上找这个
 * 监听器——帧数据是框架在 PhoneWindow 的绘制链路里派发的，`ViewTreeObserver` 从来没有这套回调，
 * 按 View 注册只会编译不过或注册到错误的观察者上。因此 attach 的入参是 Activity（要拿
 * `activity.window`）；窗口 decor 尚未创建时框架会直接抛 IllegalStateException，本类不把它
 * 判成永久不可用，而是退避重试几次（见 [attach]），最后才写 `MonitorState.Unavailable`——
 * 宁可多试半秒，也不让探针因为"挂载时机差了十几毫秒"整个会话都没有数据。
 *
 * 线程模型（关键约束）：
 *  - 系统要求注册时传入一个 Handler，回调**永远运行在该 Handler 所属线程**上（这里是专用
 *    HandlerThread），不是主线程。因此回调里绝对不允许做 Compose 重组、findViewById、
 *    任何 UI 操作，只能把 FrameMetrics 读成纯数据塞进缓冲区——这也是车机常开探针不会
 *    "探针卡主线程"的原因。
 *  - FrameMetrics 对象由系统池化复用，只能在回调栈内读取，不能跨回调持有引用。
 *
 * 单位与精度口径（真正需要运行时分支的地方）：
 *  - `FrameMetrics` 对外只暴露 `getMetric(int)`，阶段常量就是 API 24 起那一套
 *    （`INPUT_HANDLING_DURATION`/`ANIMATION_DURATION`/`LAYOUT_MEASURE_DURATION`/`DRAW_DURATION`/
 *    `SYNC_DURATION`/`COMMAND_ISSUE_DURATION`/`SWAP_BUFFERS_DURATION`/`TOTAL_DURATION` 等），
 *    **返回值一律是纳秒**；不存在所谓 `FRAME_DURATION_*_NAVNS` 新常量，也没有 API 31 才有的第二套
 *    命名（那套"pre-36 常量已废弃、新版本换名"的说法是对 SDK 演进的误传）。
 *    所以阶段拆分在所有 minSdk 24 以上的设备上口径一致，不需要按版本缺省。
 *  - 真正的坑是**宽度**：API 36 之前框架内部用 32 位存这个纳秒值，单帧超过约 4.29s
 *    （= 2^32 ns）会回绕，车机上 GPU 调试层开启、温控降频或 dumpGPU 时真能撞到，
 *    于是"卡死 5 秒"会被统计成"很快的一帧"，p99 直接失真。老设备走无符号还原
 *    （见 [decodeNanos]），36 起读原生 long。版本判断只在这一处，别得到处写 SDK_INT。
 *  - 首帧（`FIRST_DRAW_FRAME`）不过滤：冷启动首帧的耗时归因属于 StartupTracer 的预算口径，
 *    这里再判一次会出现"同一帧两处报"，诊断页宁可信一处。
 *
 * 分位数自己实现（排序 + ceil 线性索引），不引入任何统计库：车机验收看的是 p95/p99 而不是均值，
 * 均值会被偶发抖动抹平，p99 才能暴露"每秒卡一下"这种实车最难复现的问题。
 */
class FrameMetricsMonitor(
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val dispatchers: AppDispatchers = ProductionAppDispatchers,
    /** 车机屏常见 60/90Hz；预算与归因阈值全部由 JankClassifier 按刷新率推导。 */
    private val refreshRateHz: Int = 60,
    private val windowMillis: Long = 1_000L,
) : PerfMonitor {

    override val name: String = "frame"

    private val _latest = MutableStateFlow<MonitorState>(
        MonitorState.Unavailable("尚未调用 attach(activity)，没有可注册帧回调的 Window")
    )
    override val latest: StateFlow<MonitorState> = _latest

    private val _frameReport = MutableStateFlow<FrameMetricsReport?>(null)
    val frameReport: StateFlow<FrameMetricsReport?> = _frameReport

    private val bufferLock = Any()
    private var pendingTimings = mutableListOf<FrameTiming>()

    /** 系统侧因缓冲池耗尽丢掉的帧数：>0 说明统计有偏，报告要打折看。 */
    @Volatile
    private var droppedBySystem = 0L

    private var handlerThread: HandlerThread? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null

    /**
     * 重试用的主线程 Handler。lazy 是刻意的：探针若在某些 JVM 单测里被构造出来，
     * 构造期就去碰 android.os.Handler 会拿到桩实现，注册重试这条路径根本不该被单测触发。
     */
    private val mainHandler: Handler by lazy(LazyThreadSafetyMode.NONE) { Handler(Looper.getMainLooper()) }

    @Volatile
    private var pendingAttach: Runnable? = null

    /** 注册用的 Window：注销必须走同一个实例，Activity 销毁后旧 window 再取也是它。 */
    private var attachedWindow: Window? = null
    private var flushJob: Job? = null
    private var lastWindowStartMillis = clock.nowMillis()

    override fun start(scope: CoroutineScope) {
        if (flushJob != null) return
        lastWindowStartMillis = clock.nowMillis()
        flushJob = scope.launch(dispatchers.default) {
            // 窗口滚动：每 windowMillis 把缓冲区里的帧聚合成一份报告。
            // 聚合是纯计算 + 排序，放 default 池，不进主线程。
            while (isActive) {
                delay(windowMillis)
                flushWindow()
            }
        }
    }

    /**
     * 从 Activity 的 Window 注册帧回调，注册被拒时自动退避重试。
     *
     * 为什么需要重试：`PhoneWindow.addOnFrameMetricsAvailableListener` 在 `mDecor == null` 时直接抛
     * IllegalStateException，而 decor 要到 setContentView 才创建。界面侧（MainActivity）为了尽早
     * 抓到冷启动首帧，会在 setContent 前后就调进来，这一瞬间窗口完全可能还没就绪。
     * 一次失败就报 Unavailable 的话，诊断页会常年显示"无帧数据"，而真实原因是挂载时机差了十几毫秒
     * —— 这是探针自己的 bug，不该算到被测程序头上。
     *
     * 签名保持 Unit：调用方（AppGraph 的 attachFrameMetrics）只负责"尽量早地喂一个窗口进来"，
     * 判定归探针自己。
     */
    fun attach(activity: Activity) {
        cancelPendingAttach()
        attach(activity, attempt = 1)
    }

    private fun attach(activity: Activity, attempt: Int) {
        val window = activity.window
        if (listener != null) detach()
        val thread = HandlerThread("FrameMetricsMonitor").apply { start() }
        val callbackHandler = Handler(thread.looper)
        // 显式 SAM 构造：监听器是 Window 的嵌套接口，写成 ViewTreeObserver.X 那种"顺手猜一个宿主"
        // 的形式编译期就解析不到，回调参数类型也就无从推断。
        val newListener = Window.OnFrameMetricsAvailableListener { _, metrics, dropCount ->
            // 此回调运行在 handlerThread 上：只做"读数字 + 入缓冲"两件事。
            droppedBySystem += dropCount.toLong()
            val timing = readTiming(metrics)
            synchronized(bufferLock) { pendingTimings.add(timing) }
        }
        val registered = runCatching {
            window.addOnFrameMetricsAvailableListener(newListener, callbackHandler)
        }.isSuccess
        if (!registered) {
            thread.quitSafely()
            // 重试落在主线程 Handler 而不是 decorView.post：post 的目标是尚未 attach 的视图树，
            // runnable 会一直排到 view attach 之后才执行，看着像"重试"其实是无限期挂起，
            // 反而永远等不到失败结论；主线程 Handler 的延时要的是"确定性"。
            if (attempt < MAX_ATTACH_ATTEMPTS && !activity.isFinishing && !activity.isDestroyed) {
                val retry = Runnable { if (listener == null) attach(activity, attempt + 1) }
                pendingAttach = retry
                mainHandler.postDelayed(retry, ATTACH_RETRY_DELAY_MILLIS * attempt)
                return
            }
            // 重试用尽（该 ROM 的窗口确实不给帧回调 / Activity 已销毁）就把原因交出去，
            // 探针不静默降级成"0 帧"——0 帧在车机验收里会被读成"画面完全流畅"，比没数据更危险。
            _latest.value = MonitorState.Unavailable(
                "Window 拒绝帧回调注册（已重试 ${MAX_ATTACH_ATTEMPTS - 1} 次）：请确认 Activity 未销毁且窗口已 setContentView"
            )
            return
        }
        handlerThread = thread
        listener = newListener
        attachedWindow = window
        if (flushJob == null) {
            _latest.value = MonitorState.Running("已注册帧回调，等待窗口聚合（${refreshRateHz}Hz）")
        }
    }

    private fun cancelPendingAttach() {
        pendingAttach?.let { mainHandler.removeCallbacks(it) }
        pendingAttach = null
    }

    fun detach() {
        // 注销走当初注册的那个 Window：换 decorView 再取 ViewTreeObserver 是拿不到解绑效果的，
        // 监听器会一直挂在窗口上并持续向已退出的 HandlerThread 投事件。
        val currentListener = listener
        val currentWindow = attachedWindow
        if (currentListener != null && currentWindow != null) {
            runCatching { currentWindow.removeOnFrameMetricsAvailableListener(currentListener) }
        }
        handlerThread?.quitSafely()
        listener = null
        attachedWindow = null
        handlerThread = null
    }

    override fun stop() {
        flushJob?.cancel()
        flushJob = null
        // 排队的重试也必须撤掉：否则 stop 之后主线程还会跑一次 attach，把探针悄悄重新拉起，
        // 而退出界面上的状态已经被写成"帧统计已停止"。
        cancelPendingAttach()
        detach()
        _latest.value = MonitorState.Idle("帧统计已停止")
    }

    private fun flushWindow() {
        val timings = synchronized(bufferLock) {
            val snapshot = pendingTimings.toList()
            pendingTimings.clear()
            snapshot
        }
        if (timings.isEmpty()) return
        val now = clock.nowMillis()
        val actualWindowMillis = (now - lastWindowStartMillis).coerceAtLeast(1L)
        lastWindowStartMillis = now
        val report = FrameMetricsMath.aggregate(timings, actualWindowMillis, refreshRateHz, droppedBySystem)
        _frameReport.value = report
        _latest.value = if (report.meetsAcceptance) {
            MonitorState.Running(
                summary = "jank 率 ${report.jankRatePercent.toInt()}%（p99 ${report.p99FrameMillis}ms）",
                detail = "窗口 ${report.windowMillis}ms，共 ${report.totalFrames} 帧，系统侧丢弃 $droppedBySystem 帧",
            )
        } else {
            MonitorState.Breach(
                summary = "jank 率 ${report.jankRatePercent.toInt()}%，超车机验收线（worst ${report.worstFrameMillis}ms）",
                severity = Severity.CRITICAL,
                detail = report.causeBreakdown.entries.joinToString(",") { "${it.key.name}:${it.value}帧" },
            )
        }
    }

    /**
     * 读一条 FrameMetrics 的全部归因阶段。常量集合只有一套（API 24 起），因此这里不需要按版本
     * 分叉读法；唯一的版本差异在**宽度**上，交给 [decodeNanos] 处理，见类头口径说明。
     */
    private fun readTiming(metrics: FrameMetrics): FrameTiming {
        fun stage(key: Int): Long = metricMillis(metrics, key)
        return FrameTiming(
            durationMillis = stage(FrameMetrics.TOTAL_DURATION),
            userInputMillis = stage(FrameMetrics.INPUT_HANDLING_DURATION),
            animationMillis = stage(FrameMetrics.ANIMATION_DURATION),
            layoutMeasureMillis = stage(FrameMetrics.LAYOUT_MEASURE_DURATION),
            drawMillis = stage(FrameMetrics.DRAW_DURATION),
            syncMillis = stage(FrameMetrics.SYNC_DURATION),
            // command issue / swap 是 DISPLAY 与 RENDER 归因的分界线（JankClassifier 靠它分流
            // "自己画得慢"和"缓冲区/VSync 没赶上"），API 24 就有，老设备上没必要留空字段。
            commandIssueMillis = stage(FrameMetrics.COMMAND_ISSUE_DURATION),
            swapMillis = stage(FrameMetrics.SWAP_BUFFERS_DURATION),
        )
    }

    /** 单个阶段：先还原宽度，再纳秒转毫秒。整除不用 toDouble，避免浮点误差把阶段和抹不平。 */
    private fun metricMillis(metrics: FrameMetrics, key: Int): Long =
        (decodeNanos(metrics.getMetric(key)) / NANOS_PER_MILLIS).coerceAtLeast(0L)

    /**
     * 32 位还原：API 36 之前框架内部把这个纳秒计数按 int 存取，超过 2^32ns(≈4.29s) 的单帧会回绕，
     * 低 32 位之外的部分直接丢掉。车机上的实测场景是 GPU 调试层/dumpGPU/温控降频把一帧拖到秒级，
     * 此时若按原值统计，回绕后的"小帧"会同时压低 p99 并清零 worstFrameMillis，
     * 现场看到的现象就是"卡成幻灯片但诊断页全绿"。36 起 getMetric 才是完整 long。
     *
     * 注意还原的边界：它只能恢复"回绕不到一圈"的情形（真实耗时 < 8.59s 量级），
     * 再长就无从推断——那种帧属于系统级冻结，交给 AnrWatcher 的时间线，不指望探针兜住。
     */
    private fun decodeNanos(raw: Long): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            raw
        } else {
            // 只留低 32 位并按无符号解释：回绕成"负数"的老数值若原样下游处理，coerceAtLeast(0) 会把它静默清零。
            raw and UNSIGNED_INT_32_MASK
        }

    private companion object {
        const val NANOS_PER_MILLIS = 1_000_000L

        /** 0xFFFF_FFFF：与 getMetric 低 32 位做与运算即得无符号纳秒值。 */
        const val UNSIGNED_INT_32_MASK = 0xFFFF_FFFFL

        /**
         * attach 被窗口拒绝后的重试次数与退避基数。
         *
         * 四次、累计约 0.5s：足够跨过 onCreate→setContentView→首帧 layout 的空窗，
         * 又短到不会让探针在真正不可用的 ROM 上无限挂着一个 Activity 引用。
         */
        const val MAX_ATTACH_ATTEMPTS = 4
        const val ATTACH_RETRY_DELAY_MILLIS = 60L
    }
}

/**
 * 帧窗口聚合的纯函数部分：输入一批 FrameTiming，输出 FrameMetricsReport。
 * 与框架完全解耦，JVM 单测可以直接构造 FrameTiming 列表验证分位数与归因分布。
 */
object FrameMetricsMath {

    /**
     * 分位数：对排序后的耗时的 ceil(p * (n-1)) 索引取值（"最近邻"法）。
     * 车机验收常用 p95/p99，样本量小的时候线性插值反而制造假精度，最近邻最诚实。
     */
    fun percentile(sortedMillis: List<Long>, p: Double): Long {
        if (sortedMillis.isEmpty()) return 0L
        val index = ceil(p * (sortedMillis.size - 1)).toInt().coerceIn(0, sortedMillis.size - 1)
        return sortedMillis[index]
    }

    fun aggregate(
        timings: List<FrameTiming>,
        windowMillis: Long,
        refreshRateHz: Int,
        droppedBySystem: Long = 0L,
    ): FrameMetricsReport {
        val budget = JankClassifier.frameBudgetMillis(refreshRateHz)
        val durations = timings.map { it.durationMillis }.sorted()
        var janked = 0L
        var slow = 0L
        val breakdown = HashMap<JankCause, Long>()
        for (timing in timings) {
            val cause = JankClassifier.classify(timing, refreshRateHz)
            if (cause != JankCause.NONE) janked++
            // "慢帧"定义：耗时翻倍于预算（连丢 >=1 帧），比 janked 更严重一档，
            // 仪表动画出现 slow 帧用户已经肉眼可见。
            if (timing.durationMillis >= budget * 2) slow++
            if (cause != JankCause.NONE) breakdown[cause] = (breakdown[cause] ?: 0L) + 1L
        }
        // droppedBySystem 不进数据模型（PerfModels 是契约，不改字段），
        // 但它决定这份报告的置信度：真机上持续 >0 说明帧回调消费慢于系统生产，
        // 由 monitor.latest.detail 在 UI 层提示，调用方自查 HandlerThread 优先级。
        return FrameMetricsReport(
            windowMillis = windowMillis,
            totalFrames = timings.size.toLong(),
            jankedFrames = janked,
            slowFrames = slow,
            causeBreakdown = breakdown,
            p50FrameMillis = percentile(durations, 0.50),
            p95FrameMillis = percentile(durations, 0.95),
            p99FrameMillis = percentile(durations, 0.99),
            worstFrameMillis = durations.lastOrNull() ?: 0L,
        )
    }
}
