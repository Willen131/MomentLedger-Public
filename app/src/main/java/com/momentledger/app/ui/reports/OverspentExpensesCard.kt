package com.momentledger.app.ui.reports

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momentledger.app.domain.engine.BudgetCycle
import com.momentledger.app.domain.engine.BudgetPeriod
import com.momentledger.app.domain.model.Category
import com.momentledger.app.domain.model.TransactionRecord
import com.momentledger.app.ui.components.AppleCard
import com.momentledger.app.ui.components.TransactionTimelineRow
import com.momentledger.app.ui.theme.LocalAppleColors

@Composable
internal fun OverspentExpensesCard(
    records: List<TransactionRecord>,
    categoriesById: Map<String, Category>,
    period: BudgetPeriod,
    cycle: BudgetCycle
) {
    val colors = LocalAppleColors.current
    var expanded by rememberSaveable(period, cycle) { mutableStateOf(false) }
    val title = when (period) {
        BudgetPeriod.DAY -> "本日支出明细"
        BudgetPeriod.MONTH -> "本月支出明细"
        BudgetPeriod.YEAR -> "本年支出明细"
    }

    AppleCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val toggleLabel = if (expanded) "收起" else "展开"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = toggleLabel) { expanded = !expanded }
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = colors.text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${records.size} 笔",
                        color = colors.subText,
                        fontSize = 12.sp
                    )
                }
                Text(
                    text = toggleLabel,
                    color = colors.blue,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            if (expanded) {
                Text(
                    text = "预算按净支出计算，已扣除计入统计的退款。标为「不计入」的支出仅供查看。",
                    color = colors.subText,
                    fontSize = 12.sp
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                ) {
                    itemsIndexed(records, key = { _, record -> record.id }) { index, record ->
                        TransactionTimelineRow(
                            record = record,
                            category = categoriesById[record.categoryId],
                            showDate = period != BudgetPeriod.DAY
                        )
                        if (index != records.lastIndex) {
                            HorizontalDivider(color = colors.separator, thickness = 0.5.dp)
                        }
                    }
                }
            }
        }
    }
}
