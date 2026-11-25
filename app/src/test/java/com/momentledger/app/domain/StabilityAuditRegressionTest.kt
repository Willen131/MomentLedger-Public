package com.momentledger.app.domain

import com.momentledger.app.domain.backup.LocalBackupCodec
import com.momentledger.app.domain.engine.DeduplicationEngine
import com.momentledger.app.domain.engine.DedupDecision
import com.momentledger.app.domain.model.CaptureSource
import com.momentledger.app.domain.model.PaymentChannel
import com.momentledger.app.domain.model.ParsedPayment
import com.momentledger.app.domain.model.TransactionRecord
import com.momentledger.app.domain.model.TransactionType
import com.momentledger.app.domain.parser.BillFileParser
import com.momentledger.app.domain.parser.OcrImageInput
import com.momentledger.app.domain.parser.OcrLine
import com.momentledger.app.domain.parser.PaymentTextClassifier
import com.momentledger.app.domain.parser.ScreenshotBillParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StabilityAuditRegressionTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun epochMillis(y: Int, m: Int, d: Int, h: Int, min: Int, sec: Int = 0): Long =
        LocalDateTime.of(LocalDate.of(y, m, d), LocalTime.of(h, min, sec))
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    @Test
    fun billFileParserRejectsNonXlsxZipAsUnknown() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("readme.txt"))
            zos.write("not an xlsx".toByteArray())
            zos.closeEntry()
        }
        val result = BillFileParser.parse("alipay.zip", ByteArrayInputStream(baos.toByteArray()), zone)
        assertEquals("UNKNOWN", result.detectedFormat)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun billFileParserSkipsMalformedHugeColumnIndexWithoutOom() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zos.write(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="1">
                      <c r="A1" t="inlineStr"><is><t>交易时间</t></is></c>
                      <c r="B1" t="inlineStr"><is><t>交易对方</t></is></c>
                      <c r="C1" t="inlineStr"><is><t>收/支</t></is></c>
                      <c r="D1" t="inlineStr"><is><t>金额(元)</t></is></c>
                      <c r="ZZZZZZ1" t="inlineStr"><is><t>恶意超宽列</t></is></c>
                    </row>
                    <row r="2">
                      <c r="A2" t="inlineStr"><is><t>2026-04-10 12:30:00</t></is></c>
                      <c r="B2" t="inlineStr"><is><t>便利店</t></is></c>
                      <c r="C2" t="inlineStr"><is><t>支出</t></is></c>
                      <c r="D2" t="inlineStr"><is><t>¥12.50</t></is></c>
                      <c r="ZZZZZZ2" t="inlineStr"><is><t>ignored</t></is></c>
                    </row>
                  </sheetData>
                </worksheet>
                """.trimIndent().toByteArray(Charsets.UTF_8)
            )
            zos.closeEntry()
        }
        val result = BillFileParser.parse("bill.xlsx", ByteArrayInputStream(baos.toByteArray()), zone)
        assertEquals(1, result.items.size)
        assertEquals(1250L, result.items.first().amountCents)
    }

    @Test
    fun screenshotBillParserKeepsMinusRefundedRowAsExpenseAndRejectsFailedDetail() {
        val listInput = OcrImageInput(
            lines = listOf(
                OcrLine("账单", 40, 20, 120, 60),
                OcrLine("2026年4月", 40, 80, 200, 110),
                OcrLine("咖啡店", 40, 140, 220, 180),
                OcrLine("-35.00", 800, 140, 960, 180),
                OcrLine("已全额退款", 40, 195, 220, 225),
                OcrLine("咖啡店退款", 40, 280, 240, 320),
                OcrLine("+35.00", 800, 280, 960, 320),
                OcrLine("退款成功", 40, 335, 220, 365)
            ),
            fallbackTimestampMillis = epochMillis(2026, 4, 15, 10, 0)
        )
        val listParsed = ScreenshotBillParser.parse(listInput, zone)
        assertEquals(2, listParsed.candidates.size)
        assertEquals(TransactionType.EXPENSE, listParsed.candidates[0].payment.type)
        assertEquals(TransactionType.REFUND, listParsed.candidates[1].payment.type)

        val failedDetail = OcrImageInput(
            lines = listOf(
                OcrLine("微信支付", 90, 80, 240, 110),
                OcrLine("支付失败", 90, 150, 240, 180),
                OcrLine("-28.00", 90, 220, 220, 270),
                OcrLine("商户全称 测试餐厅", 90, 330, 500, 360)
            ),
            fallbackTimestampMillis = epochMillis(2026, 4, 15, 10, 0)
        )
        val failedParsed = ScreenshotBillParser.parse(failedDetail, zone)
        assertTrue(failedParsed.candidates.isEmpty())
    }

    @Test
    fun screenshotBillParserRollsDecemberBackToPreviousYearInJanuary() {
        val input = OcrImageInput(
            lines = listOf(
                OcrLine("示例商店", 90, 96, 300, 130),
                OcrLine("-15.00", 90, 220, 240, 270),
                OcrLine("支付时间 12月28日 14:30:00", 90, 330, 620, 370)
            ),
            fallbackTimestampMillis = epochMillis(2026, 1, 3, 9, 0)
        )
        val parsed = ScreenshotBillParser.parse(input, zone)
        assertEquals(1, parsed.candidates.size)
        assertEquals(epochMillis(2025, 12, 28, 14, 30, 0), parsed.candidates.first().payment.timestampMillis)
    }

    @Test
    fun paymentTextClassifierPreservesActualExpressRefundAndIncomingTransfer() {
        assertEquals(
            TransactionType.REFUND,
            PaymentTextClassifier.classifyTransactionType("极速退款已到账 ¥50.00")
        )
        assertEquals(
            TransactionType.INCOME,
            PaymentTextClassifier.classifyTransactionType("对方已转账给你，收到转账 ¥100.00")
        )
    }

    @Test
    fun dedupEnginePreservesPreciseTimestampWhenImportingDateOnlyRow() {
        val engine = DeduplicationEngine(zoneId = zone)
        val realTimeTs = epochMillis(2026, 4, 10, 18, 25, 42)
        val existing = TransactionRecord(
            id = 1L,
            timestampMillis = realTimeTs,
            localDate = "2026-04-10",
            hourOfDay = 18,
            amountCents = 4200L,
            type = TransactionType.EXPENSE,
            channel = PaymentChannel.WECHAT,
            fundingMethod = "招商银行(1234)",
            merchant = "拉面馆",
            categoryId = "exp_food",
            note = "",
            excludedFromStats = false,
            captureSource = CaptureSource.OCR,
            externalOrderId = null,
            createdAtMillis = realTimeTs,
            updatedAtMillis = realTimeTs
        )
        val dateOnlyImport = ParsedPayment(
            channel = PaymentChannel.BANK,
            fundingMethod = "招商银行(1234)",
            type = TransactionType.EXPENSE,
            amountCents = 4200L,
            merchant = "拉面馆",
            timestampMillis = epochMillis(2026, 4, 10, 18, 24, 0),
            source = CaptureSource.IMPORT,
            externalOrderId = "BANK_001",
            note = "",
            hasTimeOfDay = false
        )
        val decision = engine.evaluateImport(dateOnlyImport, null, listOf(existing))
        assertTrue(decision is DedupDecision.MergeIntoExisting)
        val merged = (decision as DedupDecision.MergeIntoExisting).updatedRecord
        assertEquals(realTimeTs, merged.timestampMillis)
        assertEquals(18, merged.hourOfDay)
    }

    @Test
    fun csvExportNeutralizesFormulaInjectionPrefixes() {
        val ts = epochMillis(2026, 4, 10, 12, 0, 0)
        val record = TransactionRecord(
            id = 1L,
            timestampMillis = ts,
            localDate = "2026-04-10",
            hourOfDay = 12,
            amountCents = 1000L,
            type = TransactionType.EXPENSE,
            channel = PaymentChannel.MANUAL,
            fundingMethod = "",
            merchant = "=HYPERLINK(\"http://evil\")",
            categoryId = "exp_food",
            note = "+cmd",
            excludedFromStats = false,
            captureSource = CaptureSource.MANUAL,
            externalOrderId = null,
            createdAtMillis = ts,
            updatedAtMillis = ts
        )
        val csv = LocalBackupCodec.exportTransactionsCsv(listOf(record), mapOf("exp_food" to "餐饮"), zone)
        assertTrue(csv.contains("'=HYPERLINK("))
        assertTrue(csv.contains(",'+cmd,"))
    }

    @Test
    fun modernWechatReceiptWithScanRefundHintStaysExpenseAndIgnoresStatusBarNoise() {
        val input = OcrImageInput(
            lines = listOf(
                OcrLine("08:20", 40, 20, 120, 50),
                OcrLine("グ令31%", 820, 20, 990, 50),
                OcrLine("×", 40, 100, 80, 140),
                OcrLine("永年新校园超市", 300, 320, 700, 370),
                OcrLine("-9.50", 360, 420, 640, 500),
                OcrLine("|当前状态 支付成功", 80, 650, 520, 690),
                OcrLine("支付时间 2026年11月21日 17:17:13", 80, 710, 780, 750),
                OcrLine("商品 永年新校园超市(永年新北邮店)", 80, 770, 820, 810),
                OcrLine("|商户全称 北京永年新商贸有限公司", 80, 830, 780, 870),
                OcrLine("收单机构 易宝支付有限公司", 80, 890, 680, 930),
                OcrLine("支付方式 |招商银行储蓄卡(7205)", 80, 950, 740, 990),
                OcrLine("交易单号 4200003141202611216750781887", 80, 1010, 880, 1050),
                OcrLine("商户单号 可在支持的商户扫码退款", 80, 1070, 780, 1110),
                OcrLine("5504350230261121", 280, 1280, 720, 1320),
                OcrLine("账单服务", 80, 1450, 240, 1490),
                OcrLine("@对订单有疑惑 同发起群收款", 80, 1540, 820, 1580),
                OcrLine("目在此商广的交易 ⑥申请电子凭证", 80, 1610, 860, 1650)
            ),
            fallbackTimestampMillis = epochMillis(2026, 11, 22, 8, 20)
        )

        val parsed = ScreenshotBillParser.parse(input, zone)
        assertEquals(1, parsed.candidates.size)
        val payment = parsed.candidates.first().payment
        assertEquals(TransactionType.EXPENSE, payment.type)
        assertEquals(950L, payment.amountCents)
        assertEquals(PaymentChannel.WECHAT, payment.channel)
        assertEquals("北京永年新商贸有限公司", payment.merchant)
        assertEquals("招商银行储蓄卡(7205)", payment.fundingMethod)
        assertEquals("", payment.note)
    }

    @Test
    fun detailReceiptFallsBackToHeadingAboveAmountInsteadOfStatusBarBatteryNoise() {
        val input = OcrImageInput(
            lines = listOf(
                OcrLine("08:20", 40, 20, 120, 50),
                OcrLine("グ令31%", 820, 20, 990, 50),
                OcrLine("永年新校园超市", 300, 320, 700, 370),
                OcrLine("-9.50", 360, 420, 640, 500),
                OcrLine("当前状态 支付成功", 80, 650, 520, 690),
                OcrLine("支付时间 2026年11月21日 17:17:13", 80, 710, 780, 750),
                OcrLine("支付方式 招商银行储蓄卡(7205)", 80, 950, 740, 990),
                OcrLine("商户单号 可在支持的商户扫码退款", 80, 1070, 780, 1110)
            ),
            fallbackTimestampMillis = epochMillis(2026, 11, 22, 8, 20)
        )

        val parsed = ScreenshotBillParser.parse(input, zone)
        assertEquals(1, parsed.candidates.size)
        val payment = parsed.candidates.first().payment
        assertEquals(TransactionType.EXPENSE, payment.type)
        assertEquals("永年新校园超市", payment.merchant)
    }
}
