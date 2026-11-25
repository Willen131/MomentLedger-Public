package com.momentledger.app.ui.review

import com.momentledger.app.data.repository.LedgerRepository
import com.momentledger.app.domain.engine.DeduplicationEngine
import com.momentledger.app.domain.model.CaptureSource
import com.momentledger.app.domain.model.DefaultCategories
import com.momentledger.app.domain.model.ImportCommitSummary
import com.momentledger.app.domain.model.ImportDuplicateStatus
import com.momentledger.app.domain.model.ImportReconciliation
import com.momentledger.app.domain.model.ImportReviewBatch
import com.momentledger.app.domain.model.ImportReviewItem
import com.momentledger.app.domain.model.ParsedPayment
import com.momentledger.app.domain.model.PaymentChannel
import com.momentledger.app.domain.model.RuleMatchType
import com.momentledger.app.domain.model.TransactionRecord
import com.momentledger.app.domain.model.TransactionType
import com.momentledger.app.testutil.FakeLedgerStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class ImportReviewViewModelTest {

    private val shanghaiZone: ZoneId = ZoneId.of("Asia/Shanghai")
    private lateinit var testDispatcher: TestDispatcher

    private val baseMillis: Long =
        ZonedDateTime.of(2026, 11, 19, 9, 30, 0, 0, shanghaiZone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createHarness(): Triple<FakeLedgerStore, LedgerRepository, ImportReviewViewModel> {
        val store = FakeLedgerStore()
        val repository = LedgerRepository(
            store = store,
            dedupEngine = DeduplicationEngine(zoneId = shanghaiZone),
            zoneId = shanghaiZone
        )
        val viewModel = ImportReviewViewModel(
            repository = repository,
            ioDispatcher = testDispatcher
        )
        return Triple(store, repository, viewModel)
    }

    private fun createRecord(
        merchant: String = "测试商户",
        type: TransactionType = TransactionType.EXPENSE,
        categoryId: String = if (type == TransactionType.INCOME) {
            DefaultCategories.INCOME_OTHER_ID
        } else {
            DefaultCategories.EXPENSE_OTHER_ID
        },
        amountCents: Long = 1000L,
        timestampMillis: Long = baseMillis
    ): TransactionRecord {
        val zoned = Instant.ofEpochMilli(timestampMillis).atZone(shanghaiZone)
        return TransactionRecord(
            id = 0L,
            timestampMillis = timestampMillis,
            localDate = zoned.toLocalDate().toString(),
            hourOfDay = zoned.hour,
            amountCents = amountCents,
            type = type,
            channel = PaymentChannel.WECHAT,
            fundingMethod = "零钱",
            merchant = merchant,
            categoryId = categoryId,
            note = "",
            excludedFromStats = false,
            captureSource = CaptureSource.IMPORT,
            externalOrderId = null,
            createdAtMillis = timestampMillis,
            updatedAtMillis = timestampMillis
        )
    }

    private fun createReviewItem(
        draftId: String,
        duplicateStatus: ImportDuplicateStatus,
        selected: Boolean,
        merchant: String = "商户-$draftId",
        type: TransactionType = TransactionType.EXPENSE,
        categoryId: String = if (type == TransactionType.INCOME) {
            DefaultCategories.INCOME_OTHER_ID
        } else {
            DefaultCategories.EXPENSE_OTHER_ID
        },
        amountCents: Long = 1000L,
        timestampMillis: Long = baseMillis
    ): ImportReviewItem = ImportReviewItem(
        draftId = draftId,
        record = createRecord(
            merchant = merchant,
            type = type,
            categoryId = categoryId,
            amountCents = amountCents,
            timestampMillis = timestampMillis
        ),
        selected = selected,
        duplicateStatus = duplicateStatus
    )

    private fun createBatch(vararg items: ImportReviewItem): ImportReviewBatch =
        ImportReviewBatch(sourceTitle = "测试导入", items = items.toList())

    @Test
    fun loadBatch_appliesFiltersAndCountsCorrectly() = runTest {
        val (_, _, viewModel) = createHarness()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        val item1 = createReviewItem(
            draftId = "draft-1",
            duplicateStatus = ImportDuplicateStatus.NEW,
            selected = true,
            merchant = "瑞幸咖啡",
            categoryId = "exp_food"
        )
        val item2 = createReviewItem(
            draftId = "draft-2",
            duplicateStatus = ImportDuplicateStatus.NEW,
            selected = true,
            merchant = "某未知商户",
            categoryId = DefaultCategories.EXPENSE_OTHER_ID
        )
        val item3 = createReviewItem(
            draftId = "draft-3",
            duplicateStatus = ImportDuplicateStatus.DUPLICATE_SKIP,
            selected = false,
            merchant = "京东商城",
            categoryId = "exp_shopping"
        )

        viewModel.loadBatch(createBatch(item1, item2, item3))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.batch)
        assertEquals(2, state.selectedCount)
        assertEquals(2, state.newCount)
        assertEquals(1, state.duplicateCount)
        assertEquals(1, state.uncategorizedCount)

        viewModel.setFilter(ImportReviewFilter.NEW_ONLY)
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.visibleItems.size)
        assertEquals(ImportReviewFilter.NEW_ONLY, viewModel.uiState.value.filter)

        viewModel.setFilter(ImportReviewFilter.DUPLICATES)
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.visibleItems.size)
        assertEquals("draft-3", viewModel.uiState.value.visibleItems.single().draftId)

        viewModel.setFilter(ImportReviewFilter.UNCATEGORIZED)
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.visibleItems.size)
        assertEquals("draft-2", viewModel.uiState.value.visibleItems.single().draftId)

        viewModel.setFilter(ImportReviewFilter.ALL)
        advanceUntilIdle()
        assertEquals(3, viewModel.uiState.value.visibleItems.size)

        collectJob.cancel()
    }

    @Test
    fun selectAllNew_checksOnlyNewAndUnchecksDuplicates() = runTest {
        val (_, _, viewModel) = createHarness()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        val newItem = createReviewItem(
            draftId = "draft-new",
            duplicateStatus = ImportDuplicateStatus.NEW,
            selected = false
        )
        val mergeItem = createReviewItem(
            draftId = "draft-merge",
            duplicateStatus = ImportDuplicateStatus.DUPLICATE_MERGE,
            selected = true
        )
        val skipItem = createReviewItem(
            draftId = "draft-skip",
            duplicateStatus = ImportDuplicateStatus.DUPLICATE_SKIP,
            selected = true
        )

        viewModel.loadBatch(createBatch(newItem, mergeItem, skipItem))
        advanceUntilIdle()
        viewModel.selectAllNew()
        advanceUntilIdle()

        val itemsById = viewModel.uiState.value.batch!!.items.associateBy { it.draftId }
        assertTrue(itemsById.getValue("draft-new").selected)
        assertFalse(itemsById.getValue("draft-merge").selected)
        assertFalse(itemsById.getValue("draft-skip").selected)
        assertEquals(1, viewModel.uiState.value.selectedCount)

        collectJob.cancel()
    }

    @Test
    fun applyBatchCategory_updatesOnlyCheckedCompatibleTypeAndMarksManuallyChanged() = runTest {
        val (_, _, viewModel) = createHarness()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        val exp1 = createReviewItem(
            draftId = "draft-exp-1",
            duplicateStatus = ImportDuplicateStatus.NEW,
            selected = true,
            type = TransactionType.EXPENSE,
            categoryId = DefaultCategories.EXPENSE_OTHER_ID
        )
        val inc1 = createReviewItem(
            draftId = "draft-inc-1",
            duplicateStatus = ImportDuplicateStatus.NEW,
            selected = true,
            type = TransactionType.INCOME,
            categoryId = DefaultCategories.INCOME_OTHER_ID
        )
        val exp2 = createReviewItem(
            draftId = "draft-exp-2",
            duplicateStatus = ImportDuplicateStatus.NEW,
            selected = false,
            type = TransactionType.EXPENSE,
            categoryId = DefaultCategories.EXPENSE_OTHER_ID
        )

        viewModel.loadBatch(createBatch(exp1, inc1, exp2))
        advanceUntilIdle()
        viewModel.applyBatchCategory("exp_shopping")
        advanceUntilIdle()

        val itemsById = viewModel.uiState.value.batch!!.items.associateBy { it.draftId }
        val updatedExp1 = itemsById.getValue("draft-exp-1")
        assertEquals("exp_shopping", updatedExp1.record.categoryId)
        assertTrue(updatedExp1.categoryManuallyChanged)
        assertFalse(updatedExp1.userEdited)

        // 收入条目不能被支出分类覆盖
        val untouchedInc1 = itemsById.getValue("draft-inc-1")
        assertEquals(DefaultCategories.INCOME_OTHER_ID, untouchedInc1.record.categoryId)
        assertFalse(untouchedInc1.categoryManuallyChanged)
        assertFalse(untouchedInc1.userEdited)

        // 未勾选的条目不参与批量应用
        val untouchedExp2 = itemsById.getValue("draft-exp-2")
        assertEquals(DefaultCategories.EXPENSE_OTHER_ID, untouchedExp2.record.categoryId)
        assertFalse(untouchedExp2.categoryManuallyChanged)
        assertFalse(untouchedExp2.userEdited)

        collectJob.cancel()
    }

    @Test
    fun confirmImport_commitsOnlyCheckedItemsAndLearnsCategory() = runTest {
        val (store, repository, viewModel) = createHarness()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        val batch = repository.prepareImportReview(
            items = listOf(
                ParsedPayment(
                    channel = PaymentChannel.WECHAT,
                    fundingMethod = "零钱",
                    type = TransactionType.EXPENSE,
                    amountCents = 1990L,
                    merchant = "瑞幸咖啡",
                    timestampMillis = baseMillis,
                    source = CaptureSource.IMPORT
                ),
                ParsedPayment(
                    channel = PaymentChannel.WECHAT,
                    fundingMethod = "零钱",
                    type = TransactionType.EXPENSE,
                    amountCents = 4500L,
                    merchant = "某独立书店",
                    timestampMillis = baseMillis + 60_000L,
                    source = CaptureSource.IMPORT
                )
            ),
            sourceTitle = "截图导入"
        )
        assertEquals(2, batch.items.size)
        assertTrue(batch.items.all { it.duplicateStatus == ImportDuplicateStatus.NEW })

        viewModel.loadBatch(batch)
        advanceUntilIdle()

        val loadedItems = viewModel.uiState.value.batch!!.items
        val item1 = loadedItems[0]
        val item2 = loadedItems[1]

        viewModel.toggleSelected(item2.draftId)
        viewModel.updateItemCategory(item1.draftId, "exp_daily")
        advanceUntilIdle()

        var summary: ImportCommitSummary? = null
        viewModel.confirmImport { summary = it }
        advanceUntilIdle()

        assertNotNull(summary)
        assertEquals(1, summary?.insertedCount)
        assertEquals(1, summary?.skippedCount)

        val storedTransactions = store.getAllTransactions()
        assertEquals(1, storedTransactions.size)
        assertEquals("瑞幸咖啡", storedTransactions.single().merchant)
        assertEquals("exp_daily", storedTransactions.single().categoryId)

        val learnedRule = store.getAllMerchantRules().firstOrNull {
            it.matchType == RuleMatchType.EXACT_USER && it.pattern == "瑞幸咖啡"
        }
        assertNotNull(learnedRule)
        assertEquals("exp_daily", learnedRule?.categoryId)

        collectJob.cancel()
    }

    @Test
    fun addManualDraft_appendsCheckedNewItemToReviewList() = runTest {
        val (_, _, viewModel) = createHarness()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        viewModel.loadBatch(ImportReviewBatch(sourceTitle = "手动录入", items = emptyList()))
        advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.batch!!.items.size)

        viewModel.addManualDraft(
            createRecord(
                merchant = "手工补录商户",
                type = TransactionType.EXPENSE,
                categoryId = DefaultCategories.EXPENSE_OTHER_ID,
                amountCents = 3000L
            )
        )
        advanceUntilIdle()

        val items = viewModel.uiState.value.batch!!.items
        assertEquals(1, items.size)
        val added = items.single()
        assertTrue(added.selected)
        assertEquals(ImportDuplicateStatus.NEW, added.duplicateStatus)
        assertTrue(added.userEdited)
        assertEquals("手工补录商户", added.record.merchant)
        assertEquals(1, viewModel.uiState.value.selectedCount)

        collectJob.cancel()
    }

    @Test
    fun updateItemCategory_doesNotSetUserEditedFlag() = runTest {
        val (_, _, viewModel) = createHarness()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        val mergeItem = createReviewItem(
            draftId = "draft-merge-cat",
            duplicateStatus = ImportDuplicateStatus.DUPLICATE_MERGE,
            selected = true,
            categoryId = DefaultCategories.EXPENSE_OTHER_ID
        )
        viewModel.loadBatch(createBatch(mergeItem))
        advanceUntilIdle()

        viewModel.updateItemCategory("draft-merge-cat", "exp_food")
        advanceUntilIdle()

        val updated = viewModel.uiState.value.batch!!.items.single { it.draftId == "draft-merge-cat" }
        assertEquals("exp_food", updated.record.categoryId)
        assertTrue(updated.categoryManuallyChanged)
        assertFalse(updated.userEdited)

        collectJob.cancel()
    }

    @Test
    fun startLoading_cancelsStaleLoader_resetCancelsLoading_confirmExposesCommittedSummary_addManualClearsError() =
        runTest {
            val (_, _, viewModel) = createHarness()

            // 1. A stale loader completing late must not overwrite the newer load result.
            val staleDeferred = CompletableDeferred<ImportReviewBatch>()
            val staleBatch = createBatch(
                createReviewItem(
                    draftId = "draft-stale",
                    duplicateStatus = ImportDuplicateStatus.NEW,
                    selected = true
                )
            )
            val freshBatch = ImportReviewBatch(sourceTitle = "新任务", items = emptyList())
            viewModel.startLoading("旧任务") { staleDeferred.await() }
            viewModel.startLoading("新任务") { freshBatch }
            staleDeferred.complete(staleBatch)
            advanceUntilIdle()
            assertEquals("新任务", viewModel.uiState.value.batch?.sourceTitle)
            assertFalse(viewModel.uiState.value.isLoading)

            // 2. reset() cancels a suspended loader; its late completion must not restore a batch.
            val suspended = CompletableDeferred<ImportReviewBatch>()
            viewModel.startLoading("待重置") { suspended.await() }
            viewModel.reset()
            suspended.complete(staleBatch)
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.batch)
            assertFalse(viewModel.uiState.value.isLoading)

            // 3. addManualDraft clears a previous loading error.
            viewModel.failLoading("失败")
            viewModel.addManualDraft(createRecord(merchant = "手工补录"))
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.errorMessage)
            val manualAdded = viewModel.uiState.value.batch!!.items.any {
                it.record.merchant == "手工补录"
            }
            assertTrue(manualAdded)

            // 4. confirmImport() without a callback exposes the committed summary until consumed.
            viewModel.loadBatch(
                createBatch(
                    createReviewItem(
                        draftId = "draft-commit",
                        duplicateStatus = ImportDuplicateStatus.NEW,
                        selected = true,
                        merchant = "瑞幸咖啡"
                    )
                )
            )
            advanceUntilIdle()
            viewModel.confirmImport()
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.committedSummary)
            assertEquals(1, viewModel.uiState.value.committedSummary?.insertedCount)
            viewModel.consumeCommittedSummary()
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.committedSummary)
        }

    @Test
    fun reconciliationGroup_toggleSelectAllAndRemoveNeverSplitTheGroup() = runTest {
        val (_, _, viewModel) = createHarness()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        // One stored bank row is replaced by a principal (DUPLICATE_MERGE) plus its fee (NEW).
        val original = createRecord(merchant = "银行汇总", amountCents = 12_000L).copy(id = 42L)
        val principalRecord = createRecord(merchant = "提现本金", amountCents = 10_000L)
        val feeRecord = createRecord(
            merchant = "提现手续费",
            amountCents = 2_000L,
            timestampMillis = baseMillis + 1_000L
        )
        val group = ImportReconciliation(
            original = original,
            replacements = linkedMapOf(
                "draft-principal" to principalRecord,
                "draft-fee" to feeRecord
            )
        )

        val principalItem = createReviewItem(
            draftId = "draft-principal",
            duplicateStatus = ImportDuplicateStatus.DUPLICATE_MERGE,
            selected = true,
            merchant = "提现本金",
            amountCents = 10_000L
        ).copy(reconciliation = group)
        val feeItem = createReviewItem(
            draftId = "draft-fee",
            duplicateStatus = ImportDuplicateStatus.NEW,
            selected = true,
            merchant = "提现手续费",
            amountCents = 2_000L,
            timestampMillis = baseMillis + 1_000L
        ).copy(reconciliation = group)

        viewModel.loadBatch(createBatch(principalItem, feeItem))
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.selectedCount)

        // Toggling either member must uncheck the whole reconciliation group.
        viewModel.toggleSelected("draft-principal")
        advanceUntilIdle()
        val unchecked = viewModel.uiState.value.batch!!.items.associateBy { it.draftId }
        assertFalse(unchecked.getValue("draft-principal").selected)
        assertFalse(unchecked.getValue("draft-fee").selected)
        assertEquals(0, viewModel.uiState.value.selectedCount)

        // The NEW fee member must pull the DUPLICATE_MERGE principal in with it.
        viewModel.selectAllNew()
        advanceUntilIdle()
        val reselected = viewModel.uiState.value.batch!!.items.associateBy { it.draftId }
        assertTrue(reselected.getValue("draft-principal").selected)
        assertTrue(reselected.getValue("draft-fee").selected)
        assertEquals(2, viewModel.uiState.value.selectedCount)

        // Removing either member must remove the entire reconciliation group.
        viewModel.removeItem("draft-fee")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.batch!!.items.isEmpty())
        assertEquals(0, viewModel.uiState.value.selectedCount)

        collectJob.cancel()
    }
}
