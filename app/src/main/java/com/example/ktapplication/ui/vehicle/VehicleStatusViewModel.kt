package com.example.ktapplication.ui.vehicle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ktapplication.core.Outcome
import com.example.ktapplication.ui.AppDependencies
import com.example.ktapplication.vehicle.domain.model.DoorPosition
import com.example.ktapplication.vehicle.domain.model.DoorState
import com.example.ktapplication.vehicle.domain.model.VehicleStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    init {
        viewModelScope.launch {
            deps.statusRepository.status.collect { status ->
                _uiState.value = status?.let { render(it) } ?: VehicleStatusUiState(unavailable = true)
            }
        }
    }

    fun refresh() {
        if (_uiState.value.isRefreshing) return
        _uiState.value = _uiState.value.copy(isRefreshing = true, message = null)
        viewModelScope.launch {
            val outcome: Outcome<*> = deps.statusRepository.refreshFromCloud(force = true)
            _uiState.value = _uiState.value.copy(
                isRefreshing = false,
                message = when (outcome) {
                    is Outcome.Success -> "云端车况已同步"
                    is Outcome.Failure -> outcome.error.userMessage
                },
            )
        }
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
}
