package com.momentledger.app.domain.parser

import com.momentledger.app.domain.model.CaptureSource
import com.momentledger.app.domain.model.PaymentChannel
import com.momentledger.app.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class BankPdfParserTest {

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

    private fun token(text: String, x: Float, y: Float, width: Float = 60f): PdfTextToken =
        PdfTextToken(text = text, x = x, y = y, width = width, height = 10f)

    @Test
    fun parsePages_icbcMultiLineWrappedTextWithWatermarks_parsesExpensesRefundsTransfersIncome() {
        val pageOneText = buildString {
            appendLine("中国工商银行借记账户历史明细（电子版）")
            appendLine("卡号 6222030200032609999 户名：测试用户 起止日期：2026-08-20 — 2026-11-20")
            appendLine("交易日期 账号 储种 序号 币种 钞汇 摘要 地区 收入/支出金额 余额 对方户名 对方账号 渠道")
            appendLine("2026-08-20")
            appendLine("19:51:24 0200002901025269999 活期 00000 人民币")
            appendLine("钞 消费 0200 -29.30 9.20 京东商城平台商户 1532****4971 快捷支付")
            appendLine("2026-08-22")
            appendLine("13:50:05 0200002901025269999 活期 00000 人民币")
            appendLine("钞 数字人民币 消费(美团) 0200 -4.91 4.29 极光2 0041****3035 其他")
            appendLine("2026-08-23")
            appendLine("18:03:29 0200002901025269999 活期 00000 人民币")
            appendLine("钞 银联入账 0200 +1,000.00 1,004.29 支付宝支付科技有 限公司 2088****0156 网上银行")
            appendLine("2026-08-25")
            appendLine("10:37:42 0200002901025269999 活期 00000 人民币")
            appendLine("钞 微信零钱提")
            appendLine("现 0200 +760.00 1,764.29 测试用户 1174****9611 快捷支付")
            appendLine("2026-08-27")
            appendLine("15:03:08 0200002901025269999 活期 00000 人民币")
            appendLine("钞 退款 0200 +17.70 1,781.99 深圳市财付通支付 科技有限公司 2433****0133 快捷支付")
            appendLine("2026-09-03")
            appendLine("20:27:09 0200002901025269999 活期 00000 人民币")
            appendLine("钞 还款 0200 -538.51 1,243.48 京东白条及金条业 务网银平台 1101****0001 快捷支付")
            appendLine("第 1 页")
        }

        val pageTwoText = buildString {
            appendLine("2026-09-12")
            appendLine("15:34:17 0200002901025269999 活期 00000 人民币")
            appendLine("钞 ATM存款 0200 +200.00 1,443.48 （空） （空） ATM交易")
            appendLine("2026-09-21")
            appendLine("00:59:04 0200002901025269999 活期 00000 人民币")
            appendLine("钞 利息 0200 +0.05 1,443.53 （空） （空） 批量业务")
            appendLine("2026-09-23")
            appendLine("20:11:00 0200002901025269999 活期 00000 人民币")
            appendLine("钞 理财 0200 -4,102.03 6,672.85 京东肯特瑞基金销 售有限公司 1110****3001 快捷支付")
            appendLine("2026-09-23")
            appendLine("21:08:13 0200002901025269999 活期 00000 人民币")
            appendLine("钞 充值 0200 -5,609.90 1,062.95 某卖家 2088****4445 快捷支付")
            appendLine("2026-11-14")
            appendLine("12:36:09 0200002901025269999 活期 00000 人民币")
            appendLine("钞 转账 0200 -35.00 965.43 支付宝支付科技有 限公司 2155****0690 快捷支付")
            appendLine("2026-11-17")
            appendLine("18:15:30 0200002901025269999 活期 00000 人民币")
            appendLine("钞 充值 0200 -200.00 416.88 深圳市财付通支付 科技有限公司 2433****0133 快捷支付")
            appendLine("第 2 页")
            appendLine("本页支出算术合计：1,021.78")
            appendLine("本页交易笔数：12")
            appendLine("本页收入算术合计：2,660.00 下单时间：2026-11-20 18:24:32")
            appendLine("中国工商银行 64058884 2026-11-20 18:24:32 48997C8AD026")
            appendLine("共 2 页")
        }

        val result = BankPdfParser.parsePages(
            pages = listOf(
                PdfPageContent(pageNumber = 1, rawText = pageOneText),
                PdfPageContent(pageNumber = 2, rawText = pageTwoText)
            ),
            fileName = "icbc_statement.pdf",
            zoneId = shanghaiZone
        )

        assertEquals("BANK_PDF", result.detectedFormat)
        assertEquals(12, result.items.size)

        val expected = listOf(
            Triple(TransactionType.EXPENSE, 2930L, "京东商城平台商户"),
            Triple(TransactionType.EXPENSE, 491L, "极光2"),
            Triple(TransactionType.TRANSFER, 100000L, "支付宝支付科技有限公司"),
            Triple(TransactionType.TRANSFER, 76000L, "测试用户"),
            Triple(TransactionType.REFUND, 1770L, "深圳市财付通支付科技有限公司"),
            Triple(TransactionType.TRANSFER, 53851L, "京东白条及金条业务网银平台"),
            Triple(TransactionType.TRANSFER, 20000L, "ATM存款"),
            Triple(TransactionType.INCOME, 5L, "利息"),
            Triple(TransactionType.TRANSFER, 410203L, "京东肯特瑞基金销售有限公司"),
            Triple(TransactionType.EXPENSE, 560990L, "某卖家"),
            Triple(TransactionType.EXPENSE, 3500L, "支付宝支付科技有限公司"),
            Triple(TransactionType.TRANSFER, 20000L, "深圳市财付通支付科技有限公司")
        )
        expected.forEachIndexed { index, (type, amountCents, merchant) ->
            val item = result.items[index]
            assertEquals("type at index $index", type, item.type)
            assertEquals("amountCents at index $index", amountCents, item.amountCents)
            assertEquals("merchant at index $index", merchant, item.merchant)
        }

        result.items.forEach { item ->
            assertEquals(PaymentChannel.BANK, item.channel)
            assertEquals(CaptureSource.IMPORT, item.source)
            assertTrue(item.hasTimeOfDay)
            assertEquals("工商银行(9999)", item.fundingMethod)
        }

        assertEquals(millisAt(2026, 8, 20, 19, 51, 24), result.items[0].timestampMillis)
        assertEquals(millisAt(2026, 9, 12, 15, 34, 17), result.items[6].timestampMillis)
        assertTrue("e-CNY expense note should keep 美团", result.items[1].note.contains("美团"))
    }

    @Test
    fun parsePages_cmbMultiPageDateOnlyStatement_parsesOwnAccountTransfersAndRepeatedRows() {
        val pageOneText = buildString {
            appendLine("招商银行交易流水")
            appendLine("户  名：测试用户")
            appendLine("账号：5342********8888")
            appendLine("记账日期 货币 交易金额 联机余额 交易摘要 对手信息")
            appendLine("2026-11-16 CNY 130.00 130.00 网联收款 测试用户")
            appendLine("2026-11-16 CNY 0.88 130.88 活动现金红包 其他应收款-活动现金红包内部")
            appendLine("户")
            appendLine("2026-11-16 CNY -100.00 30.88 享定存开户起息 测试用户")
            appendLine("2026-11-16 CNY -6.01 29.77 快捷支付 测试用户")
            appendLine("2026-11-16 CNY 6.00 35.77 一网通支付鼓励金 其它应收款-零售客户移动支付")
            appendLine("鼓励金专户")
            appendLine("2026-11-16 CNY -6.01 29.76 快捷支付 测试用户")
            appendLine("2026-11-16 CNY 6.00 35.76 一网通支付鼓励金 其它应收款-零售客户移动支付")
            appendLine("鼓励金专户")
            appendLine("2026-11-16 CNY 100.00 135.63 享定存全额支取 测试用户")
        }

        val pageTwoText = buildString {
            appendLine("2026-11-16 CNY -15.50 120.13 快捷支付 永年新校园超市")
            appendLine("2026-11-16 CNY -9.90 110.23 数币兑出 测试用户")
            appendLine("2026-11-16 CNY 0.10 54.62 数币兑回 测试用户")
            appendLine("温馨提示：本流水仅供参考，请以实际账户记录为准。")
        }

        val result = BankPdfParser.parsePages(
            pages = listOf(
                PdfPageContent(pageNumber = 1, rawText = pageOneText),
                PdfPageContent(pageNumber = 2, rawText = pageTwoText)
            ),
            fileName = "cmb_statement.pdf",
            zoneId = shanghaiZone
        )

        assertEquals("BANK_PDF", result.detectedFormat)
        assertEquals(11, result.items.size)

        val expected = listOf(
            Triple(TransactionType.TRANSFER, 13000L, "测试用户"),
            Triple(TransactionType.INCOME, 88L, "其他应收款-活动现金红包内部户"),
            Triple(TransactionType.TRANSFER, 10000L, "测试用户"),
            Triple(TransactionType.TRANSFER, 601L, "测试用户"),
            Triple(TransactionType.INCOME, 600L, "其它应收款-零售客户移动支付鼓励金专户"),
            Triple(TransactionType.TRANSFER, 601L, "测试用户"),
            Triple(TransactionType.INCOME, 600L, "其它应收款-零售客户移动支付鼓励金专户"),
            Triple(TransactionType.TRANSFER, 10000L, "测试用户"),
            Triple(TransactionType.EXPENSE, 1550L, "永年新校园超市"),
            Triple(TransactionType.TRANSFER, 990L, "测试用户"),
            Triple(TransactionType.TRANSFER, 10L, "测试用户")
        )
        expected.forEachIndexed { index, (type, amountCents, merchant) ->
            val item = result.items[index]
            assertEquals("type at index $index", type, item.type)
            assertEquals("amountCents at index $index", amountCents, item.amountCents)
            assertEquals("merchant at index $index", merchant, item.merchant)
        }

        result.items.forEach { item ->
            assertEquals(PaymentChannel.BANK, item.channel)
            assertEquals(CaptureSource.IMPORT, item.source)
            assertEquals(false, item.hasTimeOfDay)
            assertEquals("招商银行(8888)", item.fundingMethod)
        }

        assertEquals(millisAt(2026, 11, 16, 12, 0, 0), result.items[0].timestampMillis)
    }

    @Test
    fun parsePages_genericBankStatementWithSignedAmounts_parsesExpenseAndIncome() {
        val rawText = buildString {
            appendLine("中国建设银行个人活期账户交易明细")
            appendLine("卡号：6217000012345678")
            appendLine("交易日期 摘要 交易金额 账户余额 对方户名")
            appendLine("2026-11-10 消费 -45.00 1,200.00 星巴克咖啡")
            appendLine("2026-11-11 工资代发 +15,000.00 16,200.00 某科技有限公司")
        }

        val result = BankPdfParser.parsePages(
            pages = listOf(PdfPageContent(pageNumber = 1, rawText = rawText)),
            fileName = "ccb_statement.pdf",
            zoneId = shanghaiZone
        )

        assertEquals("BANK_PDF", result.detectedFormat)
        assertEquals(2, result.items.size)

        val expense = result.items[0]
        assertEquals(PaymentChannel.BANK, expense.channel)
        assertEquals(CaptureSource.IMPORT, expense.source)
        assertEquals(TransactionType.EXPENSE, expense.type)
        assertEquals(4500L, expense.amountCents)
        assertEquals("星巴克咖啡", expense.merchant)
        assertEquals("建设银行(5678)", expense.fundingMethod)
        assertEquals(false, expense.hasTimeOfDay)
        assertEquals(millisAt(2026, 11, 10, 12, 0, 0), expense.timestampMillis)

        val income = result.items[1]
        assertEquals(PaymentChannel.BANK, income.channel)
        assertEquals(CaptureSource.IMPORT, income.source)
        assertEquals(TransactionType.INCOME, income.type)
        assertEquals(1500000L, income.amountCents)
        assertEquals("某科技有限公司", income.merchant)
        assertEquals("建设银行(5678)", income.fundingMethod)
        assertEquals(false, income.hasTimeOfDay)
    }

    @Test
    fun parsePages_structuredTokenCoordinates_reconstructsWrappedColumnsCleanly() {
        val page = PdfPageContent(
            pageNumber = 1,
            width = 595f,
            height = 842f,
            rawText = "",
            tokens = listOf(
                token("招商银行交易流水", 40f, 40f, 200f),
                token("户  名：测试用户", 40f, 62f, 160f),
                token("账号：5342********8888", 300f, 62f, 220f),
                token("记账日期", 40f, 160f),
                token("货币", 110f, 160f),
                token("交易金额", 170f, 160f),
                token("联机余额", 240f, 160f),
                token("交易摘要", 320f, 160f),
                token("对手信息", 420f, 160f),
                token("2026-11-16", 40f, 200f),
                token("CNY", 110f, 200f),
                token("-25.00", 170f, 200f),
                token("100.00", 240f, 200f),
                token("快捷支付", 320f, 200f),
                token("永年新校园超", 420f, 200f, 80f),
                token("市", 420f, 214f, 14f),
                token("2026-11-16", 40f, 240f),
                token("CNY", 110f, 240f),
                token("50.00", 170f, 240f),
                token("150.00", 240f, 240f),
                token("网联收款", 320f, 240f),
                token("测试用户", 420f, 240f)
            )
        )

        val result = BankPdfParser.parsePages(
            pages = listOf(page),
            fileName = "cmb_statement.pdf",
            zoneId = shanghaiZone
        )

        assertEquals("BANK_PDF", result.detectedFormat)
        assertEquals(2, result.items.size)

        val wrappedExpense = result.items[0]
        assertEquals(PaymentChannel.BANK, wrappedExpense.channel)
        assertEquals(CaptureSource.IMPORT, wrappedExpense.source)
        assertEquals(TransactionType.EXPENSE, wrappedExpense.type)
        assertEquals(2500L, wrappedExpense.amountCents)
        assertEquals("永年新校园超市", wrappedExpense.merchant)
        assertEquals("招商银行(8888)", wrappedExpense.fundingMethod)
        assertEquals(false, wrappedExpense.hasTimeOfDay)

        val transfer = result.items[1]
        assertEquals(PaymentChannel.BANK, transfer.channel)
        assertEquals(CaptureSource.IMPORT, transfer.source)
        assertEquals(TransactionType.TRANSFER, transfer.type)
        assertEquals(5000L, transfer.amountCents)
        assertEquals("测试用户", transfer.merchant)
        assertEquals("招商银行(8888)", transfer.fundingMethod)
        assertEquals(false, transfer.hasTimeOfDay)
    }

    @Test
    fun parsePages_cmbHeaderBankWinsOverCounterpartyBank_inFundingMethod() {
        val rawText = buildString {
            appendLine("招商银行交易流水")
            appendLine("卡号 6214 8300 1234 7205")
            appendLine("户名：测试用户")
            appendLine("记账日期 货币 交易金额 联机余额 交易摘要 对手信息")
            appendLine("2026-11-16 CNY -35.00 100.00 快捷支付 工商银行")
        }

        val result = BankPdfParser.parsePages(
            pages = listOf(PdfPageContent(pageNumber = 1, rawText = rawText)),
            fileName = "cmb_statement.pdf",
            zoneId = shanghaiZone
        )

        assertEquals(1, result.items.size)
        val item = result.items.single()
        // The statement issuer (招商银行) must win over a counterparty bank mention (工商银行).
        assertEquals("招商银行(7205)", item.fundingMethod)
        assertEquals(TransactionType.EXPENSE, item.type)
        assertEquals(3500L, item.amountCents)
        assertEquals("工商银行", item.merchant)
    }

    @Test
    fun parsePages_headerWithoutHolderName_doesNotUseCounterpartyColumnAsHolderOrCard() {
        val rawText = buildString {
            appendLine("中国工商银行交易明细")
            appendLine("对方户名 对方账号 6214 8300 1234 7205")
            appendLine("交易日期 摘要 交易金额 账户余额 对方户名")
            appendLine("2026-11-10 转账 -45.00 1200.00 对方账号")
        }

        val result = BankPdfParser.parsePages(
            pages = listOf(PdfPageContent(pageNumber = 1, rawText = rawText)),
            fileName = "icbc_statement.pdf",
            zoneId = shanghaiZone
        )

        assertEquals(1, result.items.size)
        val item = result.items.single()
        // If "对方账号" were captured as the account holder, this same-name "转账" would become TRANSFER.
        assertEquals(TransactionType.EXPENSE, item.type)
        // If the counterparty account digits were captured as the card number, this would be 工商银行(7205).
        assertEquals("工商银行", item.fundingMethod)
        assertEquals(4500L, item.amountCents)
        assertEquals("对方账号", item.merchant)
    }

    @Test
    fun parsePages_walletSettlementCounterparty_outflowsStayExpenseButNetInflowIsTransfer() {
        val rawText = buildString {
            appendLine("招商银行交易流水")
            appendLine("户名：测试用户")
            appendLine("账号：5342********8888")
            appendLine("记账日期 货币 交易金额 联机余额 交易摘要 对手信息")
            appendLine("2026-11-17 CNY -35.00 100.00 网联付款 财付通")
            appendLine("2026-11-18 CNY -35.00 65.00 网联付款 支付宝支付科技有限公司")
            appendLine("2026-11-19 CNY 100.00 165.00 网联收款 财付通")
        }

        val result = BankPdfParser.parsePages(
            pages = listOf(PdfPageContent(pageNumber = 1, rawText = rawText)),
            fileName = "cmb_statement.pdf",
            zoneId = shanghaiZone
        )

        assertEquals(3, result.items.size)

        val tenpayOutflow = result.items[0]
        assertEquals(TransactionType.EXPENSE, tenpayOutflow.type)
        assertEquals(3500L, tenpayOutflow.amountCents)
        assertEquals("财付通", tenpayOutflow.merchant)

        val alipayOutflow = result.items[1]
        assertEquals(TransactionType.EXPENSE, alipayOutflow.type)
        assertEquals(3500L, alipayOutflow.amountCents)
        assertEquals("支付宝支付科技有限公司", alipayOutflow.merchant)

        val netInflow = result.items[2]
        assertEquals(TransactionType.TRANSFER, netInflow.type)
        assertEquals(10000L, netInflow.amountCents)
        assertEquals("财付通", netInflow.merchant)
    }

    @Test
    fun parsePages_ownAccountBankToBankTransfer_isTransfer() {
        val rawText = buildString {
            appendLine("招商银行交易流水")
            appendLine("户名：测试用户")
            appendLine("账号：5342********8888")
            appendLine("记账日期 货币 交易金额 联机余额 交易摘要 对手信息")
            appendLine("2026-11-17 CNY -500.00 1000.00 跨行转出 测试用户")
            appendLine("2026-11-18 CNY -500.00 500.00 转账 测试用户")
        }

        val result = BankPdfParser.parsePages(
            pages = listOf(PdfPageContent(pageNumber = 1, rawText = rawText)),
            fileName = "cmb_statement.pdf",
            zoneId = shanghaiZone
        )

        assertEquals(2, result.items.size)
        result.items.forEach { item ->
            assertEquals(TransactionType.TRANSFER, item.type)
            assertEquals(50000L, item.amountCents)
            assertEquals("测试用户", item.merchant)
            assertEquals("招商银行(8888)", item.fundingMethod)
            assertEquals(false, item.hasTimeOfDay)
        }
    }

    @Test
    fun parsePages_combinedPageFooterAfterLastRow_doesNotCorruptCounterparty() {
        val rawText = buildString {
            appendLine("招商银行交易流水")
            appendLine("户名：测试用户")
            appendLine("账号：5342********8888")
            appendLine("记账日期 货币 交易金额 联机余额 交易摘要 对手信息")
            appendLine("2026-11-17 CNY -35.00 100.00 快捷支付 星巴克")
            appendLine("2026-11-17 CNY -25.00 75.00 快捷支付 某便利店")
            appendLine("第 1 页 共 3 页")
        }

        val result = BankPdfParser.parsePages(
            pages = listOf(PdfPageContent(pageNumber = 1, rawText = rawText)),
            fileName = "cmb_statement.pdf",
            zoneId = shanghaiZone
        )

        assertEquals(2, result.items.size)
        val last = result.items.last()
        assertEquals("某便利店", last.merchant)
        assertEquals(2500L, last.amountCents)
        assertEquals(TransactionType.EXPENSE, last.type)
    }

    @Test
    fun parsePages_currencyPrefixedNegativeAmount_andUnsignedDualColumnTable_resolveSignsByDirection() {
        val currencyPrefixedText = buildString {
            appendLine("中国建设银行个人活期账户交易明细")
            appendLine("卡号：6217000012345678")
            appendLine("交易日期 摘要 交易金额 账户余额 对方户名")
            appendLine("2026-11-10 消费 ¥-29.30 1,200.00 某便利店")
        }

        val currencyPrefixed = BankPdfParser.parsePages(
            pages = listOf(PdfPageContent(pageNumber = 1, rawText = currencyPrefixedText)),
            fileName = "ccb_statement.pdf",
            zoneId = shanghaiZone
        )

        assertEquals(1, currencyPrefixed.items.size)
        val negative = currencyPrefixed.items.single()
        assertEquals(TransactionType.EXPENSE, negative.type)
        assertEquals(2930L, negative.amountCents)
        assertEquals("某便利店", negative.merchant)
        assertEquals("建设银行(5678)", negative.fundingMethod)

        val dualColumnText = buildString {
            appendLine("中国建设银行个人活期账户交易明细")
            appendLine("卡号：6217000012345678")
            appendLine("交易日期 摘要 支出金额 收入金额 余额 对方户名")
            appendLine("2026-11-10 消费 45.00 0.00 1000.00 星巴克")
            appendLine("2026-11-11 工资 0.00 15,000.00 16000.00 某科技公司")
        }

        val dualColumn = BankPdfParser.parsePages(
            pages = listOf(PdfPageContent(pageNumber = 1, rawText = dualColumnText)),
            fileName = "ccb_statement.pdf",
            zoneId = shanghaiZone
        )

        assertEquals(2, dualColumn.items.size)
        val expense = dualColumn.items[0]
        assertEquals(TransactionType.EXPENSE, expense.type)
        assertEquals(4500L, expense.amountCents)
        assertEquals("星巴克", expense.merchant)

        val income = dualColumn.items[1]
        assertEquals(TransactionType.INCOME, income.type)
        assertEquals(1500000L, income.amountCents)
        assertEquals("某科技公司", income.merchant)

        dualColumn.items.forEach { item ->
            assertEquals("建设银行(5678)", item.fundingMethod)
            assertEquals(false, item.hasTimeOfDay)
        }
    }

    @Test
    fun parsePages_splitCjkHeaderTokensAndWideBalanceToken_keepsAmountAndBalanceSeparate() {
        val page = PdfPageContent(
            pageNumber = 1,
            width = 595f,
            height = 842f,
            rawText = "",
            tokens = listOf(
                token("招商银行交易流水", 40f, 40f, 200f),
                token("户  名：测试用户", 40f, 62f, 160f),
                token("账号：5342********8888", 300f, 62f, 220f),
                token("记账日期", 40f, 160f),
                token("货币", 110f, 160f),
                token("交易金额", 170f, 160f),
                token("联机余额", 240f, 160f),
                token("摘", 320f, 160f, 14f),
                token("要", 334f, 160f, 14f),
                token("对方", 420f, 160f, 28f),
                token("户名", 448f, 160f, 28f),
                token("2026-11-16", 40f, 200f),
                token("CNY", 110f, 200f),
                token("-25.00", 170f, 200f),
                token("100.00", 200f, 200f, 80f),
                token("快捷支付", 320f, 200f),
                token("星巴克", 420f, 200f)
            )
        )

        val result = BankPdfParser.parsePages(
            pages = listOf(page),
            fileName = "cmb_statement.pdf",
            zoneId = shanghaiZone
        )

        assertEquals(1, result.items.size)
        val item = result.items.single()
        assertEquals(TransactionType.EXPENSE, item.type)
        assertEquals(2500L, item.amountCents)
        assertEquals("星巴克", item.merchant)
        assertEquals("招商银行(8888)", item.fundingMethod)
        assertEquals("快捷支付", item.note)
        assertEquals(false, item.hasTimeOfDay)
    }

    @Test
    fun parsePages_cmbVerticallyCenteredWrappedCounterpartyTokens_keepRowsClean() {
        val page = PdfPageContent(
            pageNumber = 1,
            width = 595f,
            height = 842f,
            rawText = "",
            tokens = listOf(
                token("招商银行交易流水", 40f, 40f, 200f),
                token("户  名：测试用户", 40f, 62f, 160f),
                token("账号：5342********8888", 300f, 62f, 220f),
                token("记账日期", 40f, 160f),
                token("货币", 110f, 160f),
                token("交易金额", 170f, 160f),
                token("联机余额", 240f, 160f),
                token("交易摘要", 320f, 160f),
                token("对手信息", 420f, 160f),
                token("2026-11-16", 40f, 240f),
                token("CNY", 110f, 240f),
                token("130.00", 170f, 240f),
                token("130.00", 240f, 240f),
                token("网联收款", 320f, 240f),
                token("测试用户", 420f, 240f),
                token("2026-11-16", 40f, 272f),
                token("CNY", 110f, 272f),
                token("0.88", 170f, 272f),
                token("130.88", 240f, 272f),
                token("活动现金红包", 320f, 272f),
                token("其他应收款-活动现金红包内部", 420f, 266f),
                token("户", 420f, 278f, 14f),
                token("2026-11-16", 40f, 304f),
                token("CNY", 110f, 304f),
                token("-6.01", 170f, 304f),
                token("124.87", 240f, 304f),
                token("快捷支付", 320f, 304f),
                token("测试用户", 420f, 304f),
                token("2026-11-16", 40f, 336f),
                token("CNY", 110f, 336f),
                token("6.00", 170f, 336f),
                token("130.87", 240f, 336f),
                token("一网通支付鼓励金", 320f, 336f),
                token("其它应收款-零售客户移动支付", 420f, 330f),
                token("鼓励金专户", 420f, 342f)
            )
        )

        val result = BankPdfParser.parsePages(
            pages = listOf(page),
            fileName = "cmb_statement.pdf",
            zoneId = shanghaiZone
        )

        assertEquals("BANK_PDF", result.detectedFormat)
        assertEquals(4, result.items.size)

        val row1 = result.items[0]
        assertEquals("row 1 must not absorb row 2 wrapped counterparty", "测试用户", row1.merchant)
        assertEquals(TransactionType.TRANSFER, row1.type)
        assertEquals(13000L, row1.amountCents)

        val row2 = result.items[1]
        assertEquals("其他应收款-活动现金红包内部户", row2.merchant)
        assertEquals(TransactionType.INCOME, row2.type)
        assertEquals(88L, row2.amountCents)

        val row3 = result.items[2]
        assertEquals("row 3 must stay an own-account transfer", "测试用户", row3.merchant)
        assertEquals(TransactionType.TRANSFER, row3.type)
        assertEquals(601L, row3.amountCents)

        val row4 = result.items[3]
        assertEquals("其它应收款-零售客户移动支付鼓励金专户", row4.merchant)
        assertEquals(TransactionType.INCOME, row4.type)
        assertEquals(600L, row4.amountCents)

        result.items.forEach { item ->
            assertEquals(PaymentChannel.BANK, item.channel)
            assertEquals(CaptureSource.IMPORT, item.source)
            assertEquals(false, item.hasTimeOfDay)
            assertEquals("招商银行(8888)", item.fundingMethod)
        }
    }

    @Test
    fun parsePages_splitIncomeDebitHeader_rebuildsAmountColumnAndParsesRefund() {
        val page = PdfPageContent(
            pageNumber = 1,
            width = 595f,
            height = 842f,
            rawText = "",
            tokens = listOf(
                token("中国建设银行个人活期账户交易明细", 40f, 40f, 240f),
                token("交易日期", 40f, 160f),
                token("摘要", 150f, 160f),
                token("收入/", 210f, 160f, 30f),
                token("支出金额", 244f, 160f),
                token("余额", 330f, 160f),
                token("对方户名", 430f, 160f),
                token("2026-11-14", 40f, 200f),
                token("退款", 150f, 200f),
                token("+35.00", 240f, 200f),
                token("1000.43", 330f, 200f),
                token("支付宝支付科技有限公司", 430f, 200f, 140f),
                token("2026-11-19", 40f, 240f),
                token("消费", 150f, 240f),
                token("-55.60", 240f, 240f),
                token("279.16", 330f, 240f),
                token("某电商旗舰店", 430f, 240f)
            )
        )

        val result = BankPdfParser.parsePages(
            pages = listOf(page),
            fileName = "ccb_statement.pdf",
            zoneId = shanghaiZone
        )

        assertEquals(2, result.items.size)

        val refund = result.items[0]
        assertEquals(TransactionType.REFUND, refund.type)
        assertEquals(3500L, refund.amountCents)
        assertEquals("支付宝支付科技有限公司", refund.merchant)

        val expense = result.items[1]
        assertEquals(TransactionType.EXPENSE, expense.type)
        assertEquals(5560L, expense.amountCents)
        assertEquals("某电商旗舰店", expense.merchant)
    }

    @Test
    fun parsePages_plusSignInDebitColumn_staysInflowRefund() {
        val page = PdfPageContent(
            pageNumber = 1,
            width = 595f,
            height = 842f,
            rawText = "",
            tokens = listOf(
                token("中国建设银行个人活期账户交易明细", 40f, 40f, 240f),
                token("交易日期", 40f, 160f),
                token("摘要", 150f, 160f),
                token("支出金额", 260f, 160f),
                token("余额", 360f, 160f),
                token("对方户名", 460f, 160f),
                token("2026-11-10", 40f, 200f),
                token("退款", 150f, 200f),
                token("+200.00", 250f, 200f),
                token("1000.00", 360f, 200f),
                token("支付宝支付科技有限公司", 460f, 200f, 140f)
            )
        )

        val result = BankPdfParser.parsePages(
            pages = listOf(page),
            fileName = "ccb_statement.pdf",
            zoneId = shanghaiZone
        )

        assertEquals(1, result.items.size)
        val item = result.items.single()
        assertEquals(TransactionType.REFUND, item.type)
        assertEquals(20000L, item.amountCents)
        assertEquals("支付宝支付科技有限公司", item.merchant)
        assertEquals(false, item.hasTimeOfDay)
    }
}
