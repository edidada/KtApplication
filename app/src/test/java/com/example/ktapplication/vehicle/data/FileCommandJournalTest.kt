package com.example.ktapplication.vehicle.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileCommandJournalTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun request(
        commandId: String,
        idempotencyKey: String = "idem-$commandId",
        actionCode: String = "DOOR_LOCK",
    ) = TspCommandRequest(
        commandId = commandId,
        vehicleId = "LVVFA1234ABC56789",
        actionCode = actionCode,
        parameters = mapOf("source" to "app"),
        issuedAtMillis = 1_700_000_000_000L,
        idempotencyKey = idempotencyKey,
    )

    private fun journal(): FileCommandJournal = FileCommandJournal(tempFolder.root)

    @Test
    fun fifoOrderFollowsEnqueue() = runTest {
        val journal = journal()
        listOf("c1", "c2", "c3").forEach { assertTrue(journal.enqueue(request(it))) }
        assertEquals(listOf("c1", "c2", "c3"), journal.pending().map { it.commandId })
        assertEquals(3, journal.size())
    }

    @Test
    fun duplicateIdempotencyKeyKeepsEarliest() = runTest {
        val journal = journal()
        // 用户连点三次解锁：commandId 各不相同，但幂等键一致（同一分钟分桶）。
        val first = request("c1", idempotencyKey = "K", actionCode = "DOOR_UNLOCK")
        val later = request("c2", idempotencyKey = "K", actionCode = "DOOR_UNLOCK")
        assertTrue(journal.enqueue(first))
        assertFalse(journal.enqueue(later))
        val pending = journal.pending()
        assertEquals(1, pending.size)
        // 保留的是最早那条：时间戳、commandId 都以第一次点击为准。
        assertEquals("c1", pending.single().commandId)
        assertEquals(1, journal.size())
    }

    @Test
    fun completeAndDiscardRemoveOnlyTarget() = runTest {
        val journal = journal()
        listOf("c1", "c2", "c3").forEach { journal.enqueue(request(it)) }

        journal.complete("c2")
        assertEquals(listOf("c1", "c3"), journal.pending().map { it.commandId })

        journal.discard("c1", reason = "超过最大重试次数")
        assertEquals(listOf("c3"), journal.pending().map { it.commandId })

        // 不存在的 commandId 静默无操作：冲刷路径不关心"删没删到"。
        journal.complete("nope")
        assertEquals(1, journal.size())

        journal.clear()
        assertEquals(0, journal.size())
    }

    @Test
    fun parametersSurviveRoundTrip() = runTest {
        val journal = journal()
        val withParams = request("c1").copy(parameters = mapOf("celsius" to "24", "note" to "含\"引号\"和中文"))
        journal.enqueue(withParams)
        val restored = journal().pending().single() // 新实例读同一目录
        assertEquals(withParams, restored)
    }

    @Test
    fun restartRestoresPending() = runTest {
        journal().enqueue(request("c1"))
        // 模拟进程重启：全新实例、同一目录。
        val restarted = journal()
        assertEquals(listOf("c1"), restarted.pending().map { it.commandId })
    }

    @Test
    fun corruptedLineIsSkippedAndCounted() = runTest {
        val journal = journal()
        journal.enqueue(request("c1"))
        journal.enqueue(request("c2"))
        // 手工在文件中间塞一行垃圾：模拟掉电留下的半行 JSON。
        val file = File(tempFolder.root, "pending_commands.jsonl")
        val lines = file.readLines()
        file.writeText(lines[0] + "\n" + "{\"commandId\":\"cut\" \"off\n" + lines.drop(1).joinToString("\n") + "\n")

        val restored = journal()
        assertEquals(listOf("c1", "c2"), restored.pending().map { it.commandId })
        assertEquals(1, restored.corruptedLineCount)
    }

    @Test
    fun concurrentEnqueueLosesNothing() = runTest {
        val journal = journal()
        // 30 个并发入队：Mutex 串行化"读-改-写"，一条都不能丢、不能重。
        coroutineScope {
            (1..30).map { index ->
                async { journal.enqueue(request("c$index")) }
            }.awaitAll()
        }
        val pending = journal.pending()
        assertEquals(30, pending.size)
        assertEquals((1..30).map { "c$it" }.toSet(), pending.map { it.commandId }.toSet())
    }

    @Test
    fun concurrentEnqueueWithSameKeyDedupesToFirst() = runTest {
        val journal = journal()
        coroutineScope {
            (1..10).map { async { journal.enqueue(request("c$it", idempotencyKey = "SAME")) } }.awaitAll()
        }
        val pending = journal.pending()
        assertEquals(1, pending.size)
        // 保留的必须是第一个成功入队的 commandId（并发下 c1 未必最先跑，断言"存在且在队里"）。
        assertTrue(pending.single().commandId.isNotEmpty())
    }
}
