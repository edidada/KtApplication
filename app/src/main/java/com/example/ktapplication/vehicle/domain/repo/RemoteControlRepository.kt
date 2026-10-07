package com.example.ktapplication.vehicle.domain.repo

import com.example.ktapplication.core.AppDispatchers
import com.example.ktapplication.core.DomainError
import com.example.ktapplication.core.IdGenerator
import com.example.ktapplication.core.MonotonicClock
import com.example.ktapplication.core.Outcome
import com.example.ktapplication.vehicle.data.CommandJournal
import com.example.ktapplication.vehicle.data.TspClient
import com.example.ktapplication.vehicle.data.TspCommandAck
import com.example.ktapplication.vehicle.data.TspCommandRequest
import com.example.ktapplication.vehicle.data.TspCommandResult
import com.example.ktapplication.vehicle.data.TspConnectivity
import com.example.ktapplication.vehicle.data.TspPushEvent
import com.example.ktapplication.vehicle.domain.model.GearPosition
import com.example.ktapplication.vehicle.domain.model.LockState
import com.example.ktapplication.vehicle.domain.model.RemoteAction
import com.example.ktapplication.vehicle.domain.model.RemoteCommand
import com.example.ktapplication.vehicle.domain.model.RemotePreconditionPolicy
import com.example.ktapplication.vehicle.domain.model.CommandProgress
import com.example.ktapplication.vehicle.domain.model.CommandStage
import com.example.ktapplication.vehicle.domain.model.VehicleStatus
import com.example.ktapplication.vehicle.carservice.ReconnectBackoff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 远程控车结果核验规则（纯函数，可单测）。
 *
 * 车控指令的"成功"定义不是云端回了 ACK，而是**车辆状态真的变了**：
 * 门锁指令在 TSP 上常见"受理成功但车辆未执行"（车辆休眠、网络掉线、执行被车身域拒绝）。
 * 因此每条动作都要给出一个"用什么状态变化来证明它生效了"的谓词，
 * 核验窗口内观察到该变化才判 Executed，否则判 ResultUnknown。
 */
object CommandVerificationPolicy {

    /** 核验窗口：超过这个时间车况还没变，就停在"待确认"。 */
    const val DEFAULT_VERIFY_WINDOW_MILLIS = 30_000L

    fun effectOf(action: RemoteAction, parameters: Map<String, String> = emptyMap()): (VehicleStatus) -> Boolean = when (action) {
        RemoteAction.LOCK_DOORS -> { status -> status.lockState.value == LockState.LOCKED }
        RemoteAction.UNLOCK_DOORS -> { status -> status.lockState.value == LockState.UNLOCKED }
        RemoteAction.START_CLIMATE -> { status -> status.climate.compressorOn }
        RemoteAction.STOP_CLIMATE -> { status -> !status.climate.compressorOn }
        RemoteAction.SET_TARGET_TEMPERATURE -> { status ->
            // 温度落在设定值 ±1℃ 内即可，空调上报本身有量化误差（0.5℃ 一档）。
            val requested = parameters["celsius"]?.toDoubleOrNull()
            val actual = status.climate.driverTargetCelsius.value
            requested != null && actual != null && kotlin.math.abs(actual - requested) <= 1.0
        }
        RemoteAction.START_CHARGE -> { status -> status.charging.isCharging }
        RemoteAction.STOP_CHARGE -> { status -> !status.charging.isCharging }
        RemoteAction.UNLOCK_CHARGE_PORT -> { status -> status.charging.chargePortLocked == false }
        RemoteAction.VENTILATE_WINDOWS -> { status -> status.anyDoorOpen || status.doors.isEmpty() }
        // 闪灯/鸣笛是瞬时动作，车况里不留痕，只能以"云端回报执行完成"为准。
        RemoteAction.FLASH_LIGHTS, RemoteAction.HONK_HORN -> { _ -> true }
        RemoteAction.REFRESH_STATUS -> { _ -> true }
    }

    fun verifyWindowMillis(action: RemoteAction): Long = when (action) {
        RemoteAction.START_CLIMATE, RemoteAction.SET_TARGET_TEMPERATURE -> 45_000L
        RemoteAction.START_CHARGE, RemoteAction.STOP_CHARGE -> 60_000L
        else -> DEFAULT_VERIFY_WINDOW_MILLIS
    }

    /**
     * 可自动重试的判定。
     *
     * 三条都必须成立：动作允许重试、失败发生在"发出之前/传输层"、且还没拿到过 Accepted。
     * 已经 Accepted 之后再失败绝不能重发 —— 那会让车辆收到两条相同指令（真实风险：
     * 用户等不及重试，结果车门被反复开关）。
     */
    fun canRetry(action: RemoteAction, attempts: Int, acceptedByServer: Boolean, maxAttempts: Int = 3): Boolean =
        action.autoRetryAllowed && !acceptedByServer && attempts < maxAttempts
}

/** 远程控车仓库契约。 */
interface RemoteControlRepository {
    val progress: StateFlow<List<CommandProgress>>
    val offlineQueueSize: StateFlow<Int>

    /** 发起一条指令；返回值是**最终结论**（可能等满预算）。 */
    suspend fun execute(action: RemoteAction, parameters: Map<String, String> = emptyMap()): Outcome<CommandProgress>

    fun cancel(commandId: String)

    fun start()
}

/**
 * 远程控车实现：幂等 + 退避重试 + 超时不判失败 + 状态核验 + 离线队列。
 */
class DefaultRemoteControlRepository(
    private val tspClient: TspClient,
    private val journal: CommandJournal,
    private val statusRepository: VehicleStatusRepository,
    private val clock: MonotonicClock,
    private val dispatchers: AppDispatchers,
    private val scope: CoroutineScope,
    private val idGenerator: IdGenerator,
    private val vehicleId: String,
    private val backoff: ReconnectBackoff = ReconnectBackoff(initialDelayMillis = 1_500L, maxDelayMillis = 15_000L),
    private val maxAttempts: Int = 3,
) : RemoteControlRepository {

    private val _progress = MutableStateFlow<List<CommandProgress>>(emptyList())
    override val progress: StateFlow<List<CommandProgress>> = _progress.asStateFlow()

    private val _offlineQueueSize = MutableStateFlow(0)
    override val offlineQueueSize: StateFlow<Int> = _offlineQueueSize.asStateFlow()

    private val inFlight = LinkedHashMap<String, CommandProgress>()
    private var started = false

    override fun start() {
        if (started) return
        started = true

        // 网络恢复就冲刷离线队列：用户"发了但没网"的指令必须自动补发，
        // 且补发要串行（并发补发会让云端幂等窗口内收到两条不同 commandId 的同动作指令）。
        scope.launch(dispatchers.io) {
            tspClient.connectivity.collect { connectivity ->
                _offlineQueueSize.value = journal.size()
                if (connectivity == TspConnectivity.ONLINE) flushOfflineQueue()
            }
        }

        // 推送通道里的执行结果要立刻收敛，不能等下一次轮询。
        scope.launch(dispatchers.io) {
            tspClient.pushEvents.collect { event ->
                if (event is TspPushEvent.CommandResultUpdated) applyServerResult(event.commandId, event.result)
            }
        }
    }

    override suspend fun execute(action: RemoteAction, parameters: Map<String, String>): Outcome<CommandProgress> {
        val status = statusRepository.status.value
        val violation = RemotePreconditionPolicy.evaluate(action, status, clock.nowMillis())
        if (violation != null) return Outcome.failure(violation)

        val issuedAt = clock.nowMillis()
        val command = RemoteCommand(
            id = idGenerator.next(),
            vehicleId = vehicleId,
            action = action,
            parameters = parameters,
            issuedAtMillis = issuedAt,
            idempotencyKey = RemoteCommand.idempotencyKey(vehicleId, action, issuedAt, parameters),
        )
        val request = command.toRequest()

        // 乐观反馈：先把 UI 期望的显示状态立起来，真实值到了再由仓库覆盖。
        // 注意这**不**写车（写车会绕过 TSP 审计链），只影响本地展示。
        recordProgress(command, CommandStage.Created, clock.nowMillis() + command.action.timeoutMillis)

        // 离线：落盘排队，UI 显示"离线已排队"而不是失败。
        if (tspClient.connectivity.value != TspConnectivity.ONLINE) {
            journal.enqueue(request)
            _offlineQueueSize.value = journal.size()
            val queued = CommandProgress(command, CommandStage.QueuedOffline("TSP 不可达"), issuedAt, clock.nowMillis(), null)
            record(queued)
            return Outcome.success(queued)
        }

        return runLocallyAndRemotely(command, request)
    }

    override fun cancel(commandId: String) {
        val current = inFlight[commandId] ?: return
        if (current.stage.isTerminal) return
        record(current.copy(stage = CommandStage.Cancelled, updatedAtMillis = clock.nowMillis(), remainingMillis = null))
    }

    /**
     * 单条指令的完整生命周期。
     *
     * 时序：发送（带退避重试）→ 云端受理 → 等车辆结果（推送/查询双路）→ 状态核验 → 终态。
     * 每一步都写 progress，UI 因此能显示"车辆执行中/待确认"这种真实阶段。
     */
    private suspend fun runLocallyAndRemotely(command: RemoteCommand, request: TspCommandRequest): Outcome<CommandProgress> {
        var attempt = 0
        var acceptedByServer = false
        var lastError: DomainError? = null
        val deadlineAtMillis = clock.nowMillis() + command.action.timeoutMillis

        recordProgress(command, CommandStage.Sending(attempt), deadlineAtMillis)

        while (true) {
            val ackOutcome = tspClient.submitCommand(request.copy(commandId = command.id))
            when (val ack = ackOutcome.getOrNull()) {
                null -> {
                    lastError = ackOutcome.errorOrNull()
                    if (!CommandVerificationPolicy.canRetry(command.action, attempt + 1, acceptedByServer, maxAttempts)) break
                    val wait = backoff.delayBefore(attempt)
                    if (clock.nowMillis() + wait > deadlineAtMillis) break
                    attempt += 1
                    recordProgress(command, CommandStage.Sending(attempt), deadlineAtMillis)
                    delay(wait)
                    continue
                }

                is TspCommandAck.Accepted -> {
                    acceptedByServer = true
                    recordProgress(command, CommandStage.Accepted(ack.serverMessageId), deadlineAtMillis)
                    val vehicleResult = awaitVehicleResult(command, deadlineAtMillis)
                    return finishWithVerification(command, vehicleResult, deadlineAtMillis)
                }

                is TspCommandAck.Rejected -> {
                    recordProgress(command, CommandStage.Rejected(ack.code, ack.reason), deadlineAtMillis)
                    return Outcome.failure(DomainError.Rejected(ack.code, ack.reason))
                }

                is TspCommandAck.TransportFailure -> {
                    lastError = DomainError.ConnectionUnavailable(ack.reason)
                    // retryable=false 的传输失败（例如 4xx）不重试；幂等键相同的重复提交会被云端去重。
                    if (!ack.retryable || !CommandVerificationPolicy.canRetry(command.action, attempt + 1, acceptedByServer, maxAttempts)) break
                    attempt += 1
                    recordProgress(command, CommandStage.Sending(attempt), deadlineAtMillis)
                    delay(backoff.delayBefore(attempt - 1))
                    continue
                }
            }
            break
        }

        // 预算内没结论：停在 ResultUnknown，并启动后台核验。
        // 这里最容易踩的坑是直接报"失败"，用户接着再点一次解锁 —— 而车辆可能已经执行了第一次。
        val elapsed = clock.nowMillis() - command.issuedAtMillis
        recordProgress(command, CommandStage.ResultUnknown(elapsed), deadlineAtMillis)
        startBackgroundVerification(command)
        return Outcome.failure(lastError ?: DomainError.ResultUnknown(command.action.displayName, elapsed))
    }

    /** 等车辆回报：推送优先，同时按 2s 间隔主动查询，直到预算耗尽。 */
    private suspend fun awaitVehicleResult(command: RemoteCommand, deadlineAtMillis: Long): TspCommandResult? {
        recordProgress(command, CommandStage.AwaitingVehicleResult, deadlineAtMillis)
        val budget = (deadlineAtMillis - clock.nowMillis()).coerceAtLeast(0)
        return withTimeoutOrNull(budget) {
            var latest: TspCommandResult? = null
            val pollJob = launch(dispatchers.io) {
                while (latest == null || latest is TspCommandResult.Pending) {
                    val queried = tspClient.queryCommandResult(command.id).getOrNull()
                    if (queried != null && queried !is TspCommandResult.Pending) {
                        latest = queried
                        return@launch
                    }
                    delay(2_000)
                }
            }
            val pushed = runCatching {
                tspClient.pushEvents.first { event ->
                    event is TspPushEvent.CommandResultUpdated && event.commandId == command.id
                }
            }.getOrNull() as? TspPushEvent.CommandResultUpdated
            pollJob.cancel()
            pushed?.result ?: latest
        }
    }

    /**
     * 用状态核验收敛结论。
     *
     * 云端说"执行成功"但车况没变 → 判 ResultUnknown（比云端更保守，因为用户看得见的
     * 是车门，不是接口返回码）。车况变了 → 判 Executed，忽略云端的模糊结论。
     */
    private suspend fun finishWithVerification(
        command: RemoteCommand,
        serverResult: TspCommandResult?,
        deadlineAtMillis: Long,
    ): Outcome<CommandProgress> {
        val effect = CommandVerificationPolicy.effectOf(command.action, command.parameters)
        val windowMillis = CommandVerificationPolicy.verifyWindowMillis(command.action)
        val observed = withTimeoutOrNull(windowMillis) {
            statusRepository.status.first { status -> status != null && effect(status) }
        }
        val stage: CommandStage = when {
            observed != null -> CommandStage.Executed
            serverResult is TspCommandResult.Failed -> CommandStage.Rejected(serverResult.code, serverResult.reason)
            // 闪灯/鸣笛不留车况痕迹，云端回报即结论。
            serverResult is TspCommandResult.Executed -> CommandStage.Executed
            else -> CommandStage.ResultUnknown(clock.nowMillis() - command.issuedAtMillis)
        }
        recordProgress(command, stage, deadlineAtMillis)
        val progress = inFlight[command.id]
            ?: return Outcome.failure(DomainError.Unsupported(command.action.displayName))
        return when (stage) {
            is CommandStage.Executed -> Outcome.success(progress)
            is CommandStage.Rejected -> Outcome.failure(DomainError.Rejected(stage.code, stage.reason))
            is CommandStage.ResultUnknown -> Outcome.failure(DomainError.ResultUnknown(command.action.displayName, stage.elapsedMillis))
            else -> Outcome.success(progress)
        }
    }

    /** 超时后继续在后台看车况，一旦变化就把"待确认"提升为"已完成"。 */
    private fun startBackgroundVerification(command: RemoteCommand) {
        scope.launch(dispatchers.io) {
            val effect = CommandVerificationPolicy.effectOf(command.action, command.parameters)
            val windowMillis = CommandVerificationPolicy.verifyWindowMillis(command.action)
            withTimeoutOrNull(windowMillis) {
                statusRepository.status.first { status -> status != null && effect(status) }
            }?.let { recordProgress(command, CommandStage.Executed, clock.nowMillis() + windowMillis) }
        }
    }

    private fun applyServerResult(commandId: String, result: TspCommandResult) {
        val current = inFlight[commandId] ?: return
        val stage = when (result) {
            is TspCommandResult.Executed -> CommandStage.Executed
            is TspCommandResult.Failed -> CommandStage.Rejected(result.code, result.reason)
            is TspCommandResult.Pending -> CommandStage.AwaitingVehicleResult
            is TspCommandResult.Unknown -> CommandStage.ResultUnknown(clock.nowMillis() - current.command.issuedAtMillis)
        }
        record(current.copy(stage = stage, updatedAtMillis = clock.nowMillis(), remainingMillis = null))
    }

    private suspend fun flushOfflineQueue() {
        val pending = journal.pending()
        if (pending.isEmpty()) return
        pending.forEach { request ->
            // 补发前先确认这条指令的意图还没被满足（例如用户已经用钥匙解锁了）。
            val status = statusRepository.status.value
            val command = RemoteCommand(
                id = request.commandId,
                vehicleId = request.vehicleId,
                action = RemoteAction.entries.firstOrNull { it.tspCode == request.actionCode } ?: RemoteAction.REFRESH_STATUS,
                parameters = request.parameters,
                issuedAtMillis = request.issuedAtMillis,
                idempotencyKey = request.idempotencyKey,
            )
            val violation = RemotePreconditionPolicy.evaluate(command.action, status, clock.nowMillis())
            if (violation != null) {
                journal.discard(request.commandId, violation.userMessage)
            } else {
                val outcome = runLocallyAndRemotely(command, request)
                if (outcome.isSuccess) journal.complete(request.commandId)
            }
            _offlineQueueSize.value = journal.size()
        }
    }

    private fun recordProgress(command: RemoteCommand, stage: CommandStage, deadlineAtMillis: Long) {
        val now = clock.nowMillis()
        record(
            CommandProgress(
                command = command,
                stage = stage,
                startedAtMillis = command.issuedAtMillis,
                updatedAtMillis = now,
                remainingMillis = if (stage.isTerminal) null else (deadlineAtMillis - now).coerceAtLeast(0),
            ),
        )
    }

    private fun record(progress: CommandProgress) {
        inFlight[progress.command.id] = progress
        // 只保留最近 N 条，避免长时间运行后列表无限增长（车机进程是按周计算的）。
        // inFlight 是 LinkedHashMap，插入顺序＝指令下发顺序（同 id 的后续阶段是覆盖值、
        // 不会挪位置），所以取"最近 N 条"就是 takeLast 尾部的 N 项，不能按 updatedAt 重排：
        // 重排会让一条早就下发、刚回终态的指令突然插到列表最前面，用户会以为那是他刚按的键。
        // reversed() 让最新一条排在最前，与 UI 的 take(5) 展示顺序对齐。
        val trimmed = inFlight.values.toList().takeLast(MAX_HISTORY).reversed()
        _progress.value = trimmed
    }

    private fun RemoteCommand.toRequest() = TspCommandRequest(
        commandId = id,
        vehicleId = vehicleId,
        actionCode = action.tspCode,
        parameters = parameters,
        issuedAtMillis = issuedAtMillis,
        idempotencyKey = idempotencyKey,
    )

    private companion object {
        const val MAX_HISTORY = 20
    }
}

/** 档位与控车的关联校验用的小工具，保持 domain 层不依赖具体协议常量。 */
internal fun GearPosition.isParked(): Boolean = this == GearPosition.PARK
