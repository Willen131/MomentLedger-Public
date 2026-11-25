package com.momentledger.app.ui.components

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.Chair
import androidx.compose.material.icons.rounded.CurrencyExchange
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.ElectricalServices
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Hotel
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.LocalFlorist
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.Luggage
import androidx.compose.material.icons.rounded.MedicalServices
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.Redeem
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momentledger.app.R
import com.momentledger.app.domain.model.CaptureSource
import com.momentledger.app.domain.model.Category
import com.momentledger.app.domain.model.HOUR_OF_DAY_UNKNOWN
import com.momentledger.app.domain.model.PaymentChannel
import com.momentledger.app.domain.model.StatsMetricMode
import com.momentledger.app.domain.model.TransactionRecord
import com.momentledger.app.domain.model.TransactionType
import com.momentledger.app.domain.parser.AmountParser
import com.momentledger.app.ui.theme.AppleColors
import com.momentledger.app.ui.theme.LocalAppleColors
import com.momentledger.app.ui.theme.LocalIsDarkTheme
import com.momentledger.app.ui.theme.TnumTextStyle
import com.momentledger.app.ui.theme.parseCategoryColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TimelineTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val TimelineDateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private val TimelineDateOnlyFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")

enum class AppTab(val label: String) {
    CALENDAR("日历"),
    STATS("时刻统计"),
    REPORTS("收支预算"),
    SETTINGS("设置")
}

val supportedCategoryIconKeys: List<String> = listOf(
    "restaurant",
    "local_cafe",
    "directions_car",
    "flight",
    "shopping_bag",
    "shopping_cart",
    "sports_esports",
    "medical_services",
    "fitness_center",
    "home",
    "phone_iphone",
    "menu_book",
    "card_giftcard",
    "pets",
    "work",
    "payments",
    "redeem",
    "receipt_long",
    "trending_up",
    "savings",
    "account_balance_wallet",
    "more_horiz",
    "directions_bus",
    "train",
    "hotel",
    "local_florist",
    "movie",
    "build",
    "photo_camera",
    "local_shipping",
    "local_gas_station",
    "verified_user",
    "chair",
    "electrical_services",
    "luggage",
    "currency_exchange"
) + extraCategoryIcons.keys

fun categoryIconForKey(iconKey: String): ImageVector = when (iconKey.trim()) {
    "restaurant" -> Icons.Rounded.Restaurant
    "directions_car" -> Icons.Rounded.DirectionsCar
    "shopping_bag" -> Icons.Rounded.ShoppingBag
    "shopping_cart" -> Icons.Rounded.ShoppingCart
    "sports_esports" -> Icons.Rounded.SportsEsports
    "medical_services" -> Icons.Rounded.MedicalServices
    "home" -> Icons.Rounded.Home
    "phone_iphone" -> Icons.Rounded.PhoneIphone
    "menu_book" -> Icons.AutoMirrored.Rounded.MenuBook
    "card_giftcard" -> Icons.Rounded.CardGiftcard
    "more_horiz" -> Icons.Rounded.MoreHoriz
    "payments" -> Icons.Rounded.Payments
    "redeem" -> Icons.Rounded.Redeem
    "receipt_long" -> Icons.AutoMirrored.Rounded.ReceiptLong
    "trending_up" -> Icons.AutoMirrored.Rounded.TrendingUp
    "account_balance_wallet" -> Icons.Rounded.AccountBalanceWallet
    "pets" -> Icons.Rounded.Pets
    "work" -> Icons.Rounded.Work
    "local_cafe" -> Icons.Rounded.LocalCafe
    "flight" -> Icons.Rounded.Flight
    "fitness_center" -> Icons.Rounded.FitnessCenter
    "savings" -> Icons.Rounded.Savings
    "directions_bus" -> Icons.Rounded.DirectionsBus
    "train" -> Icons.Rounded.Train
    "hotel" -> Icons.Rounded.Hotel
    "local_florist" -> Icons.Rounded.LocalFlorist
    "movie" -> Icons.Rounded.Movie
    "build" -> Icons.Rounded.Build
    "photo_camera" -> Icons.Rounded.PhotoCamera
    "local_shipping" -> Icons.Rounded.LocalShipping
    "local_gas_station" -> Icons.Rounded.LocalGasStation
    "verified_user" -> Icons.Rounded.VerifiedUser
    "chair" -> Icons.Rounded.Chair
    "electrical_services" -> Icons.Rounded.ElectricalServices
    "luggage" -> Icons.Rounded.Luggage
    "currency_exchange" -> Icons.Rounded.CurrencyExchange
    else -> extraCategoryIcons[iconKey.trim()]?.second ?: Icons.AutoMirrored.Rounded.Label
}

/** 图标的中文可读名称，用于图标选择器的无障碍描述。 */
fun categoryIconLabelForKey(iconKey: String): String = when (iconKey.trim()) {
    "restaurant" -> "餐饮"
    "local_cafe" -> "咖啡"
    "directions_car" -> "汽车"
    "flight" -> "机票"
    "shopping_bag" -> "购物袋"
    "shopping_cart" -> "购物车"
    "sports_esports" -> "游戏"
    "medical_services" -> "医疗"
    "fitness_center" -> "健身"
    "home" -> "住房"
    "phone_iphone" -> "手机"
    "menu_book" -> "书籍"
    "card_giftcard" -> "礼品卡"
    "pets" -> "宠物"
    "work" -> "工作"
    "payments" -> "收款"
    "redeem" -> "兑换"
    "receipt_long" -> "账单"
    "trending_up" -> "理财"
    "savings" -> "储蓄"
    "account_balance_wallet" -> "钱包"
    "more_horiz" -> "其他"
    "directions_bus" -> "公交"
    "train" -> "火车"
    "hotel" -> "住宿"
    "local_florist" -> "鲜花"
    "movie" -> "电影"
    "build" -> "维修"
    "photo_camera" -> "照相"
    "local_shipping" -> "快递"
    "local_gas_station" -> "加油"
    "verified_user" -> "保险"
    "chair" -> "家居"
    "electrical_services" -> "电费"
    "luggage" -> "旅游"
    "currency_exchange" -> "退款"
    else -> extraCategoryIcons[iconKey.trim()]?.first ?: "分类"
}

fun paymentChannelShortLabel(channel: PaymentChannel): String = when (channel) {
    PaymentChannel.WECHAT -> "微信"
    PaymentChannel.ALIPAY -> "支付宝"
    PaymentChannel.UNIONPAY -> "云闪付"
    PaymentChannel.BANK -> "银行卡"
    PaymentChannel.MANUAL -> "手动"
    PaymentChannel.OTHER -> "其他"
}

fun captureSourceLabel(source: CaptureSource): String = when (source) {
    CaptureSource.ACCESSIBILITY -> "无障碍"
    CaptureSource.NOTIFICATION -> "通知"
    CaptureSource.BOTH -> "双通道"
    CaptureSource.IMPORT -> "导入"
    CaptureSource.OCR -> "识图"
    CaptureSource.MANUAL -> "手动"
}

fun transactionTypeLabel(type: TransactionType): String = when (type) {
    TransactionType.EXPENSE -> "支出"
    TransactionType.INCOME -> "收入"
    TransactionType.REFUND -> "退款"
    TransactionType.TRANSFER -> "互转"
}

fun formatTransactionAmount(
    record: TransactionRecord,
    colors: AppleColors,
    includeRefundWord: Boolean = false
): Pair<String, Color> {
    val muted = record.excludedFromStats
    return when (record.type) {
        TransactionType.EXPENSE -> {
            val text = AmountParser.formatYuan(record.amountCents, "-")
            text to if (muted) colors.subText else colors.text
        }
        TransactionType.INCOME -> {
            val text = AmountParser.formatYuan(record.amountCents, "+")
            text to if (muted) colors.subText else colors.green
        }
        TransactionType.REFUND -> {
            val formatted = AmountParser.formatYuan(record.amountCents, "+")
            val text = if (includeRefundWord) "退款 $formatted" else formatted
            text to if (muted) colors.subText else colors.green
        }
        TransactionType.TRANSFER -> {
            AmountParser.formatYuan(record.amountCents) to colors.subText
        }
    }
}

@Composable
fun AppleCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    accent: Color? = null,
    shape: Shape = RoundedCornerShape(22.dp),
    glass: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalAppleColors.current
    val dark = LocalIsDarkTheme.current
    val surface = if (glass) {
        lerp(colors.card, accent ?: colors.blue, if (dark) 0.13f else 0.082f).copy(alpha = 0.95f)
    } else {
        colors.card
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier
                        .clip(shape)
                        .clickable(onClick = onClick)
                } else {
                    Modifier
                }
            ),
        shape = shape,
        color = surface,
        border = BorderStroke(
            width = 1.dp,
            color = (accent ?: colors.separator).copy(alpha = if (dark) 0.23f else 0.12f)
        ),
        tonalElevation = 0.dp,
        shadowElevation = if (dark) 0.dp else 1.dp
    ) {
        Box {
            if (glass) {
                Box(
                    Modifier.matchParentSize()
                        .bocchiGlassFill(colors.card.copy(alpha = 0.32f), accent ?: colors.blue)
                        .border(1.dp, bocchiGlassRim(), shape)
                )
            } else if (accent != null) {
                Box(
                    Modifier.matchParentSize().background(
                        Brush.linearGradient(
                            listOf(
                                accent.copy(alpha = if (dark) 0.10f else 0.065f),
                                Color.Transparent
                            )
                        )
                    )
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(contentPadding),
                content = content
            )
        }
    }
}

@Composable
fun <T> AppleSegmentedControl(
    items: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    val colors = LocalAppleColors.current
    val isDark = LocalIsDarkTheme.current
    val selectedIndex = items.indexOfFirst { it.first == selected }.coerceAtLeast(0)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(colors.segBg)
            .padding(3.dp)
    ) {
        val segmentWidth = maxWidth / items.size
        val thumbOffset by animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "segmentedThumbOffset"
        )

        Surface(
            modifier = Modifier
                .offset { androidx.compose.ui.unit.IntOffset(thumbOffset.roundToPx(), 0) }
                .width(segmentWidth)
                .height(32.dp),
            shape = CircleShape,
            color = colors.segActive,
            border = BorderStroke(1.dp, bocchiGlassRim()),
            shadowElevation = if (isDark) 1.dp else 2.dp
        ) {
            Box(Modifier.fillMaxSize().bocchiGlassFill(colors.segActive, colors.blue))
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            items.forEach { (value, label) ->
                val isSelected = value == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            onSelect(value)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = colors.text,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
internal fun AppleLargeTopBar(
    title: String,
    stage: BocchiStage,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val colors = LocalAppleColors.current
    val dark = LocalIsDarkTheme.current
    val accent = stage.motif.accent(colors)
    val shape = BocchiTicketShape
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 12.dp)
            .clip(shape)
            .bocchiGlassFill(colors.card, accent)
            .border(1.dp, bocchiGlassRim(), shape)
    ) {
        Image(
            painter = painterResource(stage.banner),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
            alpha = if (dark) 0.52f else 0.82f
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.horizontalGradient(
                        0f to colors.card.copy(alpha = if (dark) 0.95f else 0.88f),
                        0.51f to colors.card.copy(alpha = if (dark) 0.79f else 0.59f),
                        1f to colors.card.copy(alpha = if (dark) 0.25f else 0.10f)
                    )
                )
        )
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = if (dark) 0.08f else 0.52f),
                    0.38f to Color.Transparent,
                    1f to accent.copy(alpha = if (dark) 0.08f else 0.055f)
                )
            )
        )
        BocchiTicketPerforation(accent.copy(alpha = if (dark) 0.52f else 0.41f), Modifier.matchParentSize())
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        color = colors.subText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = title,
                        color = colors.text,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    BocchiSticker(stage.sticker, Modifier.size(58.dp))
                }
            }
            if (trailing != null) {
                Spacer(modifier = Modifier.width(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = trailing
                )
            }
        }
    }
}

@Composable
fun AppleBottomBar(
    selectedTab: AppTab,
    onSelectTab: (AppTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppleColors.current
    val dark = LocalIsDarkTheme.current
    val dockShape = RoundedCornerShape(32.dp)
    val dockTint by animateColorAsState(
        targetValue = selectedTab.accent(colors),
        label = "bottom bar material tint"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, colors.bg)))
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Box(
            Modifier.fillMaxWidth()
                .shadow(if (dark) 8.dp else 12.dp, dockShape)
                .clip(dockShape)
                .border(1.dp, bocchiGlassRim(), dockShape)
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Image(
                    painter = painterResource(R.drawable.bocchi_stage_art),
                    contentDescription = null,
                    modifier = Modifier.matchParentSize().blur(13.dp),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.BottomCenter,
                    alpha = if (dark) 0.38f else 0.69f
                )
            }
            Box(
                Modifier.matchParentSize()
                    .bocchiGlassFill(
                        colors.tabBg.copy(
                            alpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                if (dark) 0.88f else 0.73f
                            } else {
                                if (dark) 0.96f else 0.94f
                            }
                        ),
                        dockTint
                    )
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup()
                    .padding(horizontal = 5.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AppTab.entries.forEach { tab ->
                    val sticker = when (tab) {
                        AppTab.CALENDAR -> R.drawable.bocchi_hitori
                        AppTab.STATS -> R.drawable.bocchi_nijika
                        AppTab.REPORTS -> R.drawable.bocchi_ryo
                        AppTab.SETTINGS -> R.drawable.bocchi_kita
                    }
                    val accent = tab.accent(colors)
                    val isSelected = tab == selectedTab
                    val shape = RoundedCornerShape(25.dp)
                    val tintColor by animateColorAsState(
                        targetValue = if (isSelected) lerp(accent, colors.text, 0.14f) else colors.text,
                        label = "tabColor_${tab.name}"
                    )
                    val background by animateColorAsState(
                        targetValue = if (isSelected) {
                            accent.copy(alpha = if (dark) 0.22f else 0.11f)
                        } else {
                            accent.copy(alpha = 0f)
                        },
                        label = "tabBackground_${tab.name}"
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(shape)
                            .bocchiGlassFill(background, accent)
                            .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelectTab(tab) })
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        BocchiSticker(sticker, Modifier.size(45.dp))
                        Text(
                            text = tab.label,
                            color = tintColor,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Box(
                            Modifier
                                .size(width = 14.dp, height = 2.dp)
                                .background(if (isSelected) accent else Color.Transparent, CircleShape)
                        )
                    }
                }
            }
        }
    }
}

private fun AppTab.accent(colors: AppleColors): Color = when (this) {
    AppTab.CALENDAR -> colors.blue
    AppTab.STATS -> colors.orange
    AppTab.REPORTS -> colors.indigo
    AppTab.SETTINGS -> colors.red
}

@Composable
fun CategoryIconBadge(
    iconKey: String,
    colorHex: String,
    contentDescription: String? = null,
    size: Dp = 36.dp,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppleColors.current
    val resolvedColor = remember(colorHex, colors.blue) {
        parseCategoryColor(colorHex, colors.blue)
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(resolvedColor.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        BocchiCategoryIcon(
            iconKey = iconKey,
            contentDescription = contentDescription,
            tint = resolvedColor,
            modifier = Modifier.size(size * 0.76f)
        )
    }
}

@Composable
fun CategoryIconBadge(
    category: Category?,
    size: Dp = 36.dp,
    modifier: Modifier = Modifier
) {
    CategoryIconBadge(
        iconKey = category?.iconKey.orEmpty(),
        colorHex = category?.colorHex.orEmpty(),
        contentDescription = category?.name,
        size = size,
        modifier = modifier
    )
}

@Composable
fun ChannelBadge(
    channel: PaymentChannel,
    fundingMethod: String = "",
    modifier: Modifier = Modifier,
    customChannel: String? = null
) {
    val colors = LocalAppleColors.current
    val shortLabel = customChannel ?: paymentChannelShortLabel(channel)
    val funding = fundingMethod.trim()
    val text = if (funding.isNotEmpty() && funding != shortLabel) {
        "$shortLabel · $funding"
    } else {
        shortLabel
    }
    Text(
        text = text,
        color = colors.subText,
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

@Composable
fun CaptureSourcePill(
    source: CaptureSource,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppleColors.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(colors.segBg)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = captureSourceLabel(source),
            color = colors.subText,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
fun MetricModePill(
    mode: StatsMetricMode,
    onToggle: (StatsMetricMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppleColors.current
    val dark = LocalIsDarkTheme.current
    Row(
        modifier = modifier
            .clip(CircleShape)
            .bocchiGlassFill(colors.tabBg.copy(alpha = if (dark) 0.88f else 0.83f), colors.orange)
            .border(1.dp, bocchiGlassRim(), CircleShape)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(
            StatsMetricMode.NET_EXPENSE to "支出",
            StatsMetricMode.INCOME to "收入"
        ).forEach { (option, label) ->
            val selected = mode == option
            Surface(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        onToggle(option)
                    },
                shape = CircleShape,
                color = if (selected) colors.segActive else Color.Transparent,
                shadowElevation = if (selected) 1.dp else 0.dp
            ) {
                Text(
                    text = label,
                    color = if (selected) colors.text else colors.subText,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
fun TransactionTimelineRow(
    record: TransactionRecord,
    category: Category?,
    zoneId: ZoneId = ZoneId.systemDefault(),
    showDate: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppleColors.current
    val formattedTime = remember(record.timestampMillis, record.hourOfDay, zoneId, showDate) {
        val zoned = Instant.ofEpochMilli(record.timestampMillis).atZone(zoneId)
        if (record.hourOfDay == HOUR_OF_DAY_UNKNOWN) {
            if (showDate) "${zoned.format(TimelineDateOnlyFormatter)} 全天" else "全天"
        } else {
            val formatter = if (showDate) TimelineDateTimeFormatter else TimelineTimeFormatter
            zoned.format(formatter)
        }
    }
    val channelLabel = record.customChannel ?: paymentChannelShortLabel(record.channel)
    val title = remember(record.merchant, category, channelLabel) {
        record.merchant.trim().ifEmpty {
            category?.name ?: channelLabel
        }
    }
    val (amountText, amountColor) = remember(record.type, record.amountCents, record.excludedFromStats, colors) {
        formatTransactionAmount(record = record, colors = colors, includeRefundWord = false)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
            .alpha(if (record.excludedFromStats) 0.55f else 1f)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CategoryIconBadge(category = category, size = 36.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = title,
                    color = colors.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (record.type == TransactionType.REFUND) {
                    InlineStatusPill(
                        text = "退款",
                        containerColor = colors.green.copy(alpha = 0.14f),
                        contentColor = colors.green
                    )
                }
                if (record.excludedFromStats) {
                    InlineStatusPill(
                        text = "不计入",
                        containerColor = colors.segBg,
                        contentColor = colors.subText
                    )
                }
            }
            Spacer(modifier = Modifier.height(3.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = formattedTime,
                    style = TnumTextStyle(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.subText
                    ),
                    maxLines = 1
                )
                Text(text = "·", color = colors.subText, fontSize = 11.sp)
                ChannelBadge(
                    channel = record.channel,
                    customChannel = record.customChannel,
                    fundingMethod = record.fundingMethod,
                    modifier = Modifier.weight(1f, fill = false)
                )
                CaptureSourcePill(source = record.captureSource)
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = amountText,
            style = TnumTextStyle(
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = amountColor
            ),
            maxLines = 1
        )
    }
}

@Composable
private fun InlineStatusPill(
    text: String,
    containerColor: Color,
    contentColor: Color
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(containerColor)
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(
            text = text,
            color = contentColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}
