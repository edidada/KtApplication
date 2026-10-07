package com.example.ktapplication.vehicle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HttpTspClient 纯逻辑部分的单测：超时预算、重试判定、gzip 头。
 * 真实 socket 路径不在 JVM 单测覆盖范围（那属于 instrumentation/集成测试）。
 */
class HttpPolicyTest {

    @Test
    fun commandReadTimeoutRespectsEndToEndBudget() {
        // 15s 预算 - 5s 建连 - 0.5s 握手余量 = 9.5s 读超时（小于默认 10s，被收紧）。
        assertEquals(
            9_500L,
            HttpPolicy.commandReadTimeoutMillis(actionBudgetMillis = 15_000, connectTimeoutMillis = 5_000),
        )
        // 30s 大预算（充电类）：读超时封顶在默认 10s，不无限放大。
        assertEquals(
            10_000L,
            HttpPolicy.commandReadTimeoutMillis(actionBudgetMillis = 30_000, connectTimeoutMillis = 5_000),
        )
        // 预算比建连还短的配置错误：兜底为 1ms 立即超时，而不是 0/负数导致行为未定义。
        assertEquals(
            1L,
            HttpPolicy.commandReadTimeoutMillis(actionBudgetMillis = 3_000, connectTimeoutMillis = 5_000),
        )
    }

    @Test
    fun pushReadTimeoutExceedsHoldWindow() {
        // 长轮询挂起 25s：本地读超时必须再加建连与余量，否则正常 hold 被掐死成超时重连。
        assertEquals(30_500L, HttpPolicy.pushReadTimeoutMillis(holdMillis = 25_000, connectTimeoutMillis = 5_000))
    }

    @Test
    fun retryRulesDistinguishIdempotentGETFromCommandPOST() {
        // GET：传输失败 / 5xx / 429 可重试；其余 4xx 是确定性错误不重试。
        assertTrue(HttpPolicy.shouldRetry("GET", HttpPolicy.HTTP_TRANSPORT_FAILURE, attempt = 0, hasIdempotencyKey = false))
        assertTrue(HttpPolicy.shouldRetry("GET", 502, attempt = 0, hasIdempotencyKey = false))
        assertTrue(HttpPolicy.shouldRetry("GET", 429, attempt = 0, hasIdempotencyKey = false))
        assertFalse(HttpPolicy.shouldRetry("GET", 404, attempt = 0, hasIdempotencyKey = false))
        assertFalse(HttpPolicy.shouldRetry("GET", 400, attempt = 0, hasIdempotencyKey = false))

        // POST 控车：没有幂等键绝不重试 —— 响应丢失时车辆可能已经执行了。
        assertFalse(HttpPolicy.shouldRetry("POST", HttpPolicy.HTTP_TRANSPORT_FAILURE, attempt = 0, hasIdempotencyKey = false))
        assertFalse(HttpPolicy.shouldRetry("POST", 500, attempt = 0, hasIdempotencyKey = false))
        // 带 X-Idempotency-Key 才允许重试（云端可短路去重）。
        assertTrue(HttpPolicy.shouldRetry("POST", HttpPolicy.HTTP_TRANSPORT_FAILURE, attempt = 0, hasIdempotencyKey = true))
        assertTrue(HttpPolicy.shouldRetry("POST", 503, attempt = 0, hasIdempotencyKey = true))

        // 尝试次数封顶：第 3 次（attempt=2，maxAttempts=3）之后不再重试。
        assertTrue(HttpPolicy.shouldRetry("GET", 500, attempt = 1, hasIdempotencyKey = false, maxAttempts = 3))
        assertFalse(HttpPolicy.shouldRetry("GET", 500, attempt = 2, hasIdempotencyKey = false, maxAttempts = 3))

        // 401 不在可重试集合：交给上层的"刷新 token 后重试一次"路径，而非通用退避。
        assertFalse(HttpPolicy.shouldRetry("GET", 401, attempt = 0, hasIdempotencyKey = false))
    }

    @Test
    fun backoffGrowsExponentiallyAndCaps() {
        assertEquals(250L, HttpPolicy.backoffMillis(attempt = 1))
        assertEquals(500L, HttpPolicy.backoffMillis(attempt = 2))
        assertEquals(1_000L, HttpPolicy.backoffMillis(attempt = 3))
        // 封顶：隧道持续拥塞时不能把退避指数推到分钟级。
        assertEquals(4_000L, HttpPolicy.backoffMillis(attempt = 20))
    }

    @Test
    fun gzipHeaderAndSniffing() {
        assertEquals("gzip", HttpPolicy.acceptEncodingHeader())
        assertTrue(HttpPolicy.shouldGunzip("gzip"))
        assertTrue(HttpPolicy.shouldGunzip("GZIP, identity"))
        assertFalse(HttpPolicy.shouldGunzip(null))
        assertFalse(HttpPolicy.shouldGunzip("identity"))
    }
}
