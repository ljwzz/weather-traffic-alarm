package com.ljwzz.weathertrafficalarm.ui.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ljwzz.weathertrafficalarm.R
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmDemoScreen(
    viewModel: AlarmDemoViewModel = hiltViewModel(),
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("通勤闹钟 · 演示模式") })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "添加闹钟",
                )
            }
        },
    ) { padding ->
        if (rows.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "还没有闹钟计划\n点击右下角 + 添加一个",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "演示模式:系统闹钟直接按计划时间触发,不依赖高德/彩云",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(rows, key = { it.plan.id }) { row ->
                    AlarmPlanCard(
                        row = row,
                        onToggle = { viewModel.setEnabled(row.plan, it) },
                        onDelete = { viewModel.delete(row.plan) },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddPlanDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, time, enabled ->
                viewModel.addPlan(name, time, enabled)
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun AlarmPlanCard(
    row: AlarmPlanRow,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = row.plan.name, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "唤醒时间 ${formatTime(row.plan)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (row.nextTriggerAtMillis != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "下次系统闹钟:${formatInstant(row.nextTriggerAtMillis, row.plan.zoneId)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (row.plan.enabled) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "未注册系统闹钟",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Switch(checked = row.plan.enabled, onCheckedChange = onToggle)
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除",
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddPlanDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, time: LocalTime, enabled: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("通勤闹钟") }
    var enabled by remember { mutableStateOf(true) }
    val now = LocalTime.now()
    val timeState = rememberTimePickerState(initialHour = now.hour, initialMinute = now.minute)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加闹钟计划") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "唤醒时间", style = MaterialTheme.typography.labelLarge)
                TimePicker(state = timeState)
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "创建后立即启用", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    onConfirm(name, LocalTime.of(timeState.hour, timeState.minute), enabled)
                },
            ) {
                Text("保存并注册闹钟")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

private fun formatTime(plan: AlarmPlan): String =
    LocalTime.parse(plan.defaultWakeLocalTime).format(DateTimeFormatter.ofPattern("HH:mm"))

private fun formatInstant(millis: Long, zoneId: String): String =
    DateTimeFormatter.ofPattern("MM-dd HH:mm")
        .withZone(ZoneId.of(zoneId))
        .format(Instant.ofEpochMilli(millis))
