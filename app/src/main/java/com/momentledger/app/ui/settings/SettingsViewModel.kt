package com.momentledger.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.momentledger.app.data.prefs.LedgerPreferences
import com.momentledger.app.data.prefs.ThemePreference
import com.momentledger.app.data.repository.LedgerRepository
import com.momentledger.app.domain.model.Category
import com.momentledger.app.domain.model.CategoryType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.Locale

data class SettingsUiState(
    val quickNotificationEnabled: Boolean = true,
    val notificationPermissionGranted: Boolean = true,
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val expenseCategories: List<Category> = emptyList(),
    val incomeCategories: List<Category> = emptyList(),
    val statusMessage: String? = null,
    val isBusy: Boolean = false
)

class SettingsViewModel(
    private val repository: LedgerRepository,
    private val preferences: LedgerPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val notificationPermissionFlow = MutableStateFlow(true)
    private val statusMessageFlow = MutableStateFlow<String?>(null)
    private val busyFlow = MutableStateFlow(false)
    private val categoryMutationMutex = Mutex()

    private data class PrefsAndPermissionSnapshot(
        val quickNotificationEnabled: Boolean,
        val notificationPermissionGranted: Boolean,
        val themePreference: ThemePreference
    )

    private val prefsFlow = combine(
        preferences.quickNotificationEnabled,
        notificationPermissionFlow,
        preferences.themePreference
    ) { quickNotif, permGranted, theme ->
        PrefsAndPermissionSnapshot(
            quickNotificationEnabled = quickNotif,
            notificationPermissionGranted = permGranted,
            themePreference = theme
        )
    }

    private val categoriesFlow = repository.observeCategories()

    val uiState: StateFlow<SettingsUiState> = combine(
        prefsFlow,
        categoriesFlow,
        statusMessageFlow,
        busyFlow
    ) { prefs, categories, statusMessage, isBusy ->
        val sorted = categories.sortedWith(compareBy<Category> { it.sortOrder }.thenBy { it.id })
        SettingsUiState(
            quickNotificationEnabled = prefs.quickNotificationEnabled,
            notificationPermissionGranted = prefs.notificationPermissionGranted,
            themePreference = prefs.themePreference,
            expenseCategories = sorted.filter { it.categoryType == CategoryType.EXPENSE },
            incomeCategories = sorted.filter { it.categoryType == CategoryType.INCOME },
            statusMessage = statusMessage,
            isBusy = isBusy
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SettingsUiState(
            quickNotificationEnabled = preferences.quickNotificationEnabled.value,
            notificationPermissionGranted = notificationPermissionFlow.value,
            themePreference = preferences.themePreference.value
        )
    )

    init {
        viewModelScope.launch {
            withContext(ioDispatcher) {
                repository.ensureSeeded()
            }
        }
    }

    fun refreshNotificationPermission(granted: Boolean) {
        notificationPermissionFlow.value = granted
    }

    fun setQuickNotificationEnabled(enabled: Boolean) {
        preferences.setQuickNotificationEnabled(enabled)
    }

    fun setThemePreference(preference: ThemePreference) {
        preferences.setThemePreference(preference)
    }

    fun importBill(fileName: String, inputStream: InputStream) {
        viewModelScope.launch {
            busyFlow.value = true
            try {
                val summary = withContext(ioDispatcher) {
                    inputStream.use { stream ->
                        repository.importBillStream(fileName = fileName, stream = stream)
                    }
                }
                statusMessageFlow.value = if (
                    summary.detectedFormat == "UNKNOWN" &&
                    summary.insertedCount == 0 &&
                    summary.mergedCount == 0 &&
                    summary.skippedCount == 0
                ) {
                    "未识别到有效账单，请选择微信(.xlsx/.csv)、支付宝(.csv)或通用表格账单"
                } else {
                    "新增 ${summary.insertedCount} 笔 · 合并去重 ${summary.mergedCount} 笔 · 跳过 ${summary.skippedCount} 笔"
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                statusMessageFlow.value = "导入失败：${e.message ?: "请检查文件格式"}"
            } finally {
                busyFlow.value = false
            }
        }
    }

    suspend fun exportBackupJson(): String {
        busyFlow.value = true
        return try {
            withContext(ioDispatcher) {
                repository.exportBackupJson()
            }
        } finally {
            busyFlow.value = false
        }
    }

    fun restoreBackupJson(json: String) {
        viewModelScope.launch {
            busyFlow.value = true
            try {
                val (restoredCount, channels) = withContext(ioDispatcher) {
                    val count = repository.restoreBackupJson(json)
                    count to repository.recordedCustomChannels()
                }
                channels.forEach { channel ->
                    try {
                        preferences.addCustomChannel(channel)
                    } catch (_: IllegalArgumentException) {
                        // The name remains on its historical record.
                    }
                }
                statusMessageFlow.value = "已恢复 $restoredCount 笔本地账单"
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                statusMessageFlow.value = "恢复失败：${e.message ?: "备份文件格式无效"}"
            } finally {
                busyFlow.value = false
            }
        }
    }

    suspend fun exportCsv(): String {
        busyFlow.value = true
        return try {
            withContext(ioDispatcher) {
                repository.exportCsv()
            }
        } finally {
            busyFlow.value = false
        }
    }

    fun addOrUpdateCategory(category: Category) {
        val cleanName = category.name.trim()
        if (cleanName.isEmpty()) {
            statusMessageFlow.value = "分类名称不能为空"
            return
        }
        viewModelScope.launch {
            categoryMutationMutex.withLock {
                val existingSameType = withContext(ioDispatcher) {
                    repository.getCategoriesByType(category.categoryType)
                }
                val duplicate = existingSameType.any {
                    it.id != category.id && it.name.trim().equals(cleanName, ignoreCase = true)
                }
                if (duplicate) {
                    statusMessageFlow.value = "已存在同名分类「$cleanName」"
                    return@withLock
                }
                val normalizedColor = category.colorHex.trim().let { hex ->
                    if (hexColorRegex.matches(hex)) {
                        hex.uppercase(Locale.ROOT)
                    } else {
                        "#007AFF"
                    }
                }
                val normalizedIcon = category.iconKey.trim().ifEmpty { "label" }
                try {
                    withContext(ioDispatcher) {
                        repository.saveCustomCategory(
                            category.copy(
                                name = cleanName,
                                iconKey = normalizedIcon,
                                colorHex = normalizedColor
                            )
                        )
                    }
                } catch (error: IllegalArgumentException) {
                    statusMessageFlow.value = error.message ?: "分类保存失败"
                }
            }
        }
    }

    fun deleteCategory(categoryId: String) {
        viewModelScope.launch {
            categoryMutationMutex.withLock {
                val deleted = withContext(ioDispatcher) {
                    repository.archiveCategory(categoryId)
                }
                statusMessageFlow.value = if (deleted) {
                    "已删除分类，历史账单保留原分类"
                } else {
                    "分类已删除或不存在"
                }
            }
        }
    }

    fun moveCategory(type: CategoryType, fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        viewModelScope.launch {
            categoryMutationMutex.withLock {
                val currentList = withContext(ioDispatcher) {
                    repository.getCategoriesByType(type)
                        .sortedWith(compareBy<Category> { it.sortOrder }.thenBy { it.id })
                }
                if (fromIndex !in currentList.indices || toIndex !in currentList.indices) {
                    return@withLock
                }
                val reorderedIds = currentList.map { it.id }.toMutableList().apply {
                    val moved = removeAt(fromIndex)
                    add(toIndex, moved)
                }
                withContext(ioDispatcher) {
                    repository.updateCategorySortOrders(reorderedIds)
                }
            }
        }
    }

    fun postStatusMessage(message: String) {
        statusMessageFlow.value = message
    }

    fun clearStatusMessage() {
        statusMessageFlow.value = null
    }

    companion object {
        private val hexColorRegex = Regex("""^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$""")

        fun factory(
            repository: LedgerRepository,
            preferences: LedgerPreferences
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
                    return SettingsViewModel(
                        repository = repository,
                        preferences = preferences
                    ) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }
}
