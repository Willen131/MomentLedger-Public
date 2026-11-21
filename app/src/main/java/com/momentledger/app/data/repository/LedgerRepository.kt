package com.momentledger.app.data.repository

import androidx.room.withTransaction
import com.momentledger.app.data.db.LedgerDatabase
import com.momentledger.app.data.db.MerchantRuleEntity
import com.momentledger.app.data.db.toDomain
import com.momentledger.app.data.db.toEntity
import com.momentledger.app.domain.backup.LocalBackupCodec
import com.momentledger.app.domain.backup.LocalBackupSnapshot
import com.momentledger.app.domain.engine.CategoryMatcher
import com.momentledger.app.domain.engine.DedupDecision
import com.momentledger.app.domain.engine.DeduplicationEngine
import com.momentledger.app.domain.model.CaptureDebugLog
import com.momentledger.app.domain.model.CaptureSource
import com.momentledger.app.domain.model.Category
import com.momentledger.app.domain.model.CategoryType
import com.momentledger.app.domain.model.DefaultCategories
import com.momentledger.app.domain.model.HOUR_OF_DAY_UNKNOWN
import com.momentledger.app.domain.model.ImportCommitSummary
import com.momentledger.app.domain.model.ImportDuplicateStatus
import com.momentledger.app.domain.model.ImportReconciliation
import com.momentledger.app.domain.model.ImportReviewBatch
import com.momentledger.app.domain.model.ImportReviewItem
import com.momentledger.app.domain.model.MerchantRule
import com.momentledger.app.domain.model.PaymentChannel
import com.momentledger.app.domain.model.ParsedPayment
import com.momentledger.app.domain.model.RuleMatchType
import com.momentledger.app.domain.model.TransactionRecord
import com.momentledger.app.domain.model.TransactionType
import com.momentledger.app.domain.parser.BankPdfParser
import com.momentledger.app.domain.parser.BillFileParser
import com.momentledger.app.domain.parser.PaymentPackages
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

sealed interface IngestResult {
    data class Inserted(val record: TransactionRecord) : IngestResult
    data class Merged(val record: TransactionRecord) : IngestResult
    data object Ignored : IngestResult
}

data class ImportSummary(
    val insertedCount: Int,
    val mergedCount: Int,
    val skippedCount: Int,
    val detectedFormat: String
)

interface LedgerStore {
    fun observeTransactionsInDateRange(startDate: String, endDate: String): Flow<List<TransactionRecord>>
    fun observeCategories(): Flow<List<Category>>
    fun observeDebugLogs(): Flow<List<CaptureDebugLog>>

    suspend fun getAllTransactions(): List<TransactionRecord>
    suspend fun getTransactionById(id: Long): TransactionRecord?
    suspend fun getTransactionsInTimestampRange(startMillis: Long, endMillis: Long): List<TransactionRecord>
    suspend fun findTransactionByExternalOrderId(orderId: String): TransactionRecord?
    suspend fun insertTransaction(record: TransactionRecord): Long
    suspend fun updateTransaction(record: TransactionRecord)
    suspend fun updateExcludedFromStats(id: Long, excluded: Boolean, updatedAtMillis: Long)
    suspend fun deleteTransactionById(id: Long)

    suspend fun getAllCategories(): List<Category>
    suspend fun getCategoriesByType(type: CategoryType): List<Category>
    suspend fun getCategoryById(id: String): Category?
    suspend fun upsertCategory(category: Category)
    suspend fun updateCategorySortOrders(orderedIds: List<String>)
    suspend fun deleteCategoryAndReassign(
        categoryId: String,
        fallbackCategoryId: String,
        updatedAtMillis: Long
    )

    suspend fun getAllMerchantRules(): List<MerchantRule>
    suspend fun upsertExactUserRule(
        pattern: String,
        categoryType: CategoryType,
        categoryId: String,
        priority: Int
    )

    suspend fun insertDebugLogAndTrim(log: CaptureDebugLog, keepCount: Int = 50): Long
    suspend fun clearDebugLogs()

    suspend fun ensureDefaultsSeeded(
        defaultCategories: List<Category>,
        defaultRules: List<MerchantRule>
    )

    suspend fun replaceAll(
        categories: List<Category>,
        merchantRules: List<MerchantRule>,
        transactions: List<TransactionRecord>
    )

    suspend fun <T> runInTransaction(block: suspend () -> T): T
}

class RoomLedgerStore(
    private val database: LedgerDatabase
) : LedgerStore {

    private val transactionDao = database.transactionDao()
    private val categoryDao = database.categoryDao()
    private val merchantRuleDao = database.merchantRuleDao()
    private val debugLogDao = database.debugLogDao()

    override fun observeTransactionsInDateRange(
        startDate: String,
        endDate: String
    ): Flow<List<TransactionRecord>> =
        transactionDao.observeInDateRange(startDate, endDate).map { rows -> rows.map { it.toDomain() } }

    override fun observeCategories(): Flow<List<Category>> =
        categoryDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeDebugLogs(): Flow<List<CaptureDebugLog>> =
        debugLogDao.observeLatest(MAX_DEBUG_LOGS).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getAllTransactions(): List<TransactionRecord> =
        transactionDao.getAll().map { it.toDomain() }

    override suspend fun getTransactionById(id: Long): TransactionRecord? =
        transactionDao.getById(id)?.toDomain()

    override suspend fun getTransactionsInTimestampRange(
        startMillis: Long,
        endMillis: Long
    ): List<TransactionRecord> =
        transactionDao.getInTimestampRange(startMillis, endMillis).map { it.toDomain() }

    override suspend fun findTransactionByExternalOrderId(orderId: String): TransactionRecord? {
        val normalized = orderId.trim().takeIf { it.isNotEmpty() } ?: return null
        return transactionDao.findByExternalOrderId(normalized)?.toDomain()
    }

    override suspend fun insertTransaction(record: TransactionRecord): Long =
        transactionDao.insert(record.toEntity())

    override suspend fun updateTransaction(record: TransactionRecord) {
        transactionDao.update(record.toEntity())
    }

    override suspend fun updateExcludedFromStats(
        id: Long,
        excluded: Boolean,
        updatedAtMillis: Long
    ) {
        transactionDao.updateExcludedFromStats(id, excluded, updatedAtMillis)
    }

    override suspend fun deleteTransactionById(id: Long) {
        transactionDao.deleteById(id)
    }

    override suspend fun getAllCategories(): List<Category> =
        categoryDao.getAll().map { it.toDomain() }

    override suspend fun getCategoriesByType(type: CategoryType): List<Category> =
        categoryDao.getByType(type).map { it.toDomain() }

    override suspend fun getCategoryById(id: String): Category? =
        categoryDao.getById(id)?.toDomain()

    override suspend fun upsertCategory(category: Category) {
        categoryDao.upsert(category.toEntity())
    }

    override suspend fun updateCategorySortOrders(orderedIds: List<String>) {
        categoryDao.updateSortOrders(orderedIds)
    }

    override suspend fun deleteCategoryAndReassign(
        categoryId: String,
        fallbackCategoryId: String,
        updatedAtMillis: Long
    ) {
        categoryDao.deleteCustomCategoryAtomically(
            categoryId = categoryId,
            fallbackCategoryId = fallbackCategoryId,
            updatedAtMillis = updatedAtMillis
        )
    }

    override suspend fun getAllMerchantRules(): List<MerchantRule> =
        merchantRuleDao.getAll().map { it.toDomain() }

    override suspend fun upsertExactUserRule(
        pattern: String,
        categoryType: CategoryType,
        categoryId: String,
        priority: Int
    ) {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) return
        val normalized = trimmed.lowercase(Locale.ROOT)
        database.withTransaction {
            val existing = merchantRuleDao.getExactUserRulesByType(categoryType)
                .firstOrNull { it.pattern.trim().lowercase(Locale.ROOT) == normalized }
            if (existing != null) {
                merchantRuleDao.update(
                    existing.copy(
                        pattern = trimmed,
                        categoryId = categoryId,
                        priority = maxOf(existing.priority, priority)
                    )
                )
            } else {
                merchantRuleDao.insert(
                    MerchantRuleEntity(
                        id = 0L,
                        pattern = trimmed,
                        categoryType = categoryType,
                        matchType = RuleMatchType.EXACT_USER,
                        categoryId = categoryId,
                        priority = priority
                    )
                )
            }
        }
    }

    override suspend fun insertDebugLogAndTrim(log: CaptureDebugLog, keepCount: Int): Long =
        debugLogDao.insertAndTrim(log.toEntity(), keepCount)

    override suspend fun clearDebugLogs() {
        debugLogDao.deleteAll()
    }

    override suspend fun ensureDefaultsSeeded(
        defaultCategories: List<Category>,
        defaultRules: List<MerchantRule>
    ) {
        database.withTransaction {
            if (categoryDao.count() == 0) {
                categoryDao.insertIgnoreAll(defaultCategories.map { it.toEntity() })
            } else {
                val missingFallbacks = listOf(
                    DefaultCategories.EXPENSE_OTHER_ID,
                    DefaultCategories.INCOME_OTHER_ID,
                    DefaultCategories.REFUND_CATEGORY_ID
                ).filter { categoryDao.getById(it) == null }
                if (missingFallbacks.isNotEmpty()) {
                    categoryDao.insertIgnoreAll(
                        defaultCategories.filter { it.id in missingFallbacks }.map { it.toEntity() }
                    )
                }
            }
            val existingRules = merchantRuleDao.getAll().map { it.toDomain() }
            if (existingRules.isEmpty()) {
                merchantRuleDao.insertIgnoreAll(defaultRules.map { it.toEntity() })
            } else {
                val missingBuiltins = findMissingBuiltinRules(existingRules, defaultRules)
                if (missingBuiltins.isNotEmpty()) {
                    merchantRuleDao.insertIgnoreAll(missingBuiltins.map { it.copy(id = 0L).toEntity() })
                }
            }
        }
    }

    override suspend fun replaceAll(
        categories: List<Category>,
        merchantRules: List<MerchantRule>,
        transactions: List<TransactionRecord>
    ) {
        database.withTransaction {
            transactionDao.deleteAll()
            merchantRuleDao.deleteAll()
            categoryDao.deleteAll()
            categoryDao.upsertAll(categories.map { it.toEntity() })
            merchantRuleDao.insertAll(merchantRules.map { it.toEntity() })
            transactionDao.insertAll(transactions.map { it.toEntity() })
        }
    }

    override suspend fun <T> runInTransaction(block: suspend () -> T): T =
        database.withTransaction { block() }

    private companion object {
        const val MAX_DEBUG_LOGS = 50
    }
}

class LedgerRepository(
    private val store: LedgerStore,
    private val dedupEngine: DeduplicationEngine = DeduplicationEngine(),
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {

    private val seedMutex = Mutex()
    private val writeMutex = Mutex()

    @Volatile
    private var seeded = false

    suspend fun ensureSeeded() {
        if (seeded) return
        seedMutex.withLock {
            if (!seeded) {
                store.ensureDefaultsSeeded(
                    defaultCategories = DefaultCategories.all,
                    defaultRules = DefaultCategories.builtInRules
                )
                seeded = true
            }
        }
    }

    fun observeTransactionsInDateRange(
        startDate: String,
        endDate: String
    ): Flow<List<TransactionRecord>> = store.observeTransactionsInDateRange(startDate, endDate)

    fun observeCategories(type: CategoryType? = null): Flow<List<Category>> =
        store.observeCategories().map { categories ->
            if (type == null) categories else categories.filter { it.categoryType == type }
        }

    fun observeDebugLogs(): Flow<List<CaptureDebugLog>> = store.observeDebugLogs()

    suspend fun getTransactionById(id: Long): TransactionRecord? =
        store.getTransactionById(id)

    suspend fun getCategoriesByType(type: CategoryType): List<Category> {
        ensureSeeded()
        return store.getCategoriesByType(type).filterNot { it.isArchived }
    }

    suspend fun updateCategorySortOrders(orderedIds: List<String>) {
        ensureSeeded()
        writeMutex.withLock {
            store.updateCategorySortOrders(orderedIds)
        }
    }

    suspend fun ingestRealTimePayment(parsed: ParsedPayment): IngestResult {
        ensureSeeded()
        return writeMutex.withLock {
            val nearby = store.getTransactionsInTimestampRange(
                startMillis = parsed.timestampMillis - NEARBY_DEDUP_WINDOW_MS,
                endMillis = parsed.timestampMillis + NEARBY_DEDUP_WINDOW_MS
            )
            when (val decision = dedupEngine.evaluateRealTime(parsed, nearby, parsed.timestampMillis)) {
                DedupDecision.IgnoreAsDebounce -> IngestResult.Ignored
                is DedupDecision.MergeIntoExisting -> {
                    val existing = nearby.firstOrNull { it.id == decision.existingId }
                        ?: store.getTransactionById(decision.existingId)
                    val rules = activeMerchantRules()
                    val upgraded = applyCategoryUpgradeIfEnriched(
                        existing = existing,
                        merged = decision.updatedRecord,
                        rules = rules
                    )
                    store.updateTransaction(upgraded)
                    IngestResult.Merged(upgraded)
                }
                DedupDecision.InsertAsNew -> {
                    val normalizedOrderId = normalizeOrderId(parsed.externalOrderId)
                    if (normalizedOrderId != null &&
                        store.findTransactionByExternalOrderId(normalizedOrderId) != null
                    ) {
                        return@withLock IngestResult.Ignored
                    }
                    val rules = activeMerchantRules()
                    val zoned = Instant.ofEpochMilli(parsed.timestampMillis).atZone(zoneId)
                    val matchedCategory = CategoryMatcher.matchCategoryId(
                        merchant = parsed.merchant,
                        note = parsed.note,
                        txType = parsed.type,
                        rules = rules
                    )
                    val validCategoryId = resolveExistingCategoryId(matchedCategory, parsed.type)
                    val newRecord = TransactionRecord(
                        id = 0L,
                        timestampMillis = parsed.timestampMillis,
                        localDate = zoned.toLocalDate().toString(),
                        hourOfDay = zoned.hour,
                        amountCents = parsed.amountCents,
                        type = parsed.type,
                        channel = parsed.channel,
                        fundingMethod = parsed.fundingMethod.trim(),
                        merchant = parsed.merchant.trim(),
                        categoryId = validCategoryId,
                        note = parsed.note.trim(),
                        excludedFromStats = false,
                        captureSource = parsed.source,
                        externalOrderId = normalizedOrderId,
                        createdAtMillis = parsed.timestampMillis,
                        updatedAtMillis = parsed.timestampMillis
                    )
                    val insertedId = store.insertTransaction(newRecord)
                    IngestResult.Inserted(newRecord.copy(id = insertedId))
                }
            }
        }
    }

    suspend fun saveManualTransaction(record: TransactionRecord): Long {
        ensureSeeded()
        return writeMutex.withLock {
            val zoned = Instant.ofEpochMilli(record.timestampMillis).atZone(zoneId)
            val resolvedLocalDate = record.localDate.trim().ifEmpty { zoned.toLocalDate().toString() }
            val resolvedHour = when {
                record.hourOfDay == HOUR_OF_DAY_UNKNOWN -> HOUR_OF_DAY_UNKNOWN
                record.localDate.isBlank() || record.hourOfDay !in 0..23 -> zoned.hour
                else -> record.hourOfDay
            }
            val now = System.currentTimeMillis()
            val resolvedCreated = if (record.createdAtMillis > 0L) record.createdAtMillis else now
            val resolvedUpdated = if (record.updatedAtMillis > 0L) record.updatedAtMillis else resolvedCreated
            val matchedCategoryId = if (record.categoryId.isBlank()) {
                CategoryMatcher.matchCategoryId(
                    merchant = record.merchant,
                    note = record.note,
                    txType = record.type,
                    rules = activeMerchantRules()
                )
            } else {
                record.categoryId
            }
            val validCategoryId = resolveExistingCategoryId(matchedCategoryId, record.type)
            val normalized = record.copy(
                localDate = resolvedLocalDate,
                hourOfDay = resolvedHour,
                fundingMethod = record.fundingMethod.trim(),
                customChannel = record.customChannel?.trim()?.takeIf { it.isNotEmpty() },
                merchant = record.merchant.trim(),
                categoryId = validCategoryId,
                note = record.note.trim(),
                externalOrderId = normalizeOrderId(record.externalOrderId),
                createdAtMillis = resolvedCreated,
                updatedAtMillis = resolvedUpdated
            )
            store.insertTransaction(normalized)
        }
    }

    suspend fun updateTransaction(
        record: TransactionRecord,
        learnMerchantCategory: Boolean = true
    ) {
        ensureSeeded()
        writeMutex.withLock {
            val existing = store.getTransactionById(record.id) ?: return@withLock
            val zoned = Instant.ofEpochMilli(record.timestampMillis).atZone(zoneId)
            val isExplicitUnknownHour = record.hourOfDay == HOUR_OF_DAY_UNKNOWN
            val needsDateSync = record.localDate.isBlank() ||
                (!isExplicitUnknownHour && record.hourOfDay !in 0..23) ||
                (record.timestampMillis != existing.timestampMillis &&
                    record.localDate == existing.localDate &&
                    record.hourOfDay == existing.hourOfDay)
            val resolvedLocalDate = if (needsDateSync) {
                zoned.toLocalDate().toString()
            } else {
                record.localDate
            }
            val resolvedHour = when {
                isExplicitUnknownHour -> HOUR_OF_DAY_UNKNOWN
                needsDateSync -> zoned.hour
                else -> record.hourOfDay
            }
            val validCategoryId = if (record.categoryId == existing.categoryId && record.type == existing.type) {
                existing.categoryId
            } else {
                resolveExistingCategoryId(record.categoryId, record.type)
            }
            val updated = record.copy(
                localDate = resolvedLocalDate,
                hourOfDay = resolvedHour,
                fundingMethod = record.fundingMethod.trim(),
                customChannel = record.customChannel?.trim()?.takeIf { it.isNotEmpty() },
                merchant = record.merchant.trim(),
                categoryId = validCategoryId,
                note = record.note.trim(),
                externalOrderId = normalizeOrderId(record.externalOrderId),
                updatedAtMillis = maxOf(record.updatedAtMillis, System.currentTimeMillis())
            )
            store.updateTransaction(updated)

            val categoryChanged = existing.categoryId != updated.categoryId
            val merchantRenamedWithNonFallback = existing.merchant != updated.merchant &&
                !isFallbackCategory(updated.categoryId)
            val categoryAccepted = updated.categoryId == record.categoryId.trim()
            if (learnMerchantCategory &&
                categoryAccepted &&
                (categoryChanged || merchantRenamedWithNonFallback)
            ) {
                learnMerchantRuleIfEligible(
                    merchant = updated.merchant,
                    txType = updated.type,
                    categoryId = updated.categoryId
                )
            }
        }
    }

    suspend fun updateCategoryAndLearn(transactionId: Long, categoryId: String) {
        ensureSeeded()
        writeMutex.withLock {
            val existing = store.getTransactionById(transactionId) ?: return@withLock
            val validCategoryId = resolveExistingCategoryId(categoryId, existing.type)
            val updated = existing.copy(
                categoryId = validCategoryId,
                updatedAtMillis = System.currentTimeMillis()
            )
            store.updateTransaction(updated)
            if (validCategoryId == categoryId.trim()) {
                learnMerchantRuleIfEligible(
                    merchant = updated.merchant,
                    txType = updated.type,
                    categoryId = validCategoryId
                )
            }
        }
    }

    suspend fun setExcludedFromStats(transactionId: Long, excluded: Boolean) {
        writeMutex.withLock {
            store.updateExcludedFromStats(
                id = transactionId,
                excluded = excluded,
                updatedAtMillis = System.currentTimeMillis()
            )
        }
    }

    suspend fun deleteTransaction(transactionId: Long) {
        writeMutex.withLock {
            store.deleteTransactionById(transactionId)
        }
    }

    suspend fun importBillStream(fileName: String, stream: InputStream): ImportSummary {
        ensureSeeded()
        val parsed = BillFileParser.parse(fileName = fileName, inputStream = stream, zoneId = zoneId)
        return writeMutex.withLock {
            store.runInTransaction {
                var insertedCount = 0
                var mergedCount = 0
                var skippedCount = parsed.skippedCount
                val rules = activeMerchantRules()
                val categoryTypesById = store.getAllCategories().filterNot { it.isArchived }.associate { it.id to it.categoryType }

                for (item in parsed.items) {
                    val normalizedOrderId = normalizeOrderId(item.externalOrderId)
                    val exactMatch = normalizedOrderId?.let { store.findTransactionByExternalOrderId(it) }
                    val nearby = store.getTransactionsInTimestampRange(
                        startMillis = item.timestampMillis - NEARBY_DEDUP_WINDOW_MS,
                        endMillis = item.timestampMillis + NEARBY_DEDUP_WINDOW_MS
                    )
                    when (val decision = dedupEngine.evaluateImport(item, exactMatch, nearby)) {
                        DedupDecision.IgnoreAsDebounce -> {
                            skippedCount++
                        }
                        is DedupDecision.MergeIntoExisting -> {
                            val existing = nearby.firstOrNull { it.id == decision.existingId }
                                ?: store.getTransactionById(decision.existingId)
                            val upgraded = applyCategoryUpgradeIfEnriched(
                                existing = existing,
                                merged = decision.updatedRecord,
                                rules = rules,
                                categoryTypesById = categoryTypesById
                            )
                            store.updateTransaction(upgraded)
                            mergedCount++
                        }
                        DedupDecision.InsertAsNew -> {
                            val zoned = Instant.ofEpochMilli(item.timestampMillis).atZone(zoneId)
                            val matchedCategoryId = CategoryMatcher.matchCategoryId(
                                merchant = item.merchant,
                                note = item.note,
                                txType = item.type,
                                rules = rules
                            )
                            val validCategoryId = resolveReviewCategoryId(matchedCategoryId, item.type, categoryTypesById)
                            val now = System.currentTimeMillis()
                            val newRecord = TransactionRecord(
                                id = 0L,
                                timestampMillis = item.timestampMillis,
                                localDate = zoned.toLocalDate().toString(),
                                hourOfDay = if (item.hasTimeOfDay) zoned.hour else HOUR_OF_DAY_UNKNOWN,
                                amountCents = item.amountCents,
                                type = item.type,
                                channel = item.channel,
                                fundingMethod = item.fundingMethod.trim(),
                                merchant = item.merchant.trim(),
                                categoryId = validCategoryId,
                                note = item.note.trim(),
                                excludedFromStats = false,
                                captureSource = CaptureSource.IMPORT,
                                externalOrderId = normalizedOrderId,
                                createdAtMillis = item.timestampMillis,
                                updatedAtMillis = maxOf(item.timestampMillis, now)
                            )
                            store.insertTransaction(newRecord)
                            insertedCount++
                        }
                    }
                }

                ImportSummary(
                    insertedCount = insertedCount,
                    mergedCount = mergedCount,
                    skippedCount = skippedCount,
                    detectedFormat = parsed.detectedFormat
                )
            }
        }
    }

    suspend fun prepareImportReview(
        items: List<ParsedPayment>,
        sourceTitle: String,
        skippedCount: Int = 0,
        rawOcrText: String = "",
        imageUris: List<String> = emptyList(),
        needsVerificationFlags: List<Boolean> = emptyList(),
        dedupIntraBatch: Boolean = true
    ): ImportReviewBatch {
        ensureSeeded()
        val rules = activeMerchantRules()
        val categoryTypesById = store.getAllCategories().filterNot { it.isArchived }.associate { it.id to it.categoryType }
        val reviewItems = mutableListOf<ImportReviewItem>()
        val seenInBatch = mutableListOf<ParsedPayment>()
        val seenOrderIdsInBatch = HashSet<String>()
        val matchedExistingIdsInBatch = HashSet<Long>()

        for ((index, item) in items.withIndex()) {
            val normalizedOrderId = normalizeOrderId(item.externalOrderId)
            val zoned = Instant.ofEpochMilli(item.timestampMillis).atZone(zoneId)
            val matchedCategoryId = CategoryMatcher.matchCategoryId(
                merchant = item.merchant,
                note = item.note,
                txType = item.type,
                rules = rules
            )
            val validCategoryId = resolveReviewCategoryId(matchedCategoryId, item.type, categoryTypesById)
            val now = System.currentTimeMillis()
            val draftRecord = TransactionRecord(
                id = 0L,
                timestampMillis = item.timestampMillis,
                localDate = zoned.toLocalDate().toString(),
                hourOfDay = if (item.hasTimeOfDay) zoned.hour else HOUR_OF_DAY_UNKNOWN,
                amountCents = item.amountCents,
                type = item.type,
                channel = item.channel,
                fundingMethod = item.fundingMethod.trim(),
                merchant = item.merchant.trim(),
                categoryId = validCategoryId,
                note = item.note.trim(),
                excludedFromStats = false,
                captureSource = item.source,
                externalOrderId = normalizedOrderId,
                createdAtMillis = item.timestampMillis,
                updatedAtMillis = maxOf(item.timestampMillis, now)
            )

            val isDuplicateOrderInBatch = normalizedOrderId != null && normalizedOrderId in seenOrderIdsInBatch
            val isIntraBatchDuplicate = isDuplicateOrderInBatch || (dedupIntraBatch && seenInBatch.any { earlier ->
                isSameIntraBatchPayment(earlier, item)
            })

            val dedupMatch = if (isIntraBatchDuplicate) {
                com.momentledger.app.domain.engine.ReviewDedupMatch(
                    status = ImportDuplicateStatus.DUPLICATE_SKIP,
                    matchedExistingId = null,
                    hint = if (isDuplicateOrderInBatch) "同批重复单号" else "同批重复",
                    autoSelect = false
                )
            } else {
                val exactMatch = normalizedOrderId?.let { store.findTransactionByExternalOrderId(it) }
                    ?.takeUnless { it.id in matchedExistingIdsInBatch }
                val localDate = zoned.toLocalDate()
                val dayStartMillis = localDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val dayEndMillis = localDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1L
                val startQuery = minOf(item.timestampMillis - REVIEW_DEDUP_WINDOW_MS, dayStartMillis - REVIEW_DEDUP_WINDOW_MS)
                val endQuery = maxOf(item.timestampMillis + REVIEW_DEDUP_WINDOW_MS, dayEndMillis + REVIEW_DEDUP_WINDOW_MS)
                val nearby = store.getTransactionsInTimestampRange(
                    startMillis = startQuery,
                    endMillis = endQuery
                ).filterNot { it.id in matchedExistingIdsInBatch }
                dedupEngine.evaluateReviewCandidate(item, exactMatch, nearby)
            }

            if (!isIntraBatchDuplicate) {
                seenInBatch += item
                normalizedOrderId?.let { seenOrderIdsInBatch += it }
                dedupMatch.matchedExistingId?.let { matchedExistingIdsInBatch += it }
                matchedExistingIdsInBatch.addAll(dedupMatch.matchedExistingIds)
            }

            reviewItems += ImportReviewItem(
                draftId = UUID.randomUUID().toString(),
                record = draftRecord,
                selected = (dedupMatch.status == ImportDuplicateStatus.NEW && !dedupMatch.needsVerification) || dedupMatch.autoSelect,
                duplicateStatus = dedupMatch.status,
                matchedExistingId = dedupMatch.matchedExistingId,
                duplicateHint = dedupMatch.hint,
                needsVerification = dedupMatch.needsVerification || needsVerificationFlags.getOrElse(index) { false },
                userEdited = false,
                categoryManuallyChanged = false
            )
        }

        assembleReconciliations(
            parsedItems = items,
            reviewItems = reviewItems,
            categoryTypesById = categoryTypesById
        )

        return ImportReviewBatch(
            sourceTitle = sourceTitle,
            items = reviewItems,
            skippedCount = skippedCount,
            rawOcrText = rawOcrText,
            imageUris = imageUris
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Atomic reconciliation groups: one stored row is replaced by a principal + fee, or by the
    // wallet sub-orders that explain a bank summary.
    // ---------------------------------------------------------------------------------------------

    private data class WalletReviewCandidate(
        val reviewIndex: Int,
        val item: ImportReviewItem
    )

    private data class CombinedMatch(
        val bank: TransactionRecord,
        val wallets: List<WalletReviewCandidate>
    )

    private suspend fun assembleReconciliations(
        parsedItems: List<ParsedPayment>,
        reviewItems: MutableList<ImportReviewItem>,
        categoryTypesById: Map<String, CategoryType>
    ) {
        val reservedOriginalIds = HashSet<Long>()
        markAlreadyReconciledWithdrawals(parsedItems, reviewItems)
        assembleWithdrawalFeeReconciliation(
            parsedItems = parsedItems,
            reviewItems = reviewItems,
            reservedOriginalIds = reservedOriginalIds,
            categoryTypesById = categoryTypesById
        )
        assembleCombinedWalletReconciliation(
            parsedItems = parsedItems,
            reviewItems = reviewItems,
            reservedOriginalIds = reservedOriginalIds
        )
    }

    /**
     * On a re-import, a gross wallet withdrawal is larger than the net principal already stored.
     * Recognize the previously generated principal + fee pair so the gross amount never comes back.
     */
    private suspend fun markAlreadyReconciledWithdrawals(
        parsedItems: List<ParsedPayment>,
        reviewItems: MutableList<ImportReviewItem>
    ) {
        for ((index, parsed) in parsedItems.withIndex()) {
            if (parsed.type != TransactionType.TRANSFER || parsed.channel !in walletChannels ||
                !parsed.externalOrderId.isNullOrBlank() ||
                reviewItems[index].duplicateStatus != ImportDuplicateStatus.NEW
            ) continue
            val candidates = store.getTransactionsInTimestampRange(
                parsed.timestampMillis, parsed.timestampMillis
            )
            val principals = candidates.filter { principal ->
                principal.channel == parsed.channel && principal.type == TransactionType.TRANSFER &&
                    principal.merchant == parsed.merchant && principal.fundingMethod == parsed.fundingMethod &&
                    principal.externalOrderId.isNullOrBlank() &&
                    candidates.any { fee ->
                        fee.externalOrderId == withdrawalFeeExternalOrderId(null, principal.id) &&
                            fee.type == TransactionType.EXPENSE &&
                            principal.amountCents + fee.amountCents == parsed.amountCents
                    }
            }
            val principal = principals.singleOrNull() ?: continue
            reviewItems[index] = reviewItems[index].copy(
                selected = false, duplicateStatus = ImportDuplicateStatus.DUPLICATE_SKIP,
                matchedExistingId = principal.id, duplicateHint = "该提现已拆分本金和手续费"
            )
        }
    }

    private suspend fun assembleWithdrawalFeeReconciliation(
        parsedItems: List<ParsedPayment>,
        reviewItems: MutableList<ImportReviewItem>,
        reservedOriginalIds: MutableSet<Long>,
        categoryTypesById: Map<String, CategoryType>
    ) {
        for (index in parsedItems.indices) {
            val parsed = parsedItems[index]
            if (parsed.type != TransactionType.TRANSFER) continue
            val item = reviewItems[index]
            if (item.reconciliation != null) continue
            if (item.duplicateStatus != ImportDuplicateStatus.DUPLICATE_SKIP) continue
            val originalId = item.matchedExistingId ?: continue
            if (originalId in reservedOriginalIds) continue
            val original = store.getTransactionById(originalId) ?: continue

            val incomingOrderId = normalizeOrderId(parsed.externalOrderId)
            val originalOrderId = normalizeOrderId(original.externalOrderId)
            if (incomingOrderId != null &&
                incomingOrderId == originalOrderId &&
                parsed.channel == original.channel
            ) {
                continue
            }

            val matched = dedupEngine.findWithdrawalFeeTransferMatch(parsed, listOf(original))
                ?: continue
            if (matched.id != original.id) continue

            val incomingIsBank = parsed.channel == PaymentChannel.BANK
            if (!incomingIsBank && parsed.channel !in walletChannels) continue
            if (incomingIsBank && original.channel !in walletChannels) continue
            if (!incomingIsBank && original.channel != PaymentChannel.BANK) continue

            val walletRecord = if (incomingIsBank) original else item.record
            val bankCents = if (incomingIsBank) parsed.amountCents else original.amountCents
            val walletCents = if (incomingIsBank) original.amountCents else parsed.amountCents
            if (bankCents >= walletCents) continue
            val feeCents = walletCents - bankCents
            if (feeCents <= 0L) continue

            val walletOrderId = normalizeOrderId(walletRecord.externalOrderId)
            val now = System.currentTimeMillis()
            val principal = walletRecord.copy(
                id = 0L,
                amountCents = bankCents,
                type = TransactionType.TRANSFER,
                categoryId = resolveReviewCategoryId(
                    walletRecord.categoryId,
                    TransactionType.TRANSFER,
                    categoryTypesById
                ),
                excludedFromStats = original.excludedFromStats,
                externalOrderId = walletOrderId,
                updatedAtMillis = now
            )
            val feeDraftId = UUID.randomUUID().toString()
            val feeRecord = TransactionRecord(
                id = 0L,
                timestampMillis = walletRecord.timestampMillis,
                localDate = walletRecord.localDate,
                hourOfDay = walletRecord.hourOfDay,
                amountCents = feeCents,
                type = TransactionType.EXPENSE,
                channel = walletRecord.channel,
                fundingMethod = walletRecord.fundingMethod,
                merchant = "提现手续费",
                categoryId = resolveReviewCategoryId(
                    DefaultCategories.EXPENSE_OTHER_ID,
                    TransactionType.EXPENSE,
                    categoryTypesById
                ),
                note = "提现手续费 · 关联提现：${walletOrderId ?: original.id}",
                excludedFromStats = original.excludedFromStats,
                captureSource = walletRecord.captureSource,
                externalOrderId = withdrawalFeeExternalOrderId(walletOrderId, original.id),
                createdAtMillis = now,
                updatedAtMillis = now
            )
            val replacements = LinkedHashMap<String, TransactionRecord>()
            replacements[item.draftId] = principal
            replacements[feeDraftId] = feeRecord
            val group = ImportReconciliation(original = original, replacements = replacements)

            reviewItems[index] = item.copy(
                record = principal,
                selected = true,
                duplicateStatus = ImportDuplicateStatus.DUPLICATE_MERGE,
                matchedExistingId = original.id,
                duplicateHint = "将替换提现记录为本金 + 手续费（¥${formatCents(feeCents)}）",
                reconciliation = group
            )
            reviewItems += ImportReviewItem(
                draftId = feeDraftId,
                record = feeRecord,
                selected = true,
                duplicateStatus = ImportDuplicateStatus.NEW,
                matchedExistingId = null,
                duplicateHint = "提现手续费将从本金中拆出",
                reconciliation = group
            )
            reservedOriginalIds += original.id
        }
    }

    private suspend fun assembleCombinedWalletReconciliation(
        parsedItems: List<ParsedPayment>,
        reviewItems: MutableList<ImportReviewItem>,
        reservedOriginalIds: MutableSet<Long>
    ) {
        val walletCandidates = mutableListOf<WalletReviewCandidate>()
        for (index in parsedItems.indices) {
            val parsed = parsedItems[index]
            if (parsed.channel !in walletChannels) continue
            if (parsed.type != TransactionType.EXPENSE && parsed.type != TransactionType.REFUND) continue
            val item = reviewItems[index]
            if (item.reconciliation != null) continue
            if (item.duplicateStatus != ImportDuplicateStatus.NEW) continue
            if (!item.selected) continue
            walletCandidates += WalletReviewCandidate(index, item)
        }
        if (walletCandidates.size < 2) return

        val minTimestamp = walletCandidates.minOf { it.item.record.timestampMillis }
        val maxTimestamp = walletCandidates.maxOf { it.item.record.timestampMillis }
        val bankRecords = store.getTransactionsInTimestampRange(
            startMillis = minTimestamp - REVIEW_DEDUP_WINDOW_MS,
            endMillis = maxTimestamp + REVIEW_DEDUP_WINDOW_MS
        ).filter { record ->
            record.channel == PaymentChannel.BANK &&
                record.type == TransactionType.EXPENSE &&
                record.id !in reservedOriginalIds
        }.sortedWith(compareBy<TransactionRecord> { it.timestampMillis }.thenBy { it.id })
        if (bankRecords.isEmpty()) return

        val possibleMatches = mutableListOf<CombinedMatch>()
        val ambiguousDraftIds = HashSet<String>()
        for (bank in bankRecords) {
            if (reviewItems.any { it.matchedExistingId == bank.id }) continue
            val subsets = dedupEngine.findCombinedWalletPaymentMatches(
                bank.toCombinationPayment(), walletCandidates.map { it.item.record }
            ).map { subset ->
                subset.map { record -> walletCandidates.first { it.item.record === record } }
            }.filter { subset ->
                val orders = subset.map { normalizeOrderId(it.item.record.externalOrderId) }
                orders.all { it != null } && orders.distinct().size == subset.size
            }
            if (subsets.size == 1) {
                possibleMatches += CombinedMatch(bank, subsets.single())
            } else {
                subsets.flatten().forEach { ambiguousDraftIds += it.item.draftId }
            }
        }
        // Prefer explicit merchant agreement over a competing, unrelated bank merchant.
        val preferredMatches = possibleMatches.filter { match ->
            combinedMerchantsAligned(match) || possibleMatches.none { other ->
                combinedMerchantsAligned(other) &&
                    other.wallets.any { wallet -> match.wallets.any { it.item.draftId == wallet.item.draftId } }
            }
        }
        val bankCounts = preferredMatches.flatMap { it.wallets }
            .groupingBy { it.item.draftId }.eachCount()
        ambiguousDraftIds += bankCounts.filterValues { it > 1 }.keys
        for (match in preferredMatches) {
            if (match.wallets.none { it.item.draftId in ambiguousDraftIds }) {
                createCombinedReconciliationGroup(match, reviewItems)
                reservedOriginalIds += match.bank.id
            }
        }
        for (candidate in walletCandidates) {
            if (candidate.item.draftId in ambiguousDraftIds) {
                reviewItems[candidate.reviewIndex] = candidate.item.copy(
                    selected = false, needsVerification = true,
                    duplicateHint = "存在多组合并付款候选，请核对后选择"
                )
            }
        }
    }

    private fun createCombinedReconciliationGroup(
        match: CombinedMatch,
        reviewItems: MutableList<ImportReviewItem>
    ) {
        val replacements = LinkedHashMap<String, TransactionRecord>()
        match.wallets.forEach { candidate ->
            replacements[candidate.item.draftId] = candidate.item.record
        }
        val group = ImportReconciliation(original = match.bank, replacements = replacements)
        match.wallets.forEachIndexed { index, candidate ->
            val isPrimary = index == 0
            reviewItems[candidate.reviewIndex] = candidate.item.copy(
                selected = true,
                duplicateStatus = if (isPrimary) {
                    ImportDuplicateStatus.DUPLICATE_MERGE
                } else {
                    ImportDuplicateStatus.NEW
                },
                matchedExistingId = if (isPrimary) match.bank.id else null,
                duplicateHint = if (isPrimary) {
                    "将替换银行汇总为 ${match.wallets.size} 笔钱包子单"
                } else {
                    "银行汇总拆分出的钱包子单"
                },
                reconciliation = group
            )
        }
    }

    private fun combinedMerchantsAligned(match: CombinedMatch): Boolean {
        val bankMerchant = match.bank.merchant.trim()
        return bankMerchant.isNotEmpty() && match.wallets.all {
            it.item.record.merchant.trim().equals(bankMerchant, ignoreCase = true)
        }
    }

    private fun TransactionRecord.toCombinationPayment(): ParsedPayment = ParsedPayment(
        channel = channel,
        fundingMethod = fundingMethod,
        type = type,
        amountCents = amountCents,
        merchant = merchant,
        timestampMillis = timestampMillis,
        source = captureSource,
        externalOrderId = externalOrderId,
        note = note,
        hasTimeOfDay = hourOfDay in 0..23
    )

    private fun resolveReviewCategoryId(
        categoryId: String,
        txType: TransactionType,
        categoryTypesById: Map<String, CategoryType>
    ): String {
        val categoryType = categoryTypesById[categoryId]
        return if (categoryType != null && acceptsCategory(categoryId, categoryType, txType)) {
            categoryId
        } else {
            fallbackCategoryFor(txType).takeIf { it in categoryTypesById }.orEmpty()
        }
    }

    private fun withdrawalFeeExternalOrderId(walletOrderId: String?, originalId: Long): String =
        "momentledger:withdrawal-fee:${walletOrderId ?: originalId}"

    private fun formatCents(cents: Long): String =
        String.format(Locale.ROOT, "%.2f", cents / 100.0)

    private data class ImportGroupPlan(
        val reconciliation: ImportReconciliation,
        val primaryItem: ImportReviewItem,
        val orderedItems: List<ImportReviewItem>,
        val alreadyApplied: Boolean
    )

    suspend fun commitImportReview(items: List<ImportReviewItem>): ImportCommitSummary {
        ensureSeeded()
        return writeMutex.withLock {
            store.runInTransaction {
                var insertedCount = 0
                var mergedCount = 0
                var skippedCount = 0
                val rules = activeMerchantRules()
                val categoryTypesById = store.getAllCategories().filterNot { it.isArchived }.associate { it.id to it.categoryType }

                // Validate every group before any write so a partially edited group can never
                // delete/replace the original row.
                val groupPlans = buildImportGroupPlans(items)

                for (plan in groupPlans) {
                    if (plan.alreadyApplied) {
                        skippedCount += plan.orderedItems.size
                        continue
                    }
                    val original = plan.reconciliation.original
                    val now = System.currentTimeMillis()
                    val primary = plan.primaryItem
                    val primaryFinal = primary.record.copy(
                        id = original.id,
                        fundingMethod = primary.record.fundingMethod.trim(),
                        merchant = primary.record.merchant.trim(),
                        categoryId = resolveReviewCategoryId(
                            primary.record.categoryId,
                            primary.record.type,
                            categoryTypesById
                        ),
                        note = primary.record.note.trim(),
                        excludedFromStats = original.excludedFromStats,
                        externalOrderId = normalizeOrderId(primary.record.externalOrderId),
                        createdAtMillis = original.createdAtMillis,
                        updatedAtMillis = maxOf(primary.record.updatedAtMillis, now)
                    )
                    store.updateTransaction(primaryFinal)
                    mergedCount++
                    if (primary.categoryManuallyChanged) {
                        learnMerchantRuleIfEligible(
                            merchant = primaryFinal.merchant,
                            txType = primaryFinal.type,
                            categoryId = primaryFinal.categoryId
                        )
                    }

                    for (item in plan.orderedItems.drop(1)) {
                        val oid = normalizeOrderId(item.record.externalOrderId)
                        if (oid != null && store.findTransactionByExternalOrderId(oid) != null) {
                            throw IllegalArgumentException("替换项目单号冲突，请重新预览")
                        }
                        val child = item.record.copy(
                            id = 0L,
                            fundingMethod = item.record.fundingMethod.trim(),
                            merchant = item.record.merchant.trim(),
                            categoryId = resolveReviewCategoryId(
                                item.record.categoryId,
                                item.record.type,
                                categoryTypesById
                            ),
                            note = item.record.note.trim(),
                            excludedFromStats = original.excludedFromStats,
                            externalOrderId = oid,
                            createdAtMillis = if (item.record.createdAtMillis > 0L) {
                                item.record.createdAtMillis
                            } else {
                                now
                            },
                            updatedAtMillis = maxOf(item.record.updatedAtMillis, now)
                        )
                        store.insertTransaction(child)
                        insertedCount++
                    }
                }

                for (item in items) {
                    if (item.reconciliation != null) {
                        if (!item.selected) skippedCount++
                        continue
                    }
                    if (!item.selected) {
                        skippedCount++
                        continue
                    }

                    val validCategoryId = resolveReviewCategoryId(item.record.categoryId, item.record.type, categoryTypesById)
                    val cleanRecord = item.record.copy(
                        fundingMethod = item.record.fundingMethod.trim(),
                        merchant = item.record.merchant.trim(),
                        categoryId = validCategoryId,
                        note = item.record.note.trim(),
                        externalOrderId = normalizeOrderId(item.record.externalOrderId),
                        updatedAtMillis = maxOf(item.record.updatedAtMillis, System.currentTimeMillis())
                    )

                    when (item.duplicateStatus) {
                        ImportDuplicateStatus.DUPLICATE_MERGE -> {
                            val existing = item.matchedExistingId?.let { store.getTransactionById(it) }
                            if (existing != null) {
                                val merged = dedupEngine.mergeReviewRecord(
                                    existing = existing,
                                    incoming = cleanRecord,
                                    userEdited = item.userEdited,
                                    categoryManuallyChanged = item.categoryManuallyChanged
                                )
                                val safeOrderId = merged.externalOrderId?.let { oid ->
                                    val owner = store.findTransactionByExternalOrderId(oid)
                                    if (owner != null && owner.id != existing.id) existing.externalOrderId else oid
                                }
                                val finalMerged = if (!item.userEdited && !item.categoryManuallyChanged) {
                                    applyCategoryUpgradeIfEnriched(
                                        existing = existing,
                                        merged = merged.copy(externalOrderId = safeOrderId),
                                        rules = rules,
                                        categoryTypesById = categoryTypesById
                                    )
                                } else {
                                    merged.copy(externalOrderId = safeOrderId)
                                }
                                store.updateTransaction(finalMerged)
                                mergedCount++
                                if (item.categoryManuallyChanged) {
                                    learnMerchantRuleIfEligible(
                                        merchant = finalMerged.merchant,
                                        txType = finalMerged.type,
                                        categoryId = finalMerged.categoryId
                                    )
                                }
                            } else {
                                val inserted = insertDraftSafely(cleanRecord, forceClearOrderId = false)
                                insertedCount++
                                if (item.categoryManuallyChanged) {
                                    learnMerchantRuleIfEligible(
                                        merchant = inserted.merchant,
                                        txType = inserted.type,
                                        categoryId = inserted.categoryId
                                    )
                                }
                            }
                        }
                        ImportDuplicateStatus.DUPLICATE_SKIP -> {
                            val inserted = insertDraftSafely(cleanRecord, forceClearOrderId = true)
                            insertedCount++
                            if (item.categoryManuallyChanged) {
                                learnMerchantRuleIfEligible(
                                    merchant = inserted.merchant,
                                    txType = inserted.type,
                                    categoryId = inserted.categoryId
                                )
                            }
                        }
                        ImportDuplicateStatus.NEW -> {
                            val inserted = insertDraftSafely(cleanRecord, forceClearOrderId = false)
                            insertedCount++
                            if (item.categoryManuallyChanged) {
                                learnMerchantRuleIfEligible(
                                    merchant = inserted.merchant,
                                    txType = inserted.type,
                                    categoryId = inserted.categoryId
                                )
                            }
                        }
                    }
                }

                ImportCommitSummary(
                    insertedCount = insertedCount,
                    mergedCount = mergedCount,
                    skippedCount = skippedCount
                )
            }
        }
    }

    private suspend fun buildImportGroupPlans(items: List<ImportReviewItem>): List<ImportGroupPlan> {
        val groups = LinkedHashMap<Long, ImportReconciliation>()
        for (item in items) {
            val reconciliation = item.reconciliation ?: continue
            val existing = groups[reconciliation.original.id]
            if (existing == null) {
                groups[reconciliation.original.id] = reconciliation
            } else if (existing.original != reconciliation.original ||
                existing.replacements != reconciliation.replacements
            ) {
                throw IllegalArgumentException("导入核对组不一致，请重新预览")
            }
        }

        val plans = mutableListOf<ImportGroupPlan>()
        for ((originalId, reconciliation) in groups) {
            val groupItems = items.filter { it.reconciliation?.original?.id == originalId }
            val expectedDraftIds = reconciliation.replacements.keys
            val actualDraftIds = groupItems.mapTo(LinkedHashSet()) { it.draftId }
            if (actualDraftIds != expectedDraftIds || groupItems.size != expectedDraftIds.size) {
                throw IllegalArgumentException("导入核对组不完整或包含多余项目，请重新预览")
            }

            val selectedCount = groupItems.count { it.selected }
            if (selectedCount == 0) continue
            if (selectedCount != groupItems.size) {
                throw IllegalArgumentException("导入核对组必须全部选中或全部取消，请重新预览")
            }

            for (item in groupItems) {
                val expected = reconciliation.replacements[item.draftId]
                    ?: throw IllegalArgumentException("导入核对组缺少项目，请重新预览")
                if (!hasSameReconciliationCore(item.record, expected)) {
                    throw IllegalArgumentException("导入核对组内容已被修改，请重新预览")
                }
            }

            val primaryItem = groupItems.singleOrNull {
                it.duplicateStatus == ImportDuplicateStatus.DUPLICATE_MERGE &&
                    it.matchedExistingId == originalId
            } ?: throw IllegalArgumentException("导入核对组缺少主替换项，请重新预览")

            val orderedItems = listOf(primaryItem) + expectedDraftIds
                .filter { it != primaryItem.draftId }
                .map { draftId -> groupItems.first { it.draftId == draftId } }

            val current = store.getTransactionById(originalId)
            val alreadyApplied = current != null &&
                current != reconciliation.original &&
                isImportGroupAlreadyApplied(current, reconciliation, primaryItem.draftId)
            if (!alreadyApplied) {
                if (current == null || current != reconciliation.original) {
                    throw IllegalArgumentException("原账单已被修改或删除，请重新预览")
                }
                val primaryOrderId = normalizeOrderId(
                    reconciliation.replacements.getValue(primaryItem.draftId).externalOrderId
                )
                if (primaryOrderId != null) {
                    val owner = store.findTransactionByExternalOrderId(primaryOrderId)
                    if (owner != null && owner.id != originalId) {
                        throw IllegalArgumentException("主替换项单号冲突，请重新预览")
                    }
                }
                for (draftId in expectedDraftIds) {
                    if (draftId == primaryItem.draftId) continue
                    val orderId = normalizeOrderId(
                        reconciliation.replacements.getValue(draftId).externalOrderId
                    )
                    if (orderId != null && store.findTransactionByExternalOrderId(orderId) != null) {
                        throw IllegalArgumentException("替换项目单号冲突，请重新预览")
                    }
                }
            }
            plans += ImportGroupPlan(
                reconciliation = reconciliation,
                primaryItem = primaryItem,
                orderedItems = orderedItems,
                alreadyApplied = alreadyApplied
            )
        }
        return plans
    }

    private fun hasSameReconciliationCore(
        actual: TransactionRecord,
        expected: TransactionRecord
    ): Boolean =
        actual.amountCents == expected.amountCents &&
            actual.type == expected.type &&
            actual.channel == expected.channel &&
            actual.timestampMillis == expected.timestampMillis &&
            actual.localDate == expected.localDate &&
            actual.hourOfDay == expected.hourOfDay &&
            actual.fundingMethod.trim() == expected.fundingMethod.trim() &&
            normalizeOrderId(actual.externalOrderId) == normalizeOrderId(expected.externalOrderId)

    private suspend fun isImportGroupAlreadyApplied(
        current: TransactionRecord,
        reconciliation: ImportReconciliation,
        primaryDraftId: String
    ): Boolean {
        val primaryExpected = reconciliation.replacements[primaryDraftId] ?: return false
        if (!hasSameReconciliationCore(current, primaryExpected)) return false

        for ((draftId, expected) in reconciliation.replacements) {
            if (draftId == primaryDraftId) continue
            val orderId = normalizeOrderId(expected.externalOrderId) ?: return false
            val existing = store.findTransactionByExternalOrderId(orderId) ?: return false
            if (!hasSameReconciliationCore(existing, expected)) return false
        }
        return true
    }

    private suspend fun insertDraftSafely(
        record: TransactionRecord,
        forceClearOrderId: Boolean
    ): TransactionRecord {
        val candidateOrderId = if (forceClearOrderId) {
            null
        } else {
            val oid = normalizeOrderId(record.externalOrderId)
            if (oid != null && store.findTransactionByExternalOrderId(oid) != null) null else oid
        }
        val toInsert = record.copy(id = 0L, externalOrderId = candidateOrderId)
        store.insertTransaction(toInsert)
        return toInsert
    }

    private fun isSameIntraBatchPayment(first: ParsedPayment, second: ParsedPayment): Boolean {
        if (first.type != second.type || first.amountCents != second.amountCents) return false
        val firstOrder = normalizeOrderId(first.externalOrderId)
        val secondOrder = normalizeOrderId(second.externalOrderId)
        if (firstOrder != null && secondOrder != null) {
            return firstOrder == secondOrder
        }
        if (abs(first.timestampMillis - second.timestampMillis) > INTRA_BATCH_WINDOW_MS) return false
        val m1 = first.merchant.trim().lowercase(Locale.ROOT)
        val m2 = second.merchant.trim().lowercase(Locale.ROOT)
        return m1 == m2 || m1.contains(m2) || m2.contains(m1)
    }

    suspend fun saveCustomCategory(category: Category) {
        ensureSeeded()
        writeMutex.withLock {
            val resolvedId = category.id.trim().ifEmpty {
                "custom_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            }
            val existing = store.getCategoryById(resolvedId)
            val iconKey = category.iconKey.trim()
            require(iconKey.isNotEmpty()) { "请选择分类图标" }
            val categories = store.getAllCategories().filter { it.id != resolvedId && !it.isArchived }
            require(categories.none { it.iconKey.trim() == iconKey }) {
                "这个图标已被其他分类使用，请选择另一个"
            }
            val name = category.name.trim()
            require(name.isNotEmpty()) { "请输入分类名称" }
            require(categories.none { it.categoryType == category.categoryType && it.name.equals(name, ignoreCase = true) }) {
                "已存在同名分类「$name」"
            }

            val preserveSystem = existing?.isSystem == true ||
                resolvedId == DefaultCategories.EXPENSE_OTHER_ID ||
                resolvedId == DefaultCategories.INCOME_OTHER_ID
            val resolvedSortOrder = if (category.sortOrder > 0) {
                category.sortOrder
            } else {
                (store.getCategoriesByType(category.categoryType).maxOfOrNull { it.sortOrder } ?: 0) + 1
            }
            store.upsertCategory(
                category.copy(
                    id = resolvedId,
                    name = name,
                    iconKey = iconKey,
                    sortOrder = resolvedSortOrder,
                    isSystem = if (preserveSystem) true else category.isSystem
                )
            )
        }
    }

    private suspend fun activeMerchantRules(): List<MerchantRule> {
        val archived = store.getAllCategories().filter { it.isArchived }.mapTo(HashSet()) { it.id }
        return store.getAllMerchantRules().filterNot { it.categoryId in archived }
    }

    suspend fun archiveCategory(categoryId: String): Boolean {
        ensureSeeded()
        return writeMutex.withLock {
            val category = store.getCategoryById(categoryId) ?: return@withLock false
            if (category.isArchived) return@withLock false
            store.upsertCategory(category.copy(isArchived = true))
            true
        }
    }

    suspend fun restoreDefaultCategories(availableIconKeys: List<String>) {
        ensureSeeded()
        writeMutex.withLock {
            store.runInTransaction {
                val categories = store.getAllCategories()
                val active = categories.filterNot { it.isArchived }
                val usedIcons = active.mapTo(HashSet()) { it.iconKey.trim() }
                val activeIds = active.mapTo(HashSet()) { it.id }
                val byId = categories.associateBy { it.id }
                val restorable = DefaultCategories.all.filterNot { it.id in activeIds }
                    .map { byId[it.id] ?: it }
                // Reserve each available historical icon before assigning substitutes.
                val reserved = restorable.associate { category ->
                    category.id to category.iconKey.trim().takeIf { it.isNotEmpty() && usedIcons.add(it) }
                }
                val restored = restorable.map { category ->
                    val icon = reserved[category.id] ?: availableIconKeys.firstOrNull { it !in usedIcons }
                        ?.also(usedIcons::add)
                    requireNotNull(icon) { "可用图标不足，请先删除不再使用的分类" }
                    category.copy(isArchived = false, iconKey = icon)
                }
                restored.forEach { store.upsertCategory(it) }
            }
        }
    }

    suspend fun deleteCustomCategory(categoryId: String): Boolean {
        ensureSeeded()
        return writeMutex.withLock {
            val target = store.getCategoryById(categoryId) ?: return@withLock false
            if (target.isSystem ||
                target.id == DefaultCategories.EXPENSE_OTHER_ID ||
                target.id == DefaultCategories.INCOME_OTHER_ID
            ) {
                return@withLock false
            }
            val fallbackId = when (target.categoryType) {
                CategoryType.EXPENSE -> DefaultCategories.EXPENSE_OTHER_ID
                CategoryType.INCOME -> DefaultCategories.INCOME_OTHER_ID
            }
            store.deleteCategoryAndReassign(
                categoryId = categoryId,
                fallbackCategoryId = fallbackId,
                updatedAtMillis = System.currentTimeMillis()
            )
            true
        }
    }

    suspend fun exportBackupJson(): String {
        ensureSeeded()
        val snapshot = LocalBackupSnapshot(
            schemaVersion = BACKUP_SCHEMA_VERSION,
            exportedAtMillis = System.currentTimeMillis(),
            categories = store.getAllCategories(),
            merchantRules = store.getAllMerchantRules(),
            transactions = store.getAllTransactions()
        )
        return LocalBackupCodec.encodeJson(snapshot)
    }

    suspend fun restoreBackupJson(json: String): Int {
        val snapshot = LocalBackupCodec.decodeJson(json)
        require(snapshot.schemaVersion == BACKUP_SCHEMA_VERSION) {
            "Unsupported backup schemaVersion: ${snapshot.schemaVersion}"
        }
        return writeMutex.withLock {
            val restoredCategories = ensureSystemFallbacksPresent(snapshot.categories)
            val categoryTypesById = restoredCategories.associate { it.id to it.categoryType }
            val normalizedTransactions = snapshot.transactions.map { tx ->
                val validCat = if (tx.categoryId.isEmpty()) "" else resolveReviewCategoryId(tx.categoryId, tx.type, categoryTypesById)
                tx.copy(
                    categoryId = validCat,
                    externalOrderId = normalizeOrderId(tx.externalOrderId)
                )
            }
            val sanitizedRules = snapshot.merchantRules.map { rule ->
                val fallbackId = if (rule.categoryType == CategoryType.INCOME) {
                    DefaultCategories.INCOME_OTHER_ID
                } else {
                    DefaultCategories.EXPENSE_OTHER_ID
                }
                val validCat = if (categoryTypesById[rule.categoryId] == rule.categoryType) {
                    rule.categoryId
                } else {
                    fallbackId
                }
                rule.copy(categoryId = validCat)
            }
            dedupEngine.clearDebounceCache()
            store.replaceAll(
                categories = restoredCategories,
                merchantRules = sanitizedRules,
                transactions = normalizedTransactions
            )
            seeded = true
            normalizedTransactions.size
        }
    }

    suspend fun recordedCustomChannels(): List<String> =
        store.getAllTransactions().mapNotNull { it.customChannel }.distinct()

    suspend fun exportCsv(): String {
        ensureSeeded()
        val categoryNames = store.getAllCategories().associate { it.id to it.name }
        val transactions = store.getAllTransactions()
        return LocalBackupCodec.exportTransactionsCsv(
            transactions = transactions,
            categoryNames = categoryNames,
            zoneId = zoneId
        )
    }

    suspend fun recordDebugLog(
        source: String,
        packageName: String,
        matched: Boolean,
        summary: String
    ) {
        store.insertDebugLogAndTrim(
            log = CaptureDebugLog(
                id = 0L,
                timestampMillis = System.currentTimeMillis(),
                source = source,
                packageName = packageName,
                matched = matched,
                summary = summary
            ),
            keepCount = MAX_DEBUG_LOGS
        )
    }

    suspend fun clearDebugLogs() {
        store.clearDebugLogs()
    }

    private suspend fun applyCategoryUpgradeIfEnriched(
        existing: TransactionRecord?,
        merged: TransactionRecord,
        rules: List<MerchantRule>,
        categoryTypesById: Map<String, CategoryType>? = null
    ): TransactionRecord {
        var result = merged.copy(externalOrderId = normalizeOrderId(merged.externalOrderId))
        if (existing != null &&
            (result.merchant != existing.merchant || result.note != existing.note) &&
            isFallbackCategory(existing.categoryId)
        ) {
            val matchedCategoryId = CategoryMatcher.matchCategoryId(
                merchant = result.merchant,
                note = result.note,
                txType = result.type,
                rules = rules
            )
            val resolved = if (categoryTypesById != null) {
                resolveReviewCategoryId(matchedCategoryId, result.type, categoryTypesById)
            } else {
                resolveExistingCategoryId(matchedCategoryId, result.type)
            }
            if (resolved != result.categoryId) {
                result = result.copy(categoryId = resolved)
            }
        }
        return result
    }

    private suspend fun learnMerchantRuleIfEligible(
        merchant: String,
        txType: TransactionType,
        categoryId: String
    ) {
        val trimmed = merchant.trim()
        if (trimmed.isEmpty() || trimmed in nonLearnableGenericMerchants ||
            categoryId == DefaultCategories.REFUND_CATEGORY_ID) return
        store.upsertExactUserRule(
            pattern = trimmed,
            categoryType = CategoryMatcher.categoryTypeFor(txType),
            categoryId = categoryId,
            priority = USER_RULE_PRIORITY
        )
    }

    private suspend fun resolveExistingCategoryId(
        candidateId: String,
        txType: TransactionType
    ): String {
        val trimmed = candidateId.trim()
        if (trimmed.isNotEmpty()) {
            val category = store.getCategoryById(trimmed)
            if (category != null && !category.isArchived && acceptsCategory(category.id, category.categoryType, txType)) {
                return trimmed
            }
        }
        val fallback = fallbackCategoryFor(txType)
        return if (store.getCategoryById(fallback)?.isArchived == true) "" else fallback
    }

    private fun acceptsCategory(id: String, categoryType: CategoryType, txType: TransactionType): Boolean =
        categoryType == CategoryMatcher.categoryTypeFor(txType) ||
            (txType == TransactionType.REFUND && id == DefaultCategories.REFUND_CATEGORY_ID &&
                categoryType == CategoryType.INCOME)

    private fun ensureSystemFallbacksPresent(categories: List<Category>): List<Category> {
        if (categories.isEmpty()) return DefaultCategories.all
        val existingIds = categories.mapTo(HashSet()) { it.id }
        val extras = listOf(
            DefaultCategories.EXPENSE_OTHER_ID,
            DefaultCategories.INCOME_OTHER_ID,
            DefaultCategories.REFUND_CATEGORY_ID
        ).mapNotNull { fallbackId ->
            if (fallbackId !in existingIds) {
                DefaultCategories.all.firstOrNull { it.id == fallbackId }
            } else {
                null
            }
        }
        return categories + extras
    }

    private fun isFallbackCategory(categoryId: String): Boolean =
        categoryId == DefaultCategories.EXPENSE_OTHER_ID ||
            categoryId == DefaultCategories.INCOME_OTHER_ID

    private fun fallbackCategoryFor(txType: TransactionType): String =
        when (CategoryMatcher.categoryTypeFor(txType)) {
            CategoryType.INCOME -> DefaultCategories.INCOME_OTHER_ID
            CategoryType.EXPENSE -> DefaultCategories.EXPENSE_OTHER_ID
        }

    private fun normalizeOrderId(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        const val NEARBY_DEDUP_WINDOW_MS = 180_000L
        const val REVIEW_DEDUP_WINDOW_MS = 15 * 60 * 1000L
        const val INTRA_BATCH_WINDOW_MS = 120_000L
        const val USER_RULE_PRIORITY = 1000
        const val BACKUP_SCHEMA_VERSION = 1
        const val MAX_DEBUG_LOGS = 50

        val walletChannels = setOf(
            PaymentChannel.WECHAT,
            PaymentChannel.ALIPAY,
            PaymentChannel.UNIONPAY
        )
        val nonLearnableGenericMerchants: Set<String> = buildSet {
            add("微信")
            add("微信支付")
            add("微信支付凭证")
            add("微信收款")
            add("财付通")
            add("财付通支付")
            add("财付通支付科技有限公司")
            add("深圳市财付通支付科技有限公司")
            add("支付宝")
            add("支付宝通知")
            add("支付宝支付")
            add("支付宝支付科技有限公司")
            add("支付宝(中国)网络技术有限公司")
            add("支付宝（中国）网络技术有限公司")
            add("云闪付")
            add("云闪付支付")
            add("银联")
            add("银联支付")
            add("网联")
            add("网联清算")
            add("网联清算有限公司")
            add("快捷支付")
            add("银行流水")
            add("银行卡")
            add("未知商户")
            add("未知")
            add("其他支付")
            add("手动记账")
            addAll(PaymentPackages.defaultBankPackages.values)
            addAll(BankPdfParser.knownBankNames)
        }
    }
}

internal fun findMissingBuiltinRules(
    existingRules: List<MerchantRule>,
    defaultRules: List<MerchantRule>
): List<MerchantRule> {
    val existingBuiltinKeys = existingRules
        .asSequence()
        .filter { it.matchType == RuleMatchType.KEYWORD_BUILTIN }
        .map { it.pattern.trim().lowercase(Locale.ROOT) to it.categoryType }
        .toSet()
    return defaultRules.filter { rule ->
        rule.matchType == RuleMatchType.KEYWORD_BUILTIN &&
            (rule.pattern.trim().lowercase(Locale.ROOT) to rule.categoryType) !in existingBuiltinKeys
    }
}
