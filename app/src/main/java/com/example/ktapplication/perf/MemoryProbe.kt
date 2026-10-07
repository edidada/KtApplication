package com.example.ktapplication.perf

import android.app.ActivityManager
import android.content.Context
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
 * 内存水位探针：Java 堆 / native 堆 / 进程 PSS 三路采样。
 *
 * 三个口径缺一不可，车机上典型的翻车姿势各占一路：
 *  - Java 堆（Runtime）：只反映 Dalvik/ART 分配，业务代码泄漏在这里看得见；
 *  - native（Debug.getNativeHeapAllocatedSize）：地图引擎、多媒体解码这类 C++ 组件的泄漏
 *    Java 堆完全无感，只有 native 在涨；
 *  - totalPss（`Debug.getMemoryInfo(Debug.MemoryInfo)`）：系统视角的真实占用，决定 lmkd 是否对你下手。
 *    注意这个 API 只有 out-param 形态——framework 里不存在 `getMemoryInfo(Context)` 这种重载，
 *    Context 只用来在 Debug 通道被 ROM 限掉时兜底走 `ActivityManager.getProcessMemoryInfo(int[])`。
 *
 * GC 次数用 `Debug.getGlobalGcInvocationCount()`（公开静态方法，minSdk 24 直接可用；
 * `Debug.gcCount()` 是不可引用的内部命名，编译期解析不到）。它是进程启动以来的累计值，
 * 报告里输出的是与上一次采样的差值。
 *
 * [MemoryReport.heapGrowthBytesPerMinute] 的算法说明（为什么不用"最新样本 - 最旧样本"两点相减）：
 * 车机上 GC 策略和大堆回收会让 Java 堆在回收瞬间整体回落几百 KB~几 MB，两点法算出来经常是**负数**，
 * 掩盖真实的上涨趋势；这里对最近 [historySize] 个样本做最小二乘拟合取斜率，
 * 单点回落被样本池稀释，趋势仍然稳定。代价是对"突发大泄漏"反应慢半拍——
 * 突发泄漏由 AnrWatcher 的 memoryPressure 位和 isUnderPressure 兜底。
 */
class MemoryProbe(
    context: Context,
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val dispatchers: AppDispatchers = ProductionAppDispatchers,
    private val sampleIntervalMillis: Long = 2_000L,
    /** 线性外推用的滑动样本数；太小会被 GC 毛刺带偏，太大对泄漏反应迟钝。 */
    private val historySize: Int = 8,
) : PerfMonitor {

    override val name: String = "memory"

    private val appContext = context.applicationContext
    private val debugMemoryInfo = Debug.MemoryInfo()

    private val _latest = MutableStateFlow<MonitorState>(MonitorState.Idle("探针未启动"))
    override val latest: StateFlow<MonitorState> = _latest

    private val _memoryReport = MutableStateFlow<MemoryReport?>(null)
    val memoryReport: StateFlow<MemoryReport?> = _memoryReport

    private var job: Job? = null
    private val samples = ArrayDeque<MemorySample>()
    private var lastGcCount = -1

    override fun start(scope: CoroutineScope) {
        if (job != null) return
        lastGcCount = currentGcCount()
        samples.clear()
        job = scope.launch(dispatchers.io) {
            // binder 采样（getMemoryInfo 内部走 AMS）放 IO 池；车机上后台常开时
            // 这个协程每周期只有一次 binder 调用 + 几个 long 读取，开销常数级。
            while (isActive) {
                delay(sampleIntervalMillis)
                sample()
            }
        }
        _latest.value = MonitorState.Running("内存采样中，周期 ${sampleIntervalMillis}ms")
    }

    override fun stop() {
        job?.cancel()
        job = null
        _latest.value = MonitorState.Idle("内存采样已停止")
    }

    private fun sample() {
        val runtime = Runtime.getRuntime()
        val javaUsed = runtime.totalMemory() - runtime.freeMemory()
        val javaMax = runtime.maxMemory()
        val nativeAllocated = runCatching { Debug.getNativeHeapAllocatedSize() }.getOrDefault(0L)
        // 进程 PSS 走 Debug 的 out-param 形态：getMemoryInfo(MemoryInfo) 是唯一公开口径
        // （没有 Context 重载），内部向 AMS 要本进程 smaps 汇总，结果写进复用的 MemoryInfo。
        val pss = readProcessPss()
        val totalPssKb = pss?.totalKb
        val gcNow = currentGcCount()
        val gcDelta = if (lastGcCount < 0) 0 else (gcNow - lastGcCount).coerceAtLeast(0)
        lastGcCount = gcNow

        val now = clock.nowMillis()
        samples.addLast(MemorySample(nowMillis = now, javaHeapUsedBytes = javaUsed))
        while (samples.size > historySize) samples.removeFirst()

        val report = MemoryReport(
            capturedAtMillis = now,
            javaHeapUsedBytes = javaUsed,
            javaHeapMaxBytes = javaMax,
            nativeAllocatedBytes = nativeAllocated,
            totalPssKb = totalPssKb,
            gcCountSinceLastSample = gcDelta,
            heapGrowthBytesPerMinute = MemoryMath.growthBytesPerMinute(samples.toList()),
        )
        _memoryReport.value = report
        // 三路拆分直接进 detail：现场只看 totalPss 会误判——Java 堆稳定但 PSS 上涨 = native 泄漏，
        // 反过来 PSS 涨幅全在 dalvik 段就是业务对象没释放，整改责任方完全不同。
        val pssSplit = pss?.let { "java=${it.javaHeapKb}KB native=${it.nativeKb}KB" } ?: "PSS 未取到"
        _latest.value = if (report.isUnderPressure) {
            MonitorState.Breach(
                summary = "Java 堆水位 ${(report.usedRatio * 100).toInt()}%，超过 80% 收敛线",
                severity = Severity.WARNING,
                detail = "PSS=${totalPssKb ?: 0}KB（$pssSplit），native 分配=${nativeAllocated / 1024}KB",
            )
        } else {
            MonitorState.Running(
                summary = "Java 堆 ${(report.usedRatio * 100).toInt()}%，PSS ${totalPssKb ?: 0}KB",
                detail = "$pssSplit，窗口内 GC $gcDelta 次，堆外增速 ${report.heapGrowthBytesPerMinute / 1024}KB/min",
            )
        }
    }

    /**
     * 本进程 PSS 三口径。out-param 对象复用同一个 [debugMemoryInfo]：getMemoryInfo 每次都要
     * 走一次 binder（AMS 读 smaps），车机上 2s 一次的采样若还新建对象，等于自己制造分配抖动。
     * 读不到（权限受限/低内存时 AMS 超时）就返回 null，让上层显式显示"未取到"。
     */
    private fun readProcessPss(): ProcessPssKb? = readPssFromDebug() ?: readPssFromActivityManager()

    private fun readPssFromDebug(): ProcessPssKb? = runCatching {
        Debug.getMemoryInfo(debugMemoryInfo)
        ProcessPssKb(
            totalKb = debugMemoryInfo.totalPss.toLong(),
            javaHeapKb = debugMemoryInfo.dalvikPss.toLong(),
            nativeKb = debugMemoryInfo.nativePss.toLong(),
        )
    }.getOrNull()

    /**
     * 兜底口径：`ActivityManager.getProcessMemoryInfo(int[])` 拿回的是同一份 `Debug.MemoryInfo[]`，
     * 只是走常规服务 binder 而不是 Debug 的 native 通道。部分车机 ROM 会把 Debug 侧的 smaps 读取
     * 限掉（SELinux 收紧 /proc/[pid]/smaps_rollup；路径只写成 [pid] 占位——通配符形式会在 KDoc 里
     * 拼出块注释起始符，而 KDoc 的块注释是可以嵌套的，一旦开了嵌套就会一路吃到文件末尾），
     * 没有这一路就只能永远显示"PSS 未取到"，
     * 而 PSS 恰恰是判断 lmkd 会不会动我们这个常驻进程的唯一依据。
     */
    private fun readPssFromActivityManager(): ProcessPssKb? = runCatching {
        val am = appContext.getSystemService(ActivityManager::class.java) ?: return@runCatching null
        val info = am.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid())).firstOrNull()
            ?: return@runCatching null
        ProcessPssKb(
            totalKb = info.totalPss.toLong(),
            javaHeapKb = info.dalvikPss.toLong(),
            nativeKb = info.nativePss.toLong(),
        )
    }.getOrNull()

    /**
     * 进程启动以来的累计 GC 次数：`Debug.getGlobalGcInvocationCount()`。
     * 公开 API 里没有 `Debug.gcCount()` 这个东西（那是 framework-internal 的 Debug 方法命名习惯），
     * 拿不到时返回 0，差值逻辑会把它当"起点"处理，不会输出负数 GC。
     */
    private fun currentGcCount(): Int = runCatching { Debug.getGlobalGcInvocationCount() }.getOrDefault(0)
}

/** 采样点（单调时间 + Java 堆已用字节），纯数据供拟合。 */
data class MemorySample(val nowMillis: Long, val javaHeapUsedBytes: Long)

/** 一次 PSS 采样的三口径，单位 kB（framework 侧 PSS 一律以 kB 计）。 */
private data class ProcessPssKb(val totalKb: Long, val javaHeapKb: Long, val nativeKb: Long)

/**
 * 内存趋势的纯计算部分。与框架解耦，JVM 单测可以直接喂样本序列验证斜率。
 */
object MemoryMath {

    /**
     * 最小二乘斜率（字节/毫秒）换算成字节/分钟。
     *
     * 为什么不用两点相减：见 [MemoryProbe] 类头注释——GC 后堆瞬间回落会让两点法输出负数，
     * 把"持续上涨的泄漏"误报成"内存健康"。拟合至少要求 2 个样本，不足时返回 0（宁缺毋滥）。
     */
    fun growthBytesPerMinute(samples: List<MemorySample>): Long {
        if (samples.size < 2) return 0L
        val t0 = samples.first().nowMillis
        // 时间轴平移到相对值，避免单调时钟大基数造成乘法溢出（车机常年不关机，elapsedRealtime 可到百亿级）。
        val xs = samples.map { (it.nowMillis - t0).toDouble() }
        val ys = samples.map { it.javaHeapUsedBytes.toDouble() }
        val n = xs.size
        val meanX = xs.sum() / n
        val meanY = ys.sum() / n
        val denominator = xs.sumOf { (it - meanX) * (it - meanX) }
        if (denominator <= 0.0) return 0L // 所有样本同一时刻，无斜率可言
        val numerator = xs.indices.sumOf { (xs[it] - meanX) * (ys[it] - meanY) }
        val slopeBytesPerMillis = numerator / denominator
        return (slopeBytesPerMillis * 60_000.0).toLong()
    }
}

/** 供 UI 或离线取证把 PSS 口径统一到字节的便捷换算（PSS 报告口径是 kB）。 */
fun MemoryReport.totalPssBytes(): Long? = totalPssKb?.let { it * 1024L }

/**
 * ActivityManager 口径的设备整体内存压力（与进程内 PSS 互补：
 * 前者说明"系统要不要杀我"，后者说明"我要不要自己收敛"）。
 * 单独抽出是因为 AnrWatcher 和 MemoryProbe 都需要这个判断，避免两处口径漂移。
 */
object DeviceMemoryPressure {
    fun isUnderPressure(context: Context): Boolean = runCatching {
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.lowMemory || info.availMem < info.threshold
    }.getOrDefault(false)
}
