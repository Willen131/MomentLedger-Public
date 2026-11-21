package com.momentledger.app.domain.parser

import com.momentledger.app.domain.model.PaymentChannel
import com.momentledger.app.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Regression tests built from hand-written OCR geometry and fictional receipt text.
 * No screenshots, real merchants, or real transaction data are used.
 */
class ScreenshotBillParserTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    // Deliberately different from every receipt date so forgotten dates are easy to spot.
    private val fallbackMillis: Long = epochMillis(2026, 4, 17, 13, 24, 56)

    @Test
    fun parsesWechatDetailFromSeparateGeometricColumns() {
        val input = OcrImageInput(
            lines = listOf(
                ocr("18:17", left = 40, top = 24, right = 108, bottom = 52),
                ocr("5G 100%", left = 760, top = 24, right = 1000, bottom = 52),
                ocr("支付成功", left = 90, top = 166, right = 260, bottom = 198),
                ocr("一7.80", left = 90, top = 242, right = 230, bottom = 300),
                ocr("支付时间", left = 90, top = 338, right = 220, bottom = 370),
                ocr("2025 年 1| 月 8 日", left = 320, top = 338, right = 650, bottom = 370),
                ocr("09：42：36", left = 690, top = 338, right = 910, bottom = 370),
                ocr("|商户全称", left = 90, top = 408, right = 220, bottom = 440),
                ocr("样例便利店有限公司", left = 320, top = 408, right = 690, bottom = 440),
                ocr("支付方式", left = 90, top = 468, right = 220, bottom = 500),
                ocr("|零钱", left = 320, top = 468, right = 420, bottom = 500),
                ocr("本服务由财付通提供", left = 90, top = 910, right = 510, bottom = 942),
                ocr("发起群收款", left = 90, top = 1010, right = 310, bottom = 1042)
            ),
            fallbackTimestampMillis = fallbackMillis
        )

        val result = ScreenshotBillParser.parse(input, zone)
        val candidate = requireSingleDetail(result)
        val payment = candidate.payment

        assertEquals(PaymentChannel.WECHAT, payment.channel)
        assertEquals(TransactionType.EXPENSE, payment.type)
        assertEquals(780L, payment.amountCents)
        assertEquals("样例便利店有限公司", payment.merchant)
        assertEquals("零钱", payment.fundingMethod)
        assertEquals(epochMillis(2025, 11, 8, 9, 42, 36), payment.timestampMillis)
        assertTrue(payment.hasTimeOfDay)
        assertFalse(candidate.needsVerification)

        assertTrue(result.rawText.contains("18:17"))
        assertTrue(result.rawText.contains("2025 年 1| 月 8 日"))
        assertTrue(result.rawText.contains("09：42：36"))
        assertTrue(result.rawText.contains("|商户全称"))
        assertTrue(result.rawText.contains("本服务由财付通提供"))
        assertTrue(result.rawText.contains("发起群收款"))
    }

    @Test
    fun parsesMergedSpacedLabelsAndChineseDigitHour() {
        val input = OcrImageInput(
            lines = listOf(
                ocr("18:17", left = 40, top = 24, right = 108, bottom = 52),
                ocr("微信支付", left = 90, top = 96, right = 230, bottom = 128),
                ocr("支付成功", left = 90, top = 166, right = 260, bottom = 198),
                ocr("-7.80", left = 90, top = 242, right = 220, bottom = 300),
                ocr("支 付 时 间 2025 年 11 月 8 日 1一:42:36", left = 90, top = 338, right = 980, bottom = 370),
                ocr("|商 户 全 称 样例便利店有限公司", left = 90, top = 408, right = 850, bottom = 440),
                ocr("支付方式：|零钱", left = 90, top = 468, right = 450, bottom = 500)
            ),
            fallbackTimestampMillis = fallbackMillis
        )

        val result = ScreenshotBillParser.parse(input, zone)
        val candidate = requireSingleDetail(result)
        val payment = candidate.payment

        assertEquals(PaymentChannel.WECHAT, payment.channel)
        assertEquals(TransactionType.EXPENSE, payment.type)
        assertEquals(780L, payment.amountCents)
        assertEquals("样例便利店有限公司", payment.merchant)
        assertEquals("零钱", payment.fundingMethod)
        assertEquals(epochMillis(2025, 11, 8, 11, 42, 36), payment.timestampMillis)
        assertTrue(payment.hasTimeOfDay)
        assertFalse(candidate.needsVerification)
    }

    @Test
    fun keepsDateButRequiresVerificationWhenHourDigitIsAmbiguous() {
        val input = OcrImageInput(
            lines = listOf(
                ocr("18:17", left = 40, top = 24, right = 108, bottom = 52),
                ocr("微信支付", left = 90, top = 96, right = 230, bottom = 128),
                ocr("支付成功", left = 90, top = 166, right = 260, bottom = 198),
                ocr("-7.80", left = 90, top = 242, right = 220, bottom = 300),
                ocr("支付时间 2025 年 11 月 8 日 1二:42:36", left = 90, top = 338, right = 980, bottom = 370),
                ocr("|商户全称 样例便利店有限公司", left = 90, top = 408, right = 850, bottom = 440),
                ocr("下单时间 2025-11-08 10:30:00", left = 90, top = 478, right = 850, bottom = 510)
            ),
            fallbackTimestampMillis = fallbackMillis
        )

        val result = ScreenshotBillParser.parse(input, zone)
        val candidate = requireSingleDetail(result)
        val payment = candidate.payment
        val parsedLocal = localDateTime(payment.timestampMillis)

        assertEquals(PaymentChannel.WECHAT, payment.channel)
        assertEquals(TransactionType.EXPENSE, payment.type)
        assertEquals(780L, payment.amountCents)
        assertEquals("样例便利店有限公司", payment.merchant)
        assertEquals(LocalDate.of(2025, 11, 8), parsedLocal.toLocalDate())
        assertFalse(payment.hasTimeOfDay)
        assertTrue(candidate.needsVerification)
        assertTrue(
            "The OCR status-bar time 18:17 must not be used as the transaction time",
            payment.timestampMillis != epochMillis(2025, 11, 8, 18, 17, 0)
        )
        assertFalse(parsedLocal.toLocalTime() == LocalTime.of(18, 17))

        val retried = requireSingleDetail(ScreenshotBillParser.parse(input, zone, listOf(
            ocr("2025年11月8日 11:42:36", left = 90, top = 20)
        )))
        assertEquals(epochMillis(2025, 11, 8, 11, 42, 36), retried.payment.timestampMillis)
        assertFalse(retried.needsVerification)

        val conflictingDate = requireSingleDetail(ScreenshotBillParser.parse(input, zone, listOf(
            ocr("2025年11月9日 11:42:36", left = 90, top = 20)
        )))
        assertEquals(parsedLocal, localDateTime(conflictingDate.payment.timestampMillis))
        assertTrue(conflictingDate.needsVerification)
    }

    @Test
    fun keepsRefundTypeWhenRefundAmountIsWrittenWithMinusSign() {
        val input = OcrImageInput(
            lines = listOf(
                ocr("微信支付", left = 90, top = 96, right = 230, bottom = 128),
                ocr("退款成功", left = 90, top = 160, right = 250, bottom = 192),
                ocr("退款金额", left = 90, top = 230, right = 220, bottom = 262),
                ocr("-7.80", left = 320, top = 230, right = 450, bottom = 262),
                ocr("支付时间 2025 年 11 月 9 日 14:05:07（北京时间）", left = 90, top = 330, right = 900, bottom = 362),
                ocr("|商户全称 样例便利店有限公司", left = 90, top = 400, right = 850, bottom = 432)
            ),
            fallbackTimestampMillis = fallbackMillis
        )

        val result = ScreenshotBillParser.parse(input, zone)
        val candidate = requireSingleDetail(result)
        val payment = candidate.payment

        assertEquals(TransactionType.REFUND, payment.type)
        assertEquals(780L, payment.amountCents)
        assertEquals(PaymentChannel.WECHAT, payment.channel)
        assertEquals("样例便利店有限公司", payment.merchant)
        assertEquals(epochMillis(2025, 11, 9, 14, 5, 7), payment.timestampMillis)
        assertTrue(payment.hasTimeOfDay)
        assertFalse(candidate.needsVerification)
    }

    @Test
    fun readsMonthAndDayWhenTheReceiptOmitsTheYear() {
        val result = ScreenshotBillParser.parse(OcrImageInput(
            lines = listOf(
                ocr("示例商店", left = 90, top = 96),
                ocr("-7.80", left = 90, top = 242),
                ocr("支付时间 11 月 8 日 09:42:36", left = 90, top = 338)
            ),
            fallbackTimestampMillis = fallbackMillis
        ), zone)
        val candidate = requireSingleDetail(result)
        assertEquals(epochMillis(2026, 11, 8, 9, 42, 36), candidate.payment.timestampMillis)
        assertFalse(candidate.needsVerification)
    }

    @Test
    fun marksDetailForVerificationWhenPaymentTimeIsMissing() {
        val input = OcrImageInput(
            lines = listOf(
                ocr("微信支付", left = 90, top = 96, right = 230, bottom = 128),
                ocr("支付成功", left = 90, top = 166, right = 260, bottom = 198),
                ocr("-7.80", left = 90, top = 242, right = 220, bottom = 300),
                ocr("|商户全称 样例便利店有限公司", left = 90, top = 408, right = 850, bottom = 440)
            ),
            fallbackTimestampMillis = fallbackMillis
        )

        val result = ScreenshotBillParser.parse(input, zone)
        val candidate = requireSingleDetail(result)
        val payment = candidate.payment

        assertEquals(TransactionType.EXPENSE, payment.type)
        assertEquals(780L, payment.amountCents)
        assertEquals("样例便利店有限公司", payment.merchant)
        assertFalse(payment.hasTimeOfDay)
        assertTrue(candidate.needsVerification)
        assertEquals(LocalDate.of(2026, 4, 17), localDateTime(payment.timestampMillis).toLocalDate())
    }

    /**
     * Regression: status-bar / close / navigation chrome and a garbled merchant label must not
     * become the merchant, and an unlabeled logo line must lose to the company title next to the
     * standalone amount line.
     */
    @Test
    fun parsesCompanyTitleWithoutLeakingStatusBarChromeOrLogoText() {
        // Scenario A: two-column merchant row whose "商户全称" label was OCR-misread as "商 广 全 称".
        val garbledLabelResult = ScreenshotBillParser.parse(
            OcrImageInput(
                lines = listOf(
                    ocr("18:1720）", left = 40, top = 24, right = 200, bottom = 52),
                    ocr("×", left = 1016, top = 86, right = 1058, bottom = 126),
                    ocr("全部账单", left = 440, top = 90, right = 620, bottom = 126),
                    ocr("支付成功", left = 90, top = 166, right = 260, bottom = 198),
                    ocr("-34.60", left = 90, top = 242, right = 230, bottom = 300),
                    ocr("支付时间", left = 90, top = 338, right = 220, bottom = 370),
                    ocr("2025 年 6 月 12 日", left = 320, top = 338, right = 650, bottom = 370),
                    ocr("08:05:41", left = 690, top = 338, right = 910, bottom = 370),
                    ocr("| 商 广 全 称", left = 90, top = 408, right = 220, bottom = 440),
                    ocr("样例洗衣服务有限公司", left = 320, top = 408, right = 690, bottom = 440),
                    ocr("本服务由财付通提供", left = 90, top = 910, right = 510, bottom = 942)
                ),
                fallbackTimestampMillis = fallbackMillis
            ),
            zone
        )
        val garbledCandidate = requireSingleDetail(garbledLabelResult)
        val garbledPayment = garbledCandidate.payment

        assertEquals(PaymentChannel.WECHAT, garbledPayment.channel)
        assertEquals(TransactionType.EXPENSE, garbledPayment.type)
        assertEquals(3460L, garbledPayment.amountCents)
        assertEquals("样例洗衣服务有限公司", garbledPayment.merchant)
        assertEquals("", garbledPayment.note)
        assertEquals(epochMillis(2025, 6, 12, 8, 5, 41), garbledPayment.timestampMillis)
        assertTrue(garbledPayment.hasTimeOfDay)
        assertFalse(garbledCandidate.needsVerification)

        // Scenario B: no merchant label at all; logo, clock and close chrome surround the real title,
        // which sits directly above the standalone amount line.
        val unlabeledResult = ScreenshotBillParser.parse(
            OcrImageInput(
                lines = listOf(
                    ocr("18:17", left = 40, top = 24, right = 108, bottom = 52),
                    ocr("×", left = 1016, top = 86, right = 1058, bottom = 126),
                    ocr("示例生活", left = 90, top = 150, right = 230, bottom = 182),
                    ocr("样例护理服务有限公司", left = 90, top = 220, right = 650, bottom = 252),
                    ocr("-18.50", left = 90, top = 292, right = 220, bottom = 350),
                    ocr("支付时间", left = 90, top = 380, right = 220, bottom = 412),
                    ocr("2025 年 6 月 13 日", left = 320, top = 380, right = 650, bottom = 412),
                    ocr("21:16:04", left = 690, top = 380, right = 910, bottom = 412),
                    ocr("本服务由财付通提供", left = 90, top = 910, right = 510, bottom = 942)
                ),
                fallbackTimestampMillis = fallbackMillis
            ),
            zone
        )
        val unlabeledCandidate = requireSingleDetail(unlabeledResult)
        val unlabeledPayment = unlabeledCandidate.payment

        assertEquals(PaymentChannel.WECHAT, unlabeledPayment.channel)
        assertEquals(TransactionType.EXPENSE, unlabeledPayment.type)
        assertEquals(1850L, unlabeledPayment.amountCents)
        assertEquals("样例护理服务有限公司", unlabeledPayment.merchant)
        assertEquals("", unlabeledPayment.note)
        assertEquals(epochMillis(2025, 6, 13, 21, 16, 4), unlabeledPayment.timestampMillis)
        assertTrue(unlabeledPayment.hasTimeOfDay)
        assertFalse(unlabeledCandidate.needsVerification)
        assertFalse(unlabeledPayment.merchant == "示例生活")
        assertFalse(unlabeledPayment.merchant == "18:17")
        assertFalse(unlabeledPayment.merchant == "×")
    }

    @Test
    fun filtersDecoratedStatusTokensAndKeepsRealItemNotes() {
        val lines = listOf(
            ocr("I8:17中国移动", left = 40, top = 24, right = 210),
            ocr("5G 100%", left = 780, top = 24),
            ocr("微信支付", left = 90, top = 92),
            ocr("样例便利店", left = 90, top = 155),
            ocr("午餐套餐", left = 90, top = 220),
            ocr("付款金额：¥7.80", left = 90, top = 290),
            ocr("支付时间 2025-11-08 11:42:36", left = 90, top = 360)
        )
        val input = OcrImageInput(lines, fallbackTimestampMillis = fallbackMillis)
        val payment = requireSingleDetail(ScreenshotBillParser.parse(input, zone)).payment
        assertEquals("样例便利店", payment.merchant)
        assertEquals("午餐套餐", payment.note)
        val onlyChrome = input.copy(lines = lines.map {
            if (it.text == "午餐套餐") it.copy(text = "× 账单详情！") else it
        })
        val withChrome = requireSingleDetail(ScreenshotBillParser.parse(onlyChrome, zone)).payment
        assertEquals("样例便利店", withChrome.merchant)
        assertEquals("", withChrome.note)
    }

    /**
     * Scenario A: Alipay auto-debit detail whose OCR text never contains "支付宝".
     * The status-bar clock and battery glyph share one row, the title row is split in two
     * columns, and the merchant heading sits directly above the standalone amount.
     */
    @Test
    fun parsesAlipayAutoDebitDetailWithoutAlipayWordmark() {
        val input = OcrImageInput(
            lines = listOf(
                ocr("15:12", left = 40, top = 24, right = 108, bottom = 52),
                ocr("グ|令84%|", left = 820, top = 24, right = 1030, bottom = 52),
                ocr("〈账单详情", left = 60, top = 96, right = 240, bottom = 128),
                ocr("全部账单", left = 440, top = 96, right = 620, bottom = 128),
                ocr("样例商贸", left = 90, top = 190, right = 290, bottom = 222),
                ocr("-5.00", left = 90, top = 260, right = 220, bottom = 318),
                ocr("自动扣款成功", left = 90, top = 350, right = 320, bottom = 382),
                ocr("管理兌密支付", left = 90, top = 420, right = 300, bottom = 452),
                ocr("支付时间", left = 90, top = 490, right = 230, bottom = 522),
                ocr("2025 年 9 月 23 日", left = 320, top = 490, right = 650, bottom = 522),
                ocr("08:36:52", left = 690, top = 490, right = 910, bottom = 522),
                ocr("付款方式", left = 90, top = 560, right = 230, bottom = 592),
                ocr("示例银行储蓄卡(9988) >", left = 320, top = 560, right = 720, bottom = 592),
                ocr("支付奖励", left = 90, top = 630, right = 230, bottom = 662),
                ocr("账单管理", left = 90, top = 700, right = 230, bottom = 732)
            ),
            fallbackTimestampMillis = fallbackMillis
        )

        val result = ScreenshotBillParser.parse(input, zone)
        val candidate = requireSingleDetail(result)
        val payment = candidate.payment

        assertFalse(result.rawText.contains("支付宝"))
        assertEquals(PaymentChannel.ALIPAY, payment.channel)
        assertEquals(TransactionType.EXPENSE, payment.type)
        assertEquals(500L, payment.amountCents)
        assertEquals("样例商贸", payment.merchant)
        assertEquals("示例银行储蓄卡(9988)", payment.fundingMethod)
        assertFalse(payment.fundingMethod.contains(">"))
        assertEquals("", payment.note)
        assertEquals(epochMillis(2025, 9, 23, 8, 36, 52), payment.timestampMillis)
        assertTrue(payment.hasTimeOfDay)
        assertFalse(candidate.needsVerification)
    }

    /**
     * Scenario B: WeChat receipt with a Tenpay footer and an explicit full merchant name,
     * immediately followed by an OCR-misread acquiring-institution label ("收草机构").
     * The misread block must not become merchant or note; an explicit note added to a second
     * parse must survive as a legitimate note.
     */
    @Test
    fun parsesWechatFullMerchantAndSkipsMisreadAcquiringInstitution() {
        val baseLines = listOf(
            ocr("19:05", left = 40, top = 24, right = 108, bottom = 52),
            ocr("微信支付", left = 90, top = 96, right = 230, bottom = 128),
            ocr("支付成功", left = 90, top = 166, right = 260, bottom = 198),
            ocr("-12.34", left = 90, top = 242, right = 230, bottom = 300),
            ocr("支付时间 2025 年 10 月 6 日 19:05:41", left = 90, top = 330, right = 900, bottom = 362),
            ocr("|商户全称", left = 90, top = 408, right = 220, bottom = 440),
            ocr("样例网络科技有限公司", left = 320, top = 408, right = 690, bottom = 440),
            ocr("收草机构", left = 90, top = 468, right = 220, bottom = 500),
            ocr("示例支付服务有限公司", left = 320, top = 468, right = 690, bottom = 500),
            ocr("本服务由财付通提供", left = 90, top = 910, right = 510, bottom = 942)
        )

        val first = requireSingleDetail(ScreenshotBillParser.parse(
            OcrImageInput(lines = baseLines, fallbackTimestampMillis = fallbackMillis),
            zone
        ))
        val firstPayment = first.payment

        assertEquals(PaymentChannel.WECHAT, firstPayment.channel)
        assertEquals(TransactionType.EXPENSE, firstPayment.type)
        assertEquals(1234L, firstPayment.amountCents)
        assertEquals("样例网络科技有限公司", firstPayment.merchant)
        assertEquals("", firstPayment.note)
        assertFalse(firstPayment.merchant == "收草机构")
        assertFalse(firstPayment.merchant == "示例支付服务有限公司")
        assertFalse(firstPayment.note.contains("收草机构"))
        assertFalse(firstPayment.note.contains("示例支付服务有限公司"))
        assertEquals(epochMillis(2025, 10, 6, 19, 5, 41), firstPayment.timestampMillis)
        assertTrue(firstPayment.hasTimeOfDay)
        assertFalse(first.needsVerification)

        val retriedWithNote = requireSingleDetail(ScreenshotBillParser.parse(
            OcrImageInput(
                lines = baseLines + listOf(
                    ocr("备注", left = 90, top = 528, right = 200, bottom = 560),
                    ocr("月度会员", left = 320, top = 528, right = 500, bottom = 560)
                ),
                fallbackTimestampMillis = fallbackMillis
            ),
            zone
        ))
        assertEquals("样例网络科技有限公司", retriedWithNote.payment.merchant)
        assertEquals("月度会员", retriedWithNote.payment.note)
    }

    @Test
    fun skipsAcquirerAsMerchantAndKeepsExplicitNoteWithoutReceiptFooter() {
        val result = ScreenshotBillParser.parse(OcrImageInput(
            lines = listOf(
                ocr("微信支付", left = 90, top = 80),
                ocr("-12.34", left = 90, top = 160),
                ocr("收草机构", left = 90, top = 235),
                ocr("样例清算公司", left = 350, top = 235),
                ocr("支付时间 2025-10-06 19:05:41", left = 90, top = 300),
                ocr("备注：月度会员", left = 90, top = 365)
            ),
            fallbackTimestampMillis = fallbackMillis
        ), zone)
        val payment = requireSingleDetail(result).payment
        assertEquals("微信支付", payment.merchant)
        assertEquals("月度会员", payment.note)
    }

    private fun requireSingleDetail(result: ScreenshotBillParseResult): ScreenshotParsedCandidate {
        assertEquals("DETAIL", result.detectedMode)
        assertEquals("Expected exactly one parsed candidate", 1, result.candidates.size)
        return result.candidates.first()
    }

    private fun ocr(
        text: String,
        left: Int,
        top: Int,
        right: Int = left + 260,
        bottom: Int = top + 32
    ): OcrLine = OcrLine(
        text = text,
        left = left,
        top = top,
        right = right,
        bottom = bottom
    )

    private fun epochMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int
    ): Long = LocalDateTime.of(year, month, day, hour, minute, second)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()

    private fun localDateTime(timestampMillis: Long): LocalDateTime =
        Instant.ofEpochMilli(timestampMillis).atZone(zone).toLocalDateTime()
}
