package com.example.ktapplication.perf

import android.app.job.JobScheduler
import android.content.Context
import android.os.Build
import android.os.PowerManager
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
 * 后台保活自检。
 *
 * 结论先行：座舱 App 的"保活"靠的是系统白名单（persistent/priv-app），不是自己写的招数；
 * 真正会出事的是 Android 12+ 的 FGS 类型强约束和 OEM 自研省电策略。本探针输出的是
 * "当前保活手段还成立吗"，每条 riskNote 都是可以直接整改的动作项。
 *
 * 数据来源的权限现实（都写进注释，避免后人再踩）：
 *  - `ActivityManager.getRunningAppProcesses()`：Android 8 起**只能看到自己进程**，
 *    拿它看"全系统进程优先级"是幻觉。它的正确用途只有一个——读自身 importance。
 *    真机上验证保活要看 `/proc/<pid>/oom_score_adj`（读自己 pid 的该文件不需要 root，
 *    但本工具箱保持零 IO 依赖，留给取证脚本做）；
 *  - `foregroundServiceType` 列表：PackageManager.getServiceInfo 返回的 ServiceInfo
 *    上虽然有 foregroundServiceType 字段（API 29+），但它只在服务**运行期间**被系统回填，
 *    从 manifest 静态读不可靠。所以类型列表由调用方（知道自家 manifest 的人）传入，
 *    本类只负责基于它做合规判定；
 *  - `PowerManager.isIgnoringBatteryOptimizations()`：需要 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
 *    权限，座舱白名单应用经常"不需要也没申请"，直接调用可能抛 SecurityException——捕获并
 *    把"取不到"本身作为一条 riskNote 输出，而不是让探针崩掉；
 *  - `JobScheduler.getAllPendingJobs()`（API 24+）：只能看到**本 app**的 job，够用；
 *    系统没有 `pendingJobCounts` 这类聚合计数 API（那是 JobStats/Dropbox 时代的想象产物），
 *    计数由我们自己 size()，顺带把 id + 组件留在 [KeepAliveInspector.pendingJobs] 里供取证。
 *    注意本应用没有 pending job 是常态（没排过 JobScheduler 就是 0），所以"无兜底重排"
 *    这条 riskNote 才需要输出——它提示的是整改动作，不是异常。
 */
class KeepAliveInspector(
    context: Context,
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val dispatchers: AppDispatchers = ProductionAppDispatchers,
    /** 调用方按自家 manifest 声明的 FGS 类型传入，如 ["mediaPlayback", "dataSync"]。 */
    private val declaredForegroundServiceTypes: List<String> = emptyList(),
    private val sampleIntervalMillis: Long = 10_000L,
) : PerfMonitor {

    override val name: String = "keepAlive"

    private val appContext = context.applicationContext

    private val _latest = MutableStateFlow<MonitorState>(MonitorState.Idle("探针未启动"))
    override val latest: StateFlow<MonitorState> = _latest

    private val _keepAliveReport = MutableStateFlow<KeepAliveReport?>(null)
    val keepAliveReport: StateFlow<KeepAliveReport?> = _keepAliveReport

    private val _pendingJobs = MutableStateFlow<List<PendingJob>>(emptyList())

    /**
     * 待办 Job 明细。[KeepAliveReport] 是对外契约、只有一个 `scheduledJobs` 计数位，
     * 而实车排查要的是"哪个 job 没排上、它挂在哪个服务上"，所以明细单独走这个流给 UI/取证用。
     */
    val pendingJobs: StateFlow<List<PendingJob>> = _pendingJobs

    private var job: Job? = null

    override fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch(dispatchers.io) {
            while (isActive) {
                sample()
                delay(sampleIntervalMillis)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        _latest.value = MonitorState.Idle("保活自检已停止")
    }

    private fun sample() {
        val importance = readProcessImportance()
        val batteryIgnored = readBatteryOptimizationState()
        val pending = readPendingJobs()
        _pendingJobs.value = pending
        val report = KeepAliveMath.buildReport(
            sdkInt = SafeBuildSdk.sdkOf(Build.VERSION.SDK_INT),
            declaredForegroundServiceTypes = declaredForegroundServiceTypes,
            processImportance = importance,
            batteryOptimizationIgnored = batteryIgnored,
            scheduledJobs = pending.size,
        )
        _keepAliveReport.value = report
        _latest.value = if (report.riskNotes.isEmpty()) {
            MonitorState.Running(
                summary = "保活手段成立：${importance.description}",
                detail = "待办 Job ${describe(pending)}，采样于 ${clock.nowMillis()}ms（单调时钟），周期 ${sampleIntervalMillis}ms",
            )
        } else {
            MonitorState.Breach(
                summary = "保活存在 ${report.riskNotes.size} 项风险",
                severity = if (importance == ProcessImportance.CACHED) Severity.CRITICAL else Severity.WARNING,
                detail = report.riskNotes.joinToString(" | ") + "；待办 Job ${describe(pending)}",
            )
        }
    }

    /** id@组件(+persisted) 的紧凑写法：现场只需要知道"哪个 job 挂着、能不能自愈"。 */
    private fun describe(pendingJobs: List<PendingJob>): String =
        if (pendingJobs.isEmpty()) "无"
        else pendingJobs.joinToString(",") { if (it.persisted) "${it.id}@${it.service}*" else "${it.id}@${it.service}" }

    /**
     * getRunningAppProcesses 只能命中自己（见类头），命中不到时（系统更严格限制的场景）
     * 明确返回 UNKNOWN 而不是拿别的进程凑数。
     */
    private fun readProcessImportance(): ProcessImportance {
        val am = appContext.getSystemService(android.app.ActivityManager::class.java) ?: return ProcessImportance.UNKNOWN
        val mine = runCatching {
            am.runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }
        }.getOrNull()
        return KeepAliveMath.toProcessImportance(mine?.importance)
    }

    /** @return true/false = 是否在白名单；null = 无权限或取不到（区分"没豁免"和"不知道"）。 */
    private fun readBatteryOptimizationState(): Boolean? {
        val pm = appContext.getSystemService(PowerManager::class.java) ?: return null
        return runCatching {
            // API 23+；无 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限时抛 SecurityException。
            pm.isIgnoringBatteryOptimizations(appContext.packageName)
        }.getOrNull()
    }

    private fun readPendingJobs(): List<PendingJob> {
        val scheduler = appContext.getSystemService(JobScheduler::class.java) ?: return emptyList()
        return runCatching {
            // 只有 getAllPendingJobs() 这一个公开口径（API 24+，且只含本 uid 的 job）：
            // "pendingJobCounts" 这种按状态聚合的计数在 JobScheduler 里从来不存在，
            // 想区分 ready/deadline 只能自己从 JobInfo 上读约束和触发时间。
            scheduler.allPendingJobs.map { pending ->
                PendingJob(
                    id = pending.id,
                    service = pending.service?.flattenToShortString() ?: "-",
                    // persisted 的 job 能跨重启/跨进程被系统重新排上，是唯一的"杀后自愈"凭据，
                    // 排查看保活是否有效时先看这一位。
                    persisted = pending.isPersisted(),
                )
            }
        }.getOrDefault(emptyList())
    }
}

/**
 * 一条 pending job 的可读快照（`JobScheduler.getAllPendingJobs()` 的裁剪结果）。
 * 只留 id / 组件 / persisted：这三项足够判断"进程被杀后系统还会不会自己把活排回来"。
 */
data class PendingJob(val id: Int, val service: String, val persisted: Boolean)

/**
 * Build.VERSION.SDK_INT 在 JVM 单测（returnDefaultValues）下是 0，
 * 抽一层让 KeepAliveMath 的入参永远是可控 int，纯逻辑测试不依赖框架。
 */
internal object SafeBuildSdk {
    fun sdkOf(current: Int): Int = if (current > 0) current else 30
}

/**
 * 保活合规判定的纯函数核心：输入全是普通值，JVM 单测逐条覆盖 riskNote。
 */
object KeepAliveMath {

    // ActivityManager.RunningAppProcessInfo 的 importance 常量值（framework 字段，抄录于此
    // 以保持纯函数零 android 依赖；改动前先对照当前 AOSP 源码，这些值从未变过）：
    // IMPORTANCE_FOREGROUND = 125, IMPORTANCE_VISIBLE = 200, IMPORTANCE_SERVICE = 300,
    // IMPORTANCE_CACHED(=BACKGROUND) = 400, IMPORTANCE_GONE = 1000
    const val IMPORTANCE_FOREGROUND = 125
    const val IMPORTANCE_VISIBLE = 200
    const val IMPORTANCE_SERVICE = 300
    const val IMPORTANCE_CACHED = 400

    fun toProcessImportance(runningImportance: Int?): ProcessImportance = when (runningImportance) {
        null -> ProcessImportance.UNKNOWN
        IMPORTANCE_FOREGROUND -> ProcessImportance.FOREGROUND
        IMPORTANCE_VISIBLE -> ProcessImportance.VISIBLE
        IMPORTANCE_SERVICE -> ProcessImportance.SERVICE
        IMPORTANCE_CACHED -> ProcessImportance.CACHED
        // GONE(1000) 以及 OEM 自定义值都归入 UNKNOWN，不猜。
        else -> ProcessImportance.UNKNOWN
    }

    /**
     * 输出整改动作项。规则全部对应真实被杀场景：
     *  - Android 12（API 31）起 startForegroundService 未声明类型直接抛
     *    MissingForegroundServiceTypeException，车机升级 SoC 平台到新 Android 版本时第一批炸的就是它；
     *  - Android 14（API 34）起 dataSync 类型 FGS 有 24h 内累计 6h 限额，
     *    TSP 远程数据的常驻同步会被系统静默停掉，表现为"远程控车不响应"；
     *  - 低功耗模式下系统只放行"座舱必需"的 FGS 类型（mediaPlayback/navigation 等），
     *    类型缺失的 FGS 在 ACC OFF 后被停止；
     *  - 电池优化白名单取不到时不能假设安全——OEM 省电策略在车机上比 doze 更狠。
     */
    fun riskNotes(
        sdkInt: Int,
        declaredForegroundServiceTypes: List<String>,
        batteryOptimizationIgnored: Boolean?,
        scheduledJobs: Int,
        processImportance: ProcessImportance,
    ): List<String> {
        val notes = mutableListOf<String>()
        if (sdkInt >= 31 && declaredForegroundServiceTypes.isEmpty()) {
            notes += "Android 12+ 已强制 FGS 类型声明：未声明 foregroundServiceType 的 startForegroundService 会抛 MissingForegroundServiceTypeException，请立即补声明"
        }
        if (sdkInt >= 34 && declaredForegroundServiceTypes.contains("dataSync")) {
            notes += "Android 14+ dataSync 类型 FGS 受 24h/6h 限额，TSP 常驻同步需改为 JobScheduler/WorkManager 周期任务或申请 OEM 豁免"
        }
        if (declaredForegroundServiceTypes.isEmpty() ||
            declaredForegroundServiceTypes.none { it in COCKPIT_ALLOWED_FGS_TYPES }
        ) {
            notes += "车机低功耗模式（ACC OFF/待机）下，缺少 mediaPlayback/navigation 等座舱必需 FGS 类型的服务会被系统停止，请核对保活服务类型是否在座舱豁免集内"
        }
        when (batteryOptimizationIgnored) {
            null -> notes += "无 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限，电池优化白名单状态未知；车机 OEM 省电策略强于原生 doze，需请系统方确认白名单"
            false -> notes += "未加入电池优化白名单：非 persistent 部署下后台任务（CAN 监听/TSP 心跳）会被省电策略限流"
            true -> Unit
        }
        if (scheduledJobs == 0) {
            notes += "无 pending JobScheduler 任务：当前的后台周期工作完全依赖常驻服务，进程一旦被杀没有任何兜底重排"
        }
        if (processImportance == ProcessImportance.CACHED) {
            notes += "进程已落入 cached 档（oom_adj 会被拉高）：座舱 App 正常不应到这里，检查是否 FGS 已被系统回收；真机核对 /proc/<pid>/oom_score_adj"
        }
        return notes
    }

    fun buildReport(
        sdkInt: Int,
        declaredForegroundServiceTypes: List<String>,
        processImportance: ProcessImportance,
        batteryOptimizationIgnored: Boolean?,
        scheduledJobs: Int,
    ): KeepAliveReport = KeepAliveReport(
        processImportance = processImportance,
        foregroundServiceTypes = declaredForegroundServiceTypes,
        batteryOptimizationIgnored = batteryOptimizationIgnored ?: false,
        scheduledJobs = scheduledJobs,
        riskNotes = riskNotes(sdkInt, declaredForegroundServiceTypes, batteryOptimizationIgnored, scheduledJobs, processImportance),
    )

    /** 座舱场景常见的"待机可存活"FGS 类型集合（OEM 会有差异，这里给原生+常见车机交集）。 */
    private val COCKPIT_ALLOWED_FGS_TYPES = setOf(
        "mediaPlayback", "navigation", "location", "carApp", "connectedDevice",
    )
}
