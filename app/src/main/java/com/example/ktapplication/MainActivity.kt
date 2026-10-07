package com.example.ktapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ktapplication.ui.AppDependencies
import com.example.ktapplication.ui.cockpit.CockpitScreen
import com.example.ktapplication.ui.cockpit.CockpitViewModel
import com.example.ktapplication.ui.diagnostics.DiagnosticsScreen
import com.example.ktapplication.ui.diagnostics.DiagnosticsViewModel
import com.example.ktapplication.ui.remote.RemoteControlScreen
import com.example.ktapplication.ui.remote.RemoteControlViewModel
import com.example.ktapplication.ui.theme.KtApplicationTheme
import com.example.ktapplication.ui.vehicle.VehicleStatusScreen
import com.example.ktapplication.ui.vehicle.VehicleStatusViewModel

/**
 * 座舱 Demo 主入口。
 *
 * 页面用 NavigationSuiteScaffold 分四个目的地，对应两个招聘方向：
 *  - 座舱方向：Cockpit（CAN 信号 + 音频焦点）、Diagnostics（稳定性工具箱）；
 *  - 车联网方向：Vehicle（三路车况合并）、Remote（远程控车与状态核验）。
 *
 * ViewModel 依赖通过 CompositionLocal 注入而不是 ViewModelProvider.Factory：
 * 示例里所有依赖都是进程级单例（Application 建的），用 Factory 只是为了演示 DI 框架；
 * 这里保持手写依赖图，读者一眼能看出"谁创建、谁负责销毁"。
 */
class MainActivity : ComponentActivity() {

    private lateinit var deps: AppDependencies

    /** 首帧只收口一次：onWindowFocusChanged 在分屏/切后台时会反复触发。 */
    private var firstFrameReported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        deps = (application as CockpitApplication).dependencies
        // 帧统计必须有 DecorView，而 DecorView 只有 Activity 才有，所以由界面把探针挂上去。
        // 放在 setContent 之后：PhoneWindow 在 decor 为 null 时会拒绝注册帧回调，
        // 早一毫秒就会白白损失一次首帧数据（探针自己会重试，但没必要依赖重试）。
        setContent {
            KtApplicationTheme {
                // CompositionLocalProvider 必须在 @Composable 上下文里调用，所以放在 setContent 的内容槽：
                // 依赖图在这里下发给整棵组件树，ViewModel 通过 LocalAppDependencies.current 取用。
                CompositionLocalProvider(LocalAppDependencies provides deps) {
                    KtApplicationApp()
                }
            }
        }
        deps.attachFrameMetrics(this)
    }

    /**
     * 拿到焦点 ≈ 首帧真的上屏了。
     *
     * 车机验收看的是"上电到仪表出数"，用 onCreate 返回时刻代替会明显低估体感；
     * 收口动作交给依赖图，界面不知道自己被统计了什么。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !firstFrameReported) {
            firstFrameReported = true
            deps.markFirstFrameRendered()
        }
    }
}

val LocalAppDependencies = compositionLocalOf<AppDependencies?> { null }

enum class AppDestinations(
    val label: String,
    val icon: Int,
) {
    COCKPIT("座舱信号", R.drawable.ic_home),
    VEHICLE("车辆状态", R.drawable.ic_favorite),
    REMOTE("远程控车", R.drawable.ic_account_box),
    DIAGNOSTICS("诊断取证", R.drawable.ic_diagnostics),
}

@Composable
fun KtApplicationApp() {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.COCKPIT) }
    val deps = requireNotNull(LocalAppDependencies.current) { "必须在 CockpitApplication 启动后进入" }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach { destination ->
                item(
                    icon = { Icon(painterResource(destination.icon), contentDescription = destination.label) },
                    label = { Text(destination.label) },
                    selected = destination == currentDestination,
                    onClick = { currentDestination = destination },
                )
            }
        },
    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            val contentModifier = Modifier.padding(innerPadding)
            when (currentDestination) {
                AppDestinations.COCKPIT -> {
                    val vm: CockpitViewModel = viewModel(key = "cockpit") { CockpitViewModel(deps) }
                    CockpitScreen(vm, contentModifier)
                }

                AppDestinations.VEHICLE -> {
                    val vm: VehicleStatusViewModel = viewModel(key = "vehicle") { VehicleStatusViewModel(deps) }
                    VehicleStatusScreen(vm, contentModifier)
                }

                AppDestinations.REMOTE -> {
                    val vm: RemoteControlViewModel = viewModel(key = "remote") { RemoteControlViewModel(deps) }
                    RemoteControlScreen(vm, contentModifier)
                }

                AppDestinations.DIAGNOSTICS -> {
                    val vm: DiagnosticsViewModel = viewModel(key = "diagnostics") { DiagnosticsViewModel(deps) }
                    DiagnosticsScreen(vm, contentModifier)
                }
            }
        }
    }
}

@PreviewScreenSizes
@Composable
fun CockpitPreview() {
    KtApplicationTheme {
        Text("Compose 预览不注入依赖：车载数据链路需要 Application 级初始化，预览用 Mock 数据在各自 Screen 的 @Preview 里给出。")
    }
}
