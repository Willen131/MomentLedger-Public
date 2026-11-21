package com.momentledger.app.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.momentledger.app.domain.model.TimePeriod
import com.momentledger.app.domain.model.areValidTimePeriods
import com.momentledger.app.domain.model.defaultTimePeriods
import com.momentledger.app.domain.model.formatTimePeriodMinute
import com.momentledger.app.ui.theme.LocalAppleColors

private data class PeriodInput(val label: String, val start: String, val end: String)

private fun List<TimePeriod>.toInputs() = map {
    PeriodInput(it.label, formatTimePeriodMinute(it.startMinute), formatTimePeriodMinute(it.endMinute))
}

internal fun parsePeriodMinute(text: String): Int? {
    val match = Regex("([0-9]{1,2})[:：]([0-9]{2})").matchEntire(text.trim()) ?: return null
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
    fun change(index: Int, input: PeriodInput) {
        inputs = inputs.toMutableList().apply { set(index, input) }
        error = null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义时段") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("使用 HH:mm。六个时段须连续覆盖全天，不能重叠。")
                error?.let { Text(it, color = colors.red) }
                inputs.forEachIndexed { index, input ->
                    OutlinedTextField(
                        value = input.label,
                        onValueChange = { change(index, input.copy(label = it)) },
                        label = { Text("时段名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
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
                TextButton(onClick = { inputs = defaultTimePeriods.toInputs(); error = null }) {
                    Text("恢复默认")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val edited = inputs.mapNotNull { input ->
                    val start = parsePeriodMinute(input.start) ?: return@mapNotNull null
                    val end = parsePeriodMinute(input.end) ?: return@mapNotNull null
                    TimePeriod(input.label.trim(), start, end)
                }
                if (areValidTimePeriods(edited)) onSave(edited)
                else error = "请填写名称及有效时间，并检查时段之间是否有空档或重叠。"
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
