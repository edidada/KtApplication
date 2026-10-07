package com.example.ktapplication.ui

import com.example.ktapplication.core.MonotonicClock
import com.example.ktapplication.perf.PerfSnapshot
import com.example.ktapplication.vehicle.audio.BluetoothAudioMonitor
import com.example.ktapplication.vehicle.audio.CarAudioFocusController
import com.example.ktapplication.vehicle.carservice.CarServiceGateway
import com.example.ktapplication.vehicle.can.CanSignalHub
import com.example.ktapplication.vehicle.domain.repo.RemoteControlRepository
import com.example.ktapplication.vehicle.domain.repo.VehicleStatusRepository
import kotlinx.coroutines.flow.StateFlow

/**
 * UI 层的依赖入口。
 *
 * 项目里不引入 Hilt/Koin：车机上常见"系统 App 无法用 APT 生成 + 平台签名限制"的情况，
 * 而且示例本身想展示的是**手写依赖图**的能力（谁常驻、谁随页面销毁、谁必须懒加载）。
 * 所有依赖由 Application 建好，通过 CompositionLocal 下发，ViewModel 只拿到接口。
 */
interface AppDependencies {
    val canHub: CanSignalHub
    val carGateway: CarServiceGateway
    val statusRepository: VehicleStatusRepository
    val remoteControl: RemoteControlRepository
    val audioFocus: CarAudioFocusController
    val bluetooth: BluetoothAudioMonitor
    val clock: MonotonicClock

    /** 诊断页唯一数据源：由性能工具箱聚合。 */
    val perfSnapshot: StateFlow<PerfSnapshot>

    /** 供演示：把 CAN 帧率等实时链路指标也交给诊断页。 */
    val vehicleId: String

    /**
     * 挂载帧统计回调。
     *
     * 只有 Activity 有 DecorView，性能探针拿不到它就只能显示"无帧数据"，
     * 所以这个动作由界面发起，依赖图只负责把回调接到 [com.example.ktapplication.perf.PerfToolkit]。
     */
    fun attachFrameMetrics(activity: android.app.Activity)

    /** 首帧渲染完成：启动耗时在这里收口，车机按"上电到仪表出数"考核。 */
    fun markFirstFrameRendered()

    /** 进程级链路的显式收口（总线订阅、探针循环、作用域）。 */
    fun release()
}
