package com.example.ktapplication.core

/**
 * 领域错误。
 *
 * 车载场景的失败原因比互联网 App 多得多：网络不通只是其中最不重要的一种。
 * 车控指令失败必须能区分"车辆没执行"和"我们不知道有没有执行"，
 * 后者（超时）在 UI 上绝不能直接显示失败，否则用户会重复按门锁键。
 */
sealed class DomainError {

    /** 与 CarService / TSP 的连接不可用，通常由上层触发重连而不是直接报错给用户。 */
    data class ConnectionUnavailable(val reason: String) : DomainError()

    /** 请求已发出但在预算时间内没有结论：结果未知，需要走"查证"而不是"重试"。 */
    data class ResultUnknown(val operationName: String, val elapsedMillis: Long) : DomainError()

    /** 车辆/云端明确拒绝执行，比如车速 > 0 时禁止开充电口。 */
    data class Rejected(val code: Int, val reason: String) : DomainError()

    /** 当前车型/硬件不支持该能力。 */
    data class Unsupported(val capability: String) : DomainError()

    /** 报文或响应解析失败，一般是协议版本不匹配。 */
    data class Malformed(val detail: String) : DomainError()

    /** 信号本身不可信（无效值、超时、超量程）。 */
    data class SignalUnreliable(val signalName: String, val quality: String) : DomainError()

    val userMessage: String
        get() = when (this) {
            is ConnectionUnavailable -> "车机服务未就绪：$reason"
            is ResultUnknown -> "指令状态待确认，请稍后查看车辆状态"
            is Rejected -> "车辆拒绝执行（$code）：$reason"
            is Unsupported -> "当前车型不支持：$capability"
            is Malformed -> "协议解析异常：$detail"
            is SignalUnreliable -> "信号不可信：$signalName（$quality）"
        }
}

/**
 * 轻量 Result 包装。
 *
 * 不用 kotlin.Result 是因为它带 @JvmName 限制、不能作为 Flow 的元素类型稳定使用，
 * 而且领域错误需要携带可展示的用户文案，这对控车 UI 是刚需。
 */
sealed class Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>()
    data class Failure(val error: DomainError) : Outcome<Nothing>()

    val isSuccess: Boolean get() = this is Success

    fun getOrNull(): T? = (this as? Success)?.value

    fun errorOrNull(): DomainError? = (this as? Failure)?.error

    inline fun <R> map(transform: (T) -> R): Outcome<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    companion object {
        fun <T> success(value: T): Outcome<T> = Success(value)
        fun failure(error: DomainError): Failure = Failure(error)
    }
}
