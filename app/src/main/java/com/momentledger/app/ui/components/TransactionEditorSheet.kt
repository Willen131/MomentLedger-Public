package com.momentledger.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.flowOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.momentledger.app.ui.theme.LocalIsDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momentledger.app.domain.engine.CategoryMatcher
import com.momentledger.app.domain.model.CaptureSource
import com.momentledger.app.domain.model.Category
import com.momentledger.app.domain.model.CategoryType
import com.momentledger.app.domain.model.DefaultCategories
import com.momentledger.app.domain.model.HOUR_OF_DAY_UNKNOWN
import com.momentledger.app.domain.model.PaymentChannel
import com.momentledger.app.domain.model.TransactionRecord
import com.momentledger.app.domain.model.TransactionType
import com.momentledger.app.domain.parser.AmountParser
import com.momentledger.app.ui.settings.CategoryEditorSheet
import com.momentledger.app.ui.theme.LocalAppleColors
import com.momentledger.app.ui.theme.TnumTextStyle
import com.momentledger.app.ui.theme.parseCategoryColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

private val SheetDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日")
private val SheetTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun TransactionEditorSheet(
    initial: TransactionRecord?,
    categories: List<Category>,
    onSave: (TransactionRecord) -> Unit,
    onDelete: ((Long) -> Unit)?,
    onDismiss: () -> Unit,
    defaultDate: LocalDate = LocalDate.now(),
    autoFocusAmount: Boolean = false,
    onSwitchToScreenshotOcr: (() -> Unit)? = null,
    onSaveCategory: (suspend (Category) -> Unit)? = null
) {
    val colors = LocalAppleColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val zoneId = remember { ZoneId.systemDefault() }
    val amountFocusRequester = remember { FocusRequester() }

    LaunchedEffect(autoFocusAmount) {
        if (autoFocusAmount) {
            runCatching { amountFocusRequester.requestFocus() }
        }
    }

    val editorActions = LocalLedgerEditorActions.current
    val customChannelsFlow = remember(editorActions) {
        editorActions?.preferences?.customChannels ?: flowOf(emptyList<String>())
    }
    val customChannels by customChannelsFlow.collectAsStateWithLifecycle(
        initialValue = editorActions?.let { it.preferences.customChannels.value } ?: emptyList()
    )
    val hiddenChannelsFlow = remember(editorActions) {
        editorActions?.preferences?.hiddenChannels ?: flowOf(emptySet<PaymentChannel>())
    }
    val hiddenChannels by hiddenChannelsFlow.collectAsStateWithLifecycle(
        initialValue = editorActions?.let { it.preferences.hiddenChannels.value } ?: emptySet()
    )
    var customChannel by remember(initial?.id, defaultDate) { mutableStateOf(initial?.customChannel) }
    var channelEditorOpen by remember { mutableStateOf(false) }
    var channelToDelete by remember { mutableStateOf<ChannelOption?>(null) }
    var showRestoreCategoriesDialog by remember { mutableStateOf(false) }
    var showRestoreChannelsDialog by remember { mutableStateOf(false) }
    var categoryRestoreError by remember { mutableStateOf<String?>(null) }
    var pendingAddedChannels by remember(initial?.id, defaultDate) {
        mutableStateOf(emptyList<String>())
    }
    var categoryToDelete by remember { mutableStateOf<Category?>(null) }
    val availableCategories = remember(categories, initial?.categoryId) {
        categories.ifEmpty { DefaultCategories.all }.filter { !it.isArchived || it.id == initial?.categoryId }
    }
    val categorySaveScope = rememberCoroutineScope()
    var awaitingCategorySave by remember { mutableStateOf(false) }
    var categorySaveError by remember { mutableStateOf<String?>(null) }
    var pendingSavedCategory by remember { mutableStateOf<Category?>(null) }
    val displayedCategories = remember(availableCategories, pendingSavedCategory) {
        val pending = pendingSavedCategory
        if (pending != null && availableCategories.none { it.id == pending.id }) {
            availableCategories + pending
        } else {
            availableCategories
        }
    }

    val initialCategoryType = when (initial?.type ?: TransactionType.EXPENSE) {
        TransactionType.INCOME, TransactionType.REFUND -> CategoryType.INCOME
        TransactionType.EXPENSE, TransactionType.TRANSFER -> CategoryType.EXPENSE
    }
    val initialCategoryCandidates = remember(displayedCategories, initialCategoryType) {
        displayedCategories
            .filter { it.categoryType == initialCategoryType }
            .sortedWith(compareBy<Category> { it.sortOrder }.thenBy { it.id })
    }
    // 旧退款账单若挂着历史支出分类，默认回退到「退款」收入分类。
    val initialSelectedCategoryId = initial?.categoryId
        ?.takeIf { id -> initialCategoryCandidates.any { it.id == id } }
        ?: if (initial?.type == TransactionType.REFUND) {
            DefaultCategories.REFUND_CATEGORY_ID
        } else {
            fallbackCategoryIdFor(initialCategoryType, initialCategoryCandidates)
        }

    val initialDate = remember(initial, defaultDate, zoneId) {
        if (initial != null) {
            runCatching { LocalDate.parse(initial.localDate) }.getOrElse {
                Instant.ofEpochMilli(initial.timestampMillis).atZone(zoneId).toLocalDate()
            }
        } else {
            defaultDate
        }
    }
    val initialTime = remember(initial, zoneId) {
        if (initial != null) {
            Instant.ofEpochMilli(initial.timestampMillis).atZone(zoneId).toLocalTime()
                .withSecond(0).withNano(0)
        } else {
            LocalTime.now(zoneId).withSecond(0).withNano(0)
        }
    }

    var txType by remember(initial?.id, defaultDate) {
        mutableStateOf(initial?.type ?: TransactionType.EXPENSE)
    }
    var amountInput by remember(initial?.id, defaultDate) {
        mutableStateOf(
            initial?.let { BigDecimal.valueOf(it.amountCents, 2).toPlainString() }.orEmpty()
        )
    }
    var amountError by remember(initial?.id, defaultDate) { mutableStateOf<String?>(null) }
    var selectedDate by remember(initial?.id, defaultDate) { mutableStateOf(initialDate) }
    var selectedTime by remember(initial?.id, defaultDate) { mutableStateOf(initialTime) }
    var hasExplicitTime by remember(initial?.id, defaultDate) {
        mutableStateOf(initial == null || initial.hourOfDay != HOUR_OF_DAY_UNKNOWN)
    }
    var merchant by remember(initial?.id, defaultDate) { mutableStateOf(initial?.merchant.orEmpty()) }
    var selectedCategoryId by remember(initial?.id, defaultDate) {
        mutableStateOf(initialSelectedCategoryId)
    }
    var channel by remember(initial?.id, defaultDate) {
        mutableStateOf(initial?.channel ?: PaymentChannel.MANUAL)
    }
    var fundingMethod by remember(initial?.id, defaultDate) {
        mutableStateOf(initial?.fundingMethod.orEmpty())
    }
    var note by remember(initial?.id, defaultDate) { mutableStateOf(initial?.note.orEmpty()) }
    var excludedFromStats by remember(initial?.id, defaultDate) {
        mutableStateOf(initial?.excludedFromStats ?: false)
    }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showCategoryEditor by remember { mutableStateOf(false) }

    val expectedCategoryType = editorCategoryTypeFor(txType)
    val categoryDirection = expectedCategoryType
    val filteredCategories = remember(displayedCategories, expectedCategoryType) {
        displayedCategories
            .filter { it.categoryType == expectedCategoryType }
            .sortedWith(compareBy<Category> { it.sortOrder }.thenBy { it.id })
    }

    val effectiveCategoryId = remember(filteredCategories, selectedCategoryId, expectedCategoryType, txType) {
        if (filteredCategories.any { it.id == selectedCategoryId }) {
            selectedCategoryId
        } else {
            fallbackCategoryIdFor(
                categoryType = expectedCategoryType,
                categories = filteredCategories,
                preferredId = if (txType == TransactionType.REFUND) DefaultCategories.REFUND_CATEGORY_ID else null
            )
        }
    }

    LaunchedEffect(pendingSavedCategory, categories) {
        if (categories.any { it.id == pendingSavedCategory?.id }) pendingSavedCategory = null
    }

    // 仅展示未被隐藏/删除的渠道；编辑旧账单时额外保留其正在使用的历史渠道。
    val visibleChannelOptions = remember(
        hiddenChannels,
        customChannels,
        pendingAddedChannels,
        initial?.id,
        initial?.channel,
        initial?.customChannel
    ) {
        val options = mutableListOf<ChannelOption>()
        PaymentChannel.entries.forEach { option ->
            if (option !in hiddenChannels) {
                options += ChannelOption(channel = option, customName = null)
            }
        }
        // 新增渠道后先本地展示，避免在 prefs Flow 回流前被自动选择逻辑覆盖。
        val availableCustomChannels = (customChannels + pendingAddedChannels)
            .filter { it.isNotBlank() }
            .distinct()
        availableCustomChannels.forEach { name ->
            options += ChannelOption(channel = PaymentChannel.OTHER, customName = name)
        }
        if (initial != null) {
            val historical = when {
                initial.customChannel != null &&
                    availableCustomChannels.none { it == initial.customChannel } ->
                    ChannelOption(
                        channel = initial.channel,
                        customName = initial.customChannel,
                        isHistorical = true
                    )
                initial.customChannel == null && initial.channel in hiddenChannels ->
                    ChannelOption(
                        channel = initial.channel,
                        customName = null,
                        isHistorical = true
                    )
                else -> null
            }
            if (historical != null && options.none {
                    it.channel == historical.channel && it.customName == historical.customName
                }
            ) {
                options += historical
            }
        }
        options
    }

    LaunchedEffect(customChannels, pendingAddedChannels) {
        if (pendingAddedChannels.any { pending -> customChannels.any { it == pending } }) {
            pendingAddedChannels = pendingAddedChannels.filterNot { pending ->
                customChannels.any { it == pending }
            }
        }
    }

    val hasValidChannelSelection = visibleChannelOptions.any {
        it.channel == channel && it.customName == customChannel
    }

    LaunchedEffect(visibleChannelOptions, channel, customChannel) {
        if (!hasValidChannelSelection) {
            val fallback = visibleChannelOptions.firstOrNull { !it.isHistorical }
                ?: visibleChannelOptions.firstOrNull()
            if (fallback != null) {
                channel = fallback.channel
                customChannel = fallback.customName
            }
        }
    }

    val canSubmit = !awaitingCategorySave && hasValidChannelSelection

    val showRefundFallbackHint = initial?.type == TransactionType.REFUND &&
        initial.categoryId != DefaultCategories.REFUND_CATEGORY_ID &&
        txType == TransactionType.REFUND &&
        effectiveCategoryId == DefaultCategories.REFUND_CATEGORY_ID

    fun handleSave() {
        if (!canSubmit) return
        val parsedCents = AmountParser.parseToCents(amountInput)
        if (parsedCents == null || parsedCents <= 0L) {
            amountError = "请输入大于 0 的有效金额"
            return
        }
        val resolvedTimeForStamp = if (hasExplicitTime) {
            selectedTime.withSecond(0).withNano(0)
        } else {
            LocalTime.of(12, 0, 0)
        }
        val timestampMillis = ZonedDateTime.of(
            selectedDate,
            resolvedTimeForStamp,
            zoneId
        ).toInstant().toEpochMilli()
        val resolvedHour = if (hasExplicitTime) selectedTime.hour else HOUR_OF_DAY_UNKNOWN
        val now = System.currentTimeMillis()

        val resultRecord = if (initial != null) {
            initial.copy(
                timestampMillis = timestampMillis,
                localDate = selectedDate.toString(),
                hourOfDay = resolvedHour,
                amountCents = parsedCents,
                type = txType,
                channel = channel,
                customChannel = customChannel,
                fundingMethod = fundingMethod.trim(),
                merchant = merchant.trim(),
                categoryId = effectiveCategoryId,
                note = note.trim(),
                excludedFromStats = excludedFromStats,
                updatedAtMillis = now
            )
        } else {
            TransactionRecord(
                id = 0L,
                timestampMillis = timestampMillis,
                localDate = selectedDate.toString(),
                hourOfDay = resolvedHour,
                amountCents = parsedCents,
                type = txType,
                channel = channel,
                customChannel = customChannel,
                fundingMethod = fundingMethod.trim(),
                merchant = merchant.trim(),
                categoryId = effectiveCategoryId,
                note = note.trim(),
                excludedFromStats = excludedFromStats,
                captureSource = CaptureSource.MANUAL,
                externalOrderId = null,
                createdAtMillis = now,
                updatedAtMillis = now
            )
        }
        onSave(resultRecord)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.bg,
        contentColor = colors.text
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
        ) {
            // Sheet top navigation header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = "取消",
                        color = colors.blue,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = if (initial == null) "记一笔" else "编辑账单",
                    color = colors.text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = ::handleSave, enabled = canSubmit) {
                    Text(
                        text = "保存",
                        color = if (canSubmit) {
                            colors.blue
                        } else {
                            colors.subText.copy(alpha = 0.45f)
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (onSwitchToScreenshotOcr != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = onSwitchToScreenshotOcr),
                        shape = RoundedCornerShape(50),
                        color = colors.green.copy(alpha = 0.13f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DocumentScanner,
                                contentDescription = null,
                                tint = colors.green,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "改用截图识账（离线识别）",
                                color = colors.green,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Transaction type selector：账单仅支持支出/收入；互转账单保持原类型。
            if (initial?.type == TransactionType.TRANSFER) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = colors.segBg
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "互转",
                            color = colors.text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "互转账单保持原类型，不会自动转为支出或收入",
                            color = colors.subText,
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                AppleSegmentedControl(
                    items = listOf(
                        TransactionType.EXPENSE to "支出",
                        TransactionType.INCOME to "收入"
                    ),
                    selected = if (txType == TransactionType.REFUND) {
                        TransactionType.INCOME
                    } else {
                        txType
                    },
                    onSelect = { nextType ->
                        when (nextType) {
                            TransactionType.EXPENSE -> txType = TransactionType.EXPENSE
                            // 编辑退款时点击“收入”仍保持退款语义，改选普通收入分类才会转为收入。
                            TransactionType.INCOME -> if (txType != TransactionType.REFUND) {
                                txType = TransactionType.INCOME
                            }
                            else -> Unit
                        }
                        val nextCategoryType = editorCategoryTypeFor(txType)
                        val nextList = displayedCategories
                            .filter { it.categoryType == nextCategoryType }
                            .sortedWith(compareBy<Category> { it.sortOrder }.thenBy { it.id })
                        if (nextList.none { it.id == selectedCategoryId }) {
                            selectedCategoryId = fallbackCategoryIdFor(
                                categoryType = nextCategoryType,
                                categories = nextList,
                                preferredId = if (txType == TransactionType.REFUND) {
                                    DefaultCategories.REFUND_CATEGORY_ID
                                } else {
                                    null
                                }
                            )
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Amount card
            AppleCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                Text(
                    text = "金额（元）",
                    color = colors.subText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "¥",
                        style = TnumTextStyle(
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.text
                        )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (amountInput.isEmpty()) {
                            Text(
                                text = "0.00",
                                style = TnumTextStyle(
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.subText.copy(alpha = 0.45f)
                                )
                            )
                        }
                        BasicTextField(
                            value = amountInput,
                            onValueChange = { raw ->
                                val filtered = sanitizeAmountInput(raw)
                                amountInput = filtered
                                if (amountError != null) {
                                    amountError = null
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            textStyle = TnumTextStyle(
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.text
                            ),
                            cursorBrush = SolidColor(colors.blue),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(amountFocusRequester)
                        )
                    }
                }
                if (amountError != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = amountError.orEmpty(),
                        color = colors.red,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Date, Time, Merchant & Note card
            AppleCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                // Date & Time row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "时间",
                        color = colors.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        PickerPillButton(
                            icon = Icons.Rounded.CalendarToday,
                            label = selectedDate.format(SheetDateFormatter),
                            onClick = { showDatePicker = true }
                        )
                        PickerPillButton(
                            icon = Icons.Rounded.AccessTime,
                            label = if (hasExplicitTime) selectedTime.format(SheetTimeFormatter) else "全天",
                            onClick = { showTimePicker = true }
                        )
                        if (hasExplicitTime) {
                            Text(
                                text = "全天",
                                color = colors.subText,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { hasExplicitTime = false }
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(color = colors.separator, thickness = 0.5.dp)

                // Merchant row
                SheetTextInputRow(
                    label = "商户",
                    value = merchant,
                    placeholder = "例如：瑞幸咖啡、地铁出行",
                    onValueChange = { merchant = it }
                )

                HorizontalDivider(color = colors.separator, thickness = 0.5.dp)

                // Note row
                SheetTextInputRow(
                    label = "备注",
                    value = note,
                    placeholder = "可选，如：帮同事垫付",
                    onValueChange = { note = it }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Category selection grid
            AppleCard(contentPadding = PaddingValues(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                initial?.type == TransactionType.TRANSFER -> "分类"
                                expectedCategoryType == CategoryType.INCOME -> "收入分类"
                                else -> "支出分类"
                            },
                            color = colors.text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = filteredCategories.firstOrNull { it.id == effectiveCategoryId }?.name.orEmpty(),
                            color = colors.subText,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (editorActions != null) {
                            TextButton(
                                onClick = {
                                    categoryRestoreError = null
                                    showRestoreCategoriesDialog = true
                                },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    text = "恢复默认",
                                    color = colors.blue,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        if (onSaveCategory != null) {
                            TextButton(
                                onClick = { showCategoryEditor = true },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = null,
                                    tint = colors.blue,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = "新增分类",
                                    color = colors.blue,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                if (editorActions != null && filteredCategories.any { !it.isArchived }) {
                    Text("长按分类可删除", color = colors.subText, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    filteredCategories.forEach { category ->
                        val isSelected = category.id == effectiveCategoryId
                        val catColor = parseCategoryColor(category.colorHex, colors.blue)
                        val isDark = LocalIsDarkTheme.current
                        val readableSelectedColor = if (!isDark && catColor.luminance() > 0.45f) {
                            lerp(catColor, colors.text, 0.42f)
                        } else if (isDark && catColor.luminance() < 0.20f) {
                            lerp(catColor, colors.text, 0.35f)
                        } else {
                            catColor
                        }
                        val isProtectedFallback = category.id == DefaultCategories.EXPENSE_OTHER_ID ||
                            category.id == DefaultCategories.INCOME_OTHER_ID ||
                            category.id == DefaultCategories.REFUND_CATEGORY_ID
                        val shape = RoundedCornerShape(12.dp)
                        Row(
                            modifier = Modifier
                                .clip(shape)
                                .background(
                                    if (isSelected) catColor.copy(alpha = 0.16f) else colors.segBg
                                )
                                .then(
                                    if (isSelected) {
                                        Modifier.border(1.dp, readableSelectedColor, shape)
                                    } else {
                                        Modifier
                                    }
                                )
                                .combinedClickable(
                                    onLongClickLabel = "删除分类",
                                    onLongClick = if (editorActions != null && !category.isArchived && !isProtectedFallback) {
                                        { categoryToDelete = category }
                                    } else null,
                                    onClick = {
                                        selectedCategoryId = category.id
                                        if (initial?.type != TransactionType.TRANSFER) {
                                            txType = when {
                                                category.id == DefaultCategories.REFUND_CATEGORY_ID -> TransactionType.REFUND
                                                category.categoryType == CategoryType.INCOME -> TransactionType.INCOME
                                                else -> TransactionType.EXPENSE
                                            }
                                        }
                                    }
                                )
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = categoryIconForKey(category.iconKey),
                                contentDescription = null,
                                tint = if (isSelected) readableSelectedColor else colors.subText,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = category.name,
                                color = if (isSelected) readableSelectedColor else colors.text,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (showRefundFallbackHint) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "原账单分类不在收入分类中，已默认选择「退款」分类，保存后仍记为退款。",
                        color = colors.subText,
                        fontSize = 11.sp
                    )
                }
                if (awaitingCategorySave || categorySaveError != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = categorySaveError ?: "正在保存新分类…",
                        color = if (categorySaveError == null) colors.subText else colors.red,
                        fontSize = 11.sp
                    )
                }
                categoryRestoreError?.let { message ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = message,
                        color = colors.red,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Channel & Excluded from stats card
            AppleCard(contentPadding = PaddingValues(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "支付渠道",
                        color = colors.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (editorActions != null) {
                        TextButton(
                            onClick = { showRestoreChannelsDialog = true },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) {
                            Text(
                                text = "恢复默认",
                                color = colors.blue,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                if (editorActions != null && visibleChannelOptions.any { it.isDeletable }) {
                    Text("长按渠道可删除", color = colors.subText, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                } else {
                    Spacer(modifier = Modifier.height(10.dp))
                }
                if (visibleChannelOptions.isEmpty()) {
                    Text(
                        text = "暂无可用支付渠道。恢复默认或新增渠道后即可继续记账。",
                        color = colors.subText,
                        fontSize = 11.sp
                    )
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        visibleChannelOptions.forEach { option ->
                            val isSelected = channel == option.channel && customChannel == option.customName
                            val canDelete = editorActions != null && option.isDeletable
                            val shape = RoundedCornerShape(50)
                            Box(
                                modifier = Modifier.clip(shape)
                                    .background(if (isSelected) colors.blue.copy(alpha = 0.14f) else colors.segBg)
                                    .then(if (isSelected) Modifier.border(1.dp, colors.blue, shape) else Modifier)
                                    .combinedClickable(
                                        onLongClickLabel = if (canDelete) "删除支付渠道" else null,
                                        onLongClick = if (canDelete) {
                                            { channelToDelete = option }
                                        } else {
                                            null
                                        },
                                        onClick = {
                                            channel = option.channel
                                            customChannel = option.customName
                                        }
                                    )
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = option.customName ?: paymentChannelShortLabel(option.channel),
                                        color = if (isSelected) colors.blue else colors.text,
                                        fontSize = 12.sp
                                    )
                                    if (option.isHistorical) {
                                        Text(
                                            text = "历史",
                                            color = if (isSelected) {
                                                colors.blue.copy(alpha = 0.7f)
                                            } else {
                                                colors.subText
                                            },
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (editorActions != null) {
                    TextButton(
                        onClick = { channelEditorOpen = true },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("新增渠道", color = colors.blue)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = colors.separator, thickness = 0.5.dp)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "不计入统计",
                            color = colors.text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "开启后不计入日历总额与时刻统计图表",
                            color = colors.subText,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = excludedFromStats,
                        onCheckedChange = { excludedFromStats = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = colors.green,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = colors.segBg,
                            uncheckedBorderColor = Color.Transparent
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Primary Save button
            Button(
                onClick = ::handleSave,
                enabled = canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.blue,
                    contentColor = Color.White
                )
            ) {
                Text(
                    text = if (initial == null) "保存记账" else "保存修改",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Red Delete button when editing existing record
            if (initial != null && onDelete != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.red.copy(alpha = 0.12f),
                        contentColor = colors.red
                    ),
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "删除此笔账单",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }

    if (showCategoryEditor && onSaveCategory != null) {
        CategoryEditorSheet(
            initial = null,
            categoryType = categoryDirection,
            allCategories = displayedCategories,
            onSave = { draft ->
                if (!awaitingCategorySave) {
                    val newCategory = draft.copy(id = "custom_${UUID.randomUUID()}")
                    awaitingCategorySave = true
                    categorySaveError = null
                    showCategoryEditor = false
                    categorySaveScope.launch {
                        try {
                            onSaveCategory(newCategory)
                            pendingSavedCategory = newCategory
                            selectedCategoryId = newCategory.id
                            if (initial?.type != TransactionType.TRANSFER) {
                                txType = if (newCategory.categoryType == CategoryType.INCOME) {
                                    TransactionType.INCOME
                                } else {
                                    TransactionType.EXPENSE
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            categorySaveError = error.message ?: "分类保存失败"
                        } finally {
                            awaitingCategorySave = false
                        }
                    }
                }
            },
            onDismiss = { showCategoryEditor = false }
        )
    }

    if (showDatePicker) {
        val initialUtcMillis = remember(selectedDate) {
            selectedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialUtcMillis,
            yearRange = minOf(1900, selectedDate.year)..maxOf(2100, selectedDate.year)
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            selectedDate = Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                        }
                        showDatePicker = false
                    }
                ) {
                    Text("确定", color = colors.blue)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("取消", color = colors.subText)
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        LedgerTimePicker(
            initial = selectedTime,
            onConfirm = {
                selectedTime = it
                hasExplicitTime = true
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false }
        )
    }

    categoryToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { categoryToDelete = null },
            title = { Text("删除「${target.name}」") },
            text = { Text("删除后将从可选分类移除，历史账单仍保留原分类。") },
            confirmButton = {
                TextButton(onClick = {
                    categoryToDelete = null
                    categorySaveScope.launch {
                        editorActions?.archiveCategory?.invoke(target.id)
                    }
                }) { Text("删除", color = colors.red) }
            },
            dismissButton = { TextButton(onClick = { categoryToDelete = null }) { Text("取消") } }
        )
    }

    channelToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { channelToDelete = null },
            title = { Text("删除「${target.customName ?: paymentChannelShortLabel(target.channel)}」") },
            text = { Text("删除后将从可选渠道移除，历史账单仍保留原渠道。") },
            confirmButton = {
                TextButton(onClick = {
                    channelToDelete = null
                    editorActions?.preferences?.let { preferences ->
                        if (target.customName != null) {
                            pendingAddedChannels =
                                pendingAddedChannels.filterNot { it == target.customName }
                            preferences.deleteChannel(PaymentChannel.OTHER, target.customName)
                        } else {
                            preferences.deleteChannel(target.channel)
                        }
                    }
                }) { Text("删除", color = colors.red) }
            },
            dismissButton = { TextButton(onClick = { channelToDelete = null }) { Text("取消") } }
        )
    }

    if (showRestoreCategoriesDialog && editorActions != null) {
        AlertDialog(
            onDismissRequest = { showRestoreCategoriesDialog = false },
            title = { Text("恢复默认分类") },
            text = { Text("找回已删除的内置分类，保留自定义项和历史账单。") },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreCategoriesDialog = false
                    categoryRestoreError = null
                    categorySaveScope.launch {
                        try {
                            editorActions.restoreDefaultCategories(supportedCategoryIconKeys)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            categoryRestoreError = error.message ?: "恢复默认分类失败"
                        }
                    }
                }) { Text("恢复", color = colors.blue) }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreCategoriesDialog = false }) { Text("取消") }
            }
        )
    }

    if (showRestoreChannelsDialog && editorActions != null) {
        AlertDialog(
            onDismissRequest = { showRestoreChannelsDialog = false },
            title = { Text("恢复默认支付渠道") },
            text = { Text("找回已删除的内置支付渠道，保留自定义项和历史账单。") },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreChannelsDialog = false
                    editorActions.preferences.restoreDefaultChannels()
                }) { Text("恢复", color = colors.blue) }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreChannelsDialog = false }) { Text("取消") }
            }
        )
    }

    if (channelEditorOpen && editorActions != null) {
        var name by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { channelEditorOpen = false },
            title = { Text("新增支付渠道") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it; error = null }, label = { Text("渠道名称") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    error?.let { Text(it, color = colors.red) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        val added = editorActions.preferences.addCustomChannel(name)
                        pendingAddedChannels = (pendingAddedChannels + added).distinct()
                        customChannel = added
                        channel = PaymentChannel.OTHER
                        channelEditorOpen = false
                    } catch (invalid: IllegalArgumentException) {
                        error = invalid.message
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { channelEditorOpen = false }) { Text("取消") } }
        )
    }

    if (showDeleteConfirm && initial != null && onDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = colors.card,
            title = {
                Text(
                    text = "删除账单",
                    color = colors.text,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    text = "确认删除这笔本地记录吗？删除后无法恢复。",
                    color = colors.subText,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete(initial.id)
                    }
                ) {
                    Text("删除", color = colors.red, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消", color = colors.blue)
                }
            }
        )
    }
}

@Composable
private fun PickerPillButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val colors = LocalAppleColors.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.segBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.blue,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = label,
            style = TnumTextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = colors.text
            ),
            maxLines = 1
        )
    }
}

@Composable
private fun SheetTextInputRow(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit
) {
    val colors = LocalAppleColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = colors.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(52.dp)
        )
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    color = colors.subText.copy(alpha = 0.65f),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 14.sp,
                    color = colors.text
                ),
                cursorBrush = SolidColor(colors.blue),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 记账编辑器中的支付渠道选项。
 * 历史项仅用于编辑旧账单时保留已被删除的渠道，不能再次删除。
 */
private data class ChannelOption(
    val channel: PaymentChannel,
    val customName: String?,
    val isHistorical: Boolean = false
) {
    val isDeletable: Boolean get() = !isHistorical
}

private fun sanitizeAmountInput(raw: String): String {
    val sb = StringBuilder()
    var hasDot = false
    var decimals = 0
    for (ch in raw) {
        when {
            ch.isDigit() -> {
                if (!hasDot || decimals < 2) {
                    sb.append(ch)
                    if (hasDot) decimals++
                }
            }
            ch == '.' && !hasDot -> {
                hasDot = true
                if (sb.isEmpty()) sb.append('0')
                sb.append('.')
            }
        }
    }
    return sb.toString()
}

/**
 * 记账编辑器的分类方向：退款归入收入方向，但保存时仍保留 REFUND 类型。
 * 导入时仍可用支出分类匹配原消费，保留退款的原始分类信息。
 */
private fun editorCategoryTypeFor(txType: TransactionType): CategoryType =
    if (txType == TransactionType.REFUND) {
        CategoryType.INCOME
    } else {
        CategoryMatcher.categoryTypeFor(txType)
    }

private fun fallbackCategoryIdFor(
    categoryType: CategoryType,
    categories: List<Category>,
    preferredId: String? = null
): String {
    if (preferredId != null) {
        categories.firstOrNull { it.id == preferredId }?.let { return it.id }
    }
    val preferred = when (categoryType) {
        CategoryType.EXPENSE -> DefaultCategories.EXPENSE_OTHER_ID
        CategoryType.INCOME -> DefaultCategories.INCOME_OTHER_ID
    }
    return categories.firstOrNull { it.id == preferred }?.id
        ?: categories.firstOrNull()?.id
        ?: preferred
}
