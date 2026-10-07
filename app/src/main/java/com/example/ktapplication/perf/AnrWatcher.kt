package com.example.ktapplication.perf

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Printer
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 主线程卡顿 / ANR 探针（心跳法）。
 *
 * 原理：后台协程每 [heartbeatIntervalMillis] 向主线程 Looper 投递一个"心跳"任务，
 * 心跳排队即被测量——"投出时间"与"实际执行时间"的差就是主线程被占用的时长。
 * 车机上这个探针必须能后台常开，所以自身开销是 O(1)：每个周期最多在主线程队列里挂一个任务，
 * 主线程被卡死时不再追加投递（beatPending 门闩），队列不会无限膨胀。
 *
 * **为什么在后台线程判定超时就抢先抓栈，而不是等心跳跑完再算**：
 * 心跳真正执行时主线程已经解除阻塞，此时抓到的栈是"事后现场"，卡点早就不在栈顶了。
 * 所以在后台协程里发现 `now - lastBeatCompleted >= dumpThreshold`（主线程还没消化心跳 = 仍被卡住）
 * 时立即 dump 主线程栈，拿到的才是卡点本身。代价是 [AnrReport.blockedDurationMillis]
 * 只是"至少卡了这么久"的下界——这在车机取证里够用，因为真正要看的是栈，不是精确时长。
 *
 * [AnrReport.wasRendering] 通过 `Looper.setMessageLogging(Printer)` 实现：
 * 主线程每处理一条 Message 前后各打一行日志，我们在 Printer 里识别 callback/target 含
 * Choreographer 的消息（即一帧的输入/动画/布局/绘制调度），据此区分"启动卡"
 * （无帧在跑）还是"滑动卡"（帧回调进行中）。车载场景两者处置方完全不同：
 * 启动卡找 Application/首帧链路，滑动卡找列表 measure/draw。
 * 注意：全系统只有这一个日志槽位，探针 stop 时必须置回 null，否则会把 systrace 之外的
 * 日志通道一直占死。
 *
 * 依赖注入 [MonotonicClock] 而不是 SystemClock.elapsedRealtime() 裸调用：
 * 车机休眠唤醒后墙上时间会跳变，测时长必须用单调时钟；且单测里可直接拨表。
 */
class AnrWatcher(
    context: Context,
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val dispatchers: AppDispatchers = ProductionAppDispatchers,
    private val heartbeatIntervalMillis: Long = 500L,
    private val dumpThresholdMillis: Long = AnrReport.DUMP_THRESHOLD_MILLIS,
) : PerfMonitor {

    override val name: String = "anr"

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 最近一次心跳在主线程上真正执行完毕的时刻（单调毫秒）。 */
    private val lastBeatCompletedAt = AtomicLong(clock.nowMillis())

    /** 门闩：主线程队列里已有一个待执行心跳时，不再重复投递。 */
    private val beatPending = AtomicBoolean(false)

    /** 当前主线程是否正在分发 Choreographer 帧消息。 */
    private val rendering = AtomicBoolean(false)

    /** 本轮阻塞只 dump 一次，防止持续卡顿刷屏（dump 本身是 stop-the-world 级别的开销）。 */
    private val episodeDumped = AtomicBoolean(false)

    private val _latest = MutableStateFlow<MonitorState>(MonitorState.Idle("探针未启动"))
    override val latest: StateFlow<MonitorState> = _latest

    /** 强类型结果通道，供 PerfToolkit 汇总成 PerfSnapshot。 */
    private val _anrReport = MutableStateFlow<AnrReport?>(null)
    val anrReport: StateFlow<AnrReport?> = _anrReport

    @Volatile
    private var watcherJob: Job? = null

    @Volatile
    private var messagePrinter: Printer? = null

    init {
        // 在主线程构造时先把 Thread 引用锁死：API 30+ 直接取公开的 Looper.getThread()；
        // 24–29 按线程名找 "main"（Android 进程的首个 Java 线程就是主线程，名字稳定），
        // 全程不反射框架私有字段，理由见 [resolveMainThread]。
        // 取舍说明：宁可上报"无栈"（mainThreadStack = null 的路径不保留，最差也是拿到当前线程近似），
        // 也不要在探针里引入崩溃风险。
        mainThreadRef = resolveMainThread()
    }

    override fun start(scope: CoroutineScope) {
        if (watcherJob != null) return
        lastBeatCompletedAt.set(clock.nowMillis())
        beatPending.set(false)
        episodeDumped.set(false)
        // setMessageLogging 放在主线程执行，保证与消息分发串行化，不漏掉第一条消息的日志。
        mainHandler.post { installPrinter() }
        watcherJob = scope.launch(dispatchers.default) {
            // 心跳协程挂在调用方传入的 scope 上，不新建 Thread：
            // stop()/scope 取消后零残留，满足"后台常开但不泄漏"的车载装机要求。
            while (isActive) {
                delay(heartbeatIntervalMillis)
                tick()
            }
        }
        _latest.value = MonitorState.Running("心跳监控中，周期 ${heartbeatIntervalMillis}ms")
    }

    override fun stop() {
        watcherJob?.cancel()
        watcherJob = null
        val printer = messagePrinter
        if (printer != null) {
            // 注意：Looper 只有 setMessageLogging，没有公开的 getMessageLogging 可以校验槽位归属，
            // 所以这里以"我们自己装过才摘"为约束；车机上如果同时跑第三方 trace 工具，
            // 由使用方决定探针与工具的先后顺序。
            mainHandler.post { Looper.getMainLooper().setMessageLogging(null) }
            messagePrinter = null
        }
        rendering.set(false)
        _latest.value = MonitorState.Idle("探针已停止")
    }

    /** 后台心跳周期：投递心跳 + 判定是否处于"卡住未消化"状态。 */
    private fun tick() {
        val now = clock.nowMillis()
        // 先原子占用门闩再投递：如果 post 之后才 set(true)，主线程空闲时心跳 lambda 可能先跑完
        // 把门闩置 false，我们再 set(true) 就把它永久锁死了（探针从此失明）。
        if (!beatPending.getAndSet(true)) {
            mainHandler.post {
                // 这段代码能跑起来 = 主线程刚刚腾出手。此刻的排队延迟就是本轮阻塞时长。
                val completedAt = clock.nowMillis()
                lastBeatCompletedAt.set(completedAt)
                beatPending.set(false)
            }
        }
        val stalledMillis = now - lastBeatCompletedAt.get()
        if (stalledMillis >= dumpThresholdMillis) {
            if (episodeDumped.compareAndSet(false, true)) {
                dumpBlockedEpisode(stalledMillis, now)
            }
        } else {
            episodeDumped.set(false)
            // 恢复期间把轻微阻塞（未达 dump 阈值但 > SLIGHT）也反映到 latest，UI 能看到"刚卡过"。
            val level = StallLevel.classify(stalledMillis)
            if (level != StallLevel.NORMAL && _anrReport.value == null) {
                _latest.value = MonitorState.Running("主线程最近一次心跳延迟 ${stalledMillis}ms（${level.displayName}）")
            }
        }
    }

    /** 达到 dump 阈值：抓"案发现场"的主线程栈 + 当时的渲染/内存状态。 */
    private fun dumpBlockedEpisode(stalledMillis: Long, detectedAtMillis: Long) {
        val level = StallLevel.classify(stalledMillis)
        // 从非主线程读另一个线程的栈是 Java 标准能力（VMStack.getThreadStackTrace），
        // 不会要求目标线程配合，正好适合"主线程回不来"的场景。
        val stack = runCatching {
            val thread = mainThreadRef
            // 阻塞场景下 Thread.getStackTrace 个别 AOSP 版本会返回空数组而不是 null，
            // 空栈比"没有栈"更容易被误读成"主线程空闲"，统一转成 null 交上层展示"无栈"。
            thread.stackTrace?.takeIf { it.isNotEmpty() }
                ?.joinToString("\n") { "    at $it" }
        }.getOrNull()
        val memoryPressure = queryMemoryPressure()
        val report = AnrReport(
            detectedAtMillis = detectedAtMillis,
            // 下界语义：抓到栈之后主线程可能还继续卡着，真实时长只会 >= 这个值。
            blockedDurationMillis = stalledMillis,
            level = level,
            mainThreadStack = stack,
            wasRendering = rendering.get(),
            memoryPressure = memoryPressure,
        )
        _anrReport.value = report
        _latest.value = MonitorState.Breach(
            summary = "主线程阻塞 ${stalledMillis}ms（${level.displayName}）",
            severity = if (level == StallLevel.ANR_SUSPECTED) Severity.CRITICAL else Severity.WARNING,
            detail = if (report.wasRendering) "发生在帧回调内（滑动卡）" else "非帧回调期间（启动/业务卡）",
        )
    }

    /**
     * 内存压力位。车机上 GC 与 lowmemorykiller 抢 CPU 会把卡顿放大数倍，
     * ANR 报告不带内存水位等于少半个证据链。
     * 说明：framework 里不存在 `ActivityManager.isLowMemDevice`（那是 OEM 私有能力），
     * 这里用公开的 availMem/threshold/lowMemory 组合判定。
     */
    private fun queryMemoryPressure(): Boolean = runCatching {
        val am = appContext.getSystemService(ActivityManager::class.java) ?: return false
        val info = ActivityManager.MemoryInfo()
        // getMemoryInfo 是 binder 调用，只能在非主线程调（此刻主线程正卡着，我们恰好在后台协程）。
        am.getMemoryInfo(info)
        info.lowMemory || info.availMem < info.threshold
    }.getOrDefault(false)

    private fun installPrinter() {
        if (messagePrinter != null) return
        val printer = Printer { line ->
            // Looper 消息分发约定：开始打 ">>>> Dispatching to ..."，结束打 "<<< ..."。
            when {
                line.startsWith(">>>>") -> rendering.set(line.contains(CHOREOGRAPHER_MARK))
                line.startsWith("<<<") -> rendering.set(false)
            }
        }
        messagePrinter = printer
        Looper.getMainLooper().setMessageLogging(printer)
    }

    companion object {
        private const val CHOREOGRAPHER_MARK = "android.view.Choreographer"

        /** Android 进程首个 Java 线程的固定名字，低版本用它定位主线程。 */
        private const val MAIN_THREAD_NAME = "main"

        @Volatile
        private var mainThreadRef: Thread = Thread.currentThread()
            // init 块会覆盖它；这里只是给一个非空初值，避免 Kotlin 编译期"未初始化"报错。

        /**
         * 取主线程 Thread 对象。
         *
         * `Looper.getThread()` 在框架实现里自 API 3 就存在，但**直到 API 30 才进公开 SDK**，
         * 所以只在 30+ 用它；24–29 走线程名查找，不碰反射。
         *
         * 原先这里反射 `Looper.mThread`，lint 报 SoonBlockedPrivateApi：私有字段访问在
         * 目标 API 36+ 会直接抛异常。ANR 探针去反射框架私有字段是本末倒置——为了看住主线程
         * 反而给自己埋一颗崩溃雷，所以彻底删掉这条路径。
         * 最差情况退化成交错线程的近似值：漏报优于误报，更优于探针自己崩。
         */
        private fun resolveMainThread(): Thread {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Looper.getMainLooper().thread
            // Android 进程的第一个 Java 线程名恒为 "main"，且与主 Looper 一一绑定；
            // getAllStackTraces() 只在构造时调一次，代价可以接受。
            val named = runCatching {
                Thread.getAllStackTraces().keys.firstOrNull { it.name == MAIN_THREAD_NAME }
            }.getOrNull()
            return named ?: Thread.currentThread()
        }
    }
}
