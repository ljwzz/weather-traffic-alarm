package com.ljwzz.weathertrafficalarm.ui.zhitu

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun wrap(value: Int, size: Int): Int = ((value % size) + size) % size

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlarmTimeWheelSheet(
    initial: String,
    title: String,
    countdown: (String) -> String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val is24Hour = DateFormat.is24HourFormat(context)
    val parts = remember(initial) { initial.split(':').map { it.toInt() } }
    var hour by remember(initial) { mutableIntStateOf(parts[0]) }
    var minute by remember(initial) { mutableIntStateOf(parts[1]) }
    val time = "%02d:%02d".format(hour, minute)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ZhituColors.Background,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
    ) {
        Column(Modifier.fillMaxWidth().height(630.dp).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss, modifier = Modifier.width(56.dp)) { Text("×", fontSize = 30.sp) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, color = ZhituColors.Ink, fontWeight = FontWeight.Bold)
                    Text(countdown(time), style = MaterialTheme.typography.bodyMedium, color = ZhituColors.Muted)
                }
                TextButton(onClick = { onSave(time) }, modifier = Modifier.width(56.dp)) { Text("✓", fontSize = 28.sp) }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 76.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!is24Hour) WheelColumn(
                    label = "AM/PM", selected = hour / 12, size = 2,
                    valueText = { if (it == 0) "AM" else "PM" },
                    onSelected = { hour = it * 12 + hour % 12 }, modifier = Modifier.weight(1f),
                )
                WheelColumn(
                    label = "时", selected = if (is24Hour) hour else hour % 12,
                    size = if (is24Hour) 24 else 12,
                    valueText = { value -> "%02d".format(if (is24Hour) value else if (value == 0) 12 else value) },
                    onSelected = { hour = if (is24Hour) it else hour / 12 * 12 + it },
                    modifier = Modifier.weight(1f),
                )
                WheelColumn(
                    label = "分", selected = minute, size = 60,
                    valueText = { "%02d".format(it) }, onSelected = { minute = it },
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "上下滑动或点击数字调整时间",
                modifier = Modifier.fillMaxWidth().padding(top = 38.dp),
                color = ZhituColors.Muted,
                style = MaterialTheme.typography.bodySmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun WheelColumn(
    label: String,
    selected: Int,
    size: Int,
    valueText: (Int) -> String,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(selected)
    val select by rememberUpdatedState(onSelected)
    val stepPx = with(LocalDensity.current) { 54.dp.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    Column(
        modifier.pointerInput(size, stepPx) {
            detectVerticalDragGestures(
                onDragEnd = { drag = 0f },
                onDragCancel = { drag = 0f },
                onVerticalDrag = { change, amount ->
                    change.consume()
                    drag += amount
                    var next = current
                    while (drag >= stepPx) { next = wrap(next - 1, size); select(next); drag -= stepPx }
                    while (drag <= -stepPx) { next = wrap(next + 1, size); select(next); drag += stepPx }
                },
            )
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = ZhituColors.Ink, style = MaterialTheme.typography.bodyMedium)
        for (offset in -2..2) {
            val value = wrap(selected + offset, size)
            Box(
                Modifier.fillMaxWidth().height(54.dp)
                    .semantics { contentDescription = "$label ${valueText(value)}" }
                    .clickable { onSelected(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    valueText(value),
                    color = if (offset == 0) ZhituColors.Ink else ZhituColors.Muted.copy(alpha = if (kotlin.math.abs(offset) == 2) .45f else .75f),
                    fontSize = if (offset == 0) 42.sp else 34.sp,
                    fontWeight = if (offset == 0) FontWeight.Medium else FontWeight.Light,
                )
            }
        }
    }
}
