package com.example.ktapplication.vehicle.domain.model

import com.example.ktapplication.core.DomainError

/**
 * 远程控车指令模型。
 *
 * 车联网 App 与普通 App 最大的差别是：**每条指令都有安全约束和不可逆风险**。
 * 所以把前置条件写在动作定义上，而不是散落在各个按钮的 onClick 里，
 * 这样 UI、仓库层、单测共用同一份规则，不会出现"页面禁用了但接口还能调"的漏洞。
 */
enum class RemoteAction(
    val displayName: String,
    /** TSP 协议里的动作码，真实项目由云端接口文档定义。 */
    val tspCode: String,
    /** 要求车辆处于 P 挡；行驶中解锁车门属于安全问题。 */
    val requiresParked: Boolean = false,
    /** 要求车速接近 0。 */
    val requiresStandstill: Boolean = false,
    /** 要求插枪，例如停止充电。 */
    val requiresCharging: Boolean = false,
    /** 要求 SOC 上限未达到。 */
    val requiresNotFull: Boolean = false,
    /** 指令端到端预算：超时后进入"结果未知"而不是"失败"。 */
    val timeoutMillis: Long = 15_000,
    /** 是否允许自动重试。带物理动作的指令（锁门）可重试，涉及安全的（解锁）不自动重试。 */
    val autoRetryAllowed: Boolean = true,
    /** 是否幂等：重复执行结果相同。闪灯是幂等的，启动充电不是（会重复下发预约）。 */
    val idempotent: Boolean = true,
) {
    LOCK_DOORS("上锁", "DOOR_LOCK", autoRetryAllowed = true),
    UNLOCK_DOORS(
        displayName = "解锁",
        tspCode = "DOOR_UNLOCK",
        requiresStandstill = true,
        autoRetryAllowed = false,
        idempotent = false,
    ),
    START_CLIMATE("开启空调", "AC_START", timeoutMillis = 25_000),
    STOP_CLIMATE("关闭空调", "AC_STOP"),
    SET_TARGET_TEMPERATURE("设定温度", "AC_TEMP", timeoutMillis = 20_000),
    VENTILATE_WINDOWS("车窗通风", "WINDOW_VENT", requiresStandstill = true),
    START_CHARGE(
        displayName = "开始充电",
        tspCode = "CHARGE_START",
        requiresParked = true,
        requiresCharging = false,
        requiresNotFull = true,
        autoRetryAllowed = false,
        idempotent = false,
        timeoutMillis = 30_000,
    ),
    STOP_CHARGE("停止充电", "CHARGE_STOP", requiresCharging = true, autoRetryAllowed = false, idempotent = false),
    UNLOCK_CHARGE_PORT("弹开充电口", "CHARGE_PORT_OPEN", requiresStandstill = true, autoRetryAllowed = false),
    FLASH_LIGHTS("闪灯寻车", "LIGHT_FLASH", timeoutMillis = 10_000),
    HONK_HORN("鸣笛寻车", "HORN", timeoutMillis = 10_000, requiresStandstill = true),
    REFRESH_STATUS("主动刷新车况", "STATUS_REFRESH", timeoutMillis = 12_000),
    ;

    /** 需要车辆处于 P 挡的动作在 UI 上应显示"请先驻车"而不是直接禁用按钮。 */
    val requiresIgnitionOff: Boolean get() = requiresParked
}

data class RemoteCommand(
    val id: String,
    val vehicleId: String,
    val action: RemoteAction,
    /** 动作参数，例如 SET_TARGET_TEMPERATURE -> {"celsius":"24"}。 */
    val parameters: Map<String, String> = emptyMap(),
    val issuedAtMillis: Long,
    /** 幂等键：同一次"用户意图"重试时保持不变，云端据此去重。 */
    val idempotencyKey: String,
    /** 已经尝试过的次数，用于退避计算。 */
    val attempt: Int = 0,
) {
    fun nextAttempt(): RemoteCommand = copy(attempt = attempt + 1)

    fun parameter(name: String): String? = parameters[name]

    companion object {
        /**
         * 幂等键的构造规则：**不含 attempt**。
         *
         * 只包含 (vehicleId, action, 参数) 的话，用户十分钟内两次"解锁"会被云端当成
         * 同一次请求而丢弃第二次；加入分钟级时间分桶后，既允许正常的重复操作，
         * 又能让同一分钟内的连点和自动重试落到同一个键上，由 TSP 去重。
         */
        fun idempotencyKey(
            vehicleId: String,
            action: RemoteAction,
            issuedAtMillis: Long,
            parameters: Map<String, String>,
        ): String {
            val bucket = issuedAtMillis / 60_000
            val paramDigest = parameters.entries.sortedBy { it.key }
                .joinToString("") { "${it.key}${it.value}" }
                .hashCode()
                .toString(16)
            return "$vehicleId-${action.tspCode}-$bucket-$paramDigest"
        }
    }
}

/**
 * 指令生命周期阶段。
 *
 * 关键区分是 [ResultUnknown]：请求已发出但 15s 内没有结论。此时**绝不能**告诉用户
 * "失败，请重试"，因为车门可能已经解锁了。正确做法是把状态停在"待确认"，
 * 由查询接口 + 车况变化（lockState 真的变了）来收敛结论。
 */
sealed class CommandStage {
    data object Created : CommandStage()

    /** 网络不可用，先进本地队列，等连接恢复再冲刷。 */
    data class QueuedOffline(val reason: String) : CommandStage()

    data class Sending(val attempt: Int) : CommandStage()

    /** 云端已受理，返回了服务端消息号。 */
    data class Accepted(val serverMessageId: String) : CommandStage()

    /** 车已收到，等待执行结果回报。 */
    data object AwaitingVehicleResult : CommandStage()

    data object Executed : CommandStage()
    data class Rejected(val code: Int, val reason: String) : CommandStage()

    /** 超时且结果未知。 */
    data class ResultUnknown(val elapsedMillis: Long) : CommandStage()

    data object Cancelled : CommandStage()

    /** 终态：不会再自发变化。 */
    val isTerminal: Boolean
        get() = this is Executed || this is Rejected || this is Cancelled || this is ResultUnknown

    val userMessage: String
        get() = when (this) {
            is Created -> "准备发送"
            is QueuedOffline -> "离线已排队，联网后自动发送"
            is Sending -> "发送中（第 ${attempt + 1} 次）"
            is Accepted -> "云端已受理"
            is AwaitingVehicleResult -> "车辆执行中"
            is Executed -> "已完成"
            is Rejected -> "车辆拒绝：$reason"
            is ResultUnknown -> "状态待确认，请查看车况"
            is Cancelled -> "已取消"
        }
}

data class CommandProgress(
    val command: RemoteCommand,
    val stage: CommandStage,
    val startedAtMillis: Long,
    val updatedAtMillis: Long,
    /** 剩余预算时间，UI 用它画倒计时；null 表示已进入终态。 */
    val remainingMillis: Long?,
) {
    val elapsedMillis: Long get() = updatedAtMillis - startedAtMillis

    fun errorOrNull(): DomainError? = when (val current = stage) {
        is CommandStage.Rejected -> DomainError.Rejected(current.code, current.reason)
        is CommandStage.ResultUnknown -> DomainError.ResultUnknown(command.action.displayName, current.elapsedMillis)
        is CommandStage.QueuedOffline -> DomainError.ConnectionUnavailable(current.reason)
        else -> null
    }
}

/**
 * 远程控车前置条件校验。
 *
 * 抽成独立对象是因为这条规则必须同时在三个地方生效：按钮置灰、仓库层拦截、单测断言。
 * 写在 ViewModel 里就会出现"直接调仓库层能绕过安全校验"的问题。
 */
object RemotePreconditionPolicy {

    fun evaluate(action: RemoteAction, status: VehicleStatus?, nowMillis: Long): DomainError? {
        // 车况完全不可用时（离线且本地无缓存），只有"刷新车况"这种只读动作允许发送。
        if (status == null) {
            return if (action == RemoteAction.REFRESH_STATUS) null
            else DomainError.ConnectionUnavailable("暂无车辆状态")
        }
        // 只读动作永远放行：车况已经过期时，用户唯一的自救手段就是再拉一次，
        // 把它一起拦掉等于"数据越旧越刷不动"的死锁 —— 这条在真车上出现过投诉。
        if (action == RemoteAction.REFRESH_STATUS) return null
        if (!status.signalHealth.canBusState.equals("STREAMING", ignoreCase = true) &&
            status.updatedAtMillis + STALE_VEHICLE_STATUS_MILLIS < nowMillis
        ) {
            return DomainError.SignalUnreliable("车辆状态", "已过期 ${nowMillis - status.updatedAtMillis}ms")
        }
        val speed = status.speedKilometresPerHour
        val gear = status.gear
        val soc = status.stateOfChargePercent
        return when {
            // 先判"能不能确定"，再判"确定成的状态是否允许"。
            // 拿不到车速时绝不能默认 0 km/h 放行解锁 —— 未知必须按不安全处理。
            action.requiresStandstill && !speed.isUsable ->
                DomainError.SignalUnreliable("车速", speed.freshness.name)

            action.requiresStandstill && speed.value!! > STANDSILL_TOLERANCE_KMH ->
                DomainError.Rejected(4001, "车辆仍在移动（${"%.1f".format(speed.value!!)} km/h）")

            action.requiresParked && !gear.isUsable ->
                DomainError.SignalUnreliable("档位", gear.freshness.name)

            action.requiresParked && gear.value != GearPosition.PARK ->
                DomainError.Rejected(4002, "请先挂入 P 挡")

            action.requiresCharging && !status.charging.isCharging ->
                DomainError.Rejected(4003, "当前未在充电")

            action.requiresNotFull && !soc.isUsable ->
                DomainError.SignalUnreliable("动力电池电量", soc.freshness.name)

            action.requiresNotFull && soc.orDefault(0.0) >= FULL_SOC_THRESHOLD ->
                DomainError.Rejected(4004, "电池已充满")

            action == RemoteAction.SET_TARGET_TEMPERATURE && !temperatureIsValid(status.climate.driverTargetCelsius.value) ->
                DomainError.Rejected(4005, "温度参数非法")

            else -> null
        }
    }

    private fun temperatureIsValid(celsius: Double?): Boolean {
        val value = celsius ?: return false
        return value >= 16.0 && value <= 32.0
    }

    private const val STANDSILL_TOLERANCE_KMH = 1.0
    private const val FULL_SOC_THRESHOLD = 99.0

    /** 超过这个时长还拿不到新鲜车况，就不允许下发有物理动作的指令。 */
    const val STALE_VEHICLE_STATUS_MILLIS = 30_000L
}
