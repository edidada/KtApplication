package com.example.ktapplication.ui.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ktapplication.core.DomainError
import com.example.ktapplication.core.Outcome
import com.example.ktapplication.core.RetryPolicy
import com.example.ktapplication.core.SingleFlight
import com.example.ktapplication.core.retryOn
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

    /**
     * 控车指令单飞。
     *
     * key 里带参数指纹：`UNLOCK_DOORS:` 这类无参动作，连点两次是**误触**，必须挡；
     * 而 `SET_TARGET_TEMPERATURE:celsius=22` 与 `=23` 是两次**不同的用户意图**，
     * 不能按"重复点击"丢弃 —— 丢了就是"我明明调到 23 度，屏幕上又弹回 22"。
     * 参数排序后再拼，保证 Map 遍历顺序不影响 key。
     */
    private val commandFlight = SingleFlight()

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
            val outcome: Outcome<CommandProgress> = commandFlight.runOnce(
                key = flightKey(action, parameters),
                // 重复点击不排队、不等待：立刻给一条"正在进行"的反馈。
                // 排队会让两条指令先后都执行一遍（那是用户没预期的），静默丢弃则让用户以为没点上。
                onBusy = {
                    Outcome.failure(
                        DomainError.Rejected(
                            DUPLICATE_TAP_CODE,
                            "上一条${action.displayName}尚未结束，请查看进度",
                        )
                    )
                },
            ) {
                // 只有"根本没送到"（连接不可用）会被 retryOn 重试；
                // ResultUnknown 明确不重试 —— 车辆可能已经执行过了，重发就是二次解锁。
                retryOn(RetryPolicy.remoteCommand) { deps.remoteControl.execute(action, parameters) }
            }
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
        // 单飞占用和"仓库里有没有未终态指令"是两个事实：前者从点击那一刻就成立，
        // 后者要等 Created 阶段写进 progress。只看后者，弱网下按钮会有一瞬可点。
        val busyPrefixes = commandFlight.busyKeys
        return RemoteAction.entries.map { action ->
            val violation = RemotePreconditionPolicy.evaluate(action, status, deps.clock.nowMillis())
            val inProgress = action in running || busyPrefixes.any { it.startsWith("${action.name}:") }
            ActionRow(
                action = action,
                enabled = violation == null && !inProgress,
                blockingReason = violation?.userMessage ?: "车辆状态不足以下发该指令".takeIf { status == null },
                inProgress = inProgress,
            )
        }
    }

    private fun flightKey(action: RemoteAction, parameters: Map<String, String>): String =
        action.name + ":" + parameters.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }

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

    private companion object {
        /**
         * 负数、且不与任何 TSP/车辆返回码重叠：这条"拒绝"是我们本地挡下来的，
         * 车辆根本没收到指令，所以不能复用 Rejected 的正常业务码段，
         * 否则线上统计会把本地防抖算成"车辆拒绝率"。
         */
        const val DUPLICATE_TAP_CODE = -409
    }
}
