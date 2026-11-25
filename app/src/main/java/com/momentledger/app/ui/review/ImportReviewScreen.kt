package com.momentledger.app.ui.review

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.ensureActive
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.momentledger.app.domain.engine.CategoryMatcher
import com.momentledger.app.domain.model.Category
import com.momentledger.app.domain.model.CategoryType
import com.momentledger.app.domain.model.HOUR_OF_DAY_UNKNOWN
import com.momentledger.app.domain.model.ImportCommitSummary
import com.momentledger.app.domain.model.ImportDuplicateStatus
import com.momentledger.app.domain.model.ImportReviewItem
import com.momentledger.app.quickentry.QuickEntryActions
import com.momentledger.app.ui.components.AppleCard
import com.momentledger.app.ui.components.CategoryIconBadge
import com.momentledger.app.ui.components.ChannelBadge
import com.momentledger.app.ui.components.TransactionEditorSheet
import com.momentledger.app.ui.components.categoryIconForKey
import com.momentledger.app.ui.components.formatTransactionAmount
import com.momentledger.app.ui.components.transactionTypeLabel
import com.momentledger.app.ui.theme.LocalAppleColors
import com.momentledger.app.ui.theme.TnumTextStyle
import com.momentledger.app.ui.theme.parseCategoryColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ReviewDateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private val ReviewDateOnlyFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")

@Composable
fun ImportReviewScreen(
    viewModel: ImportReviewViewModel,
    onCancel: () -> Unit,
    onCompleted: (ImportCommitSummary) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isScreenCapture = uiState.batch?.sourceTitle == QuickEntryActions.SCREEN_REVIEW_TITLE
    val colors = LocalAppleColors.current
    val context = LocalContext.current
    val zoneId = remember { ZoneId.systemDefault() }

    var showRawOcrText by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<ImportReviewItem?>(null) }
    var inlineCategoryItem by remember { mutableStateOf<ImportReviewItem?>(null) }
    var batchCategorySheetOpen by remember { mutableStateOf(false) }
    var manualDraftSheetOpen by remember { mutableStateOf(false) }

    val allCategories = remember(uiState.expenseCategories, uiState.incomeCategories) {
        uiState.expenseCategories + uiState.incomeCategories
    }
    val screenDraft = uiState.batch?.items?.singleOrNull()?.takeIf {
        isScreenCapture && it.selected && !it.needsVerification && it.duplicateStatus == ImportDuplicateStatus.NEW
    }
    var showQuickConfirmation by rememberSaveable(screenDraft?.draftId) { mutableStateOf(true) }

    LaunchedEffect(uiState.committedSummary) {
        val summary = uiState.committedSummary ?: return@LaunchedEffect
        viewModel.consumeCommittedSummary()
        onCompleted(summary)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .navigationBarsPadding()
    ) {
        // Top header bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = onCancel,
                enabled = !uiState.isCommitting
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "取消",
                    tint = if (uiState.isCommitting) colors.subText else colors.text
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = uiState.batch?.sourceTitle?.ifBlank { "导入确认与分类" }
                        ?: uiState.loadingTitle.ifBlank { "识别与解析" },
                    color = colors.text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val batch = uiState.batch
                if (batch != null) {
                    val subtitle = buildString {
                        append("共 ${batch.items.size} 笔 · 已选 ${uiState.selectedCount} 笔")
                        if (batch.skippedCount > 0) {
                            append(" · 已过滤无效 ${batch.skippedCount} 笔")
                        }
                    }
                    Text(
                        text = subtitle,
                        color = colors.subText,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
            }
            Button(
                onClick = { viewModel.confirmImport() },
                enabled = !uiState.isLoading && !uiState.isCommitting && uiState.selectedCount > 0,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.blue,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    text = when {
                        uiState.isCommitting -> "写入中..."
                        isScreenCapture -> "确认入账"
                        else -> "导入 (${uiState.selectedCount})"
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        HorizontalDivider(color = colors.separator, thickness = 0.5.dp)

        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = colors.blue)
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = uiState.loadingTitle.ifBlank { "正在本地离线识别账单..." },
                            color = colors.text,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "全程纯本地运行，不上传任何图片或数据",
                            color = colors.subText,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            uiState.pdfPasswordPromptMessage != null && uiState.batch == null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "等待输入 PDF 账单打开密码...",
                        color = colors.subText,
                        fontSize = 14.sp
                    )
                }
            }

            uiState.errorMessage != null && uiState.batch == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AppleCard(contentPadding = PaddingValues(20.dp)) {
                        Text(
                            text = "未能完成识别",
                            color = colors.text,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = uiState.errorMessage.orEmpty(),
                            color = colors.subText,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TextButton(onClick = onCancel) {
                                Text("返回", color = colors.subText)
                            }
                            TextButton(onClick = { manualDraftSheetOpen = true }) {
                                Text("手动补录一笔", color = colors.blue, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            else -> {
                val batch = uiState.batch
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isScreenCapture) {
                        item(key = "screenCaptureHint") {
                            AppleCard(contentPadding = PaddingValues(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DocumentScanner,
                                        contentDescription = null,
                                        tint = colors.blue,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Text(
                                        "截图已释放。核对金额、方向和时间后，点按确认入账。",
                                        color = colors.subText,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                    if (uiState.errorMessage != null) {
                        item(key = "errorBanner") {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = colors.red.copy(alpha = 0.14f)
                            ) {
                                Text(
                                    text = uiState.errorMessage.orEmpty(),
                                    color = colors.red,
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }

                    // Screenshot thumbnails + expandable raw OCR preview
                    if (batch != null && (batch.imageUris.isNotEmpty() || batch.rawOcrText.isNotBlank())) {
                        item(key = "ocrPreview") {
                            AppleCard(contentPadding = PaddingValues(12.dp)) {
                                if (batch.imageUris.isNotEmpty()) {
                                    ScreenshotThumbnailsRow(
                                        context = context,
                                        uriStrings = batch.imageUris
                                    )
                                    if (batch.rawOcrText.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        HorizontalDivider(color = colors.separator, thickness = 0.5.dp)
                                    }
                                }
                                if (batch.rawOcrText.isNotBlank()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { showRawOcrText = !showRawOcrText }
                                            .padding(top = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "查看离线识别原文（便于核对金额与时间）",
                                            color = colors.blue,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Icon(
                                            imageVector = if (showRawOcrText) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                            contentDescription = null,
                                            tint = colors.blue,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    AnimatedVisibility(visible = showRawOcrText) {
                                        Text(
                                            text = batch.rawOcrText,
                                            color = colors.subText,
                                            fontSize = 11.sp,
                                            lineHeight = 16.sp,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 8.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(colors.segBg)
                                                .padding(10.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Filter chips + batch actions
                    item(key = "filterAndBatchBar") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ReviewFilterChip(
                                    label = "全部 (${batch?.items?.size ?: 0})",
                                    selected = uiState.filter == ImportReviewFilter.ALL,
                                    onClick = { viewModel.setFilter(ImportReviewFilter.ALL) }
                                )
                                ReviewFilterChip(
                                    label = "新增 (${uiState.newCount})",
                                    selected = uiState.filter == ImportReviewFilter.NEW_ONLY,
                                    onClick = { viewModel.setFilter(ImportReviewFilter.NEW_ONLY) }
                                )
                                ReviewFilterChip(
                                    label = "疑似重复 (${uiState.duplicateCount})",
                                    selected = uiState.filter == ImportReviewFilter.DUPLICATES,
                                    onClick = { viewModel.setFilter(ImportReviewFilter.DUPLICATES) }
                                )
                                ReviewFilterChip(
                                    label = "其他分类 (${uiState.uncategorizedCount})",
                                    selected = uiState.filter == ImportReviewFilter.UNCATEGORIZED,
                                    onClick = { viewModel.setFilter(ImportReviewFilter.UNCATEGORIZED) }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ActionPillButton(
                                    label = "全选新增",
                                    icon = Icons.Rounded.Check,
                                    accent = colors.blue,
                                    onClick = viewModel::selectAllNew
                                )
                                ActionPillButton(
                                    label = "批量改分类",
                                    icon = Icons.AutoMirrored.Rounded.Label,
                                    accent = colors.orange,
                                    enabled = uiState.selectedCount > 0,
                                    onClick = { batchCategorySheetOpen = true }
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                ActionPillButton(
                                    label = "补录一笔",
                                    icon = Icons.Rounded.Add,
                                    accent = colors.green,
                                    onClick = { manualDraftSheetOpen = true }
                                )
                            }
                        }
                    }

                    if (uiState.visibleItems.isEmpty()) {
                        item(key = "emptyState") {
                            AppleCard(contentPadding = PaddingValues(20.dp)) {
                                Text(
                                    text = if ((batch?.items?.size ?: 0) == 0) {
                                        "未识别到有效金额记录"
                                    } else {
                                        "当前筛选条件下暂无条目"
                                    },
                                    color = colors.text,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "可以切换筛选标签，或点击「补录一笔」对照原文快速添加。",
                                    color = colors.subText,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    } else {
                        items(
                            items = uiState.visibleItems,
                            key = { it.draftId }
                        ) { item ->
                            ReviewItemCard(
                                item = item,
                                category = uiState.categoriesById[item.record.categoryId],
                                zoneId = zoneId,
                                onToggleSelect = { viewModel.toggleSelected(item.draftId) },
                                onTapCategory = { inlineCategoryItem = item },
                                onTapEdit = { editingItem = item }
                            )
                        }
                    }
                }
            }
        }
    }

    if (screenDraft != null && showQuickConfirmation) {
        val record = screenDraft.record
        val (amount, amountColor) = formatTransactionAmount(record, colors)
        val time = Instant.ofEpochMilli(record.timestampMillis).atZone(zoneId).format(ReviewDateTimeFormatter)
        val category = allCategories.firstOrNull { it.id == record.categoryId }?.name
        AlertDialog(
            onDismissRequest = onCancel,
            properties = DialogProperties(
                dismissOnBackPress = !uiState.isCommitting,
                dismissOnClickOutside = !uiState.isCommitting
            ),
            icon = {
                Icon(
                    imageVector = Icons.Rounded.DocumentScanner,
                    contentDescription = null,
                    tint = colors.blue,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = { Text("确认识屏账单") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${transactionTypeLabel(record.type)}  $amount",
                        style = TnumTextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = amountColor)
                    )
                    Text(record.merchant.ifBlank { "商户待确认" }, color = colors.text)
                    Text(listOfNotNull(time, category).joinToString(" · "), color = colors.subText)
                    uiState.errorMessage?.let { Text(it, color = colors.red) }
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmImport() }, enabled = !uiState.isCommitting) {
                    Text(if (uiState.isCommitting) "写入中…" else "确认入账")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = onCancel, enabled = !uiState.isCommitting) { Text("取消") }
                    TextButton(onClick = { showQuickConfirmation = false }, enabled = !uiState.isCommitting) {
                        Text("核对详情")
                    }
                }
            }
        )
    }

    // Inline single-item category picker
    inlineCategoryItem?.let { target ->
        val expectedType = CategoryMatcher.categoryTypeFor(target.record.type)
        val options = (if (expectedType == CategoryType.INCOME) uiState.incomeCategories else uiState.expenseCategories)
            .filterNot { it.isArchived }
        CategoryPickerSheet(
            title = "选择分类 · ${target.record.merchant.ifBlank { "未命名" }}",
            categories = options,
            selectedId = target.record.categoryId,
            onSelect = { categoryId ->
                viewModel.updateItemCategory(target.draftId, categoryId)
                inlineCategoryItem = null
            },
            onDismiss = { inlineCategoryItem = null }
        )
    }

    // Batch category picker
    if (batchCategorySheetOpen) {
        BatchCategoryPickerSheet(
            expenseCategories = uiState.expenseCategories,
            incomeCategories = uiState.incomeCategories,
            selectedCount = uiState.selectedCount,
            onSelect = { categoryId ->
                viewModel.applyBatchCategory(categoryId)
                batchCategorySheetOpen = false
            },
            onDismiss = { batchCategorySheetOpen = false }
        )
    }

    // Full draft item editor
    editingItem?.let { target ->
        TransactionEditorSheet(
            initial = target.record,
            categories = allCategories,
            onSave = { updated ->
                viewModel.updateItemRecord(target.draftId, updated)
                editingItem = null
            },
            onDelete = {
                viewModel.removeItem(target.draftId)
                editingItem = null
            },
            onDismiss = { editingItem = null }
        )
    }

    // Manual supplemental draft
    if (manualDraftSheetOpen) {
        TransactionEditorSheet(
            initial = null,
            categories = allCategories,
            autoFocusAmount = true,
            onSave = { created ->
                viewModel.addManualDraft(created)
                manualDraftSheetOpen = false
            },
            onDelete = null,
            onDismiss = { manualDraftSheetOpen = false }
        )
    }
}

@Composable
private fun ScreenshotThumbnailsRow(
    context: Context,
    uriStrings: List<String>
) {
    val colors = LocalAppleColors.current
    val appContext = remember(context) { context.applicationContext }
    var thumbnails by remember(uriStrings) { mutableStateOf<List<androidx.compose.ui.graphics.ImageBitmap>>(emptyList()) }

    LaunchedEffect(uriStrings) {
        val loaded = withContext(Dispatchers.IO) {
            uriStrings.take(8).mapNotNull { raw ->
                coroutineContext.ensureActive()
                runCatching { decodeThumbnailBitmap(appContext, Uri.parse(raw), maxSide = 220)?.asImageBitmap() }.getOrNull()
            }
        }
        thumbnails = loaded
    }

    if (thumbnails.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            thumbnails.forEach { imageBitmap ->
                Image(
                    bitmap = imageBitmap,
                    contentDescription = "账单截图",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 58.dp, height = 84.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(0.5.dp, colors.separator, RoundedCornerShape(10.dp))
                )
            }
        }
    }
}

private fun decodeThumbnailBitmap(context: Context, uri: Uri, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val w = bounds.outWidth
    val h = bounds.outHeight
    if (w <= 0 || h <= 0) return null
    var sample = 1
    while (maxOf(w / (sample * 2), h / (sample * 2)) >= maxSide) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, opts)
    } ?: return null
    val rotation = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
    }.getOrDefault(0f)
    if (rotation == 0f) return decoded
    val matrix = Matrix().apply { postRotate(rotation) }
    return try {
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
            if (it !== decoded && !decoded.isRecycled) decoded.recycle()
        }
    } catch (_: OutOfMemoryError) {
        decoded
    }
}

@Composable
private fun ReviewItemCard(
    item: ImportReviewItem,
    category: Category?,
    zoneId: ZoneId,
    onToggleSelect: () -> Unit,
    onTapCategory: () -> Unit,
    onTapEdit: () -> Unit
) {
    val colors = LocalAppleColors.current
    val record = item.record
    val (amountText, amountColor) = remember(record.type, record.amountCents, record.excludedFromStats, colors) {
        formatTransactionAmount(record = record, colors = colors, includeRefundWord = true)
    }
    val formattedTime = remember(record.timestampMillis, record.hourOfDay, zoneId) {
        val zoned = Instant.ofEpochMilli(record.timestampMillis).atZone(zoneId)
        if (record.hourOfDay == HOUR_OF_DAY_UNKNOWN) {
            "${zoned.format(ReviewDateOnlyFormatter)} 全天"
        } else {
            zoned.format(ReviewDateTimeFormatter)
        }
    }

    AppleCard(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 11.dp),
        onClick = onTapEdit,
        modifier = Modifier.alpha(if (item.selected) 1f else 0.62f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onToggleSelect,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = if (item.selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = if (item.selected) "取消勾选" else "勾选",
                    tint = if (item.selected) colors.blue else colors.subText,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onTapCategory)
            ) {
                CategoryIconBadge(category = category, size = 38.dp)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = record.merchant.ifBlank { category?.name ?: "待确认消费" },
                        color = colors.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    when (item.duplicateStatus) {
                        ImportDuplicateStatus.DUPLICATE_MERGE -> StatusPill("可合并", colors.orange)
                        ImportDuplicateStatus.DUPLICATE_SKIP -> StatusPill(
                            if (item.duplicateHint.contains("同批")) "同批重复" else "疑似重复",
                            colors.subText
                        )
                        ImportDuplicateStatus.NEW -> if (item.needsVerification) {
                            StatusPill("建议核对", colors.orange)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = category?.name ?: "未分类",
                        color = colors.blue,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable(onClick = onTapCategory)
                    )
                    Text(text = "·", color = colors.subText, fontSize = 11.sp)
                    Text(
                        text = formattedTime,
                        style = TnumTextStyle(fontSize = 11.sp, color = colors.subText),
                        maxLines = 1
                    )
                    Text(text = "·", color = colors.subText, fontSize = 11.sp)
                    ChannelBadge(
                        channel = record.channel,
                        customChannel = record.customChannel,
                        fundingMethod = record.fundingMethod,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                if (record.note.isNotBlank() || item.duplicateHint.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = item.duplicateHint.ifBlank { record.note },
                        color = colors.subText,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = amountText,
                style = TnumTextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = amountColor
                ),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun ReviewFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalAppleColors.current
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        color = if (selected) colors.blue else colors.card
    ) {
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else colors.text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun ActionPillButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = accent.copy(alpha = 0.13f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                color = accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CategoryPickerSheet(
    title: String,
    categories: List<Category>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalAppleColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.card,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = title,
                color = colors.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(14.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEach { category ->
                    val isSelected = category.id == selectedId
                    val catColor = parseCategoryColor(category.colorHex, colors.blue)
                    val shape = RoundedCornerShape(12.dp)
                    Row(
                        modifier = Modifier
                            .clip(shape)
                            .background(if (isSelected) catColor.copy(alpha = 0.18f) else colors.segBg)
                            .then(if (isSelected) Modifier.border(1.dp, catColor, shape) else Modifier)
                            .clickable { onSelect(category.id) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = categoryIconForKey(category.iconKey),
                            contentDescription = null,
                            tint = catColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = category.name,
                            color = colors.text,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun BatchCategoryPickerSheet(
    expenseCategories: List<Category>,
    incomeCategories: List<Category>,
    selectedCount: Int,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalAppleColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showingIncome by remember { mutableStateOf(false) }
    val activeCategories = (if (showingIncome) incomeCategories else expenseCategories).filterNot { it.isArchived }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.card,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "批量修改已选 $selectedCount 笔账单分类",
                color = colors.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "仅作用于对应收支类型的已勾选条目，并自动记住商户分类习惯",
                color = colors.subText,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReviewFilterChip(
                    label = "支出分类",
                    selected = !showingIncome,
                    onClick = { showingIncome = false }
                )
                ReviewFilterChip(
                    label = "收入分类",
                    selected = showingIncome,
                    onClick = { showingIncome = true }
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                activeCategories.forEach { category ->
                    val catColor = parseCategoryColor(category.colorHex, colors.blue)
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.segBg)
                            .clickable { onSelect(category.id) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = categoryIconForKey(category.iconKey),
                            contentDescription = null,
                            tint = catColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = category.name,
                            color = colors.text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
