package com.example.ktapplication.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 协程流控工具。
 *
 * 这一层解决的是车机 UI 上真实存在的两类事故：
 *
 * 1. **重复下发**。远程控车页面用户连点两次"解锁"，两次点击会各自 launch 一个协程，
 *    各自生成一条指令。云端对同一账号短时重复指令**不做去重**（他们只做鉴权），
 *    于是车辆真的解锁两次，或者第二条把第一条的执行结果覆盖成"未知"。
 *    布尔标志位 `isSending` 挡不住：标志位读写跨协程，且在 launch 排队和真正开始执行之间
 *    有个空档 —— 这个空档在低端车机上能到几十毫秒，够点两次了。
 *
 * 2. **抖动风暴**。"刷新车况"按钮、下拉刷新、页面可见时自动刷新如果都直接调仓储层，
 *    弱网下车机 TSP 请求会排队堆积。请求本身有超时，但用户看不到进度就会再点，
 *    最后一条响应可能对应最早那次点击，UI 显示的是几秒前的陈旧数据。
 *
 * 实现原则：不引第三方库（项目零运行时依赖），时间一律走 [MonotonicClock] 抽象，
 * 这样单测能用 [FakeMonotonicClock] 拨表而不是靠 sleep 猜。
 */

/**
 * 单飞（single flight）：同一个 key 同时只允许一个挂起任务在跑，后来者立刻拿到降级结果。
 *
 * **为什么用 synchronized 而不是 kotlinx.coroutines.sync.Mutex**（这版是被单测逼出来的结论）：
 * Mutex.lock() 是可取消的挂起函数，而释放动作必须写在 `finally` 里；持有者被取消时
 * （用户划掉页面、ViewModel 清理）协程已经处于 cancelled 状态，`withLock { remove(key) }`
 * 会在进入前就抛 CancellationException —— 结果是**释放永远执行不到**，key 永久"看起来在忙"，
 * 之后用户怎么点都只走 onBusy 分支。车机上表现为"某个功能突然再也不响应，重启进程才好"。
 * 这里的临界区都是 O(1) 的 HashMap 读写、且**绝不在锁内挂起**，用普通监视器锁更正确也更便宜。
 */
class SingleFlight {

    private val lock = Any()
    private val inFlight = HashMap<String, Job>()

    /**
     * 以 [key] 为占用运行 [block]。
     *
     * @param onBusy 已有同 key 任务在跑时的返回值；**刻意不是挂起函数**，
     *   "挡重复"的价值就在于调用方零等待、零排队。
     */
    suspend fun <T> runOnce(
        key: String,
        onBusy: () -> T,
        block: suspend () -> T,
    ): T {
        // 票据用调用方的 Job：这样"谁占的"是可验证的，释放时能按身份比对，
        // 不会出现 A 被取消后把 B 刚拿到的占用误删。
        val callerJob = currentCoroutineContext()[Job]
            ?: error("runOnce 必须在协程内调用：单飞占用要靠调用方的 Job 身份释放")

        if (!tryAcquire(key, callerJob)) return onBusy()
        try {
            return block()
        } finally {
            // 同步释放：不挂起，所以取消路径、异常路径都能走到 —— 这正是弃用 Mutex 的理由。
            release(key, callerJob)
        }
    }

    /** 给 UI 显示"该操作正在进行"用。 */
    fun isBusy(key: String): Boolean = synchronized(lock) { inFlight.containsKey(key) }

    val busyKeys: Set<String> get() = synchronized(lock) { inFlight.keys.toSet() }

    private fun tryAcquire(key: String, job: Job): Boolean = synchronized(lock) {
        if (inFlight.containsKey(key)) {
            false
        } else {
            inFlight[key] = job
            true
        }
    }

    private fun release(key: String, job: Job) {
        synchronized(lock) {
            if (inFlight[key] === job) inFlight.remove(key)
        }
    }
}

/**
 * 首部节流：窗口内只放行**第一个**元素，后续直接丢弃。
 *
 * 为什么不用 debounce（尾部节流）：点击类场景要的是"按下立刻有反馈"，
 * debounce 会把第一次点击也压到停止操作之后才执行，车机上按了半秒没反应，
 * 用户一定认为没点上，然后加倍猛点 —— 这恰恰是我们要消除的行为。
 *
 * 注意语义细节：这里是"丢弃"而不是"缓存最后一次"，所以它适合刷新这类幂等触发，
 * 不适合"每一次都必须执行"的场景（那种该用 [SingleFlight] 或者队列）。
 */
fun <T> Flow<T>.throttleFirst(
    windowMillis: Long,
    clock: MonotonicClock = SystemMonotonicClock,
): Flow<T> {
    require(windowMillis >= 0) { "节流窗口不能为负" }
    val window = windowMillis
    return flow {
        // 首元素无条件放行，用显式标志位而不是"上次放行时间 = Long.MIN_VALUE"：
        // MIN_VALUE 参与减法会溢出，溢出结果的正负取决于运行时刻，属于典型的
        // "测试里永远正确、上车偶发第一次也被节流"的坑。
        var lastPassedMillis = 0L
        var hasPassed = false
        collect { value ->
            val now = clock.nowMillis()
            if (!hasPassed || now - lastPassedMillis >= window) {
                hasPassed = true
                lastPassedMillis = now
                emit(value)
            }
        }
    }
}

/** 时间衰减节流：按 key 记录上次放行时刻，适合"同一类按钮、多个目标"共用一套窗口。 */
class LeadingThrottle(
    private val windowMillis: Long,
    private val clock: MonotonicClock = SystemMonotonicClock,
) {
    init {
        require(windowMillis >= 0) { "节流窗口不能为负" }
    }

    private val lastPassedMillis = HashMap<String, Long>()

    /** 线程安全说明：设计为只在主线程（Compose 点击处理）调用；跨线程请自行加锁。 */
    fun allow(key: String): Boolean = synchronized(lastPassedMillis) {
        val now = clock.nowMillis()
        val previous = lastPassedMillis[key]
        if (previous != null && now - previous < windowMillis) return false
        lastPassedMillis[key] = now
        true
    }

    fun reset(key: String) {
        synchronized(lastPassedMillis) { lastPassedMillis.remove(key) }
    }
}
