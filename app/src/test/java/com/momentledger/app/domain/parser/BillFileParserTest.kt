package com.momentledger.app.domain.parser

import com.momentledger.app.domain.engine.CategoryMatcher
import com.momentledger.app.domain.model.CaptureSource
import com.momentledger.app.domain.model.DefaultCategories
import com.momentledger.app.domain.model.HOUR_OF_DAY_UNKNOWN
import com.momentledger.app.domain.model.PaymentChannel
import com.momentledger.app.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BillFileParserTest {

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

    @Test
    fun parse_alipayGb18030CsvWithPreambleQuotedCommaAndStatuses() {
        val preamble = (1..18).joinToString("\r\n") { idx ->
            when (idx) {
                1 -> "------------------------------------------------------------------------------------"
                2 -> "支付宝（中国）网络技术有限公司  电子客户回单"
                3 -> "账号:[willen@example.com]"
                18 -> "---------------------------------交易记录明细列表------------------------------------"
                else -> "元数据行$idx: 测试摘要"
            }
        }

        val csvBody = buildString {
            appendLine(preamble)
            appendLine("交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注")
            appendLine("2026-11-19 12:34:56,餐饮美食,\"美团,外卖\",meituan@alipay.com,黄焖鸡米饭,支出,¥36.50,花呗,交易成功,202611192200140001\t,M001,午餐")
            appendLine("2026-11-19 15:20:10,餐饮美食,瑞幸咖啡,luckin@alipay.com,生椰拿铁退款,不计收支,¥16.90,花呗,退款成功,202611192200140002\t,M002,")
            appendLine("2026-11-19 18:00:00,投资理财,余额宝,yuebao@alipay.com,转入余额宝,不计收支,500.00,招商银行储蓄卡(8848),交易成功,202611192200140003\t,,")
            appendLine("2026-11-19 19:10:00,购物消费,淘宝卖家,shop@alipay.com,蓝牙耳机,支出,199.00,余额宝,交易关闭,202611192200140004\t,M004,未付款关闭")
        }

        val gbBytes = csvBody.toByteArray(Charset.forName("GB18030"))
        val result = BillFileParser.parse(
            fileName = "alipay_record_20261119.csv",
            inputStream = ByteArrayInputStream(gbBytes),
            zoneId = shanghaiZone
        )

        assertTrue(result.detectedFormat.contains("ALIPAY", ignoreCase = true))
        assertEquals(1, result.skippedCount)
        assertEquals(3, result.items.size)

        val expense = result.items[0]
        assertEquals(PaymentChannel.ALIPAY, expense.channel)
        assertEquals(TransactionType.EXPENSE, expense.type)
        assertEquals(3650L, expense.amountCents)
        assertEquals("美团,外卖", expense.merchant)
        assertEquals("花呗", expense.fundingMethod)
        assertEquals("202611192200140001", expense.externalOrderId)
        assertEquals(CaptureSource.IMPORT, expense.source)
        assertEquals(millisAt(2026, 11, 19, 12, 34, 56), expense.timestampMillis)

        val refund = result.items[1]
        assertEquals(PaymentChannel.ALIPAY, refund.channel)
        assertEquals(TransactionType.REFUND, refund.type)
        assertEquals(1690L, refund.amountCents)
        assertEquals("瑞幸咖啡", refund.merchant)
        assertEquals("202611192200140002", refund.externalOrderId)

        val transfer = result.items[2]
        assertEquals(PaymentChannel.ALIPAY, transfer.channel)
        assertEquals(TransactionType.TRANSFER, transfer.type)
        assertEquals(50000L, transfer.amountCents)
        assertEquals("招商银行储蓄卡(8848)", transfer.fundingMethod)
        assertEquals("202611192200140003", transfer.externalOrderId)
    }

    @Test
    fun parse_alipayUtf8BomHandlesEscapedQuotesAndMobileRechargeExpense() {
        val content = buildString {
            append('\uFEFF')
            appendLine("支付宝账单明细")
            appendLine("交易时间,交易分类,交易对方,商品说明,收/支,金额（元）,收/付款方式,交易状态,交易订单号,备注")
            appendLine("2026-11-19 08:05:00,通讯充值,\"中国移动\"\"官方旗舰店\"\"\",话费充值100元,支出,100.00,支付宝零钱,交易成功,ALI_ORDER_100,")
            appendLine("2026-11-19 08:06:00,日用百货,全家便利店,早餐,支出,12.50,花呗,支付失败,ALI_ORDER_101,")
        }

        val result = BillFileParser.parse(
            fileName = "alipay_utf8.csv",
            inputStream = ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)),
            zoneId = shanghaiZone
        )

        assertEquals(1, result.items.size)
        assertEquals(1, result.skippedCount)
        assertEquals(TransactionType.EXPENSE, result.items[0].type)
        assertEquals("中国移动\"官方旗舰店\"", result.items[0].merchant)
        assertEquals(10000L, result.items[0].amountCents)
    }

    @Test
    fun parse_wechatPureZipXlsxWithSharedAndInlineStringsAndSparseCells() {
        val sharedStrings = listOf(
            "微信支付账单明细",
            "微信昵称：[Willen]",
            "交易时间",
            "交易类型",
            "交易对方",
            "商品",
            "收/支",
            "金额(元)",
            "支付方式",
            "当前状态",
            "交易单号",
            "商户单号",
            "备注",
            "商户消费",
            "瑞幸咖啡",
            "生椰拿铁",
            "支出",
            "招商银行(8848)",
            "支付成功",
            "WX_ORDER_001",
            "/",
            "微信红包",
            "张伟",
            "收入",
            "已存入零钱",
            "WX_ORDER_002",
            "已全额退款",
            "WX_ORDER_003",
            "零钱提现",
            "已撤销",
            "WX_ORDER_004"
        )

        val sharedStringsXml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("""<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="${sharedStrings.size}" uniqueCount="${sharedStrings.size}">""")
            sharedStrings.forEachIndexed { idx, value ->
                if (idx == 14) {
                    // Rich-text run + phonetic tag to verify proper <r><t> concatenation and <rPh> exclusion
                    append("""<si><r><t>瑞幸</t></r><r><t>咖啡</t></r><rPh><t>ruixing</t></rPh></si>""")
                } else {
                    append("""<si><t>${xmlEscape(value)}</t></si>""")
                }
            }
            append("""</sst>""")
        }

        val sheet1Xml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
            // Preamble summary rows
            append("""<row r="1"><c r="A1" t="s"><v>0</v></c></row>""")
            append("""<row r="2"><c r="A2" t="s"><v>1</v></c></row>""")
            append("""<row r="3"><c r="A3" t="inlineStr"><is><t>共4笔记录</t></is></c></row>""")
            // Header row (row 4)
            append("""<row r="4">""")
            for (colOffset in 0..10) {
                val colLetter = ('A' + colOffset)
                append("""<c r="${colLetter}4" t="s"><v>${2 + colOffset}</v></c>""")
            }
            append("""</row>""")
            // Row 5: Expense (mix of t="s", t="inlineStr", numeric amount)
            append("""<row r="5">""")
            append("""<c r="A5" t="inlineStr"><is><t>2026-11-19 08:30:15</t></is></c>""")
            append("""<c r="B5" t="s"><v>13</v></c>""") // 商户消费
            append("""<c r="C5" t="s"><v>14</v></c>""") // 瑞幸咖啡 (rich text)
            append("""<c r="D5" t="s"><v>15</v></c>""") // 生椰拿铁
            append("""<c r="E5" t="s"><v>16</v></c>""") // 支出
            append("""<c r="F5"><v>16.90</v></c>""") // numeric cell
            append("""<c r="G5" t="s"><v>17</v></c>""") // 招商银行(8848)
            append("""<c r="H5" t="s"><v>18</v></c>""") // 支付成功
            append("""<c r="I5" t="s"><v>19</v></c>""") // WX_ORDER_001
            append("""<c r="J5" t="s"><v>20</v></c>""") // "/"
            append("""<c r="K5" t="s"><v>20</v></c>""") // "/"
            append("""</row>""")
            // Row 6: Income with sparse omitted column D6 ("商品") and "/" placeholder for payment method
            append("""<row r="6">""")
            append("""<c r="A6" t="inlineStr"><is><t>2026-11-19 12:00:00</t></is></c>""")
            append("""<c r="B6" t="s"><v>21</v></c>""") // 微信红包
            append("""<c r="C6" t="s"><v>22</v></c>""") // 张伟
            // D6 omitted intentionally (sparse cell test)
            append("""<c r="E6" t="s"><v>23</v></c>""") // 收入
            append("""<c r="F6" t="inlineStr"><is><t>¥88.88</t></is></c>""")
            append("""<c r="G6" t="s"><v>20</v></c>""") // "/"
            append("""<c r="H6" t="s"><v>24</v></c>""") // 已存入零钱
            append("""<c r="I6" t="s"><v>25</v></c>""") // WX_ORDER_002
            append("""</row>""")
            // Row 7: Original expense whose post-status became "已全额退款" -> MUST remain EXPENSE
            append("""<row r="7">""")
            append("""<c r="A7" t="inlineStr"><is><t>2026-11-19 15:45:00</t></is></c>""")
            append("""<c r="B7" t="s"><v>13</v></c>""")
            append("""<c r="C7" t="inlineStr"><is><t>海底捞</t></is></c>""")
            append("""<c r="D7" t="s"><v>20</v></c>""") // "/"
            append("""<c r="E7" t="s"><v>16</v></c>""") // 支出
            append("""<c r="F7"><v>218.5</v></c>""")
            append("""<c r="G7" t="inlineStr"><is><t>零钱</t></is></c>""")
            append("""<c r="H7" t="s"><v>26</v></c>""") // 已全额退款
            append("""<c r="I7" t="s"><v>27</v></c>""") // WX_ORDER_003
            append("""</row>""")
            // Row 8: Companion WeChat refund credit row ("收入" + "海底捞-退款" + "已全额退款") -> REFUND
            append("""<row r="8">""")
            append("""<c r="A8" t="inlineStr"><is><t>2026-11-19 15:50:00</t></is></c>""")
            append("""<c r="B8" t="inlineStr"><is><t>海底捞-退款</t></is></c>""")
            append("""<c r="C8" t="inlineStr"><is><t>海底捞</t></is></c>""")
            append("""<c r="D8" t="s"><v>20</v></c>""") // "/"
            append("""<c r="E8" t="s"><v>23</v></c>""") // 收入
            append("""<c r="F8"><v>218.5</v></c>""")
            append("""<c r="G8" t="inlineStr"><is><t>零钱</t></is></c>""")
            append("""<c r="H8" t="s"><v>26</v></c>""") // 已全额退款
            append("""<c r="I8" t="inlineStr"><is><t>WX_ORDER_003_REF</t></is></c>""")
            append("""</row>""")
            // Row 9: Cancelled ("已撤销") -> must be skipped
            append("""<row r="9">""")
            append("""<c r="A9" t="inlineStr"><is><t>2026-11-19 19:00:00</t></is></c>""")
            append("""<c r="B9" t="s"><v>28</v></c>""") // 零钱提现
            append("""<c r="C9" t="s"><v>20</v></c>""")
            append("""<c r="D9" t="s"><v>20</v></c>""")
            append("""<c r="E9" t="s"><v>20</v></c>""") // "/"
            append("""<c r="F9"><v>500.00</v></c>""")
            append("""<c r="G9" t="s"><v>20</v></c>""")
            append("""<c r="H9" t="s"><v>29</v></c>""") // 已撤销
            append("""<c r="I9" t="s"><v>30</v></c>""") // WX_ORDER_004
            append("""</row>""")
            append("""</sheetData></worksheet>""")
        }

        val xlsxBytes = buildXlsxZip(
            mapOf(
                "xl/sharedStrings.xml" to sharedStringsXml,
                "xl/worksheets/sheet1.xml" to sheet1Xml
            )
        )

        val result = BillFileParser.parse(
            fileName = "微信支付账单(20261101-20261130).xlsx",
            inputStream = ByteArrayInputStream(xlsxBytes),
            zoneId = shanghaiZone
        )

        assertTrue(result.detectedFormat.contains("WECHAT", ignoreCase = true))
        assertTrue(result.detectedFormat.contains("XLSX", ignoreCase = true))
        assertEquals(1, result.skippedCount)
        assertEquals(4, result.items.size)

        val expense = result.items[0]
        assertEquals(PaymentChannel.WECHAT, expense.channel)
        assertEquals(TransactionType.EXPENSE, expense.type)
        assertEquals(1690L, expense.amountCents)
        assertEquals("瑞幸咖啡", expense.merchant)
        assertEquals("招商银行(8848)", expense.fundingMethod)
        assertEquals("WX_ORDER_001", expense.externalOrderId)
        assertEquals("生椰拿铁", expense.note)
        assertEquals(CaptureSource.IMPORT, expense.source)
        assertEquals(millisAt(2026, 11, 19, 8, 30, 15), expense.timestampMillis)

        val income = result.items[1]
        assertEquals(PaymentChannel.WECHAT, income.channel)
        assertEquals(TransactionType.INCOME, income.type)
        assertEquals(8888L, income.amountCents)
        assertEquals("张伟", income.merchant)
        assertEquals("", income.fundingMethod)
        assertEquals("WX_ORDER_002", income.externalOrderId)
        assertEquals(millisAt(2026, 11, 19, 12, 0, 0), income.timestampMillis)

        val refundedOrigExpense = result.items[2]
        assertEquals(PaymentChannel.WECHAT, refundedOrigExpense.channel)
        assertEquals(TransactionType.EXPENSE, refundedOrigExpense.type)
        assertEquals(21850L, refundedOrigExpense.amountCents)
        assertEquals("海底捞", refundedOrigExpense.merchant)
        assertEquals("零钱", refundedOrigExpense.fundingMethod)
        assertEquals("WX_ORDER_003", refundedOrigExpense.externalOrderId)

        val refundCredit = result.items[3]
        assertEquals(PaymentChannel.WECHAT, refundCredit.channel)
        assertEquals(TransactionType.REFUND, refundCredit.type)
        assertEquals(21850L, refundCredit.amountCents)
        assertEquals("海底捞", refundCredit.merchant)
        assertEquals("WX_ORDER_003_REF", refundCredit.externalOrderId)
    }

    @Test
    fun parse_wechatCsvHandlesNeutralTransferAndSkippedRows() {
        val csv = "\uFEFF" + """
            微信支付账单明细
            ----------------------微信支付账单明细列表--------------------
            交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注
            2026-11-19 21:00:00,零钱提现,/,/,/,¥300.00,零钱,已到账,WX_TR_1,/,"提现到招行"
            2026-11-19 21:05:00,扫二维码付款,星巴克,拿铁,支出,¥38.00,零钱,支付成功,WX_EXP_2,M2,/
            2026-11-19 21:10:00,商户消费,未知店,/,支出,¥10.00,零钱,支付失败,WX_FAIL_3,/,/
        """.trimIndent()

        val result = BillFileParser.parse(
            fileName = "wechat_bill.csv",
            inputStream = ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)),
            zoneId = shanghaiZone
        )

        assertTrue(result.detectedFormat.contains("WECHAT", ignoreCase = true))
        assertEquals(1, result.skippedCount)
        assertEquals(2, result.items.size)

        assertEquals(TransactionType.TRANSFER, result.items[0].type)
        assertEquals(30000L, result.items[0].amountCents)
        assertEquals("WX_TR_1", result.items[0].externalOrderId)

        assertEquals(TransactionType.EXPENSE, result.items[1].type)
        assertEquals("星巴克", result.items[1].merchant)
        assertEquals(3800L, result.items[1].amountCents)
    }

    @Test
    fun parse_alipayPreservesYuebaoIncomeSkipsClosedRefundAndHandlesMultilineQuotedField() {
        val csv = buildString {
            appendLine("交易时间,交易分类,交易对方,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,备注")
            appendLine("2026-11-19 07:00:00,投资理财,余额宝,余额宝-2026.11.19-收益发放,收入,1.28,余额宝,交易成功,ORD_INC_1,\"每日\n收益\"")
            appendLine("2026-11-19 09:00:00,购物消费,某网店,退款关闭测试,支出,50.00,花呗,退款关闭,ORD_SKIP_2,")
        }

        val result = BillFileParser.parse(
            fileName = "alipay_extra.csv",
            inputStream = ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)),
            zoneId = shanghaiZone
        )

        assertEquals(1, result.items.size)
        assertEquals(1, result.skippedCount)
        assertEquals(TransactionType.INCOME, result.items[0].type)
        assertEquals(128L, result.items[0].amountCents)
        assertTrue(result.items[0].note.contains("每日\n收益"))
    }

    @Test
    fun parse_alipayCsvIgnoresFooterSummaryAndFootnoteRowsWithoutIncrementingSkippedCount() {
        val csvWithDividerAndSummary = buildString {
            appendLine("支付宝（中国）网络技术有限公司  电子客户回单")
            appendLine("---------------------------------交易记录明细列表------------------------------------")
            appendLine("交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注")
            appendLine("2026-11-19 12:00:00,餐饮美食,瑞幸咖啡,luckin@alipay.com,生椰拿铁,支出,16.90,花呗,交易成功,ORD_1,M_1,")
            appendLine("已支出:1笔 16.90元")
            appendLine("已收入:0笔 0.00元")
            appendLine("不计收支:0笔 0.00元")
            appendLine("1.以上记录仅作对账参考")
            appendLine("------------------------------------------------------------------------------------")
            appendLine("导出时间:[2026-11-20 09:00:00],附加说明")
        }

        val result = BillFileParser.parse(
            fileName = "alipay_footer.csv",
            inputStream = ByteArrayInputStream(csvWithDividerAndSummary.toByteArray(Charset.forName("GB18030"))),
            zoneId = shanghaiZone
        )

        assertEquals(1, result.items.size)
        assertEquals(0, result.skippedCount)
        assertEquals("瑞幸咖啡", result.items[0].merchant)
        assertEquals(1690L, result.items[0].amountCents)
    }

    @Test
    fun parse_rejectsOversizedStreamAndXlsxZipBombWithoutPreallocation() {
        val oversizedStream = object : InputStream() {
            private var remaining = BillFileParser.MAX_BILL_BYTES.toLong() + 1024L

            override fun read(): Int {
                if (remaining <= 0L) return -1
                remaining--
                return 'a'.code
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0L) return -1
                val chunk = minOf(len.toLong(), remaining).toInt()
                b.fill('a'.code.toByte(), off, off + chunk)
                remaining -= chunk
                return chunk
            }
        }

        try {
            BillFileParser.parse(
                fileName = "oversized.csv",
                inputStream = oversizedStream,
                zoneId = shanghaiZone
            )
            fail("Expected IllegalArgumentException for >20MB input stream")
        } catch (e: IllegalArgumentException) {
            assertEquals("账单文件过大（上限 20MB）", e.message)
        }

        val bombZipBytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
                val chunk = ByteArray(64 * 1024) { ' '.code.toByte() }
                var written = 0
                val target = BillFileParser.MAX_BILL_BYTES + 1024
                while (written < target) {
                    val toWrite = minOf(chunk.size, target - written)
                    zos.write(chunk, 0, toWrite)
                    written += toWrite
                }
                zos.closeEntry()
            }
            baos.toByteArray()
        }
        assertTrue(bombZipBytes.size < 256 * 1024)

        try {
            BillFileParser.parse(
                fileName = "zip_bomb.xlsx",
                inputStream = ByteArrayInputStream(bombZipBytes),
                zoneId = shanghaiZone
            )
            fail("Expected IllegalArgumentException for >20MB decompressed XLSX entry")
        } catch (e: IllegalArgumentException) {
            assertEquals("账单文件过大（上限 20MB）", e.message)
        }
    }

    @Test
    fun bankDateOnlyDualColumnCsv_parsesExpenseAndIncomeWithHasTimeOfDayFalse() {
        val csv = """
            招商银行交易流水
            记账日期,交易摘要,支出金额,收入金额,对方户名,流水号
            2026-11-20,美团外卖订单,36.50,,美团外卖,CMB001
            2026/11/20,工资代发,,12500.00,某科技公司,CMB002
        """.trimIndent() + "\n"

        val result = BillFileParser.parse(
            fileName = "cmb_202611.csv",
            inputStream = ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)),
            zoneId = shanghaiZone
        )

        assertEquals("GENERIC_CSV", result.detectedFormat)
        assertEquals(2, result.items.size)
        assertEquals(0, result.skippedCount)

        val expense = result.items[0]
        assertEquals(TransactionType.EXPENSE, expense.type)
        assertEquals(3650L, expense.amountCents)
        assertEquals("美团外卖", expense.merchant)
        assertEquals(PaymentChannel.BANK, expense.channel)
        assertEquals("CMB001", expense.externalOrderId)
        assertFalse(expense.hasTimeOfDay)
        assertEquals(millisAt(2026, 11, 20, 12, 0, 0), expense.timestampMillis)

        val income = result.items[1]
        assertEquals(TransactionType.INCOME, income.type)
        assertEquals(1250000L, income.amountCents)
        assertEquals("某科技公司", income.merchant)
        assertEquals("CMB002", income.externalOrderId)
        assertFalse(income.hasTimeOfDay)
    }

    @Test
    fun ecommerceOrderCsv_parsesActualPaidAndSkipsClosedOrders() {
        val csv = """
            京东订单导出
            下单时间,店铺名称,商品名称,订单金额,实付款,订单状态,订单编号
            2026-11-20 10:15:22,苹果官方旗舰店,AirPods 4,259.00,¥199.00,已完成,JD20261120001
            2026-11-20 11:00:00,某服饰店,卫衣,129.00,129.00,已取消,JD20261120002
        """.trimIndent() + "\n"

        val result = BillFileParser.parse(
            fileName = "jd_orders.csv",
            inputStream = ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)),
            zoneId = shanghaiZone
        )

        assertEquals(1, result.items.size)
        assertEquals(1, result.skippedCount)

        val order = result.items[0]
        assertEquals(19900L, order.amountCents)
        assertEquals(TransactionType.EXPENSE, order.type)
        assertEquals("苹果官方旗舰店", order.merchant)
        assertTrue(order.hasTimeOfDay)
        assertEquals("JD20261120001", order.externalOrderId)
    }

    @Test
    fun signedAmountGenericCsv_infersIncomeAndExpenseFromSign() {
        val csv = """
            交易日期,商户名称,发生额
            2026-11-19,库迪咖啡,-18.80
            2026-11-19,报销款,+50.00
        """.trimIndent() + "\n"

        val result = BillFileParser.parse(
            fileName = "custom.csv",
            inputStream = ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)),
            zoneId = shanghaiZone
        )

        assertEquals(2, result.items.size)

        val expense = result.items[0]
        assertEquals(TransactionType.EXPENSE, expense.type)
        assertEquals(1880L, expense.amountCents)
        assertFalse(expense.hasTimeOfDay)

        val income = result.items[1]
        assertEquals(TransactionType.INCOME, income.type)
        assertEquals(5000L, income.amountCents)
        assertFalse(income.hasTimeOfDay)
    }

    @Test
    fun expandedBuiltInRules_matchesEcommerceAndDeliveryMerchants() {
        assertEquals(-1, HOUR_OF_DAY_UNKNOWN)
        assertEquals("OCR", CaptureSource.OCR.name)
        assertEquals(
            "exp_food",
            CategoryMatcher.matchCategoryId("饿了么", "", TransactionType.EXPENSE, DefaultCategories.builtInRules)
        )
        assertEquals(
            "exp_food",
            CategoryMatcher.matchCategoryId("库迪咖啡", "", TransactionType.EXPENSE, DefaultCategories.builtInRules)
        )
        assertEquals(
            "exp_shopping",
            CategoryMatcher.matchCategoryId("得物App", "", TransactionType.EXPENSE, DefaultCategories.builtInRules)
        )
        assertEquals(
            "exp_shopping",
            CategoryMatcher.matchCategoryId("抖音商城", "", TransactionType.EXPENSE, DefaultCategories.builtInRules)
        )
        assertEquals(
            "exp_food",
            CategoryMatcher.matchCategoryId("朴朴超市", "", TransactionType.EXPENSE, DefaultCategories.builtInRules)
        )
    }

    @Test
    fun fullWidthSignsAndCurrencyBeforeMinus_parsesCorrectSignAndAmount() {
        val csv = """
            交易日期,商户名称,发生额
            2026-11-20,瑞幸咖啡,－18.80
            2026-11-20,麦当劳,¥-36.50
            2026-11-20,返现,＋12.00
        """.trimIndent() + "\n"

        val result = BillFileParser.parse(
            fileName = "signs.csv",
            inputStream = ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)),
            zoneId = shanghaiZone
        )

        assertEquals(3, result.items.size)
        assertEquals(TransactionType.EXPENSE, result.items[0].type)
        assertEquals(1880L, result.items[0].amountCents)
        assertEquals(TransactionType.EXPENSE, result.items[1].type)
        assertEquals(3650L, result.items[1].amountCents)
        assertEquals(TransactionType.INCOME, result.items[2].type)
        assertEquals(1200L, result.items[2].amountCents)
    }

    @Test
    fun bankCsvWithGenericTransactionStatusColumn_detectedAsGenericBankNotAlipay() {
        val csv = """
            招商银行交易明细
            交易日期,商户名称,支出金额,收入金额,交易状态
            2026-11-20,山姆会员店,128.00,,交易成功
        """.trimIndent() + "\n"

        val result = BillFileParser.parse(
            fileName = "cmb_detail.csv",
            inputStream = ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)),
            zoneId = shanghaiZone
        )

        assertEquals("GENERIC_CSV", result.detectedFormat)
        assertEquals(1, result.items.size)
        assertEquals(PaymentChannel.BANK, result.items[0].channel)
        assertEquals(12800L, result.items[0].amountCents)
        assertEquals(TransactionType.EXPENSE, result.items[0].type)
    }

    @Test
    fun wechatPartialAndFullRefundPairs_keepOriginalExpenseAndParseSeparateRefundRow() {
        val csv = "\uFEFF" + """
            微信支付账单明细
            交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注
            2026-11-15 16:33:56,美团平台商户-退款,美团平台商户,美团平台商户,收入,1.11,零钱,已退款¥1.11,WX_R1,,/
            2026-11-15 16:10:16,商户消费,美团,美团订单-001,支出,62.58,零钱,已退款(¥1.11),WX_E1,M1,/
            2026-11-03 11:36:36,淘宝平台-退款,淘宝平台,淘宝平台,收入,183.99,零钱,已全额退款,WX_R2,,/
            2026-11-03 09:48:12,商户消费,淘宝平台商户,商品,支出,183.99,零钱,已全额退款,WX_E2,M2,/
            2026-11-08 16:50:18,微信红包-退款,/,/,收入,3,零钱,已全额退款,WX_R3,,/
            2026-11-07 16:50:15,微信红包（单发）,发给好友,/,支出,3,零钱,已全额退款,WX_E3,M3,/
        """.trimIndent() + "\n"

        val result = BillFileParser.parse(
            fileName = "wechat_refund_pairs.csv",
            inputStream = ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)),
            zoneId = shanghaiZone
        )

        assertEquals(6, result.items.size)
        val byId = result.items.associateBy { it.externalOrderId }
        assertEquals(TransactionType.EXPENSE, byId.getValue("WX_E1").type)
        assertEquals(TransactionType.REFUND, byId.getValue("WX_R1").type)
        assertEquals(TransactionType.EXPENSE, byId.getValue("WX_E2").type)
        assertEquals(TransactionType.REFUND, byId.getValue("WX_R2").type)
        assertEquals(TransactionType.EXPENSE, byId.getValue("WX_E3").type)
        assertEquals(TransactionType.REFUND, byId.getValue("WX_R3").type)

        val totalExpense = result.items.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountCents }
        val totalRefund = result.items.filter { it.type == TransactionType.REFUND }.sumOf { it.amountCents }
        assertEquals(6147L, totalExpense - totalRefund)
    }

    @Test
    fun alipayPaidThenRefundedClosedOrder_preservedWhileUnpaidClosedOrderSkipped() {
        val csv = """
            支付宝（中国）网络技术有限公司  电子客户回单
            交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注
            2026-11-17 16:11:04,退款,标贝店,a@test.com,退款-订阅,不计收支,200.00,工商银行储蓄卡(7032),退款成功,ALI_PARTIAL_1_REF1,M2,
            2026-11-14 15:39:23,生活服务,标贝店,a@test.com,订阅,支出,299.00,工商银行储蓄卡(7032),交易成功,ALI_PARTIAL_1,M2,
            2026-11-14 12:38:43,退款,某卖家,b@test.com,退款-商品,不计收支,35.00,工商银行储蓄卡(7032),退款成功,ALI_FULL_1_REF1,M1,
            2026-11-14 12:35:43,日用百货,某卖家,b@test.com,商品,支出,35.00,工商银行储蓄卡(7032),交易关闭,ALI_FULL_1,M1,
            2026-10-21 21:03:26,文化休闲,未付款店铺,c@test.com,眼镜,不计收支,1039.24,,交易关闭,ALI_UNPAID_1,M3,
            2026-09-21 23:32:30,收入,未成交买家,d@test.com,耳机,收入,470.00,,交易关闭,ALI_UNPAID_2,M4,
        """.trimIndent() + "\n"

        val result = BillFileParser.parse(
            fileName = "alipay_real_closed.csv",
            inputStream = ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)),
            zoneId = shanghaiZone
        )

        assertEquals(4, result.items.size)
        assertEquals(2, result.skippedCount)
        val byId = result.items.associateBy { it.externalOrderId }
        assertEquals(TransactionType.EXPENSE, byId.getValue("ALI_FULL_1").type)
        assertEquals(3500L, byId.getValue("ALI_FULL_1").amountCents)
        assertEquals(TransactionType.REFUND, byId.getValue("ALI_FULL_1_REF1").type)
        assertEquals(TransactionType.EXPENSE, byId.getValue("ALI_PARTIAL_1").type)
        assertEquals(TransactionType.REFUND, byId.getValue("ALI_PARTIAL_1_REF1").type)

        val totalExpense = result.items.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountCents }
        val totalRefund = result.items.filter { it.type == TransactionType.REFUND }.sumOf { it.amountCents }
        assertEquals(9900L, totalExpense - totalRefund)
    }

    @Test
    fun alipayNeutralClosedOrderFullyRefunded_parsesOriginalAsExpenseAndRefundRowAsRefund() {
        val csv = """
            支付宝（中国）网络技术有限公司  电子客户回单
            交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注
            2026-11-18 10:05:00,退款,某卖家,a@test.com,退款-商品,不计收支,88.00,工商银行储蓄卡(7032),退款成功,ALI_NEUTRAL_1_REF1,M1,
            2026-11-18 10:00:00,日用百货,某卖家,a@test.com,商品,不计收支,88.00,工商银行储蓄卡(7032),交易关闭,ALI_NEUTRAL_1,M1,
        """.trimIndent() + "\n"

        val result = BillFileParser.parse(
            fileName = "alipay_neutral_closed.csv",
            inputStream = ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)),
            zoneId = shanghaiZone
        )

        assertEquals(2, result.items.size)
        val byId = result.items.associateBy { it.externalOrderId }
        val original = byId.getValue("ALI_NEUTRAL_1")
        val refund = byId.getValue("ALI_NEUTRAL_1_REF1")

        assertEquals(TransactionType.EXPENSE, original.type)
        assertEquals(8800L, original.amountCents)
        assertEquals(TransactionType.REFUND, refund.type)
        assertEquals(8800L, refund.amountCents)

        val totalExpense = result.items.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountCents }
        val totalRefund = result.items.filter { it.type == TransactionType.REFUND }.sumOf { it.amountCents }
        assertEquals(0L, totalExpense - totalRefund)
    }

    @Test
    fun alipayClosedExpenseWithNonEmptyPaymentMethod_isRetainedForLaterRefundReconciliation() {
        val csv = """
            支付宝（中国）网络技术有限公司  电子客户回单
            交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注
            2026-11-19 10:00:00,购物消费,某网店,a@test.com,商品A,支出,50.00,花呗,交易关闭,ALI_CLOSED_PAID,M1,
        """.trimIndent() + "\n"

        val result = BillFileParser.parse(
            fileName = "alipay_single_closed.csv",
            inputStream = ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)),
            zoneId = shanghaiZone
        )

        assertEquals(
            "A paid closed expense must survive so a refund delivered in the next CSV can cancel it",
            1,
            result.items.size
        )
        assertEquals(0, result.skippedCount)
        val item = result.items.single()
        assertEquals(TransactionType.EXPENSE, item.type)
        assertEquals(5000L, item.amountCents)
        assertEquals("花呗", item.fundingMethod)
        assertEquals("ALI_CLOSED_PAID", item.externalOrderId)
    }

    @Test
    fun alipayClosedIncomeAndClosedExpenseWithoutPaymentMethod_areStillSkipped() {
        val csv = """
            支付宝（中国）网络技术有限公司  电子客户回单
            交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注
            2026-11-19 11:00:00,收入,买家,a@test.com,商品B,收入,100.00,花呗,交易关闭,ALI_CLOSED_INCOME,M2,
            2026-11-19 12:00:00,购物消费,某网店,a@test.com,商品C,支出,50.00,,交易关闭,ALI_CLOSED_NO_FUNDING,M3,
        """.trimIndent() + "\n"

        val result = BillFileParser.parse(
            fileName = "alipay_closed_variants.csv",
            inputStream = ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)),
            zoneId = shanghaiZone
        )

        assertEquals(0, result.items.size)
        assertEquals(2, result.skippedCount)
    }

    @Test
    fun alipayRefundClosedAndFailedRowsWithPaymentMethod_areStillFiltered() {
        val csv = """
            支付宝（中国）网络技术有限公司  电子客户回单
            交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注
            2026-11-19 13:00:00,退款,某卖家,a@test.com,退款-商品,支出,50.00,花呗,退款关闭,ALI_REFUND_CLOSED,M4,
            2026-11-19 14:00:00,日用百货,全家便利店,a@test.com,早餐,支出,12.50,花呗,支付失败,ALI_FAILED,M5,
        """.trimIndent() + "\n"

        val result = BillFileParser.parse(
            fileName = "alipay_failed_closed.csv",
            inputStream = ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)),
            zoneId = shanghaiZone
        )

        assertEquals(0, result.items.size)
        assertEquals(2, result.skippedCount)
    }

    private fun buildXlsxZip(entries: Map<String, String>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            entries.forEach { (path, content) ->
                zos.putNextEntry(ZipEntry(path))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun xmlEscape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
