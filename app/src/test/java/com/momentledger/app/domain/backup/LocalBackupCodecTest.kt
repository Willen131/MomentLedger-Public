package com.momentledger.app.domain.backup

import com.momentledger.app.domain.model.CaptureSource
import com.momentledger.app.domain.model.Category
import com.momentledger.app.domain.model.CategoryType
import com.momentledger.app.domain.model.DefaultCategories
import com.momentledger.app.domain.model.MerchantRule
import com.momentledger.app.domain.model.PaymentChannel
import com.momentledger.app.domain.model.RuleMatchType
import com.momentledger.app.domain.model.TransactionRecord
import com.momentledger.app.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class LocalBackupCodecTest {

    private val shanghaiZone: ZoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun jsonRoundTrip_preservesTransactionsCategoriesAndRulesVerbatim() {
        val ts1 = ZonedDateTime.of(2026, 11, 19, 12, 34, 56, 0, shanghaiZone).toInstant().toEpochMilli()
        val ts2 = ZonedDateTime.of(2026, 11, 19, 18, 5, 0, 0, shanghaiZone).toInstant().toEpochMilli()

        val categories = DefaultCategories.all + Category(
            id = "custom_pet",
            name = "宠物",
            categoryType = CategoryType.EXPENSE,
            iconKey = "pets",
            colorHex = "#FF9500",
            sortOrder = 99,
            isSystem = false
        )
        val rules = listOf(
            MerchantRule(
                id = 1001L,
                pattern = "瑞幸咖啡",
                categoryType = CategoryType.EXPENSE,
                matchType = RuleMatchType.EXACT_USER,
                categoryId = "exp_food",
                priority = 1
            ),
            MerchantRule(
                id = 1002L,
                pattern = "工资",
                categoryType = CategoryType.INCOME,
                matchType = RuleMatchType.KEYWORD_BUILTIN,
                categoryId = "inc_salary",
                priority = 100
            )
        )
        val transactions = listOf(
            TransactionRecord(
                id = 1L,
                timestampMillis = ts1,
                localDate = "2026-11-19",
                hourOfDay = 12,
                amountCents = 1690L,
                type = TransactionType.EXPENSE,
                channel = PaymentChannel.WECHAT,
                fundingMethod = "招商银行信用卡(8848)",
                merchant = "瑞幸咖啡",
                categoryId = "exp_food",
                note = "生椰拿铁",
                excludedFromStats = false,
                captureSource = CaptureSource.BOTH,
                externalOrderId = "420000240020261119123456",
                createdAtMillis = ts1,
                updatedAtMillis = ts1 + 1000L
            ),
            TransactionRecord(
                id = 2L,
                timestampMillis = ts2,
                localDate = "2026-11-19",
                hourOfDay = 18,
                amountCents = 50000L,
                type = TransactionType.TRANSFER,
                channel = PaymentChannel.ALIPAY,
                fundingMethod = "余额宝",
                merchant = "信用卡还款",
                categoryId = DefaultCategories.EXPENSE_OTHER_ID,
                note = "账单还款",
                excludedFromStats = true,
                captureSource = CaptureSource.IMPORT,
                externalOrderId = null,
                createdAtMillis = ts2,
                updatedAtMillis = ts2
            )
        )

        val snapshot = LocalBackupSnapshot(
            schemaVersion = 1,
            exportedAtMillis = ts2 + 5000L,
            categories = categories,
            merchantRules = rules,
            transactions = transactions
        )

        val encoded = LocalBackupCodec.encodeJson(snapshot)
        assertTrue(encoded.contains("\"schemaVersion\": 1"))
        assertTrue(encoded.contains("招商银行信用卡(8848)"))
        assertTrue(encoded.contains("420000240020261119123456"))

        val decoded = LocalBackupCodec.decodeJson(encoded)
        assertEquals(snapshot, decoded)

        // Unknown keys from future versions should be ignored cleanly.
        val withUnknownKey = encoded.replaceFirst("{", "{\n  \"futureField\": \"ignored\",")
        assertEquals(snapshot, LocalBackupCodec.decodeJson(withUnknownKey))
    }

    @Test
    fun exportTransactionsCsv_includesBomHeadersEscapingAndFormattedFields() {
        val ts1 = ZonedDateTime.of(2026, 11, 19, 12, 34, 56, 0, shanghaiZone).toInstant().toEpochMilli()
        val ts2 = ZonedDateTime.of(2026, 11, 19, 15, 0, 5, 0, shanghaiZone).toInstant().toEpochMilli()

        val transactions = listOf(
            TransactionRecord(
                id = 1L,
                timestampMillis = ts1,
                localDate = "2026-11-19",
                hourOfDay = 12,
                amountCents = 3650L,
                type = TransactionType.EXPENSE,
                channel = PaymentChannel.ALIPAY,
                fundingMethod = "花呗",
                merchant = "美团,外卖",
                categoryId = "exp_food",
                note = "备注含\"引号\"\n和换行",
                excludedFromStats = false,
                captureSource = CaptureSource.ACCESSIBILITY,
                externalOrderId = "202611192200140001",
                createdAtMillis = ts1,
                updatedAtMillis = ts1
            ),
            TransactionRecord(
                id = 2L,
                timestampMillis = ts2,
                localDate = "2026-11-19",
                hourOfDay = 15,
                amountCents = 123456L,
                type = TransactionType.INCOME,
                channel = PaymentChannel.BANK,
                fundingMethod = "招商银行储蓄卡",
                merchant = "公司发薪",
                categoryId = "inc_salary",
                note = "",
                excludedFromStats = true,
                captureSource = CaptureSource.MANUAL,
                externalOrderId = null,
                createdAtMillis = ts2,
                updatedAtMillis = ts2
            )
        )

        val categoryNames = mapOf(
            "exp_food" to "餐饮",
            "inc_salary" to "工资"
        )

        val csv = LocalBackupCodec.exportTransactionsCsv(
            transactions = transactions,
            categoryNames = categoryNames,
            zoneId = shanghaiZone
        )

        assertTrue(csv.startsWith("\uFEFF"))
        val withoutBom = csv.removePrefix("\uFEFF")
        assertTrue(
            withoutBom.startsWith("时间,类型,金额(元),分类,商户,支付渠道,资金来源,来源通道,不计入统计,备注,官方单号")
        )
        assertTrue(withoutBom.contains("2026-11-19 12:34:56,支出,¥36.50,餐饮,\"美团,外卖\",支付宝,花呗,无障碍识别,否,\"备注含\"\"引号\"\"\n和换行\",202611192200140001"))
        assertTrue(withoutBom.contains("2026-11-19 15:00:05,收入,¥1234.56,工资,公司发薪,银行卡,招商银行储蓄卡,手动录入,是,,"))
    }
}
