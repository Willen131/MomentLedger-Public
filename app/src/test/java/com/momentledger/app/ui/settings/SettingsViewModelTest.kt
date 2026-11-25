package com.momentledger.app.ui.settings

import android.content.SharedPreferences
import com.momentledger.app.data.prefs.LedgerPreferences
import com.momentledger.app.data.prefs.ThemePreference
import com.momentledger.app.data.repository.IngestResult
import com.momentledger.app.data.repository.LedgerRepository
import com.momentledger.app.domain.engine.DeduplicationEngine
import com.momentledger.app.domain.model.CaptureSource
import com.momentledger.app.domain.model.Category
import com.momentledger.app.domain.model.CategoryType
import com.momentledger.app.domain.model.DefaultCategories
import com.momentledger.app.domain.model.ParsedPayment
import com.momentledger.app.domain.model.PaymentChannel
import com.momentledger.app.domain.model.RuleMatchType
import com.momentledger.app.domain.model.TransactionType
import com.momentledger.app.domain.model.defaultTimePeriods
import com.momentledger.app.domain.engine.BudgetPeriod
import com.momentledger.app.testutil.FakeLedgerStore
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
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @Test
    fun deletingChannelsPersistsAndRestoringDefaultsKeepsExistingCustomChannels() {
        val storage = InMemorySharedPreferences()
        val preferences = LedgerPreferences(storage)
        preferences.addCustomChannel("饭卡")
        preferences.addCustomChannel("礼品卡余额")
        preferences.deleteChannel(PaymentChannel.WECHAT)
        preferences.deleteChannel(PaymentChannel.OTHER, "饭卡")
        val reloaded = LedgerPreferences(storage)
        assertEquals(setOf(PaymentChannel.WECHAT), reloaded.hiddenChannels.value)
        assertEquals(listOf("礼品卡余额"), reloaded.customChannels.value)
        reloaded.restoreDefaultChannels()
        val restored = LedgerPreferences(storage)
        assertTrue(restored.hiddenChannels.value.isEmpty())
        assertEquals(listOf("礼品卡余额"), restored.customChannels.value)
    }

    @Test
    fun legacyBudgetKeepsMonthAndYearTotalsAndPutsDailyOnlyOnUpgradeDay() {
        val storage = InMemorySharedPreferences()
        storage.edit().putString("budget", legacyBudgetJson).apply()
        val upgradeDay = LocalDate.parse("2026-11-21")

        val firstOpen = LedgerPreferences(storage, today = { upgradeDay }).budget.value
        assertEquals(25, firstOpen.startDay)
        assertEquals(320000L, firstOpen.totalCents)
        assertEquals(3600000L, firstOpen.yearlyCents)
        assertEquals(3, firstOpen.yearStartMonth)
        assertEquals(10, firstOpen.yearStartDay)
        assertEquals(5000L, firstOpen.amountFor(BudgetPeriod.DAY, upgradeDay))
        assertNull(firstOpen.amountFor(BudgetPeriod.DAY, upgradeDay.minusDays(1)))
        assertNull(firstOpen.amountFor(BudgetPeriod.DAY, upgradeDay.plusDays(1)))
        assertEquals(320000L, firstOpen.amountFor(BudgetPeriod.MONTH, upgradeDay))
        assertEquals(3600000L, firstOpen.amountFor(BudgetPeriod.YEAR, upgradeDay))
        // The legacy category limit must not create any additional budget.
        assertEquals(1, firstOpen.dailyCents.size)
        assertEquals(setOf(5000L), firstOpen.dailyCents.values.toSet())

        val nextDay = upgradeDay.plusDays(1)
        val reopened = LedgerPreferences(storage, today = { nextDay }).budget.value
        assertNull(reopened.amountFor(BudgetPeriod.DAY, nextDay))
        assertEquals(5000L, reopened.amountFor(BudgetPeriod.DAY, upgradeDay))
        assertEquals(320000L, reopened.amountFor(BudgetPeriod.MONTH, nextDay))
        assertEquals(3600000L, reopened.amountFor(BudgetPeriod.YEAR, nextDay))
        assertEquals(25, reopened.startDay)
        assertEquals(3, reopened.yearStartMonth)
        assertEquals(10, reopened.yearStartDay)
    }

    @Test
    fun legacyCategoryOnlyBudgetHasNoActiveBudget() {
        val storage = InMemorySharedPreferences()
        storage.edit().putString("budget", """{"startDay":25,"categoryCents":{"food":50000}}""").apply()
        val today = LocalDate.parse("2026-11-21")

        val budget = LedgerPreferences(storage, today = { today }).budget.value
        assertEquals(25, budget.startDay)
        assertTrue(budget.dailyCents.isEmpty())
        assertNull(budget.amountFor(BudgetPeriod.DAY, today))
        assertNull(budget.amountFor(BudgetPeriod.MONTH, today))
        assertNull(budget.amountFor(BudgetPeriod.YEAR, today))
    }

    @Test
    fun clearedDailyBudgetStaysEmptyAfterReloadAndIsNotMigratedAgain() {
        val storage = InMemorySharedPreferences()
        storage.edit().putString("budget", legacyBudgetJson).apply()
        val upgradeDay = LocalDate.parse("2026-11-21")

        val preferences = LedgerPreferences(storage, today = { upgradeDay })
        val migrated = preferences.budget.value
        assertEquals(5000L, migrated.amountFor(BudgetPeriod.DAY, upgradeDay))
        preferences.setBudget(migrated.withDailyBudget(setOf(upgradeDay), null))

        val nextDay = upgradeDay.plusDays(1)
        val reloaded = LedgerPreferences(storage, today = { nextDay }).budget.value
        assertNull(reloaded.amountFor(BudgetPeriod.DAY, upgradeDay))
        assertNull(reloaded.amountFor(BudgetPeriod.DAY, nextDay))
        assertEquals(320000L, reloaded.amountFor(BudgetPeriod.MONTH, nextDay))
    }

    private val legacyBudgetJson =
        """{"startDay":25,"totalCents":320000,"categoryCents":{"food":50000},"daily":{"totalCents":5000},"yearly":{"totalCents":3600000},"yearStartMonth":3,"yearStartDay":10}"""

    @Test
    fun customizedTimePeriodsReloadAndInvalidRangesLeaveSavedValueIntact() {
        val storage = InMemorySharedPreferences()
        val preferences = LedgerPreferences(storage)
        val edited = defaultTimePeriods.mapIndexed { index, period ->
            when (index) {
                0 -> period.copy(label = "通勤", endMinute = 675)
                1 -> period.copy(startMinute = 675)
                else -> period
            }
        }
        preferences.setTimePeriods(edited)
        assertEquals(edited, LedgerPreferences(storage).timePeriods.value)

        val overlapping = edited.toMutableList().apply { this[1] = this[1].copy(startMinute = 674) }
        assertTrue(runCatching { preferences.setTimePeriods(overlapping) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(edited, LedgerPreferences(storage).timePeriods.value)
    }


    private val shanghaiZone: ZoneId = ZoneId.of("Asia/Shanghai")
    private lateinit var testDispatcher: TestDispatcher

    @Before
    fun setUp() {
        testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createHarness(): Triple<FakeLedgerStore, LedgerRepository, SettingsViewModel> {
        val store = FakeLedgerStore()
        val repository = LedgerRepository(
            store = store,
            dedupEngine = DeduplicationEngine(zoneId = shanghaiZone),
            zoneId = shanghaiZone
        )
        val preferences = LedgerPreferences(InMemorySharedPreferences())
        val viewModel = SettingsViewModel(
            repository = repository,
            preferences = preferences,
            ioDispatcher = testDispatcher
        )
        return Triple(store, repository, viewModel)
    }

    @Test
    fun categoryAddRenameReorderAndDelete_archivesBuiltInAndCustomWithoutReassigningHistory() = runTest {
        val (store, repository, viewModel) = createHarness()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        val initialCount = viewModel.uiState.value.expenseCategories.size
        assertTrue(initialCount >= 11)

        // 1. Add custom expense category
        viewModel.addOrUpdateCategory(
            Category(
                id = "",
                name = "宠物萌宠",
                categoryType = CategoryType.EXPENSE,
                iconKey = "pets",
                colorHex = "#ff9500",
                sortOrder = 0,
                isSystem = false
            )
        )
        advanceUntilIdle()

        val created = viewModel.uiState.value.expenseCategories.firstOrNull { it.name == "宠物萌宠" }
        assertNotNull(created)
        assertFalse(created!!.isSystem)
        assertEquals("#FF9500", created.colorHex)

        // 2. Rename the custom category
        viewModel.addOrUpdateCategory(created.copy(name = "毛孩子", colorHex = "#34C759"))
        advanceUntilIdle()

        val renamed = viewModel.uiState.value.expenseCategories.firstOrNull { it.id == created.id }
        assertEquals("毛孩子", renamed?.name)
        assertEquals("#34C759", renamed?.colorHex)

        // 3. Reorder custom category up by one slot
        val currentList = viewModel.uiState.value.expenseCategories
        val createdIdx = currentList.indexOfFirst { it.id == created.id }
        assertTrue(createdIdx > 0)
        viewModel.moveCategory(CategoryType.EXPENSE, createdIdx, createdIdx - 1)
        advanceUntilIdle()

        val reordered = viewModel.uiState.value.expenseCategories
        assertEquals(created.id, reordered[createdIdx - 1].id)

        // 4. Link a transaction & learned merchant rule, then delete category and verify safe reassignment
        val t0 = ZonedDateTime.of(2026, 11, 19, 10, 0, 0, 0, shanghaiZone)
            .toInstant()
            .toEpochMilli()
        val ingested = repository.ingestRealTimePayment(
            ParsedPayment(
                channel = PaymentChannel.WECHAT,
                fundingMethod = "零钱",
                type = TransactionType.EXPENSE,
                amountCents = 6800L,
                merchant = "萌宠医院",
                timestampMillis = t0,
                source = CaptureSource.ACCESSIBILITY
            )
        ) as IngestResult.Inserted
        repository.updateCategoryAndLearn(ingested.record.id, created.id)

        viewModel.deleteCategory(DefaultCategories.EXPENSE_OTHER_ID)
        advanceUntilIdle()
        assertFalse(store.getCategoryById(DefaultCategories.EXPENSE_OTHER_ID)!!.isArchived)
        assertEquals("该系统兜底分类不可删除，或已删除", viewModel.uiState.value.statusMessage)

        viewModel.deleteCategory("exp_fun")
        viewModel.deleteCategory(created.id)
        advanceUntilIdle()

        assertTrue(store.getCategoryById("exp_fun")!!.isArchived)
        assertTrue(store.getCategoryById(created.id)!!.isArchived)
        assertEquals("已删除分类，历史账单保留原分类", viewModel.uiState.value.statusMessage)
        assertEquals(created.id, repository.getTransactionById(ingested.record.id)?.categoryId)
        val newRecord = repository.saveManualTransaction(ingested.record.copy(id = 0, categoryId = ""))
        assertEquals("exp_medical", repository.getTransactionById(newRecord)?.categoryId)
        val unmatched = repository.saveManualTransaction(ingested.record.copy(id = 0, categoryId = "", merchant = "杂项甲"))
        assertEquals(DefaultCategories.EXPENSE_OTHER_ID, repository.getTransactionById(unmatched)?.categoryId)

        collectJob.cancel()
    }

    @Test
    fun importBill_reportsExactInsertedMergedAndSkippedSummaryMessage() = runTest {
        val (_, repository, viewModel) = createHarness()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        // Pre-seed one BANK notification that should merge with the second CSV row within ±180s
        val tMerge = ZonedDateTime.of(2026, 11, 19, 14, 20, 0, 0, shanghaiZone)
            .toInstant()
            .toEpochMilli()
        repository.ingestRealTimePayment(
            ParsedPayment(
                channel = PaymentChannel.BANK,
                fundingMethod = "招商银行",
                type = TransactionType.EXPENSE,
                amountCents = 12800L,
                merchant = "招商银行",
                timestampMillis = tMerge,
                source = CaptureSource.NOTIFICATION
            )
        )

        val wechatCsv = buildString {
            append('\uFEFF')
            appendLine("微信支付账单明细,,,,,,,,")
            appendLine("交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注")
            appendLine("2026-11-19 08:30:00,商户消费,瑞幸咖啡,拿铁,支出,¥16.90,零钱,支付成功,4200002400111111,M1001,/")
            appendLine("2026-11-19 14:21:10,商户消费,山姆会员商店,生鲜采购,支出,¥128.00,招商银行储蓄卡,支付成功,4200002400222222,M1002,/")
            appendLine("2026-11-19 18:00:00,商户消费,取消订单,测试商品,支出,¥50.00,零钱,交易关闭,4200002400333333,M1003,/")
        }

        viewModel.importBill(
            fileName = "微信支付账单.csv",
            inputStream = ByteArrayInputStream(wechatCsv.toByteArray(StandardCharsets.UTF_8))
        )
        advanceUntilIdle()

        assertEquals(
            "新增 1 笔 · 合并去重 1 笔 · 跳过 1 笔",
            viewModel.uiState.value.statusMessage
        )
        assertFalse(viewModel.uiState.value.isBusy)

        viewModel.clearStatusMessage()
        assertNull(viewModel.uiState.value.statusMessage)

        collectJob.cancel()
    }

    @Test
    fun preferencesAndQuickNotificationToggle_updateReactiveUiStateAndBackupRoundTrips() = runTest {
        val (store, _, viewModel) = createHarness()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.quickNotificationEnabled)
        viewModel.refreshNotificationPermission(false)
        viewModel.setQuickNotificationEnabled(false)
        viewModel.setThemePreference(ThemePreference.DARK)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.quickNotificationEnabled)
        assertFalse(state.notificationPermissionGranted)
        assertEquals(ThemePreference.DARK, state.themePreference)

        val backupJson = viewModel.exportBackupJson()
        store.clearAll()
        viewModel.restoreBackupJson(backupJson)
        advanceUntilIdle()
        assertEquals("已恢复 0 笔本地账单", viewModel.uiState.value.statusMessage)

        collectJob.cancel()
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()

        override fun getString(key: String?, defValue: String?): String? =
            (data[key] as? String) ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? Set<String>)?.toMutableSet() ?: defValues

        override fun getInt(key: String?, defValue: Int): Int =
            (data[key] as? Int) ?: defValue

        override fun getLong(key: String?, defValue: Long): Long =
            (data[key] as? Long) ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            (data[key] as? Float) ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            (data[key] as? Boolean) ?: defValue

        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?) = apply {
                if (key != null) pending[key] = value
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply {
                if (key != null) pending[key] = values?.toSet()
            }

            override fun putInt(key: String?, value: Int) = apply {
                if (key != null) pending[key] = value
            }

            override fun putLong(key: String?, value: Long) = apply {
                if (key != null) pending[key] = value
            }

            override fun putFloat(key: String?, value: Float) = apply {
                if (key != null) pending[key] = value
            }

            override fun putBoolean(key: String?, value: Boolean) = apply {
                if (key != null) pending[key] = value
            }

            override fun remove(key: String?) = apply {
                if (key != null) pending[key] = null
            }

            override fun clear() = apply { clearAll = true }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearAll) data.clear()
                for ((k, v) in pending) {
                    if (v == null) data.remove(k) else data[k] = v
                }
            }
        }

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
    }
}
