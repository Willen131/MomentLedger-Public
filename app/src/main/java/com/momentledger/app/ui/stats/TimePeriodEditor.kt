package com.momentledger.app.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.momentledger.app.domain.model.TimePeriod
import com.momentledger.app.domain.model.areValidTimePeriods
import com.momentledger.app.domain.model.defaultTimePeriods
import com.momentledger.app.domain.model.formatTimePeriodMinute
import com.momentledger.app.ui.theme.LocalAppleColors
import kotlinx.coroutines.launch

private data class PeriodInput(
    val label: String,
    val start: String,
    val end: String,
    val id: String = java.util.UUID.randomUUID().toString()
)

private val periodTimePattern = Regex("([0-9]{1,2})[:：]([0-9]{2})")

private fun List<TimePeriod>.toInputs() = map {
    PeriodInput(it.label, formatTimePeriodMinute(it.startMinute), formatTimePeriodMinute(it.endMinute))
}

private fun PeriodInput.toPeriod(): TimePeriod? {
    val startMinute = parsePeriodMinute(start) ?: return null
    val endMinute = parsePeriodMinute(end) ?: return null
    return TimePeriod(label.trim(), startMinute, endMinute)
}

internal fun parsePeriodMinute(text: String): Int? {
    val match = periodTimePattern.matchEntire(text.trim()) ?: return null
    val hour = match.groupValues[1].toInt()
    val minute = match.groupValues[2].toInt()
    return when {
        hour == 24 && minute == 0 -> 1440
        hour in 0..23 && minute in 0..59 -> hour * 60 + minute
        else -> null
    }
}

@Composable
internal fun TimePeriodEditor(
    periods: List<TimePeriod>,
    onSave: (List<TimePeriod>) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalAppleColors.current
    var inputs by remember(periods) { mutableStateOf(periods.toInputs()) }
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    fun change(index: Int, input: PeriodInput) {
        inputs = inputs.toMutableList().apply { set(index, input) }
        error = null
    }

    fun addPeriod() {
        val edited = inputs.mapNotNull { it.toPeriod() }
        val splitIndex = if (edited.size == inputs.size && areValidTimePeriods(edited)) {
            edited.indices.maxBy { edited[it].endMinute - edited[it].startMinute }
        } else {
            null
        }
        val split = splitIndex?.let { edited[it] }
        val addedIndex = if (splitIndex != null && split != null && split.endMinute - split.startMinute >= 2) {
            val boundary = formatTimePeriodMinute((split.startMinute + split.endMinute) / 2)
            inputs = inputs.toMutableList().apply {
                val original = this[splitIndex]
                this[splitIndex] = original.copy(end = boundary)
                add(splitIndex + 1, PeriodInput("新时段", boundary, original.end))
            }
            splitIndex + 1
        } else {
            inputs = inputs + PeriodInput("", "", "")
            inputs.lastIndex
        }
        error = null
        scope.launch { listState.animateScrollToItem(addedIndex) }
    }

    fun removePeriod(index: Int) {
        val removed = inputs[index].toPeriod()
        val edited = inputs.toMutableList()
        if (removed != null) {
            val previous = inputs.indexOfFirst { parsePeriodMinute(it.end) == removed.startMinute }
            val next = inputs.indexOfFirst { parsePeriodMinute(it.start) == removed.endMinute }
            when {
                previous >= 0 && previous != index -> edited[previous] = edited[previous].copy(end = inputs[index].end)
                next >= 0 && next != index -> edited[next] = edited[next].copy(start = inputs[index].start)
            }
        }
        edited.removeAt(index)
        inputs = edited
        error = null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义时段") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("当前 ${inputs.size} 个时段。用 HH:mm 填写起止时间，时段须连续覆盖 00:00–24:00。")
                error?.let { Text(it, color = colors.red) }
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false).heightIn(max = 350.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(inputs, key = { _, item -> item.id }) { index, input ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = input.label,
                                    onValueChange = { change(index, input.copy(label = it)) },
                                    label = { Text("时段名称") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { removePeriod(index) }, enabled = inputs.size > 1) {
                                    Text("删除")
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = input.start,
                                    onValueChange = { change(index, input.copy(start = it)) },
                                    label = { Text("开始") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = input.end,
                                    onValueChange = { change(index, input.copy(end = it)) },
                                    label = { Text("结束") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = ::addPeriod, enabled = inputs.size < 1440) { Text("添加时段") }
                    TextButton(onClick = {
                        inputs = defaultTimePeriods.toInputs()
                        error = null
                        scope.launch { listState.scrollToItem(0) }
                    }) { Text("恢复默认") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val edited = inputs.mapNotNull { it.toPeriod() }
                when {
                    edited.size != inputs.size || edited.any { it.startMinute == 1440 } ->
                        error = "请输入有效时间。24:00 仅可用作结束时间。"
                    edited.any { it.label.isBlank() } ->
                        error = "请填写所有时段的名称。"
                    !areValidTimePeriods(edited) ->
                        error = "开始必须早于结束，全天不能有空档或重叠。"
                    else -> onSave(edited)
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
