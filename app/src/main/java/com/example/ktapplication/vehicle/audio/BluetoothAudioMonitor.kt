package com.example.ktapplication.vehicle.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.example.ktapplication.core.AppDispatchers
import com.example.ktapplication.core.MonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 车机侧关注的蓝牙音频配置档。 */
enum class AudioProfile(val displayName: String, val frameworkProfileId: Int) {
    /** 音乐通道：手机把歌推到车机。 */
    A2DP("A2DP 音乐", BluetoothProfile.A2DP),

    /** 通话通道：SCO 链路，来电时占用麦克风与扬声器。 */
    HFP("HFP 通话", BluetoothProfile.HEADSET),
}

sealed class BluetoothAudioEvent {
    data class ProfileStateChanged(val profile: AudioProfile, val connected: Boolean, val device: String?) : BluetoothAudioEvent()
    data class DeviceConnected(val address: String, val name: String?, val profile: AudioProfile) : BluetoothAudioEvent()
    data class DeviceDisconnected(val address: String, val profile: AudioProfile) : BluetoothAudioEvent()

    /** 链路掉了但设备仍在配对列表里 —— 车机上最常见的是"连上又掉"的抖动。 */
    data class LinkUnstable(val profile: AudioProfile, val flapCount: Int) : BluetoothAudioEvent()
}

/**
 * 蓝牙音频链路监控。
 *
 * 为什么不只监听广播：AAOS 上 `BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED` 从
 * API 28 才有公开常量，而且很多车机把这个广播收进了系统 App；三方 App 常见做法是
 * **广播 + 主动轮询 getProfileConnectionState 双保险**。这里两条都实现，
 * 轮询间隔 2s，只在自己掉线时用来兜底，不做常态轮询以省电。
 */
class BluetoothAudioMonitor(
    private val context: Context,
    private val clock: MonotonicClock,
    private val dispatchers: AppDispatchers,
    private val pollIntervalMillis: Long = 2_000L,
    @Suppress("DEPRECATION")
    private val adapterProvider: () -> BluetoothAdapter? = { BluetoothAdapter.getDefaultAdapter() },
) {

    private val _events = MutableSharedFlow<BluetoothAudioEvent>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<BluetoothAudioEvent> = _events.asSharedFlow()

    private val profileStates = AudioProfile.entries.associateWith { MutableStateFlow(false) }

    fun stateOf(profile: AudioProfile): StateFlow<Boolean> = (profileStates[profile] ?: MutableStateFlow(false)).asStateFlow()

    /** 音乐是否应当输出到蓝牙：A2DP 已连（HFP 已连但 A2DP 没连时仍走车机扬声器）。 */
    val musicOverBluetooth: StateFlow<Boolean> get() = requireNotNull(profileStates[AudioProfile.A2DP]).asStateFlow()

    private val flapCounters = mutableMapOf<AudioProfile, Int>()
    private val lastDisconnectAt = mutableMapOf<AudioProfile, Long>()
    private val profileProxies = mutableMapOf<AudioProfile, BluetoothProfile>()
    private var lastObserved = mapOf(AudioProfile.A2DP to false, AudioProfile.HFP to false)
    private var receiverRegistered = false
    private var pollJob: kotlinx.coroutines.Job? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val device = intent.parcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED ->
                    _events.tryEmit(BluetoothAudioEvent.DeviceConnected(deviceAddress(device), deviceNameOrNull(device), AudioProfile.A2DP))
                BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                    _events.tryEmit(BluetoothAudioEvent.DeviceDisconnected(deviceAddress(device), AudioProfile.A2DP))
                A2DP_CONNECTION_ACTION, HFP_CONNECTION_ACTION -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    val profile = if (action == A2DP_CONNECTION_ACTION) AudioProfile.A2DP else AudioProfile.HFP
                    val connected = state == BluetoothProfile.STATE_CONNECTED
                    handleObserved(profile, connected, deviceNameOrNull(device))
                }
            }
        }
    }

    fun start(scope: CoroutineScope) {
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    addAction(A2DP_CONNECTION_ACTION)
                    addAction(HFP_CONNECTION_ACTION)
                }
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // API 33 起 registerReceiver 必须显式声明导出性。这里选 RECEIVER_NOT_EXPORTED：
                    // 过滤器里全是系统 protected broadcast（ACL / profile 连接状态），只有 framework
                    // 能发，三方广播根本收不进来；声明成 EXPORTED 反而给恶意 App 留出"伪造蓝牙已连接"
                    // 的注入面。注意标志位只在 33+ 存在，低版本走下面的无重载路径照常注册，
                    // 不能拿 minSdk 24 去调 33 的方法（会 NoSuchMethodError，且这里被 runCatching 吞掉
                    // 就更难查——表现是"蓝牙状态永远不动"）。
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(receiver, filter)
                }
                receiverRegistered = true
            }
        }
        adapterProvider()?.let { warmUpProfileProxies(it) }
        pollJob?.cancel()
        pollJob = scope.launch(dispatchers.io) {
            // 广播优先，轮询只用于"广播没来但状态已变"的场景（车机常见）。
            while (isActive) {
                pollOnce()
                delay(pollIntervalMillis)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        adapterProvider()?.let { closeProfileProxies(it) }
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun pollOnce() {
        val adapter = adapterProvider() ?: return
        // 没有 BLUETOOTH_CONNECT 就不查状态：宁可显示"未连接"，也不能在无权限时报告"已连接"。
        // 权限判定用 hasConnectPermission() 显式做（这才是 lint 想要的"检查或处理异常"），
        // 注解只是把这个事实告诉静态检查器——它看不懂自定义的门禁函数。
        val permitted = hasConnectPermission()
        AudioProfile.entries.forEach { profile ->
            val state = if (!permitted) BluetoothProfile.STATE_DISCONNECTED else runCatching {
                adapter.getProfileConnectionState(profile.frameworkProfileId)
            }.getOrDefault(BluetoothProfile.STATE_DISCONNECTED)
            handleObserved(profile, state == BluetoothProfile.STATE_CONNECTED, if (permitted) deviceNameOf(profile) else null)
        }
    }

    /** 等待某个配置档就绪，用于"电话接通后再恢复导航音量"这类时序。 */
    suspend fun awaitProfile(profile: AudioProfile, timeoutMillis: Long = 5_000L): Boolean {
        if (stateOf(profile).value) return true
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) {
            stateOf(profile).first { connected -> connected }
        } ?: stateOf(profile).value
    }

    private fun handleObserved(profile: AudioProfile, connected: Boolean, deviceName: String?) {
        val previous = lastObserved[profile] ?: false
        if (previous == connected) return
        lastObserved = lastObserved + (profile to connected)
        profileStates.getValue(profile).value = connected
        if (previous && !connected) {
            lastDisconnectAt[profile] = clock.nowMillis()
            // 掉线后又连上：累计抖动次数，超过 3 次上报 LinkUnstable（真实故障特征）。
            val flaps = (flapCounters[profile] ?: 0) + 1
            flapCounters[profile] = flaps
            if (flaps >= FLAP_ALARM_THRESHOLD) _events.tryEmit(BluetoothAudioEvent.LinkUnstable(profile, flaps))
        } else if (connected) {
            flapCounters[profile] = 0
            _events.tryEmit(BluetoothAudioEvent.DeviceConnected("unknown", deviceName, profile))
        }
        _events.tryEmit(BluetoothAudioEvent.ProfileStateChanged(profile, connected, deviceName))
    }

    /**
     * `BluetoothProfile.getConnectedDevices()` 需要 BLUETOOTH_CONNECT（API 31+）。
     * 拿不到就返回 null，UI 只显示"已连接"而不编造设备名。
     */
    @SuppressLint("MissingPermission")
    private fun deviceNameOf(profile: AudioProfile): String? =
        if (hasConnectPermission()) connectedDevicesOf(profile).firstOrNull()?.name else null

    /**
     * BLUETOOTH_CONNECT 是否已授予。
     *
     * API 31 起它是**运行时**权限（用户能在设置里关掉，车机还常被 OEM 默认关掉），
     * 而 `BluetoothDevice.name`、`getProfileConnectionState`、`getConnectedDevices`
     * 都会直接抛 SecurityException。lint 的 MissingPermission 检查就是冲着这条来的：
     * 正确做法是"显式查权限 + 处理异常"，而不是贴 @SuppressLint 把它按掉——
     * 车机上"已连接但设备名空白"和"权限没给却显示已连接"都是这么埋下的。
     * 31 以下 BLUETOOTH 是安装期权限，manifest 声明过就恒为真。
     */
    private fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) true else runCatching {
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    /** 广播里带过来的设备：没权限只保留地址（读地址不需要权限），名字按缺失处理。 */
    @SuppressLint("MissingPermission")
    private fun deviceNameOrNull(device: BluetoothDevice?): String? {
        val target = device ?: return null
        if (!hasConnectPermission()) return null
        return runCatching { target.name }.getOrNull()
    }

    /**
     * 取已连设备。
     *
     * `getProfileProxy` 是**异步**的：立刻读 connectedDevices 一定为空，这是很多车机
     * 蓝牙状态显示"已连接但没有设备名"的根因。这里在 start() 时申请代理并缓存下来，
     * 轮询时只用缓存好的 proxy。
     */
    @SuppressLint("MissingPermission")
    private fun connectedDevicesOf(profile: AudioProfile): List<BluetoothDevice> =
        runCatching { profileProxies[profile]?.connectedDevices ?: emptyList() }.getOrDefault(emptyList())

    private fun warmUpProfileProxies(adapter: BluetoothAdapter) {
        AudioProfile.entries.forEach { profile ->
            if (profileProxies.containsKey(profile)) return@forEach
            runCatching {
                adapter.getProfileProxy(
                    context,
                    // 框架侧唯一的 profile 监听接口就是 BluetoothProfile.ServiceListener，
                    // 它只有 onServiceConnected/onServiceDisconnected 两个方法，而且语义是
                    // **"服务代理就绪/失效"**，不是"设备连上了"：把 onServiceConnected 当成
                    // A2DP 已连接是车机上最常见的错误，表现是刚开机就显示"蓝牙音乐已连接"。
                    // 真正的连接状态一律来自广播 + pollOnce() 的 getProfileConnectionState，
                    // 这里只缓存 proxy（读已连设备名、按 device 读 getConnectionState）。
                    // 机型不能猜：getProfileProxy 在部分车机上永不回调（蓝牙服务被 OEM 改过），
                    // 所以 connectedDevicesOf() 拿不到 proxy 时回空表，状态仍由轮询给出。
                    object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile?) {
                            if (profileId != profile.frameworkProfileId) return
                            // 二次校验 id：A2DP 与 HEADSET 共用一个 adapter，回调顺序不保证，
                            // 存错 key 会让 HFP 的 proxy 顶掉 A2DP 的设备列表。
                            val resolved = proxy ?: return
                            profileProxies[profile] = resolved
                        }

                        override fun onServiceDisconnected(profileId: Int) {
                            // 蓝牙进程重启/关闭：代理对象立即变成不可用（调用会抛 DeadObjectException），
                            // 必须按 id 精确摘除，不能让 start() 的 containsKey 短路把它永久缓存住。
                            if (profileId != profile.frameworkProfileId) return
                            profileProxies.remove(profile)
                        }
                    },
                    profile.frameworkProfileId,
                )
            }
        }
    }

    private fun closeProfileProxies(adapter: BluetoothAdapter) {
        profileProxies.forEach { (_, proxy) -> runCatching { adapter.closeProfileProxy(proxy.iProfileId(), proxy) } }
        profileProxies.clear()
    }

    /** BluetoothProfile 没有公开 getId()，用已知 id 反查。 */
    private fun BluetoothProfile.iProfileId(): Int = when (this) {
        is android.bluetooth.BluetoothA2dp -> BluetoothProfile.A2DP
        is android.bluetooth.BluetoothHeadset -> BluetoothProfile.HEADSET
        else -> BluetoothProfile.A2DP
    }

    private fun deviceAddress(device: BluetoothDevice?): String = runCatching { device?.address }.getOrNull() ?: "unknown"

    /** getParcelableExtra(String) 在 API 33 起被标废弃，需要按版本走 Class 重载。 */
    @Suppress("DEPRECATION")
    private fun Intent.parcelableExtra(key: String): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, BluetoothDevice::class.java)
        } else {
            getParcelableExtra(key) as? BluetoothDevice
        }

    private companion object {
        const val A2DP_CONNECTION_ACTION = "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED"
        const val HFP_CONNECTION_ACTION = "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED"
        const val FLAP_ALARM_THRESHOLD = 3
    }
}

/**
 * 音频输出路由决策（纯函数）。
 *
 * 座舱的输出目标不止"蓝牙/扬声器"两个：还有后排耳机音区、USB 声卡、以及
 * OEM 自定义的 DSP 通道。规则集中在这里，UI 与播放器共用同一份结论。
 */
object CarAudioOutputRouter {

    enum class OutputTarget(val displayName: String) {
        CAR_SPEAKER("车机扬声器"),
        BLUETOOTH_A2DP("蓝牙车载音响"),
        BLUETOOTH_HFP("蓝牙通话通道（SCO）"),
        HEAD_UNIT_USB("USB 音频"),
        REAR_HEADSET("后排耳机音区"),
    }

    /**
     * 通话永远走 HFP（SCO），音乐看 A2DP；这是车机上最容易做错的一条：
     * 有些实现"蓝牙已连"就把音乐也走 SCO，结果音质掉到 8kHz 被用户投诉。
     */
    fun routeFor(
        usage: AudioUsage,
        a2dpConnected: Boolean,
        hfpConnected: Boolean,
        rearHeadsetActive: Boolean,
        usbAudioActive: Boolean,
    ): OutputTarget = when {
        usage == AudioUsage.PHONE_CALL || usage == AudioUsage.RINGTONE ->
            if (hfpConnected) OutputTarget.BLUETOOTH_HFP else OutputTarget.CAR_SPEAKER
        rearHeadsetActive && usage == AudioUsage.MEDIA -> OutputTarget.REAR_HEADSET
        usbAudioActive && usage == AudioUsage.MEDIA -> OutputTarget.HEAD_UNIT_USB
        a2dpConnected && usage == AudioUsage.MEDIA -> OutputTarget.BLUETOOTH_A2DP
        else -> OutputTarget.CAR_SPEAKER
    }

    /** 导航播报永远走车机扬声器，即使音乐在蓝牙/后排：这是座舱的固定要求。 */
    fun forcesCarSpeaker(usage: AudioUsage): Boolean =
        usage == AudioUsage.NAVIGATION || usage == AudioUsage.ALERT || usage == AudioUsage.SYSTEM_PROMPT
}
