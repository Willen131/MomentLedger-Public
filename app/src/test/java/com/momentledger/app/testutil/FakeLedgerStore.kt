package com.momentledger.app.testutil

import com.momentledger.app.data.repository.LedgerStore
import com.momentledger.app.data.repository.findMissingBuiltinRules
import com.momentledger.app.domain.model.CaptureDebugLog
import com.momentledger.app.domain.model.Category
import com.momentledger.app.domain.model.CategoryType
import com.momentledger.app.domain.model.DefaultCategories
import com.momentledger.app.domain.model.MerchantRule
import com.momentledger.app.domain.model.RuleMatchType
import com.momentledger.app.domain.model.TransactionRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.Locale

class FakeLedgerStore(seedDefaults: Boolean = true) : LedgerStore {

    private val lock = Any()
    private val transactions = linkedMapOf<Long, TransactionRecord>()
    private val categories = linkedMapOf<String, Category>()
    private val merchantRules = linkedMapOf<Long, MerchantRule>()
    private val debugLogs = mutableListOf<CaptureDebugLog>()

    private var nextTransactionId = 1L
    private var nextRuleId = 1L
    private var nextDebugLogId = 1L
    private var transactionCount = 0

    private val transactionsFlow = MutableStateFlow<List<TransactionRecord>>(emptyList())
    private val categoriesFlow = MutableStateFlow<List<Category>>(emptyList())
    private val debugLogsFlow = MutableStateFlow<List<CaptureDebugLog>>(emptyList())

    init {
        if (seedDefaults) {
            for (category in DefaultCategories.all) {
                categories[category.id] = category
            }
            for (rule in DefaultCategories.builtInRules) {
                merchantRules[rule.id] = rule
            }
            nextRuleId = (DefaultCategories.builtInRules.maxOfOrNull { it.id } ?: 0L) + 1L
            emitCategoriesLocked()
        }
    }

    override fun observeTransactionsInDateRange(
        startDate: String,
        endDate: String
    ): Flow<List<TransactionRecord>> = transactionsFlow.map { list ->
        list.filter { it.localDate >= startDate && it.localDate <= endDate }
    }

    override fun observeCategories(): Flow<List<Category>> = categoriesFlow

    override fun observeDebugLogs(): Flow<List<CaptureDebugLog>> = debugLogsFlow

    override suspend fun getAllTransactions(): List<TransactionRecord> = synchronized(lock) {
        sortedTransactionsLocked()
    }

    override suspend fun getTransactionById(id: Long): TransactionRecord? = synchronized(lock) {
        transactions[id]
    }

    override suspend fun getTransactionsInTimestampRange(
        startMillis: Long,
        endMillis: Long
    ): List<TransactionRecord> = synchronized(lock) {
        transactions.values
            .filter { it.timestampMillis in startMillis..endMillis }
            .sortedWith(compareBy<TransactionRecord> { it.timestampMillis }.thenBy { it.id })
    }

    override suspend fun findTransactionByExternalOrderId(orderId: String): TransactionRecord? =
        synchronized(lock) {
            val normalized = orderId.trim().takeIf { it.isNotEmpty() } ?: return null
            transactions.values.firstOrNull { it.externalOrderId == normalized }
        }

    override suspend fun insertTransaction(record: TransactionRecord): Long = synchronized(lock) {
        val normalizedOrderId = record.externalOrderId?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedOrderId != null && transactions.values.any { it.externalOrderId == normalizedOrderId }) {
            throw IllegalStateException("Duplicate externalOrderId: $normalizedOrderId")
        }
        val assignedId = if (record.id > 0L && !transactions.containsKey(record.id)) {
            nextTransactionId = maxOf(nextTransactionId, record.id + 1L)
            record.id
        } else {
            nextTransactionId++
        }
        transactions[assignedId] = record.copy(id = assignedId, externalOrderId = normalizedOrderId)
        emitTransactionsLocked()
        assignedId
    }

    override suspend fun updateTransaction(record: TransactionRecord) {
        synchronized(lock) {
            val normalizedOrderId = record.externalOrderId?.trim()?.takeIf { it.isNotEmpty() }
            if (normalizedOrderId != null &&
                transactions.values.any { it.id != record.id && it.externalOrderId == normalizedOrderId }
            ) {
                throw IllegalStateException("Duplicate externalOrderId: $normalizedOrderId")
            }
            if (transactions.containsKey(record.id)) {
                transactions[record.id] = record.copy(externalOrderId = normalizedOrderId)
                emitTransactionsLocked()
            }
        }
    }

    override suspend fun updateExcludedFromStats(
        id: Long,
        excluded: Boolean,
        updatedAtMillis: Long
    ) {
        synchronized(lock) {
            val existing = transactions[id] ?: return
            transactions[id] = existing.copy(
                excludedFromStats = excluded,
                updatedAtMillis = updatedAtMillis
            )
            emitTransactionsLocked()
        }
    }

    override suspend fun deleteTransactionById(id: Long) {
        synchronized(lock) {
            if (transactions.remove(id) != null) {
                emitTransactionsLocked()
            }
        }
    }

    override suspend fun getAllCategories(): List<Category> = synchronized(lock) {
        sortedCategoriesLocked()
    }

    override suspend fun getCategoriesByType(type: CategoryType): List<Category> = synchronized(lock) {
        sortedCategoriesLocked().filter { it.categoryType == type }
    }

    override suspend fun getCategoryById(id: String): Category? = synchronized(lock) {
        categories[id]
    }

    override suspend fun upsertCategory(category: Category) {
        synchronized(lock) {
            categories[category.id] = category
            emitCategoriesLocked()
        }
    }

    override suspend fun updateCategorySortOrders(orderedIds: List<String>) {
        synchronized(lock) {
            orderedIds.forEachIndexed { index, id ->
                val current = categories[id] ?: return@forEachIndexed
                categories[id] = current.copy(sortOrder = index + 1)
            }
            emitCategoriesLocked()
        }
    }

    override suspend fun deleteCategoryAndReassign(
        categoryId: String,
        fallbackCategoryId: String,
        updatedAtMillis: Long
    ) {
        synchronized(lock) {
            val target = categories[categoryId] ?: return
            if (target.isSystem) return

            for ((txId, tx) in transactions.entries.toList()) {
                if (tx.categoryId == categoryId) {
                    transactions[txId] = tx.copy(
                        categoryId = fallbackCategoryId,
                        updatedAtMillis = updatedAtMillis
                    )
                }
            }
            for ((ruleId, rule) in merchantRules.entries.toList()) {
                if (rule.categoryId == categoryId) {
                    merchantRules[ruleId] = rule.copy(categoryId = fallbackCategoryId)
                }
            }
            categories.remove(categoryId)
            emitTransactionsLocked()
            emitCategoriesLocked()
        }
    }

    override suspend fun getAllMerchantRules(): List<MerchantRule> = synchronized(lock) {
        merchantRules.values.sortedWith(
            compareBy<MerchantRule> { it.matchType.ordinal }
                .thenByDescending { it.priority }
                .thenByDescending { it.id }
        )
    }

    override suspend fun upsertExactUserRule(
        pattern: String,
        categoryType: CategoryType,
        categoryId: String,
        priority: Int
    ) {
        synchronized(lock) {
            val trimmed = pattern.trim()
            if (trimmed.isEmpty()) return
            val normalized = trimmed.lowercase(Locale.ROOT)
            val existing = merchantRules.values.firstOrNull { rule ->
                rule.categoryType == categoryType &&
                    rule.matchType == RuleMatchType.EXACT_USER &&
                    rule.pattern.trim().lowercase(Locale.ROOT) == normalized
            }
            if (existing != null) {
                merchantRules[existing.id] = existing.copy(
                    pattern = trimmed,
                    categoryId = categoryId,
                    priority = maxOf(existing.priority, priority)
                )
            } else {
                val id = nextRuleId++
                merchantRules[id] = MerchantRule(
                    id = id,
                    pattern = trimmed,
                    categoryType = categoryType,
                    matchType = RuleMatchType.EXACT_USER,
                    categoryId = categoryId,
                    priority = priority
                )
            }
        }
    }

    override suspend fun insertDebugLogAndTrim(log: CaptureDebugLog, keepCount: Int): Long =
        synchronized(lock) {
            val id = nextDebugLogId++
            debugLogs.add(log.copy(id = id))
            val sorted = debugLogs
                .sortedWith(compareByDescending<CaptureDebugLog> { it.timestampMillis }.thenByDescending { it.id })
                .take(keepCount)
            debugLogs.clear()
            debugLogs.addAll(sorted)
            emitDebugLogsLocked()
            id
        }

    override suspend fun clearDebugLogs() {
        synchronized(lock) {
            debugLogs.clear()
            emitDebugLogsLocked()
        }
    }

    override suspend fun ensureDefaultsSeeded(
        defaultCategories: List<Category>,
        defaultRules: List<MerchantRule>
    ) {
        synchronized(lock) {
            var categoriesChanged = false
            if (categories.isEmpty()) {
                for (category in defaultCategories) {
                    categories[category.id] = category
                }
                categoriesChanged = true
            } else {
                for (fallbackId in listOf(DefaultCategories.EXPENSE_OTHER_ID, DefaultCategories.INCOME_OTHER_ID, DefaultCategories.REFUND_CATEGORY_ID)) {
                    if (!categories.containsKey(fallbackId)) {
                        defaultCategories.firstOrNull { it.id == fallbackId }?.let {
                            categories[it.id] = it
                            categoriesChanged = true
                        }
                    }
                }
            }
            if (merchantRules.isEmpty()) {
                for (rule in defaultRules) {
                    merchantRules[rule.id] = rule
                }
                nextRuleId = (defaultRules.maxOfOrNull { it.id } ?: 0L) + 1L
            } else {
                val missingBuiltins = findMissingBuiltinRules(merchantRules.values.toList(), defaultRules)
                for (rule in missingBuiltins) {
                    val assignedId = nextRuleId++
                    merchantRules[assignedId] = rule.copy(id = assignedId)
                }
            }
            if (categoriesChanged) {
                emitCategoriesLocked()
            }
        }
    }

    override suspend fun replaceAll(
        categories: List<Category>,
        merchantRules: List<MerchantRule>,
        transactions: List<TransactionRecord>
    ) {
        synchronized(lock) {
            this.transactions.clear()
            this.categories.clear()
            this.merchantRules.clear()

            for (cat in categories) {
                this.categories[cat.id] = cat
            }
            for (rule in merchantRules) {
                this.merchantRules[rule.id] = rule
            }
            for (tx in transactions) {
                val normalizedOrderId = tx.externalOrderId?.trim()?.takeIf { it.isNotEmpty() }
                this.transactions[tx.id] = tx.copy(externalOrderId = normalizedOrderId)
            }

            nextTransactionId = (transactions.maxOfOrNull { it.id } ?: 0L) + 1L
            nextRuleId = (merchantRules.maxOfOrNull { it.id } ?: 0L) + 1L

            emitCategoriesLocked()
            emitTransactionsLocked()
        }
    }

    override suspend fun <T> runInTransaction(block: suspend () -> T): T {
        synchronized(lock) {
            transactionCount++
        }
        return block()
    }

    fun transactionInvocationCount(): Int = synchronized(lock) { transactionCount }

    fun clearAll() {
        synchronized(lock) {
            transactions.clear()
            categories.clear()
            merchantRules.clear()
            debugLogs.clear()
            nextTransactionId = 1L
            nextRuleId = 1L
            nextDebugLogId = 1L
            transactionCount = 0
            emitCategoriesLocked()
            emitTransactionsLocked()
            emitDebugLogsLocked()
        }
    }

    fun snapshotRules(): List<MerchantRule> = synchronized(lock) {
        merchantRules.values.toList()
    }

    private fun sortedTransactionsLocked(): List<TransactionRecord> =
        transactions.values.sortedWith(
            compareByDescending<TransactionRecord> { it.timestampMillis }
                .thenByDescending { it.id }
        )

    private fun sortedCategoriesLocked(): List<Category> =
        categories.values.sortedWith(
            compareBy<Category> { it.categoryType.ordinal }
                .thenBy { it.sortOrder }
                .thenBy { it.id }
        )

    private fun emitTransactionsLocked() {
        transactionsFlow.value = sortedTransactionsLocked()
    }

    private fun emitCategoriesLocked() {
        categoriesFlow.value = sortedCategoriesLocked()
    }

    private fun emitDebugLogsLocked() {
        debugLogsFlow.value = debugLogs.toList()
    }
}
