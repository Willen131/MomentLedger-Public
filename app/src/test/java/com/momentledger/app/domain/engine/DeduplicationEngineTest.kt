package com.momentledger.app.domain.engine

import com.momentledger.app.domain.model.CaptureSource
import com.momentledger.app.domain.model.DefaultCategories
import com.momentledger.app.domain.model.ImportDuplicateStatus
import com.momentledger.app.domain.model.ParsedPayment
import com.momentledger.app.domain.model.PaymentChannel
import com.momentledger.app.domain.model.TransactionRecord
import com.momentledger.app.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class DeduplicationEngineTest {

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

    private fun makeRecord(
        id: Long,
        timestampMillis: Long,
        amountCents: Long,
        type: TransactionType = TransactionType.EXPENSE,
        channel: PaymentChannel = PaymentChannel.WECHAT,
        fundingMethod: String = "",
        merchant: String = "瑞幸咖啡",
        categoryId: String = "exp_food",
        note: String = "",
        excludedFromStats: Boolean = false,
        captureSource: CaptureSource = CaptureSource.NOTIFICATION,
        externalOrderId: String? = null
    ): TransactionRecord {
        val zoned = Instant.ofEpochMilli(timestampMillis).atZone(shanghaiZone)
        return TransactionRecord(
            id = id,
            timestampMillis = timestampMillis,
            localDate = zoned.toLocalDate().toString(),
            hourOfDay = zoned.hour,
            amountCents = amountCents,
            type = type,
            channel = channel,
            fundingMethod = fundingMethod,
            merchant = merchant,
            categoryId = categoryId,
            note = note,
            excludedFromStats = excludedFromStats,
            captureSource = captureSource,
            externalOrderId = externalOrderId,
            createdAtMillis = timestampMillis,
            updatedAtMillis = timestampMillis
        )
    }

    @Test
    fun evaluateRealTime_debouncesIdenticalAccessibilityEventWithin10Seconds() {
        val engine = DeduplicationEngine(zoneId = shanghaiZone)
        val t0 = millisAt(2026, 11, 19, 14, 0, 0)
        val event = ParsedPayment(
            channel = PaymentChannel.WECHAT,
            fundingMethod = "零钱",
            type = TransactionType.EXPENSE,
            amountCents = 1690L,
            merchant = "瑞幸咖啡",
            timestampMillis = t0,
            source = CaptureSource.ACCESSIBILITY
        )

        val first = engine.evaluateRealTime(event, emptyList(), nowMillis = t0)
        assertEquals(DedupDecision.InsertAsNew, first)

        // Even before DB row appears in nearbyCandidates, or when it is present, 4s later is debounced
        val secondEmptyCandidates = engine.evaluateRealTime(
            incoming = event.copy(timestampMillis = t0 + 4_000L),
            nearbyCandidates = emptyList(),
            nowMillis = t0 + 4_000L
        )
        assertEquals(DedupDecision.IgnoreAsDebounce, secondEmptyCandidates)

        val saved = makeRecord(
            id = 10L,
            timestampMillis = t0,
            amountCents = 1690L,
            fundingMethod = "零钱",
            merchant = "瑞幸咖啡",
            captureSource = CaptureSource.ACCESSIBILITY
        )
        val secondWithCandidate = engine.evaluateRealTime(
            incoming = event.copy(timestampMillis = t0 + 6_000L),
            nearbyCandidates = listOf(saved),
            nowMillis = t0 + 6_000L
        )
        assertEquals(DedupDecision.IgnoreAsDebounce, secondWithCandidate)

        // After 10s debounce window expires (with no nearby candidate), it is no longer debounced
        val afterWindow = engine.evaluateRealTime(
            incoming = event.copy(timestampMillis = t0 + 11_000L),
            nearbyCandidates = emptyList(),
            nowMillis = t0 + 11_000L
        )
        assertEquals(DedupDecision.InsertAsNew, afterWindow)

        // clearDebounceCache resets debounce immediately
        engine.clearDebounceCache()
        val afterClear = engine.evaluateRealTime(
            incoming = event.copy(timestampMillis = t0 + 12_000L),
            nearbyCandidates = emptyList(),
            nowMillis = t0 + 12_000L
        )
        assertEquals(DedupDecision.InsertAsNew, afterClear)
    }

    @Test
    fun evaluateRealTime_differentCaptureSourceIsNotSuppressedByDebounceAndMergesToBoth() {
        val engine = DeduplicationEngine(zoneId = shanghaiZone)
        val t0 = millisAt(2026, 11, 19, 10, 15, 0)

        val notificationEvent = ParsedPayment(
            channel = PaymentChannel.WECHAT,
            fundingMethod = "",
            type = TransactionType.EXPENSE,
            amountCents = 1690L,
            merchant = "微信支付凭证",
            timestampMillis = t0,
            source = CaptureSource.NOTIFICATION
        )
        assertEquals(DedupDecision.InsertAsNew, engine.evaluateRealTime(notificationEvent, emptyList(), t0))

        val existingNotification = makeRecord(
            id = 42L,
            timestampMillis = t0,
            amountCents = 1690L,
            channel = PaymentChannel.WECHAT,
            fundingMethod = "",
            merchant = "微信支付凭证",
            captureSource = CaptureSource.NOTIFICATION
        )

        val accessibilityEvent = ParsedPayment(
            channel = PaymentChannel.WECHAT,
            fundingMethod = "招商银行(8848)",
            type = TransactionType.EXPENSE,
            amountCents = 1690L,
            merchant = "瑞幸咖啡",
            timestampMillis = t0 + 6_000L,
            source = CaptureSource.ACCESSIBILITY
        )

        val decision = engine.evaluateRealTime(
            incoming = accessibilityEvent,
            nearbyCandidates = listOf(existingNotification),
            nowMillis = t0 + 6_000L
        )

        assertTrue(decision is DedupDecision.MergeIntoExisting)
        val merge = decision as DedupDecision.MergeIntoExisting
        assertEquals(42L, merge.existingId)
        assertEquals("瑞幸咖啡", merge.updatedRecord.merchant)
        assertEquals("招商银行(8848)", merge.updatedRecord.fundingMethod)
        assertEquals(CaptureSource.BOTH, merge.updatedRecord.captureSource)
        assertEquals(PaymentChannel.WECHAT, merge.updatedRecord.channel)
    }

    @Test
    fun evaluateRealTime_sameChannelOutside20SecondsInsertsAsNew() {
        val engine = DeduplicationEngine(zoneId = shanghaiZone)
        val t0 = millisAt(2026, 11, 19, 9, 0, 0)
        val existing = makeRecord(
            id = 7L,
            timestampMillis = t0,
            amountCents = 1690L,
            channel = PaymentChannel.WECHAT,
            merchant = "瑞幸咖啡",
            captureSource = CaptureSource.ACCESSIBILITY
        )
        val laterSameChannel = ParsedPayment(
            channel = PaymentChannel.WECHAT,
            fundingMethod = "",
            type = TransactionType.EXPENSE,
            amountCents = 1690L,
            merchant = "瑞幸咖啡",
            timestampMillis = t0 + 25_000L,
            source = CaptureSource.NOTIFICATION
        )

        val decision = engine.evaluateRealTime(laterSameChannel, listOf(existing), t0 + 25_000L)
        assertEquals(DedupDecision.InsertAsNew, decision)
    }

    @Test
    fun evaluateRealTime_crossMidnightBankAndWalletMergePreservesWalletDateAndBankFunding() {
        val engine = DeduplicationEngine(zoneId = shanghaiZone)
        val walletTime = millisAt(2026, 11, 19, 23, 59, 30)
        val bankTime = millisAt(2026, 11, 20, 0, 0, 40) // 70s later, crossed midnight!

        val existingWallet = makeRecord(
            id = 101L,
            timestampMillis = walletTime,
            amountCents = 3450L,
            type = TransactionType.EXPENSE,
            channel = PaymentChannel.WECHAT,
            fundingMethod = "",
            merchant = "老乡鸡餐厅",
            categoryId = "exp_food",
            captureSource = CaptureSource.ACCESSIBILITY
        )

        val bankNotification = ParsedPayment(
            channel = PaymentChannel.BANK,
            fundingMethod = "招商银行",
            type = TransactionType.EXPENSE,
            amountCents = 3450L,
            merchant = "招商银行",
            timestampMillis = bankTime,
            source = CaptureSource.NOTIFICATION
        )

        val decision = engine.evaluateRealTime(
            incoming = bankNotification,
            nearbyCandidates = listOf(existingWallet),
            nowMillis = bankTime
        )

        assertTrue(decision is DedupDecision.MergeIntoExisting)
        val merge = decision as DedupDecision.MergeIntoExisting
        assertEquals(101L, merge.existingId)
        assertEquals(PaymentChannel.WECHAT, merge.updatedRecord.channel)
        assertEquals("2026-11-19", merge.updatedRecord.localDate)
        assertEquals(23, merge.updatedRecord.hourOfDay)
        assertEquals(walletTime, merge.updatedRecord.timestampMillis)
        assertEquals("招商银行", merge.updatedRecord.fundingMethod)
        assertEquals("老乡鸡餐厅", merge.updatedRecord.merchant)
        assertEquals(CaptureSource.BOTH, merge.updatedRecord.captureSource)
    }

    @Test
    fun evaluateRealTime_bankFirstThenWalletPromotesChannelAndEnrichesMerchant() {
        val engine = DeduplicationEngine(zoneId = shanghaiZone)
        val bankTime = millisAt(2026, 11, 19, 20, 10, 0)
        val walletTime = bankTime + 45_000L

        val existingBank = makeRecord(
            id = 202L,
            timestampMillis = bankTime,
            amountCents = 8800L,
            type = TransactionType.EXPENSE,
            channel = PaymentChannel.BANK,
            fundingMethod = "工商银行",
            merchant = "工商银行",
            categoryId = DefaultCategories.EXPENSE_OTHER_ID,
            captureSource = CaptureSource.NOTIFICATION
        )

        val walletEvent = ParsedPayment(
            channel = PaymentChannel.ALIPAY,
            fundingMethod = "银行卡",
            type = TransactionType.EXPENSE,
            amountCents = 8800L,
            merchant = "山姆会员店",
            timestampMillis = walletTime,
            source = CaptureSource.ACCESSIBILITY
        )

        val decision = engine.evaluateRealTime(
            incoming = walletEvent,
            nearbyCandidates = listOf(existingBank),
            nowMillis = walletTime
        )

        assertTrue(decision is DedupDecision.MergeIntoExisting)
        val merge = decision as DedupDecision.MergeIntoExisting
        assertEquals(202L, merge.existingId)
        assertEquals(PaymentChannel.ALIPAY, merge.updatedRecord.channel)
        assertEquals("山姆会员店", merge.updatedRecord.merchant)
        assertEquals("工商银行", merge.updatedRecord.fundingMethod)
        assertEquals(CaptureSource.BOTH, merge.updatedRecord.captureSource)
    }

    @Test
    fun evaluateRealTime_doesNotMergeDifferentWalletsOrOutside180sCrossChannelWindow() {
        val engine = DeduplicationEngine(zoneId = shanghaiZone)
        val t0 = millisAt(2026, 11, 19, 12, 0, 0)

        val wechatRecord = makeRecord(
            id = 1L,
            timestampMillis = t0,
            amountCents = 2500L,
            channel = PaymentChannel.WECHAT,
            merchant = "瑞幸咖啡"
        )
        val alipayIncoming = ParsedPayment(
            channel = PaymentChannel.ALIPAY,
            fundingMethod = "花呗",
            type = TransactionType.EXPENSE,
            amountCents = 2500L,
            merchant = "蜜雪冰城",
            timestampMillis = t0 + 30_000L,
            source = CaptureSource.ACCESSIBILITY
        )
        assertEquals(
            DedupDecision.InsertAsNew,
            engine.evaluateRealTime(alipayIncoming, listOf(wechatRecord), t0 + 30_000L)
        )

        val lateBankIncoming = ParsedPayment(
            channel = PaymentChannel.BANK,
            fundingMethod = "招商银行",
            type = TransactionType.EXPENSE,
            amountCents = 2500L,
            merchant = "招商银行",
            timestampMillis = t0 + 185_000L,
            source = CaptureSource.NOTIFICATION
        )
        assertEquals(
            DedupDecision.InsertAsNew,
            engine.evaluateRealTime(lateBankIncoming, listOf(wechatRecord), t0 + 185_000L)
        )
    }

    @Test
    fun evaluateImport_exactOrderIdSkipsAndNearbyBankOrWalletMergesWhilePreservingUserEdits() {
        val engine = DeduplicationEngine(zoneId = shanghaiZone)
        val autoTime = millisAt(2026, 11, 19, 23, 59, 50)
        val officialTime = millisAt(2026, 11, 20, 0, 0, 12) // 22s later, official timestamp

        val existingOrderRecord = makeRecord(
            id = 300L,
            timestampMillis = officialTime,
            amountCents = 5600L,
            channel = PaymentChannel.ALIPAY,
            fundingMethod = "花呗",
            merchant = "美团外卖",
            captureSource = CaptureSource.IMPORT,
            externalOrderId = "20261120220014998877"
        )

        val duplicateOrderImport = ParsedPayment(
            channel = PaymentChannel.ALIPAY,
            fundingMethod = "花呗",
            type = TransactionType.EXPENSE,
            amountCents = 5600L,
            merchant = "美团外卖",
            timestampMillis = officialTime,
            source = CaptureSource.IMPORT,
            externalOrderId = "20261120220014998877"
        )

        assertEquals(
            DedupDecision.IgnoreAsDebounce,
            engine.evaluateImport(duplicateOrderImport, existingOrderRecord, listOf(existingOrderRecord))
        )

        // Bank fallback within +-180s gets enriched by official WeChat bill import, keeping user edits
        val bankFallback = makeRecord(
            id = 301L,
            timestampMillis = autoTime,
            amountCents = 4200L,
            type = TransactionType.EXPENSE,
            channel = PaymentChannel.BANK,
            fundingMethod = "招商银行",
            merchant = "招商银行",
            categoryId = "exp_daily",
            note = "帮同事代买",
            excludedFromStats = true,
            captureSource = CaptureSource.NOTIFICATION,
            externalOrderId = null
        )
        val wechatBillRow = ParsedPayment(
            channel = PaymentChannel.WECHAT,
            fundingMethod = "招商银行(8848)",
            type = TransactionType.EXPENSE,
            amountCents = 4200L,
            merchant = "全家便利店",
            timestampMillis = officialTime,
            source = CaptureSource.IMPORT,
            externalOrderId = "42000021002026112000112233",
            note = "矿泉水等"
        )

        val mergeDecision = engine.evaluateImport(
            incoming = wechatBillRow,
            exactOrderMatch = null,
            nearbyCandidates = listOf(bankFallback)
        )
        assertTrue(mergeDecision is DedupDecision.MergeIntoExisting)
        val merged = (mergeDecision as DedupDecision.MergeIntoExisting).updatedRecord
        assertEquals(301L, mergeDecision.existingId)
        assertEquals(PaymentChannel.WECHAT, merged.channel)
        assertEquals("全家便利店", merged.merchant)
        assertEquals("招商银行(8848)", merged.fundingMethod)
        assertEquals("42000021002026112000112233", merged.externalOrderId)
        assertEquals(officialTime, merged.timestampMillis)
        assertEquals("2026-11-20", merged.localDate)
        assertEquals(0, merged.hourOfDay)
        // Preserves user-edited fields
        assertEquals("exp_daily", merged.categoryId)
        assertEquals("帮同事代买", merged.note)
        assertEquals(true, merged.excludedFromStats)

        // Different official externalOrderId on a nearby record must NOT merge
        val secondOrderSameAmount = wechatBillRow.copy(
            externalOrderId = "42000021002026112000999999"
        )
        assertEquals(
            DedupDecision.InsertAsNew,
            engine.evaluateImport(secondOrderSameAmount, null, listOf(merged))
        )
    }

    @Test
    fun evaluateImport_keepsSpecificBankFundingOverGenericLabelAndHonorsConfiguredZoneId() {
        val tokyoZone = ZoneId.of("Asia/Tokyo")
        val engine = DeduplicationEngine(zoneId = tokyoZone)
        val utcMillis = Instant.parse("2026-11-19T15:30:00Z").toEpochMilli() // 2026-11-20 00:30:00 in Asia/Tokyo

        val bankFallback = makeRecord(
            id = 401L,
            timestampMillis = utcMillis - 20_000L,
            amountCents = 6400L,
            channel = PaymentChannel.BANK,
            fundingMethod = "招商银行",
            merchant = "招商银行",
            captureSource = CaptureSource.NOTIFICATION
        )
        val alipayBill = ParsedPayment(
            channel = PaymentChannel.ALIPAY,
            fundingMethod = "银行卡",
            type = TransactionType.EXPENSE,
            amountCents = 6400L,
            merchant = "盒马鲜生",
            timestampMillis = utcMillis,
            source = CaptureSource.IMPORT,
            externalOrderId = "ALI_9988"
        )

        val decision = engine.evaluateImport(alipayBill, null, listOf(bankFallback))
        assertTrue(decision is DedupDecision.MergeIntoExisting)
        val updated = (decision as DedupDecision.MergeIntoExisting).updatedRecord
        assertEquals("招商银行", updated.fundingMethod)
        assertEquals("2026-11-20", updated.localDate)
        assertEquals(0, updated.hourOfDay)
    }

    @Test
    fun evaluateImport_prioritizesSameChannelMatchOverCloserBankFallbackWithin180s() {
        val engine = DeduplicationEngine(zoneId = shanghaiZone)
        val officialTime = millisAt(2026, 11, 19, 18, 30, 0)

        val closerBankFallback = makeRecord(
            id = 501L,
            timestampMillis = officialTime - 2_000L,
            amountCents = 2800L,
            channel = PaymentChannel.BANK,
            fundingMethod = "招商银行",
            merchant = "招商银行",
            captureSource = CaptureSource.NOTIFICATION
        )
        val delayedSameChannelWechat = makeRecord(
            id = 502L,
            timestampMillis = officialTime - 15_000L,
            amountCents = 2800L,
            channel = PaymentChannel.WECHAT,
            fundingMethod = "零钱",
            merchant = "微信支付",
            captureSource = CaptureSource.ACCESSIBILITY
        )
        val wechatBill = ParsedPayment(
            channel = PaymentChannel.WECHAT,
            fundingMethod = "零钱",
            type = TransactionType.EXPENSE,
            amountCents = 2800L,
            merchant = "喜茶",
            timestampMillis = officialTime,
            source = CaptureSource.IMPORT,
            externalOrderId = "WX_SAME_CH_502"
        )

        val decision = engine.evaluateImport(
            incoming = wechatBill,
            exactOrderMatch = null,
            nearbyCandidates = listOf(closerBankFallback, delayedSameChannelWechat)
        )

        assertTrue(decision is DedupDecision.MergeIntoExisting)
        val merge = decision as DedupDecision.MergeIntoExisting
        assertEquals(502L, merge.existingId)
        assertEquals("喜茶", merge.updatedRecord.merchant)
        assertEquals("WX_SAME_CH_502", merge.updatedRecord.externalOrderId)
    }

    @Test
    fun evaluateRealTime_bankResolvedWalletNotificationWithin180sMergesWhileNormalSameChannelAfter20sInsertsAsNew() {
        val engine = DeduplicationEngine(zoneId = shanghaiZone)
        val t0 = millisAt(2026, 11, 19, 8, 30, 0)

        // (a) WeChat ACCESSIBILITY at t=0s + CMB-resolved WECHAT/NOTIFICATION (fundingMethod="招商银行") at t=65s
        val wechatAccessibilityRecord = makeRecord(
            id = 601L,
            timestampMillis = t0,
            amountCents = 1690L,
            channel = PaymentChannel.WECHAT,
            fundingMethod = "",
            merchant = "瑞幸咖啡",
            captureSource = CaptureSource.ACCESSIBILITY
        )
        val cmbResolvedWechatNotification = ParsedPayment(
            channel = PaymentChannel.WECHAT,
            fundingMethod = "招商银行",
            type = TransactionType.EXPENSE,
            amountCents = 1690L,
            merchant = "瑞幸咖啡",
            timestampMillis = t0 + 65_000L,
            source = CaptureSource.NOTIFICATION
        )

        val mergeDecision = engine.evaluateRealTime(
            incoming = cmbResolvedWechatNotification,
            nearbyCandidates = listOf(wechatAccessibilityRecord),
            nowMillis = t0 + 65_000L
        )
        assertTrue(mergeDecision is DedupDecision.MergeIntoExisting)
        val merged = (mergeDecision as DedupDecision.MergeIntoExisting).updatedRecord
        assertEquals(601L, mergeDecision.existingId)
        assertEquals(CaptureSource.BOTH, merged.captureSource)
        assertEquals("招商银行", merged.fundingMethod)

        // (b1) Two separate WeChat NOTIFICATION events with non-bank/blank funding 65s apart -> InsertAsNew
        val subwayRide1 = makeRecord(
            id = 602L,
            timestampMillis = t0 + 300_000L,
            amountCents = 400L,
            channel = PaymentChannel.WECHAT,
            fundingMethod = "",
            merchant = "深圳通乘车码",
            captureSource = CaptureSource.NOTIFICATION
        )
        val subwayRide2SameSource = ParsedPayment(
            channel = PaymentChannel.WECHAT,
            fundingMethod = "",
            type = TransactionType.EXPENSE,
            amountCents = 400L,
            merchant = "深圳通乘车码",
            timestampMillis = t0 + 365_000L,
            source = CaptureSource.NOTIFICATION
        )
        assertEquals(
            DedupDecision.InsertAsNew,
            engine.evaluateRealTime(subwayRide2SameSource, listOf(subwayRide1), t0 + 365_000L)
        )

        // (b2) Cross-source (ACCESSIBILITY + NOTIFICATION) with non-bank funding ("零钱") 65s apart -> InsertAsNew
        val subwayRide1Access = makeRecord(
            id = 603L,
            timestampMillis = t0 + 600_000L,
            amountCents = 400L,
            channel = PaymentChannel.WECHAT,
            fundingMethod = "零钱",
            merchant = "深圳通乘车码",
            captureSource = CaptureSource.ACCESSIBILITY
        )
        val subwayRide2NonBankNotif = ParsedPayment(
            channel = PaymentChannel.WECHAT,
            fundingMethod = "零钱",
            type = TransactionType.EXPENSE,
            amountCents = 400L,
            merchant = "深圳通乘车码",
            timestampMillis = t0 + 665_000L,
            source = CaptureSource.NOTIFICATION
        )
        assertEquals(
            DedupDecision.InsertAsNew,
            engine.evaluateRealTime(subwayRide2NonBankNotif, listOf(subwayRide1Access), t0 + 665_000L)
        )
    }

    @Test
    fun evaluateRealTime_prunesExpiredDebounceEntriesWithoutSuppressingFutureEvents() {
        val engine = DeduplicationEngine(debounceWindowMs = 10_000L, zoneId = shanghaiZone)
        val base = millisAt(2026, 11, 19, 8, 0, 0)

        for (i in 0 until 150) {
            val event = ParsedPayment(
                channel = PaymentChannel.WECHAT,
                fundingMethod = "",
                type = TransactionType.EXPENSE,
                amountCents = 100L + i,
                merchant = "商户$i",
                timestampMillis = base + i * 1_000L,
                source = CaptureSource.ACCESSIBILITY
            )
            assertEquals(
                DedupDecision.InsertAsNew,
                engine.evaluateRealTime(event, emptyList(), base + i * 1_000L)
            )
        }

        // Event 0 was at base + 0; at base + 150_000L its debounce entry has expired
        val replayedZero = ParsedPayment(
            channel = PaymentChannel.WECHAT,
            fundingMethod = "",
            type = TransactionType.EXPENSE,
            amountCents = 100L,
            merchant = "商户0",
            timestampMillis = base + 150_000L,
            source = CaptureSource.ACCESSIBILITY
        )
        assertEquals(
            DedupDecision.InsertAsNew,
            engine.evaluateRealTime(replayedZero, emptyList(), base + 150_000L)
        )
    }

    @Test
    fun evaluateReviewCandidate_matchesManualAndEcommerceAcrossChannelsWithin3Min() {
        val engine = DeduplicationEngine(zoneId = shanghaiZone)
        val baseMillis = millisAt(2026, 11, 19, 12, 30, 0)

        val existing = makeRecord(
            id = 10L,
            timestampMillis = baseMillis,
            amountCents = 3150L,
            type = TransactionType.EXPENSE,
            channel = PaymentChannel.MANUAL,
            fundingMethod = "",
            merchant = "麦当劳",
            note = "",
            externalOrderId = null
        )
        val incoming = ParsedPayment(
            channel = PaymentChannel.OTHER,
            fundingMethod = "",
            type = TransactionType.EXPENSE,
            amountCents = 3150L,
            merchant = "麦当劳(高新店)",
            timestampMillis = baseMillis + 120_000L,
            source = CaptureSource.OCR,
            externalOrderId = null,
            note = "美团外卖"
        )

        val match = engine.evaluateReviewCandidate(
            incoming = incoming,
            exactOrderMatch = null,
            nearbyCandidates = listOf(existing)
        )

        assertEquals(ImportDuplicateStatus.DUPLICATE_MERGE, match.status)
        assertEquals(10L, match.matchedExistingId)
    }

    @Test
    fun evaluateReviewCandidate_matchesSameNormalizedMerchantWithin15MinAsSkipWhenNoNewInfo() {
        val engine = DeduplicationEngine(zoneId = shanghaiZone)
        val baseMillis = millisAt(2026, 11, 19, 15, 0, 0)

        val existing = makeRecord(
            id = 20L,
            timestampMillis = baseMillis,
            amountCents = 24900L,
            type = TransactionType.EXPENSE,
            channel = PaymentChannel.ALIPAY,
            fundingMethod = "花呗",
            merchant = "苹果官方旗舰店",
            note = "AirPods",
            externalOrderId = "ORD001"
        )
        val incoming = ParsedPayment(
            channel = PaymentChannel.OTHER,
            fundingMethod = "",
            type = TransactionType.EXPENSE,
            amountCents = 24900L,
            merchant = "苹果官方旗舰店",
            timestampMillis = baseMillis + 720_000L,
            source = CaptureSource.OCR,
            externalOrderId = null,
            note = ""
        )

        val match = engine.evaluateReviewCandidate(
            incoming = incoming,
            exactOrderMatch = null,
            nearbyCandidates = listOf(existing)
        )

        assertEquals(ImportDuplicateStatus.DUPLICATE_SKIP, match.status)
        assertEquals(20L, match.matchedExistingId)
    }
}
