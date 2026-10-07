package com.example.ktapplication.ui.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ktapplication.core.Outcome
import com.example.ktapplication.ui.AppDependencies
import com.example.ktapplication.vehicle.domain.model.CommandProgress
import com.example.ktapplication.vehicle.domain.model.CommandStage
import com.example.ktapplication.vehicle.domain.model.RemoteAction
import com.example.ktapplication.vehicle.domain.model.RemotePreconditionPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RemoteControlUiState(
    val actions: List<ActionRow> = emptyList(),
    val recent: List<CommandProgress> = emptyList(),
    val offlineQueued: Int = 0,
    val toast: String? = null,
) {
    val blockedCount: Int get() = actions.count { !it.enabled }
}

data class ActionRow(
    val action: RemoteAction,
    val enabled: Boolean,
    val blockingReason: String?,
    val inProgress: Boolean,
)

class RemoteControlViewModel(private val deps: AppDependencies) : ViewModel() {

    private val _uiState = MutableStateFlow(RemoteControlUiState())
    val uiState: StateFlow<RemoteControlUiState> = _uiState.asStateFlow()

    private var lastCommand: MutableStateFlow<CommandProgress?> = MutableStateFlow(null)
    val lastCommandState: StateFlow<CommandProgress?> = lastCommand.asStateFlow()

    init {
        viewModelScope.launch {
            deps.remoteControl.progress.collect { progress ->
                _uiState.value = _uiState.value.copy(
                    recent = progress,
                    actions = buildActions(progress),
                )
                lastCommand.value = progress.firstOrNull()
            }
        }
        viewModelScope.launch {
            deps.remoteControl.offlineQueueSize.collect { size ->
                _uiState.value = _uiState.value.copy(offlineQueued = size)
            }
        }
        // 车况变化会影响按钮可用性（例如车速降到 0 才能解锁），所以要跟着状态流重算。
        viewModelScope.launch {
            deps.statusRepository.status.collect {
                _uiState.value = _uiState.value.copy(actions = buildActions(_uiState.value.recent))
            }
        }
    }

    fun execute(action: RemoteAction, parameters: Map<String, String> = emptyMap()) {
        viewModelScope.launch {
            val outcome = deps.remoteControl.execute(action, parameters)
            _uiState.value = _uiState.value.copy(
                toast = when (outcome) {
                    is Outcome.Success -> "${action.displayName}：${outcome.value.stage.userMessage}"
                    is Outcome.Failure -> "${action.displayName}：${outcome.error.userMessage}"
                },
            )
        }
    }

    fun setTemperature(celsius: Int) = execute(RemoteAction.SET_TARGET_TEMPERATURE, mapOf("celsius" to celsius.toString()))

    fun consumeToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }

    private fun buildActions(progress: List<CommandProgress>): List<ActionRow> {
        val status = deps.statusRepository.status.value
        val running = progress.filter { !it.stage.isTerminal }.map { it.command.action }.toSet()
        return RemoteAction.entries.map { action ->
            val violation = RemotePreconditionPolicy.evaluate(action, status, deps.clock.nowMillis())
            ActionRow(
                action = action,
                enabled = violation == null && action !in running,
                blockingReason = violation?.userMessage ?: "车辆状态不足以下发该指令".takeIf { status == null },
                inProgress = action in running,
            )
        }
    }

    /** 供 UI 显示阶段进度条。 */
    fun progressFraction(progress: CommandProgress): Float = when (progress.stage) {
        is CommandStage.Created -> 0.1f
        is CommandStage.QueuedOffline -> 0.2f
        is CommandStage.Sending -> 0.4f
        is CommandStage.Accepted -> 0.6f
        is CommandStage.AwaitingVehicleResult -> 0.8f
        is CommandStage.Executed -> 1f
        else -> 1f
    }
}
