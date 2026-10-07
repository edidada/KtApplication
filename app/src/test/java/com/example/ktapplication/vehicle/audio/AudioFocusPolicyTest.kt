package com.example.ktapplication.vehicle.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音频焦点仲裁的 JVM 单测。
 *
 * 这块逻辑在真机上几乎无法穷举（要同时凑出"有人在打电话 + 导航在播报 + 后排在看视频"
 * 这类组合），但它错了就是"开车时导航没声音"这种严重问题，所以全部组合在这里覆盖。
 * 断言用的是**规则本身**（谁该被压低、谁该被拒），不是实现返回值。
 */
class AudioFocusPolicyTest {

    private val now = 100_000L

    /** 前排主音区：OEM 配置里几乎不给前排裁剪 usage，整表放开便于覆盖仲裁分支。 */
    private val frontZone = AudioZone(
        id = 1,
        displayName = "前排",
        usageUsable = AudioUsage.entries.toSet(),
        isPrimary = true,
    )

    /** 后排从音区：没有导航播报与告警，只有娱乐和电话。 */
    private val rearZone = AudioZone(
        id = 2,
        displayName = "后排",
        usageUsable = setOf(AudioUsage.MEDIA, AudioUsage.RADIO, AudioUsage.AUX, AudioUsage.PHONE_CALL),
        isPrimary = false,
    )

    private fun held(usage: AudioUsage, zoneId: Int = 1) =
        ActiveFocus(usage, zoneId, "com.example.other.hud", now - 5_000L)

    private fun decide(
        request: AudioUsage,
        vararg currentlyHeld: ActiveFocus,
        zone: AudioZone = frontZone,
        requestingZoneIsPrimary: Boolean = true,
    ): FocusDecision = AudioFocusPolicy.decide(request, currentlyHeld.toList(), zone, requestingZoneIsPrimary)

    private fun FocusDecision.expectDuck(): FocusDecision.AllowWithDuck =
        this as? FocusDecision.AllowWithDuck ?: throw AssertionError("期望 AllowWithDuck，实际=$this")

    private fun FocusDecision.expectReject(): FocusDecision.Reject =
        this as? FocusDecision.Reject ?: throw AssertionError("期望 Reject，实际=$this")

    // ---------- 优先级表本身 ----------

    @Test
    fun `优先级严格按安全等级排列`() {
        assertTrue(AudioUsage.ALERT.priority > AudioUsage.PHONE_CALL.priority)
        assertTrue(AudioUsage.PHONE_CALL.priority > AudioUsage.RINGTONE.priority)
        assertTrue(AudioUsage.RINGTONE.priority > AudioUsage.SYSTEM_PROMPT.priority)
        assertTrue(AudioUsage.SYSTEM_PROMPT.priority > AudioUsage.VOICE_ASSISTANT.priority)
        assertTrue(AudioUsage.VOICE_ASSISTANT.priority > AudioUsage.NAVIGATION.priority)
        assertTrue(AudioUsage.NAVIGATION.priority > AudioUsage.DTS.priority)
        // 娱乐类三兄弟同级：换音源时靠"同级抢占"而不是优先级。
        assertEquals(20, AudioUsage.MEDIA.priority)
        assertEquals(20, AudioUsage.RADIO.priority)
        assertEquals(20, AudioUsage.AUX.priority)
        assertEquals(100, AudioUsage.ALERT.priority)
        assertEquals(60, AudioUsage.NAVIGATION.priority)
    }

    @Test
    fun `打断行为与 usage 绑定`() {
        assertEquals(InterruptionBehaviour.DUCK, AudioUsage.MEDIA.interruptionBehaviour)
        assertEquals(InterruptionBehaviour.DUCK, AudioUsage.RADIO.interruptionBehaviour)
        assertEquals(InterruptionBehaviour.PAUSE_LOWER, AudioUsage.NAVIGATION.interruptionBehaviour)
        assertEquals(InterruptionBehaviour.PAUSE_ALL, AudioUsage.PHONE_CALL.interruptionBehaviour)
        assertEquals(InterruptionBehaviour.NEVER_INTERRUPTIBLE, AudioUsage.ALERT.interruptionBehaviour)
    }

    // ---------- 仲裁：允许 ----------

    @Test
    fun `空闲音区直接授予焦点`() {
        assertEquals(FocusDecision.Allow, decide(AudioUsage.NAVIGATION))
        assertEquals(FocusDecision.Allow, decide(AudioUsage.ALERT))
    }

    @Test
    fun `导航播报把音乐压低而不是静音`() {
        val duck = decide(AudioUsage.NAVIGATION, held(AudioUsage.MEDIA)).expectDuck()
        // -14dB：AAOS 默认压低幅度，人声能听清又不完全切断音乐。
        assertEquals(-14.0, duck.attenuateDb.toDouble(), 0.0001)
        assertEquals(AudioFocusPolicy.DEFAULT_DUCK_ATTENUATION_DB, duck.attenuateDb)
        // 换算成播放器侧的实际音量倍数：10^(-14/20) = 0.1995 倍。
        // 别记成 0.25 —— 那是 -12dB；把 dB 和线性倍数搞混，duck 出来要么太轻要么直接把音乐干没了。
        assertEquals(0.1995, dbToLinear(duck.attenuateDb).toDouble(), 0.0005)
    }

    @Test
    fun `告警音把音乐压低（音乐是可 duck 的源）`() {
        decide(AudioUsage.ALERT, held(AudioUsage.MEDIA)).expectDuck()
    }

    @Test
    fun `电话打断导航时直接放行（导航不可 duck 只能暂停）`() {
        // NAVIGATION 的行为是 PAUSE_LOWER，无法 duck，所以结论是 Allow 而非 AllowWithDuck。
        assertEquals(FocusDecision.Allow, decide(AudioUsage.PHONE_CALL, held(AudioUsage.NAVIGATION)))
    }

    @Test
    fun `同级音源互相抢占：新的赢但旧的只降音量`() {
        val duck = decide(AudioUsage.MEDIA, held(AudioUsage.RADIO)).expectDuck()
        assertEquals(AudioFocusPolicy.DEFAULT_DUCK_ATTENUATION_DB, duck.attenuateDb)
        assertEquals(FocusDecision.AllowWithDuck(AudioFocusPolicy.DEFAULT_DUCK_ATTENUATION_DB), decide(AudioUsage.AUX, held(AudioUsage.MEDIA)))
    }

    // ---------- 仲裁：拒绝 ----------

    @Test
    fun `安全告警播放期间任何人都不能抢焦点`() {
        val reject = decide(AudioUsage.NAVIGATION, held(AudioUsage.ALERT)).expectReject()
        assertEquals(AudioUsage.ALERT, reject.heldBy)
        assertEquals("安全告警播放中，不可打断", reject.reason)
        // 连电话也不能打断告警 —— 这条在实车上是硬要求。
        decide(AudioUsage.PHONE_CALL, held(AudioUsage.ALERT)).expectReject()
        // 只要占用者不是告警本身，最高优先级照样放行（铃声是 PAUSE_LOWER，直接被暂停）。
        assertEquals(FocusDecision.Allow, decide(AudioUsage.ALERT, held(AudioUsage.RINGTONE)))
    }

    @Test
    fun `低优先级请求被拒并告诉 UI 是谁占着`() {
        val navHeld = decide(AudioUsage.MEDIA, held(AudioUsage.NAVIGATION)).expectReject()
        assertEquals(AudioUsage.NAVIGATION, navHeld.heldBy)
        assertEquals("导航播报 优先级更高", navHeld.reason)

        val inCall = decide(AudioUsage.SYSTEM_PROMPT, held(AudioUsage.PHONE_CALL)).expectReject()
        assertEquals("蓝牙电话 优先级更高", inCall.reason)
    }

    @Test
    fun `音区不支持的 usage 一律拒绝`() {
        // 后排没有导航声道：播报必须落到前排，App 不能"自己找一个能响的地方"。
        val reject = decide(AudioUsage.NAVIGATION, zone = rearZone).expectReject()
        assertEquals("音区 后排 不支持 导航播报", reject.reason)
        // Reject.heldBy 取该音区里优先级最高的 usage，用于"正在通话中"这类提示。
        assertEquals(AudioUsage.PHONE_CALL, reject.heldBy)
    }

    @Test
    fun `后排不能打断前排播放`() {
        val reject = decide(
            AudioUsage.MEDIA,
            held(AudioUsage.NAVIGATION, zoneId = 2),
            zone = rearZone,
            requestingZoneIsPrimary = false,
        ).expectReject()
        assertEquals("后排音区不能打断前排播放", reject.reason)
    }

    @Test
    fun `后排申请只影响自己的音区`() {
        // 前排正在放音乐，后排放自己的视频：不该被前排的 20 级占用者挡住。
        assertEquals(
            FocusDecision.Allow,
            decide(AudioUsage.MEDIA, held(AudioUsage.MEDIA, zoneId = 1), zone = rearZone, requestingZoneIsPrimary = false),
        )
    }

    @Test
    fun `主音区申请能看到其他音区的占用者`() {
        // 前排（主音区）想放音乐，而后排占着电话：主音区可跨音区仲裁，电话优先级更高 → 拒绝。
        val reject = decide(
            AudioUsage.MEDIA,
            held(AudioUsage.PHONE_CALL, zoneId = 2),
            zone = frontZone,
            requestingZoneIsPrimary = true,
        ).expectReject()
        assertEquals(AudioUsage.PHONE_CALL, reject.heldBy)
    }

    // ---------- 焦点丢失后的恢复 ----------

    @Test
    fun `重新获得焦点可以自动恢复`() {
        assertTrue(AudioFocusPolicy.shouldAutoRestore(AudioFocusEvent.Gain, ignitionOn = true, heldForMillis = 0L))
    }

    @Test
    fun `短暂丢失且允许 duck 时达到最短持有时间才恢复`() {
        val transient = AudioFocusEvent.LostTransient(canDuck = true)
        assertFalse(AudioFocusPolicy.shouldAutoRestore(transient, ignitionOn = true, heldForMillis = 2_999L))
        assertTrue(AudioFocusPolicy.shouldAutoRestore(transient, ignitionOn = true, heldForMillis = 3_000L))
        assertTrue(AudioFocusPolicy.shouldAutoRestore(transient, ignitionOn = true, heldForMillis = 8_000L, minHoldMillis = 5_000L))
    }

    @Test
    fun `长时间或永久丢失不自动恢复`() {
        assertFalse(AudioFocusPolicy.shouldAutoRestore(AudioFocusEvent.Lost(), ignitionOn = true, heldForMillis = 60_000L))
        assertFalse(AudioFocusPolicy.shouldAutoRestore(AudioFocusEvent.LostExtended, ignitionOn = true, heldForMillis = 60_000L))
    }

    @Test
    fun `车辆下电后任何事件都不允许自动恢复播放`() {
        // 真实投诉点：ACC OFF 收到一堆 LOSS，下次上电突然把音量拉回自动播放。
        assertFalse(AudioFocusPolicy.shouldAutoRestore(AudioFocusEvent.Gain, ignitionOn = false, heldForMillis = 0L))
        assertFalse(AudioFocusPolicy.shouldAutoRestore(AudioFocusEvent.LostTransient(canDuck = true), ignitionOn = false, heldForMillis = 60_000L))
    }

    @Test
    fun `超出预算才释放焦点且告警永不自动释放`() {
        assertTrue(AudioFocusPolicy.shouldReleaseAfter(AudioUsage.MEDIA, elapsedMillis = 10_000L, budgetMillis = 5_000L))
        assertFalse(AudioFocusPolicy.shouldReleaseAfter(AudioUsage.MEDIA, elapsedMillis = 5_000L, budgetMillis = 5_000L))
        assertFalse(AudioFocusPolicy.shouldReleaseAfter(AudioUsage.ALERT, elapsedMillis = 1_000_000L, budgetMillis = 5_000L))
    }

    // ---------- 事件到播放动作的映射 ----------

    @Test
    fun `事件语义映射到暂停或降音量`() {
        val duckable = AudioFocusEvent.LostTransient(canDuck = true)
        assertTrue(duckable.shouldDuck)
        assertTrue(duckable.shouldRestore)
        assertFalse(duckable.shouldPausePlayback)

        val pausable = AudioFocusEvent.LostTransient(canDuck = false)
        assertFalse(pausable.shouldDuck)
        assertFalse(pausable.shouldRestore)
        assertTrue(pausable.shouldPausePlayback)

        assertTrue(AudioFocusEvent.Lost(wasCanDuck = true).shouldPausePlayback)
        assertTrue(AudioFocusEvent.LostExtended.shouldPausePlayback)
        assertFalse(AudioFocusEvent.Gain.shouldPausePlayback)
        assertTrue(AudioFocusEvent.Gain.shouldRestore)
        assertFalse(AudioFocusEvent.Gain.shouldDuck)
    }

    private fun dbToLinear(db: Float): Float = Math.pow(10.0, db.toDouble() / 20.0).toFloat()
}
