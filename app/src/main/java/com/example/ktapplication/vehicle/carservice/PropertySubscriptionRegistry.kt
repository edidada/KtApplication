package com.example.ktapplication.vehicle.carservice

import com.example.ktapplication.core.AppDispatchers
import com.example.ktapplication.core.MonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 属性订阅共享层。
 *
 * 车机上同时有多个组件关心同一批属性：仪表要车速、空调页要温度、语音要档位、
 * 远程控车要门锁状态。如果每个订阅方都调一次 `registerCarPropertyCallback`，
 * VHAL 侧的回调注册数会成倍增长（这是 AAOS 上很常见的性能坑：属性回调注册
 * 泄漏导致 system_server 端 CarService 内存涨、最后整个 CarService 被杀）。
 *
 * 这里做三件事：
 *  1. 以 (property, areaId) 为 key 引用计数，只有第一个订阅者真正注册；
 *  2. 最后一个订阅者离开后延迟 [stopTimeoutMillis] 再注销 —— 页面切换时不要来回注册；
 *  3. 用 [Channel.CONFLATED] 输出，保证慢订阅者不会积压 50Hz 的旧值。
 */
class PropertySubscriptionRegistry(
    private val clock: MonotonicClock,
    private val dispatchers: AppDispatchers,
    private val scope: CoroutineScope,
    /** 最后一个订阅者离开后保留订阅的时间，覆盖 Compose 页面切换的空窗。 */
    private val stopTimeoutMillis: Long = 5_000L,
    private val registrar: Registrar,
) {

    /** 由具体网关实现：真正向 VHAL 注册/注销/写入。 */
    interface Registrar {
        suspend fun register(property: CarVehicleProperty, areaId: Int): Boolean
        suspend fun unregister(property: CarVehicleProperty, areaId: Int)
        /**
         * 单点读。命名成 readSample 而不是 read：网关接口上已经有一个
         * `read(property, areaId): Outcome<CarPropertySample>`（带默认 areaId），
         * 两者 JVM 签名完全相同，同名会直接判"conflicting overloads"。
         */
        suspend fun readSample(property: CarVehicleProperty, areaId: Int): CarPropertySample?
        suspend fun write(property: CarVehicleProperty, areaId: Int, value: PropertyValue): String?
    }

    private data class Key(val property: CarVehicleProperty, val areaId: Int)

    private class Entry(
        val key: Key,
    ) {
        /**
         * 每个订阅者一个独立 channel。
         *
         * 早期版本是"一条共享 channel + receiveAsFlow"，那是错的：Channel 的接收是
         * **分发型**的，两个订阅者只会各收到一半样本（仪表拿到偶数帧、空调页拿到奇数帧）。
         * 注册可以共享（这才是省 VHAL 回调的地方），投递必须扇出。
         */
        val listeners = java.util.concurrent.CopyOnWriteArraySet<Channel<CarPropertySample>>()
        var refCount = 0
        var idleJob: Job? = null
        @Volatile
        var registered = false
        val mutex = Mutex()
    }

    private val entries = ConcurrentHashMap<Key, Entry>()

    /** 供实现层在收到 VHAL 回调时投递。 */
    fun publish(sample: CarPropertySample) {
        val entry = entries[Key(sample.property, sample.areaId)] ?: return
        entry.listeners.forEach { channel -> channel.trySend(sample) }
    }

    fun subscribe(property: CarVehicleProperty, areaId: Int = GLOBAL_AREA_ID): Flow<CarPropertySample> {
        val key = Key(property, areaId)
        val entry = entries.getOrPut(key) { Entry(key) }
        val listener = Channel<CarPropertySample>(Channel.CONFLATED)
        entry.listeners.add(listener)
        entry.refCount += 1
        entry.idleJob?.cancel()
        scope.launch(dispatchers.io) {
            entry.mutex.withLock {
                if (!entry.registered) {
                    val ok = registrar.register(property, areaId)
                    entry.registered = ok
                    if (!ok) {
                        // 注册失败要立刻给订阅者一个 NOT_AVAILABLE 样本，否则 UI 会一直卡在"加载中"。
                        listener.trySend(CarPropertySample.unavailable(property, areaId, clock.nowMillis()))
                    }
                }
            }
        }
        return callbackFlow {
            val forward = launch { listener.receiveAsFlow().collect { send(it) } }
            awaitClose {
                forward.cancel()
                entry.listeners.remove(listener)
                listener.cancel()
            }
        }
    }

    /** 订阅者结束时调用；引用计数归零后延时注销。 */
    fun release(property: CarVehicleProperty, areaId: Int = GLOBAL_AREA_ID) {
        val key = Key(property, areaId)
        val entry = entries[key] ?: return
        entry.refCount = (entry.refCount - 1).coerceAtLeast(0)
        if (entry.refCount == 0) {
            entry.idleJob = scope.launch(dispatchers.io) {
                kotlinx.coroutines.delay(stopTimeoutMillis)
                if ((entries[key]?.refCount ?: 0) == 0) {
                    entries[key]?.let { e ->
                        if (e.registered) {
                            e.mutex.withLock {
                                if (e.registered) {
                                    registrar.unregister(property, areaId)
                                    e.registered = false
                                }
                            }
                        }
                    }
                    entries.remove(key)
                }
            }
        }
    }

    suspend fun readOnce(property: CarVehicleProperty, areaId: Int): CarPropertySample? =
        registrar.readSample(property, areaId)

    suspend fun write(property: CarVehicleProperty, areaId: Int, value: PropertyValue): String? =
        registrar.write(property, areaId, value.coercedTo(property.valueType))

    /** 当前注册数，诊断页用来发现回调泄漏。 */
    val activeRegistrationCount: Int get() = entries.values.count { it.registered }

    fun releaseAll() {
        entries.keys.toList().forEach { key ->
            entries[key]?.let { entry ->
                if (entry.registered) scope.launch(dispatchers.io) { registrar.unregister(key.property, key.areaId) }
                entry.registered = false
                entry.refCount = 0
            }
        }
        entries.clear()
    }
}

/**
 * 属性缓存：把"最后一次可信值 + 新鲜度"收敛到一处。
 *
 * 座舱必须有这一层，因为链路会抖动（网关重启、TBOX 断连），如果 UI 直接吃回调流，
 * 每次断连都会看到数值跳 0；缓存 + 陈旧判定能让界面保持上一个可信值并标注"陈旧"。
 */
class PropertyCache(private val clock: MonotonicClock) {

    private val samples = MutableStateFlow<Map<String, CarPropertySample>>(emptyMap())

    val snapshot: StateFlow<Map<String, CarPropertySample>> = samples

    fun put(sample: CarPropertySample) {
        // 只接受时间戳更新（或相同）的样本：车机上 VHAL 回调和轮询读值可能同时到达，
        // 乱序写入会让仪表上的车速来回跳。
        val key = "${sample.property.name}:${sample.areaId}"
        val existing = samples.value[key]
        if (existing != null && existing.timestampMillis > sample.timestampMillis) return
        samples.value = samples.value + (key to sample)
    }

    fun get(property: CarVehicleProperty, areaId: Int = GLOBAL_AREA_ID): CarPropertySample? =
        samples.value["${property.name}:$areaId"]

    /** 超过 [freshThresholdMillis] 未更新的属性按陈旧处理。 */
    fun freshOf(property: CarVehicleProperty, areaId: Int = GLOBAL_AREA_ID, freshThresholdMillis: Long = 2_000L): CarPropertySample? {
        val sample = get(property, areaId) ?: return null
        return if (clock.nowMillis() - sample.timestampMillis > freshThresholdMillis) sample.copy(status = PropertyStatus.UNAVAILABLE) else sample
    }

    fun numeric(property: CarVehicleProperty, areaId: Int = GLOBAL_AREA_ID): Double? =
        freshOf(property, areaId)?.numericValue

    fun clear() {
        samples.value = emptyMap()
    }
}
