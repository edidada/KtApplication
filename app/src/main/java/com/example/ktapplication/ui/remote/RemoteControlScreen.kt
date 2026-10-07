package com.example.ktapplication.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ktapplication.vehicle.domain.model.RemoteAction

/**
 * 远程控车页。
 *
 * 三条车载/车联网的产品级约束体现在这里：
 *  1. **不可用要有原因**：按钮置灰必须告诉用户"为什么"（行驶中/未驻车/未在充电），
 *     否则用户会认为是 App 卡住并反复点击；
 *  2. **"结果未知"要显示成待确认**，绝不能显示失败 —— 见 CommandStage.ResultUnknown；
 *  3. 指令历史要可见，用户投诉"我明明按了两次"时，这是唯一的现场证据。
 */
@Composable
fun RemoteControlScreen(viewModel: RemoteControlViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.offlineQueued > 0) {
                Text(
                    "离线队列中还有 ${state.offlineQueued} 条指令待发送",
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text("有 ${state.blockedCount} 项因车辆状态被禁用", style = MaterialTheme.typography.labelMedium)
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(state.actions, key = { it.action.name }) { row ->
                    ActionTile(
                        title = row.action.displayName,
                        subtitle = row.blockingReason ?: "超时 ${row.action.timeoutMillis / 1000}s · ${if (row.action.autoRetryAllowed) "可自动重试" else "不自动重试"} · ${if (row.action.idempotent) "幂等" else "非幂等"}",
                        enabled = row.enabled,
                        inProgress = row.inProgress,
                        onClick = {
                            if (row.action == RemoteAction.SET_TARGET_TEMPERATURE) viewModel.setTemperature(24)
                            else viewModel.execute(row.action)
                        },
                    )
                }
            }
            state.toast?.let {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(it, modifier = Modifier.padding(12.dp))
                }
            }
            Text("最近指令", style = MaterialTheme.typography.titleSmall)
            state.recent.take(5).forEach { progress ->
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "${progress.command.action.displayName} · ${progress.stage.userMessage} · 用时 ${progress.elapsedMillis}ms",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(progress = { viewModel.progressFraction(progress) }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun ActionTile(
    title: String,
    subtitle: String,
    enabled: Boolean,
    inProgress: Boolean,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.labelSmall)
            }
            if (inProgress) {
                OutlinedButton(onClick = onClick) { Text("执行中") }
            } else {
                Button(onClick = onClick, enabled = enabled) { Text(title) }
            }
        }
    }
}
