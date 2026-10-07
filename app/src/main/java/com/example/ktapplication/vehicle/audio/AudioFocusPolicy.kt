package com.example.ktapplication.vehicle.audio

/**
 * 车载音频模型与焦点仲裁规则。
 *
 * 车机音频和手机最大的差别是**多音区 + 强制优先级**：
 *  - 手机上"谁最后申请谁拿焦点"，车机上导航播报必须能压住音乐、来电必须能压住导航，
 *    这套优先级由 OEM 在 `car_audio_configuration.xml` 里定义，App 只能遵守；
 *  - 一次 ACC OFF 之后所有音频都要停，所以焦点申请必须可撤销、可超时；
 *  - 后排音区（headset）不能打断前排导航，除非配置了 INTERRUPT_ALL。
 *
 * 仲裁逻辑写成纯函数（[AudioFocusPolicy]）是因为它在真机上几乎无法复现全组合，
 * 而它的正确性直接决定"开车时导航没声音"这类严重问题。
 */

/** 对齐 AOSP 的 CarAudioDescription usage 概念。 */
enum class AudioUsage(
    val displayName: String,
    /** 数值越大优先级越高，与 OEM 配置里的 priority 同源。 */
    val priority: Int,
    /** 被更高优先级打断时是 duck（降音量）还是 pause（停播放）。 */
    val interruptionBehaviour: InterruptionBehaviour,
) {
    MEDIA("多媒体", 20, InterruptionBehaviour.DUCK),
    NAVIGATION("导航播报", 60, InterruptionBehaviour.PAUSE_LOWER),
    PHONE_CALL("蓝牙电话", 90, InterruptionBehaviour.PAUSE_ALL),
    RINGTONE("来电铃声", 80, InterruptionBehaviour.PAUSE_LOWER),
    ALERT("告警音（碰撞/未系安全带）", 100, InterruptionBehaviour.NEVER_INTERRUPTIBLE),
    DTS("影院/DTS 音效", 25, InterruptionBehaviour.DUCK),
    SYSTEM_PROMPT("系统提示音", 70, InterruptionBehaviour.PAUSE_LOWER),
    VOICE_ASSISTANT("语音助手", 65, InterruptionBehaviour.PAUSE_LOWER),
    RADIO("FM/AM 广播", 20, InterruptionBehaviour.DUCK),
    AUX("AUX 输入", 20, InterruptionBehaviour.DUCK),
}

enum class InterruptionBehaviour {
    /** 降音量继续播（导航播报结束后自动回升）。 */
    DUCK,

    /** 暂停比自己低优先级的源。 */
    PAUSE_LOWER,

    /** 暂停除告警外的所有源。 */
    PAUSE_ALL,

    /** 不可被打断（安全告警）。 */
    NEVER_INTERRUPTIBLE,
}

/** 焦点申请结果，语义对齐 AudioManager 的三个常量。 */
enum class FocusRequestResult { GRANTED, GRANTED_TRANSIENT, DELAYED, REJECTED }

/** 焦点变化事件。 */
sealed class AudioFocusEvent {
    data object Granted : AudioFocusEvent()

    /** 短暂丢失，可 duck 或暂停后自动恢复（导航播报、来电）。 */
    data class LostTransient(val canDuck: Boolean) : AudioFocusEvent()

    /** 长时间丢失（另一个 App 开始播放），应暂停并释放资源。 */
    data class Lost(val wasCanDuck: Boolean = false) : AudioFocusEvent()

    /** 永久丢失（用户在其他 App 里明确停止），不应再自动恢复。 */
    data object LostExtended : AudioFocusEvent()

    /** 短暂丢失结束，恢复。 */
    data object Gain : AudioFocusEvent()

    val shouldPausePlayback: Boolean
        get() = this is Lost || this is LostExtended || (this is LostTransient && !canDuck)

    val shouldDuck: Boolean get() = this is LostTransient && canDuck

    val shouldRestore: Boolean get() = this is Gain || (this is LostTransient && canDuck)
}

/** 焦点仲裁结论。 */
sealed class FocusDecision {
    data object Allow : FocusDecision()

    /** 允许但需要把当前 [AudioUsage] 降音量。 */
    data class AllowWithDuck(val attenuateDb: Float) : FocusDecision()

    /** 拒绝，并给出需要让位的占用者，便于 UI 提示"正在通话中"。 */
    data class Reject(val heldBy: AudioUsage, val reason: String) : FocusDecision()
}

/** 单个音区（前排/后排/头枕）。 */
data class AudioZone(
    val id: Int,
    val displayName: String,
    val usageUsable: Set<AudioUsage>,
    /** 主音区可以打断从音区，反之不行。 */
    val isPrimary: Boolean,
)

data class ActiveFocus(val usage: AudioUsage, val zoneId: Int, val packageName: String, val sinceMillis: Long)

/**
 * 纯函数仲裁器。
 *
 * 规则来源是 AAOS 的 `CarAudioZone` + `AudioFocusService` 行为：
 *  - 同级 usage 互相抢占（换了个音乐 App 播歌，旧的要停）；
 *  - 高优先级只 duck 不 pause 那些支持 duck 的源；
 *  - ALERT 一旦申请，任何人都不能抢；
 *  - 从音区不能打断主音区。
 */
object AudioFocusPolicy {

    /** 典型导航播报的压低幅度：AAOS 默认 -14dB 左右，人声能听清又不完全静音。 */
    const val DEFAULT_DUCK_ATTENUATION_DB: Float = -14f

    fun decide(
        request: AudioUsage,
        currentlyHeld: List<ActiveFocus>,
        zone: AudioZone,
        requestingZoneIsPrimary: Boolean,
    ): FocusDecision {
        if (!zone.usageUsable.contains(request)) {
            return FocusDecision.Reject(zone.usageUsable.maxByOrNull { it.priority } ?: AudioUsage.MEDIA, "音区 ${zone.displayName} 不支持 ${request.displayName}")
        }
        val holders = currentlyHeld.filter { it.zoneId == zone.id || (zone.isPrimary && it.zoneId != zone.id && requestingZoneIsPrimary) }

        // 安全告警是不可抢占的：任何人想打断 ALERT 都被拒绝。
        holders.firstOrNull { it.usage == AudioUsage.ALERT }?.let {
            return FocusDecision.Reject(it.usage, "安全告警播放中，不可打断")
        }

        val highestHeld = holders.maxOfOrNull { it.usage.priority } ?: return FocusDecision.Allow
        val holder = holders.firstOrNull { it.usage.priority == highestHeld } ?: return FocusDecision.Allow

        return when {
            request.priority > holder.usage.priority ->
                if (holder.usage.interruptionBehaviour == InterruptionBehaviour.DUCK) {
                    FocusDecision.AllowWithDuck(DEFAULT_DUCK_ATTENUATION_DB)
                } else {
                    FocusDecision.Allow
                }
            // 同级：允许，但要求对方停（换播放器/同一 usage 竞争）。
            request.priority == holder.usage.priority -> FocusDecision.AllowWithDuck(DEFAULT_DUCK_ATTENUATION_DB)
            // 非主音区不能压主音区。
            !zone.isPrimary && !requestingZoneIsPrimary -> FocusDecision.Reject(holder.usage, "后排音区不能打断前排播放")
            else -> FocusDecision.Reject(holder.usage, "${holder.usage.displayName} 优先级更高")
        }
    }

    /**
     * 焦点丢失后是否允许自动恢复。
     *
     * 车机特有的坑：ACC OFF 时系统会给一堆 LOSS，如果不区分"车辆下电"和"别人抢了焦点"，
     * App 会在下次上电时突然把音量拉回并自动播放，这是真实投诉点。
     * [ignitionOn] 为 false 时必须彻底放弃恢复。
     */
    fun shouldAutoRestore(event: AudioFocusEvent, ignitionOn: Boolean, heldForMillis: Long, minHoldMillis: Long = 3_000L): Boolean {
        if (!ignitionOn) return false
        return when (event) {
            is AudioFocusEvent.Gain -> true
            is AudioFocusEvent.LostTransient -> heldForMillis >= minHoldMillis
            else -> false
        }
    }

    /** 请求超时后不该继续持有焦点（避免上一个页面的申请还挂着）。 */
    fun shouldReleaseAfter(request: AudioUsage, elapsedMillis: Long, budgetMillis: Long): Boolean =
        elapsedMillis > budgetMillis && request != AudioUsage.ALERT
}
