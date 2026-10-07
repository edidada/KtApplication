package com.example.ktapplication.vehicle.data

import com.example.ktapplication.core.DomainError
import com.example.ktapplication.core.Outcome
import com.example.ktapplication.vehicle.data.json.JArray
import com.example.ktapplication.vehicle.data.json.JNumber
import com.example.ktapplication.vehicle.data.json.JObject
import com.example.ktapplication.vehicle.data.json.JString
import com.example.ktapplication.vehicle.data.json.JsonParseException
import com.example.ktapplication.vehicle.data.json.JsonValue
import com.example.ktapplication.vehicle.data.json.MiniJson
import com.example.ktapplication.vehicle.data.json.jNum
import com.example.ktapplication.vehicle.data.json.jObj
import com.example.ktapplication.vehicle.data.json.jStr
import com.example.ktapplication.vehicle.data.json.optBoolean
import com.example.ktapplication.vehicle.data.json.optDouble
import com.example.ktapplication.vehicle.data.json.optInt
import com.example.ktapplication.vehicle.data.json.optLong
import com.example.ktapplication.vehicle.data.json.optObject
import com.example.ktapplication.vehicle.data.json.optString
import com.example.ktapplication.vehicle.data.json.toJson

/**
 * TSP 报文 ↔ [TspClient] DTO 的映射层。
 *
 * 信封约定（常见 TSP 网关风格）：`{"code":0,"msg":"ok","data":{...}}`
 *
 * 错误映射规则（**显式约定，上层依赖这些语义，勿改**）：
 *  - 整包 JSON 语法错误 → [DomainError.Malformed]（协议版本不匹配 / 中间层截包）；
 *  - 信封缺 `code` 或 code 类型不符 → [DomainError.Malformed]；
 *  - `code != 0` 且落在 4000..4999 业务拒绝段 → [DomainError.Rejected]：
 *    云端在该段回"车辆/权限明确拒绝"，必须保留原始 code 与 msg，因为 UI 要区分
 *    "车速过高禁止解锁"（可解释）和"账号无车权"（要引导去授权页）；
 *  - `code != 0` 的其他段（5xxx 网关、6xxx 限流等）→ [DomainError.ConnectionUnavailable]：
 *    这类错误云端还没做决定，客户端按"链路问题"处理（可安全重试/入队），
 *    不该升级成业务失败展示给用户；
 *  - `code == 0` 但 `data` 缺失或不是对象 → [DomainError.Malformed]：
 *    返回全 null 的空快照会让上层误判为"所有信号同时消失"，比报错更危险。
 *
 * 单值字段缺失/类型漂移一律降级为 null 而不是抛错：TSP 不同车型字段集合不一致，
 * 车辆下电后云端还会重放缓存旧值，字段漂移是稳态而非异常。
 */
object TspProtocol {
    const val CODE_OK = 0

    /** 业务拒绝段：4000..4999。 */
    const val REJECTED_RANGE_LOW = 4000
    const val REJECTED_RANGE_HIGH = 4999

    /** 401（HTTP 语义复用进 body）/ 4010：token 过期，HttpTspClient 据此刷新凭证重试一次。 */
    const val CODE_TOKEN_EXPIRED_LEGACY = 401
    const val CODE_TOKEN_EXPIRED = 4010

    /** 解析信封；成功返回 data 对象，失败返回映射后的 DomainError。 */
    fun unpack(text: String): Outcome<JObject> {
        val root = try {
            MiniJson.parseObject(text)
        } catch (e: JsonParseException) {
            return Outcome.failure(DomainError.Malformed(e.detail))
        }
        val code = root.optInt("code")
            ?: return Outcome.failure(DomainError.Malformed("信封缺少 code"))
        if (code != CODE_OK) {
            val msg = root.optString("msg") ?: "code=$code"
            return Outcome.failure(envelopeError(code, msg))
        }
        val data = root.optObject("data")
            ?: return Outcome.failure(DomainError.Malformed("code=0 但缺少 data 对象"))
        return Outcome.success(data)
    }

    private fun envelopeError(code: Int, msg: String): DomainError = when (code) {
        in REJECTED_RANGE_LOW..REJECTED_RANGE_HIGH -> DomainError.Rejected(code, msg)
        else -> DomainError.ConnectionUnavailable("TSP 网关错误($code): $msg")
    }
}

/** 车况报文 → 快照。 */
object TspStatusParser {

    /**
     * 文本入口：先拆信封再解字段。
     * @param receivedAtMillis 由调用方传入（接收侧时钟），解析层不碰时钟，方便单测断言。
     * 信封级错误（语法错 / code!=0 / 缺 data）抛 [JsonParseException]：
     * 本方法面向"已经确认是状态报文"的调用点；HttpTspClient 会先走
     * [TspProtocol.unpack] 拿到 Outcome，不会让异常泄漏出去。
     */
    fun parse(jsonText: String, receivedAtMillis: Long): TspStatusSnapshot {
        val data = when (val unpacked = TspProtocol.unpack(jsonText)) {
            is Outcome.Failure -> throw JsonParseException(unpacked.error.userMessage)
            is Outcome.Success -> unpacked.value
        }
        return parseData(data, receivedAtMillis)
    }

    /** 已拆封的 data 对象 → 快照；HttpTspClient 与推送通道复用。 */
    fun parseData(data: JObject, receivedAtMillis: Long): TspStatusSnapshot {
        val vin = data.optString("vin").orEmpty()
        // reportTime 缺失时用 receivedAt 兜底：若留 0，上层的"陈旧判定"
        // 会把每条报文都判成过期，车况页直接全灰。
        val reportTime = data.optLong("reportTime") ?: receivedAtMillis

        val ac = data.optObject("acStatus")
        val charge = data.optObject("chargeStatus")
        val position = data.optObject("position")

        // doors 是动态 key 对象（FL/FR/RL/RR/TRUNK…），逐 key 取值、未知 key 原样保留：
        // 新车型加了前备箱之类的新门时不应该丢数据。
        // 值同时接受 number 与 string：老网关把门状态序列化成 "1"，硬要 JNumber 会让整组门消失。
        // 解不出来的条目直接丢弃而不是回 0 —— 0 在协议里是"门关"，猜成 0 会把"未知"报成"确定已关"。
        // 整个 doors 键缺失时回空表而不是 null：空表的语义是"本轮没有门状态"，聚合层会保留上一轮 CAN 值。
        val doors = data.optObject("doors")?.let { doorsObj ->
            doorsObj.members.mapNotNull { (key, value) ->
                val raw = (value as? JNumber)?.value?.toInt()
                    ?: (value as? JString)?.value?.toIntOrNull()
                raw?.let { key to it }
            }.toMap()
        } ?: emptyMap()

        return TspStatusSnapshot(
            vehicleId = vin,
            reportedAtMillis = reportTime,
            receivedAtMillis = receivedAtMillis,
            stateOfChargePercent = data.optDouble("soc"),
            remainingRangeKilometres = data.optDouble("range"),
            odometerKilometres = data.optDouble("mileage"),
            speedKilometresPerHour = data.optDouble("speed"),
            gearRaw = data.optInt("gear"),
            lockRaw = data.optInt("centralLock"),
            doorsRaw = doors,
            driverSeatbeltRaw = data.optInt("seatBelt"),
            airConditionerOn = ac?.optBoolean("on"),
            targetTemperatureCelsius = ac?.optDouble("temp"),
            fanLevel = ac?.optInt("fan"),
            pluggedIn = charge?.optBoolean("plugged"),
            chargingPowerKilowatts = charge?.optDouble("powerKw"),
            chargingCurrentAmperes = charge?.optDouble("currentA"),
            packVoltageVolts = charge?.optDouble("volt"),
            lowBatteryVolts = data.optDouble("accVolt"),
            latitude = position?.optDouble("lat"),
            longitude = position?.optDouble("lng"),
            accuracyMeters = position?.optDouble("acc")?.toFloat(),
            dataOrigin = data.optString("origin"),
        )
    }
}

/** 指令回执 / 查询结果 / 推送事件报文解析。 */
object TspCommandParser {

    /**
     * 受理响应：`{"code":0,"data":{"serverMessageId":"SM123"}}`。
     * 传输层失败（IOException 等）不走这里，由 HttpTspClient 构造 TransportFailure。
     * 业务 code!=0：4xxx 段 → Rejected(retryable=false)；其余段 → TransportFailure(retryable=true)，
     * 因为网关 5xx / 限流意味着"云端还没做决定"，与"明确拒绝"的用户体验完全不同。
     */
    fun parseAck(text: String): TspCommandAck {
        return when (val unpacked = TspProtocol.unpack(text)) {
            is Outcome.Failure -> ackFallback(unpacked.error)
            is Outcome.Success -> {
                val serverMessageId = unpacked.value.optString("serverMessageId")
                if (serverMessageId.isNullOrBlank()) {
                    // code=0 但没给服务端消息号：后续无法查证，不能假装 Accepted，
                    // 否则指令永远停在"已受理"而没人去 queryCommandResult。
                    TspCommandAck.TransportFailure("受理成功但缺少 serverMessageId", retryable = false)
                } else {
                    TspCommandAck.Accepted(serverMessageId)
                }
            }
        }
    }

    private fun ackFallback(error: DomainError): TspCommandAck = when (error) {
        is DomainError.Rejected -> TspCommandAck.Rejected(error.code, error.reason, retryable = false)
        is DomainError.Malformed -> TspCommandAck.TransportFailure("响应无法解析：${error.detail}", retryable = false)
        else -> TspCommandAck.TransportFailure(error.userMessage, retryable = true)
    }

    /**
     * 结果查询：`{"code":0,"data":{"state":"EXECUTED|PENDING|FAILED|UNKNOWN",...}}`。
     * 信封错 / state 解析不出一律回 [TspCommandResult.Unknown]：
     * 宁可停在"待确认"也不能误报失败 —— 误报失败会诱导用户重复按门锁键。
     */
    fun parseResult(text: String): TspCommandResult {
        val data = (TspProtocol.unpack(text) as? Outcome.Success)?.value ?: return TspCommandResult.Unknown
        return parseResultData(data)
    }

    fun parseResultData(data: JObject): TspCommandResult = when (data.optString("state")?.uppercase()) {
        "EXECUTED", "SUCCESS" -> TspCommandResult.Executed
        "PENDING", "EXECUTING" -> TspCommandResult.Pending(data.optLong("etaMillis"))
        "FAILED" -> TspCommandResult.Failed(
            code = data.optInt("failCode") ?: -1,
            reason = data.optString("failMsg") ?: "车辆执行失败",
        )
        // "REJECTED" 与 "FAILED" 在部分网关版本混用，都是终态、都带原因。
        "REJECTED" -> TspCommandResult.Failed(
            code = data.optInt("failCode") ?: -1,
            reason = data.optString("failMsg") ?: "车辆拒绝执行",
        )
        else -> TspCommandResult.Unknown
    }

    /**
     * 单条推送事件。契约签名。
     * 无法识别的 type 返回 null：推送通道要优先保活，一条看不懂的新事件
     * （云端先行灰度的 type）不应该打断整个连接。
     */
    fun parsePushEvent(text: String): TspPushEvent? =
        parseSingle(MiniJson.tryParse(text), receivedAtMillis = 0L)

    /** 长轮询批量入口：`[{...},{...}]`，单对象也接受；坏条目跳过不连坐。 */
    fun parsePushEvents(text: String, receivedAtMillis: Long = 0L): List<TspPushEvent> {
        val root = try {
            MiniJson.parse(text)
        } catch (_: JsonParseException) {
            return emptyList()
        }
        val items: List<JsonValue> = when (root) {
            is JArray -> root.elements
            is JObject -> listOf(root)
            else -> return emptyList()
        }
        return items.mapNotNull { parseSingle(it, receivedAtMillis) }
    }

    /** 事件对象 → DTO；StatusUpdated 的 receivedAt 由调用方补，保证时钟注入一致。 */
    fun parseSingle(value: JsonValue?, receivedAtMillis: Long = 0L): TspPushEvent? {
        val obj = value as? JObject ?: return null
        return when (obj.optString("type")?.uppercase()) {
            "STATUS" -> {
                val data = obj.optObject("data") ?: return null
                TspPushEvent.StatusUpdated(TspStatusParser.parseData(data, receivedAtMillis))
            }
            "COMMAND_RESULT" -> {
                val commandId = obj.optString("commandId") ?: return null
                val result = obj.optObject("result")?.let { parseResultData(it) } ?: TspCommandResult.Unknown
                TspPushEvent.CommandResultUpdated(commandId, result)
            }
            "ALERT" -> TspPushEvent.Alert(
                code = obj.optInt("code") ?: 0,
                message = obj.optString("message") ?: "车辆告警",
                level = when (obj.optString("level")?.uppercase()) {
                    "CRITICAL" -> AlertLevel.CRITICAL
                    "WARNING" -> AlertLevel.WARNING
                    else -> AlertLevel.INFO
                },
            )
            "HEARTBEAT" -> TspPushEvent.Heartbeat(obj.optLong("serverTime") ?: 0L)
            "SESSION_EXPIRED" -> TspPushEvent.SessionExpired
            else -> null
        }
    }
}

/** 请求构造：只产出合法紧凑 JSON / query 串，不掺 HTTP 细节。 */
object TspRequestBuilder {

    /**
     * 控车指令请求体。idempotencyKey 同时放 body 和 `X-Idempotency-Key` 头：
     * 前者给业务层对账，后者让网关在鉴权后直接短路去重，避免隧道弱网下
     * 重传的指令进入执行队列。
     */
    fun commandBody(req: TspCommandRequest): String {
        val parameters = JObject(req.parameters.mapValues { (_, v) -> jStr(v) })
        return jObj(
            "commandId" to jStr(req.commandId),
            "vehicleId" to jStr(req.vehicleId),
            "actionCode" to jStr(req.actionCode),
            "parameters" to parameters,
            "issuedAt" to jNum(req.issuedAtMillis),
            "idempotencyKey" to jStr(req.idempotencyKey),
        ).toJson()
    }

    /** 状态查询 query：`vin=..&fields=..`。所有值做百分号编码（防御性）。 */
    fun queryString(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (key, value) -> "${urlEncode(key)}=${urlEncode(value)}" }

    /** 长轮询 query。cursor 服务端可能回不透明串，必须编码；空 cursor 传空串表示从头。 */
    fun pushQuery(cursor: String?, timeoutMillis: Long, vehicleId: String): String =
        queryString(
            "vin" to vehicleId,
            "cursor" to (cursor ?: ""),
            "wait" to timeoutMillis.toString(),
        )

    /**
     * 不用 java.net.URLEncoder：它把空格编成 '+'（form 风格），
     * 而 TSP 网关按 RFC 3986 解析 query，'+' 会被还原成字面加号导致 cursor 校验失败。
     * 手写百分号编码，只保留 unreserved 字符集。
     */
    internal fun urlEncode(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xFF
            when {
                c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code ||
                    c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code -> append(c.toChar())
                else -> append('%').append(c.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
}

/**
 * 离线队列编解码：一行一条 [TspCommandRequest]。
 * 字段命名与 [TspRequestBuilder.commandBody] 保持一致，出问题时
 * 可以直接把队列文件贴给云端同学对账。
 */
internal object JournalCodec {

    fun encode(req: TspCommandRequest): String = jObj(
        "commandId" to jStr(req.commandId),
        "vehicleId" to jStr(req.vehicleId),
        "actionCode" to jStr(req.actionCode),
        "parameters" to JObject(req.parameters.mapValues { (_, v) -> jStr(v) }),
        "issuedAtMillis" to jNum(req.issuedAtMillis),
        "idempotencyKey" to jStr(req.idempotencyKey),
    ).toJson()

    /** 损坏行返回 null：掉电可能留下半行 JSON，必须逐行容错而不是整体读不出来。 */
    fun decode(line: String): TspCommandRequest? {
        val obj = MiniJson.tryParse(line) as? JObject ?: return null
        val commandId = obj.optString("commandId") ?: return null
        val vehicleId = obj.optString("vehicleId") ?: return null
        val actionCode = obj.optString("actionCode") ?: return null
        val issuedAt = obj.optLong("issuedAtMillis") ?: return null
        val parameters = obj.optObject("parameters")
            ?.members
            ?.mapNotNull { (k, v) -> (v as? JString)?.value?.let { k to it } }
            ?.toMap()
            ?: emptyMap()
        return TspCommandRequest(
            commandId = commandId,
            vehicleId = vehicleId,
            actionCode = actionCode,
            parameters = parameters,
            issuedAtMillis = issuedAt,
            // 老版本队列文件可能没有这两个键：给空串而不是丢整条，
            // 丢指令比带着空幂等键发送更危险（用户按过的锁指令不能凭空消失）。
            idempotencyKey = obj.optString("idempotencyKey") ?: commandId,
        )
    }
}
