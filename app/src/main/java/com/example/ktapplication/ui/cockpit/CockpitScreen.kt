package com.example.ktapplication.ui.cockpit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 座舱信号面板。
 *
 * 用 LazyVerticalGrid 而不是 ScrollView+Column：车机屏是 1920x720/1280x720 的横屏，
 * 信号卡数量会随车型增加，需要按宽度自适应列数并且只重组可见项 ——
 * 50Hz 数据流下"整页重组"是掉帧的首要原因。
 */
@Composable
fun CockpitScreen(viewModel: CockpitViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BusStatusBar(
                busState = state.busState.name,
                frameRateHz = state.frameRateHz,
                dropRates = state.dropRates,
                staleSignals = state.staleSignals,
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 180.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(state.cards, key = { it.title }) { card -> SignalCard(card) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = viewModel::simulateNavigationPrompt) { Text("模拟导航播报（duck 音乐）") }
                OutlinedButton(onClick = viewModel::simulateNavigationPrompt) {
                    Text("当前焦点：${state.heldAudioUsage?.displayName ?: "无"}")
                }
                OutlinedButton(onClick = viewModel::simulateNavigationPrompt) {
                    Text("音乐输出：${if (state.musicOverBluetooth) "蓝牙 A2DP" else "车机扬声器"}｜duck ${"%.0f%%".format(state.duckLevel * 100)}")
                }
            }
        }
    }
}

@Composable
private fun BusStatusBar(
    busState: String,
    frameRateHz: Double,
    dropRates: Map<String, Double>,
    staleSignals: List<String>,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("CAN 总线：$busState　实测帧率 ${"%.0f".format(frameRateHz)} Hz", style = MaterialTheme.typography.titleMedium)
            if (dropRates.isNotEmpty()) {
                Text(
                    text = dropRates.entries.joinToString("　") { (bus, rate) -> "$bus 丢帧 ${"%.2f".format(rate)}%" },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (staleSignals.isNotEmpty()) {
                Text(
                    text = "超时/无效信号：${staleSignals.take(6).joinToString()}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SignalCard(card: SignalCardUi) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(card.title, style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = card.value,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (card.usable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                )
                Text(" ${card.unit}", style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = "${card.sourceLabel} · ${card.quality}",
                style = MaterialTheme.typography.labelSmall,
                color = if (card.usable) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
            )
        }
    }
}
