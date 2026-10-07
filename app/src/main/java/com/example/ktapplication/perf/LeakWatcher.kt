package com.example.ktapplication.perf

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
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * 极简自研 LeakCanary（弱引用 + ReferenceQueue 判泄漏）。
 *
 * 用法：页面/ViewModel/大型 holder 销毁时调用 `watch("ScreenSaverActivity", activity)`，
 * 探针持弱引用观察；到期仍未被回收即出 [LeakReport]（只有"嫌疑"级结论，没有堆引用链——
 * 引用链分析需要 hprof dump+解析，那是几百 ms 的 stop-the-world + 大内存操作，
 * 车机后台常驻探针绝对不能做，留给出证流程线下跑）。
 *
 * **为什么真机（尤其车机）不能把 System.gc() 当回事**：
 *  - ART 里 `Runtime.gc()`/`System.gc()` 只是"建议"，是否执行由 GC 策略决定；
 *  - 车机 ROM 常改 `dalvik.vm.*` 属性（堆大小、GC 类型、后台 GC 限流），不少座舱系统
 *    默认忽略显式 GC 以保证仪表进程的帧稳定性；
 *  - app 也没有权限像 LeakCanary 的 `Debug.releaseWeakGlobals()` 那样强制回收（那是隐藏 API）。
 * 所以这里把 System.gc() 只当"尽力而为的加测"：调用了、等了一个窗口，
 * 若引用仍未入队，结论措辞保持"嫌疑"（[LeakReport.suspicionReason]），
 * 而不是"确认泄漏"。宁可漏报，不可在仪表进程里制造一次全量 GC 抖动还给出错误结论。
 *
 * 线程模型：观察循环是挂在传入 scope 上的协程（stop 即取消，无 Thread 泄漏）；
 * watch/purge 都是并发安全容器操作，可以在任意线程调用（Activity onDestroy 直接调）。
 */
class LeakWatcher(
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val dispatchers: AppDispatchers = ProductionAppDispatchers,
    /** 存活超过这个时长才值得 GC 加测：给正常的销毁/动画收尾留出时间。 */
    private val retainThresholdMillis: Long = 5_000L,
    /** 触发 System.gc() 后等待引用的时间：太短的话 ART 并发标记还没跑完。 */
    private val gcSettleDelayMillis: Long = 2_000L,
    private val sweepIntervalMillis: Long = 1_000L,
) : PerfMonitor {

    override val name: String = "leak"

    private val _latest = MutableStateFlow<MonitorState>(MonitorState.Idle("探针未启动"))
    override val latest: StateFlow<MonitorState> = _latest

    private val _leakReports = MutableStateFlow<List<LeakReport>>(emptyList())
    val leakReports: StateFlow<List<LeakReport>> = _leakReports

    private val queue = ReferenceQueue<Any>()
    private val watched = ConcurrentHashMap<String, WatchEntry>()
    private var job: Job? = null

    /** 观察一个对象。tag 建议用"场景-类名"（如 "NavActivity"），同一 tag 重复 watch 后者覆盖前者。 */
    fun watch(tag: String, `object`: Any) {
        val reference = WeakReference(`object`, queue)
        watched[tag] = WatchEntry(
            tag = tag,
            className = `object`.javaClass.name,
            firstSeenAtMillis = clock.nowMillis(),
            reference = reference,
            gcTriggeredAtMillis = null,
            reportedAtMillis = null,
        )
    }

    fun unwatch(tag: String) {
        watched.remove(tag)
    }

    /** 把已进 ReferenceQueue（=已被 GC 回收）的 tag 移出观察名单。公开的另一个原因：单测可手动驱动。 */
    fun purgeReferences() {
        while (true) {
            val ref = queue.poll() ?: break
            // 用 identity 匹配而不是 hash：WeakReference 被回收后 get() 恒为 null，
            // 但 ConcurrentHashMap 的 key 是 tag，直接按 ref 在 entry 集合里找。
            val iterator = watched.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next().value
                if (entry.reference === ref) {
                    iterator.remove()
                    break
                }
            }
        }
    }

    override fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch(dispatchers.default) {
            while (isActive) {
                delay(sweepIntervalMillis)
                sweep()
            }
        }
        _latest.value = MonitorState.Running("观察 ${watched.size} 个引用")
    }

    override fun stop() {
        job?.cancel()
        job = null
        _latest.value = MonitorState.Idle("引用观察已停止")
    }

    /**
     * 单轮巡检（同步语义，便于测试直接驱动）：
     *  1. purge：回收掉的直接移出名单；
     *  2. 超阈值且还没 GC 加测过的：记下加测时刻，触发 System.gc()（尽力而为，原因见类头）；
     *  3. GC 加测已超过 [gcSettleDelayMillis] 仍存活的：按 [LeakThresholdPolicy] 出报告。
     *     每轮泄漏只报一次（reportedAtMillis 去重），持续观察靠 latest 的状态。
     */
    fun sweep() {
        purgeReferences()
        val now = clock.nowMillis()
        val candidates = watched.values.toList()
        var gcRequested = false
        for (entry in candidates) {
            val age = now - entry.firstSeenAtMillis
            if (age < retainThresholdMillis) continue
            if (entry.gcTriggeredAtMillis == null) {
                watched[entry.tag] = entry.copy(gcTriggeredAtMillis = now)
                gcRequested = true
                continue
            }
            val settled = now - entry.gcTriggeredAtMillis >= gcSettleDelayMillis
            val leaked = LeakThresholdPolicy.isLeaked(
                ageMillis = age,
                gcTriggered = true, // 走到这里说明已过了 GC 加测窗口
                thresholdMillis = retainThresholdMillis,
                gcSettled = settled,
            )
            if (leaked && entry.reportedAtMillis == null) {
                watched[entry.tag] = entry.copy(reportedAtMillis = now)
                emitReport(entry, age)
            }
        }
        if (gcRequested) {
            // 见类头注释：这是"建议"不是"命令"，车机 ROM 可能直接忽略，所以我们还要等 settle 窗口。
            runCatching { System.gc() }
        }
        val reports = _leakReports.value
        _latest.value = if (reports.isNotEmpty()) {
            MonitorState.Breach(
                summary = "${reports.size} 个引用疑似泄漏",
                severity = Severity.CRITICAL,
                detail = reports.joinToString("; ") { "${it.tag}（${it.retainedMinutes.fmt()}min）" },
            )
        } else {
            MonitorState.Running("观察 ${watched.size} 个引用")
        }
    }

    private fun emitReport(entry: WatchEntry, ageMillis: Long) {
        val report = LeakReport(
            tag = entry.tag,
            className = entry.className,
            retainedSinceMillis = ageMillis,
            firstSeenAtMillis = entry.firstSeenAtMillis,
            suspicionReason = LeakThresholdPolicy.suspicionReason(entry.tag, ageMillis),
        )
        _leakReports.value = _leakReports.value.filterNot { it.tag == report.tag } + report
    }

    /** 当前仍在观察的 tag 集合（已回收的会随 purge 消失），便于 UI/测试断言。 */
    fun watchedTags(): Set<String> = watched.keys.toSet()

    /**
     * 数字格式化固定用 Locale.ROOT：诊断页要显示 "12.5"，而某些 locale（如阿拉伯语区）
     * 默认格式化会把小数点换成别的符号，跨端比对与单测断言都会对不上。
     */
    private fun Double.fmt(): String = String.format(java.util.Locale.ROOT, "%.1f", this)
}

/** 内部观察条目；copy 语义保证状态机（未 GC -> 已加测 -> 已报告）不可变可测。 */
data class WatchEntry(
    val tag: String,
    val className: String,
    val firstSeenAtMillis: Long,
    val reference: WeakReference<Any>,
    val gcTriggeredAtMillis: Long?,
    val reportedAtMillis: Long?,
)

/**
 * "是否判为泄漏"的纯策略。抽出来的原因：GC 时机在不同 ROM 上不可控，
 * 判定规则反而是最稳的可单测部分——阈值、加测窗口、去重都必须回归验证，
 * 否则仪表常驻进程会被误报刷屏，真出问题时的报警就没人看了。
 */
object LeakThresholdPolicy {

    /**
     * 判定三条件缺一不可：
     *  - gcTriggered：没做过 GC 加测，"没回收"没有任何信息量；
     *  - gcSettled：ART 并发 GC 标记需要时间，加测后立刻检查会把正常回收误判成泄漏；
     *  - age >= threshold：销毁路径（动画收尾、协程取消）本身可能吃掉数秒。
     */
    fun isLeaked(
        ageMillis: Long,
        gcTriggered: Boolean,
        thresholdMillis: Long,
        gcSettled: Boolean,
    ): Boolean = gcTriggered && gcSettled && ageMillis >= thresholdMillis

    /** 报告里的定性措辞：明确"嫌疑"而非"确认"，并解释证据强度。 */
    fun suspicionReason(tag: String, ageMillis: Long): String =
        "弱引用在 GC 加测后 ${ageMillis}ms 仍未进入 ReferenceQueue；" +
            "车机 ROM 可能忽略显式 GC，此为嫌疑级结论（tag=$tag），引用链需线下 hprof 取证"
}
