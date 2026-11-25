package com.momentledger.app.data.repository

import com.momentledger.app.domain.backup.LocalBackupCodec
import com.momentledger.app.domain.engine.AnalyticsCalculator
import com.momentledger.app.domain.engine.DeduplicationEngine
import com.momentledger.app.domain.model.CaptureSource
import com.momentledger.app.domain.model.Category
import com.momentledger.app.domain.model.CategoryType
import com.momentledger.app.domain.model.DefaultCategories
import com.momentledger.app.domain.model.HOUR_OF_DAY_UNKNOWN
import com.momentledger.app.domain.model.ImportDuplicateStatus
import com.momentledger.app.domain.model.ImportReviewItem
import com.momentledger.app.domain.model.MerchantRule
import com.momentledger.app.domain.model.ParsedPayment
import com.momentledger.app.domain.model.PaymentChannel
import com.momentledger.app.domain.model.RuleMatchType
import com.momentledger.app.domain.model.TransactionRecord
import com.momentledger.app.domain.model.TransactionType
import com.momentledger.app.testutil.FakeLedgerStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

class LedgerRepositoryTest {
    @Test
    fun restoringDefaultsReactivatesBuiltInCategoriesWithoutResurrectingDeletedCustomOnes() = runTest {
        val (store, repository) = newRepository()
        val food = DefaultCategories.all.first { it.id == "exp_food" }
        val custom = food.copy(id = "custom_cafe", name = "咖啡", isSystem = false)
        val archivedCustom = custom.copy(id = "custom_books", name = "书店", iconKey = "school")
        repository.archiveCategory(food.id)
        repository.saveCustomCategory(custom)
        repository.saveCustomCategory(archivedCustom)
        repository.archiveCategory(archivedCustom.id)
        repository.restoreDefaultCategories(listOf("local_cafe", "school"))
        val restored = store.getAllCategories().associateBy { it.id }
        assertFalse(restored.getValue(food.id).isArchived)
        assertEquals("local_cafe", restored.getValue(food.id).iconKey)
        assertEquals(custom.iconKey, restored.getValue(custom.id).iconKey)
        assertTrue(restored.getValue(archivedCustom.id).isArchived)
    }


    @Test
    fun customChannelsStayDistinctInReportsCsvAndBackup() = runTest {
        val (store, repository) = newRepository()
        val first = transactionRecord(millisAt(2026, 11, 19, 10, 0, 0), 500L,
            TransactionType.EXPENSE, PaymentChannel.OTHER, "", "早餐").copy(customChannel = " 饭卡 ")
        repository.saveManualTransaction(first)
        repository.saveManualTransaction(first.copy(customChannel = "家用现金", amountCents = 200L))
        val channels = AnalyticsCalculator.computeChannelBreakdown(store.getAllTransactions())
        assertEquals(listOf("饭卡", "家用现金"), channels.map { it.label })
        assertTrue(repository.exportCsv().contains(",饭卡,"))
        val (restored, target) = newRepository()
        target.restoreBackupJson(repository.exportBackupJson())
        assertEquals(setOf("饭卡", "家用现金"), restored.getAllTransactions().map { it.customChannel }.toSet())
    }

    @Test
    fun customCategoryRejectsIconAlreadyAssignedToOtherDirection() = runTest {
        val (store, repository) = newRepository()
        val category = Category("custom_coffee", "咖啡", CategoryType.EXPENSE, "local_cafe", "#007AFF", 0, false)
        repository.saveCustomCategory(category)
        val error = runCatching {
            repository.saveCustomCategory(category.copy(id = "custom_rewards", name = "奖励", categoryType = CategoryType.INCOME))
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertNotNull(store.getCategoryById("custom_coffee"))
        assertNull(store.getCategoryById("custom_rewards"))
    }

    @Test
    fun manuallyEnteredRefundRetainsIncomeCategoryThroughBackup() = runTest {
        val (_, repository) = newRepository()
        val record = transactionRecord(millisAt(2026, 11, 19, 10, 0, 0), 500L,
            TransactionType.REFUND, PaymentChannel.MANUAL, "", "退款")
            .copy(categoryId = DefaultCategories.REFUND_CATEGORY_ID)
        val id = repository.saveManualTransaction(record)
        assertEquals(DefaultCategories.REFUND_CATEGORY_ID, repository.getTransactionById(id)?.categoryId)
        val (restored, target) = newRepository()
        target.restoreBackupJson(repository.exportBackupJson())
        val persisted = restored.getAllTransactions().single()
        assertEquals(TransactionType.REFUND, persisted.type)
        assertEquals(DefaultCategories.REFUND_CATEGORY_ID, persisted.categoryId)
    }

    private val shanghaiZone: ZoneId = ZoneId.of("Asia/Shanghai")

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

    private fun newRepository(
        store: FakeLedgerStore = FakeLedgerStore()
    ): Pair<FakeLedgerStore, LedgerRepository> {
        val engine = DeduplicationEngine(zoneId = shanghaiZone)
        return store to LedgerRepository(store = store, dedupEngine = engine, zoneId = shanghaiZone)
    }

    @Test
    fun deleteCustomCategory_reassignsTransactionsAndLearnedRulesToFallbackAndRefusesSystemFallback() =
        runTest {
            val (store, repo) = newRepository()

            val customExpense = Category(
                id = "custom_pet",
                name = "宠物",
                categoryType = CategoryType.EXPENSE,
                iconKey = "pets",
                colorHex = "#FF9500",
                sortOrder = 20,
                isSystem = false
            )
            val customIncome = Category(
                id = "custom_side",
                name = "副业",
                categoryType = CategoryType.INCOME,
                iconKey = "work",
                colorHex = "#34C759",
                sortOrder = 10,
                isSystem = false
            )
            repo.saveCustomCategory(customExpense)
            repo.saveCustomCategory(customIncome)

            val t0 = millisAt(2026, 11, 19, 10, 15, 0)
            val expIngest = repo.ingestRealTimePayment(
                ParsedPayment(
                    channel = PaymentChannel.WECHAT,
                    fundingMethod = "零钱",
                    type = TransactionType.EXPENSE,
                    amountCents = 8800L,
                    merchant = "萌宠乐园",
                    timestampMillis = t0,
                    source = CaptureSource.ACCESSIBILITY
                )
            )
            assertTrue(expIngest is IngestResult.Inserted)
            val expId = (expIngest as IngestResult.Inserted).record.id
            repo.updateCategoryAndLearn(expId, "custom_pet")

            val incId = repo.saveManualTransaction(
                TransactionRecord(
                    id = 0L,
                    timestampMillis = t0 + 60_000L,
                    localDate = "2026-11-19",
                    hourOfDay = 10,
                    amountCents = 50000L,
                    type = TransactionType.INCOME,
                    channel = PaymentChannel.BANK,
                    fundingMethod = "招商银行",
                    merchant = "设计外包客户",
                    categoryId = "custom_side",
                    note = "",
                    excludedFromStats = false,
                    captureSource = CaptureSource.MANUAL,
                    externalOrderId = null,
                    createdAtMillis = t0,
                    updatedAtMillis = t0
                )
            )
            repo.updateCategoryAndLearn(incId, "custom_side")

            // Built-in fallbacks and system categories cannot be deleted
            assertFalse(repo.deleteCustomCategory(DefaultCategories.EXPENSE_OTHER_ID))
            assertFalse(repo.deleteCustomCategory(DefaultCategories.INCOME_OTHER_ID))
            assertFalse(repo.deleteCustomCategory("exp_food"))
            assertNotNull(store.getCategoryById(DefaultCategories.EXPENSE_OTHER_ID))
            assertNotNull(store.getCategoryById(DefaultCategories.INCOME_OTHER_ID))

            // Deleting custom categories reassigns transactions and learned rules cleanly
            assertTrue(repo.deleteCustomCategory("custom_pet"))
            assertTrue(repo.deleteCustomCategory("custom_side"))

            assertNull(store.getCategoryById("custom_pet"))
            assertNull(store.getCategoryById("custom_side"))

            assertEquals(
                DefaultCategories.EXPENSE_OTHER_ID,
                repo.getTransactionById(expId)?.categoryId
            )
            assertEquals(
                DefaultCategories.INCOME_OTHER_ID,
                repo.getTransactionById(incId)?.categoryId
            )

            val exactExpenseRule = store.snapshotRules().firstOrNull {
                it.matchType == RuleMatchType.EXACT_USER && it.pattern == "萌宠乐园"
            }
            val exactIncomeRule = store.snapshotRules().firstOrNull {
                it.matchType == RuleMatchType.EXACT_USER && it.pattern == "设计外包客户"
            }
            assertEquals(DefaultCategories.EXPENSE_OTHER_ID, exactExpenseRule?.categoryId)
            assertEquals(DefaultCategories.INCOME_OTHER_ID, exactIncomeRule?.categoryId)
        }

    @Test
    fun importBillStream_mergesIntoBankFallbackAndUpgradesCategoryOnMerchantEnrichment() =
        runTest {
            val (_, repo) = newRepository()

            val t0 = millisAt(2026, 11, 19, 14, 20, 0)
            val bankCapture = repo.ingestRealTimePayment(
                ParsedPayment(
                    channel = PaymentChannel.BANK,
                    fundingMethod = "招商银行",
                    type = TransactionType.EXPENSE,
                    amountCents = 12800L,
                    merchant = "招商银行",
                    timestampMillis = t0,
                    source = CaptureSource.NOTIFICATION
                )
            )
            assertTrue(bankCapture is IngestResult.Inserted)
            val insertedBank = (bankCapture as IngestResult.Inserted).record
            assertEquals(DefaultCategories.EXPENSE_OTHER_ID, insertedBank.categoryId)

            val wechatCsv = buildString {
                append('\uFEFF')
                appendLine("微信支付账单明细,,,,,,,,")
                appendLine("交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注")
                appendLine("2026-11-19 14:21:15,商户消费,山姆会员商店,山姆商品,支出,¥128.00,招商银行储蓄卡,支付成功,4200002400123456,M1001,/")
            }

            val summary = repo.importBillStream(
                fileName = "微信支付账单.csv",
                stream = ByteArrayInputStream(wechatCsv.toByteArray(StandardCharsets.UTF_8))
            )

            assertEquals(0, summary.insertedCount)
            assertEquals(1, summary.mergedCount)
            assertEquals(0, summary.skippedCount)
            assertEquals("WECHAT_CSV", summary.detectedFormat)

            val updated = repo.getTransactionById(insertedBank.id)
            assertNotNull(updated)
            assertEquals(PaymentChannel.WECHAT, updated?.channel)
            assertEquals("4200002400123456", updated?.externalOrderId)
            assertEquals("山姆会员商店", updated?.merchant)
            // Enriching merchant from "招商银行" -> "山姆会员商店" automatically upgrades category from exp_other -> exp_daily
            assertEquals("exp_daily", updated?.categoryId)

            // Re-importing the exact same file skips by externalOrderId without duplicating
            val secondImport = repo.importBillStream(
                fileName = "微信支付账单.csv",
                stream = ByteArrayInputStream(wechatCsv.toByteArray(StandardCharsets.UTF_8))
            )
            assertEquals(0, secondImport.insertedCount)
            assertEquals(0, secondImport.mergedCount)
            assertEquals(1, secondImport.skippedCount)
        }

    @Test
    fun ingestRealTimePayment_bankThenWalletEnrichesMerchantAndUpgradesFallbackCategory() =
        runTest {
            val (_, repo) = newRepository()

            val t0 = millisAt(2026, 11, 19, 8, 30, 0)
            val bankFirst = repo.ingestRealTimePayment(
                ParsedPayment(
                    channel = PaymentChannel.BANK,
                    fundingMethod = "招商银行",
                    type = TransactionType.EXPENSE,
                    amountCents = 1690L,
                    merchant = "招商银行",
                    timestampMillis = t0,
                    source = CaptureSource.NOTIFICATION
                )
            )
            assertTrue(bankFirst is IngestResult.Inserted)
            val bankRecord = (bankFirst as IngestResult.Inserted).record
            assertEquals(DefaultCategories.EXPENSE_OTHER_ID, bankRecord.categoryId)

            val walletSecond = repo.ingestRealTimePayment(
                ParsedPayment(
                    channel = PaymentChannel.WECHAT,
                    fundingMethod = "零钱",
                    type = TransactionType.EXPENSE,
                    amountCents = 1690L,
                    merchant = "瑞幸咖啡",
                    timestampMillis = t0 + 45_000L,
                    source = CaptureSource.ACCESSIBILITY
                )
            )
            assertTrue(walletSecond is IngestResult.Merged)
            val merged = (walletSecond as IngestResult.Merged).record
            assertEquals(bankRecord.id, merged.id)
            assertEquals(PaymentChannel.WECHAT, merged.channel)
            assertEquals("招商银行", merged.fundingMethod)
            assertEquals("瑞幸咖啡", merged.merchant)
            assertEquals("exp_food", merged.categoryId)
        }

    @Test
    fun updateCategoryAndLearn_teachesExactUserRuleForFutureMatches() = runTest {
        val (_, repo) = newRepository()

        val t0 = millisAt(2026, 11, 19, 12, 0, 0)
        val first = repo.ingestRealTimePayment(
            ParsedPayment(
                channel = PaymentChannel.ALIPAY,
                fundingMethod = "花呗",
                type = TransactionType.EXPENSE,
                amountCents = 2500L,
                merchant = "星光小店",
                timestampMillis = t0,
                source = CaptureSource.ACCESSIBILITY
            )
        ) as IngestResult.Inserted
        assertEquals(DefaultCategories.EXPENSE_OTHER_ID, first.record.categoryId)

        repo.updateCategoryAndLearn(first.record.id, "exp_food")
        assertEquals("exp_food", repo.getTransactionById(first.record.id)?.categoryId)

        val second = repo.ingestRealTimePayment(
            ParsedPayment(
                channel = PaymentChannel.ALIPAY,
                fundingMethod = "花呗",
                type = TransactionType.EXPENSE,
                amountCents = 3200L,
                merchant = "星光小店",
                timestampMillis = t0 + 300_000L,
                source = CaptureSource.ACCESSIBILITY
            )
        ) as IngestResult.Inserted
        assertEquals("exp_food", second.record.categoryId)

        // Learned EXPENSE rule must not bleed into INCOME
        val incomeSameName = repo.ingestRealTimePayment(
            ParsedPayment(
                channel = PaymentChannel.ALIPAY,
                fundingMethod = "余额",
                type = TransactionType.INCOME,
                amountCents = 1000L,
                merchant = "星光小店",
                timestampMillis = t0 + 600_000L,
                source = CaptureSource.NOTIFICATION
            )
        ) as IngestResult.Inserted
        assertEquals(DefaultCategories.INCOME_OTHER_ID, incomeSameName.record.categoryId)
    }

    @Test
    fun exportBackupJson_clearStore_restoreBackupJson_restoresCategoriesRulesAndTransactions() =
        runTest {
            val (store, repo) = newRepository()

            val customCategory = Category(
                id = "custom_tea",
                name = "茶饮甜品",
                categoryType = CategoryType.EXPENSE,
                iconKey = "local_cafe",
                colorHex = "#AF52DE",
                sortOrder = 99,
                isSystem = false
            )
            repo.saveCustomCategory(customCategory)

            val t0 = millisAt(2026, 11, 19, 15, 45, 0)
            val txId = repo.saveManualTransaction(
                TransactionRecord(
                    id = 0L,
                    timestampMillis = t0,
                    localDate = "2026-11-19",
                    hourOfDay = 15,
                    amountCents = 2800L,
                    type = TransactionType.EXPENSE,
                    channel = PaymentChannel.WECHAT,
                    fundingMethod = "招商银行",
                    merchant = "街头手作糖水",
                    categoryId = "custom_tea",
                    note = "帮同事垫付",
                    excludedFromStats = true,
                    captureSource = CaptureSource.MANUAL,
                    externalOrderId = "ORD-BACKUP-1",
                    createdAtMillis = t0,
                    updatedAtMillis = t0
                )
            )
            repo.updateCategoryAndLearn(txId, "custom_tea")

            val backupJson = repo.exportBackupJson()

            store.clearAll()
            assertTrue(store.getAllTransactions().isEmpty())
            assertTrue(store.getAllCategories().isEmpty())
            assertTrue(store.snapshotRules().isEmpty())

            val restoredCount = repo.restoreBackupJson(backupJson)
            assertEquals(1, restoredCount)

            assertNotNull(store.getCategoryById("custom_tea"))
            val restoredTx = repo.getTransactionById(txId)
            assertNotNull(restoredTx)
            assertEquals("custom_tea", restoredTx?.categoryId)
            assertEquals("招商银行", restoredTx?.fundingMethod)
            assertEquals("ORD-BACKUP-1", restoredTx?.externalOrderId)
            assertTrue(restoredTx?.excludedFromStats == true)
            assertEquals("帮同事垫付", restoredTx?.note)

            // Learned rule survived restore and works for future payments
            val nextTx = repo.ingestRealTimePayment(
                ParsedPayment(
                    channel = PaymentChannel.WECHAT,
                    fundingMethod = "零钱",
                    type = TransactionType.EXPENSE,
                    amountCents = 1900L,
                    merchant = "街头手作糖水",
                    timestampMillis = t0 + 300_000L,
                    source = CaptureSource.ACCESSIBILITY
                )
            ) as IngestResult.Inserted
            assertEquals("custom_tea", nextTx.record.categoryId)
        }

    @Test
    fun repositoryUtilities_sortOrdersCsvDebugLogsAndDateRangeQueries() = runTest {
        val (_, repo) = newRepository()

        repo.updateCategorySortOrders(listOf("exp_shopping", "exp_food"))
        val expenseCategories = repo.getCategoriesByType(CategoryType.EXPENSE)
        assertEquals("exp_shopping", expenseCategories[0].id)
        assertEquals("exp_food", expenseCategories[1].id)

        val t1 = millisAt(2026, 11, 18, 22, 10, 0)
        val t2 = millisAt(2026, 11, 19, 9, 5, 0)
        val id1 = repo.saveManualTransaction(
            TransactionRecord(
                id = 0L,
                timestampMillis = t1,
                localDate = "2026-11-18",
                hourOfDay = 22,
                amountCents = 1500L,
                type = TransactionType.EXPENSE,
                channel = PaymentChannel.MANUAL,
                fundingMethod = "",
                merchant = "夜宵摊",
                categoryId = "exp_food",
                note = "",
                excludedFromStats = false,
                captureSource = CaptureSource.MANUAL,
                externalOrderId = "   ",
                createdAtMillis = t1,
                updatedAtMillis = t1
            )
        )
        val id2 = repo.saveManualTransaction(
            TransactionRecord(
                id = 0L,
                timestampMillis = t2,
                localDate = "2026-11-19",
                hourOfDay = 9,
                amountCents = 1800L,
                type = TransactionType.EXPENSE,
                channel = PaymentChannel.ALIPAY,
                fundingMethod = "余额",
                merchant = "瑞幸咖啡",
                categoryId = "exp_food",
                note = "",
                excludedFromStats = false,
                captureSource = CaptureSource.MANUAL,
                externalOrderId = null,
                createdAtMillis = t2,
                updatedAtMillis = t2
            )
        )

        // Blank externalOrderId normalized to null
        assertNull(repo.getTransactionById(id1)?.externalOrderId)

        val day19Only = repo.observeTransactionsInDateRange("2026-11-19", "2026-11-19").first()
        assertEquals(listOf(id2), day19Only.map { it.id })

        val csv = repo.exportCsv()
        assertTrue(csv.startsWith("\uFEFF"))
        assertTrue(csv.contains("餐饮"))
        assertTrue(csv.contains("瑞幸咖啡"))

        repo.setExcludedFromStats(id2, true)
        assertTrue(repo.getTransactionById(id2)?.excludedFromStats == true)

        repo.deleteTransaction(id1)
        assertNull(repo.getTransactionById(id1))

        repeat(60) { idx ->
            repo.recordDebugLog("ACCESSIBILITY", "com.tencent.mm", idx % 2 == 0, "event-$idx")
        }
        val logs = repo.observeDebugLogs().first()
        assertEquals(50, logs.size)

        repo.clearDebugLogs()
        assertTrue(repo.observeDebugLogs().first().isEmpty())
    }

    @Test
    fun ensureSeeded_populatesEmptyStoreIdempotentlyAndUpdateTransactionRespectsLearnFlag() =
        runTest {
            val emptyStore = FakeLedgerStore(seedDefaults = false)
            assertTrue(emptyStore.getAllCategories().isEmpty())
            assertTrue(emptyStore.snapshotRules().isEmpty())

            val (_, repo) = newRepository(emptyStore)
            val t0 = millisAt(2026, 11, 19, 11, 0, 0)

            // First operation lazily seeds defaults idempotently
            val inserted = repo.ingestRealTimePayment(
                ParsedPayment(
                    channel = PaymentChannel.WECHAT,
                    fundingMethod = "零钱",
                    type = TransactionType.EXPENSE,
                    amountCents = 2200L,
                    merchant = "云端小馆",
                    timestampMillis = t0,
                    source = CaptureSource.ACCESSIBILITY
                )
            ) as IngestResult.Inserted

            assertEquals(DefaultCategories.all.size, emptyStore.getAllCategories().size)
            assertEquals(DefaultCategories.builtInRules.size, emptyStore.snapshotRules().size)
            assertEquals(DefaultCategories.EXPENSE_OTHER_ID, inserted.record.categoryId)

            // Updating with learnMerchantCategory = false updates record without creating a rule
            repo.updateTransaction(
                record = inserted.record.copy(categoryId = "exp_social"),
                learnMerchantCategory = false
            )
            assertEquals("exp_social", repo.getTransactionById(inserted.record.id)?.categoryId)
            assertFalse(
                emptyStore.snapshotRules().any {
                    it.matchType == RuleMatchType.EXACT_USER && it.pattern == "云端小馆"
                }
            )

            // Updating with learnMerchantCategory = true learns the rule
            repo.updateTransaction(
                record = inserted.record.copy(categoryId = "exp_food"),
                learnMerchantCategory = true
            )
            assertTrue(
                emptyStore.snapshotRules().any {
                    it.matchType == RuleMatchType.EXACT_USER &&
                        it.pattern == "云端小馆" &&
                        it.categoryId == "exp_food"
                }
            )

            // Merging a richer merchant into a record with a non-fallback category preserves existing categoryId
            val t1 = millisAt(2026, 11, 19, 19, 0, 0)
            val preTaggedId = repo.saveManualTransaction(
                TransactionRecord(
                    id = 0L,
                    timestampMillis = t1,
                    localDate = "2026-11-19",
                    hourOfDay = 19,
                    amountCents = 4500L,
                    type = TransactionType.EXPENSE,
                    channel = PaymentChannel.BANK,
                    fundingMethod = "招商银行",
                    merchant = "招商银行",
                    categoryId = "exp_social",
                    note = "",
                    excludedFromStats = false,
                    captureSource = CaptureSource.NOTIFICATION,
                    externalOrderId = null,
                    createdAtMillis = t1,
                    updatedAtMillis = t1
                )
            )
            val merged = repo.ingestRealTimePayment(
                ParsedPayment(
                    channel = PaymentChannel.WECHAT,
                    fundingMethod = "零钱",
                    type = TransactionType.EXPENSE,
                    amountCents = 4500L,
                    merchant = "瑞幸咖啡",
                    timestampMillis = t1 + 30_000L,
                    source = CaptureSource.ACCESSIBILITY
                )
            ) as IngestResult.Merged
            assertEquals(preTaggedId, merged.record.id)
            assertEquals("瑞幸咖啡", merged.record.merchant)
            assertEquals("exp_social", merged.record.categoryId)
        }

    @Test
    fun updateCategoryAndLearn_onBankFallbackOrGatewayName_updatesRecordWithoutCreatingRule() =
        runTest {
            val (store, repo) = newRepository()

            val t0 = millisAt(2026, 11, 19, 9, 10, 0)
            val bankIngest = repo.ingestRealTimePayment(
                ParsedPayment(
                    channel = PaymentChannel.BANK,
                    fundingMethod = "招商银行",
                    type = TransactionType.EXPENSE,
                    amountCents = 3500L,
                    merchant = "招商银行",
                    timestampMillis = t0,
                    source = CaptureSource.NOTIFICATION
                )
            ) as IngestResult.Inserted
            assertEquals(DefaultCategories.EXPENSE_OTHER_ID, bankIngest.record.categoryId)

            repo.updateCategoryAndLearn(bankIngest.record.id, "exp_food")
            assertEquals("exp_food", repo.getTransactionById(bankIngest.record.id)?.categoryId)
            assertFalse(
                store.snapshotRules().any {
                    it.matchType == RuleMatchType.EXACT_USER && it.pattern == "招商银行"
                }
            )

            // Subsequent unparsed bank notification must still fall back to EXPENSE_OTHER_ID
            val nextBankIngest = repo.ingestRealTimePayment(
                ParsedPayment(
                    channel = PaymentChannel.BANK,
                    fundingMethod = "招商银行",
                    type = TransactionType.EXPENSE,
                    amountCents = 4200L,
                    merchant = "招商银行",
                    timestampMillis = t0 + 300_000L,
                    source = CaptureSource.NOTIFICATION
                )
            ) as IngestResult.Inserted
            assertEquals(DefaultCategories.EXPENSE_OTHER_ID, nextBankIngest.record.categoryId)

            // Gateway labels also do not create learned rules
            val gatewayIngest = repo.ingestRealTimePayment(
                ParsedPayment(
                    channel = PaymentChannel.UNIONPAY,
                    fundingMethod = "银联",
                    type = TransactionType.EXPENSE,
                    amountCents = 1900L,
                    merchant = "云闪付支付",
                    timestampMillis = t0 + 600_000L,
                    source = CaptureSource.NOTIFICATION
                )
            ) as IngestResult.Inserted
            repo.updateCategoryAndLearn(gatewayIngest.record.id, "exp_transport")
            assertEquals("exp_transport", repo.getTransactionById(gatewayIngest.record.id)?.categoryId)
            assertFalse(
                store.snapshotRules().any {
                    it.matchType == RuleMatchType.EXACT_USER && it.pattern == "云闪付支付"
                }
            )
        }

    @Test
    fun categoryTypeMismatch_fallsBackToCompatibleDefaultWithoutLearningCrossTypeRule() =
        runTest {
            val (store, repo) = newRepository()

            val t0 = millisAt(2026, 11, 19, 13, 0, 0)
            // 1. saveManualTransaction rejects EXPENSE categoryId on INCOME transaction
            val manualIncomeId = repo.saveManualTransaction(
                TransactionRecord(
                    id = 0L,
                    timestampMillis = t0,
                    localDate = "2026-11-19",
                    hourOfDay = 13,
                    amountCents = 10000L,
                    type = TransactionType.INCOME,
                    channel = PaymentChannel.MANUAL,
                    fundingMethod = "",
                    merchant = "跨类型测试商户",
                    categoryId = "exp_food",
                    note = "",
                    excludedFromStats = false,
                    captureSource = CaptureSource.MANUAL,
                    externalOrderId = null,
                    createdAtMillis = t0,
                    updatedAtMillis = t0
                )
            )
            assertEquals(
                DefaultCategories.INCOME_OTHER_ID,
                repo.getTransactionById(manualIncomeId)?.categoryId
            )

            // 2. updateTransaction switching EXPENSE -> INCOME with stale EXPENSE categoryId falls back without learning
            val expenseTx = repo.ingestRealTimePayment(
                ParsedPayment(
                    channel = PaymentChannel.ALIPAY,
                    fundingMethod = "余额",
                    type = TransactionType.EXPENSE,
                    amountCents = 2800L,
                    merchant = "类型切换商户",
                    timestampMillis = t0 + 60_000L,
                    source = CaptureSource.ACCESSIBILITY
                )
            ) as IngestResult.Inserted
            repo.updateTransaction(
                record = expenseTx.record.copy(
                    type = TransactionType.INCOME,
                    categoryId = "exp_shopping"
                ),
                learnMerchantCategory = true
            )
            assertEquals(
                DefaultCategories.INCOME_OTHER_ID,
                repo.getTransactionById(expenseTx.record.id)?.categoryId
            )
            assertFalse(
                store.snapshotRules().any {
                    it.matchType == RuleMatchType.EXACT_USER && it.pattern == "类型切换商户"
                }
            )

            // 3. updateCategoryAndLearn rejects INCOME categoryId on EXPENSE transaction without learning
            val anotherExpense = repo.ingestRealTimePayment(
                ParsedPayment(
                    channel = PaymentChannel.WECHAT,
                    fundingMethod = "零钱",
                    type = TransactionType.EXPENSE,
                    amountCents = 3300L,
                    merchant = "禁止串类商户",
                    timestampMillis = t0 + 120_000L,
                    source = CaptureSource.ACCESSIBILITY
                )
            ) as IngestResult.Inserted
            repo.updateCategoryAndLearn(anotherExpense.record.id, "inc_salary")
            assertEquals(
                DefaultCategories.EXPENSE_OTHER_ID,
                repo.getTransactionById(anotherExpense.record.id)?.categoryId
            )
            assertFalse(
                store.snapshotRules().any {
                    it.matchType == RuleMatchType.EXACT_USER && it.pattern == "禁止串类商户"
                }
            )

            // 4. restoreBackupJson sanitizes cross-type categoryIds in restored transactions
            val corruptedJson = repo.exportBackupJson()
                .replace(""""categoryId": "exp_other"""", """"categoryId": "inc_salary"""")
            store.clearAll()
            repo.restoreBackupJson(corruptedJson)
            assertEquals(
                DefaultCategories.EXPENSE_OTHER_ID,
                repo.getTransactionById(anotherExpense.record.id)?.categoryId
            )
        }

    @Test
    fun importBillStream_executesEntireBatchInsideSingleTransaction() = runTest {
        val (store, repo) = newRepository()

        val wechatCsv = buildString {
            append('\uFEFF')
            appendLine("微信支付账单明细,,,,,,,,")
            appendLine("交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注")
            appendLine("2026-11-19 08:30:00,商户消费,瑞幸咖啡,拿铁,支出,¥16.90,零钱,支付成功,TX_BATCH_1,M1,/")
            appendLine("2026-11-19 12:15:00,商户消费,全家便利店,午餐,支出,¥24.50,零钱,支付成功,TX_BATCH_2,M2,/")
            appendLine("2026-11-19 18:40:00,商户消费,滴滴出行,打车,支出,¥32.00,零钱,支付成功,TX_BATCH_3,M3,/")
        }

        assertEquals(0, store.transactionInvocationCount())
        val summary = repo.importBillStream(
            fileName = "wechat_batch.csv",
            stream = ByteArrayInputStream(wechatCsv.toByteArray(StandardCharsets.UTF_8))
        )

        assertEquals(3, summary.insertedCount)
        assertEquals(1, store.transactionInvocationCount())
        assertEquals(3, store.getAllTransactions().size)
    }

    @Test
    fun updateTransaction_onNonExistentId_doesNotCreateOrphanExactUserRule() = runTest {
        val (store, repo) = newRepository()

        val t0 = millisAt(2026, 11, 19, 16, 0, 0)
        val ghostRecord = TransactionRecord(
            id = 99999L,
            timestampMillis = t0,
            localDate = "2026-11-19",
            hourOfDay = 16,
            amountCents = 4200L,
            type = TransactionType.EXPENSE,
            channel = PaymentChannel.WECHAT,
            fundingMethod = "零钱",
            merchant = "幽灵商户",
            categoryId = "exp_food",
            note = "",
            excludedFromStats = false,
            captureSource = CaptureSource.MANUAL,
            externalOrderId = null,
            createdAtMillis = t0,
            updatedAtMillis = t0
        )

        repo.updateTransaction(ghostRecord, learnMerchantCategory = true)

        assertNull(repo.getTransactionById(99999L))
        assertFalse(
            store.snapshotRules().any {
                it.matchType == RuleMatchType.EXACT_USER && it.pattern == "幽灵商户"
            }
        )
    }

    @Test
    fun prepareImportReview_flagsIntraBatchDuplicatesFromOverlappingScreenshots() = runTest {
        val (_, repo) = newRepository()

        val t0 = millisAt(2026, 11, 19, 8, 30, 0)
        val firstScreenshot = ParsedPayment(
            channel = PaymentChannel.WECHAT,
            fundingMethod = "零钱",
            type = TransactionType.EXPENSE,
            amountCents = 1690L,
            merchant = "瑞幸咖啡",
            timestampMillis = t0,
            source = CaptureSource.OCR
        )
        val secondScreenshot = firstScreenshot.copy(timestampMillis = t0 + 10_000L)

        val batch = repo.prepareImportReview(
            items = listOf(firstScreenshot, secondScreenshot),
            sourceTitle = "微信账单截图"
        )

        assertEquals(2, batch.items.size)

        val firstItem = batch.items[0]
        assertEquals(ImportDuplicateStatus.NEW, firstItem.duplicateStatus)
        assertTrue(firstItem.selected)

        val secondItem = batch.items[1]
        assertEquals(ImportDuplicateStatus.DUPLICATE_SKIP, secondItem.duplicateStatus)
        assertFalse(secondItem.selected)
        assertTrue(secondItem.duplicateHint.contains("同批"))
    }

    @Test
    fun commitImportReview_checkedDuplicateSkipWithCollidingOrderId_clearsOrderIdAndInsertsWithoutCrash() =
        runTest {
            val (store, repo) = newRepository()

            val t0 = millisAt(2026, 11, 19, 9, 0, 0)
            val existing = repo.ingestRealTimePayment(
                ParsedPayment(
                    channel = PaymentChannel.WECHAT,
                    fundingMethod = "零钱",
                    type = TransactionType.EXPENSE,
                    amountCents = 5000L,
                    merchant = "星巴克",
                    timestampMillis = t0,
                    source = CaptureSource.ACCESSIBILITY,
                    externalOrderId = "WX123"
                )
            ) as IngestResult.Inserted
            assertEquals("WX123", existing.record.externalOrderId)

            val batch = repo.prepareImportReview(
                items = listOf(
                    ParsedPayment(
                        channel = PaymentChannel.WECHAT,
                        fundingMethod = "零钱",
                        type = TransactionType.EXPENSE,
                        amountCents = 5000L,
                        merchant = "星巴克",
                        timestampMillis = t0 + 20_000L,
                        source = CaptureSource.OCR,
                        externalOrderId = "WX123"
                    )
                ),
                sourceTitle = "微信账单截图"
            )
            val duplicateItem = batch.items.single()
            assertEquals(ImportDuplicateStatus.DUPLICATE_SKIP, duplicateItem.duplicateStatus)
            assertFalse(duplicateItem.selected)

            // User overrides the skip decision; the colliding official order id must be cleared
            // so the insert does not violate the unique externalOrderId constraint.
            val summary = repo.commitImportReview(
                listOf(duplicateItem.copy(selected = true))
            )

            assertEquals(1, summary.insertedCount)
            val all = store.getAllTransactions()
            assertEquals(2, all.size)
            val original = all.first { it.id == existing.record.id }
            assertEquals("WX123", original.externalOrderId)
            val inserted = all.first { it.id != existing.record.id }
            assertNull(inserted.externalOrderId)
        }

    @Test
    fun commitImportReview_checkedDuplicateMerge_respectsUserEditsAndLearnsCategory() = runTest {
        val (store, repo) = newRepository()

        val t0 = millisAt(2026, 11, 19, 12, 0, 0)
        val existingId = repo.saveManualTransaction(
            TransactionRecord(
                id = 0L,
                timestampMillis = t0,
                localDate = "2026-11-19",
                hourOfDay = 12,
                amountCents = 3150L,
                type = TransactionType.EXPENSE,
                channel = PaymentChannel.MANUAL,
                fundingMethod = "",
                merchant = "麦当劳",
                categoryId = "exp_other",
                note = "",
                excludedFromStats = false,
                captureSource = CaptureSource.MANUAL,
                externalOrderId = null,
                createdAtMillis = t0,
                updatedAtMillis = t0
            )
        )

        val batch = repo.prepareImportReview(
            items = listOf(
                ParsedPayment(
                    channel = PaymentChannel.OTHER,
                    fundingMethod = "美团",
                    type = TransactionType.EXPENSE,
                    amountCents = 3150L,
                    merchant = "麦当劳(高新店)",
                    timestampMillis = t0 + 60_000L,
                    source = CaptureSource.OCR,
                    note = "美团外卖"
                )
            ),
            sourceTitle = "美团订单截图"
        )
        val mergeItem = batch.items.single()
        assertEquals(ImportDuplicateStatus.DUPLICATE_MERGE, mergeItem.duplicateStatus)
        assertFalse(mergeItem.selected)
        assertEquals(existingId, mergeItem.matchedExistingId ?: 0L)

        // User accepts the merge but overrides the category.
        val editedItem = mergeItem.copy(
            selected = true,
            record = mergeItem.record.copy(categoryId = "exp_food"),
            userEdited = true,
            categoryManuallyChanged = true
        )
        val summary = repo.commitImportReview(listOf(editedItem))

        assertEquals(1, summary.mergedCount)
        assertEquals(1, store.getAllTransactions().size)
        val merged = store.getTransactionById(existingId)
        assertNotNull(merged)
        assertEquals("exp_food", merged?.categoryId)
        assertEquals("麦当劳(高新店)", merged?.merchant)
        assertTrue(
            store.getAllMerchantRules().any {
                it.matchType == RuleMatchType.EXACT_USER &&
                    it.pattern == "麦当劳(高新店)" &&
                    it.categoryId == "exp_food"
            }
        )
    }

    @Test
    fun dateOnlyImportAndManualSave_preservesHourOfDayUnknownAndExcludesFrom24hChart() = runTest {
        val (store, repo) = newRepository()

        val t0 = millisAt(2026, 11, 19, 18, 45, 0)
        val batch = repo.prepareImportReview(
            items = listOf(
                ParsedPayment(
                    channel = PaymentChannel.BANK,
                    fundingMethod = "招商银行",
                    type = TransactionType.EXPENSE,
                    amountCents = 12800L,
                    merchant = "山姆会员店",
                    timestampMillis = t0,
                    source = CaptureSource.OCR,
                    hasTimeOfDay = false
                )
            ),
            sourceTitle = "银行短信截图"
        )
        repo.commitImportReview(batch.items)

        val saved = store.getAllTransactions().single()
        assertEquals(HOUR_OF_DAY_UNKNOWN, saved.hourOfDay)

        repo.updateTransaction(saved.copy(note = "补记备注"))
        val updated = store.getTransactionById(saved.id)
        assertNotNull(updated)
        assertEquals(HOUR_OF_DAY_UNKNOWN, updated?.hourOfDay)

        val all = store.getAllTransactions()
        val calendar = AnalyticsCalculator.computeMonthlyCalendar(
            transactions = all,
            yearMonth = YearMonth.parse(saved.localDate.substring(0, 7))
        )
        assertTrue(calendar.daysByDate.values.any { it.netAmountCents == 12800L })
        assertEquals(
            0L,
            AnalyticsCalculator.computeHourlyBuckets(all).sumOf { it.netAmountCents }
        )

        val csv = LocalBackupCodec.exportTransactionsCsv(
            transactions = all,
            categoryNames = mapOf("exp_daily" to "日用", "exp_other" to "其他支出"),
            zoneId = shanghaiZone
        )
        assertTrue(csv.contains(saved.localDate))
    }

    @Test
    fun commitImportReview_categoryOnlyEditOnDuplicateMerge_preservesExistingTimeAndRicherMerchant() =
        runTest {
            val (store, repo) = newRepository()

            val existingId = repo.saveManualTransaction(
                TransactionRecord(
                    id = 0L,
                    timestampMillis = millisAt(2026, 11, 20, 8, 30, 0),
                    localDate = "2026-11-20",
                    hourOfDay = 8,
                    amountCents = 1690L,
                    type = TransactionType.EXPENSE,
                    channel = PaymentChannel.WECHAT,
                    fundingMethod = "零钱",
                    merchant = "瑞幸咖啡(科技园店)",
                    categoryId = DefaultCategories.EXPENSE_OTHER_ID,
                    note = "",
                    excludedFromStats = false,
                    captureSource = CaptureSource.MANUAL,
                    externalOrderId = null,
                    createdAtMillis = millisAt(2026, 11, 20, 8, 30, 0),
                    updatedAtMillis = millisAt(2026, 11, 20, 8, 30, 0)
                )
            )

            val item = ImportReviewItem(
                draftId = "merge-cat-only",
                record = TransactionRecord(
                    id = 0L,
                    timestampMillis = millisAt(2026, 11, 20, 12, 0, 0),
                    localDate = "2026-11-20",
                    hourOfDay = HOUR_OF_DAY_UNKNOWN,
                    amountCents = 1690L,
                    type = TransactionType.EXPENSE,
                    channel = PaymentChannel.BANK,
                    fundingMethod = "招商银行",
                    merchant = "财付通",
                    categoryId = "exp_food",
                    note = "",
                    excludedFromStats = false,
                    captureSource = CaptureSource.OCR,
                    externalOrderId = "CMB_999",
                    createdAtMillis = millisAt(2026, 11, 20, 12, 0, 0),
                    updatedAtMillis = millisAt(2026, 11, 20, 12, 0, 0)
                ),
                selected = true,
                duplicateStatus = ImportDuplicateStatus.DUPLICATE_MERGE,
                matchedExistingId = existingId,
                userEdited = false,
                categoryManuallyChanged = true
            )

            val summary = repo.commitImportReview(listOf(item))

            assertEquals(1, summary.mergedCount)
            assertEquals(0, summary.insertedCount)

            val updated = store.getTransactionById(existingId)!!
            assertEquals(8, updated.hourOfDay)
            assertEquals(millisAt(2026, 11, 20, 8, 30, 0), updated.timestampMillis)
            assertEquals("瑞幸咖啡(科技园店)", updated.merchant)
            assertEquals("exp_food", updated.categoryId)
            assertEquals("CMB_999", updated.externalOrderId)

            assertTrue(
                store.getAllMerchantRules().any {
                    it.matchType == RuleMatchType.EXACT_USER &&
                        it.pattern == "瑞幸咖啡(科技园店)" &&
                        it.categoryId == "exp_food"
                }
            )
        }

    @Test
    fun prepareImportReview_timedIncomingMatchesExistingDateOnlyRecordSameDay() = runTest {
        val (_, repo) = newRepository()

        val existingId = repo.saveManualTransaction(
            TransactionRecord(
                id = 0L,
                timestampMillis = millisAt(2026, 11, 20, 12, 0, 0),
                localDate = "2026-11-20",
                hourOfDay = HOUR_OF_DAY_UNKNOWN,
                amountCents = 3650L,
                type = TransactionType.EXPENSE,
                channel = PaymentChannel.BANK,
                fundingMethod = "招商银行",
                merchant = "美团外卖",
                categoryId = "exp_food",
                note = "",
                excludedFromStats = false,
                captureSource = CaptureSource.MANUAL,
                externalOrderId = null,
                createdAtMillis = millisAt(2026, 11, 20, 12, 0, 0),
                updatedAtMillis = millisAt(2026, 11, 20, 12, 0, 0)
            )
        )

        val batch = repo.prepareImportReview(
            items = listOf(
                ParsedPayment(
                    channel = PaymentChannel.WECHAT,
                    fundingMethod = "零钱",
                    type = TransactionType.EXPENSE,
                    amountCents = 3650L,
                    merchant = "美团外卖",
                    timestampMillis = millisAt(2026, 11, 20, 19, 25, 0),
                    source = CaptureSource.OCR,
                    hasTimeOfDay = true
                )
            ),
            sourceTitle = "微信账单截图"
        )

        assertEquals(1, batch.items.size)
        assertEquals(ImportDuplicateStatus.DUPLICATE_MERGE, batch.items[0].duplicateStatus)
        assertEquals(existingId, batch.items[0].matchedExistingId)
        assertFalse(batch.items[0].selected)
    }

    @Test
    fun ensureSeeded_v1UpgradeSyncsMissingV2BuiltInRulesWithoutTouchingUserRules() = runTest {
        val store = FakeLedgerStore(seedDefaults = false)
        store.replaceAll(
            categories = DefaultCategories.all,
            merchantRules = listOf(
                MerchantRule(
                    id = 1L,
                    pattern = "瑞幸",
                    categoryType = CategoryType.EXPENSE,
                    matchType = RuleMatchType.KEYWORD_BUILTIN,
                    categoryId = "exp_food",
                    priority = 100
                ),
                MerchantRule(
                    id = 50L,
                    pattern = "小区早餐店",
                    categoryType = CategoryType.EXPENSE,
                    matchType = RuleMatchType.EXACT_USER,
                    categoryId = "exp_food",
                    priority = 1000
                )
            ),
            transactions = emptyList()
        )

        val (_, repo) = newRepository(store)
        repo.ensureSeeded()

        val rules = store.getAllMerchantRules()
        listOf("饿了么", "得物", "抖音商城", "朴朴超市").forEach { keyword ->
            assertTrue(
                "missing V2 built-in keyword rule: $keyword",
                rules.any {
                    it.pattern == keyword && it.matchType == RuleMatchType.KEYWORD_BUILTIN
                }
            )
        }

        val userRule = rules.firstOrNull { it.pattern == "小区早餐店" }
        assertNotNull(userRule)
        assertEquals(50L, userRule?.id)
        assertEquals(RuleMatchType.EXACT_USER, userRule?.matchType)
        assertEquals("exp_food", userRule?.categoryId)
    }

    @Test
    fun prepareAndCommitImportReview_reimportingCorrectedWeChatOrder_autoSelectsAndFixesMisclassifiedRefundToExpense() =
        runTest {
            val (store, repo) = newRepository()

            val existingId = repo.saveManualTransaction(
                TransactionRecord(
                    id = 0L,
                    timestampMillis = millisAt(2026, 11, 15, 16, 10, 16),
                    localDate = "2026-11-15",
                    hourOfDay = 16,
                    amountCents = 6258L,
                    type = TransactionType.REFUND,
                    channel = PaymentChannel.WECHAT,
                    fundingMethod = "零钱",
                    merchant = "美团",
                    categoryId = DefaultCategories.EXPENSE_OTHER_ID,
                    note = "美团订单-001",
                    excludedFromStats = false,
                    captureSource = CaptureSource.IMPORT,
                    externalOrderId = "WX_BUG_1",
                    createdAtMillis = millisAt(2026, 11, 15, 16, 10, 16),
                    updatedAtMillis = millisAt(2026, 11, 15, 16, 10, 16)
                )
            )

            val batch = repo.prepareImportReview(
                items = listOf(
                    ParsedPayment(
                        channel = PaymentChannel.WECHAT,
                        fundingMethod = "零钱",
                        type = TransactionType.EXPENSE,
                        amountCents = 6258L,
                        merchant = "美团",
                        timestampMillis = millisAt(2026, 11, 15, 16, 10, 16),
                        source = CaptureSource.IMPORT,
                        externalOrderId = "WX_BUG_1",
                        note = "美团订单-001"
                    )
                ),
                sourceTitle = "微信账单整理",
                dedupIntraBatch = false
            )

            assertEquals(1, batch.items.size)
            val reviewItem = batch.items.single()
            assertEquals(ImportDuplicateStatus.DUPLICATE_MERGE, reviewItem.duplicateStatus)
            assertEquals(existingId, reviewItem.matchedExistingId)
            assertTrue("Type correction merge must be auto-selected for one-tap repair", reviewItem.selected)
            assertTrue(reviewItem.duplicateHint.contains("修正"))

            val summary = repo.commitImportReview(batch.items)
            assertEquals(1, summary.mergedCount)
            assertEquals(0, summary.insertedCount)

            val repaired = store.getTransactionById(existingId)!!
            assertEquals(TransactionType.EXPENSE, repaired.type)
            assertEquals(6258L, repaired.amountCents)
        }

    @Test
    fun prepareImportReview_bankImportMatchesExistingAlipayExpenseSymmetrically_andPreservesRepeatedSameDayBonusesWhenIntraDedupDisabled() =
        runTest {
            val (_, repo) = newRepository()

            val alipayId = repo.saveManualTransaction(
                TransactionRecord(
                    id = 0L,
                    timestampMillis = millisAt(2026, 11, 19, 19, 53, 35),
                    localDate = "2026-11-19",
                    hourOfDay = 19,
                    amountCents = 500L,
                    type = TransactionType.EXPENSE,
                    channel = PaymentChannel.ALIPAY,
                    fundingMethod = "工商银行储蓄卡(7032)",
                    merchant = "北京畅世通达",
                    categoryId = "exp_food",
                    note = "智能货柜",
                    excludedFromStats = false,
                    captureSource = CaptureSource.IMPORT,
                    externalOrderId = "ALI_500",
                    createdAtMillis = millisAt(2026, 11, 19, 19, 53, 35),
                    updatedAtMillis = millisAt(2026, 11, 19, 19, 53, 35)
                )
            )
            val wxTopup1 = repo.saveManualTransaction(
                TransactionRecord(
                    id = 0L,
                    timestampMillis = millisAt(2026, 11, 16, 11, 32, 29),
                    localDate = "2026-11-16",
                    hourOfDay = 11,
                    amountCents = 601L,
                    type = TransactionType.TRANSFER,
                    channel = PaymentChannel.WECHAT,
                    fundingMethod = "招商银行储蓄卡(7205)",
                    merchant = "招商银行(7205)",
                    categoryId = DefaultCategories.EXPENSE_OTHER_ID,
                    note = "",
                    excludedFromStats = false,
                    captureSource = CaptureSource.IMPORT,
                    externalOrderId = "WX_TOPUP_1",
                    createdAtMillis = millisAt(2026, 11, 16, 11, 32, 29),
                    updatedAtMillis = millisAt(2026, 11, 16, 11, 32, 29)
                )
            )
            val wxTopup2 = repo.saveManualTransaction(
                TransactionRecord(
                    id = 0L,
                    timestampMillis = millisAt(2026, 11, 16, 11, 32, 39),
                    localDate = "2026-11-16",
                    hourOfDay = 11,
                    amountCents = 601L,
                    type = TransactionType.TRANSFER,
                    channel = PaymentChannel.WECHAT,
                    fundingMethod = "招商银行储蓄卡(7205)",
                    merchant = "招商银行(7205)",
                    categoryId = DefaultCategories.EXPENSE_OTHER_ID,
                    note = "",
                    excludedFromStats = false,
                    captureSource = CaptureSource.IMPORT,
                    externalOrderId = "WX_TOPUP_2",
                    createdAtMillis = millisAt(2026, 11, 16, 11, 32, 39),
                    updatedAtMillis = millisAt(2026, 11, 16, 11, 32, 39)
                )
            )

            val batch = repo.prepareImportReview(
                items = listOf(
                    ParsedPayment(
                        channel = PaymentChannel.BANK,
                        fundingMethod = "工商银行(7032)",
                        type = TransactionType.EXPENSE,
                        amountCents = 500L,
                        merchant = "支付宝支付科技有限公司",
                        timestampMillis = millisAt(2026, 11, 19, 19, 53, 36),
                        source = CaptureSource.IMPORT,
                        hasTimeOfDay = true
                    ),
                    ParsedPayment(
                        channel = PaymentChannel.BANK,
                        fundingMethod = "招商银行(7205)",
                        type = TransactionType.TRANSFER,
                        amountCents = 601L,
                        merchant = "快捷支付",
                        timestampMillis = millisAt(2026, 11, 16, 12, 0, 0),
                        source = CaptureSource.IMPORT,
                        hasTimeOfDay = false
                    ),
                    ParsedPayment(
                        channel = PaymentChannel.BANK,
                        fundingMethod = "招商银行(7205)",
                        type = TransactionType.TRANSFER,
                        amountCents = 601L,
                        merchant = "快捷支付",
                        timestampMillis = millisAt(2026, 11, 16, 12, 0, 0),
                        source = CaptureSource.IMPORT,
                        hasTimeOfDay = false
                    ),
                    ParsedPayment(
                        channel = PaymentChannel.BANK,
                        fundingMethod = "招商银行(7205)",
                        type = TransactionType.INCOME,
                        amountCents = 600L,
                        merchant = "一网通支付鼓励金",
                        timestampMillis = millisAt(2026, 11, 16, 12, 0, 0),
                        source = CaptureSource.IMPORT,
                        hasTimeOfDay = false
                    ),
                    ParsedPayment(
                        channel = PaymentChannel.BANK,
                        fundingMethod = "招商银行(7205)",
                        type = TransactionType.INCOME,
                        amountCents = 600L,
                        merchant = "一网通支付鼓励金",
                        timestampMillis = millisAt(2026, 11, 16, 12, 0, 0),
                        source = CaptureSource.IMPORT,
                        hasTimeOfDay = false
                    ),
                    ParsedPayment(
                        channel = PaymentChannel.BANK,
                        fundingMethod = "招商银行(7205)",
                        type = TransactionType.INCOME,
                        amountCents = 600L,
                        merchant = "一网通支付鼓励金",
                        timestampMillis = millisAt(2026, 11, 16, 12, 0, 0),
                        source = CaptureSource.IMPORT,
                        hasTimeOfDay = false
                    )
                ),
                sourceTitle = "银行 PDF 流水整理",
                dedupIntraBatch = false
            )

            assertEquals(6, batch.items.size)
            // Bank -5.00 matches existing Alipay -5.00 symmetrically and defaults unchecked
            assertEquals(ImportDuplicateStatus.DUPLICATE_SKIP, batch.items[0].duplicateStatus)
            assertEquals(alipayId, batch.items[0].matchedExistingId)
            assertFalse(batch.items[0].selected)

            // Two CMB date-only 6.01 transfers match the two distinct WeChat rows 1-to-1
            assertEquals(setOf(wxTopup1, wxTopup2), setOf(batch.items[1].matchedExistingId, batch.items[2].matchedExistingId))
            assertFalse(batch.items[1].selected)
            assertFalse(batch.items[2].selected)

            // Three identical same-day CMB +6.00 bonus rows ALL remain NEW & selected
            assertEquals(ImportDuplicateStatus.NEW, batch.items[3].duplicateStatus)
            assertEquals(ImportDuplicateStatus.NEW, batch.items[4].duplicateStatus)
            assertEquals(ImportDuplicateStatus.NEW, batch.items[5].duplicateStatus)
            assertTrue(batch.items[3].selected && batch.items[4].selected && batch.items[5].selected)
        }

    @Test
    fun prepareImportReview_dateOnlyBankExpense_doesNotMatchUnrelatedSameAmountWalletExpense() =
        runTest {
            val (_, repo) = newRepository()

            repo.saveManualTransaction(
                TransactionRecord(
                    id = 0L,
                    timestampMillis = millisAt(2026, 11, 16, 12, 1, 0),
                    localDate = "2026-11-16",
                    hourOfDay = 12,
                    amountCents = 1500L,
                    type = TransactionType.EXPENSE,
                    channel = PaymentChannel.WECHAT,
                    fundingMethod = "零钱",
                    merchant = "午餐B",
                    categoryId = "exp_food",
                    note = "",
                    excludedFromStats = false,
                    captureSource = CaptureSource.MANUAL,
                    externalOrderId = null,
                    createdAtMillis = millisAt(2026, 11, 16, 12, 1, 0),
                    updatedAtMillis = millisAt(2026, 11, 16, 12, 1, 0)
                )
            )

            val batch = repo.prepareImportReview(
                items = listOf(
                    ParsedPayment(
                        channel = PaymentChannel.BANK,
                        fundingMethod = "招商银行(7205)",
                        type = TransactionType.EXPENSE,
                        amountCents = 1500L,
                        merchant = "超市A",
                        timestampMillis = millisAt(2026, 11, 16, 12, 0, 0),
                        source = CaptureSource.IMPORT,
                        hasTimeOfDay = false
                    )
                ),
                sourceTitle = "银行 PDF 流水整理"
            )

            assertEquals(1, batch.items.size)
            val reviewItem = batch.items.single()
            assertEquals(ImportDuplicateStatus.NEW, reviewItem.duplicateStatus)
            assertNull(reviewItem.matchedExistingId)
            assertTrue(reviewItem.selected)
        }

    @Test
    fun prepareImportReview_exactOrderIdTypeMismatch_autoSelectsOnlyRefundToExpenseRepair() =
        runTest {
            val (_, repo) = newRepository()

            val refundId = repo.saveManualTransaction(
                TransactionRecord(
                    id = 0L,
                    timestampMillis = millisAt(2026, 11, 15, 16, 10, 16),
                    localDate = "2026-11-15",
                    hourOfDay = 16,
                    amountCents = 6258L,
                    type = TransactionType.REFUND,
                    channel = PaymentChannel.WECHAT,
                    fundingMethod = "零钱",
                    merchant = "美团",
                    categoryId = DefaultCategories.EXPENSE_OTHER_ID,
                    note = "",
                    excludedFromStats = false,
                    captureSource = CaptureSource.IMPORT,
                    externalOrderId = "WX_REPAIR_1",
                    createdAtMillis = millisAt(2026, 11, 15, 16, 10, 16),
                    updatedAtMillis = millisAt(2026, 11, 15, 16, 10, 16)
                )
            )
            val transferId = repo.saveManualTransaction(
                TransactionRecord(
                    id = 0L,
                    timestampMillis = millisAt(2026, 11, 15, 17, 0, 0),
                    localDate = "2026-11-15",
                    hourOfDay = 17,
                    amountCents = 8800L,
                    type = TransactionType.TRANSFER,
                    channel = PaymentChannel.BANK,
                    fundingMethod = "招商银行(7205)",
                    merchant = "测试用户",
                    categoryId = DefaultCategories.EXPENSE_OTHER_ID,
                    note = "",
                    excludedFromStats = false,
                    captureSource = CaptureSource.IMPORT,
                    externalOrderId = "WX_TRANSFER_1",
                    createdAtMillis = millisAt(2026, 11, 15, 17, 0, 0),
                    updatedAtMillis = millisAt(2026, 11, 15, 17, 0, 0)
                )
            )

            val repairBatch = repo.prepareImportReview(
                items = listOf(
                    ParsedPayment(
                        channel = PaymentChannel.WECHAT,
                        fundingMethod = "零钱",
                        type = TransactionType.EXPENSE,
                        amountCents = 6258L,
                        merchant = "美团",
                        timestampMillis = millisAt(2026, 11, 15, 16, 10, 16),
                        source = CaptureSource.IMPORT,
                        externalOrderId = "WX_REPAIR_1"
                    )
                ),
                sourceTitle = "微信账单整理"
            )
            val repairItem = repairBatch.items.single()
            assertEquals(ImportDuplicateStatus.DUPLICATE_MERGE, repairItem.duplicateStatus)
            assertEquals(refundId, repairItem.matchedExistingId)
            assertTrue("REFUND -> EXPENSE repair must be auto-selected", repairItem.selected)

            val mismatchBatch = repo.prepareImportReview(
                items = listOf(
                    ParsedPayment(
                        channel = PaymentChannel.WECHAT,
                        fundingMethod = "零钱",
                        type = TransactionType.EXPENSE,
                        amountCents = 8800L,
                        merchant = "测试用户",
                        timestampMillis = millisAt(2026, 11, 15, 17, 0, 0),
                        source = CaptureSource.IMPORT,
                        externalOrderId = "WX_TRANSFER_1"
                    )
                ),
                sourceTitle = "微信账单整理"
            )
            val mismatchItem = mismatchBatch.items.single()
            assertEquals(ImportDuplicateStatus.DUPLICATE_MERGE, mismatchItem.duplicateStatus)
            assertEquals(transferId, mismatchItem.matchedExistingId)
            assertFalse("Only REFUND -> EXPENSE repairs may auto-select", mismatchItem.selected)
        }

    @Test
    fun prepareImportReview_walletDetailEnrichesPlaceholderBankMerchant_butGenericMerchantNeverLearnsRule() =
        runTest {
            val (store, repo) = newRepository()

            val bankImportedId = repo.saveManualTransaction(
                TransactionRecord(
                    id = 0L,
                    timestampMillis = millisAt(2026, 11, 19, 18, 30, 0),
                    localDate = "2026-11-19",
                    hourOfDay = 18,
                    amountCents = 3200L,
                    type = TransactionType.EXPENSE,
                    channel = PaymentChannel.BANK,
                    fundingMethod = "招商银行(7205)",
                    merchant = "支付宝支付科技有限公司",
                    categoryId = DefaultCategories.EXPENSE_OTHER_ID,
                    note = "",
                    excludedFromStats = false,
                    captureSource = CaptureSource.IMPORT,
                    externalOrderId = null,
                    createdAtMillis = millisAt(2026, 11, 19, 18, 30, 0),
                    updatedAtMillis = millisAt(2026, 11, 19, 18, 30, 0)
                )
            )

            val batch = repo.prepareImportReview(
                items = listOf(
                    ParsedPayment(
                        channel = PaymentChannel.WECHAT,
                        fundingMethod = "零钱",
                        type = TransactionType.EXPENSE,
                        amountCents = 3200L,
                        merchant = "星巴克",
                        timestampMillis = millisAt(2026, 11, 19, 18, 31, 0),
                        source = CaptureSource.IMPORT,
                        hasTimeOfDay = true
                    )
                ),
                sourceTitle = "微信账单整理"
            )

            val reviewItem = batch.items.single()
            assertEquals(ImportDuplicateStatus.DUPLICATE_MERGE, reviewItem.duplicateStatus)
            assertEquals(bankImportedId, reviewItem.matchedExistingId)
            assertTrue(reviewItem.duplicateHint.contains("合并"))

            val summary = repo.commitImportReview(listOf(reviewItem.copy(selected = true)))
            assertEquals(1, summary.mergedCount)

            val enriched = store.getTransactionById(bankImportedId)!!
            assertEquals("星巴克", enriched.merchant)

            val genericId = repo.saveManualTransaction(
                TransactionRecord(
                    id = 0L,
                    timestampMillis = millisAt(2026, 11, 20, 10, 0, 0),
                    localDate = "2026-11-20",
                    hourOfDay = 10,
                    amountCents = 500L,
                    type = TransactionType.EXPENSE,
                    channel = PaymentChannel.ALIPAY,
                    fundingMethod = "花呗",
                    merchant = "支付宝支付科技有限公司",
                    categoryId = DefaultCategories.EXPENSE_OTHER_ID,
                    note = "",
                    excludedFromStats = false,
                    captureSource = CaptureSource.IMPORT,
                    externalOrderId = null,
                    createdAtMillis = millisAt(2026, 11, 20, 10, 0, 0),
                    updatedAtMillis = millisAt(2026, 11, 20, 10, 0, 0)
                )
            )
            repo.updateCategoryAndLearn(genericId, "exp_shopping")

            assertTrue(
                "Generic settlement merchants must never become learned exact-user rules",
                store.getAllMerchantRules().none {
                    it.pattern == "支付宝支付科技有限公司" &&
                        it.matchType == RuleMatchType.EXACT_USER
                }
            )
        }

    @Test
    fun prepareImportReview_dateOnlyCrossChannelDedup_recognizesExtendedBankNames() =
        runTest {
            val (_, repo) = newRepository()

            val alipayId = repo.saveManualTransaction(
                TransactionRecord(
                    id = 0L,
                    timestampMillis = millisAt(2026, 11, 18, 15, 20, 0),
                    localDate = "2026-11-18",
                    hourOfDay = 15,
                    amountCents = 2600L,
                    type = TransactionType.EXPENSE,
                    channel = PaymentChannel.ALIPAY,
                    fundingMethod = "兴业银行储蓄卡(1122)",
                    merchant = "美团外卖",
                    categoryId = "exp_food",
                    note = "",
                    excludedFromStats = false,
                    captureSource = CaptureSource.IMPORT,
                    externalOrderId = "ALI_XY_1",
                    createdAtMillis = millisAt(2026, 11, 18, 15, 20, 0),
                    updatedAtMillis = millisAt(2026, 11, 18, 15, 20, 0)
                )
            )

            val batch = repo.prepareImportReview(
                items = listOf(
                    ParsedPayment(
                        channel = PaymentChannel.BANK,
                        fundingMethod = "兴业银行(1122)",
                        type = TransactionType.EXPENSE,
                        amountCents = 2600L,
                        merchant = "永辉超市",
                        timestampMillis = millisAt(2026, 11, 18, 12, 0, 0),
                        source = CaptureSource.IMPORT,
                        hasTimeOfDay = false
                    )
                ),
                sourceTitle = "银行 PDF 流水整理"
            )

            val reviewItem = batch.items.single()
            assertEquals(ImportDuplicateStatus.DUPLICATE_SKIP, reviewItem.duplicateStatus)
            assertEquals(alipayId, reviewItem.matchedExistingId)
            assertFalse(reviewItem.selected)
        }

    @Test
    fun prepareImportReview_duplicateOrderIdWithinBatch_targetsExistingOnceAndSkipsSecondRow() =
        runTest {
            val (_, repo) = newRepository()

            val existingId = repo.saveManualTransaction(
                TransactionRecord(
                    id = 0L,
                    timestampMillis = millisAt(2026, 11, 20, 9, 30, 0),
                    localDate = "2026-11-20",
                    hourOfDay = 9,
                    amountCents = 1000L,
                    type = TransactionType.EXPENSE,
                    channel = PaymentChannel.WECHAT,
                    fundingMethod = "零钱",
                    merchant = "某商户A",
                    categoryId = "exp_food",
                    note = "",
                    excludedFromStats = false,
                    captureSource = CaptureSource.IMPORT,
                    externalOrderId = "DUP_BATCH_1",
                    createdAtMillis = millisAt(2026, 11, 20, 9, 30, 0),
                    updatedAtMillis = millisAt(2026, 11, 20, 9, 30, 0)
                )
            )

            val batch = repo.prepareImportReview(
                items = listOf(
                    ParsedPayment(
                        channel = PaymentChannel.WECHAT,
                        fundingMethod = "零钱",
                        type = TransactionType.EXPENSE,
                        amountCents = 1000L,
                        merchant = "某商户A",
                        timestampMillis = millisAt(2026, 11, 20, 9, 30, 0),
                        source = CaptureSource.IMPORT,
                        externalOrderId = "DUP_BATCH_1"
                    ),
                    ParsedPayment(
                        channel = PaymentChannel.WECHAT,
                        fundingMethod = "零钱",
                        type = TransactionType.EXPENSE,
                        amountCents = 1000L,
                        merchant = "某商户A",
                        timestampMillis = millisAt(2026, 11, 20, 9, 30, 0),
                        source = CaptureSource.IMPORT,
                        externalOrderId = "DUP_BATCH_1"
                    )
                ),
                sourceTitle = "微信账单整理",
                dedupIntraBatch = false
            )

            assertEquals(2, batch.items.size)
            val first = batch.items[0]
            val second = batch.items[1]

            assertEquals(ImportDuplicateStatus.DUPLICATE_SKIP, first.duplicateStatus)
            assertEquals(existingId, first.matchedExistingId)
            assertFalse(first.selected)

            assertEquals(ImportDuplicateStatus.DUPLICATE_SKIP, second.duplicateStatus)
            assertNull(second.matchedExistingId)
            assertTrue(second.duplicateHint.contains("同批重复单号"))
            assertFalse(second.selected)
        }

    private fun transactionRecord(
        timestampMillis: Long,
        amountCents: Long,
        type: TransactionType,
        channel: PaymentChannel,
        fundingMethod: String,
        merchant: String
    ): TransactionRecord {
        val zoned = java.time.Instant.ofEpochMilli(timestampMillis).atZone(shanghaiZone)
        return TransactionRecord(
            id = 0L,
            timestampMillis = timestampMillis,
            localDate = zoned.toLocalDate().toString(),
            hourOfDay = zoned.hour,
            amountCents = amountCents,
            type = type,
            channel = channel,
            fundingMethod = fundingMethod,
            merchant = merchant,
            categoryId = DefaultCategories.EXPENSE_OTHER_ID,
            note = "",
            excludedFromStats = false,
            captureSource = CaptureSource.IMPORT,
            externalOrderId = null,
            createdAtMillis = timestampMillis,
            updatedAtMillis = timestampMillis
        )
    }

    @Test
    fun prepareImportReview_combinedBankDebitMatchesSameBankSplitWallets_butNotDifferentFundingBank() =
        runTest {
            val (_, repo) = newRepository()

            val splitMillis = millisAt(2026, 9, 23, 21, 8, 0)
            repo.saveManualTransaction(
                transactionRecord(
                    timestampMillis = splitMillis,
                    amountCents = 555500L,
                    type = TransactionType.EXPENSE,
                    channel = PaymentChannel.ALIPAY,
                    fundingMethod = "工商银行储蓄卡(7032)",
                    merchant = "某商户"
                )
            )
            repo.saveManualTransaction(
                transactionRecord(
                    timestampMillis = splitMillis,
                    amountCents = 5490L,
                    type = TransactionType.EXPENSE,
                    channel = PaymentChannel.ALIPAY,
                    fundingMethod = "工商银行储蓄卡(7032)",
                    merchant = "某商户"
                )
            )

            val combinedBatch = repo.prepareImportReview(
                items = listOf(
                    ParsedPayment(
                        channel = PaymentChannel.BANK,
                        fundingMethod = "工商银行(7032)",
                        type = TransactionType.EXPENSE,
                        amountCents = 560990L,
                        merchant = "某商户",
                        timestampMillis = millisAt(2026, 9, 23, 21, 8, 13),
                        source = CaptureSource.IMPORT,
                        hasTimeOfDay = true
                    )
                ),
                sourceTitle = "银行 PDF 流水整理",
                dedupIntraBatch = false
            )

            val combinedItem = combinedBatch.items.single()
            assertEquals(ImportDuplicateStatus.DUPLICATE_SKIP, combinedItem.duplicateStatus)
            assertFalse(combinedItem.selected)
            assertTrue(combinedItem.duplicateHint.contains("合并支付"))

            // Same amount, but the wallet sub-orders were funded from a different bank ("零钱").
            val zeroChangeMillis = millisAt(2026, 9, 24, 10, 0, 0)
            repo.saveManualTransaction(
                transactionRecord(
                    timestampMillis = zeroChangeMillis,
                    amountCents = 555500L,
                    type = TransactionType.EXPENSE,
                    channel = PaymentChannel.ALIPAY,
                    fundingMethod = "零钱",
                    merchant = "某商户"
                )
            )
            repo.saveManualTransaction(
                transactionRecord(
                    timestampMillis = zeroChangeMillis,
                    amountCents = 5490L,
                    type = TransactionType.EXPENSE,
                    channel = PaymentChannel.ALIPAY,
                    fundingMethod = "零钱",
                    merchant = "某商户"
                )
            )

            val zeroChangeBatch = repo.prepareImportReview(
                items = listOf(
                    ParsedPayment(
                        channel = PaymentChannel.BANK,
                        fundingMethod = "工商银行(7032)",
                        type = TransactionType.EXPENSE,
                        amountCents = 560990L,
                        merchant = "某商户",
                        timestampMillis = millisAt(2026, 9, 24, 10, 0, 13),
                        source = CaptureSource.IMPORT,
                        hasTimeOfDay = true
                    )
                ),
                sourceTitle = "银行 PDF 流水整理",
                dedupIntraBatch = false
            )

            val zeroChangeItem = zeroChangeBatch.items.single()
            assertEquals(ImportDuplicateStatus.NEW, zeroChangeItem.duplicateStatus)
            assertTrue("Different funding bank must not be treated as a combined payment", zeroChangeItem.selected)
        }

    @Test
    fun prepareImportReview_withdrawalFeeTransferDedup_handlesTimedIcbcAndDateOnlyCmb() =
        runTest {
            val (store, repo) = newRepository()

            repo.saveManualTransaction(
                transactionRecord(
                    timestampMillis = millisAt(2026, 11, 14, 12, 36, 4),
                    amountCents = 100100L,
                    type = TransactionType.TRANSFER,
                    channel = PaymentChannel.WECHAT,
                    fundingMethod = "工商银行储蓄卡(7032)",
                    merchant = "微信提现"
                )
            )
            repo.saveManualTransaction(
                transactionRecord(
                    timestampMillis = millisAt(2026, 9, 23, 20, 10, 53),
                    amountCents = 1031030L,
                    type = TransactionType.TRANSFER,
                    channel = PaymentChannel.ALIPAY,
                    fundingMethod = "余额",
                    merchant = "中国工商银行"
                ).copy(note = "提现-实时提现")
            )
            repo.saveManualTransaction(
                transactionRecord(
                    timestampMillis = millisAt(2026, 11, 16, 11, 27, 48),
                    amountCents = 13013L,
                    type = TransactionType.TRANSFER,
                    channel = PaymentChannel.WECHAT,
                    fundingMethod = "招商银行储蓄卡(7205)",
                    merchant = "微信提现"
                )
            )

            val batch = repo.prepareImportReview(
                items = listOf(
                    ParsedPayment(
                        channel = PaymentChannel.BANK,
                        fundingMethod = "工商银行(7032)",
                        type = TransactionType.TRANSFER,
                        amountCents = 100000L,
                        merchant = "某商户",
                        note = "网联收款 · 资金方向：转入",
                        timestampMillis = millisAt(2026, 11, 14, 12, 36, 4),
                        source = CaptureSource.IMPORT,
                        hasTimeOfDay = true
                    ),
                    ParsedPayment(
                        channel = PaymentChannel.BANK,
                        fundingMethod = "工商银行(7032)",
                        type = TransactionType.TRANSFER,
                        amountCents = 1030000L,
                        merchant = "某商户",
                        note = "网联收款 · 资金方向：转入",
                        timestampMillis = millisAt(2026, 9, 23, 20, 10, 54),
                        source = CaptureSource.IMPORT,
                        hasTimeOfDay = true
                    ),
                    ParsedPayment(
                        channel = PaymentChannel.BANK,
                        fundingMethod = "招商银行(7205)",
                        type = TransactionType.TRANSFER,
                        amountCents = 13000L,
                        merchant = "某商户",
                        note = "网联收款 · 资金方向：转入",
                        timestampMillis = millisAt(2026, 11, 16, 12, 0, 0),
                        source = CaptureSource.IMPORT,
                        hasTimeOfDay = false
                    )
                ),
                sourceTitle = "银行 PDF 流水整理"
            )

            assertEquals(6, batch.items.size)
            assertTrue(batch.items.all { it.selected })
            assertEquals(3, batch.items.count { it.record.type == TransactionType.EXPENSE })
            repo.commitImportReview(batch.items)
            val records = store.getAllTransactions()
            assertEquals(1143000L, records.filter { it.type == TransactionType.TRANSFER }.sumOf { it.amountCents })
            assertEquals(1143L, records.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountCents })
        }

    @Test
    fun prepareImportReview_combinedPaymentDoesNotReuseWalletSubOrdersMatchedEarlierInBatch() =
        runTest {
            val (_, repo) = newRepository()

            val splitMillis = millisAt(2026, 9, 23, 21, 8, 0)
            repo.saveManualTransaction(
                transactionRecord(
                    timestampMillis = splitMillis,
                    amountCents = 555500L,
                    type = TransactionType.EXPENSE,
                    channel = PaymentChannel.ALIPAY,
                    fundingMethod = "工商银行储蓄卡(7032)",
                    merchant = "某商户"
                )
            )
            repo.saveManualTransaction(
                transactionRecord(
                    timestampMillis = splitMillis,
                    amountCents = 5490L,
                    type = TransactionType.EXPENSE,
                    channel = PaymentChannel.ALIPAY,
                    fundingMethod = "工商银行储蓄卡(7032)",
                    merchant = "某商户"
                )
            )

            val batch = repo.prepareImportReview(
                items = listOf(
                    ParsedPayment(
                        channel = PaymentChannel.BANK,
                        fundingMethod = "工商银行(7032)",
                        type = TransactionType.EXPENSE,
                        amountCents = 560990L,
                        merchant = "某商户",
                        timestampMillis = millisAt(2026, 9, 23, 21, 8, 13),
                        source = CaptureSource.IMPORT,
                        hasTimeOfDay = true
                    ),
                    ParsedPayment(
                        channel = PaymentChannel.BANK,
                        fundingMethod = "工商银行(7032)",
                        type = TransactionType.EXPENSE,
                        amountCents = 560990L,
                        merchant = "某商户",
                        timestampMillis = millisAt(2026, 9, 23, 21, 8, 15),
                        source = CaptureSource.IMPORT,
                        hasTimeOfDay = true
                    )
                ),
                sourceTitle = "银行 PDF 流水整理",
                dedupIntraBatch = false
            )

            assertEquals(2, batch.items.size)

            val first = batch.items[0]
            assertEquals(ImportDuplicateStatus.DUPLICATE_SKIP, first.duplicateStatus)
            assertFalse(first.selected)

            val second = batch.items[1]
            assertEquals(
                "A second bank debit must not reuse wallet orders already matched in this batch",
                ImportDuplicateStatus.NEW,
                second.duplicateStatus
            )
            assertTrue(second.selected)
        }
}
