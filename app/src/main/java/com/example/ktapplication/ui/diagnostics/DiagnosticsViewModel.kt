package com.example.ktapplication.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ktapplication.perf.PerfSnapshot
import com.example.ktapplication.perf.PerfReportFormatter
import com.example.ktapplication.ui.AppDependencies
import com.example.ktapplication.vehicle.can.BusHealth
import com.example.ktapplication.vehicle.carservice.CarServiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DiagnosticsUiState(
    val perf: PerfSnapshot? = null,
    val reportText: String = "",
    val busRows: List<Pair<String, String>> = emptyList(),
    val carServiceLine: String = "未连接",
    val cloudLine: String = "未知",
    val canFrameRateHz: Double = 0.0,
)

/**
 * 诊断页。
 *
 * 车机 App 的稳定性排查窗口极短：现场一旦复现不了就没证据了。所以诊断页必须
 * **常开、可离线取证**：主线程阻塞、掉帧归因、内存抖动、泄漏嫌疑、启动阶段、
 * CAN 丢帧率、CarService 重连次数全部同屏展示，出问题时截图或 dump 文本就能定位。
 */
class DiagnosticsViewModel(private val deps: AppDependencies) : ViewModel() {

    private val _uiState = MutableStateFlow(DiagnosticsUiState())
    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            deps.perfSnapshot.collect { snapshot ->
                _uiState.value = _uiState.value.copy(
                    perf = snapshot,
                    reportText = PerfReportFormatter.format(snapshot),
                )
            }
        }
        viewModelScope.launch {
            deps.canHub.busHealth.collect { health ->
                _uiState.value = _uiState.value.copy(busRows = health.entries.map { (bus, value) -> bus.displayName to describe(value) })
            }
        }
        viewModelScope.launch {
            deps.canHub.frameRateHz.collect { hz -> _uiState.value = _uiState.value.copy(canFrameRateHz = hz) }
        }
        viewModelScope.launch {
            deps.carGateway.state.collect { state ->
                _uiState.value = _uiState.value.copy(carServiceLine = describeCarService(state))
            }
        }
        viewModelScope.launch {
            deps.statusRepository.health.collect { health ->
                _uiState.value = _uiState.value.copy(cloudLine = "云端 ${health.cloudState} · CAN ${health.canBusState} · 丢帧 ${health.busDropRatesPercent}")
            }
        }
    }

    private fun describe(health: BusHealth): String =
        "收 ${health.framesReceived} 帧 / 丢 ${health.framesDropped}（${"%.2f".format(health.dropRatePercent)}%）· 超时信号 ${health.signalsTimedOut} · " +
            if (health.lastFrameAtMillis == null) "从未收到帧" else "最近帧 @${health.lastFrameAtMillis}ms"

    private fun describeCarService(state: CarServiceState): String = when (state) {
        is CarServiceState.Ready -> "就绪：${state.supportedProperties.size} 个属性（${state.carType}）"
        is CarServiceState.Reconnecting -> "重连中：第 ${state.attempt} 次，${state.nextRetryInMillis}ms 后重试（${state.cause}）"
        else -> state.userMessage
    }
}
