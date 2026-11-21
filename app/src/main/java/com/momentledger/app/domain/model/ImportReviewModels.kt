package com.momentledger.app.domain.model

enum class ImportDuplicateStatus {
    NEW,
    DUPLICATE_MERGE,
    DUPLICATE_SKIP
}

/**
 * Non-persisted description of an atomic import replacement group.
 *
 * [original] is the stored row that will be replaced in place (its id is kept), while
 * [replacements] maps every expected review draft id to the record that must be written.
 * The map insertion order is meaningful: the first entry is the primary replacement.
 */
data class ImportReconciliation(
    val original: TransactionRecord,
    val replacements: Map<String, TransactionRecord>
)

data class ImportReviewItem(
    val draftId: String,
    val record: TransactionRecord,
    val selected: Boolean,
    val duplicateStatus: ImportDuplicateStatus,
    val matchedExistingId: Long? = null,
    val duplicateHint: String = "",
    val needsVerification: Boolean = false,
    val userEdited: Boolean = false,
    val categoryManuallyChanged: Boolean = false,
    val reconciliation: ImportReconciliation? = null
)

data class ImportReviewBatch(
    val sourceTitle: String,
    val items: List<ImportReviewItem>,
    val skippedCount: Int = 0,
    val rawOcrText: String = "",
    val imageUris: List<String> = emptyList()
)

data class ImportCommitSummary(
    val insertedCount: Int,
    val mergedCount: Int,
    val skippedCount: Int
)
