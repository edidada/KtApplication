package com.example.ktapplication

import android.app.Application
import com.example.ktapplication.di.AppGraph
import com.example.ktapplication.ui.AppDependencies

/**
 * 座舱 App 的进程入口。
 *
 * 车机上 Application 的职责比手机上重：总线监听、CarService 绑定、云端长连接这些
 * 常驻链路必须活得比 Activity 久（中控分屏、页面切换、甚至系统重启 Launcher 都不该断流），
 * 所以依赖图在这里建，只在这里 bootstrap，也只在这里收口。
 *
 * 两个刻意的设计：
 *  - **构造与启动分离**：AppGraph 只建对象，bootstrap() 才真的连总线。
 *    这样单测/工具可以只构造依赖图观察装配是否成环，而不需要真链路。
 *  - **不在 Application 里做重活**：车机冷启动预算（上电到仪表出数）通常按 2s 卡，
 *    任何同步 IO（读信号表、建目录）都不该放这儿。这里只放内存装配 + 异步连接。
 */
class CockpitApplication : Application() {

    /** 依赖图在 [onCreate] 最早段建立，[AppGraph] 内部全部是内存操作，无阻塞。 */
    lateinit var graph: AppGraph
        private set

    /** UI 只通过这个入口拿接口，拿不到具体实现类型。 */
    val dependencies: AppDependencies
        get() = graph

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.bootstrap()
    }

    /**
     * 真机上不会被调用（进程直接被杀），保留是为了 Robolectric / 本地跑批能收敛资源。
     * 线上进程收口靠的是：所有协程挂在一个 SupervisorJob 上，进程终止即释放。
     */
    override fun onTerminate() {
        if (::graph.isInitialized) graph.release()
        super.onTerminate()
    }
}
