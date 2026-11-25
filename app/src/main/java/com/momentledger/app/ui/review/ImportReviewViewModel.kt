package com.momentledger.app.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.momentledger.app.data.repository.LedgerRepository
import com.momentledger.app.domain.engine.CategoryMatcher
import com.momentledger.app.domain.model.Category
import com.momentledger.app.domain.model.CategoryType
import com.momentledger.app.domain.model.DefaultCategories
import com.momentledger.app.domain.model.ImportCommitSummary
import com.momentledger.app.domain.model.ImportDuplicateStatus
import com.momentledger.app.domain.model.ImportReviewBatch
import com.momentledger.app.domain.model.ImportReviewItem
import com.momentledger.app.domain.model.TransactionRecord
import com.momentledger.app.pdf.PdfPasswordRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class ImportReviewFilter {
    ALL,
    NEW_ONLY,
    DUPLICATES,
    UNCATEGORIZED
}

data class ImportReviewUiState(
    val isLoading: Boolean = false,
    val loadingTitle: String = "",
    val errorMessage: String? = null,
    val pdfPasswordPromptMessage: String? = null,
    val batch: ImportReviewBatch? = null,
    val filter: ImportReviewFilter = ImportReviewFilter.ALL,
    val isCommitting: Boolean = false,
    val committedSummary: ImportCommitSummary? = null,
    val expenseCategories: List<Category> = emptyList(),
    val incomeCategories: List<Category> = emptyList()
) {
    private val allItems: List<ImportReviewItem>
        get() = batch?.items.orEmpty()

    val visibleItems: List<ImportReviewItem>
        get() = when (filter) {
            ImportReviewFilter.ALL -> allItems
            ImportReviewFilter.NEW_ONLY -> allItems.filter { it.duplicateStatus == ImportDuplicateStatus.NEW }
            ImportReviewFilter.DUPLICATES -> allItems.filter { it.duplicateStatus != ImportDuplicateStatus.NEW }
            ImportReviewFilter.UNCATEGORIZED -> allItems.filter { isFallbackCategory(it.record.categoryId) }
        }

    val selectedCount: Int
        get() = allItems.count { it.selected }

    val newCount: Int
        get() = allItems.count { it.duplicateStatus == ImportDuplicateStatus.NEW }

    val duplicateCount: Int
        get() = allItems.count { it.duplicateStatus != ImportDuplicateStatus.NEW }

    val uncategorizedCount: Int
        get() = allItems.count { isFallbackCategory(it.record.categoryId) }

    val categoriesById: Map<String, Category>
        get() = (expenseCategories + incomeCategories).associateBy { it.id }

    private fun isFallbackCategory(categoryId: String): Boolean =
        categoryId == DefaultCategories.EXPENSE_OTHER_ID ||
            categoryId == DefaultCategories.INCOME_OTHER_ID
}

class ImportReviewViewModel(
    private val repository: LedgerRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private data class InternalState(
        val isLoading: Boolean = false,
        val loadingTitle: String = "",
        val errorMessage: String? = null,
        val pdfPasswordPromptMessage: String? = null,
        val batch: ImportReviewBatch? = null,
        val filter: ImportReviewFilter = ImportReviewFilter.ALL,
        val isCommitting: Boolean = false,
        val committedSummary: ImportCommitSummary? = null
    )

    private val internalState = MutableStateFlow(InternalState())
    private var loadingJob: Job? = null
    private var commitJob: Job? = null

    val uiState: StateFlow<ImportReviewUiState> = combine(
        internalState,
        repository.observeCategories()
    ) { state, categories ->
        val sorted = categories.sortedWith(compareBy<Category> { it.sortOrder }.thenBy { it.id })
        ImportReviewUiState(
            isLoading = state.isLoading,
            loadingTitle = state.loadingTitle,
            errorMessage = state.errorMessage,
            pdfPasswordPromptMessage = state.pdfPasswordPromptMessage,
            batch = state.batch,
            filter = state.filter,
            isCommitting = state.isCommitting,
            committedSummary = state.committedSummary,
            expenseCategories = sorted.filter { it.categoryType == CategoryType.EXPENSE },
            incomeCategories = sorted.filter { it.categoryType == CategoryType.INCOME }
        )
    }.flowOn(ioDispatcher)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ImportReviewUiState()
        )

    init {
        viewModelScope.launch {
            withContext(ioDispatcher) {
                repository.ensureSeeded()
            }
        }
    }

    fun startLoading(
        title: String,
        errorPrefix: String = "识别失败",
        loader: suspend () -> ImportReviewBatch
    ) {
        loadingJob?.cancel()
        internalState.value = InternalState(
            isLoading = true,
            loadingTitle = title
        )
        loadingJob = viewModelScope.launch {
            try {
                val batch = withContext(ioDispatcher) { loader() }
                if (!isActive) return@launch
                internalState.value = InternalState(
                    isLoading = false,
                    loadingTitle = "",
                    errorMessage = null,
                    pdfPasswordPromptMessage = null,
                    batch = batch,
                    filter = ImportReviewFilter.ALL,
                    isCommitting = false
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (pw: PdfPasswordRequiredException) {
                if (!isActive) return@launch
                internalState.update {
                    it.copy(
                        isLoading = false,
                        isCommitting = false,
                        pdfPasswordPromptMessage = pw.message ?: "请输入 PDF 打开密码"
                    )
                }
            } catch (t: Throwable) {
                if (!isActive) return@launch
                val detail = t.message?.takeIf { it.isNotBlank() } ?: "请重试"
                internalState.update {
                    it.copy(
                        isLoading = false,
                        isCommitting = false,
                        errorMessage = "$errorPrefix：$detail"
                    )
                }
            }
        }
    }

    fun clearPdfPasswordPrompt() {
        internalState.update { it.copy(pdfPasswordPromptMessage = null) }
    }

    fun beginLoading(title: String) {
        loadingJob?.cancel()
        loadingJob = null
        internalState.value = InternalState(
            isLoading = true,
            loadingTitle = title
        )
    }

    fun loadBatch(batch: ImportReviewBatch) {
        loadingJob?.cancel()
        loadingJob = null
        internalState.value = InternalState(
            isLoading = false,
            loadingTitle = "",
            errorMessage = null,
            batch = batch,
            filter = ImportReviewFilter.ALL,
            isCommitting = false
        )
    }

    fun failLoading(message: String) {
        internalState.update {
            it.copy(
                isLoading = false,
                isCommitting = false,
                errorMessage = message
            )
        }
    }

    fun setFilter(filter: ImportReviewFilter) {
        internalState.update { it.copy(filter = filter) }
    }

    fun toggleSelected(draftId: String) {
        updateItems { items ->
            val target = items.firstOrNull { it.draftId == draftId } ?: return@updateItems items
            val groupIds = target.reconciliation?.replacements?.keys ?: setOf(draftId)
            items.map { item ->
                if (item.draftId in groupIds) item.copy(selected = !target.selected) else item
            }
        }
    }

    fun selectAllNew() {
        updateItems { items ->
            items.map { item ->
                item.copy(selected = item.reconciliation != null || item.duplicateStatus == ImportDuplicateStatus.NEW)
            }
        }
    }

    fun updateItemCategory(draftId: String, categoryId: String) {
        val targetType = categoryTypeForId(categoryId)
        updateItems { items ->
            items.map { item ->
                if (item.draftId != draftId) {
                    item
                } else {
                    val expected = CategoryMatcher.categoryTypeFor(item.record.type)
                    if (targetType != null && targetType != expected) {
                        item
                    } else {
                        item.copy(
                            record = item.record.copy(categoryId = categoryId),
                            categoryManuallyChanged = true
                        )
                    }
                }
            }
        }
    }

    fun applyBatchCategory(categoryId: String) {
        val targetType = categoryTypeForId(categoryId) ?: return
        updateItems { items ->
            items.map { item ->
                if (!item.selected) {
                    item
                } else if (CategoryMatcher.categoryTypeFor(item.record.type) != targetType) {
                    item
                } else {
                    item.copy(
                        record = item.record.copy(categoryId = categoryId),
                        categoryManuallyChanged = true
                    )
                }
            }
        }
    }

    fun updateItemRecord(draftId: String, updated: TransactionRecord) {
        updateItems { items ->
            items.map { item ->
                if (item.draftId != draftId) {
                    item
                } else {
                    val categoryChanged = item.record.categoryId != updated.categoryId
                    item.copy(
                        record = updated,
                        userEdited = true,
                        categoryManuallyChanged = item.categoryManuallyChanged || categoryChanged,
                        needsVerification = false
                    )
                }
            }
        }
    }

    fun removeItem(draftId: String) {
        updateItems { items ->
            val groupIds = items.firstOrNull { it.draftId == draftId }
                ?.reconciliation?.replacements?.keys ?: setOf(draftId)
            items.filterNot { it.draftId in groupIds }
        }
    }

    fun addManualDraft(record: TransactionRecord) {
        val newDraft = ImportReviewItem(
            draftId = UUID.randomUUID().toString(),
            record = record,
            selected = true,
            duplicateStatus = ImportDuplicateStatus.NEW,
            matchedExistingId = null,
            duplicateHint = "",
            needsVerification = false,
            userEdited = true,
            categoryManuallyChanged = true
        )
        internalState.update { state ->
            val currentBatch = state.batch ?: ImportReviewBatch(sourceTitle = "手动补录", items = emptyList())
            state.copy(
                errorMessage = null,
                batch = currentBatch.copy(items = currentBatch.items + newDraft)
            )
        }
    }

    fun confirmImport(onComplete: ((ImportCommitSummary) -> Unit)? = null) {
        val currentBatch = internalState.value.batch ?: return
        if (internalState.value.isCommitting || commitJob?.isActive == true) return
        commitJob = viewModelScope.launch {
            internalState.update { it.copy(isCommitting = true, errorMessage = null) }
            try {
                val summary = withContext(ioDispatcher) {
                    repository.commitImportReview(currentBatch.items)
                }
                internalState.value = InternalState(committedSummary = summary)
                onComplete?.invoke(summary)
            } catch (ce: CancellationException) {
                internalState.update { it.copy(isCommitting = false) }
                throw ce
            } catch (e: Exception) {
                internalState.update {
                    it.copy(
                        isCommitting = false,
                        errorMessage = "导入失败：${e.message ?: "请重试"}"
                    )
                }
            } finally {
                commitJob = null
            }
        }
    }

    fun consumeCommittedSummary() {
        internalState.update { it.copy(committedSummary = null) }
    }

    fun reset() {
        if (commitJob?.isActive == true) return
        loadingJob?.cancel()
        loadingJob = null
        internalState.value = InternalState()
    }

    private fun updateItems(transform: (List<ImportReviewItem>) -> List<ImportReviewItem>) {
        internalState.update { state ->
            val batch = state.batch ?: return@update state
            state.copy(batch = batch.copy(items = transform(batch.items)))
        }
    }

    private fun categoryTypeForId(categoryId: String): CategoryType? {
        val state = uiState.value
        state.expenseCategories.firstOrNull { it.id == categoryId }?.let { return CategoryType.EXPENSE }
        state.incomeCategories.firstOrNull { it.id == categoryId }?.let { return CategoryType.INCOME }
        return DefaultCategories.all.firstOrNull { it.id == categoryId }?.categoryType
    }

    companion object {
        fun factory(repository: LedgerRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(ImportReviewViewModel::class.java)) {
                        return ImportReviewViewModel(repository = repository) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }
}
