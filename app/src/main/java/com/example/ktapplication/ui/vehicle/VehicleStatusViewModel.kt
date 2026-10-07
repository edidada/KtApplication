package com.example.ktapplication.ui.vehicle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ktapplication.core.Outcome
import com.example.ktapplication.core.RetryPolicy
import com.example.ktapplication.core.retryOn
import com.example.ktapplication.core.throttleFirst
import com.example.ktapplication.ui.AppDependencies
import com.example.ktapplication.vehicle.domain.model.DoorPosition
import com.example.ktapplication.vehicle.domain.model.DoorState
import com.example.ktapplication.vehicle.domain.model.VehicleStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

data class VehicleStatusUiState(
    val socText: String = "--",
    val rangeText: String = "--",
    val odometerText: String = "--",
    val speedText: String = "--",
    val gearText: String = "--",
    val lockText: String = "--",
    val seatbeltText: String = "--",
    val climateText: String = "--",
    val chargingText: String = "未充电",
    val lowBatteryText: String = "--",
    val doors: List<Pair<String, String>> = emptyList(),
    val locationSummary: String = "位置未知",
    val updatedAtText: String = "--",
    val perFieldSources: Map<String, String> = emptyMap(),
    val isRefreshing: Boolean = false,
    val message: String? = null,
    val unavailable: Boolean = true,
) {
    /** 任何字段陈旧时都要在页面上留痕：车况页的"看起来正常"比"报错"更危险。 */
    val warningFields: List<String> get() = perFieldSources.filterValues { it.contains("STALE") || it.contains("UNAVAILABLE") }.keys.toList()
}

class VehicleStatusViewModel(private val deps: AppDependencies) : ViewModel() {

    private val _uiState = MutableStateFlow(VehicleStatusUiState())
    val uiState: StateFlow<VehicleStatusUiState> = _uiState.asStateFlow()

    /**
     * 刷新意图通道。
     *
     * 用 Channel(CONFLATED) 而不是 SharedFlow：SharedFlow 在 replay=0 时只投递给**当时已订阅**的人，
     * 而 init 里的 collect 要等主线程队列排到才开始订阅，用户点得快就会"点了没反应"。
     * CONFLATED 通道会把最新一次意图留住，等消费者上线再取，且连点天然合并成一次。
     */
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)

    init {
        viewModelScope.launch {
            deps.statusRepository.status.collect { status ->
                _uiState.value = status?.let { render(it) } ?: VehicleStatusUiState(unavailable = true)
            }
        }
        viewModelScope.launch {
            // 通道 → 首部节流 → 串行执行。串行是白送的：collect 一次只处理一个元素，
            // 所以上面那个 isRefreshing 标志位不再兼职当锁（它只管显示），
            // 而"用布尔位当锁"本身就是竞态来源 —— 读写跨协程、还漏了异常路径的复位。
            refreshRequests.receiveAsFlow()
                .throttleFirst(REFRESH_THROTTLE_MILLIS, deps.clock)
                .collect { performRefresh() }
        }
    }

    fun refresh() {
        refreshRequests.trySend(Unit)
    }

    private suspend fun performRefresh() {
        _uiState.value = _uiState.value.copy(isRefreshing = true, message = null)
        val outcome = retryOn(
            policy = RetryPolicy.statusSync,
            onRetry = { attempt, _ ->
                // 重试要让用户看见：车机上"转圈但没有任何文字"会被理解成卡死，
                // 于是用户去按电源/切页面，反而把请求取消了。
                _uiState.value = _uiState.value.copy(
                    message = "链路不稳，正在重试（第 ${attempt + 1} 次）",
                )
            },
        ) { deps.statusRepository.refreshFromCloud(force = true) }
        _uiState.value = _uiState.value.copy(
            isRefreshing = false,
            message = when (outcome) {
                is Outcome.Success -> "云端车况已同步"
                is Outcome.Failure -> outcome.error.userMessage
            },
        )
    }

    private fun render(status: VehicleStatus): VehicleStatusUiState {
        val sources = buildMap {
            put("车速", "${status.speedKilometresPerHour.source}·${status.speedKilometresPerHour.freshness}")
            put("SOC", "${status.stateOfChargePercent.source}·${status.stateOfChargePercent.freshness}")
            put("续航", "${status.remainingRangeKilometres.source}·${status.remainingRangeKilometres.freshness}")
            put("档位", "${status.gear.source}·${status.gear.freshness}")
        }
        return VehicleStatusUiState(
            socText = text(status.stateOfChargePercent.value) { "%.0f".format(it) },
            rangeText = text(status.remainingRangeKilometres.value) { "%.0f".format(it) },
            odometerText = text(status.odometerKilometres.value) { "%.1f".format(it) },
            speedText = text(status.speedKilometresPerHour.value) { "%.1f".format(it) },
            gearText = status.gear.value?.displayName ?: "--",
            lockText = status.lockState.value?.displayName ?: "--",
            seatbeltText = status.driverSeatbelt.value?.displayName ?: "--",
            climateText = text(status.climate.driverTargetCelsius.value) { "%.1f℃".format(it) },
            chargingText = if (status.charging.isCharging) {
                "充电中 ${text(status.charging.powerKilowatts.value) { "%.1f".format(it) }} kW"
            } else {
                if (status.charging.pluggedIn.value == true) "已插枪未充电" else "未充电"
            },
            lowBatteryText = text(status.lowBatteryVolts.value) { "%.1f V".format(it) },
            doors = DoorPosition.entries.map { position ->
                position.displayName to (status.doors[position]?.value ?: DoorState.UNKNOWN).displayName
            },
            locationSummary = status.location?.let {
                if (!it.isUsable) "位置精度不足"
                else "%.5f, %.5f（±%.0fm，${if (it.fromVehicle) "整车 TBOX" else "座舱定位"}）".format(
                    it.latitude.value ?: 0.0,
                    it.longitude.value ?: 0.0,
                    it.accuracyMeters,
                )
            } ?: "位置未知",
            updatedAtText = "数据时间 ${status.updatedAtMillis} ms（单调时钟）",
            perFieldSources = sources,
            unavailable = false,
        )
    }

    private fun text(value: Double?, formatter: (Double) -> String): String = value?.let(formatter) ?: "--"

    private companion object {
        /**
         * 手动刷新的最小间隔。取值依据：TSP 车况接口按 VIN 限流，车机侧 4G 一次拉取
         * 实测 300~900ms，1.5s 既能挡住连点，又不至于让用户以为按钮坏了。
         */
        const val REFRESH_THROTTLE_MILLIS = 1_500L
    }
}
