package com.momentledger.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momentledger.app.domain.model.Category
import com.momentledger.app.domain.model.CategoryType
import com.momentledger.app.domain.model.DefaultCategories
import com.momentledger.app.ui.components.AppleCard
import com.momentledger.app.ui.components.AppleSegmentedControl
import com.momentledger.app.ui.components.CategoryIconBadge
import com.momentledger.app.ui.components.categoryIconForKey
import com.momentledger.app.ui.components.categoryIconLabelForKey
import com.momentledger.app.ui.components.supportedCategoryIconKeys
import com.momentledger.app.ui.theme.LocalAppleColors
import com.momentledger.app.ui.theme.parseCategoryColor

private val AppleCategoryPalette: List<String> = listOf(
    "#FF9500",
    "#007AFF",
    "#34C759",
    "#FF3B30",
    "#5856D6",
    "#FF2D55",
    "#AF52DE",
    "#32ADE6",
    "#FFCC00",
    "#FF6482",
    "#00C7BE",
    "#8E8E93"
)

@Composable
fun CategoryManagerScreen(
    expenseCategories: List<Category>,
    incomeCategories: List<Category>,
    onAddOrUpdateCategory: (Category) -> Unit,
    onDeleteCategory: (String) -> Unit,
    onMoveCategory: (CategoryType, Int, Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val colors = LocalAppleColors.current
    var selectedType by remember { mutableStateOf(CategoryType.EXPENSE) }
    var editorTarget by remember { mutableStateOf<Category?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var deleteConfirmTarget by remember { mutableStateOf<Category?>(null) }

    val activeList = remember(selectedType, expenseCategories, incomeCategories) {
        when (selectedType) {
            CategoryType.EXPENSE -> expenseCategories
            CategoryType.INCOME -> incomeCategories
        }.filterNot { it.isArchived }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .navigationBarsPadding()
    ) {
        // iOS navigation header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onBack)
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.ChevronLeft,
                    contentDescription = "返回设置",
                    tint = colors.blue,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = "设置",
                    color = colors.blue,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Text(
                text = "分类管理",
                color = colors.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )

            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(colors.blue)
                    .clickable {
                        editorTarget = null
                        editorOpen = true
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "新增分类",
                    tint = Color.White,
                    modifier = Modifier.size(19.dp)
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            AppleSegmentedControl(
                items = listOf(
                    CategoryType.EXPENSE to "支出分类",
                    CategoryType.INCOME to "收入分类"
                ),
                selected = selectedType,
                onSelect = { selectedType = it }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "点击分类可修改名称、图标或颜色；使用上下箭头调整展示排序。",
                color = colors.subText,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp)
        ) {
            itemsIndexed(
                items = activeList,
                key = { _, item -> item.id }
            ) { index, category ->
                val isFirst = index == 0
                val isLast = index == activeList.lastIndex
                val rowShape = when {
                    isFirst && isLast -> RoundedCornerShape(16.dp)
                    isFirst -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    isLast -> RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
                    else -> RoundedCornerShape(0.dp)
                }
                val isProtectedFallback = category.id == DefaultCategories.EXPENSE_OTHER_ID ||
                    category.id == DefaultCategories.INCOME_OTHER_ID

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(rowShape)
                        .clickable {
                            editorTarget = category
                            editorOpen = true
                        },
                    shape = rowShape,
                    color = colors.card
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
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
                                        text = category.name,
                                        color = colors.text,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    CategoryRoleBadge(
                                        isProtectedFallback = isProtectedFallback,
                                        isSystem = category.isSystem
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { onMoveCategory(selectedType, index, index - 1) },
                                    enabled = index > 0,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.KeyboardArrowUp,
                                        contentDescription = "上移",
                                        tint = if (index > 0) colors.blue else colors.subText.copy(alpha = 0.35f)
                                    )
                                }
                                IconButton(
                                    onClick = { onMoveCategory(selectedType, index, index + 1) },
                                    enabled = index < activeList.lastIndex,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = "下移",
                                        tint = if (index < activeList.lastIndex) {
                                            colors.blue
                                        } else {
                                            colors.subText.copy(alpha = 0.35f)
                                        }
                                    )
                                }
                                IconButton(
                                    onClick = { deleteConfirmTarget = category },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteOutline,
                                        contentDescription = "删除分类",
                                        tint = colors.red,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                        }
                        if (!isLast) {
                            HorizontalDivider(
                                color = colors.separator,
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(start = 62.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (editorOpen) {
        CategoryEditorSheet(
            initial = editorTarget,
            categoryType = editorTarget?.categoryType ?: selectedType,
            allCategories = expenseCategories + incomeCategories,
            onSave = { updated ->
                onAddOrUpdateCategory(updated)
                editorOpen = false
                editorTarget = null
            },
            onDismiss = {
                editorOpen = false
                editorTarget = null
            }
        )
    }

    deleteConfirmTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteConfirmTarget = null },
            containerColor = colors.card,
            title = {
                Text(
                    text = "删除「${target.name}」",
                    color = colors.text,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    text = "删除后将从可选分类移除，历史账单仍保留原分类。",
                    color = colors.subText,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteCategory(target.id)
                        deleteConfirmTarget = null
                    }
                ) {
                    Text("删除", color = colors.red, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmTarget = null }) {
                    Text("取消", color = colors.blue)
                }
            }
        )
    }
}

@Composable
private fun CategoryRoleBadge(
    isProtectedFallback: Boolean,
    isSystem: Boolean
) {
    val colors = LocalAppleColors.current
    val (label, bgColor, textColor) = when {
        isProtectedFallback -> Triple("默认", colors.segBg, colors.subText)
        isSystem -> Triple("系统", colors.segBg, colors.subText)
        else -> Triple("自定义", colors.blue.copy(alpha = 0.14f), colors.blue)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CategoryEditorSheet(
    initial: Category?,
    categoryType: CategoryType,
    allCategories: List<Category>,
    onSave: (Category) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalAppleColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val protectedInitialName = initial?.takeIf {
        it.id == DefaultCategories.EXPENSE_OTHER_ID || it.id == DefaultCategories.INCOME_OTHER_ID
    }?.name
    val isProtectedFallback = protectedInitialName != null

    // 图标在收入/支出分类之间全局唯一；编辑自身时忽略自己占用的图标。
    val occupiedByOtherCategories = remember(allCategories, initial?.id) {
        allCategories.asSequence()
            .filter { it.id != initial?.id && !it.isArchived }
            .map { it.iconKey.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }
    val availableIconKeys = remember(occupiedByOtherCategories) {
        supportedCategoryIconKeys.filterNot { it in occupiedByOtherCategories }
    }
    val initialIconKey = initial?.iconKey?.trim().orEmpty()
    val initialIconNeedsReselection = initial != null &&
        initialIconKey.isNotEmpty() &&
        initialIconKey !in availableIconKeys

    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var iconKey by remember(initial?.id) { mutableStateOf(initialIconKey) }
    var colorHex by remember(initial?.id) {
        mutableStateOf(initial?.colorHex ?: AppleCategoryPalette.first())
    }
    var nameError by remember(initial?.id) { mutableStateOf<String?>(null) }

    // 分类列表异步到达或原图标冲突时，自动落到第一个可用图标。
    LaunchedEffect(availableIconKeys) {
        if (iconKey !in availableIconKeys) {
            iconKey = availableIconKeys.firstOrNull().orEmpty()
        }
    }

    val selectedColor = remember(colorHex, colors.blue) {
        parseCategoryColor(colorHex, colors.blue)
    }
    val canSubmit = availableIconKeys.isNotEmpty() && iconKey in availableIconKeys

    fun submit() {
        if (!canSubmit) return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            nameError = "请输入分类名称"
            return
        }
        val finalName = protectedInitialName ?: trimmed
        val result = if (initial != null) {
            initial.copy(
                name = finalName,
                iconKey = iconKey,
                colorHex = colorHex
            )
        } else {
            Category(
                id = "",
                name = finalName,
                categoryType = categoryType,
                iconKey = iconKey,
                colorHex = colorHex,
                sortOrder = 0,
                isSystem = false
            )
        }
        onSave(result)
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
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onDismiss) {
                    Text("取消", color = colors.blue, fontSize = 15.sp)
                }
                Text(
                    text = if (initial == null) {
                        if (categoryType == CategoryType.INCOME) "新增收入分类" else "新增支出分类"
                    } else {
                        "编辑分类"
                    },
                    color = colors.text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = ::submit, enabled = canSubmit) {
                    Text(
                        text = "保存",
                        color = if (canSubmit) colors.blue else colors.subText.copy(alpha = 0.45f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Live preview & Name input card
            AppleCard(contentPadding = PaddingValues(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CategoryIconBadge(
                        iconKey = iconKey,
                        colorHex = colorHex,
                        contentDescription = name,
                        size = 44.dp
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isProtectedFallback) "系统兜底分类名称已锁定" else "分类名称",
                            color = colors.subText,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.bg)
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            if (name.isEmpty()) {
                                Text(
                                    text = "例如：咖啡茶饮、宠物、健身",
                                    color = colors.subText.copy(alpha = 0.6f),
                                    fontSize = 15.sp
                                )
                            }
                            BasicTextField(
                                value = name,
                                onValueChange = { raw ->
                                    if (!isProtectedFallback) {
                                        name = raw.take(12)
                                        if (nameError != null) nameError = null
                                    }
                                },
                                enabled = !isProtectedFallback,
                                singleLine = true,
                                textStyle = TextStyle(
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isProtectedFallback) colors.subText else colors.text
                                ),
                                cursorBrush = SolidColor(colors.blue),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                if (nameError != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = nameError.orEmpty(),
                        color = colors.red,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // iOS Color Palette Card
            AppleCard(contentPadding = PaddingValues(16.dp)) {
                Text(
                    text = "强调色",
                    color = colors.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(12.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AppleCategoryPalette.forEach { hex ->
                        val swatchColor = parseCategoryColor(hex, colors.blue)
                        val selected = hex.equals(colorHex, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(swatchColor)
                                .then(
                                    if (selected) {
                                        Modifier.border(2.5.dp, colors.text.copy(alpha = 0.35f), CircleShape)
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable { colorHex = hex },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "已选颜色",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Icon Grid Selector Card
            AppleCard(contentPadding = PaddingValues(16.dp)) {
                Text(
                    text = "选择图标",
                    color = colors.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (initialIconNeedsReselection) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "原图标已被其他分类占用或不再可用，请重新选择一个图标。",
                        color = colors.red,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                if (availableIconKeys.isEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "暂无可选图标：所有图标均已被其他分类（含收入与支出）使用，请先修改或删除其他分类后再保存。",
                        color = colors.red,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    availableIconKeys.forEach { key ->
                        val selected = key == iconKey
                        val iconLabel = categoryIconLabelForKey(key)
                        val tileShape = RoundedCornerShape(12.dp)
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(tileShape)
                                .background(
                                    if (selected) selectedColor.copy(alpha = 0.16f) else colors.bg
                                )
                                .then(
                                    if (selected) {
                                        Modifier.border(1.5.dp, selectedColor, tileShape)
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable { iconKey = key },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = categoryIconForKey(key),
                                contentDescription = if (selected) {
                                    "$iconLabel 图标，已选中"
                                } else {
                                    "$iconLabel 图标"
                                },
                                tint = if (selected) selectedColor else colors.subText,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = ::submit,
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
                    text = if (initial == null) "创建分类" else "保存修改",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
