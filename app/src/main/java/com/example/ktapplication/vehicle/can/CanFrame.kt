package com.example.ktapplication.vehicle.can

/**
 * CAN 总线域。
 *
 * 真车按功能分区，不同域之间通过网关转发，延迟和刷新率都不一样。
 * 座舱 App 拿到的帧一定带域信息，否则无法解释"为什么车速帧比空调帧新"。
 */
enum class CanBus(val displayName: String, val nominalBitrateKbps: Int) {
    POWERTRAIN("动力域", 500),
    CHASSIS("底盘域", 500),
    BODY("车身域", 250),
    INFOTAINMENT("座舱域", 500),
    DIAGNOSTIC("诊断域", 500),
}

/**
 * 一帧原始 CAN 报文。
 *
 * 这里是"未解码"的最小单元：只有 ID、DLC 和 8 字节 payload。
 * 把原始帧和物理信号分成两层是刻意的 —— 解码规则来自 DBC 文件，会随车型变化，
 * 而抓帧、回放、丢帧统计这些能力都只依赖原始帧，可以跨车型复用。
 */
data class CanFrame(
    val bus: CanBus,
    /** 报文标识符：标准帧 11bit（0..0x7FF），扩展帧 29bit。 */
    val id: Int,
    /** Data Length Code，经典 CAN 为 0..8；CAN FD 最大 64。 */
    val dlc: Int,
    val data: ByteArray,
    /** 收帧时刻，单调时钟毫秒；用于超时判定和刷新率统计。 */
    val receivedAtMillis: Long,
    val isExtendedId: Boolean = false,
    val isRemoteRequestFrame: Boolean = false,
) {
    init {
        require(dlc in 0..MAX_CLASSIC_DLC) { "经典 CAN 的 DLC 必须在 0..8，收到 $dlc" }
        require(data.size >= dlc) { "payload 长度 ${data.size} 小于 DLC $dlc" }
        require(isExtendedId || id in 0x000..0x7FF) { "标准帧 ID 越界：0x${id.toString(16)}" }
        require(!isRemoteRequestFrame || dlc == 0) { "远程帧不携带数据" }
    }

    val isTruncated: Boolean get() = data.size > dlc

    fun byteAt(index: Int): Int = data[index].toInt() and 0xFF

    override fun equals(other: Any?): Boolean = other is CanFrame &&
        other.bus == bus && other.id == id && other.dlc == dlc &&
        other.receivedAtMillis == receivedAtMillis && other.data.contentEquals(data)

    override fun hashCode(): Int {
        var result = bus.hashCode()
        result = 31 * result + id
        result = 31 * result + dlc
        result = 31 * result + data.contentHashCode()
        result = 31 * result + receivedAtMillis.hashCode()
        return result
    }

    companion object {
        const val MAX_CLASSIC_DLC: Int = 8

        /** 便于手写测试报文：hex 字符串形如 "0A 1B 2C"。 */
        fun of(
            bus: CanBus,
            id: Int,
            hexBytes: String,
            receivedAtMillis: Long = 0L,
            isExtendedId: Boolean = false,
        ): CanFrame {
            val bytes = hexBytes.trim()
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
                .map { it.toInt(radix = 16).toByte() }
                .toByteArray()
            return CanFrame(
                bus = bus,
                id = id,
                dlc = bytes.size,
                data = bytes,
                receivedAtMillis = receivedAtMillis,
                isExtendedId = isExtendedId,
            )
        }
    }
}

/**
 * 总线健康度：车载 App 稳定性排查里最先看的东西之一。
 *
 * 现象"车速表卡住"通常是三种原因之一：BusOff（总线关断）、丢帧、信号本身进入
 * 超时状态（DBC 里的 timeout 计数），这里把三者分开统计，方便定位是链路问题还是整车问题。
 */
data class BusHealth(
    val bus: CanBus,
    val framesReceived: Long,
    val framesDropped: Long,
    val signalsTimedOut: Int,
    val lastFrameAtMillis: Long?,
) {
    val dropRatePercent: Double
        get() {
            val total = framesReceived + framesDropped
            return if (total == 0L) 0.0 else framesDropped * 100.0 / total
        }

    /** 长时间收不到帧说明网关/串口/CAN 控制器这一层有问题，而不是某个信号的问题。 */
    fun isSilent(nowMillis: Long, thresholdMillis: Long = 2_000): Boolean =
        lastFrameAtMillis == null || nowMillis - lastFrameAtMillis > thresholdMillis
}
