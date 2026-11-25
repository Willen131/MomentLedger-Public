package com.momentledger.app.data.repository

import com.momentledger.app.domain.engine.DeduplicationEngine
import com.momentledger.app.domain.model.CaptureSource
import com.momentledger.app.domain.model.DefaultCategories
import com.momentledger.app.domain.model.HOUR_OF_DAY_UNKNOWN
import com.momentledger.app.domain.model.ImportDuplicateStatus
import com.momentledger.app.domain.model.ImportReviewBatch
import com.momentledger.app.domain.model.ImportReviewItem
import com.momentledger.app.domain.model.ParsedPayment
import com.momentledger.app.domain.model.PaymentChannel
import com.momentledger.app.domain.model.TransactionRecord
import com.momentledger.app.domain.model.TransactionType
import com.momentledger.app.testutil.FakeLedgerStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Regression coverage for bank <-> wallet reconciliation performed through
 * [LedgerRepository.prepareImportReview] / [LedgerRepository.commitImportReview].
 *
 * Assertions intentionally inspect only public review-batch data and persisted
 * [FakeLedgerStore.getAllTransactions] rows, never dedup-engine internals.
 */
class ImportReconciliationTest {

    @Test
    fun explicitFundingBankOverridesBankNameInMerchant() = runTest {
        val (_, repo) = newRepository()
        val timestamp = millisAt(2026, 11, 21, 13, 30, 0)
        saveRecord(repo, timestamp, 5000L, TransactionType.EXPENSE,
            PaymentChannel.ALIPAY, "招商银行(7032)", "工商银行活动商户")
        val incoming = walletSplit(timestamp)[0].copy(
            channel = PaymentChannel.BANK, amountCents = 5000L, externalOrderId = null)
        val batch = repo.prepareImportReview(listOf(incoming), "银行流水")
        assertEquals(ImportDuplicateStatus.NEW, batch.items.single().duplicateStatus)
    }

    @Test
    fun ambiguousWalletSubsetsWhenBankArrivesLast_requireManualVerification() = runTest {
        val (_, repo) = newRepository()
        val timestamp = millisAt(2026, 11, 21, 13, 30, 0)
        val split = walletSplit(timestamp)
        val alternatives = listOf(
            split[0].copy(amountCents = 6000L, externalOrderId = "alternative-a"),
            split[1].copy(amountCents = 5000L, externalOrderId = "alternative-b")
        )
        repo.commitImportReview(repo.prepareImportReview(split + alternatives, "钱包账单", dedupIntraBatch = false).items)
        val bank = split[0].copy(channel = PaymentChannel.BANK, externalOrderId = null, amountCents = 11000L)
        val batch = repo.prepareImportReview(listOf(bank), "银行流水")
        assertTrue(batch.items.single().needsVerification)
        assertFalse(batch.items.single().selected)
    }

    @Test
    fun multipleWalletSubsetsForOneBankDebit_requireManualVerification() = runTest {
        val (_, repo) = newRepository()
        val timestamp = millisAt(2026, 11, 21, 13, 30, 0)
        saveRecord(repo, timestamp, 11000L, TransactionType.EXPENSE,
            PaymentChannel.BANK, bankCard, "某商户")
        val split = walletSplit(timestamp)
        val alternatives = listOf(
            split[0].copy(amountCents = 6000L, externalOrderId = "alternative-a"),
            split[1].copy(amountCents = 5000L, externalOrderId = "alternative-b")
        )
        val batch = repo.prepareImportReview(split + alternatives, "钱包账单", dedupIntraBatch = false)
        assertTrue(batch.items.all { it.reconciliation == null && !it.selected && it.needsVerification })
    }

    @Test
    fun multipleBankDebitsForOneWalletSubset_requireManualVerification() = runTest {
        val (_, repo) = newRepository()
        val timestamp = millisAt(2026, 11, 21, 13, 30, 0)
        repeat(2) {
            saveRecord(repo, timestamp, 11000L, TransactionType.EXPENSE,
                PaymentChannel.BANK, bankCard, "某商户")
        }
        val batch = repo.prepareImportReview(walletSplit(timestamp), "钱包账单", dedupIntraBatch = false)
        assertTrue(batch.items.all { it.reconciliation == null && !it.selected && it.needsVerification })
    }

    @Test
    fun bankNamedWalletTransferWithoutWithdrawalEvidence_isNotTreatedAsWithdrawal() = runTest {
        val (store, repo) = newRepository()
        val timestamp = millisAt(2026, 11, 22, 9, 15, 0)
        saveRecord(repo, timestamp, 10010L, TransactionType.TRANSFER,
            PaymentChannel.WECHAT, walletCard, "工商银行(7032)")
        val batch = repo.prepareImportReview(listOf(bankWithdrawalReceipt(timestamp)), "银行流水")
        assertEquals(ImportDuplicateStatus.NEW, batch.items.single().duplicateStatus)
        repo.commitImportReview(batch.items)
        assertEquals(2, store.getAllTransactions().size)
    }

    @Test
    fun bankTransferWithoutInflowEvidence_isNotPairedWithWithdrawal() = runTest {
        val (_, repo) = newRepository()
        val timestamp = millisAt(2026, 11, 22, 9, 15, 0)
        repo.commitImportReview(repo.prepareImportReview(listOf(walletWithdrawal(timestamp)), "微信账单").items)
        val batch = repo.prepareImportReview(
            listOf(bankWithdrawalReceipt(timestamp).copy(merchant = "本人", note = "")), "银行流水")
        assertEquals(ImportDuplicateStatus.NEW, batch.items.single().duplicateStatus)
    }

    private val shanghaiZone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val bankCard = "工商银行(7032)"
    private val walletCard = "工商银行储蓄卡(7032)"

    private fun millisAt(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int
    ): Long = ZonedDateTime.of(year, month, day, hour, minute, second, 0, shanghaiZone)
        .toInstant()
        .toEpochMilli()

    private fun dateStamp(timestampMillis: Long): String =
        Instant.ofEpochMilli(timestampMillis).atZone(shanghaiZone).toLocalDate().toString()

    private fun newRepository(): Pair<FakeLedgerStore, LedgerRepository> {
        val store = FakeLedgerStore()
        val engine = DeduplicationEngine(zoneId = shanghaiZone)
        return store to LedgerRepository(store = store, dedupEngine = engine, zoneId = shanghaiZone)
    }

    private suspend fun saveRecord(
        repo: LedgerRepository,
        timestampMillis: Long,
        amountCents: Long,
        type: TransactionType,
        channel: PaymentChannel,
        fundingMethod: String,
        merchant: String,
        note: String = "",
        hourOfDay: Int? = null
    ): Long {
        val zoned = Instant.ofEpochMilli(timestampMillis).atZone(shanghaiZone)
        return repo.saveManualTransaction(
            TransactionRecord(
                id = 0L,
                timestampMillis = timestampMillis,
                localDate = zoned.toLocalDate().toString(),
                hourOfDay = hourOfDay ?: zoned.hour,
                amountCents = amountCents,
                type = type,
                channel = channel,
                fundingMethod = fundingMethod,
                merchant = merchant,
                categoryId = DefaultCategories.EXPENSE_OTHER_ID,
                note = note,
                excludedFromStats = false,
                captureSource = CaptureSource.IMPORT,
                externalOrderId = null,
                createdAtMillis = timestampMillis,
                updatedAtMillis = timestampMillis
            )
        )
    }

    private fun expenseTotal(records: List<TransactionRecord>): Long =
        records.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountCents }

    // ---------------------------------------------------------------------------------------------
    // 1. Same bank, different explicit card tails: same-day same-amount wallet and bank are independent.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun prepareImportReview_walletFirstTimedThenBankDateOnlyWithDifferentCardTail_keepsBothExpenses() =
        runTest {
            val (store, repo) = newRepository()
            val walletMillis = millisAt(2026, 11, 20, 10, 0, 0)
            saveRecord(
                repo = repo,
                timestampMillis = walletMillis,
                amountCents = 5000L,
                type = TransactionType.EXPENSE,
                channel = PaymentChannel.WECHAT,
                fundingMethod = "招商银行储蓄卡(7205)",
                merchant = "咖啡店甲"
            )

            val bankDateOnlyMillis = millisAt(2026, 11, 20, 12, 0, 0)
            val batch = repo.prepareImportReview(
                items = listOf(
                    ParsedPayment(
                        channel = PaymentChannel.BANK,
                        fundingMethod = "招商银行(1234)",
                        type = TransactionType.EXPENSE,
                        amountCents = 5000L,
                        merchant = "超市乙",
                        timestampMillis = bankDateOnlyMillis,
                        source = CaptureSource.IMPORT,
                        hasTimeOfDay = false
                    )
                ),
                sourceTitle = "银行流水"
            )

            val item = batch.items.single()
            assertEquals(ImportDuplicateStatus.NEW, item.duplicateStatus)
            assertTrue(
                "A different explicit card tail must not default-skip a real bank expense",
                item.selected
            )
            assertNull(item.matchedExistingId)

            repo.commitImportReview(batch.items)
            val all = store.getAllTransactions()
            assertEquals("wallet and bank expenses must both survive commit", 2, all.size)
            assertEquals(10000L, expenseTotal(all))
        }

    @Test
    fun prepareImportReview_bankDateOnlyFirstThenWalletTimedWithDifferentCardTail_keepsBothExpenses() =
        runTest {
            val (store, repo) = newRepository()
            val bankDateOnlyMillis = millisAt(2026, 11, 20, 12, 0, 0)
            saveRecord(
                repo = repo,
                timestampMillis = bankDateOnlyMillis,
                amountCents = 5000L,
                type = TransactionType.EXPENSE,
                channel = PaymentChannel.BANK,
                fundingMethod = "招商银行(1234)",
                merchant = "超市乙",
                hourOfDay = HOUR_OF_DAY_UNKNOWN
            )

            val walletMillis = millisAt(2026, 11, 20, 10, 0, 0)
            val batch = repo.prepareImportReview(
                items = listOf(
                    ParsedPayment(
                        channel = PaymentChannel.WECHAT,
                        fundingMethod = "招商银行储蓄卡(7205)",
                        type = TransactionType.EXPENSE,
                        amountCents = 5000L,
                        merchant = "咖啡店甲",
                        timestampMillis = walletMillis,
                        source = CaptureSource.IMPORT,
                        hasTimeOfDay = true
                    )
                ),
                sourceTitle = "微信账单"
            )

            val item = batch.items.single()
            assertEquals(ImportDuplicateStatus.NEW, item.duplicateStatus)
            assertTrue(
                "A different explicit card tail must not default-merge the wallet expense",
                item.selected
            )

            repo.commitImportReview(batch.items)
            val all = store.getAllTransactions()
            assertEquals("wallet and bank expenses must both survive commit", 2, all.size)
            assertEquals(10000L, expenseTotal(all))
        }

    // ---------------------------------------------------------------------------------------------
    // 2. One bank debit <-> two wallet sub-orders on the same card and second.
    // ---------------------------------------------------------------------------------------------

    private fun walletSplit(timestampMillis: Long): List<ParsedPayment> = listOf(
        ParsedPayment(
            channel = PaymentChannel.WECHAT,
            fundingMethod = walletCard,
            type = TransactionType.EXPENSE,
            amountCents = 10000L,
            merchant = "某商户",
            timestampMillis = timestampMillis,
            source = CaptureSource.IMPORT,
            externalOrderId = "split-product-${dateStamp(timestampMillis)}"
        ),
        ParsedPayment(
            channel = PaymentChannel.WECHAT,
            fundingMethod = walletCard,
            type = TransactionType.EXPENSE,
            amountCents = 1000L,
            merchant = "某商户",
            timestampMillis = timestampMillis,
            source = CaptureSource.IMPORT,
            externalOrderId = "split-service-${dateStamp(timestampMillis)}"
        )
    )

    private fun assertOnlyWalletSplitRemains(records: List<TransactionRecord>) {
        assertEquals("single bank debit must be replaced by exactly two wallet rows", 2, records.size)
        assertEquals(11000L, expenseTotal(records))
        assertEquals(setOf(10000L, 1000L), records.map { it.amountCents }.toSet())
        assertTrue(
            "no bank debit row may remain after the split replacement",
            records.all { it.channel == PaymentChannel.WECHAT }
        )
    }

    @Test
    fun commitImportReview_bankDebitStoredFirst_thenWalletSameCardSameSecondSplit_replacesWithWalletRows() =
        runTest {
            val (store, repo) = newRepository()
            val timestamp = millisAt(2026, 11, 21, 13, 30, 0)
            saveRecord(
                repo = repo,
                timestampMillis = timestamp,
                amountCents = 11000L,
                type = TransactionType.EXPENSE,
                channel = PaymentChannel.BANK,
                fundingMethod = bankCard,
                merchant = "某商户"
            )

            val walletItems = walletSplit(timestamp)
            val batch = repo.prepareImportReview(walletItems, "微信账单")
            repo.commitImportReview(batch.items)

            assertOnlyWalletSplitRemains(store.getAllTransactions())

            // Re-preparing and re-committing the same wallet batch must stay at two rows.
            val retry = repo.prepareImportReview(walletItems, "微信账单")
            repo.commitImportReview(retry.items)
            assertOnlyWalletSplitRemains(store.getAllTransactions())
        }

    @Test
    fun prepareImportReview_walletRowsStoredFirst_thenBankCombinedDebit_isNotAddedAsThirdRow() =
        runTest {
            val (store, repo) = newRepository()
            val timestamp = millisAt(2026, 11, 21, 13, 30, 0)

            val walletBatch = repo.prepareImportReview(walletSplit(timestamp), "微信账单")
            repo.commitImportReview(walletBatch.items)
            assertEquals(2, store.getAllTransactions().size)

            val bankBatch = repo.prepareImportReview(
                items = listOf(
                    ParsedPayment(
                        channel = PaymentChannel.BANK,
                        fundingMethod = bankCard,
                        type = TransactionType.EXPENSE,
                        amountCents = 11000L,
                        merchant = "某商户",
                        timestampMillis = timestamp,
                        source = CaptureSource.IMPORT
                    )
                ),
                sourceTitle = "银行流水"
            )

            val bankItem = bankBatch.items.single()
            assertFalse(
                "A bank debit already explained by the wallet split must not be auto-selected",
                bankItem.selected
            )
            repo.commitImportReview(bankBatch.items)
            assertOnlyWalletSplitRemains(store.getAllTransactions())
        }

    @Test
    fun commitImportReview_combinedReplacement_doesNotReuseAdjacentBankRowFromAnotherMerchant() =
        runTest {
            val (store, repo) = newRepository()
            val timestamp = millisAt(2026, 11, 21, 13, 30, 0)
            saveRecord(
                repo = repo,
                timestampMillis = timestamp,
                amountCents = 11000L,
                type = TransactionType.EXPENSE,
                channel = PaymentChannel.BANK,
                fundingMethod = bankCard,
                merchant = "某商户"
            )
            val otherBankId = saveRecord(
                repo = repo,
                timestampMillis = timestamp + 20_000L,
                amountCents = 11000L,
                type = TransactionType.EXPENSE,
                channel = PaymentChannel.BANK,
                fundingMethod = bankCard,
                merchant = "另一商户"
            )

            val batch = repo.prepareImportReview(walletSplit(timestamp), "微信账单")
            repo.commitImportReview(batch.items)

            val all = store.getAllTransactions()
            assertEquals("only the matching bank debit may be consumed", 3, all.size)
            assertEquals(22000L, expenseTotal(all))

            val otherBank = all.firstOrNull { it.id == otherBankId }
            assertNotNull(
                "The adjacent bank row from another merchant must not be reused/removed",
                otherBank
            )
            assertEquals(PaymentChannel.BANK, otherBank!!.channel)
            assertEquals(11000L, otherBank.amountCents)

            val walletRows = all.filter { it.channel == PaymentChannel.WECHAT }
            assertEquals(setOf(10000L, 1000L), walletRows.map { it.amountCents }.toSet())
        }

    // ---------------------------------------------------------------------------------------------
    // 3. Gross wallet withdrawal 100.10 vs net bank receipt 100.00 -> principal + 0.10 fee.
    // ---------------------------------------------------------------------------------------------

    private fun walletWithdrawal(timestampMillis: Long): ParsedPayment = ParsedPayment(
        channel = PaymentChannel.WECHAT,
        fundingMethod = walletCard,
        type = TransactionType.TRANSFER,
        amountCents = 10010L,
        merchant = "微信提现",
        timestampMillis = timestampMillis,
        source = CaptureSource.IMPORT,
        externalOrderId = "withdrawal-$timestampMillis",
        note = "零钱提现"
    )

    private fun bankWithdrawalReceipt(timestampMillis: Long): ParsedPayment = ParsedPayment(
        channel = PaymentChannel.BANK,
        fundingMethod = bankCard,
        type = TransactionType.TRANSFER,
        amountCents = 10000L,
        merchant = "网联清算有限公司",
        timestampMillis = timestampMillis,
        source = CaptureSource.IMPORT,
        note = "网联收款 · 资金方向：转入"
    )

    private fun assertSecondBatchSelectsPrincipalAndFee(batch: ImportReviewBatch) {
        assertTrue("second batch must return review items", batch.items.isNotEmpty())
        batch.items.forEach { item ->
            assertTrue(
                "second batch item ${item.record.type}/${item.record.amountCents} must default-select",
                item.selected
            )
        }
        assertTrue(
            "second batch must include the 100.00 principal transfer",
            batch.items.any {
                it.record.type == TransactionType.TRANSFER && it.record.amountCents == 10000L
            }
        )
        assertTrue(
            "second batch must include the reconcilable 0.10 fee expense",
            batch.items.any {
                it.record.type == TransactionType.EXPENSE && it.record.amountCents == 10L
            }
        )
    }

    private fun assertReconciledWithdrawal(records: List<TransactionRecord>) {
        assertEquals("principal + fee must be exactly two rows", 2, records.size)
        val transfers = records.filter { it.type == TransactionType.TRANSFER }
        val fees = records.filter { it.type == TransactionType.EXPENSE }
        assertEquals(1, transfers.size)
        assertEquals(1, fees.size)
        assertEquals(10000L, transfers.single().amountCents)
        assertEquals(10L, fees.single().amountCents)
    }

    @Test
    fun prepareAndCommitWalletFirst_thenBankNetReceipt_splitsIntoPrincipalAndFeeWithoutDuplicates() =
        runTest {
            val (store, repo) = newRepository()
            val timestamp = millisAt(2026, 11, 22, 9, 15, 0)
            val wallet = walletWithdrawal(timestamp)
            val bank = bankWithdrawalReceipt(timestamp)

            repo.commitImportReview(
                repo.prepareImportReview(listOf(wallet), "微信账单").items
            )

            val secondBatch = repo.prepareImportReview(listOf(bank), "银行流水")
            assertSecondBatchSelectsPrincipalAndFee(secondBatch)
            repo.commitImportReview(secondBatch.items)

            assertReconciledWithdrawal(store.getAllTransactions())

            // Re-import / retry both sides must not duplicate principal or fee.
            repo.commitImportReview(
                repo.prepareImportReview(listOf(wallet), "微信账单").items
            )
            repo.commitImportReview(
                repo.prepareImportReview(listOf(bank), "银行流水").items
            )
            assertReconciledWithdrawal(store.getAllTransactions())
        }

    @Test
    fun prepareAndCommitBankFirst_thenWalletGrossDebit_splitsIntoPrincipalAndFeeWithoutDuplicates() =
        runTest {
            val (store, repo) = newRepository()
            val timestamp = millisAt(2026, 11, 22, 9, 15, 0)
            val wallet = walletWithdrawal(timestamp)
            val bank = bankWithdrawalReceipt(timestamp)

            repo.commitImportReview(
                repo.prepareImportReview(listOf(bank), "银行流水").items
            )

            val secondBatch = repo.prepareImportReview(listOf(wallet), "微信账单")
            assertSecondBatchSelectsPrincipalAndFee(secondBatch)
            repo.commitImportReview(secondBatch.items)

            assertReconciledWithdrawal(store.getAllTransactions())

            // Re-import / retry both sides must not duplicate principal or fee.
            repo.commitImportReview(
                repo.prepareImportReview(listOf(bank), "银行流水").items
            )
            repo.commitImportReview(
                repo.prepareImportReview(listOf(wallet), "微信账单").items
            )
            assertReconciledWithdrawal(store.getAllTransactions())
        }

    @Test
    fun commitImportReview_sameSecondBatchCommittedTwice_staysPrincipalAndFee() = runTest {
        val (store, repo) = newRepository()
        val timestamp = millisAt(2026, 11, 22, 9, 15, 0)

        repo.commitImportReview(
            repo.prepareImportReview(listOf(walletWithdrawal(timestamp)), "微信账单").items
        )

        val secondBatch = repo.prepareImportReview(
            listOf(bankWithdrawalReceipt(timestamp)),
            "银行流水"
        )
        assertSecondBatchSelectsPrincipalAndFee(secondBatch)
        repo.commitImportReview(secondBatch.items)
        repo.commitImportReview(secondBatch.items)

        assertReconciledWithdrawal(store.getAllTransactions())
    }

    // ---------------------------------------------------------------------------------------------
    // 4. Recharge / repayment and different-card transfers must not be mistaken for a withdrawal fee.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun prepareImportReview_walletRechargeAndBankRepayment_areNotPairedAsWithdrawalFee() = runTest {
        val (store, repo) = newRepository()
        val timestamp = millisAt(2026, 11, 23, 10, 0, 0)

        val recharge = ParsedPayment(
            channel = PaymentChannel.WECHAT,
            fundingMethod = walletCard,
            type = TransactionType.TRANSFER,
            amountCents = 10010L,
            merchant = "零钱充值",
            timestampMillis = timestamp,
            source = CaptureSource.IMPORT,
            note = "充值到零钱"
        )
        repo.commitImportReview(
            repo.prepareImportReview(listOf(recharge), "微信账单").items
        )

        val repayment = ParsedPayment(
            channel = PaymentChannel.BANK,
            fundingMethod = bankCard,
            type = TransactionType.TRANSFER,
            amountCents = 10000L,
            merchant = "某银行",
            timestampMillis = timestamp,
            source = CaptureSource.IMPORT,
            note = "网联付款 · 资金方向：转出"
        )
        val batch = repo.prepareImportReview(listOf(repayment), "银行流水")
        val item = batch.items.single()
        assertEquals(
            "A recharge/repayment pair must not be treated as a withdrawal+0.10 fee",
            ImportDuplicateStatus.NEW,
            item.duplicateStatus
        )
        assertTrue(item.selected)

        repo.commitImportReview(batch.items)
        val all = store.getAllTransactions()
        assertEquals(2, all.size)
        assertTrue(
            "Recharge and repayment must not synthesize a fee expense",
            all.none { it.type == TransactionType.EXPENSE }
        )
    }

    @Test
    fun prepareImportReview_withdrawalOnDifferentCardTail_isNotPairedWithBankReceipt() = runTest {
        val (store, repo) = newRepository()
        val timestamp = millisAt(2026, 11, 23, 11, 0, 0)

        repo.commitImportReview(
            repo.prepareImportReview(listOf(walletWithdrawal(timestamp)), "微信账单").items
        )

        val otherCardBank = ParsedPayment(
            channel = PaymentChannel.BANK,
            fundingMethod = "工商银行(1234)",
            type = TransactionType.TRANSFER,
            amountCents = 10000L,
            merchant = "网联清算有限公司",
            timestampMillis = timestamp,
            source = CaptureSource.IMPORT,
            note = "网联收款 · 资金方向：转入"
        )
        val batch = repo.prepareImportReview(listOf(otherCardBank), "银行流水")
        val item = batch.items.single()
        assertEquals(
            "Withdrawal and receipt on different card tails must not pair",
            ImportDuplicateStatus.NEW,
            item.duplicateStatus
        )
        assertTrue(item.selected)

        repo.commitImportReview(batch.items)
        val all = store.getAllTransactions()
        assertEquals(2, all.size)
        assertTrue(all.none { it.type == TransactionType.EXPENSE })
        assertEquals(setOf(10010L, 10000L), all.map { it.amountCents }.toSet())
    }

    @Test
    fun prepareImportReview_withdrawalAndReceiptOnDifferentCardsAtDifferentTimes_areNotPaired() =
        runTest {
            val (store, repo) = newRepository()
            val walletTimestamp = millisAt(2026, 11, 23, 11, 0, 0)
            val bankTimestamp = millisAt(2026, 11, 23, 12, 5, 30)

            repo.commitImportReview(
                repo.prepareImportReview(listOf(walletWithdrawal(walletTimestamp)), "微信账单").items
            )

            val otherCardBank = ParsedPayment(
                channel = PaymentChannel.BANK,
                fundingMethod = "工商银行(5678)",
                type = TransactionType.TRANSFER,
                amountCents = 10000L,
                merchant = "网联清算有限公司",
                timestampMillis = bankTimestamp,
                source = CaptureSource.IMPORT,
                note = "网联收款 · 资金方向：转入"
            )
            val batch = repo.prepareImportReview(listOf(otherCardBank), "银行流水")
            val item = batch.items.single()
            assertEquals(
                "Different cards at different hour/minute/second must not pair as withdrawal + fee",
                ImportDuplicateStatus.NEW,
                item.duplicateStatus
            )
            assertTrue(item.selected)

            repo.commitImportReview(batch.items)
            val all = store.getAllTransactions()
            assertEquals(2, all.size)
            assertTrue(all.none { it.type == TransactionType.EXPENSE })
            assertEquals(setOf(10010L, 10000L), all.map { it.amountCents }.toSet())
        }

    @Test
    fun prepareImportReview_dateOnlyRechargeWithoutCardTail_keepsTwoRecordsInsteadOfFee() = runTest {
        val (store, repo) = newRepository()
        val timestamp = millisAt(2026, 11, 24, 12, 0, 0)

        val recharge = ParsedPayment(
            channel = PaymentChannel.WECHAT,
            fundingMethod = "工商银行储蓄卡",
            type = TransactionType.TRANSFER,
            amountCents = 10010L,
            merchant = "零钱充值",
            timestampMillis = timestamp,
            source = CaptureSource.IMPORT,
            note = "充值到零钱",
            hasTimeOfDay = false
        )
        repo.commitImportReview(
            repo.prepareImportReview(listOf(recharge), "微信账单").items
        )

        val repayment = ParsedPayment(
            channel = PaymentChannel.BANK,
            fundingMethod = "工商银行",
            type = TransactionType.TRANSFER,
            amountCents = 10000L,
            merchant = "信用卡还款",
            timestampMillis = timestamp,
            source = CaptureSource.IMPORT,
            note = "网联付款 · 资金方向：转出",
            hasTimeOfDay = false
        )
        val batch = repo.prepareImportReview(listOf(repayment), "银行流水")
        val item = batch.items.single()
        assertEquals(
            "No-card-tail, no-time recharge must stay a separate record",
            ImportDuplicateStatus.NEW,
            item.duplicateStatus
        )
        assertTrue(item.selected)

        repo.commitImportReview(batch.items)
        val all = store.getAllTransactions()
        assertEquals(2, all.size)
        assertTrue(all.none { it.type == TransactionType.EXPENSE })
    }

    @Test
    fun prepareImportReview_tinyRechargeWithoutBankEvidence_keepsTwoTransfers() = runTest {
        val (store, repo) = newRepository()
        val walletTimestamp = millisAt(2026, 11, 24, 14, 20, 0)

        val tinyRecharge = ParsedPayment(
            channel = PaymentChannel.WECHAT,
            fundingMethod = "零钱",
            type = TransactionType.TRANSFER,
            amountCents = 10L,
            merchant = "零钱充值",
            timestampMillis = walletTimestamp,
            source = CaptureSource.IMPORT,
            note = "充值到零钱"
        )
        repo.commitImportReview(
            repo.prepareImportReview(listOf(tinyRecharge), "微信账单").items
        )

        val dateOnlyBankMillis = millisAt(2026, 11, 24, 12, 0, 0)
        val tinyBankTransfer = ParsedPayment(
            channel = PaymentChannel.BANK,
            fundingMethod = "本人快捷支付",
            type = TransactionType.TRANSFER,
            amountCents = 10L,
            merchant = "本人快捷支付",
            timestampMillis = dateOnlyBankMillis,
            source = CaptureSource.IMPORT,
            note = "网联付款 · 资金方向：转出",
            hasTimeOfDay = false
        )
        val batch = repo.prepareImportReview(listOf(tinyBankTransfer), "银行流水")
        val item = batch.items.single()
        assertEquals(
            "A 0.10 transfer without card/fee evidence must not be treated as a withdrawal fee",
            ImportDuplicateStatus.NEW,
            item.duplicateStatus
        )
        assertTrue(item.selected)

        repo.commitImportReview(batch.items)
        val all = store.getAllTransactions()
        assertEquals("The wallet and bank 0.10 transfers must both survive", 2, all.size)
        assertEquals(
            setOf(PaymentChannel.WECHAT, PaymentChannel.BANK),
            all.map { it.channel }.toSet()
        )
        assertTrue(
            "Both rows must remain 0.10 transfers",
            all.all { it.type == TransactionType.TRANSFER && it.amountCents == 10L }
        )
        assertTrue(
            "A 0.10 transfer must not synthesize a fee expense",
            all.none { it.type == TransactionType.EXPENSE }
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 5. Partial user edits on replacement/fee-split reviews must be rejected with an
    //    IllegalArgumentException and leave the store completely untouched.
    // ---------------------------------------------------------------------------------------------

    private suspend fun assertCombinationPartialCommitRejected(
        store: FakeLedgerStore,
        repo: LedgerRepository,
        items: List<ImportReviewItem>
    ) {
        val before = store.getAllTransactions()
        var rejected = false
        try {
            repo.commitImportReview(items)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(
            "A partially selected combination group must be rejected with IllegalArgumentException",
            rejected
        )
        assertEquals(
            "Rejected partial commit must leave the repository unchanged",
            before,
            store.getAllTransactions()
        )
    }

    @Test
    fun commitImportReview_partialCombinationReplacement_neverSilentlyDeletesOriginalBankExpense() =
        runTest {
            val timestamp = millisAt(2026, 11, 25, 18, 0, 0)

            run {
                val (store, repo) = newRepository()
                saveRecord(
                    repo, timestamp, 11000L, TransactionType.EXPENSE,
                    PaymentChannel.BANK, bankCard, "某商户"
                )
                val batch = repo.prepareImportReview(walletSplit(timestamp), "微信账单")
                assertEquals(2, batch.items.size)
                val oneCancelled = batch.items.mapIndexed { index, item ->
                    if (index == 0) item.copy(selected = false) else item
                }
                assertCombinationPartialCommitRejected(store, repo, oneCancelled)
            }

            run {
                val (store, repo) = newRepository()
                saveRecord(
                    repo, timestamp, 11000L, TransactionType.EXPENSE,
                    PaymentChannel.BANK, bankCard, "某商户"
                )
                val batch = repo.prepareImportReview(walletSplit(timestamp), "微信账单")
                assertEquals(2, batch.items.size)
                assertCombinationPartialCommitRejected(store, repo, batch.items.drop(1))
            }

            run {
                val (store, repo) = newRepository()
                saveRecord(
                    repo, timestamp, 11000L, TransactionType.EXPENSE,
                    PaymentChannel.BANK, bankCard, "某商户"
                )
                val batch = repo.prepareImportReview(walletSplit(timestamp), "微信账单")
                assertEquals(2, batch.items.size)
                val oneAmountEdited = batch.items.mapIndexed { index, item ->
                    if (index == 0) {
                        item.copy(
                            record = item.record.copy(
                                amountCents = item.record.amountCents + 500L
                            ),
                            userEdited = true
                        )
                    } else {
                        item
                    }
                }
                assertCombinationPartialCommitRejected(store, repo, oneAmountEdited)
            }
        }

    @Test
    fun commitImportReview_groupPreviewedThenOriginalManuallyChanged_throwsAndKeepsRepository() =
        runTest {
            val (store, repo) = newRepository()
            val timestamp = millisAt(2026, 11, 25, 19, 0, 0)
            val bankId = saveRecord(
                repo, timestamp, 11000L, TransactionType.EXPENSE,
                PaymentChannel.BANK, bankCard, "某商户"
            )
            val batch = repo.prepareImportReview(walletSplit(timestamp), "微信账单")
            assertEquals(2, batch.items.size)

            val original = store.getTransactionById(bankId)
            assertNotNull("The group preview requires the original bank row", original)
            repo.updateTransaction(original!!.copy(amountCents = 11001L))

            val before = store.getAllTransactions()
            var rejected = false
            try {
                repo.commitImportReview(batch.items)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(
                "Committing a preview whose original was edited must throw IllegalArgumentException",
                rejected
            )
            assertEquals(
                "Rejected commit after editing the original must leave the repository unchanged",
                before,
                store.getAllTransactions()
            )
        }

    @Test
    fun commitImportReview_wholeCombinationGroupCancelled_succeedsWithoutChanges() = runTest {
        val (store, repo) = newRepository()
        val timestamp = millisAt(2026, 11, 25, 19, 30, 0)
        val bankId = saveRecord(
            repo, timestamp, 11000L, TransactionType.EXPENSE,
            PaymentChannel.BANK, bankCard, "某商户"
        )
        val batch = repo.prepareImportReview(walletSplit(timestamp), "微信账单")
        assertEquals(2, batch.items.size)
        val cancelled = batch.items.map { it.copy(selected = false) }
        val before = store.getAllTransactions()

        repo.commitImportReview(cancelled)

        val after = store.getAllTransactions()
        assertEquals("Cancelling the whole group must not modify the repository", before, after)
        assertNotNull(
            "The original bank expense must remain after cancellation",
            after.firstOrNull { it.id == bankId }
        )
    }

    private suspend fun commitWalletWithdrawal(
        store: FakeLedgerStore,
        repo: LedgerRepository,
        timestampMillis: Long
    ): Long {
        repo.commitImportReview(
            repo.prepareImportReview(listOf(walletWithdrawal(timestampMillis)), "微信账单").items
        )
        return store.getAllTransactions().single().id
    }

    private suspend fun assertFeeSplitPartialCommitRejected(
        store: FakeLedgerStore,
        repo: LedgerRepository,
        items: List<ImportReviewItem>
    ) {
        val before = store.getAllTransactions()
        var rejected = false
        try {
            repo.commitImportReview(items)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(
            "A partially selected withdrawal fee group must be rejected with IllegalArgumentException",
            rejected
        )
        assertEquals(
            "Rejected partial commit must leave the repository unchanged",
            before,
            store.getAllTransactions()
        )
    }

    @Test
    fun commitImportReview_partialWithdrawalFeeSplit_neverSilentlyDeletesOriginalWithdrawal() =
        runTest {
            val timestamp = millisAt(2026, 11, 26, 9, 40, 0)

            run {
                val (store, repo) = newRepository()
                commitWalletWithdrawal(store, repo, timestamp)
                val batch = repo.prepareImportReview(
                    listOf(bankWithdrawalReceipt(timestamp)),
                    "银行流水"
                )
                val oneCancelled = batch.items.mapIndexed { index, item ->
                    if (index == 0) item.copy(selected = false) else item
                }
                assertFeeSplitPartialCommitRejected(store, repo, oneCancelled)
            }

            run {
                val (store, repo) = newRepository()
                commitWalletWithdrawal(store, repo, timestamp)
                val batch = repo.prepareImportReview(
                    listOf(bankWithdrawalReceipt(timestamp)),
                    "银行流水"
                )
                assertFeeSplitPartialCommitRejected(
                    store,
                    repo,
                    batch.items.drop(1)
                )
            }

            run {
                val (store, repo) = newRepository()
                commitWalletWithdrawal(store, repo, timestamp)
                val batch = repo.prepareImportReview(
                    listOf(bankWithdrawalReceipt(timestamp)),
                    "银行流水"
                )
                val oneAmountEdited = batch.items.mapIndexed { index, item ->
                    if (index == 0) {
                        item.copy(
                            record = item.record.copy(
                                amountCents = item.record.amountCents + 5L
                            ),
                            userEdited = true
                        )
                    } else {
                        item
                    }
                }
                assertFeeSplitPartialCommitRejected(store, repo, oneAmountEdited)
            }
        }

    @Test
    fun commitImportReview_wholeWithdrawalFeeGroupCancelled_succeedsWithoutChanges() = runTest {
        val (store, repo) = newRepository()
        val timestamp = millisAt(2026, 11, 26, 10, 10, 0)
        val walletId = commitWalletWithdrawal(store, repo, timestamp)
        val batch = repo.prepareImportReview(
            listOf(bankWithdrawalReceipt(timestamp)),
            "银行流水"
        )
        assertTrue("The fee group preview must produce review items", batch.items.isNotEmpty())
        val cancelled = batch.items.map { it.copy(selected = false) }
        val before = store.getAllTransactions()

        repo.commitImportReview(cancelled)

        val after = store.getAllTransactions()
        assertEquals("Cancelling the whole fee group must not modify the repository", before, after)
        assertNotNull(
            "The original withdrawal must remain after cancellation",
            after.firstOrNull { it.id == walletId }
        )
    }
}
