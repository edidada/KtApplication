package com.example.ktapplication.vehicle.audio

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.example.ktapplication.core.AppDispatchers
import com.example.ktapplication.core.DomainError
import com.example.ktapplication.core.MonotonicClock
import com.example.ktapplication.core.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 车载音频焦点控制器。
 *
 * 手机 App 里"申请焦点失败就直接播"很常见，车机上这是严重问题：
 * 导航没声音、通话里混进音乐都会被判定为事故。所以这里的设计目标是：
 *  1. **申请是 suspend 的且有超时预算**，拿不到焦点不会让调用方协程悬挂；
 *  2. **焦点事件转成 Flow**，播放器和 UI 用同一份事实，不各自监听 listener；
 *  3. **withFocus 保证释放**，页面被杀/协程取消时不会把焦点占死（车机上表现为
 *     "退出音乐后导航再也不播报"，需要重启 CarService 才能恢复）。
 */
class CarAudioFocusController(
    context: Context,
    private val clock: MonotonicClock,
    private val dispatchers: AppDispatchers,
    private val scope: CoroutineScope,
    private val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager,
) {

    private val _focusEvents = MutableSharedFlow<AudioFocusEvent>(
        replay = 1,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val focusEvents: SharedFlow<AudioFocusEvent> = _focusEvents.asSharedFlow()

    private val _heldUsage = MutableStateFlow<AudioUsage?>(null)

    /** 当前持有的焦点用途，UI 用来显示"导航正在播报"角标。 */
    val heldUsage: StateFlow<AudioUsage?> = _heldUsage.asStateFlow()

    private val _duckLevel = MutableStateFlow(1f)

    /** 播放器应把音量乘上的系数（1f 正常，0.25f 表示被 duck）。 */
    val duckLevel: StateFlow<Float> = _duckLevel.asStateFlow()

    private var request: AudioFocusRequest? = null
    private var heldSinceMillis = 0L

    private val listener = AudioManager.OnAudioFocusChangeListener { eventType ->
        val event = mapFocusChange(eventType)
        _focusEvents.tryEmit(event)
        when {
            event is AudioFocusEvent.Granted || event is AudioFocusEvent.Gain -> _duckLevel.value = 1f
            event.shouldDuck -> _duckLevel.value = DUCK_VOLUME_MULTIPLIER
            event.shouldPausePlayback -> _duckLevel.value = 1f
        }
    }

    /**
     * 申请焦点。
     *
     * [budgetMillis] 是等待上限。真机上遇到过的问题：CarAudioService 未就绪时
     * requestAudioFocus 会同步阻塞几百毫秒，不给预算就会让"点击播放"变成 ANR 现场。
     */
    suspend fun requestFocus(
        usage: AudioUsage,
        zone: AudioZone? = null,
        persistent: Boolean = true,
        budgetMillis: Long = 1_500L,
        currentlyHeld: List<ActiveFocus> = emptyList(),
    ): Outcome<FocusRequestResult> {
        val primary = zone?.isPrimary ?: true
        val decision = zone?.let {
            AudioFocusPolicy.decide(usage, currentlyHeld, it, requestingZoneIsPrimary = primary)
        } ?: FocusDecision.Allow
        if (decision is FocusDecision.Reject) {
            return Outcome.failure(DomainError.Rejected(409, "${usage.displayName} 被 ${decision.heldBy.displayName} 占用：${decision.reason}"))
        }

        val result = withTimeout(budgetMillis) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requestModernFocus(usage, persistent, decision)
            } else {
                requestLegacyFocus(usage)
            }
        } ?: return Outcome.failure(DomainError.ResultUnknown("音频焦点申请", budgetMillis))

        when (result) {
            FocusRequestResult.GRANTED, FocusRequestResult.GRANTED_TRANSIENT -> {
                heldSinceMillis = clock.nowMillis()
                _heldUsage.value = usage
                _focusEvents.tryEmit(AudioFocusEvent.Granted)
            }
            FocusRequestResult.REJECTED -> {
                _heldUsage.value = null
                return Outcome.failure(DomainError.Rejected(403, "系统拒绝音频焦点：${usage.displayName}"))
            }
            FocusRequestResult.DELAYED -> {
                // DELAYED 表示"前面的人让给你时会回调"，此时不能立刻播放。
                _heldUsage.value = null
            }
        }
        return Outcome.success(result)
    }

    suspend fun abandonFocus(): Unit {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            request?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
            request = null
        } else {
            runCatching { audioManager.abandonAudioFocus(listener) }
        }
        _heldUsage.value = null
        _duckLevel.value = 1f
        _focusEvents.tryEmit(AudioFocusEvent.Gain)
    }

    /**
     * 作用域式焦点持有。
     *
     * 用法：`controller.withFocus(AudioUsage.NAVIGATION) { player.play() }`。
     * 退出块（含异常与协程取消）一定会 abandonFocus —— 这是防止"焦点泄漏"的唯一可靠手段。
     */
    suspend fun <T> withFocus(
        usage: AudioUsage,
        zone: AudioZone? = null,
        block: suspend () -> T,
    ): Outcome<T> {
        val granted = requestFocus(usage, zone)
        if (granted is Outcome.Failure) return Outcome.failure(granted.error)
        return try {
            Outcome.success(block())
        } finally {
            abandonFocus()
        }
    }

    /** 焦点是否仍然属于本次持有（用于恢复播放前的自检）。 */
    fun stillHolds(usage: AudioUsage): Boolean = _heldUsage.value == usage

    fun heldDurationMillis(): Long = if (_heldUsage.value == null) 0 else clock.nowMillis() - heldSinceMillis

    /**
     * API 26+ 的焦点申请。
     *
     * `AudioFocusRequest.Builder` 与 `requestAudioFocus(AudioFocusRequest)` 都是 API 26 才有的，
     * minSdk 24 必须在 [requestFocus] 里按 SDK_INT 分流到 [requestLegacyFocus]；
     * [androidx.annotation.RequiresApi] 把这个前提写进签名，调用点漏判版本时 lint 会直接报错 ——
     * "忘了分流"在车机上的表现是老导航屏一播语音就崩，是最不该靠测试覆盖来兜的低级错误。
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun requestModernFocus(usage: AudioUsage, persistent: Boolean, decision: FocusDecision): FocusRequestResult {
        val builder = AudioFocusRequest.Builder(focusGainType(usage, decision))
            .setAudioAttributes(audioAttributesOf(usage))
            .setOnAudioFocusChangeListener(listener, android.os.Handler(dispatcherToLooperFallback()))
            .setWillPauseWhenDucked(usage.interruptionBehaviour == InterruptionBehaviour.DUCK)
        if (!persistent) builder.setAcceptsDelayedFocusGain(false)
        val built = builder.build()
        request = built
        return mapResult(audioManager.requestAudioFocus(built))
    }

    @Suppress("DEPRECATION")
    private fun requestLegacyFocus(usage: AudioUsage): FocusRequestResult =
        mapResult(
            audioManager.requestAudioFocus(listener, streamTypeOf(usage), gainTypeOf(usage)),
        )

    private fun dispatcherToLooperFallback(): android.os.Looper = android.os.Looper.getMainLooper()

    private fun audioAttributesOf(usage: AudioUsage): AudioAttributes = AudioAttributes.Builder()
        .setUsage(systemUsageOf(usage))
        .setContentType(contentTypeOf(usage))
        .build()

    private fun focusGainType(usage: AudioUsage, decision: FocusDecision): Int = when {
        decision is FocusDecision.AllowWithDuck || usage.interruptionBehaviour == InterruptionBehaviour.DUCK ->
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        usage == AudioUsage.NAVIGATION || usage == AudioUsage.SYSTEM_PROMPT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        usage == AudioUsage.ALERT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
        else -> AudioManager.AUDIOFOCUS_GAIN
    }

    private fun gainTypeOf(usage: AudioUsage): Int = when (usage) {
        AudioUsage.MEDIA, AudioUsage.RADIO, AudioUsage.AUX, AudioUsage.DTS -> AudioManager.AUDIOFOCUS_GAIN
        else -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
    }

    @Suppress("DEPRECATION")
    private fun streamTypeOf(usage: AudioUsage): Int = when (usage) {
        AudioUsage.PHONE_CALL -> AudioManager.STREAM_VOICE_CALL
        AudioUsage.RINGTONE -> AudioManager.STREAM_RING
        AudioUsage.NAVIGATION, AudioUsage.SYSTEM_PROMPT, AudioUsage.ALERT -> AudioManager.STREAM_NOTIFICATION
        else -> AudioManager.STREAM_MUSIC
    }

    private fun systemUsageOf(usage: AudioUsage): Int = when (usage) {
        AudioUsage.MEDIA, AudioUsage.RADIO, AudioUsage.AUX, AudioUsage.DTS -> AudioAttributes.USAGE_MEDIA
        AudioUsage.PHONE_CALL -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        AudioUsage.RINGTONE -> AudioAttributes.USAGE_NOTIFICATION_RINGTONE
        AudioUsage.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        AudioUsage.ALERT -> AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
        AudioUsage.SYSTEM_PROMPT -> AudioAttributes.USAGE_NOTIFICATION
        // USAGE_ASSISTANT 从 API 26 才有；本项目 minSdk 24，低版本退到 SONIFICATION。
        AudioUsage.VOICE_ASSISTANT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes.USAGE_ASSISTANT
        } else {
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
        }
    }

    private fun contentTypeOf(usage: AudioUsage): Int = when (usage) {
        AudioUsage.PHONE_CALL -> AudioAttributes.CONTENT_TYPE_SPEECH
        AudioUsage.NAVIGATION, AudioUsage.VOICE_ASSISTANT, AudioUsage.SYSTEM_PROMPT -> AudioAttributes.CONTENT_TYPE_SPEECH
        else -> AudioAttributes.CONTENT_TYPE_MUSIC
    }

    private fun mapResult(raw: Int): FocusRequestResult = when (raw) {
        AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> FocusRequestResult.GRANTED
        AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> FocusRequestResult.DELAYED
        AudioManager.AUDIOFOCUS_REQUEST_FAILED -> FocusRequestResult.REJECTED
        else -> FocusRequestResult.REJECTED
    }

    private fun mapFocusChange(change: Int): AudioFocusEvent = when (change) {
        AudioManager.AUDIOFOCUS_GAIN -> AudioFocusEvent.Gain
        // 短暂丢失且允许 duck：导航播报时的音乐处理走这一支。
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> AudioFocusEvent.LostTransient(canDuck = true)
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> AudioFocusEvent.LostTransient(canDuck = false)
        AudioManager.AUDIOFOCUS_LOSS -> AudioFocusEvent.Lost()
        else -> AudioFocusEvent.Lost()
    }

    /**
     * requestAudioFocus 在部分车机上会同步阻塞，这里给一个超时预算。
     * 用 withContext + timeout 包住同步调用是可行的，但会占用一个协程；
     * 音频申请是低频动作，直接 runCatching + 主线程外调度即可。
     */
    private suspend fun <T> withTimeout(millis: Long, block: () -> T): T? =
        kotlinx.coroutines.withTimeoutOrNull(millis) {
            kotlinx.coroutines.withContext(dispatchers.io) { block() }
        }

    /** 车辆下电（ACC OFF）时必须调用：放弃焦点且不再自动恢复。 */
    fun onIgnitionOff() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            request?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        } else {
            runCatching { audioManager.abandonAudioFocus(listener) }
        }
        request = null
        _heldUsage.value = null
        _duckLevel.value = 1f
    }

    companion object {
        /** duck 到 25% 音量，约等于 -12dB，与 AAOS 默认行为接近。 */
        const val DUCK_VOLUME_MULTIPLIER = 0.25f
    }
}
