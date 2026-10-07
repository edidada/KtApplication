package com.example.ktapplication.vehicle.data

import com.example.ktapplication.core.AppDispatchers
import com.example.ktapplication.core.DomainError
import com.example.ktapplication.core.MonotonicClock
import com.example.ktapplication.core.Outcome
import com.example.ktapplication.core.ProductionAppDispatchers
import com.example.ktapplication.core.SystemMonotonicClock
import com.example.ktapplication.vehicle.data.json.JObject
import com.example.ktapplication.vehicle.data.json.MiniJson
import com.example.ktapplication.vehicle.data.json.optArray
import com.example.ktapplication.vehicle.data.json.optInt
import com.example.ktapplication.vehicle.data.json.optString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.channels.ClosedByInterruptException
import java.util.zip.GZIPInputStream

/**
 * 访问凭证提供者。
 *
 * 车机 App 的 token 由账号体系异步刷新（OAuth 有效期通常 2h），而 HttpURLConnection
 * 是同步语义，所以把"拿 token"做成 suspend 注入，而不是构造期一次性传入字符串 ——
 * 否则 token 过期后整个客户端只能重建。
 *
 * [onUnauthorized] 是带默认实现的钩子：401/4010 时客户端要求实现方丢弃缓存，
 * 下一次 [token] 调用即触发真正的刷新。没有它就只能拿旧 token 反复撞 401。
 */
interface AccessTokenProvider {
    suspend fun token(): String?

    /** 收到 401/4010 时回调；实现方应使缓存失效，让下一次 token() 强制刷新。 */
    suspend fun onUnauthorized() {}
}

/**
 * HTTP 策略（纯函数，与 socket 解耦，便于 JVM 单测直接断言）。
 *
 * 车联网场景的超时预算不是拍脑袋：远程控车读超时 = "端到端预算 - 建连 - 握手余量"。
 * 隧道里 TCP 建连可能吃掉 2s，若读超时仍按默认 10s，UI 的 15s 倒计时会先于底层
 * 超时到期，用户看到的现象是"转圈完了但请求还在飞"。
 */
internal object HttpPolicy {

    const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 5_000
    const val DEFAULT_READ_TIMEOUT_MILLIS = 10_000

    /** 单次逻辑请求最多尝试次数（含首发）。 */
    const val MAX_ATTEMPTS = 3

    /** RTT 超过这个值判 DEGRADED：控车仍可发，但预算要收紧。 */
    const val DEGRADED_RTT_MILLIS = 3_000L

    /** 代表"没拿到 HTTP 状态码"（传输层失败）的哨兵值。 */
    const val HTTP_TRANSPORT_FAILURE = -1

    /** TLS 握手 + 首包的经验余量。 */
    private const val HANDSHAKE_MARGIN_MILLIS = 500L

    /**
     * 控车 POST 的读超时：端预算减去建连和握手余量，且不超过全局默认读超时。
     * coerceAtLeast(1)：预算被配错（比建连还短）时宁可立刻超时，也不要 0/负数。
     */
    fun commandReadTimeoutMillis(
        actionBudgetMillis: Long,
        connectTimeoutMillis: Int,
        defaultReadTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    ): Long {
        val usable = actionBudgetMillis - connectTimeoutMillis - HANDSHAKE_MARGIN_MILLIS
        return usable.coerceIn(1L, defaultReadTimeoutMillis.toLong())
    }

    /** 长轮询的读超时必须大于服务端挂起时长，否则正常的 hold 会被本地掐死。 */
    fun pushReadTimeoutMillis(holdMillis: Long, connectTimeoutMillis: Int): Long =
        holdMillis + connectTimeoutMillis + HANDSHAKE_MARGIN_MILLIS

    /**
     * 重试判定。规则：
     *  - 只有传输失败(-1)、5xx、429（限流）值得重试；其余 4xx 是确定性错误，重试徒增延迟。
     *  - GET/HEAD 天然幂等，可以重试；
     *  - POST 控车**只有携带 idempotencyKey 时才允许重试** —— 弱网下"请求已送达但响应
     *    丢失"的假失败很常见，没有幂等键的重试就是让用户意外多解锁一次。
     */
    fun shouldRetry(
        method: String,
        httpCode: Int,
        attempt: Int,
        hasIdempotencyKey: Boolean,
        maxAttempts: Int = MAX_ATTEMPTS,
    ): Boolean {
        if (attempt + 1 >= maxAttempts) return false
        val worthRetrying = httpCode == HTTP_TRANSPORT_FAILURE || httpCode >= 500 || httpCode == 429
        if (!worthRetrying) return false
        return when (method.uppercase()) {
            "GET", "HEAD" -> true
            "POST" -> hasIdempotencyKey
            else -> false
        }
    }

    /** 指数退避 + 封顶：弱网下密集重试会加剧拥塞，所以退避本身必须封顶。 */
    fun backoffMillis(attempt: Int, baseMillis: Long = 250L, capMillis: Long = 4_000L): Long {
        val exponent = (attempt - 1).coerceIn(0, 10)
        return (baseMillis shl exponent).coerceAtMost(capMillis)
    }

    /**
     * 显式声明 gzip：一旦自定义了 Accept-Encoding，HttpURLConnection 就不再透明解压，
     * 解码必须自己走 GZIPInputStream（见 readBody）。不声明则大报文（全量车况）
     * 在地库 3G 回退链路上的下载时间会成倍增加。
     */
    fun acceptEncodingHeader(): String = "gzip"

    fun shouldGunzip(contentEncoding: String?): Boolean =
        contentEncoding?.contains("gzip", ignoreCase = true) == true
}

/**
 * 基于 [HttpURLConnection] 的 TSP 客户端（零第三方依赖）。
 *
 * 工程约束的实现要点：
 *  - 超时可配置：建连默认 5s、读默认 10s，控车按 action 预算收紧（见 [HttpPolicy]）；
 *  - 每个 suspend 方法都用 withTimeout 包一层"端预算"，兑现契约里"最坏 15s 有结论" ——
 *    底层 socket 超时只兜单次 IO，端预算兜住"重试 + 退避"的总时长；
 *  - 401/4010 触发 [AccessTokenProvider.onUnauthorized] 后只重试**一次**；
 *  - 推送通道是长轮询冷流：collector 取消即干净退出；SessionExpired 后重新鉴权 +
 *    重置 cursor 重连，而不是结束流（车况页不该因一次 token 过期永久失去推送）。
 */
class HttpTspClient(
    baseUrl: String,
    private val defaultVehicleId: String,
    private val tokenProvider: AccessTokenProvider,
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val dispatchers: AppDispatchers = ProductionAppDispatchers,
    private val connectTimeoutMillis: Int = HttpPolicy.DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = HttpPolicy.DEFAULT_READ_TIMEOUT_MILLIS,
    private val maxAttempts: Int = HttpPolicy.MAX_ATTEMPTS,
    /** 长轮询单次挂起时长，需与网关约定一致（一般 20~30s）。 */
    private val pushHoldMillis: Long = 25_000L,
    /** 状态/查询类 GET 的端预算；对应 RemoteAction.REFRESH_STATUS 的量级。 */
    private val statusBudgetMillis: Long = 12_000L,
    /** actionCode → 端到端预算；缺省 15s，与领域层 RemoteAction.timeoutMillis 对齐。 */
    private val commandBudgets: Map<String, Long> = emptyMap(),
) : TspClient {

    private val baseUrl = baseUrl.trimEnd('/')

    private val _connectivity = MutableStateFlow(TspConnectivity.OFFLINE)
    override val connectivity: StateFlow<TspConnectivity> = _connectivity.asStateFlow()

    @Volatile
    private var closed = false

    /** 单次请求的内部结局：拿到 HTTP 响应，或传输层失败（offline 判定含在其中）。 */
    private sealed class HttpAttempt {
        class Response(val httpCode: Int, val body: String) : HttpAttempt()
        class Transport(val reason: String, val retryable: Boolean, val offline: Boolean) : HttpAttempt()
    }

    // ---- TspClient 契约实现 ----

    override suspend fun fetchStatus(vehicleId: String): Outcome<TspStatusSnapshot> =
        withBudget(
            budgetMillis = statusBudgetMillis,
            timeoutError = { elapsed ->
                // 读接口超时按"链路不可用"处理是安全的：没有副作用，重试或回退缓存都行。
                DomainError.ConnectionUnavailable("车况请求超时（${elapsed}ms）")
            },
        ) {
            val rttStart = clock.nowMillis()
            val query = TspRequestBuilder.queryString("vin" to vehicleId)
            when (val attempt = performRequest("GET", "/api/v1/status?$query", readTimeoutMillis)) {
                is HttpAttempt.Response -> {
                    markOnline(rtt = clock.nowMillis() - rttStart)
                    statusFromBody(vehicleId, attempt.body)
                }
                is HttpAttempt.Transport -> {
                    if (attempt.offline) _connectivity.value = TspConnectivity.OFFLINE
                    Outcome.failure(DomainError.ConnectionUnavailable(attempt.reason))
                }
            }
        }

    override suspend fun submitCommand(request: TspCommandRequest): Outcome<TspCommandAck> {
        val budget = commandBudgets[request.actionCode] ?: DEFAULT_COMMAND_BUDGET_MILLIS
        val readTimeout = HttpPolicy
            .commandReadTimeoutMillis(budget, connectTimeoutMillis, readTimeoutMillis)
            .toInt()
        return withBudget(
            budgetMillis = budget,
            timeoutError = { elapsed ->
                // 控车超时是"结果未知"而不是"失败"：契约要求上层走查证路径。
                DomainError.ResultUnknown("submitCommand(${request.actionCode})", elapsed)
            },
        ) {
            val attempt = performRequest(
                method = "POST",
                pathWithQuery = "/api/v1/command",
                readTimeout = readTimeout,
                body = TspRequestBuilder.commandBody(request),
                idempotencyKey = request.idempotencyKey,
            )
            when (attempt) {
                is HttpAttempt.Response -> Outcome.success(TspCommandParser.parseAck(attempt.body))
                is HttpAttempt.Transport -> {
                    if (attempt.offline) _connectivity.value = TspConnectivity.OFFLINE
                    // 传输失败以 Ack 形态返回而不是 Outcome.failure：ack.retryable 是
                    // 队列冲刷策略做"自动重试 / 停在待确认"决策的直接输入。
                    Outcome.success(TspCommandAck.TransportFailure(attempt.reason, attempt.retryable))
                }
            }
        }
    }

    override suspend fun queryCommandResult(commandId: String): Outcome<TspCommandResult> =
        withBudget(
            budgetMillis = statusBudgetMillis,
            timeoutError = { elapsed ->
                DomainError.ConnectionUnavailable("结果查询超时（${elapsed}ms）")
            },
        ) {
            val query = TspRequestBuilder.queryString("commandId" to commandId)
            when (val attempt = performRequest("GET", "/api/v1/command/result?$query", readTimeoutMillis)) {
                is HttpAttempt.Response -> Outcome.success(TspCommandParser.parseResult(attempt.body))
                is HttpAttempt.Transport -> {
                    if (attempt.offline) _connectivity.value = TspConnectivity.OFFLINE
                    Outcome.failure(DomainError.ConnectionUnavailable(attempt.reason))
                }
            }
        }

    override suspend fun probeConnectivity(): TspConnectivity {
        if (closed) return TspConnectivity.OFFLINE
        if (tokenProvider.token().isNullOrBlank()) {
            _connectivity.value = TspConnectivity.NOT_PROVISIONED
            return TspConnectivity.NOT_PROVISIONED
        }
        val startedAt = clock.nowMillis()
        val attempt = singleExchange(
            method = "GET",
            pathWithQuery = "/api/v1/status?" + TspRequestBuilder.queryString("vin" to defaultVehicleId),
            readTimeoutMs = PROBE_READ_TIMEOUT_MILLIS,
        )
        val rtt = clock.nowMillis() - startedAt
        val next = when (attempt) {
            is HttpAttempt.Response ->
                if (rtt > HttpPolicy.DEGRADED_RTT_MILLIS) TspConnectivity.DEGRADED else TspConnectivity.ONLINE
            // 超时但包发出去了：链路在、质量差；DNS 失败/连接被拒：真离线。
            is HttpAttempt.Transport ->
                if (attempt.offline) TspConnectivity.OFFLINE else TspConnectivity.DEGRADED
        }
        _connectivity.value = next
        return next
    }

    override fun close() {
        // 长轮询循环靠该标志自然收束：当 in-flight 的 exchange 结束（读超时兜底）后流终止。
        // 不强拆在途 socket：HttpURLConnection 没有线程安全的跨线程 abort API，
        // 让读超时到期是这里代价最小、也最不容易引入半关闭状态的做法。
        closed = true
        _connectivity.value = TspConnectivity.OFFLINE
    }

    // ---- 推送通道：长轮询 + 退避重连 ----

    /**
     * 冷流：每次 collect 启动一个独立轮询循环。
     *
     * 取消语义：collector 取消后 [delay] 与 exchange 的挂起点立刻抛 CancellationException；
     * [singleExchange] 把"取消导致的中断型 IOException"转回 CancellationException，
     * 绝不让它落入"传输失败 → 退避重连"分支 —— 否则已取消的流会退而不死，
     * 变成后台幽灵连接（车机上电耗和流量都敏感）。
     */
    override val pushEvents: Flow<TspPushEvent> = flow {
        var cursor: String? = null
        var backoffAttempt = 0
        while (!closed && currentCoroutineContext()[Job]?.isActive == true) {
            val query = TspRequestBuilder.pushQuery(cursor, pushHoldMillis, defaultVehicleId)
            val readTimeout = HttpPolicy
                .pushReadTimeoutMillis(pushHoldMillis, connectTimeoutMillis)
                .toInt()
            // 长轮询是幂等 GET，允许 performRequest 内部有限重试；外层退避兜住网关持续 5xx。
            when (val attempt = performRequest("GET", "/api/v1/push?$query", readTimeout = readTimeout)) {
                is HttpAttempt.Response -> {
                    backoffAttempt = 0
                    markOnline(rtt = 0L)
                    val batch = parsePushBatch(attempt.body, clock.nowMillis())
                    batch.cursor?.let { cursor = it }
                    for (event in batch.events) {
                        emit(event)
                        if (event is TspPushEvent.SessionExpired) {
                            // 重新鉴权 + 重置游标：旧 cursor 在新会话里无效，
                            // 带着它重连会被网关直接拒绝并白白进入退避循环。
                            tokenProvider.onUnauthorized()
                            cursor = null
                        }
                    }
                }
                is HttpAttempt.Transport -> {
                    if (attempt.offline) _connectivity.value = TspConnectivity.OFFLINE
                    backoffAttempt += 1
                    delay(HttpPolicy.backoffMillis(backoffAttempt, baseMillis = 1_000L, capMillis = 30_000L))
                }
            }
        }
    }.flowOn(dispatchers.io)

    private class PushBatch(val cursor: String?, val events: List<TspPushEvent>)

    /** 推送响应约定：`{"code":0,"data":{"cursor":"..","events":[...]}}`；兼容裸数组/单对象。 */
    private fun parsePushBatch(text: String, receivedAtMillis: Long): PushBatch {
        val root = MiniJson.tryParse(text)
        if (root is JObject) {
            val data = (TspProtocol.unpack(text) as? Outcome.Success)?.value
            val events = data?.optArray("events")?.elements
            if (data != null && events != null) {
                return PushBatch(
                    cursor = data.optString("cursor"),
                    events = events.mapNotNull { TspCommandParser.parseSingle(it, receivedAtMillis) },
                )
            }
            // 信封解析失败：可能网关直接推裸事件对象，交给单事件入口，坏条目跳过。
            return PushBatch(root.optString("cursor"), listOfNotNull(TspCommandParser.parseSingle(root, receivedAtMillis)))
        }
        return PushBatch(null, TspCommandParser.parsePushEvents(text, receivedAtMillis))
    }

    // ---- 请求管线 ----

    /** 带重试与 401 刷新的一次"逻辑请求"（含退避），受外层端预算约束。 */
    private suspend fun performRequest(
        method: String,
        pathWithQuery: String,
        readTimeout: Int,
        body: String? = null,
        idempotencyKey: String? = null,
    ): HttpAttempt {
        var attemptIndex = 0
        var refreshTried = false
        while (true) {
            val token = tokenProvider.token()
            if (token.isNullOrBlank()) {
                _connectivity.value = TspConnectivity.NOT_PROVISIONED
                return HttpAttempt.Transport("缺少访问凭证", retryable = false, offline = false)
            }
            val extraHeaders = buildHeaders(token, idempotencyKey)
            val result = singleExchange(method, pathWithQuery, readTimeoutMs = readTimeout, body = body, extraHeaders = extraHeaders)
            if (result is HttpAttempt.Response && isAuthFailure(result) && !refreshTried) {
                // 只刷新一次：token 服务故障时无限刷新会把一个请求变成死循环。
                refreshTried = true
                tokenProvider.onUnauthorized()
                continue
            }
            val retryable = when (result) {
                is HttpAttempt.Response ->
                    HttpPolicy.shouldRetry(method, result.httpCode, attemptIndex, idempotencyKey != null, maxAttempts)
                is HttpAttempt.Transport ->
                    result.retryable && HttpPolicy.shouldRetry(
                        method, HttpPolicy.HTTP_TRANSPORT_FAILURE, attemptIndex, idempotencyKey != null, maxAttempts,
                    )
            }
            if (!retryable) return result
            attemptIndex += 1
            delay(HttpPolicy.backoffMillis(attemptIndex))
        }
    }

    private fun buildHeaders(token: String, idempotencyKey: String?): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["Authorization"] = "Bearer $token"
        // 幂等键进请求头让网关在鉴权后即短路去重，弱网重传的指令不再进执行队列。
        idempotencyKey?.let { headers["X-Idempotency-Key"] = it }
        return headers
    }

    /** 401（HTTP 层）或 body 信封 code=401/4010（网关把鉴权失败包进 200 是常见风格）。 */
    private fun isAuthFailure(response: HttpAttempt.Response): Boolean {
        if (response.httpCode == 401) return true
        val code = (MiniJson.tryParse(response.body) as? JObject)?.optInt("code")
        return code == TspProtocol.CODE_TOKEN_EXPIRED_LEGACY || code == TspProtocol.CODE_TOKEN_EXPIRED
    }

    /**
     * 单次 socket 交互；阻塞 IO 全部收在 [withContext] + dispatchers.io。
     *
     * 取消处理要点：Dispatchers.IO 会在线程 interrupt 时打断阻塞调用，
     * HttpURLConnection 的表现为 [ClosedByInterruptException] 或各类 IOException。
     * 先检查 Job 是否活跃：不活跃 → 转回 CancellationException 干净退出；
     * 活跃 → 才归入传输失败参与重试判定。否则"用户划走页面"会被误判成
     * "网络抖动"并白白退避重试。
     */
    private suspend fun singleExchange(
        method: String,
        pathWithQuery: String,
        readTimeoutMs: Int,
        body: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpAttempt = withContext(dispatchers.io) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL("$baseUrl$pathWithQuery")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = this@HttpTspClient.connectTimeoutMillis
                // 注意：apply 内裸读 readTimeout 会命中 HttpURLConnection 自身的属性（receiver 优先），
                // 所以右值必须用不与属性重名的参数 readTimeoutMs，否则是"自己赋值给自己"的静默 bug。
                readTimeout = readTimeoutMs
                useCaches = false
                // TSP 网关不发 3xx；若被透明代理劫持到登录页，跟随重定向会把
                // HTML 当 JSON 解出 Malformed，不如原样返回状态码让上层识别。
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Encoding", HttpPolicy.acceptEncodingHeader())
                extraHeaders.forEach { (name, value) -> setRequestProperty(name, value) }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            HttpAttempt.Response(code, readBody(conn))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ClosedByInterruptException) {
            if (!isActiveContext()) throw CancellationException("请求随协程取消而中断")
            HttpAttempt.Transport("连接被中断：${e.message}", retryable = true, offline = false)
        } catch (e: SocketTimeoutException) {
            // 隧道/地库的典型失败：链路在但丢包。可重试，但不判离线。
            if (!isActiveContext()) throw CancellationException("请求随协程取消而中断")
            HttpAttempt.Transport("socket 读超时", retryable = true, offline = false)
        } catch (e: UnknownHostException) {
            if (!isActiveContext()) throw CancellationException("请求随协程取消而中断")
            HttpAttempt.Transport("DNS 解析失败：${e.message}", retryable = true, offline = true)
        } catch (e: IOException) {
            if (!isActiveContext()) throw CancellationException("请求随协程取消而中断")
            HttpAttempt.Transport("传输异常：${e.javaClass.simpleName} ${e.message}", retryable = true, offline = true)
        } finally {
            conn?.disconnect()
        }
    }

    private suspend fun isActiveContext(): Boolean = currentCoroutineContext()[Job]?.isActive == true

    /** 读响应体；显式声明 Accept-Encoding 后 gzip 解压必须自己做。 */
    private fun readBody(conn: HttpURLConnection): String {
        // 4xx/5xx 时 getInputStream 抛 IOException，而错误信封正文在 errorStream 里 —— 必须读。
        val stream = runCatching { conn.inputStream }.getOrNull() ?: conn.errorStream ?: return ""
        val bytes = stream.use { input ->
            val decoded = if (HttpPolicy.shouldGunzip(conn.contentEncoding)) GZIPInputStream(input) else input
            decoded.use { it.readBytes() }
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun statusFromBody(vehicleId: String, text: String): Outcome<TspStatusSnapshot> =
        when (val unpacked = TspProtocol.unpack(text)) {
            is Outcome.Failure -> Outcome.failure(unpacked.error)
            is Outcome.Success -> {
                val snapshot = TspStatusParser.parseData(unpacked.value, clock.nowMillis())
                // 云端漏回 vin 时用请求侧 vehicleId 补齐：快照 vehicleId 非空是契约。
                Outcome.success(if (snapshot.vehicleId.isBlank()) snapshot.copy(vehicleId = vehicleId) else snapshot)
            }
        }

    /**
     * 端预算包裹：withTimeout 到点抛 TimeoutCancellationException，
     * 转成调用方指定的 DomainError —— 契约的"预算内必返回"由这一层兑现。
     * 注意只捕 TimeoutCancellationException：外部 collector 的真取消必须原样传播。
     */
    private suspend fun <T> withBudget(
        budgetMillis: Long,
        timeoutError: (Long) -> DomainError,
        block: suspend () -> Outcome<T>,
    ): Outcome<T> {
        val startedAt = clock.nowMillis()
        return try {
            withTimeout(budgetMillis) { block() }
        } catch (e: TimeoutCancellationException) {
            Outcome.failure(timeoutError(clock.nowMillis() - startedAt))
        }
    }

    private fun markOnline(rtt: Long) {
        _connectivity.value =
            if (rtt > HttpPolicy.DEGRADED_RTT_MILLIS) TspConnectivity.DEGRADED else TspConnectivity.ONLINE
    }

    private companion object {
        /** 与 RemoteAction 默认 timeoutMillis 对齐。 */
        const val DEFAULT_COMMAND_BUDGET_MILLIS = 15_000L

        /** 探针要快：2s 内给结论，否则"检查网络"这个动作本身就会卡住 UI。 */
        const val PROBE_READ_TIMEOUT_MILLIS = 2_000
    }
}
