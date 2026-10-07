package com.example.ktapplication.vehicle.data

import com.example.ktapplication.core.DomainError
import com.example.ktapplication.core.Outcome
import com.example.ktapplication.vehicle.data.json.optString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TspProtocolTest {

    private val fullPayload = """
        {"code":0,"msg":"ok","data":{
          "vin":"LVVFA1234ABC56789",
          "soc":72.5,"range":318,"mileage":23456.7,"speed":0,"gear":3,
          "centralLock":1,
          "doors":{"FL":0,"FR":0,"RL":1,"RR":2,"TRUNK":0},
          "seatBelt":1,
          "acStatus":{"on":true,"temp":24,"fan":3},
          "chargeStatus":{"plugged":false,"powerKw":0,"currentA":0,"volt":382},
          "accVolt":12.4,
          "position":{"lat":30.1,"lng":120.2,"acc":15},
          "reportTime":1700000000000,
          "origin":"VEHICLE"
        }}
    """.trimIndent()

    @Test
    fun fullPayloadMapsEveryField() {
        val snapshot = TspStatusParser.parse(fullPayload, receivedAtMillis = 999_999L)
        assertEquals("LVVFA1234ABC56789", snapshot.vehicleId)
        assertEquals(1_700_000_000_000L, snapshot.reportedAtMillis)
        assertEquals(999_999L, snapshot.receivedAtMillis)
        assertEquals(72.5, snapshot.stateOfChargePercent ?: error("soc"), 1e-9)
        assertEquals(318.0, snapshot.remainingRangeKilometres ?: error("range"), 1e-9)
        assertEquals(23456.7, snapshot.odometerKilometres ?: error("mileage"), 1e-9)
        assertEquals(0.0, snapshot.speedKilometresPerHour ?: error("speed"), 1e-9)
        assertEquals(3, snapshot.gearRaw)
        assertEquals(1, snapshot.lockRaw)
        assertEquals(mapOf("FL" to 0, "FR" to 0, "RL" to 1, "RR" to 2, "TRUNK" to 0), snapshot.doorsRaw)
        assertEquals(1, snapshot.driverSeatbeltRaw)
        assertEquals(true, snapshot.airConditionerOn)
        assertEquals(24.0, snapshot.targetTemperatureCelsius ?: error("temp"), 1e-9)
        assertEquals(3, snapshot.fanLevel)
        assertEquals(false, snapshot.pluggedIn)
        assertEquals(0.0, snapshot.chargingPowerKilowatts ?: error("powerKw"), 1e-9)
        assertEquals(0.0, snapshot.chargingCurrentAmperes ?: error("currentA"), 1e-9)
        assertEquals(382.0, snapshot.packVoltageVolts ?: error("volt"), 1e-9)
        assertEquals(12.4, snapshot.lowBatteryVolts ?: error("accVolt"), 1e-9)
        assertEquals(30.1, snapshot.latitude ?: error("lat"), 1e-9)
        assertEquals(120.2, snapshot.longitude ?: error("lng"), 1e-9)
        assertEquals(15f, snapshot.accuracyMeters)
        assertEquals("VEHICLE", snapshot.dataOrigin)
    }

    @Test
    fun sparseAndTypeDriftedPayloadSurvives() {
        // 车下电后云端重放的老缓存：类型漂移（soc 成字符串）、嵌套缺失、doors 值为字符串。
        val sparse = """{"code":0,"data":{"vin":"V1","soc":"72.5","gear":"notAByte","doors":{"FL":"1"},"reportTime":"1700000000001"}}"""
        val snapshot = TspStatusParser.parse(sparse, receivedAtMillis = 5_000L)
        assertEquals("V1", snapshot.vehicleId)
        assertEquals(72.5, snapshot.stateOfChargePercent ?: error("宽容取字符串数值"), 1e-9)
        assertEquals(1_700_000_000_001L, snapshot.reportedAtMillis)
        assertEquals(mapOf("FL" to 1), snapshot.doorsRaw)
        // 类型完全对不上：该字段 null，但不崩、不影响其他字段。
        assertNull(snapshot.gearRaw)
        assertNull(snapshot.airConditionerOn)
        assertNull(snapshot.latitude)
        assertNull(snapshot.dataOrigin)
    }

    @Test
    fun missingReportTimeFallsBackToReceivedAt() {
        val snapshot = TspStatusParser.parse("""{"code":0,"data":{"vin":"V1"}}""", receivedAtMillis = 4242L)
        assertEquals(4242L, snapshot.reportedAtMillis)
    }

    @Test
    fun envelopeCodeNonZeroMapsToRejected() {
        val unpacked = TspProtocol.unpack("""{"code":4001,"msg":"车速过高，禁止解锁","data":null}""")
        assertTrue(unpacked is Outcome.Failure)
        val error = (unpacked as Outcome.Failure).error
        assertTrue("应映射 Rejected，实际 ${error::class.simpleName}", error is DomainError.Rejected)
        assertEquals(4001, (error as DomainError.Rejected).code)
        assertEquals("车速过高，禁止解锁", error.reason)

        // 非拒绝段（网关 5xxx）按链路问题处理，可安全重试/入队。
        val gateway = (TspProtocol.unpack("""{"code":5002,"msg":"upstream timeout"}""") as Outcome.Failure).error
        assertTrue(gateway is DomainError.ConnectionUnavailable)

        // 语法错误 → Malformed。
        assertTrue((TspProtocol.unpack("{oops") as Outcome.Failure).error is DomainError.Malformed)
        // code=0 缺 data → Malformed，而不是全 null 快照。
        assertTrue((TspProtocol.unpack("""{"code":0}""") as Outcome.Failure).error is DomainError.Malformed)
    }

    @Test
    fun ackParsingAcceptedRejectedAndMissingServerId() {
        val accepted = TspCommandParser.parseAck("""{"code":0,"data":{"serverMessageId":"SM-9"}}""")
        assertEquals(TspCommandAck.Accepted("SM-9"), accepted)

        val rejected = TspCommandParser.parseAck("""{"code":4030,"msg":"无车控权限"}""") as TspCommandAck.Rejected
        assertEquals(4030, rejected.code)
        assertEquals("无车控权限", rejected.reason)
        assertEquals(false, rejected.retryable)

        // 网关错误段：结果未知，可重试。
        val gateway = TspCommandParser.parseAck("""{"code":5000,"msg":"busy"}""") as TspCommandAck.TransportFailure
        assertEquals(true, gateway.retryable)

        // code=0 但没消息号：不能假装受理成功。
        val noId = TspCommandParser.parseAck("""{"code":0,"data":{}}""")
        assertTrue(noId is TspCommandAck.TransportFailure)
    }

    @Test
    fun resultParsingCoversAllStates() {
        fun result(stateJson: String): TspCommandResult =
            TspCommandParser.parseResult("""{"code":0,"data":$stateJson}""")

        assertEquals(TspCommandResult.Executed, result("""{"state":"EXECUTED"}"""))
        assertEquals(TspCommandResult.Pending(3_000L), result("""{"state":"PENDING","etaMillis":3000}"""))
        val failed = result("""{"state":"FAILED","failCode":51,"failMsg":"电机未响应"}""")
        assertEquals(TspCommandResult.Failed(51, "电机未响应"), failed)
        // 认不出的 state / 信封错误：一律 Unknown（宁可待确认不误报失败）。
        assertEquals(TspCommandResult.Unknown, result("""{"state":"WHAT"}"""))
        assertEquals(TspCommandResult.Unknown, TspCommandParser.parseResult("""{"code":4004,"msg":"x"}"""))
    }

    @Test
    fun pushEventsParseThreeKinds() {
        val statusJson = """{"type":"STATUS","data":{"vin":"V1","soc":60,"reportTime":1000}}"""
        val status = TspCommandParser.parsePushEvent(statusJson)
        assertNotNull(status)
        status as TspPushEvent.StatusUpdated
        assertEquals(60.0, status.snapshot.stateOfChargePercent ?: error("soc"), 1e-9)

        val commandResult = TspCommandParser.parsePushEvent(
            """{"type":"COMMAND_RESULT","commandId":"c-1","result":{"state":"EXECUTED"}}""",
        )
        assertEquals(TspPushEvent.CommandResultUpdated("c-1", TspCommandResult.Executed), commandResult)

        val alert = TspCommandParser.parsePushEvent(
            """{"type":"ALERT","code":77,"message":"胎压异常","level":"warning"}""",
        )
        assertEquals(TspPushEvent.Alert(77, "胎压异常", AlertLevel.WARNING), alert)

        assertEquals(TspPushEvent.SessionExpired, TspCommandParser.parsePushEvent("""{"type":"SESSION_EXPIRED"}"""))
        // 未知 type → null，推送通道跳过而不是断开。
        assertNull(TspCommandParser.parsePushEvent("""{"type":"FUTURE_THING"}"""))

        // 长轮询批量：坏条目跳过、好条目保留。
        val batch = TspCommandParser.parsePushEvents(
            """[$statusJson, {"type":"GARBAGE"}, {"type":"HEARTBEAT","serverTime":7}]""",
            receivedAtMillis = 8L,
        )
        assertEquals(2, batch.size)
        assertEquals(TspPushEvent.Heartbeat(7L), batch[1])
    }

    @Test
    fun requestBuilderProducesParsableBodyAndEncodedQuery() {
        val req = TspCommandRequest(
            commandId = "c-1",
            vehicleId = "V1",
            actionCode = "DOOR_LOCK",
            parameters = mapOf("source" to "app"),
            issuedAtMillis = 1_700_000_000_000L,
            idempotencyKey = "idem 1+2",
        )
        val body = TspRequestBuilder.commandBody(req)
        val reparsed = com.example.ktapplication.vehicle.data.json.MiniJson.parseObject(body)
        assertEquals("c-1", reparsed.optString("commandId"))
        assertEquals(1_700_000_000_000L, reparsed.optString("issuedAt")?.toLongOrNull())

        // 空格必须编成 %20 而不是 '+'（RFC 3986 网关语义）。
        val query = TspRequestBuilder.queryString("cursor" to "a b+c")
        assertEquals("cursor=a%20b%2Bc", query)

        val push = TspRequestBuilder.pushQuery(cursor = null, timeoutMillis = 25_000, vehicleId = "V1")
        assertTrue(push.contains("wait=25000"))
        assertTrue(push.contains("cursor="))
    }
}
