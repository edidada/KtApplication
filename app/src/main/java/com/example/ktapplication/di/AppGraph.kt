package com.example.ktapplication.di

import android.annotation.SuppressLint
import android.app.Application
import com.example.ktapplication.CockpitApplication
import com.example.ktapplication.core.AppDispatchers
import com.example.ktapplication.core.AppScopeHolder
import com.example.ktapplication.core.IdGenerator
import com.example.ktapplication.core.MonotonicClock
import com.example.ktapplication.core.ProductionAppDispatchers
import com.example.ktapplication.core.SystemMonotonicClock
import com.example.ktapplication.core.TimestampedIdGenerator
import com.example.ktapplication.perf.PerfSnapshot
import com.example.ktapplication.perf.PerfToolkit
import com.example.ktapplication.ui.AppDependencies
import com.example.ktapplication.vehicle.audio.BluetoothAudioMonitor
import com.example.ktapplication.vehicle.audio.CarAudioFocusController
import com.example.ktapplication.vehicle.can.BusHealth
import com.example.ktapplication.vehicle.can.CanBus
import com.example.ktapplication.vehicle.can.CanSignalHub
import com.example.ktapplication.vehicle.can.MockCanBusClient
import com.example.ktapplication.vehicle.carservice.CarServiceGateway
import com.example.ktapplication.vehicle.carservice.MockCarServiceGateway
import com.example.ktapplication.vehicle.carservice.ReflectiveCarServiceGateway
import com.example.ktapplication.vehicle.data.CommandJournal
import com.example.ktapplication.vehicle.data.FileCommandJournal
import com.example.ktapplication.vehicle.data.MockTspClient
import com.example.ktapplication.vehicle.data.TspClient
import com.example.ktapplication.vehicle.data.asDemoVehicle
import com.example.ktapplication.vehicle.domain.repo.DefaultRemoteControlRepository
import com.example.ktapplication.vehicle.domain.repo.DefaultVehicleStatusRepository
import com.example.ktapplication.vehicle.domain.repo.RemoteControlRepository
import com.example.ktapplication.vehicle.domain.repo.VehicleStatusRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 手写依赖图（Service Locator）。
 *
 * 为什么不用 Hilt/Koin，也不用 ViewModelProvider.Factory：
 *  - 车机上的系统级 App 常被平台签名与 APT 限制卡住，能"纯手写装配"是最稳的能力证明；
 *  - 车载链路的对象**生命周期不一致**：CAN 枢纽、CarService 网关、车况仓库是进程级常驻
 *    （页面切换不能断总线订阅），而音频焦点申请是页面级的。用框架的 @Singleton 反而掩盖了这件事。
 *
 * 因此这里明确区分三段：
 *  1. **构造**（本类 init 顺序 = 依赖顺序，谁都不需要 lazy，除了反射网关的 binder）；
 *  2. **bootstrap**：真正把链路跑起来（连总线、绑 CarService、起仓库协程、起性能探针）；
 *  3. **release**：探针与协程全部收口，不留后台尾巴。
 *
 * 数据源的选择遵循"抽象接口 + Mock 实现"：本机没有 `android.car` 时落 [MockCarServiceGateway]，
 * 真机上自动切 [ReflectiveCarServiceGateway]，业务层代码一行不改。
 */
class AppGraph(private val application: Application) : AppDependencies, AppScopeHolder {

    override val clock: MonotonicClock = SystemMonotonicClock

    override val vehicleId: String = DEMO_VIN

    private val dispatchers: AppDispatchers = ProductionAppDispatchers

    /**
     * 进程级作用域。
     *
     * 用 [SupervisorJob] 而不是普通 Job：CAN 收帧协程解码到畸形帧、或云端轮询抛异常时，
     * 不能把 CarService 订阅和性能探针一起取消掉 —— 车机上"整条链路没了"比"一路降级"严重得多。
     */
    private val supervisorScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default)
    override val scope: CoroutineScope get() = supervisorScope

    // ---------- 座舱链路：CAN 总线 ----------

    private val canClient: MockCanBusClient = MockCanBusClient(clock = clock)

    override val canHub: CanSignalHub = CanSignalHub(
        client = canClient,
        clock = clock,
        dispatchers = dispatchers,
    )

    // ---------- 座舱链路：CarService（VHAL） ----------

    override val carGateway: CarServiceGateway = createCarServiceGateway()

    // ---------- 车联网链路：TSP 云端 ----------

    /**
     * 演示用车况由 Mock 按时间轴推进（充电、掉电、里程增长），
     * 换成 [com.example.ktapplication.vehicle.data.HttpTspClient] 即可对接真实 TSP：
     * 接口只有 5 个方法，仓库层与控车层不用改。
     */
    private val tspClient: TspClient = MockTspClient(
        clock = clock,
        responseDelayMillis = 120L,
    ).asDemoVehicle(vehicleId = vehicleId, clock = clock)

    /** 控车指令必须落盘：地库发出一条解锁，进程被杀也不能丢这条"结果未知"的记录。 */
    private val journal: CommandJournal = FileCommandJournal(
        directory = File(application.filesDir, JOURNAL_DIR),
        fileName = JOURNAL_FILE,
    )

    private val idGenerator: IdGenerator = TimestampedIdGenerator(clock)

    private val statusRepo: DefaultVehicleStatusRepository = DefaultVehicleStatusRepository(
        canHub = canHub,
        carGateway = carGateway,
        tspClient = tspClient,
        clock = clock,
        dispatchers = dispatchers,
        scope = supervisorScope,
        vehicleId = vehicleId,
    )

    override val statusRepository: VehicleStatusRepository = statusRepo

    override val remoteControl: RemoteControlRepository = DefaultRemoteControlRepository(
        tspClient = tspClient,
        journal = journal,
        statusRepository = statusRepo,
        clock = clock,
        dispatchers = dispatchers,
        scope = supervisorScope,
        idGenerator = idGenerator,
        vehicleId = vehicleId,
    )

    // ---------- 座舱链路：音频焦点 / 蓝牙 ----------

    override val audioFocus: CarAudioFocusController = CarAudioFocusController(
        context = application,
        clock = clock,
        dispatchers = dispatchers,
        scope = supervisorScope,
    )

    override val bluetooth: BluetoothAudioMonitor = BluetoothAudioMonitor(
        context = application,
        clock = clock,
        dispatchers = dispatchers,
    )

    // ---------- 稳定性工具箱 ----------

    private val perfToolkit: PerfToolkit = PerfToolkit(
        context = application,
        clock = clock,
        dispatchers = dispatchers,
        refreshRateHz = DEFAULT_REFRESH_RATE_HZ,
        // 本 App 没声明 FGS，自检会据此提示"后台常驻没有合法手段"，这正是它该报的结论。
        declaredForegroundServiceTypes = emptyList(),
    )

    override val perfSnapshot: StateFlow<PerfSnapshot> = perfToolkit.snapshot

    private var bootstrapped = false
    private var released = false

    /**
     * 把所有链路跑起来。[CockpitApplication.onCreate] 调用一次，重复调用被忽略。
     *
     * 每个阶段都进 [PerfToolkit.startupTracer]：车机现场"起不来 / 起得慢"的第一手证据
     * 就是阶段耗时分布，比事后抓 systrace 有用得多。
     */
    fun bootstrap() {
        if (bootstrapped) return
        bootstrapped = true

        perfToolkit.startupTracer.beginPhase(PHASE_CAN)
        canHub.start(supervisorScope)
        perfToolkit.startupTracer.endPhase(PHASE_CAN)

        perfToolkit.startupTracer.beginPhase(PHASE_CAR_SERVICE)
        // CarService 绑定是异步的（真机 300~1500ms），这里只发起连接，
        // 完成时刻从 carGateway.state 观察，不用假数据污染启动统计。
        supervisorScope.launch(dispatchers.io) { carGateway.connect() }
        perfToolkit.startupTracer.endPhase(PHASE_CAR_SERVICE)

        perfToolkit.startupTracer.beginPhase(PHASE_TSP)
        statusRepo.start()
        remoteControl.start()
        perfToolkit.startupTracer.endPhase(PHASE_TSP)

        perfToolkit.startupTracer.beginPhase(PHASE_PERF)
        bluetooth.start(supervisorScope)
        perfToolkit.start(supervisorScope)
        observeCanMetrics()
        perfToolkit.startupTracer.endPhase(PHASE_PERF)
        perfToolkit.startupTracer.tracePoint("bootstrapped")
    }

    override fun attachFrameMetrics(activity: android.app.Activity) {
        // 帧统计必须有 DecorView，只有 Activity 能提供；未 attach 时诊断页显示"无帧数据"而不是 0 帧。
        perfToolkit.frameMetrics.attach(activity)
    }

    override fun markFirstFrameRendered() {
        perfToolkit.startupTracer.tracePoint("firstFrame")
        perfToolkit.startupTracer.finish(firstFrameAtMillis = clock.nowMillis())
    }

    override fun release() {
        if (released) return
        released = true
        perfToolkit.stop()
        canHub.stop()
        bluetooth.stop()
        carGateway.disconnect()
        tspClient.close()
        supervisorScope.cancel("AppGraph released")
    }

    /**
     * CAN 指标喂给诊断页。
     *
     * [PerfSnapshot] 里的 CAN 字段由外部注入（探针不该反向依赖车辆链路），
     * 所以这里做唯一的一次桥接：帧率 + "哪条总线静默了"。
     */
    private fun observeCanMetrics() {
        supervisorScope.launch(dispatchers.default) {
            combine(canHub.frameRateHz, canHub.busHealth) { frameRate, health ->
                frameRate to silentBuses(health)
            }.collect { (frameRate, silent) ->
                perfToolkit.updateCanMetrics(frameRate, silent)
            }
        }
    }

    /**
     * 静默总线判定：超过 [BUS_SILENT_THRESHOLD_MILLIS] 没收到任何帧。
     *
     * 这是整车级问题定位的第一个分叉点 —— 一路 CAN 静默通常是网段掉线、
     * 域控制器休眠或网关没转发，而不是 App 的解码问题。
     */
    private fun silentBuses(health: Map<CanBus, BusHealth>): List<String> {
        val now = clock.nowMillis()
        return health.values
            .filter { it.lastFrameAtMillis == null || now - it.lastFrameAtMillis!! > BUS_SILENT_THRESHOLD_MILLIS }
            .map { it.bus.displayName }
    }

    /**
     * 有 `android.car` 就走反射实现，否则用 Mock。
     *
     * 判定用 Class.forName 而不是 Build.MANUFACTURER 之类的"看起来像车机"的猜测：
     * 只有 framework 里真的存在 Car 类，反射路径才可能成功。
     *
     * PrivateApi 豁免同 [ReflectiveCarServiceGateway]：`android.car` 本就是车机系统 API，
     * 手机上不存在这个类，探测失败就走 Mock，不会产生任何运行期风险。
     */
    @SuppressLint("PrivateApi")
    private fun createCarServiceGateway(): CarServiceGateway {
        val hasCarFramework = runCatching { Class.forName(CAR_FRAMEWORK_CLASS) }.isSuccess
        return if (hasCarFramework) {
            ReflectiveCarServiceGateway(
                context = application,
                clock = clock,
                dispatchers = dispatchers,
                scope = supervisorScope,
            )
        } else {
            MockCarServiceGateway(
                clock = clock,
                dispatchers = dispatchers,
                scope = supervisorScope,
                vehicleId = vehicleId,
            )
        }
    }

    private companion object {
        const val DEMO_VIN = "LVSHCDAAXFA000123"
        const val JOURNAL_DIR = "tsp"
        const val JOURNAL_FILE = "pending_commands.jsonl"
        const val CAR_FRAMEWORK_CLASS = "android.car.Car"
        const val DEFAULT_REFRESH_RATE_HZ = 60
        const val BUS_SILENT_THRESHOLD_MILLIS = 1_000L

        const val PHASE_CAN = "can_attach"
        const val PHASE_CAR_SERVICE = "carservice_bind"
        const val PHASE_TSP = "tsp_subscribe"
        const val PHASE_PERF = "perf_probe_start"
    }
}

/** Application 上暴露的稳定入口，避免各处写 `as CockpitApplication`。 */
val Application.appGraph: AppGraph
    get() = (this as? CockpitApplication)?.graph ?: error("必须在 CockpitApplication 中获取依赖图")
