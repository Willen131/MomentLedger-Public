package com.momentledger.app.domain.engine

import com.momentledger.app.domain.model.CaptureSource
import com.momentledger.app.domain.model.HOUR_OF_DAY_UNKNOWN
import com.momentledger.app.domain.model.ImportDuplicateStatus
import com.momentledger.app.domain.model.ParsedPayment
import com.momentledger.app.domain.model.PaymentChannel
import com.momentledger.app.domain.model.TransactionRecord
import com.momentledger.app.domain.model.TransactionType
import com.momentledger.app.domain.parser.BankPdfParser
import com.momentledger.app.domain.parser.PaymentPackages
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

data class ReviewDedupMatch(
    val status: ImportDuplicateStatus,
    val matchedExistingId: Long? = null,
    val hint: String = "",
    val autoSelect: Boolean = false,
    val matchedExistingIds: List<Long> = emptyList(),
    val needsVerification: Boolean = false
)

/**
 * Decision produced by [DeduplicationEngine] for an incoming real-time or imported payment.
 */
sealed interface DedupDecision {
    /** Suppresses a duplicate real-time debounce event or already-imported official order. */
    data object IgnoreAsDebounce : DedupDecision

    /** Merges incoming details into an existing persisted [TransactionRecord]. */
    data class MergeIntoExisting(
        val existingId: Long,
        val updatedRecord: TransactionRecord
    ) : DedupDecision

    /** No matching transaction exists; insert as a new record. */
    data object InsertAsNew : DedupDecision
}

/**
 * Stateless-per-query plus bounded debounce engine handling:
 * 1. Real-time same-source debounce within [debounceWindowMs].
 * 2. Same-channel Accessibility <-> Notification merging within [sameChannelWindowMs].
 * 3. Cross-channel BANK <-> Wallet merging within [crossChannelWindowMs], including across midnight.
 * 4. Official bill import reconciliation via exact `externalOrderId` or [importWindowMs] fallback.
 */
class DeduplicationEngine(
    private val sameChannelWindowMs: Long = 20_000L,
    private val crossChannelWindowMs: Long = 180_000L,
    private val importWindowMs: Long = 180_000L,
    private val debounceWindowMs: Long = 10_000L,
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {

    private data class DebounceKey(
        val source: CaptureSource,
        val channel: PaymentChannel,
        val type: TransactionType,
        val amountCents: Long,
        val merchant: String,
        val fundingMethod: String,
        val externalOrderId: String
    )

    private val debounceLock = Any()
    private val debounceCache = LinkedHashMap<DebounceKey, Long>()

    /**
     * Evaluates a real-time payment event from Accessibility or Notification capture.
     */
    fun evaluateRealTime(
        incoming: ParsedPayment,
        nearbyCandidates: List<TransactionRecord>,
        nowMillis: Long = incoming.timestampMillis
    ): DedupDecision {
        if (shouldSuppressAsDebounce(incoming, nowMillis)) {
            return DedupDecision.IgnoreAsDebounce
        }

        val sameChannelMatch = nearbyCandidates
            .asSequence()
            .filter { candidate ->
                candidate.channel == incoming.channel &&
                    candidate.type == incoming.type &&
                    candidate.amountCents == incoming.amountCents &&
                    abs(candidate.timestampMillis - incoming.timestampMillis) <= sameChannelWindowMs &&
                    isOrderCompatible(candidate.externalOrderId, incoming.externalOrderId)
            }
            .minWithOrNull(
                compareBy<TransactionRecord> { abs(it.timestampMillis - incoming.timestampMillis) }
                    .thenBy { it.id }
            )

        if (sameChannelMatch != null) {
            return DedupDecision.MergeIntoExisting(
                existingId = sameChannelMatch.id,
                updatedRecord = mergeRealTimeRecord(sameChannelMatch, incoming, nowMillis)
            )
        }

        val crossChannelMatch = nearbyCandidates
            .asSequence()
            .filter { candidate ->
                (isBankWalletPair(candidate.channel, incoming.channel) ||
                    isBankResolvedWalletCrossCapture(candidate, incoming)) &&
                    candidate.type == incoming.type &&
                    candidate.amountCents == incoming.amountCents &&
                    abs(candidate.timestampMillis - incoming.timestampMillis) <= crossChannelWindowMs &&
                    isOrderCompatible(candidate.externalOrderId, incoming.externalOrderId) &&
                    !hasExplicitFundingBankConflict(candidate, incoming)
            }
            .minWithOrNull(
                compareBy<TransactionRecord> { if (it.channel == incoming.channel) 0 else 1 }
                    .thenBy { abs(it.timestampMillis - incoming.timestampMillis) }
                    .thenBy { it.id }
            )

        if (crossChannelMatch != null) {
            return DedupDecision.MergeIntoExisting(
                existingId = crossChannelMatch.id,
                updatedRecord = mergeRealTimeRecord(crossChannelMatch, incoming, nowMillis)
            )
        }

        return DedupDecision.InsertAsNew
    }

    /**
     * Evaluates a parsed row from an official WeChat or Alipay bill file against existing records.
     */
    fun evaluateImport(
        incoming: ParsedPayment,
        exactOrderMatch: TransactionRecord?,
        nearbyCandidates: List<TransactionRecord>
    ): DedupDecision {
        if (exactOrderMatch != null) {
            return DedupDecision.IgnoreAsDebounce
        }

        val incomingOrderId = incoming.externalOrderId?.trim()?.takeIf { it.isNotEmpty() }
        if (incomingOrderId != null &&
            nearbyCandidates.any { it.externalOrderId?.trim() == incomingOrderId }
        ) {
            return DedupDecision.IgnoreAsDebounce
        }

        val match = nearbyCandidates
            .asSequence()
            .filter { candidate ->
                candidate.type == incoming.type &&
                    candidate.amountCents == incoming.amountCents &&
                    (candidate.channel == incoming.channel ||
                        candidate.channel == PaymentChannel.BANK ||
                        incoming.channel == PaymentChannel.BANK) &&
                    abs(candidate.timestampMillis - incoming.timestampMillis) <= importWindowMs &&
                    isOrderCompatible(candidate.externalOrderId, incomingOrderId) &&
                    !hasExplicitFundingBankConflict(candidate, incoming)
            }
            .minWithOrNull(
                compareBy<TransactionRecord> { if (it.channel == incoming.channel) 0 else 1 }
                    .thenBy { abs(it.timestampMillis - incoming.timestampMillis) }
                    .thenBy { if (it.externalOrderId.isNullOrBlank()) 0 else 1 }
                    .thenBy { it.id }
            )

        if (match != null) {
            return DedupDecision.MergeIntoExisting(
                existingId = match.id,
                updatedRecord = mergeImportRecord(match, incoming)
            )
        }

        return DedupDecision.InsertAsNew
    }

    /**
     * Evaluates an incoming bill-file or OCR candidate for the unified import review screen.
     * Supports cross-source matching (e.g. MANUAL/OTHER screenshots <-> WECHAT/ALIPAY/BANK bills)
     * and distinguishes enriching merges ([ImportDuplicateStatus.DUPLICATE_MERGE]) from pure duplicates.
     */
    fun evaluateReviewCandidate(
        incoming: ParsedPayment,
        exactOrderMatch: TransactionRecord?,
        nearbyCandidates: List<TransactionRecord>
    ): ReviewDedupMatch {
        val incomingOrderId = incoming.externalOrderId?.trim()?.takeIf { it.isNotEmpty() }
        val orderMatch = exactOrderMatch
            ?: incomingOrderId?.let { oid -> nearbyCandidates.firstOrNull { it.externalOrderId?.trim() == oid } }
        if (orderMatch != null) {
            if (orderMatch.type != incoming.type) {
                val isRefundBugRepair = orderMatch.type == TransactionType.REFUND &&
                    incoming.type == TransactionType.EXPENSE
                return ReviewDedupMatch(
                    status = ImportDuplicateStatus.DUPLICATE_MERGE,
                    matchedExistingId = orderMatch.id,
                    hint = "可修正交易类型（${typeDisplayName(orderMatch.type)} → ${typeDisplayName(incoming.type)}）",
                    autoSelect = isRefundBugRepair
                )
            }
            return if (hasEnrichingInfo(orderMatch, incoming)) {
                ReviewDedupMatch(
                    status = ImportDuplicateStatus.DUPLICATE_MERGE,
                    matchedExistingId = orderMatch.id,
                    hint = "订单号已存在，可合并补充信息"
                )
            } else {
                ReviewDedupMatch(
                    status = ImportDuplicateStatus.DUPLICATE_SKIP,
                    matchedExistingId = orderMatch.id,
                    hint = "库内已有同单号账单"
                )
            }
        }

        val match = nearbyCandidates
            .asSequence()
            .filter { candidate ->
                candidate.type == incoming.type &&
                    candidate.amountCents == incoming.amountCents &&
                    isOrderCompatible(candidate.externalOrderId, incomingOrderId) &&
                    !hasExplicitFundingBankConflict(candidate, incoming) &&
                    matchesReviewWindow(candidate, incoming)
            }
            .minWithOrNull(
                compareBy<TransactionRecord> { if (it.channel == incoming.channel) 0 else 1 }
                    .thenBy { abs(it.timestampMillis - incoming.timestampMillis) }
                    .thenBy { it.id }
            )

        if (match != null) {
            return if (hasEnrichingInfo(match, incoming)) {
                ReviewDedupMatch(
                    status = ImportDuplicateStatus.DUPLICATE_MERGE,
                    matchedExistingId = match.id,
                    hint = "可合并补充库内已有账单"
                )
            } else {
                ReviewDedupMatch(
                    status = ImportDuplicateStatus.DUPLICATE_SKIP,
                    matchedExistingId = match.id,
                    hint = "疑似与库内已有账单重复"
                )
            }
        }

        val combinedMatches = findCombinedWalletPaymentMatches(incoming, nearbyCandidates)
        combinedMatches.singleOrNull()?.let { subset ->
            return ReviewDedupMatch(
                status = ImportDuplicateStatus.DUPLICATE_SKIP,
                matchedExistingId = null,
                hint = "已拆分为库内 ${subset.size} 笔合并支付订单",
                autoSelect = false,
                matchedExistingIds = subset.map { it.id }
            )
        }

        if (combinedMatches.size > 1) {
            return ReviewDedupMatch(
                status = ImportDuplicateStatus.NEW,
                hint = "存在多组合并付款候选，请核对后选择",
                needsVerification = true
            )
        }

        findWithdrawalFeeTransferMatch(incoming, nearbyCandidates)?.let { feeMatch ->
            return ReviewDedupMatch(
                status = ImportDuplicateStatus.DUPLICATE_SKIP,
                matchedExistingId = feeMatch.id,
                hint = "与钱包提现记录重复（含0.1%手续费差额）",
                autoSelect = false
            )
        }

        return ReviewDedupMatch(status = ImportDuplicateStatus.NEW)
    }

    /**
     * Detects when a single bank-card debit (e.g., ICBC quick-pay) corresponds to 2..4 split
     * wallet orders paid in one checkout batch (such as Taobao/Xianyu item + inspection fee)
     * using the same funding bank within [importWindowMs].
     */
    fun findCombinedWalletPaymentMatches(
        incoming: ParsedPayment,
        nearbyCandidates: List<TransactionRecord>
    ): List<List<TransactionRecord>> {
        if (incoming.channel != PaymentChannel.BANK || !incoming.hasTimeOfDay) return emptyList()
        if (incoming.type != TransactionType.EXPENSE && incoming.type != TransactionType.REFUND) return emptyList()

        val eligible = nearbyCandidates
            .asSequence()
            .filter { c ->
                c.channel in walletChannels &&
                    c.type == incoming.type &&
                    c.hourOfDay in 0..23 &&
                    abs(c.timestampMillis - incoming.timestampMillis) <= importWindowMs &&
                    !hasExplicitFundingBankConflict(c, incoming) &&
                    isFundingBankAligned(c, incoming) &&
                    c.amountCents in 1 until incoming.amountCents
            }
            .sortedBy { abs(it.timestampMillis - incoming.timestampMillis) }
            .take(MAX_COMBINED_CANDIDATES)
            .toList()
        if (eligible.size < 2) return emptyList()

        return (2..minOf(MAX_COMBINED_SUBSET_SIZE, eligible.size)).flatMap { size ->
            findSubsetSumClusters(eligible, size, incoming.amountCents)
        }
    }

    private fun findSubsetSumClusters(
        items: List<TransactionRecord>,
        targetSize: Int,
        targetSum: Long
    ): List<List<TransactionRecord>> {
        val picked = ArrayList<TransactionRecord>(targetSize)
        val matches = mutableListOf<List<TransactionRecord>>()

        fun search(start: Int, remaining: Long, minTs: Long, maxTs: Long) {
            if (picked.size == targetSize) {
                if (remaining == 0L) matches += picked.toList()
                return
            }
            if (items.size - start < targetSize - picked.size) return
            for (i in start until items.size) {
                val candidate = items[i]
                if (candidate.amountCents > remaining) continue
                val nextMin = if (picked.isEmpty()) candidate.timestampMillis else minOf(minTs, candidate.timestampMillis)
                val nextMax = if (picked.isEmpty()) candidate.timestampMillis else maxOf(maxTs, candidate.timestampMillis)
                if (nextMax - nextMin > COMBINED_PAYMENT_SPREAD_MS) continue
                picked.add(candidate)
                search(i + 1, remaining - candidate.amountCents, nextMin, nextMax)
                picked.removeAt(picked.lastIndex)
            }
        }
        search(0, targetSum, Long.MAX_VALUE, Long.MIN_VALUE)
        return matches
    }

    /**
     * Detects wallet-to-bank withdrawal transfer pairs where the wallet records the gross amount
     * including the standard 0.1% withdrawal fee (min ¥0.10) while the bank records the net credit.
     */
    fun findWithdrawalFeeTransferMatch(
        incoming: ParsedPayment,
        nearbyCandidates: List<TransactionRecord>
    ): TransactionRecord? {
        if (incoming.type != TransactionType.TRANSFER) return null
        val incomingIsBank = incoming.channel == PaymentChannel.BANK
        val incomingIsWallet = incoming.channel in walletChannels
        if (!incomingIsBank && !incomingIsWallet) return null

        return nearbyCandidates
            .asSequence()
            .filter { candidate ->
                if (candidate.type != TransactionType.TRANSFER) return@filter false
                val oppositeSide = if (incomingIsBank) {
                    candidate.channel in walletChannels
                } else {
                    candidate.channel == PaymentChannel.BANK
                }
                if (!oppositeSide) return@filter false
                if (hasExplicitFundingBankConflict(candidate, incoming)) return@filter false
                if (!isFundingBankAligned(candidate, incoming)) return@filter false
                if (!matchesReviewWindow(candidate, incoming)) return@filter false

                val bankCents = if (incomingIsBank) incoming.amountCents else candidate.amountCents
                val walletCents = if (incomingIsBank) candidate.amountCents else incoming.amountCents
                if (bankCents < 100L || walletCents <= bankCents) return@filter false

                val bankMerchant = if (incomingIsBank) incoming.merchant else candidate.merchant
                val bankNote = if (incomingIsBank) incoming.note else candidate.note
                val bankFunding = if (incomingIsBank) incoming.fundingMethod else candidate.fundingMethod
                if (hasExplicitOutboundBankConflict(bankMerchant, bankNote, bankFunding)) {
                    return@filter false
                }
                val hasConfirmedInbound = hasConfirmedInboundBankEvidence(
                    merchant = bankMerchant,
                    note = bankNote,
                    fundingMethod = bankFunding
                )
                // Date-only and legacy records still require affirmative inbound evidence.
                if (!hasConfirmedInbound) return@filter false

                val walletMerchant = if (incomingIsBank) candidate.merchant else incoming.merchant
                val walletNote = if (incomingIsBank) candidate.note else incoming.note
                val walletFunding = if (incomingIsBank) candidate.fundingMethod else incoming.fundingMethod
                if (!isWalletWithdrawalEvidence(walletMerchant, walletNote, walletFunding)) {
                    return@filter false
                }

                val diff = walletCents - bankCents
                val expectedFee = maxOf(10L, (bankCents + 500L) / 1000L)
                val tolerance = maxOf(2L, expectedFee / 20L)
                abs(diff - expectedFee) <= tolerance
            }
            .minWithOrNull(
                compareBy<TransactionRecord> { abs(it.timestampMillis - incoming.timestampMillis) }
                    .thenBy { it.id }
            )
    }

    private fun isWalletWithdrawalEvidence(
        merchant: String,
        note: String,
        fundingMethod: String
    ): Boolean {
        val text = "$merchant $note $fundingMethod"
        if (text.contains("充值") || text.contains("还款")) return false
        if (text.contains("提现")) return true
        // Older WeChat rows may identify withdrawals through a bank name and service fee.
        val bankNamedMerchant = banksMentioned(merchant).isNotEmpty()
        val serviceFeeNote = note.contains("服务费") &&
            (note.contains("¥") || note.contains("￥"))
        return bankNamedMerchant && serviceFeeNote
    }

    private fun hasConfirmedInboundBankEvidence(
        merchant: String,
        note: String,
        fundingMethod: String
    ): Boolean {
        val text = "$note $merchant $fundingMethod"
        if (directionInboundRegex.containsMatchIn(text)) return true
        return text.contains("网联收款") ||
            text.contains("银联入账") ||
            text.contains("提现入账") ||
            text.contains("零钱提现") ||
            text.contains("提现到账")
    }

    private fun hasExplicitOutboundBankConflict(
        merchant: String,
        note: String,
        fundingMethod: String
    ): Boolean {
        val text = "$note $merchant $fundingMethod"
        if (directionOutboundRegex.containsMatchIn(text)) return true
        if (text.contains("还款")) return true
        if (text.contains("充值支出")) return true
        val hasOutboundWord = text.contains("转出") && (note.contains("方向") || note.contains("资金"))
        return hasOutboundWord
    }

    fun mergeReviewRecord(
        existing: TransactionRecord,
        incoming: TransactionRecord,
        userEdited: Boolean,
        categoryManuallyChanged: Boolean = false
    ): TransactionRecord {
        if (userEdited) {
            val resolvedOrderId = incoming.externalOrderId?.trim()?.takeIf { it.isNotEmpty() }
                ?: existing.externalOrderId
            return existing.copy(
                timestampMillis = incoming.timestampMillis,
                localDate = incoming.localDate,
                hourOfDay = incoming.hourOfDay,
                amountCents = incoming.amountCents,
                type = incoming.type,
                channel = if (incoming.channel != PaymentChannel.OTHER || existing.channel == PaymentChannel.MANUAL) {
                    incoming.channel
                } else {
                    existing.channel
                },
                customChannel = incoming.customChannel,
                fundingMethod = incoming.fundingMethod.trim().ifBlank { existing.fundingMethod },
                merchant = incoming.merchant.trim().ifBlank { existing.merchant },
                categoryId = incoming.categoryId,
                note = incoming.note.trim().ifBlank { existing.note },
                excludedFromStats = incoming.excludedFromStats,
                externalOrderId = resolvedOrderId,
                updatedAtMillis = maxOf(existing.updatedAtMillis, System.currentTimeMillis())
            )
        }

        val mergedMerchant = chooseRicherMerchant(
            existingMerchant = existing.merchant,
            existingFunding = existing.fundingMethod,
            incomingMerchant = incoming.merchant,
            incomingFunding = incoming.fundingMethod,
            preferIncomingOnTie = incoming.merchant.trim().length > existing.merchant.trim().length
        )
        val mergedFunding = when {
            incoming.fundingMethod.isBlank() -> existing.fundingMethod
            !isGenericFunding(incoming.fundingMethod) -> incoming.fundingMethod.trim()
            isGenericFunding(existing.fundingMethod) -> incoming.fundingMethod.trim()
            else -> existing.fundingMethod
        }
        val mergedOrderId = existing.externalOrderId?.takeIf { it.isNotBlank() }
            ?: incoming.externalOrderId?.trim()?.takeIf { it.isNotEmpty() }
        val mergedChannel = when {
            existing.channel == PaymentChannel.MANUAL && incoming.channel != PaymentChannel.MANUAL -> incoming.channel
            existing.channel == PaymentChannel.BANK && incoming.channel in walletChannels -> incoming.channel
            else -> existing.channel
        }
        val useIncomingTime = existing.hourOfDay == HOUR_OF_DAY_UNKNOWN && incoming.hourOfDay in 0..23
        val typeCorrected = existing.type != incoming.type &&
            !existing.externalOrderId.isNullOrBlank() &&
            existing.externalOrderId.trim() == incoming.externalOrderId?.trim()
        val resolvedType = if (typeCorrected) incoming.type else existing.type
        val resolvedCategoryId = when {
            categoryManuallyChanged -> incoming.categoryId
            typeCorrected &&
                CategoryMatcher.categoryTypeFor(existing.type) != CategoryMatcher.categoryTypeFor(incoming.type) ->
                incoming.categoryId
            else -> existing.categoryId
        }
        return existing.copy(
            timestampMillis = if (useIncomingTime) incoming.timestampMillis else existing.timestampMillis,
            localDate = if (useIncomingTime) incoming.localDate else existing.localDate,
            hourOfDay = if (useIncomingTime) incoming.hourOfDay else existing.hourOfDay,
            type = resolvedType,
            channel = mergedChannel,
            fundingMethod = mergedFunding,
            merchant = mergedMerchant,
            categoryId = resolvedCategoryId,
            note = existing.note.ifBlank { incoming.note.trim() },
            externalOrderId = mergedOrderId,
            updatedAtMillis = maxOf(existing.updatedAtMillis, System.currentTimeMillis())
        )
    }

    private fun matchesReviewWindow(candidate: TransactionRecord, incoming: ParsedPayment): Boolean {
        val deltaMs = abs(candidate.timestampMillis - incoming.timestampMillis)
        val bothHaveTime = incoming.hasTimeOfDay && candidate.hourOfDay in 0..23
        val sameDayDateOnly = !bothHaveTime &&
            Instant.ofEpochMilli(incoming.timestampMillis).atZone(zoneId).toLocalDate().toString() == candidate.localDate
        val channelCompatible = candidate.channel == incoming.channel ||
            candidate.channel == PaymentChannel.BANK ||
            incoming.channel == PaymentChannel.BANK ||
            candidate.channel == PaymentChannel.MANUAL ||
            candidate.channel == PaymentChannel.OTHER ||
            incoming.channel == PaymentChannel.MANUAL ||
            incoming.channel == PaymentChannel.OTHER
        if (bothHaveTime && deltaMs <= importWindowMs && channelCompatible) {
            return true
        }
        val merchantsAligned = areNormalizedMerchantsAligned(candidate.merchant, incoming.merchant)
        if (bothHaveTime && deltaMs <= REVIEW_MERCHANT_WINDOW_MS && merchantsAligned) {
            return true
        }
        val fundingBankAligned = isFundingBankAligned(candidate, incoming)
        return sameDayDateOnly && (merchantsAligned || (channelCompatible && fundingBankAligned))
    }

    private fun isFundingBankAligned(candidate: TransactionRecord, incoming: ParsedPayment): Boolean {
        if (candidate.channel != PaymentChannel.BANK && incoming.channel != PaymentChannel.BANK) {
            return false
        }
        if (hasExplicitFundingBankConflict(candidate, incoming)) return false
        val leftBanks = fundingBanks(candidate.fundingMethod, candidate.merchant)
        val rightBanks = fundingBanks(incoming.fundingMethod, incoming.merchant)
        return leftBanks.intersect(rightBanks).isNotEmpty()
    }

    /**
     * Detects irreconcilable funding sources: a shared bank with two different explicit card
     * tails, or two clearly different banks. Only parenthesized 4-digit card tails are used so
     * arbitrary order/reference numbers can never be mistaken for a card suffix.
     */
    private fun hasExplicitFundingBankConflict(
        candidate: TransactionRecord,
        incoming: ParsedPayment
    ): Boolean {
        val leftTail = explicitCardTail(candidate.fundingMethod)
        val rightTail = explicitCardTail(incoming.fundingMethod)
        if (leftTail != null && rightTail != null && leftTail != rightTail) return true

        val leftBanks = fundingBanks(candidate.fundingMethod, candidate.merchant)
        val rightBanks = fundingBanks(incoming.fundingMethod, incoming.merchant)
        return leftBanks.isNotEmpty() &&
            rightBanks.isNotEmpty() &&
            leftBanks.intersect(rightBanks).isEmpty()
    }

    private fun fundingBanks(fundingMethod: String, merchant: String): Set<String> =
        banksMentioned(fundingMethod).ifEmpty { banksMentioned(merchant) }

    private fun banksMentioned(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        return knownBankNames.filterTo(LinkedHashSet()) { text.contains(it) }
    }

    private fun explicitCardTail(fundingMethod: String): String? {
        // Card tails are only trusted when the funding label clearly refers to a bank/card.
        if (!fundingMethod.contains("银行") && !fundingMethod.contains("卡")) return null
        return explicitCardTailRegex.find(fundingMethod)?.groupValues?.getOrNull(1)
    }

    private fun typeDisplayName(type: TransactionType): String = when (type) {
        TransactionType.EXPENSE -> "支出"
        TransactionType.INCOME -> "收入"
        TransactionType.REFUND -> "退款"
        TransactionType.TRANSFER -> "转账"
    }

    private fun hasEnrichingInfo(existing: TransactionRecord, incoming: ParsedPayment): Boolean {
        val existingMerchant = existing.merchant.trim()
        val incomingMerchant = incoming.merchant.trim()
        val merchantEnriched = when {
            incomingMerchant.isEmpty() -> false
            isGenericMerchant(existingMerchant, existing.fundingMethod) &&
                !isGenericMerchant(incomingMerchant, incoming.fundingMethod) -> true
            !isGenericMerchant(incomingMerchant, incoming.fundingMethod) &&
                incomingMerchant.length > existingMerchant.length &&
                (incomingMerchant.contains(existingMerchant, ignoreCase = true) ||
                    existing.channel == PaymentChannel.MANUAL) -> true
            else -> false
        }
        val orderIdEnriched = existing.externalOrderId.isNullOrBlank() && !incoming.externalOrderId.isNullOrBlank()
        val noteEnriched = existing.note.isBlank() && incoming.note.isNotBlank()
        val fundingEnriched = isGenericFunding(existing.fundingMethod) &&
            incoming.fundingMethod.isNotBlank() &&
            !isGenericFunding(incoming.fundingMethod)
        val timeEnriched = existing.hourOfDay == HOUR_OF_DAY_UNKNOWN && incoming.hasTimeOfDay
        return merchantEnriched || orderIdEnriched || noteEnriched || fundingEnriched || timeEnriched
    }

    private fun areNormalizedMerchantsAligned(left: String, right: String): Boolean {
        if (left.trim() in genericMerchants || right.trim() in genericMerchants) return false
        val a = normalizeMerchantKey(left)
        val b = normalizeMerchantKey(right)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || (a.length >= 2 && b.contains(a)) || (b.length >= 2 && a.contains(b))
    }

    private fun normalizeMerchantKey(raw: String): String =
        raw.trim().lowercase(Locale.ROOT).replace(merchantKeyNoiseRegex, "")

    /**
     * Clears the in-memory real-time debounce cache.
     */
    fun clearDebounceCache() {
        synchronized(debounceLock) {
            debounceCache.clear()
        }
    }

    private fun shouldSuppressAsDebounce(incoming: ParsedPayment, nowMillis: Long): Boolean {
        if (debounceWindowMs <= 0L) return false
        val key = DebounceKey(
            source = incoming.source,
            channel = incoming.channel,
            type = incoming.type,
            amountCents = incoming.amountCents,
            merchant = incoming.merchant.trim(),
            fundingMethod = incoming.fundingMethod.trim(),
            externalOrderId = incoming.externalOrderId?.trim().orEmpty()
        )
        synchronized(debounceLock) {
            val iterator = debounceCache.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (abs(nowMillis - entry.value) >= debounceWindowMs) {
                    iterator.remove()
                }
            }

            val previousMillis = debounceCache[key]
            if (previousMillis != null && abs(nowMillis - previousMillis) < debounceWindowMs) {
                return true
            }

            debounceCache[key] = nowMillis
            while (debounceCache.size > MAX_DEBOUNCE_CACHE_SIZE) {
                val eldest = debounceCache.entries.iterator()
                if (eldest.hasNext()) {
                    eldest.next()
                    eldest.remove()
                } else {
                    break
                }
            }
            return false
        }
    }

    private fun mergeRealTimeRecord(
        existing: TransactionRecord,
        incoming: ParsedPayment,
        nowMillis: Long
    ): TransactionRecord {
        val bankArrivedFirstAndWalletSecond =
            existing.channel == PaymentChannel.BANK && incoming.channel in walletChannels
        val walletArrivedFirstAndBankSecond =
            existing.channel in walletChannels && incoming.channel == PaymentChannel.BANK

        val mergedChannel = if (bankArrivedFirstAndWalletSecond) {
            incoming.channel
        } else {
            existing.channel
        }

        val mergedFunding = when {
            walletArrivedFirstAndBankSecond -> {
                if (isGenericFunding(existing.fundingMethod) && incoming.fundingMethod.isNotBlank()) {
                    incoming.fundingMethod.trim()
                } else {
                    existing.fundingMethod
                }
            }
            bankArrivedFirstAndWalletSecond -> {
                if (isGenericFunding(existing.fundingMethod) && !isGenericFunding(incoming.fundingMethod)) {
                    incoming.fundingMethod.trim()
                } else {
                    existing.fundingMethod
                }
            }
            else -> {
                if (isGenericFunding(existing.fundingMethod) && incoming.fundingMethod.isNotBlank()) {
                    incoming.fundingMethod.trim()
                } else {
                    existing.fundingMethod.ifBlank { incoming.fundingMethod.trim() }
                }
            }
        }

        val mergedMerchant = chooseRicherMerchant(
            existingMerchant = existing.merchant,
            existingFunding = existing.fundingMethod,
            incomingMerchant = incoming.merchant,
            incomingFunding = incoming.fundingMethod,
            preferIncomingOnTie = false
        )

        val (mergedTimestamp, mergedLocalDate, mergedHour) =
            if (bankArrivedFirstAndWalletSecond && incoming.timestampMillis < existing.timestampMillis) {
                timestampParts(incoming.timestampMillis)
            } else {
                Triple(existing.timestampMillis, existing.localDate, existing.hourOfDay)
            }

        val mergedSource = combineCaptureSources(existing.captureSource, incoming.source)
        val mergedOrderId = existing.externalOrderId?.takeIf { it.isNotBlank() }
            ?: incoming.externalOrderId?.trim()?.takeIf { it.isNotEmpty() }

        return existing.copy(
            timestampMillis = mergedTimestamp,
            localDate = mergedLocalDate,
            hourOfDay = mergedHour,
            channel = mergedChannel,
            fundingMethod = mergedFunding,
            merchant = mergedMerchant,
            note = existing.note.ifBlank { incoming.note.trim() },
            captureSource = mergedSource,
            externalOrderId = mergedOrderId,
            updatedAtMillis = maxOf(existing.updatedAtMillis, nowMillis)
        )
    }

    private fun mergeImportRecord(
        existing: TransactionRecord,
        incoming: ParsedPayment
    ): TransactionRecord {
        val (officialTimestamp, officialLocalDate, officialHour) = timestampParts(incoming.timestampMillis)
        val preserveExistingTime = !incoming.hasTimeOfDay && existing.hourOfDay in 0..23
        val incomingOrderId = incoming.externalOrderId?.trim()?.takeIf { it.isNotEmpty() }
            ?: existing.externalOrderId
        val incomingFunding = incoming.fundingMethod.trim()
        val mergedFunding = when {
            incomingFunding.isEmpty() -> existing.fundingMethod
            !isGenericFunding(incomingFunding) -> incomingFunding
            isGenericFunding(existing.fundingMethod) -> incomingFunding
            else -> existing.fundingMethod
        }

        return existing.copy(
            timestampMillis = if (preserveExistingTime) existing.timestampMillis else officialTimestamp,
            localDate = if (preserveExistingTime) existing.localDate else officialLocalDate,
            hourOfDay = if (preserveExistingTime) existing.hourOfDay else if (incoming.hasTimeOfDay) officialHour else HOUR_OF_DAY_UNKNOWN,
            channel = incoming.channel,
            fundingMethod = mergedFunding,
            merchant = chooseRicherMerchant(
                existingMerchant = existing.merchant,
                existingFunding = existing.fundingMethod,
                incomingMerchant = incoming.merchant,
                incomingFunding = incoming.fundingMethod,
                preferIncomingOnTie = true
            ),
            note = existing.note.ifBlank { incoming.note.trim() },
            externalOrderId = incomingOrderId,
            updatedAtMillis = maxOf(existing.updatedAtMillis, officialTimestamp)
        )
    }

    private fun timestampParts(timestampMillis: Long): Triple<Long, String, Int> {
        val zoned = Instant.ofEpochMilli(timestampMillis).atZone(zoneId)
        return Triple(timestampMillis, zoned.toLocalDate().toString(), zoned.hour)
    }

    private fun combineCaptureSources(existing: CaptureSource, incoming: CaptureSource): CaptureSource = when {
        existing == CaptureSource.BOTH -> CaptureSource.BOTH
        (existing == CaptureSource.ACCESSIBILITY && incoming == CaptureSource.NOTIFICATION) ||
            (existing == CaptureSource.NOTIFICATION && incoming == CaptureSource.ACCESSIBILITY) -> CaptureSource.BOTH
        else -> existing
    }

    private fun isBankWalletPair(first: PaymentChannel, second: PaymentChannel): Boolean =
        (first == PaymentChannel.BANK && second in walletChannels) ||
            (first in walletChannels && second == PaymentChannel.BANK)

    private fun isBankResolvedWalletCrossCapture(
        candidate: TransactionRecord,
        incoming: ParsedPayment
    ): Boolean {
        if (candidate.channel != incoming.channel || candidate.channel !in walletChannels) {
            return false
        }
        if (candidate.captureSource == CaptureSource.BOTH ||
            candidate.captureSource == incoming.source
        ) {
            return false
        }
        val isCrossCapturePair =
            (candidate.captureSource == CaptureSource.ACCESSIBILITY &&
                incoming.source == CaptureSource.NOTIFICATION) ||
                (candidate.captureSource == CaptureSource.NOTIFICATION &&
                    incoming.source == CaptureSource.ACCESSIBILITY)
        if (!isCrossCapturePair) return false

        val notificationFunding = if (incoming.source == CaptureSource.NOTIFICATION) {
            incoming.fundingMethod
        } else {
            candidate.fundingMethod
        }
        return hasBankFundingMethod(notificationFunding)
    }

    private fun hasBankFundingMethod(fundingMethod: String): Boolean {
        val trimmed = fundingMethod.trim()
        if (trimmed.isEmpty()) return false
        return trimmed.contains("银行") || trimmed in knownBankNames
    }

    private fun isOrderCompatible(existingOrderId: String?, incomingOrderId: String?): Boolean {
        val left = existingOrderId?.trim().orEmpty()
        val right = incomingOrderId?.trim().orEmpty()
        return left.isEmpty() || right.isEmpty() || left == right
    }

    private fun chooseRicherMerchant(
        existingMerchant: String,
        existingFunding: String,
        incomingMerchant: String,
        incomingFunding: String,
        preferIncomingOnTie: Boolean
    ): String {
        val existingTrimmed = existingMerchant.trim()
        val incomingTrimmed = incomingMerchant.trim()
        val existingGeneric = isGenericMerchant(existingTrimmed, existingFunding)
        val incomingGeneric = isGenericMerchant(incomingTrimmed, incomingFunding)

        return when {
            !incomingGeneric && existingGeneric -> incomingTrimmed
            !existingGeneric && incomingGeneric -> existingTrimmed
            !incomingGeneric && !existingGeneric -> {
                if (preferIncomingOnTie) {
                    incomingTrimmed
                } else if (incomingTrimmed.length > existingTrimmed.length) {
                    incomingTrimmed
                } else {
                    existingTrimmed
                }
            }
            else -> {
                if (preferIncomingOnTie) {
                    incomingTrimmed.ifBlank { existingTrimmed }
                } else {
                    existingTrimmed.ifBlank { incomingTrimmed }
                }
            }
        }
    }

    private fun isGenericMerchant(merchant: String, fundingMethod: String): Boolean {
        val trimmed = merchant.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed in genericMerchants) return true
        if (trimmed in knownBankNames) return true
        val fundingTrimmed = fundingMethod.trim()
        return fundingTrimmed.isNotEmpty() && trimmed == fundingTrimmed
    }

    private fun isGenericFunding(funding: String): Boolean =
        funding.trim() in genericFundingLabels

    private companion object {
        const val MAX_DEBOUNCE_CACHE_SIZE = 512
        const val REVIEW_MERCHANT_WINDOW_MS = 15 * 60 * 1000L
        const val COMBINED_PAYMENT_SPREAD_MS = 30 * 1000L
        const val MAX_COMBINED_CANDIDATES = 12
        const val MAX_COMBINED_SUBSET_SIZE = 4

        val explicitCardTailRegex = Regex("""[（(]\s*(?:尾号\s*[:：]?\s*)?(\d{4})\s*[)）]""")
        val directionInboundRegex = Regex("""资金方向\s*[:：]\s*转入""")
        val directionOutboundRegex = Regex("""资金方向\s*[:：]\s*(?:转出|支出|付款)""")

        val walletChannels = setOf(
            PaymentChannel.WECHAT,
            PaymentChannel.ALIPAY,
            PaymentChannel.UNIONPAY
        )

        private val merchantKeyNoiseRegex = Regex("""[\s\-_()（）·,.，。]""")

        val knownBankNames: Set<String> =
            PaymentPackages.defaultBankPackages.values.toSet() + BankPdfParser.knownBankNames

        val genericMerchants = setOf(
            "微信",
            "微信支付",
            "微信支付凭证",
            "微信收款",
            "财付通",
            "财付通支付",
            "财付通支付科技有限公司",
            "深圳市财付通支付科技有限公司",
            "支付宝",
            "支付宝通知",
            "支付宝支付",
            "支付宝支付科技有限公司",
            "支付宝(中国)网络技术有限公司",
            "支付宝（中国）网络技术有限公司",
            "云闪付",
            "银联",
            "网联",
            "网联清算",
            "网联清算有限公司",
            "快捷支付",
            "银行流水",
            "银行卡",
            "未知商户",
            "未知",
            "其他支付",
            "手动记账"
        )

        val genericFundingLabels = setOf(
            "",
            "银行卡",
            "储蓄卡",
            "借记卡",
            "信用卡",
            "快捷支付",
            "银行卡支付",
            "未知"
        )
    }
}
