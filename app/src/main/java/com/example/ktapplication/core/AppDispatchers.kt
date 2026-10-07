package com.example.ktapplication.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.plus

/**
 * 调度器抽象。
 *
 * 直接写死 [Dispatchers.Main] 会让所有涉及协程的逻辑没法做 JVM 单测，
 * 所以仓库里任何 suspend / Flow 代码都只依赖这个接口，生产环境注入
 * [ProductionAppDispatchers]，测试注入 StandardTestDispatcher。
 */
interface AppDispatchers {
    /** UI 线程；ViewModel 里的 stateIn 用它，保证 Flow 收集结果能直接驱动 Compose。 */
    val main: CoroutineDispatcher

    val default: CoroutineDispatcher

    /** CAN 收帧、TSP 网络请求这类阻塞/长耗时 IO。 */
    val io: CoroutineDispatcher

    /** 信号解码这类 CPU 密集计算（DBC 位域拆包）。 */
    val computation: CoroutineDispatcher
}

object ProductionAppDispatchers : AppDispatchers {
    // Main.immediate：已经在主线程时不再排队，避免焦点回调、帧统计出现额外一跳延迟。
    override val main: CoroutineDispatcher get() = Dispatchers.Main.immediate
    override val default: CoroutineDispatcher get() = Dispatchers.Default
    override val io: CoroutineDispatcher get() = Dispatchers.IO
    override val computation: CoroutineDispatcher get() = Dispatchers.Default
}

/** 单线程调度器集合，用来在测试里让整条链路变成确定性的单线程执行。 */
class SingleDispatcherAppDispatchers(private val dispatcher: CoroutineDispatcher) : AppDispatchers {
    override val main: CoroutineDispatcher get() = dispatcher
    override val default: CoroutineDispatcher get() = dispatcher
    override val io: CoroutineDispatcher get() = dispatcher
    override val computation: CoroutineDispatcher get() = dispatcher
}

/**
 * 应用级协程作用域的持有者。
 *
 * 车机上服务生命周期比 Activity 长（CarService 断连重连、CAN 总线监听必须跨页面存活），
 * 所以这些常驻协程挂在 Application scope 上，而不是 viewModelScope。
 */
interface AppScopeHolder {
    val scope: CoroutineScope
}

class DefaultAppScopeHolder(
    override val scope: CoroutineScope = MainScope() + ProductionAppDispatchers.default,
) : AppScopeHolder
