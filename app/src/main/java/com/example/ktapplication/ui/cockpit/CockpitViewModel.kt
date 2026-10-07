package com.example.ktapplication.ui.cockpit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ktapplication.ui.AppDependencies
import com.example.ktapplication.vehicle.audio.AudioUsage
import com.example.ktapplication.vehicle.can.CanBusConnectionState
import com.example.ktapplication.vehicle.can.CanSignalCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 座舱页 UI 状态：一次渲染需要的全部信息，不做二次查询。 */
data class CockpitUiState(
    val cards: List<SignalCardUi> = emptyList(),
    val busState: CanBusConnectionState = CanBusConnectionState.DISCONNECTED,
    val frameRateHz: Double = 0.0,
    val dropRates: Map<String, Double> = emptyMap(),
    val staleSignals: List<String> = emptyList(),
    val heldAudioUsage: AudioUsage? = null,
    val musicOverBluetooth: Boolean = false,
    val duckLevel: Float = 1f,
) {
    val isBusSilent: Boolean get() = busState != CanBusConnectionState.STREAMING

    /** 仪表侧最关键的三块：车速、SOC、续航。它们任一不可信时整页要标灰。 */
    val criticalSignalsUnavailable: Boolean
        get() = cards.filter { it.critical }.any { !it.usable }
}

data class SignalCardUi(
    val title: String,
    val value: String,
    val unit: String,
    val quality: String,
    val usable: Boolean,
    val critical: Boolean,
    val sourceLabel: String,
)

class CockpitViewModel(private val deps: AppDependencies) : ViewModel() {

    private val _uiState = MutableStateFlow(CockpitUiState())
    val uiState: StateFlow<CockpitUiState> = _uiState.asStateFlow()

    init {
        // 快照流已经是 20Hz 节流的（见 CanSignalHub），这里再叠一层 UI 相关字段。
        viewModelScope.launch {
            deps.canHub.snapshot.collect { snapshot ->
                _uiState.value = _uiState.value.copy(
                    cards = buildCards(snapshot.signals),
                    staleSignals = snapshot.unreliableSignals,
                )
            }
        }
        viewModelScope.launch {
            deps.canHub.connectionState.collect { state ->
                _uiState.value = _uiState.value.copy(busState = state)
            }
        }
        viewModelScope.launch {
            deps.canHub.frameRateHz.collect { hz ->
                _uiState.value = _uiState.value.copy(frameRateHz = hz)
            }
        }
        viewModelScope.launch {
            deps.canHub.busHealth.collect { health ->
                _uiState.value = _uiState.value.copy(
                    dropRates = health.entries.associate { (bus, value) -> bus.displayName to value.dropRatePercent },
                )
            }
        }
        viewModelScope.launch {
            deps.audioFocus.heldUsage.collect { usage ->
                _uiState.value = _uiState.value.copy(heldAudioUsage = usage)
            }
        }
        viewModelScope.launch {
            deps.audioFocus.duckLevel.collect { level ->
                _uiState.value = _uiState.value.copy(duckLevel = level)
            }
        }
        viewModelScope.launch {
            deps.bluetooth.musicOverBluetooth.collect { overBt ->
                _uiState.value = _uiState.value.copy(musicOverBluetooth = overBt)
            }
        }
    }

    /**
     * 多路状态收敛方式。
     *
     * 没用 `combine(a..f)` 有两个原因：它最多 5 路，第 6 路要嵌套；而且嵌套写法在
     * 车速 20Hz、总线状态偶发变化时会出现"新快照配旧连接状态"的中间帧，
     * 仪表上表现为指针抖动。逐路收集 + 单点收敛让每次只更新真正变化的字段，
     * Compose 的重组范围也因此更小。
     */

    /** 演示音频焦点仲裁：模拟一次导航播报，把音乐 duck 下去。 */
    fun simulateNavigationPrompt() {
        viewModelScope.launch {
            deps.audioFocus.requestFocus(AudioUsage.NAVIGATION)
            kotlinx.coroutines.delay(3_000)
            deps.audioFocus.abandonFocus()
        }
    }

    private fun buildCards(signals: Map<String, com.example.ktapplication.vehicle.can.DecodedSignal>): List<SignalCardUi> {
        val now = deps.clock.nowMillis()
        fun card(name: String, title: String, unit: String, critical: Boolean): SignalCardUi? {
            val definition = CanSignalCatalog.definition(name) ?: return null
            val signal = signals[definition.name] ?: return null
            val stale = signal.isStaleAt(now)
            return SignalCardUi(
                title = title,
                value = signal.displayValue,
                unit = unit,
                quality = if (stale) "STALE" else signal.quality.name,
                usable = signal.physicalValue != null && !stale && signal.quality.name == "VALID",
                critical = critical,
                sourceLabel = "CAN ${definition.bus.name}",
            )
        }
        return listOfNotNull(
            card(CanSignalCatalog.SPEED_KMH.name, "车速", "km/h", critical = true),
            card(CanSignalCatalog.PACK_SOC_PERCENT.name, "动力电池", "%", critical = true),
            card(CanSignalCatalog.EV_RANGE_KM.name, "续航", "km", critical = true),
            card(CanSignalCatalog.MOTOR_RPM.name, "电机转速", "rpm", critical = false),
            card(CanSignalCatalog.PACK_CURRENT_A.name, "电池电流", "A", critical = false),
            card(CanSignalCatalog.STEERING_ANGLE_DEG.name, "方向盘转角", "°", critical = false),
            card(CanSignalCatalog.LOW_BATTERY_POWER_C.name, "小电瓶", "V", critical = false),
            card(CanSignalCatalog.ODOMETER_KM.name, "总里程", "km", critical = false),
        )
    }
}
