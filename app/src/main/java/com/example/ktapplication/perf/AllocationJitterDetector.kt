package com.example.ktapplication.perf

import android.os.Debug
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

/**
 * 内存抖动（allocation jitter）探针。
 *
 * 车机上抖动的杀伤路径：高频分配 -> 并发 GC 抢 CPU -> 滑动/动画期间周期性掉帧。
 * 光看 MemoryProbe 的水位发现不了（堆可能一直不大），必须看**分配速率**本身。
 *
 * 实现走 ART 的调试计数器：
 *  - `Debug.startAllocCounting()` 打开分配计数（进程级开关，常开有微小开销，
 *    量产 ROM 可通过是否启动本探针控制）；`stopAllocCounting()` 在 stop 时关掉。
 *    这两个开关是**全局布尔**、framework 里没有引用计数，所以两个探针同时开启时先 stop 的会
 *    把另一个的计数也关掉——本工具箱里只有这一个消费者，约定不要再引第二个分配探针。
 *  - 快照来源是**公开 API 的三个全局计数器**：`getGlobalAllocCount()`（对象分配次数）、
 *    `getGlobalAllocSize()`（分配字节数）、`getGlobalGcInvocationCount()`（GC 次数），
 *    按 cutils/debug.h 的 enum 顺序装进 LongArray 的 0/1/2 位。
 *    老的 `Debug.readDebugCount()` 早从公开 SDK 移除（javap 在 android-36 上查无此方法），
 *    所以锁冲突（DEBUG_LOCK_CONTENTION_COUNT）与阻塞式 GC（DEBUG_BLOCKING_GC_COUNT）
 *    这两项默认**取不到**：快照长度只有 3，[AllocationJitterMath.delta] 对越界下标一律记 0，
 *    表现是 `blockingGcCount` 恒为 0。这不是 bug 而是能力边界，此时告警线只剩字节速率；
 *    真要把阻塞 GC 归因做实，用 Perfetto/heapprofd 抓一次对照，或让平台侧提供扩展快照读取器
 *    注入 [counterReader]（见下）。
 *  - 计数器是 **int** 累计值：进程常年不关机时 alloc size 会计到溢出（>2GB 回绕成负），
 *    差值出现负数就被钳 0，表现为"某窗口字节速率为 0"。宁缺毋滥——所以这里不用
 *    `resetGlobalAllocSize()` 主动归零：重置是全进程动作，会把同进程其他取证工具的计数一起抹掉。
 *
 * **下标语义的可注入性必须保留**：debug.h 的 enum 历史上随版本追加过新项，不同 AOSP 分支
 * 下标不保证一致，车机 ROM 也可能只暴露前三项。默认映射：
 *   0 = DEBUG_ALLOCATIONS（对象分配次数）
 *   1 = DEBUG_FILLED_DATA_BYTES（分配字节数，"filled data size" 即对象数据体字节）
 *   2 = DEBUG_GC_COUNT（GC 次数）
 *   3 = DEBUG_LOCK_CONTENTION_COUNT（瘦客户端锁冲突次数，公开 API 无）
 *   4 = DEBUG_BLOCKING_GC_COUNT（阻塞式 GC 次数，真正抢主线程 CPU 的那类，公开 API 无）
 * 因此映射被设计成可注入的 [CounterIndex]：实车上先用 Perfetto/heapprofd 对照一次，
 * 若 ROM 改过 debug.h 或额外暴露了调试计数器，调用方传修正后的下标 + 自己的读取器即可，
 * 不用改探针代码。
 * 计数器都是**进程启动以来的累计值**，报告输出的是窗口内的差值。
 */
class AllocationJitterDetector(
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val dispatchers: AppDispatchers = ProductionAppDispatchers,
    private val windowMillis: Long = 1_000L,
    private val counterIndex: CounterIndex = CounterIndex.DEFAULT,
    /** 计数读取器抽象出来：真机用 [DebugAllocCounters.snapshot]，单测可注入假数组。 */
    private val counterReader: () -> LongArray = { DebugAllocCounters.snapshot() },
) : PerfMonitor {

    override val name: String = "jitter"

    private val _latest = MutableStateFlow<MonitorState>(MonitorState.Idle("探针未启动"))
    override val latest: StateFlow<MonitorState> = _latest

    private val _jitterReport = MutableStateFlow<AllocationJitterReport?>(null)
    val jitterReport: StateFlow<AllocationJitterReport?> = _jitterReport

    private var job: Job? = null
    private var lastSnapshot: LongArray? = null
    private var lastSnapshotAtMillis = 0L

    override fun start(scope: CoroutineScope) {
        if (job != null) return
        runCatching { Debug.startAllocCounting() }
        lastSnapshot = runCatching { counterReader() }.getOrNull()
        lastSnapshotAtMillis = clock.nowMillis()
        job = scope.launch(dispatchers.io) {
            while (isActive) {
                delay(windowMillis)
                readWindow()
            }
        }
        _latest.value = MonitorState.Running("分配计数中，窗口 ${windowMillis}ms")
    }

    override fun stop() {
        job?.cancel()
        job = null
        runCatching { Debug.stopAllocCounting() }
        lastSnapshot = null
        _latest.value = MonitorState.Idle("分配计数已停止")
    }

    private fun readWindow() {
        val current = runCatching { counterReader() }.getOrNull() ?: return
        val now = clock.nowMillis()
        val previous = lastSnapshot
        val window = (now - lastSnapshotAtMillis).coerceAtLeast(1L)
        lastSnapshot = current
        lastSnapshotAtMillis = now
        if (previous == null) return
        val report = AllocationJitterMath.report(window, previous, current, counterIndex)
        _jitterReport.value = report
        val contention = AllocationJitterMath.delta(previous, current, counterIndex.lockContention)
        // "0 次"和"取不到"必须分开写：公开 API 下锁冲突/阻塞 GC 没有计数器，
        // 若统一显示 0，诊断页会把"探针没这个能力"读成"这窗口没有阻塞 GC"，
        // 现场排障时这正是最容易把方向带偏的一步（该上 Perfetto 却继续查业务代码）。
        val blockingText = if (hasCounter(counterIndex.blockingGcCount, previous, current)) "${report.blockingGcCount} 次" else "不可得"
        val contentionText = if (hasCounter(counterIndex.lockContention, previous, current)) "+$contention" else "不可得"
        _latest.value = if (report.isJittering) {
            MonitorState.Breach(
                summary = "分配速率 ${report.bytesAllocatedPerSecond / 1024}KB/s，分配 ${report.allocations} 个对象",
                severity = Severity.WARNING,
                detail = "窗口 ${report.windowMillis}ms；GC ${report.gcCount} 次（阻塞 $blockingText）；锁冲突 $contentionText",
            )
        } else {
            MonitorState.Running(
                summary = "分配速率 ${report.bytesAllocatedPerSecond / 1024}KB/s",
                detail = "GC ${report.gcCount} 次（阻塞 $blockingText），窗口 ${report.windowMillis}ms",
            )
        }
    }

    /** 该下标在两份快照里是否都有槽位——越界即代表当前口径没有这一项计数。 */
    private fun hasCounter(index: Int, first: LongArray, second: LongArray): Boolean =
        index >= 0 && index < first.size && index < second.size
}

/**
 * 公开 API 能拿到的分配计数器快照。
 *
 * 刻意排成与 cutils/debug.h enum **前缀一致**的下标（0=分配次数、1=分配字节、2=GC 次数），
 * 这样 [CounterIndex.DEFAULT] 不用为"公开 API 版"再单独造一套映射；平台若额外暴露了
 * 锁冲突/阻塞 GC 计数，只需在这里往后追加槽位，消费端代码与单测的下标约定都不动。
 *
 * 每个读取都要包起来：这些是 native 方法，ROM 阉割实现时可能抛 UnsatisfiedLinkError，
 * 记 0 让它"看起来没分配"总好过把后台采集协程掀掉（抖动探针常驻，稳定性优先于精度）。
 */
object DebugAllocCounters {

    fun snapshot(): LongArray = longArrayOf(
        runCatching { Debug.getGlobalAllocCount().toLong() }.getOrDefault(0L),      // DEBUG_ALLOCATIONS
        runCatching { Debug.getGlobalAllocSize().toLong() }.getOrDefault(0L),       // DEBUG_FILLED_DATA_BYTES
        runCatching { Debug.getGlobalGcInvocationCount().toLong() }.getOrDefault(0L), // DEBUG_GC_COUNT
    )
}

/**
 * 计数器快照的下标映射。默认值按 cutils/debug.h 常见 enum 顺序（前三项由
 * [DebugAllocCounters.snapshot] 提供，后两项在公开 API 下会越界、按缺失记 0），
 * 但下标随 ROM 可能漂移——所以做成数据类由调用方注入，探针本身不写死。
 */
data class CounterIndex(
    val allocations: Int,
    val filledDataBytes: Int,
    val gcCount: Int,
    val lockContention: Int,
    val blockingGcCount: Int,
) {
    companion object {
        val DEFAULT = CounterIndex(
            allocations = 0,            // DEBUG_ALLOCATIONS
            filledDataBytes = 1,        // DEBUG_FILLED_DATA_BYTES
            gcCount = 2,                // DEBUG_GC_COUNT
            lockContention = 3,         // DEBUG_LOCK_CONTENTION_COUNT
            blockingGcCount = 4,        // DEBUG_BLOCKING_GC_COUNT
        )
    }
}

/**
 * 抖动报告的纯计算部分：计数器快照（前后两个 LongArray）-> AllocationJitterReport。
 * 与 framework 完全解耦，JVM 单测直接喂两个 LongArray 验证差值与判定线。
 */
object AllocationJitterMath {

    /**
     * 单下标差值：越界返回 0（对应"该 ROM 的快照没有这一项"——公开 API 版就只有前三项，
     * 下标 3/4 天然越界，这正是我们不做能力假装的方式），
     * 负值钳到 0——计数器理论上单调递增，出现负数只可能是调试计数器被重置（例如有人调了
     * `Debug.resetGlobalAllocSize()`/`resetAllCounts()`）或者 int 累计值回绕，
     * 重置/回绕瞬间不该报抖动。
     */
    fun delta(before: LongArray, after: LongArray, index: Int): Long {
        if (index < 0 || index >= before.size || index >= after.size) return 0L
        return (after[index] - before[index]).coerceAtLeast(0L)
    }

    fun report(
        windowMillis: Long,
        before: LongArray,
        after: LongArray,
        index: CounterIndex = CounterIndex.DEFAULT,
    ): AllocationJitterReport = AllocationJitterReport(
        windowMillis = windowMillis,
        allocations = delta(before, after, index.allocations),
        bytesAllocated = delta(before, after, index.filledDataBytes),
        gcCount = delta(before, after, index.gcCount).toInt(),
        blockingGcCount = delta(before, after, index.blockingGcCount).toInt(),
    )
}
