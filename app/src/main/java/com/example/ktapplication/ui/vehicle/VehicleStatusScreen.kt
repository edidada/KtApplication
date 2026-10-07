package com.example.ktapplication.ui.vehicle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 车况页（手机/车机通用逻辑的展示位）。
 *
 * 这一页的重点不是好看，而是**每个数字都要能追溯到来源与新鲜度** ——
 * 远程车况类 App 最典型的差评是"车已经解锁了 App 还显示上锁"，
 * 所以这里把 freshness 显式画出来，而不是假装数据总是对的。
 */
@Composable
fun VehicleStatusScreen(viewModel: VehicleStatusViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Metric("SOC", state.socText, "%", Modifier.weight(1f))
                Metric("续航", state.rangeText, "km", Modifier.weight(1f))
                Metric("车速", state.speedText, "km/h", Modifier.weight(1f))
                Metric("小电瓶", state.lowBatteryText, "", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Metric("档位", state.gearText, "", Modifier.weight(1f))
                Metric("门锁", state.lockText, "", Modifier.weight(1f))
                Metric("安全带", state.seatbeltText, "", Modifier.weight(1f))
                Metric("空调", state.climateText, "", Modifier.weight(1f))
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("充电：${state.chargingText}", style = MaterialTheme.typography.bodyMedium)
                    Text("里程：${state.odometerText} km", style = MaterialTheme.typography.bodyMedium)
                    Text("位置：${state.locationSummary}", style = MaterialTheme.typography.bodyMedium)
                    Text(state.updatedAtText, style = MaterialTheme.typography.labelSmall)
                    Text(
                        text = state.doors.joinToString("　") { (name, value) -> "$name $value" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.warningFields.isNotEmpty()) {
                        Text(
                            "陈旧字段：${state.warningFields.joinToString()}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        text = state.perFieldSources.entries.joinToString("\n") { (k, v) -> "$k ← $v" },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = viewModel::refresh, enabled = !state.isRefreshing) { Text("从云端刷新") }
                if (state.isRefreshing) CircularProgressIndicator(modifier = Modifier.padding(start = 4.dp))
            }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (state.unavailable) {
                Text(
                    "暂无可信车况：CAN 未就绪、CarService 未连接、云端尚未返回。",
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/**
 * 单项指标卡：宽度按权重均分。
 *
 * [modifier] 由调用点（车况指标行）传入 `Modifier.weight(1f)`，而不是把本函数写成布局作用域的扩展：
 * foundation 1.8 之后 `RowScope` 与 `ColumnScope` 是两个独立接口，没有公共的 `RowColumnScope` 可当接收者，
 * 而 `weight` 又只在父布局作用域内可见。显式传 modifier 同时符合 Compose 的组件写法 ——
 * 小部件不该假设自己只能待在 `Row` 里（同一张卡在横屏车机上会被放进 `Column` 复用）。
 */
@Composable
private fun Metric(title: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Text("$value $unit", style = MaterialTheme.typography.titleLarge)
        }
    }
}
