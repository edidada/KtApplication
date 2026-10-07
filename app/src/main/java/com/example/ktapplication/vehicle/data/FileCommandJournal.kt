package com.example.ktapplication.vehicle.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * 落盘版离线指令队列。
 *
 * 存储选型说明（为什么是"整文件重写 + rename"而不是 append）：
 *  - 车机/手机随时可能掉电（车辆下电、系统被 LMK 回收、OTA 重启），
 *    **就地 append 后截断**（写一半失败再 truncate 回滚）在掉电窗口里会留下
 *    半行 JSON；本格式一行一条、前一条损坏会连带 parse 后续内容错位，
 *    整个队列就废了 —— 队列废了意味着用户按过的锁车指令凭空消失。
 *  - 正确做法：全部写进同目录临时文件 → fsync 语义交给 rename →
 *    `File.renameTo` 在同一文件系统内是原子替换，旧文件要么完整存在
 *    要么已被完整的新文件替换，不存在"半个新文件"的状态。
 *  - 不用 SQLite：零依赖约束 + 队列只有几十条，整文件读写的成本可以忽略，
 *    反而少一层崩溃面（车机闪存上 SQLite WAL 掉电损坏并不罕见）。
 *
 * 构造函数只收 [File]，不依赖 Context：单测直接传 TemporaryFolder，
 * 生产由 DI 层传 `context.filesDir`。
 */
class FileCommandJournal(
    directory: File,
    private val fileName: String = DEFAULT_FILE_NAME,
) : CommandJournal {

    private val directory = directory.also { it.mkdirs() }
    private val file = File(directory, fileName)
    private val tmpFile = File(directory, "$fileName.tmp")

    /** 写读互斥：冲刷协程和 UI 入队协程并发操作同一文件时，"读-改-写"必须整体串行。 */
    private val mutex = Mutex()

    /** 最近一次读取时跳过的损坏行数，诊断页展示用。 */
    @Volatile
    var corruptedLineCount: Int = 0
        private set

    private companion object {
        const val DEFAULT_FILE_NAME = "pending_commands.jsonl"
    }

    override suspend fun pending(): List<TspCommandRequest> = mutex.withLock { load() }

    /**
     * 入队。幂等去重按 **idempotencyKey**（不是 commandId）且**保留最早一条**：
     * 用户连点三次解锁会生成三个 commandId 但同一个幂等键（同一分钟分桶），
     * 重启冲刷时只应该真发一条，而且以第一次点击的时间为准。
     */
    override suspend fun enqueue(request: TspCommandRequest): Boolean = mutex.withLock {
        val existing = load()
        if (existing.any { it.idempotencyKey == request.idempotencyKey }) return@withLock false
        persist(existing + request)
        true
    }

    /** 只移除队首匹配项：同 commandId 理论上唯一，防御性也只删一条。 */
    override suspend fun complete(commandId: String): Unit = mutex.withLock {
        val remaining = dropFirst(load(), commandId)
        persist(remaining)
    }

    override suspend fun discard(commandId: String, reason: String): Unit = mutex.withLock {
        val remaining = dropFirst(load(), commandId)
        persist(remaining)
        // reason 目前只用于上层日志；落盘"死信"是后续迭代的事，不提前造概念。
    }

    override suspend fun clear(): Unit = mutex.withLock {
        corruptedLineCount = 0
        if (file.exists()) file.delete()
    }

    override suspend fun size(): Int = mutex.withLock { load().size }

    // ---- 内部 ----

    /** 进程重启后 pending() 能恢复：每次都从磁盘读，不缓存内存副本，避免双副本不一致。 */
    private fun load(): List<TspCommandRequest> {
        if (!file.exists()) {
            corruptedLineCount = 0
            return emptyList()
        }
        val decoded = ArrayList<TspCommandRequest>()
        var corrupted = 0
        file.useLines { lines ->
            lines.forEach { line ->
                if (line.isBlank()) return@forEach
                val req = JournalCodec.decode(line)
                if (req == null) {
                    // 损坏行跳过并计数：一行坏不能连坐整个队列。
                    corrupted += 1
                } else {
                    decoded += req
                }
            }
        }
        corruptedLineCount = corrupted
        // 读取路径也要做去重折叠：万一历史上写入被中断产生过重复行，恢复时语义不变。
        return dedupeKeepingEarliest(decoded)
    }

    private fun persist(entries: List<TspCommandRequest>) {
        tmpFile.bufferedWriter().use { writer ->
            entries.forEach { entry ->
                writer.write(JournalCodec.encode(entry))
                writer.write("\n")
            }
            writer.flush()
        }
        // renameTo 失败的主要场景是目标被其他进程占用：先删再试一次，仍失败才抛。
        if (!tmpFile.renameTo(file)) {
            file.delete()
            if (!tmpFile.renameTo(file)) {
                tmpFile.delete()
                throw IllegalStateException("指令队列落盘失败：${file.absolutePath}")
            }
        }
    }

    private fun dropFirst(entries: List<TspCommandRequest>, commandId: String): List<TspCommandRequest> {
        val index = entries.indexOfFirst { it.commandId == commandId }
        return if (index < 0) entries else entries.toMutableList().apply { removeAt(index) }
    }

    private fun dedupeKeepingEarliest(entries: List<TspCommandRequest>): List<TspCommandRequest> {
        val seen = HashSet<String>()
        return entries.filter { seen.add(it.idempotencyKey) }
    }
}

/**
 * 轻量内存队列：单测与 Compose 预览专用。
 *
 * 与 [FileCommandJournal] 保持完全相同的去重/顺序语义，让"接了 repository 的逻辑
 * 在内存和落盘两种装配下行为一致"。不实现落盘 —— 预览进程死了就死了。
 */
class InMemoryCommandJournal : CommandJournal {

    private val entries = ArrayList<TspCommandRequest>()

    private val mutex = Mutex()

    override suspend fun pending(): List<TspCommandRequest> = mutex.withLock { ArrayList(entries) }

    override suspend fun enqueue(request: TspCommandRequest): Boolean = mutex.withLock {
        if (entries.any { it.idempotencyKey == request.idempotencyKey }) return@withLock false
        entries.add(request)
        true
    }

    override suspend fun complete(commandId: String): Unit = mutex.withLock {
        val index = entries.indexOfFirst { it.commandId == commandId }
        if (index >= 0) entries.removeAt(index)
    }

    override suspend fun discard(commandId: String, reason: String): Unit = complete(commandId)

    override suspend fun clear(): Unit = mutex.withLock { entries.clear() }

    override suspend fun size(): Int = mutex.withLock { entries.size }
}
