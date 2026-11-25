package com.momentledger.app.ui.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momentledger.app.R
import com.momentledger.app.domain.engine.BudgetAlert
import com.momentledger.app.domain.engine.BudgetPeriod
import com.momentledger.app.domain.engine.BudgetProgress
import com.momentledger.app.domain.parser.AmountParser
import com.momentledger.app.ui.components.AppleCard
import com.momentledger.app.ui.components.BocchiSticker
import com.momentledger.app.ui.components.BocchiTicketShape
import com.momentledger.app.ui.theme.LocalAppleColors
import com.momentledger.app.ui.theme.TnumTextStyle

internal data class BudgetNotice(
    val period: BudgetPeriod,
    val progress: BudgetProgress,
    val affectedPeriods: Int = 1
) : SnackbarVisuals {
    val exceeded: Boolean get() = progress.alert == BudgetAlert.OVER
    val title: String get() = when {
        exceeded -> "${period.budgetLabel}已超支"
        progress.remainingCents == 0L -> "${period.budgetLabel}已用完"
        else -> "${period.budgetLabel}接近上限"
    }
    val amount: String get() = if (exceeded) {
        "超出 ${AmountParser.formatYuan(progress.overCents)}"
    } else {
        "剩余 ${AmountParser.formatYuan(progress.remainingCents)}"
    }
    override val message: String get() = "$title · $amount"
    override val actionLabel: String get() = "查看预算"
    override val withDismissAction: Boolean get() = true
    override val duration: SnackbarDuration get() = SnackbarDuration.Long
}

@Composable
internal fun BudgetReactionSnackbar(notice: BudgetNotice, data: SnackbarData, canOpenBudget: Boolean) {
    BudgetReactionCard(
        notice = notice,
        onOpenBudget = if (canOpenBudget) data::performAction else null,
        onDismiss = data::dismiss
    )
}

@Composable
internal fun BudgetReactionCard(
    notice: BudgetNotice,
    onOpenBudget: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null
) {
    val colors = LocalAppleColors.current
    val accent = if (notice.exceeded) colors.red else colors.orange
    val popup = onDismiss != null
    val sticker = if (popup) {
        if (notice.exceeded) R.drawable.bocchi_budget_hitori_cry else R.drawable.bocchi_budget_nijika_near
    } else {
        if (notice.exceeded) R.drawable.bocchi_budget_ryo_cry else R.drawable.bocchi_budget_kita_near
    }
    AppleCard(
        modifier = if (popup) Modifier.shadow(9.dp, BocchiTicketShape, clip = false) else Modifier,
        contentPadding = PaddingValues(start = 19.dp, top = 12.dp, end = 17.dp, bottom = if (popup) 3.dp else 12.dp),
        accent = accent,
        shape = BocchiTicketShape,
        glass = popup
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(notice.title, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    notice.amount,
                    style = TnumTextStyle(fontSize = 21.sp, fontWeight = FontWeight.Bold, color = accent)
                )
                if (notice.affectedPeriods > 1) {
                    Text("共 ${notice.affectedPeriods} 项预算提醒", color = colors.subText, fontSize = 11.sp)
                }
            }
            BocchiSticker(sticker, Modifier.size(if (popup) 68.dp else 82.dp))
        }
        if (onDismiss != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onOpenBudget != null) {
                    TextButton(onClick = onOpenBudget) {
                        Text(notice.actionLabel, color = accent, fontWeight = FontWeight.SemiBold)
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭预算提醒", tint = colors.subText)
                }
            }
        }
    }
}
