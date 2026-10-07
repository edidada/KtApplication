package com.example.ktapplication.vehicle.data

import com.example.ktapplication.core.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 车联网云端（TSP）接口契约。
 *
 * 这一层刻意只描述"云端语义"而不掺入车辆领域模型：车况字段是否可信、
 * CAN 值和云端值如何合并，都是 repository 层的事。分层之后 Mock 实现可以
 * 只关心时序，真机实现可以只关心协议。
 */

/** 链路状态。DEGRADED 表示能连上但 RTT 超预算，远程控车要据此收紧超时。 */
enum class TspConnectivity { ONLINE, DEGRADED, OFFLINE, NOT_PROVISIONED }

/**
 * 云端车况快照。
 *
 * 所有可空字段都必须容忍缺失：TSP 在不同车型、不同年款上返回的字段集合不一样，
 * 而且车辆下电后大部分字段停止更新（云端会返回最后一次缓存值 + 旧的时间戳）。
 */
data class TspStatusSnapshot(
    val vehicleId: String,
    /** 车辆侧上报时间（云端给），可能远早于 receivedAtMillis —— 判断"陈旧"要用它。 */
    val reportedAtMillis: Long,
    /** App 收到该快照的时间（单调时钟）。 */
    val receivedAtMillis: Long,
    val stateOfChargePercent: Double? = null,
    val remainingRangeKilometres: Double? = null,
    val odometerKilometres: Double? = null,
    val speedKilometresPerHour: Double? = null,
    val gearRaw: Int? = null,
    val lockRaw: Int? = null,
    /** key 为 FL/FR/RL/RR/TRUNK，value 为 0=关 1=开 2=未关严。 */
    val doorsRaw: Map<String, Int> = emptyMap(),
    val driverSeatbeltRaw: Int? = null,
    val airConditionerOn: Boolean? = null,
    val targetTemperatureCelsius: Double? = null,
    val fanLevel: Int? = null,
    val pluggedIn: Boolean? = null,
    val chargingPowerKilowatts: Double? = null,
    val chargingCurrentAmperes: Double? = null,
    val packVoltageVolts: Double? = null,
    val lowBatteryVolts: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null,
    /** 云端标注的数据来源，用于诊断页；常见值 VEHICLE / CACHE。 */
    val dataOrigin: String? = null,
)

data class TspCommandRequest(
    val commandId: String,
    val vehicleId: String,
    val actionCode: String,
    val parameters: Map<String, String>,
    val issuedAtMillis: Long,
    val idempotencyKey: String,
)

sealed class TspCommandAck {
    /** 云端受理，返回服务端消息号；后续结果靠它查询。 */
    data class Accepted(val serverMessageId: String) : TspCommandAck()

    /** 明确拒绝（参数非法/权限不足/车辆状态不允许）。retryable=false 时不应自动重试。 */
    data class Rejected(val code: Int, val reason: String, val retryable: Boolean) : TspCommandAck()

    /** 传输层失败（超时/连接重置），语义等价于"结果未知"。 */
    data class TransportFailure(val reason: String, val retryable: Boolean) : TspCommandAck()
}

sealed class TspCommandResult {
    data object Executed : TspCommandResult()
    data class Failed(val code: Int, val reason: String) : TspCommandResult()
    data class Pending(val estimatedRemainingMillis: Long?) : TspCommandResult()

    /** 车辆长时间未回报，或云端明确回 "UNKNOWN"：不能当成失败重试。 */
    data object Unknown : TspCommandResult()
}

sealed class TspPushEvent {
    data class StatusUpdated(val snapshot: TspStatusSnapshot) : TspPushEvent()
    data class CommandResultUpdated(val commandId: String, val result: TspCommandResult) : TspPushEvent()
    data class Alert(val code: Int, val message: String, val level: AlertLevel) : TspPushEvent()
    data class Heartbeat(val serverTimeMillis: Long) : TspPushEvent()

    /** token 过期：上层需要重新鉴权后重连，而不是直接放弃推送通道。 */
    data object SessionExpired : TspPushEvent()
}

enum class AlertLevel { INFO, WARNING, CRITICAL }

/**
 * 客户端能力要求：
 *  - [pushEvents] 必须是可重复收集的冷流，并且内部自带重连；
 *  - 所有 suspend 方法都必须**在给定超时内一定返回**，不能因为长连接卡死而悬挂，
 *    远程控车的用户体验取决于"最坏 15s 有结论"；
 *  - 失败必须区分"明确拒绝"和"结果未知"，见 [TspCommandAck]。
 */
interface TspClient {

    val connectivity: StateFlow<TspConnectivity>

    val pushEvents: Flow<TspPushEvent>

    suspend fun fetchStatus(vehicleId: String): Outcome<TspStatusSnapshot>

    suspend fun submitCommand(request: TspCommandRequest): Outcome<TspCommandAck>

    /** 结果未知时用它查证；返回 Unknown 表示云端也没有结论。 */
    suspend fun queryCommandResult(commandId: String): Outcome<TspCommandResult>

    suspend fun probeConnectivity(): TspConnectivity

    fun close()
}

/**
 * 离线指令队列的持久化契约。
 *
 * 车机 App 被系统回收是常态（OTA 升级、低功耗模式），排队中的控车指令必须落盘，
 * 重启后继续冲刷；同时必须去重 —— 用户连点三次解锁，重启后不能真发三条。
 */
interface CommandJournal {

    /** 返回按入队顺序排列的待发送请求；重复 [TspCommandRequest.idempotencyKey] 只保留最早一条。 */
    suspend fun pending(): List<TspCommandRequest>

    suspend fun enqueue(request: TspCommandRequest): Boolean

    suspend fun complete(commandId: String)

    suspend fun discard(commandId: String, reason: String)

    suspend fun clear()

    suspend fun size(): Int
}
