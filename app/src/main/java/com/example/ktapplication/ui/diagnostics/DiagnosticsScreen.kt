package com.example.ktapplication.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 稳定性诊断页：把工具箱的输出原文展示出来。
 *
 * 用等宽字体 + 原文而不是拆成组件，是刻意的：现场排查时工程师要的是"能复制走的文本"，
 * 拆成小卡片反而不利于截图取证，也便于直接贴到问题单里。
 */
@Composable
fun DiagnosticsScreen(viewModel: DiagnosticsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryTile("CAN 帧率", "${"%.0f".format(state.canFrameRateHz)} Hz", Modifier.weight(1f))
                SummaryTile("严重级别", state.perf?.overallSeverity?.name ?: "INFO", Modifier.weight(1f))
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("链路状态", style = MaterialTheme.typography.titleSmall)
                    Text("CarService：${state.carServiceLine}", style = MaterialTheme.typography.bodySmall)
                    Text(state.cloudLine, style = MaterialTheme.typography.bodySmall)
                    state.busRows.forEach { (bus, row) -> Text("$bus　$row", style = MaterialTheme.typography.bodySmall) }
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("性能与稳定性取证文本", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = state.reportText.ifEmpty { "等待第一次采样窗口（默认 1s）…" },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

/**
 * 概览小卡：宽度按权重均分。
 *
 * [modifier] 从调用点传入而不是把函数写成布局作用域的扩展，是因为 foundation 1.8 之后
 * `RowScope` 与 `ColumnScope` 拆成了两个独立接口（不再有公共的 `RowColumnScope`），
 * `Modifier.weight` 只在父布局作用域里可见 —— 让 `Row { SummaryTile(..., Modifier.weight(1f)) }`
 * 把权重显式传进来，既保住"均分宽度"的意图，也让子组件不假设自己被放在哪种布局里。
 */
@Composable
private fun SummaryTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}
