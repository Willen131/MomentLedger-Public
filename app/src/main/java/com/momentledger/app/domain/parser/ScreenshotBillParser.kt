package com.momentledger.app.domain.parser

import com.momentledger.app.domain.model.CaptureSource
import com.momentledger.app.domain.model.ParsedPayment
import com.momentledger.app.domain.model.PaymentChannel
import com.momentledger.app.domain.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs

data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val height: Int get() = (bottom - top).coerceAtLeast(1)
    val width: Int get() = (right - left).coerceAtLeast(1)
    val centerY: Int get() = (top + bottom) / 2
}

data class OcrImageInput(
    val lines: List<OcrLine>,
    val imageWidth: Int = 1080,
    val imageHeight: Int = 2400,
    val fallbackTimestampMillis: Long = System.currentTimeMillis()
)

data class ScreenshotParsedCandidate(
    val payment: ParsedPayment,
    val needsVerification: Boolean = false
)

data class ScreenshotBillParseResult(
    val candidates: List<ScreenshotParsedCandidate>,
    val rawText: String,
    val detectedMode: String
)

/**
 * Pure-Kotlin, Android-ICU-safe (zero lookbehind) parser for offline OCR text extracted
 * from payment, bank, e-commerce, and food-delivery screenshots.
 */
object ScreenshotBillParser {

    private data class VisualRow(
        val items: List<OcrLine>,
        val top: Int,
        val bottom: Int
    ) {
        val joinedText: String get() = items.joinToString(" ") { it.text.trim() }.trim()
    }

    private data class ExtractedTime(
        val timestampMillis: Long,
        val hasTimeOfDay: Boolean
    )

    private data class ExtractedAmount(
        val amountCents: Long,
        val signHint: TransactionType?
    )

    private data class ListRowAmount(
        val amount: ExtractedAmount,
        val consumedTokens: Int
    )

    private val whitespaceRegex = Regex("""\s+""")
    private val trailingPunctRegex = Regex("""[!！。.:：\s]+$""")

    private val signedStandaloneAmountRegex = Regex(
        """^([+\-＋－])\s*[¥￥]?\s*(\d[\d,]*(?:\.\d{1,2})?)\s*元?$"""
    )
    private val currencyStandaloneAmountRegex = Regex(
        """^([+\-＋－])?\s*(?:[¥￥]\s*(\d[\d,]*(?:\.\d{1,2})?)|(\d[\d,]*(?:\.\d{1,2})?)\s*元)$"""
    )
    private val inlineCurrencyRegex = Regex(
        """([+\-＋－])?\s*(?:[¥￥]\s*(\d[\d,]*(?:\.\d{1,2})?)|(\d[\d,]*(?:\.\d{1,2})?)\s*元)"""
    )
    private val plainDecimalRegex = Regex("""^([+\-＋－])?\s*(\d[\d,]*\.\d{1,2})$""")
    private val currencyOnlySet = setOf("¥", "￥")

    private val fullDateTimeRegex = Regex(
        """(\d{4})[-/年.](\d{1,2})[-/月.](\d{1,2})日?\s+(\d{1,2}):(\d{2})(?::(\d{2}))?"""
    )
    private val fullDateOnlyRegex = Regex(
        """(\d{4})[-/年.](\d{1,2})[-/月.](\d{1,2})日?"""
    )
    private val yearMonthHeaderRegex = Regex("""^(\d{4})\s*年\s*(\d{1,2})\s*月$""")
    private val monthDayTimeRegex = Regex("""(\d{1,2})[-/月](\d{1,2})日?\s+(\d{1,2}):(\d{2})(?::(\d{2}))?""")
    private val monthDayOnlyRegex = Regex("""^(\d{1,2})[-/月](\d{1,2})日?$""")
    private val monthDayChineseInlineRegex = Regex("""(\d{1,2})月(\d{1,2})日""")
    private val relativeDayTimeRegex = Regex("""(今天|今日|昨天|昨日|前天)\s*(\d{1,2}):(\d{2})(?::(\d{2}))?""")
    private val relativeDayOnlyRegex = Regex("""^(今天|今日|昨天|昨日|前天)$""")
    private val timeOnlyRegex = Regex("""^(\d{1,2}):(\d{2})(?::(\d{2}))?$""")

    private val detailAnchors = setOf(
        "支付成功", "付款成功", "支付完成", "交易成功", "转账成功", "已转账", "已支付",
        "付款完成", "扣款成功", "待朋友收款", "待对方收款", "待朋友确认收款", "待对方确认收款",
        "朋友已收款", "对方已收款", "你已收款", "你已收钱", "已存入零钱", "已存入你的零钱",
        "收款成功", "收款到账", "退款成功", "退款已到账", "已退款",
        "订单详情", "账单详情", "交易详情", "订单已完成", "已完成", "实付款", "实付金额",
        "实际支付", "合计实付", "实付", "交易金额", "付款金额", "支付金额", "转账时间",
        "付款时间", "支付时间", "下单时间", "收款时间", "交易日期", "订单编号", "交易单号"
    )

    private val singleDetailGuardKeywords = listOf(
        "订单详情", "账单详情", "交易详情", "商品总价", "店铺优惠", "实付款", "实付金额",
        "合计实付", "打包费", "配送费", "待朋友收款", "你已收款", "已存入你的零钱",
        "付款时间", "转账时间", "收款时间", "订单编号", "交易单号"
    )

    private val actualPaidLabels = setOf(
        "实付款", "实付金额", "实际支付", "实际付款", "合计实付", "实付",
        "退款金额", "实退金额", "到账金额"
    )

    private val genericPriorityAmountLabels = setOf(
        "收款金额", "转账金额", "支付金额", "付款金额", "交易金额", "消费金额"
    )

    private val priorityAmountLabels = actualPaidLabels + genericPriorityAmountLabels

    private val noiseAmountLabels = setOf(
        "商品总价", "商品金额", "原价", "订单金额", "总价", "小计",
        "店铺优惠", "平台优惠", "优惠", "已优惠", "优惠金额", "立减", "满减", "抵扣",
        "红包", "美团红包", "外卖红包", "神券", "优惠券",
        "配送费", "打包费", "包装费", "运费", "服务费", "手续费", "税费", "餐盒费",
        "余额", "可用余额", "账户余额", "额度", "可用额度"
    )

    private val noiseAmountLineRegex = Regex(
        """商品总价|商品金额|原价|订单金额|小计|总价|单价|店铺优惠|平台优惠|已优惠|优惠|立减|满减|抵扣|红包|神券|配送费|打包费|餐盒费|包装费|运费|手续费|服务费|税费|余额|额度|积分|尾号|卡号"""
    )

    private val nonCompletedRefundStripRegex = Regex(
        """(?:申请|我要|去|发起|可|支持|极速|闪电|快速|7天无理由|七天无理由|无理由退货|售后/)退款|退款/售后|退款售后|申请售后|退换货"""
    )

    private val explicitRefundCueRegex = Regex(
        """退款成功|退款已到账|已退款|已全额退款|退款金额|实退金额|退款中"""
    )

    private val merchantLabels = setOf(
        "店铺", "店铺名称", "商户名称", "收款商户", "商家名称", "商家", "商户",
        "收款方", "交易对方", "对方账户", "对方户名", "收款人", "门店", "付款给", "转账给", "来自"
    )

    private val fundingLabels = setOf(
        "支付方式", "付款方式", "扣款方式", "扣款账户", "支付账户", "收/付款方式", "资金来源"
    )

    private val orderIdLabels = setOf(
        "订单编号", "订单号", "交易单号", "交易订单号", "商户单号", "商家订单号", "流水号", "微信转账单号"
    )

    private val timeLabels = setOf(
        "付款时间", "支付时间", "转账时间", "收款时间", "交易时间", "下单时间", "创建时间", "完成时间",
        "交易日期", "记账日期", "消费日期"
    )

    private val otherLabels = setOf(
        "当前状态", "交易状态", "订单状态", "状态", "账单分类", "备注", "转账说明", "商品说明", "商品名称"
    )

    private val uiChromeWords = setOf(
        "完成", "关闭", "返回", "确定", "我知道了", "查看详情", "账单详情", "订单详情", "交易详情",
        "更多", "分享", "联系商家", "客服", "申请售后", "申请退款", "我要退款", "退款/售后", "售后/退款",
        "极速退款", "退换货", "再来一单", "评价", "去评价",
        "查看物流", "删除订单", "开发票", "申请开票", "查看零钱", "重发转账消息", "定位到聊天位置",
        "支付成功", "付款成功", "交易成功", "订单已完成", "已完成", "微信支付", "支付宝", "云闪付",
        "账单", "全部账单", "筛选", "统计", "搜索"
    )

    private val platformLabels: List<Pair<String, PaymentChannel>> = listOf(
        "美团外卖" to PaymentChannel.OTHER,
        "美团优选" to PaymentChannel.OTHER,
        "美团买菜" to PaymentChannel.OTHER,
        "美团" to PaymentChannel.OTHER,
        "饿了么" to PaymentChannel.OTHER,
        "淘宝" to PaymentChannel.OTHER,
        "天猫" to PaymentChannel.OTHER,
        "京东" to PaymentChannel.OTHER,
        "拼多多" to PaymentChannel.OTHER,
        "抖音商城" to PaymentChannel.OTHER,
        "抖音支付" to PaymentChannel.OTHER,
        "小红书" to PaymentChannel.OTHER,
        "得物" to PaymentChannel.OTHER,
        "滴滴出行" to PaymentChannel.OTHER,
        "大众点评" to PaymentChannel.OTHER,
        "微信支付" to PaymentChannel.WECHAT,
        "微信转账" to PaymentChannel.WECHAT,
        "微信" to PaymentChannel.WECHAT,
        "支付宝" to PaymentChannel.ALIPAY,
        "云闪付" to PaymentChannel.UNIONPAY,
        "银联" to PaymentChannel.UNIONPAY
    )

    private val summaryOrHeaderRowRegex = Regex(
        """(?:总支出|总收入|本月支出|本月收入|月账单|合计|共\d+笔|结余|可用余额)|(?:支出.*收入|收入.*支出)"""
    )

    private val skippedListRowRegex = Regex(
        """交易关闭|已关闭|已取消|未支付|待支付|待付款|支付失败|扣款失败|交易失败"""
    )

    private val standaloneFundingRegex = Regex(
        """^(?:零钱|微信零钱|零钱通|余额|余额宝|花呗|白条|亲属卡|(?:中国)?[\u4e00-\u9fa5]{2,8}银行(?:储蓄卡|借记卡|信用卡|银行卡)?(?:[（(]\d{4}[）)])?)$"""
    )

    private val longNumericIdRegex = Regex("""^[A-Za-z0-9]{10,}$""")

    /**
     * Removes duplicate OCR lines produced by overlapping vertical strips on long scrolling screenshots,
     * while preserving distinct items that happen to share identical text further down the page.
     */
    fun deduplicateTiledOcrLines(lines: List<OcrLine>): List<OcrLine> {
        val cleaned = lines
            .mapNotNull { line ->
                val trimmed = line.text.trim()
                if (trimmed.isEmpty()) null else line.copy(text = trimmed)
            }
            .sortedWith(compareBy<OcrLine> { it.top }.thenBy { it.left })

        val kept = ArrayList<OcrLine>(cleaned.size)
        for (candidate in cleaned) {
            val normalizedText = normalizeLineForDedup(candidate.text)
            val isDuplicate = kept.any { existing ->
                normalizeLineForDedup(existing.text) == normalizedText &&
                    isSpacialOverlapDuplicate(existing, candidate)
            }
            if (!isDuplicate) {
                kept += candidate
            }
        }
        return kept
    }

    fun parse(
        input: OcrImageInput,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): ScreenshotBillParseResult {
        val dedupLines = deduplicateTiledOcrLines(input.lines)
        if (dedupLines.isEmpty()) {
            return ScreenshotBillParseResult(emptyList(), "", "EMPTY")
        }

        val visualRows = clusterIntoVisualRows(dedupLines)
        val rawText = visualRows.joinToString("\n") { it.joinedText }
        val fallbackDateTime = Instant.ofEpochMilli(input.fallbackTimestampMillis).atZone(zoneId)

        val listCandidates = tryParseListMode(visualRows, fallbackDateTime, zoneId)
        if (listCandidates != null && listCandidates.size >= 2) {
            return ScreenshotBillParseResult(
                candidates = listCandidates,
                rawText = rawText,
                detectedMode = "LIST"
            )
        }

        val detailCandidate = tryParseDetailMode(visualRows, rawText, fallbackDateTime, zoneId)
        if (detailCandidate != null) {
            return ScreenshotBillParseResult(
                candidates = listOf(detailCandidate),
                rawText = rawText,
                detectedMode = "DETAIL"
            )
        }

        val fallbackCandidate = tryParseFallback(visualRows, fallbackDateTime, zoneId)
        if (fallbackCandidate != null) {
            return ScreenshotBillParseResult(
                candidates = listOf(fallbackCandidate),
                rawText = rawText,
                detectedMode = "FALLBACK"
            )
        }

        return ScreenshotBillParseResult(emptyList(), rawText, "EMPTY")
    }

    private fun normalizeLineForDedup(text: String): String =
        whitespaceRegex.replace(text.trim(), "")

    private fun isSpacialOverlapDuplicate(a: OcrLine, b: OcrLine): Boolean {
        val minHeight = minOf(a.height, b.height).coerceAtLeast(16)
        val yDelta = abs(a.top - b.top)
        val yThreshold = maxOf(24, (minHeight * 0.7).toInt())
        if (yDelta > yThreshold) return false

        val minWidth = minOf(a.width, b.width).coerceAtLeast(1)
        val xOverlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
        if (xOverlap >= minWidth * 0.45) return true
        return abs(a.left - b.left) <= 48
    }

    private fun clusterIntoVisualRows(lines: List<OcrLine>): List<VisualRow> {
        val sorted = lines.sortedWith(compareBy<OcrLine> { it.top }.thenBy { it.left })
        val groups = mutableListOf<MutableList<OcrLine>>()
        val groupBounds = mutableListOf<Pair<Int, Int>>()

        for (line in sorted) {
            var matchedIdx = -1
            for (i in groups.indices) {
                val (gTop, gBottom) = groupBounds[i]
                val gHeight = (gBottom - gTop).coerceAtLeast(16)
                val minHeight = minOf(gHeight, line.height).coerceAtLeast(16)
                val overlap = minOf(gBottom, line.bottom) - maxOf(gTop, line.top)
                val centerDelta = abs((gTop + gBottom) / 2 - line.centerY)
                if (overlap >= (minHeight * 0.45).toInt() || centerDelta <= (minHeight * 0.55).toInt()) {
                    matchedIdx = i
                    break
                }
            }
            if (matchedIdx >= 0) {
                groups[matchedIdx] += line
                val (oldTop, oldBottom) = groupBounds[matchedIdx]
                groupBounds[matchedIdx] = minOf(oldTop, line.top) to maxOf(oldBottom, line.bottom)
            } else {
                groups += mutableListOf(line)
                groupBounds += (line.top to line.bottom)
            }
        }

        return groups.mapIndexed { idx, items ->
            val ordered = items.sortedBy { it.left }
            val (top, bottom) = groupBounds[idx]
            VisualRow(ordered, top, bottom)
        }.sortedBy { it.top }
    }

    private fun tryParseListMode(
        rows: List<VisualRow>,
        fallbackDateTime: java.time.ZonedDateTime,
        zoneId: ZoneId
    ): List<ScreenshotParsedCandidate>? {
        val allText = rows.joinToString("\n") { it.joinedText }
        val genuineSignedRowCount = rows.count { row ->
            if (summaryOrHeaderRowRegex.containsMatchIn(row.joinedText) || row.items.size < 2) {
                false
            } else {
                val matched = extractListRowAmount(row)
                matched != null && !isBreakdownOrLabelRow(row, matched)
            }
        }
        val looksLikeSingleDetail = singleDetailGuardKeywords.any { allText.contains(it) }
        if (looksLikeSingleDetail && genuineSignedRowCount < 2) {
            return null
        }

        val pagePlatform = detectPlatformAndChannel(allText)
        var activeYearMonth = YearMonth.from(fallbackDateTime.toLocalDate())
        var activeDate: LocalDate? = null

        val results = mutableListOf<ScreenshotParsedCandidate>()
        var idx = 0
        while (idx < rows.size) {
            val row = rows[idx]
            val joined = row.joinedText

            val ymMatch = yearMonthHeaderRegex.matchEntire(joined)
            if (ymMatch != null) {
                val y = ymMatch.groupValues[1].toIntOrNull()
                val m = ymMatch.groupValues[2].toIntOrNull()
                if (y != null && m != null && m in 1..12) {
                    activeYearMonth = YearMonth.of(y, m)
                }
                idx++
                continue
            }

            if (row.items.size == 1) {
                val headerDate = parseDateOnlyHeader(joined, activeYearMonth, fallbackDateTime.toLocalDate())
                if (headerDate != null) {
                    activeDate = headerDate
                    idx++
                    continue
                }
            }

            if (summaryOrHeaderRowRegex.containsMatchIn(joined) || skippedListRowRegex.containsMatchIn(joined)) {
                idx++
                continue
            }

            val listRowAmount = extractListRowAmount(row)
            if (listRowAmount == null || isBreakdownOrLabelRow(row, listRowAmount)) {
                idx++
                continue
            }
            val rowAmount = listRowAmount.amount

            val merchantParts = row.items.dropLast(listRowAmount.consumedTokens)
                .map { it.text.trim() }
                .filter { it.isNotEmpty() && !isStandaloneAmountToken(it) && it !in uiChromeWords }
            var merchant = merchantParts.joinToString(" ").trim()
            if (merchant.isEmpty() && idx > 0) {
                val prev = rows[idx - 1].joinedText
                if (!summaryOrHeaderRowRegex.containsMatchIn(prev) &&
                    extractListRowAmount(rows[idx - 1]) == null &&
                    parseDateOnlyHeader(prev, activeYearMonth, fallbackDateTime.toLocalDate()) == null &&
                    yearMonthHeaderRegex.matchEntire(prev) == null
                ) {
                    merchant = prev
                }
            }
            val merchantNorm = trailingPunctRegex.replace(merchant, "")
            if (merchant.isEmpty() ||
                summaryOrHeaderRowRegex.containsMatchIn(merchant) ||
                merchantNorm in noiseAmountLabels ||
                merchantNorm in priorityAmountLabels ||
                noiseAmountLineRegex.containsMatchIn(merchantNorm)
            ) {
                idx++
                continue
            }

            var rowTime: ExtractedTime? = null
            var subStatusText = ""
            var consumed = 1
            for (lookAhead in 1..2) {
                val nextRow = rows.getOrNull(idx + lookAhead) ?: break
                val nextJoined = nextRow.joinedText
                if (extractListRowAmount(nextRow) != null ||
                    yearMonthHeaderRegex.matchEntire(nextJoined) != null ||
                    summaryOrHeaderRowRegex.containsMatchIn(nextJoined)
                ) {
                    break
                }
                val parsedSubTime = parseTimeExpression(
                    text = nextJoined,
                    activeYearMonth = activeYearMonth,
                    activeDate = activeDate,
                    fallbackDate = fallbackDateTime.toLocalDate(),
                    zoneId = zoneId
                )
                if (parsedSubTime != null) {
                    rowTime = parsedSubTime
                    consumed = lookAhead + 1
                } else if (skippedListRowRegex.containsMatchIn(nextJoined) ||
                    explicitRefundCueRegex.containsMatchIn(nextJoined)
                ) {
                    subStatusText = nextJoined
                    consumed = lookAhead + 1
                }
            }

            if (skippedListRowRegex.containsMatchIn(subStatusText)) {
                idx += consumed
                continue
            }

            val resolvedTime = rowTime ?: if (activeDate != null) {
                ExtractedTime(
                    timestampMillis = activeDate.atTime(12, 0, 0).atZone(zoneId).toInstant().toEpochMilli(),
                    hasTimeOfDay = false
                )
            } else {
                ExtractedTime(
                    fallbackDateTime.toLocalDate().atTime(12, 0, 0).atZone(zoneId).toInstant().toEpochMilli(),
                    hasTimeOfDay = false
                )
            }

            val sanitizedMerchantAndStatus = nonCompletedRefundStripRegex.replace("$merchant $subStatusText", "")
            val txType = when {
                explicitRefundCueRegex.containsMatchIn(sanitizedMerchantAndStatus) ||
                    sanitizedMerchantAndStatus.contains("退款") -> TransactionType.REFUND
                rowAmount.signHint != null -> rowAmount.signHint
                else -> PaymentTextClassifier.classifyTransactionType(sanitizedMerchantAndStatus)
                    ?: TransactionType.EXPENSE
            }

            val rowPlatform = detectPlatformAndChannel(merchant)
            val resolvedChannel = if (rowPlatform.second != PaymentChannel.OTHER) {
                rowPlatform.second
            } else {
                pagePlatform.second
            }
            val note = buildNote(
                platformName = rowPlatform.first ?: pagePlatform.first,
                merchant = merchant,
                extra = ""
            )

            results += ScreenshotParsedCandidate(
                payment = ParsedPayment(
                    channel = resolvedChannel,
                    fundingMethod = if (resolvedChannel == PaymentChannel.BANK) {
                        rowPlatform.first ?: pagePlatform.first.orEmpty()
                    } else {
                        ""
                    },
                    type = txType,
                    amountCents = rowAmount.amountCents,
                    merchant = merchant,
                    timestampMillis = resolvedTime.timestampMillis,
                    source = CaptureSource.OCR,
                    note = note,
                    hasTimeOfDay = resolvedTime.hasTimeOfDay
                ),
                needsVerification = false
            )
            idx += consumed
        }

        return results.takeIf { it.size >= 2 }
    }

    private fun tryParseDetailMode(
        rows: List<VisualRow>,
        rawText: String,
        fallbackDateTime: java.time.ZonedDateTime,
        zoneId: ZoneId
    ): ScreenshotParsedCandidate? {
        val tokens = flattenAndMergeCurrencyTokens(rows)
        if (tokens.isEmpty()) return null

        val platform = detectPlatformAndChannel(rawText)
        val hasDetailAnchor = tokens.any { token ->
            val norm = trailingPunctRegex.replace(token, "")
            val label = splitColon(token)?.first
            norm in detailAnchors ||
                (label != null && label in detailAnchors) ||
                detailAnchors.any { anchor -> norm.contains(anchor) }
        } || platform.first != null

        if (!hasDetailAnchor) return null

        val extractedAmount = extractDetailAmount(rows, tokens) ?: return null
        val extractedTime = extractDetailTimestamp(tokens, fallbackDateTime, zoneId)
        val fundingMethod = extractFundingMethod(tokens, platform)
        val orderId = extractOrderId(tokens)

        val txType = resolveDetailTransactionType(
            rawText = rawText,
            tokens = tokens,
            signHint = extractedAmount.signHint
        )

        val merchantAndExtra = extractDetailMerchant(
            tokens = tokens,
            platformName = platform.first,
            channel = platform.second,
            fundingMethod = fundingMethod,
            txType = txType
        )

        val note = buildNote(
            platformName = platform.first,
            merchant = merchantAndExtra.first,
            extra = merchantAndExtra.second
        )

        return ScreenshotParsedCandidate(
            payment = ParsedPayment(
                channel = platform.second,
                fundingMethod = fundingMethod,
                type = txType,
                amountCents = extractedAmount.amountCents,
                merchant = merchantAndExtra.first,
                timestampMillis = extractedTime.timestampMillis,
                source = CaptureSource.OCR,
                externalOrderId = orderId,
                note = note,
                hasTimeOfDay = extractedTime.hasTimeOfDay
            ),
            needsVerification = false
        )
    }

    private fun tryParseFallback(
        rows: List<VisualRow>,
        fallbackDateTime: java.time.ZonedDateTime,
        zoneId: ZoneId
    ): ScreenshotParsedCandidate? {
        val tokens = flattenAndMergeCurrencyTokens(rows)
        var amount: ExtractedAmount? = null
        for (token in tokens) {
            if (noiseAmountLineRegex.containsMatchIn(token)) continue
            val standalone = parseStandaloneAmount(token)
            if (standalone != null) {
                amount = standalone
                break
            }
            val inline = parseInlineCurrencyAmount(token)
            if (inline != null) {
                amount = inline
                break
            }
        }
        val resolvedAmount = amount ?: return null

        val merchant = tokens.firstOrNull { token ->
            !isStandaloneAmountToken(token) &&
                parseInlineCurrencyAmount(token) == null &&
                token !in uiChromeWords &&
                !longNumericIdRegex.matches(token) &&
                parseTimeExpression(
                    text = token,
                    activeYearMonth = YearMonth.from(fallbackDateTime.toLocalDate()),
                    activeDate = null,
                    fallbackDate = fallbackDateTime.toLocalDate(),
                    zoneId = zoneId
                ) == null
        } ?: "待确认消费"

        val extractedTime = extractDetailTimestamp(tokens, fallbackDateTime, zoneId)
        val txType = resolvedAmount.signHint ?: TransactionType.EXPENSE

        return ScreenshotParsedCandidate(
            payment = ParsedPayment(
                channel = PaymentChannel.OTHER,
                fundingMethod = "",
                type = txType,
                amountCents = resolvedAmount.amountCents,
                merchant = merchant,
                timestampMillis = extractedTime.timestampMillis,
                source = CaptureSource.OCR,
                hasTimeOfDay = extractedTime.hasTimeOfDay
            ),
            needsVerification = true
        )
    }

    private fun isBreakdownOrLabelRow(row: VisualRow, amountMatch: ListRowAmount): Boolean {
        val leftText = row.items.dropLast(amountMatch.consumedTokens)
            .joinToString("") { it.text.trim() }
            .trim()
        val leftNorm = trailingPunctRegex.replace(leftText, "")
        if (leftNorm.isEmpty()) return true
        return leftNorm in noiseAmountLabels ||
            leftNorm in priorityAmountLabels ||
            noiseAmountLineRegex.containsMatchIn(leftNorm)
    }

    private fun extractListRowAmount(row: VisualRow): ListRowAmount? {
        if (row.items.size < 2) return null
        val last = row.items.last().text.trim()
        parseListAmountToken(last)?.let { return ListRowAmount(it, consumedTokens = 1) }
        if (row.items.size >= 3) {
            val combinedLastTwo = "${row.items[row.items.size - 2].text.trim()}$last"
            parseListAmountToken(combinedLastTwo)?.let { return ListRowAmount(it, consumedTokens = 2) }
        }
        return null
    }

    private fun parseListAmountToken(token: String): ExtractedAmount? {
        val trimmed = token.trim()
        signedStandaloneAmountRegex.matchEntire(trimmed)?.let { match ->
            val sign = if (match.groupValues[1] in setOf("+", "＋")) TransactionType.INCOME else TransactionType.EXPENSE
            val cents = AmountParser.parseToCents(match.groupValues[2]) ?: return null
            return ExtractedAmount(cents, sign)
        }
        currencyStandaloneAmountRegex.matchEntire(trimmed)?.let { match ->
            val rawSign = match.groupValues[1]
            val sign = when (rawSign) {
                "+", "＋" -> TransactionType.INCOME
                "-", "－" -> TransactionType.EXPENSE
                else -> null
            }
            val num = match.groupValues[2].ifEmpty { match.groupValues[3] }
            val cents = AmountParser.parseToCents(num) ?: return null
            return ExtractedAmount(cents, sign)
        }
        return null
    }

    private fun flattenAndMergeCurrencyTokens(rows: List<VisualRow>): List<String> {
        val raw = rows.flatMap { row -> row.items.map { it.text.trim() } }.filter { it.isNotEmpty() }
        val merged = ArrayList<String>(raw.size)
        var i = 0
        while (i < raw.size) {
            val curr = raw[i]
            val next = raw.getOrNull(i + 1)
            if (curr in currencyOnlySet && next != null && AmountParser.parseToCents(next) != null) {
                merged += "¥$next"
                i += 2
            } else if (next == "元" && AmountParser.parseToCents(curr) != null) {
                merged += "${curr}元"
                i += 2
            } else {
                merged += curr
                i += 1
            }
        }
        return merged
    }

    private fun extractDetailAmount(rows: List<VisualRow>, tokens: List<String>): ExtractedAmount? {
        // Pass 1: Actual-paid labels strictly take priority over generic transaction/order amounts
        extractAmountByLabelSet(rows, tokens, actualPaidLabels)?.let { return it }

        // Pass 2: Generic priority labels (e.g. "交易金额", "消费金额", "付款金额")
        extractAmountByLabelSet(rows, tokens, genericPriorityAmountLabels)?.let { return it }

        // Pass 3: Standalone currency/signed amount not preceded by a noise label
        for (i in tokens.indices) {
            val prevNorm = tokens.getOrNull(i - 1)?.let { trailingPunctRegex.replace(it, "") }
            if (prevNorm in noiseAmountLabels) continue
            val token = tokens[i]
            if (noiseAmountLineRegex.containsMatchIn(token)) continue
            parseStandaloneAmount(token)?.let { return it }
        }

        // Pass 4: Inline currency match on non-noise token
        for (i in tokens.indices) {
            val prevNorm = tokens.getOrNull(i - 1)?.let { trailingPunctRegex.replace(it, "") }
            if (prevNorm in noiseAmountLabels) continue
            val token = tokens[i]
            if (noiseAmountLineRegex.containsMatchIn(token)) continue
            parseInlineCurrencyAmount(token)?.let { return it }
        }
        return null
    }

    private fun extractAmountByLabelSet(
        rows: List<VisualRow>,
        tokens: List<String>,
        labels: Set<String>
    ): ExtractedAmount? {
        val sortedLabels = labels.sortedByDescending { it.length }

        // Sub-pass A: Visual rows (supports both "实付款 | ¥249.00" and "已优惠¥8.00 | 实付¥31.50")
        for (row in rows) {
            val rowItems = row.items.map { it.text.trim() }
            for (k in rowItems.indices) {
                val item = rowItems[k]
                val norm = trailingPunctRegex.replace(item, "")
                if (norm in labels && k + 1 < rowItems.size) {
                    val rest = rowItems.drop(k + 1).joinToString("")
                    parseStandaloneAmount(rest)?.let { return it }
                    parseInlineCurrencyAmount(rest)?.let { return it }
                }
                for (label in sortedLabels) {
                    val matchPos = item.indexOf(label)
                    if (matchPos >= 0 && (matchPos == 0 || item[matchPos - 1] != '待')) {
                        val afterLabel = item.substring(matchPos + label.length)
                            .trimStart(':', '：', ' ')
                            .trim()
                        if (afterLabel.isNotEmpty()) {
                            parseStandaloneAmount(afterLabel)?.let { return it }
                            parseInlineCurrencyAmount(afterLabel)?.let { return it }
                        } else if (k + 1 < rowItems.size) {
                            val rest = rowItems.drop(k + 1).joinToString("")
                            parseStandaloneAmount(rest)?.let { return it }
                            parseInlineCurrencyAmount(rest)?.let { return it }
                        }
                    }
                }
            }
        }

        // Sub-pass B: Flattened tokens (colon pair, adjacent next token, or embedded suffix)
        for (i in tokens.indices) {
            val token = tokens[i]
            val pair = splitColon(token)
            if (pair != null && pair.first in labels && pair.second.isNotEmpty()) {
                parseStandaloneAmount(pair.second)?.let { return it }
                parseInlineCurrencyAmount(pair.second)?.let { return it }
            }
            val norm = trailingPunctRegex.replace(token, "")
            if (norm in labels) {
                val next = tokens.getOrNull(i + 1)
                if (next != null) {
                    parseStandaloneAmount(next)?.let { return it }
                    parseInlineCurrencyAmount(next)?.let { return it }
                }
            }
            for (label in sortedLabels) {
                val matchPos = token.indexOf(label)
                if (matchPos >= 0 && (matchPos == 0 || token[matchPos - 1] != '待')) {
                    val suffix = token.substring(matchPos + label.length)
                        .trimStart(':', '：', ' ')
                        .trim()
                    if (suffix.isNotEmpty()) {
                        parseStandaloneAmount(suffix)?.let { return it }
                        parseInlineCurrencyAmount(suffix)?.let { return it }
                    }
                }
            }
        }
        return null
    }

    private fun parseStandaloneAmount(raw: String): ExtractedAmount? {
        val trimmed = raw.trim()
        parseListAmountToken(trimmed)?.let { return it }
        plainDecimalRegex.matchEntire(trimmed)?.let { match ->
            val sign = when (match.groupValues[1]) {
                "+", "＋" -> TransactionType.INCOME
                "-", "－" -> TransactionType.EXPENSE
                else -> null
            }
            val cents = AmountParser.parseToCents(match.groupValues[2]) ?: return null
            return ExtractedAmount(cents, sign)
        }
        return null
    }

    private fun parseInlineCurrencyAmount(raw: String): ExtractedAmount? {
        val match = inlineCurrencyRegex.find(raw) ?: return null
        val sign = when (match.groupValues[1]) {
            "+", "＋" -> TransactionType.INCOME
            "-", "－" -> TransactionType.EXPENSE
            else -> null
        }
        val num = match.groupValues[2].ifEmpty { match.groupValues[3] }
        val cents = AmountParser.parseToCents(num) ?: return null
        return ExtractedAmount(cents, sign)
    }

    private fun isStandaloneAmountToken(token: String): Boolean =
        parseStandaloneAmount(token) != null

    private fun extractDetailTimestamp(
        tokens: List<String>,
        fallbackDateTime: java.time.ZonedDateTime,
        zoneId: ZoneId
    ): ExtractedTime {
        val fallbackDate = fallbackDateTime.toLocalDate()
        val fallbackYm = YearMonth.from(fallbackDate)

        // First check explicitly labeled time tokens
        for (i in tokens.indices) {
            val token = tokens[i]
            val pair = splitColon(token)
            if (pair != null && pair.first in timeLabels && pair.second.isNotEmpty()) {
                parseTimeExpression(pair.second, fallbackYm, null, fallbackDate, zoneId)?.let { return it }
            }
            val norm = trailingPunctRegex.replace(token, "")
            if (norm in timeLabels) {
                tokens.getOrNull(i + 1)?.let { next ->
                    parseTimeExpression(next, fallbackYm, null, fallbackDate, zoneId)?.let { return it }
                }
            }
            for (label in timeLabels) {
                if (token.startsWith(label) && token.length > label.length) {
                    parseTimeExpression(token.removePrefix(label).trim(), fallbackYm, null, fallbackDate, zoneId)
                        ?.let { return it }
                }
            }
        }

        // Next check any full date-time line, then date-only line
        var dateOnlyFallback: ExtractedTime? = null
        for (token in tokens) {
            val parsed = parseTimeExpression(token, fallbackYm, null, fallbackDate, zoneId) ?: continue
            if (parsed.hasTimeOfDay) return parsed
            if (dateOnlyFallback == null) dateOnlyFallback = parsed
        }
        if (dateOnlyFallback != null) return dateOnlyFallback

        return ExtractedTime(
            fallbackDate.atTime(12, 0, 0).atZone(zoneId).toInstant().toEpochMilli(),
            hasTimeOfDay = false
        )
    }

    private fun parseDateOnlyHeader(
        text: String,
        activeYearMonth: YearMonth,
        fallbackDate: LocalDate
    ): LocalDate? {
        val trimmed = text.trim()
        relativeDayOnlyRegex.matchEntire(trimmed)?.let { match ->
            return resolveRelativeDate(match.groupValues[1], fallbackDate)
        }
        fullDateOnlyRegex.matchEntire(trimmed)?.let { match ->
            return safeLocalDate(
                match.groupValues[1].toIntOrNull(),
                match.groupValues[2].toIntOrNull(),
                match.groupValues[3].toIntOrNull()
            )
        }
        monthDayOnlyRegex.matchEntire(trimmed)?.let { match ->
            return safeLocalDate(
                activeYearMonth.year,
                match.groupValues[1].toIntOrNull(),
                match.groupValues[2].toIntOrNull()
            )
        }
        return null
    }

    private fun parseTimeExpression(
        text: String,
        activeYearMonth: YearMonth,
        activeDate: LocalDate?,
        fallbackDate: LocalDate,
        zoneId: ZoneId
    ): ExtractedTime? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        fullDateTimeRegex.find(trimmed)?.let { m ->
            val date = safeLocalDate(m.groupValues[1].toIntOrNull(), m.groupValues[2].toIntOrNull(), m.groupValues[3].toIntOrNull())
            val time = safeLocalTime(m.groupValues[4].toIntOrNull(), m.groupValues[5].toIntOrNull(), m.groupValues[6].toIntOrNull() ?: 0)
            if (date != null && time != null) {
                return ExtractedTime(LocalDateTime.of(date, time).atZone(zoneId).toInstant().toEpochMilli(), true)
            }
        }

        relativeDayTimeRegex.find(trimmed)?.let { m ->
            val date = resolveRelativeDate(m.groupValues[1], fallbackDate)
            val time = safeLocalTime(m.groupValues[2].toIntOrNull(), m.groupValues[3].toIntOrNull(), m.groupValues[4].toIntOrNull() ?: 0)
            if (time != null) {
                return ExtractedTime(LocalDateTime.of(date, time).atZone(zoneId).toInstant().toEpochMilli(), true)
            }
        }

        monthDayTimeRegex.find(trimmed)?.let { m ->
            val month = m.groupValues[1].toIntOrNull()
            val day = m.groupValues[2].toIntOrNull()
            val time = safeLocalTime(m.groupValues[3].toIntOrNull(), m.groupValues[4].toIntOrNull(), m.groupValues[5].toIntOrNull() ?: 0)
            val date = safeLocalDate(activeYearMonth.year, month, day)
            if (date != null && time != null) {
                return ExtractedTime(LocalDateTime.of(date, time).atZone(zoneId).toInstant().toEpochMilli(), true)
            }
        }

        fullDateOnlyRegex.find(trimmed)?.let { m ->
            val date = safeLocalDate(m.groupValues[1].toIntOrNull(), m.groupValues[2].toIntOrNull(), m.groupValues[3].toIntOrNull())
            if (date != null) {
                return ExtractedTime(date.atTime(12, 0, 0).atZone(zoneId).toInstant().toEpochMilli(), false)
            }
        }

        monthDayOnlyRegex.matchEntire(trimmed)?.let { m ->
            val date = safeLocalDate(activeYearMonth.year, m.groupValues[1].toIntOrNull(), m.groupValues[2].toIntOrNull())
            if (date != null) {
                return ExtractedTime(date.atTime(12, 0, 0).atZone(zoneId).toInstant().toEpochMilli(), false)
            }
        }

        monthDayChineseInlineRegex.find(trimmed)?.let { m ->
            val date = safeLocalDate(activeYearMonth.year, m.groupValues[1].toIntOrNull(), m.groupValues[2].toIntOrNull())
            if (date != null) {
                return ExtractedTime(date.atTime(12, 0, 0).atZone(zoneId).toInstant().toEpochMilli(), false)
            }
        }

        relativeDayOnlyRegex.matchEntire(trimmed)?.let { m ->
            val date = resolveRelativeDate(m.groupValues[1], fallbackDate)
            return ExtractedTime(date.atTime(12, 0, 0).atZone(zoneId).toInstant().toEpochMilli(), false)
        }

        timeOnlyRegex.matchEntire(trimmed)?.let { m ->
            val baseDate = activeDate ?: fallbackDate
            val time = safeLocalTime(m.groupValues[1].toIntOrNull(), m.groupValues[2].toIntOrNull(), m.groupValues[3].toIntOrNull() ?: 0)
            if (time != null) {
                return ExtractedTime(LocalDateTime.of(baseDate, time).atZone(zoneId).toInstant().toEpochMilli(), true)
            }
        }

        return null
    }

    private fun resolveRelativeDate(word: String, fallbackDate: LocalDate): LocalDate = when (word) {
        "昨天", "昨日" -> fallbackDate.minusDays(1)
        "前天" -> fallbackDate.minusDays(2)
        else -> fallbackDate
    }

    private fun safeLocalDate(year: Int?, month: Int?, day: Int?): LocalDate? {
        if (year == null || month == null || day == null) return null
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    private fun safeLocalTime(hour: Int?, minute: Int?, second: Int): LocalTime? {
        if (hour == null || minute == null) return null
        return runCatching { LocalTime.of(hour, minute, second) }.getOrNull()
    }

    private fun detectPlatformAndChannel(text: String): Pair<String?, PaymentChannel> {
        for ((label, channel) in platformLabels) {
            if (text.contains(label)) {
                return label to channel
            }
        }
        for (bankName in PaymentPackages.defaultBankPackages.values) {
            if (text.contains(bankName)) {
                return bankName to PaymentChannel.BANK
            }
        }
        if (text.contains("已存入你的零钱") || text.contains("待朋友收款") || text.contains("你已收款")) {
            return "微信支付" to PaymentChannel.WECHAT
        }
        return null to PaymentChannel.OTHER
    }

    private fun extractFundingMethod(
        tokens: List<String>,
        platform: Pair<String?, PaymentChannel>
    ): String {
        for (i in tokens.indices) {
            val token = tokens[i]
            val pair = splitColon(token)
            if (pair != null && pair.first in fundingLabels && pair.second.isNotEmpty()) {
                return pair.second
            }
            val norm = trailingPunctRegex.replace(token, "")
            if (norm in fundingLabels) {
                val next = tokens.getOrNull(i + 1)
                if (next != null && next !in uiChromeWords && !isStandaloneAmountToken(next)) {
                    return next
                }
            }
        }
        tokens.firstOrNull { standaloneFundingRegex.matches(it) }?.let { return it }
        if (platform.second == PaymentChannel.BANK) {
            return platform.first.orEmpty()
        }
        return ""
    }

    private fun extractOrderId(tokens: List<String>): String? {
        for (i in tokens.indices) {
            val token = tokens[i]
            val pair = splitColon(token)
            if (pair != null && pair.first in orderIdLabels && pair.second.isNotEmpty()) {
                return pair.second.trim()
            }
            val norm = trailingPunctRegex.replace(token, "")
            if (norm in orderIdLabels) {
                val next = tokens.getOrNull(i + 1)?.trim()
                if (!next.isNullOrEmpty() && next.length >= 6) {
                    return next
                }
            }
        }
        return null
    }

    private fun resolveDetailTransactionType(
        rawText: String,
        tokens: List<String>,
        signHint: TransactionType?
    ): TransactionType {
        val filteredTokens = tokens
            .filter { it !in uiChromeWords }
            .map { nonCompletedRefundStripRegex.replace(it, "").trim() }
            .filter { it.isNotEmpty() }
        val sanitizedText = if (filteredTokens.isNotEmpty()) {
            filteredTokens.joinToString("\n")
        } else {
            nonCompletedRefundStripRegex.replace(rawText, "")
        }
        val classified = PaymentTextClassifier.classifyTransactionType(sanitizedText)
        if (classified != null) return classified
        if (signHint != null) return signHint
        if (filteredTokens.any { explicitRefundCueRegex.containsMatchIn(it) }) return TransactionType.REFUND
        return TransactionType.EXPENSE
    }

    private fun extractDetailMerchant(
        tokens: List<String>,
        platformName: String?,
        channel: PaymentChannel,
        fundingMethod: String,
        txType: TransactionType
    ): Pair<String, String> {
        val labeledValueIndices = collectSkipValueIndices(tokens)

        // 1. Explicitly labeled merchant/store
        for (i in tokens.indices) {
            val token = tokens[i]
            val pair = splitColon(token)
            if (pair != null && pair.first in merchantLabels && isUsableMerchant(pair.second, platformName, fundingMethod)) {
                val secondary = findSecondaryItemTitle(tokens, i, labeledValueIndices, pair.second, platformName, fundingMethod)
                return pair.second to secondary
            }
            val norm = trailingPunctRegex.replace(token, "")
            if (norm in merchantLabels) {
                val next = tokens.getOrNull(i + 1)
                if (next != null && isUsableMerchant(next, platformName, fundingMethod)) {
                    val secondary = findSecondaryItemTitle(tokens, i + 1, labeledValueIndices, next, platformName, fundingMethod)
                    return next to secondary
                }
            }
        }

        // 2. First clean non-label, non-chrome candidate token
        for (i in tokens.indices) {
            if (i in labeledValueIndices) continue
            val token = tokens[i]
            if (isUsableMerchant(token, platformName, fundingMethod)) {
                val secondary = findSecondaryItemTitle(tokens, i, labeledValueIndices, token, platformName, fundingMethod)
                return token to secondary
            }
        }

        if (txType == TransactionType.TRANSFER) {
            return PaymentTextClassifier.inferTransferTitle(tokens.joinToString("\n")) to ""
        }
        val joined = tokens.joinToString("\n")
        if (channel == PaymentChannel.WECHAT && (joined.contains("转账") || joined.contains("收款"))) {
            return "微信转账" to ""
        }
        val fallbackName = platformName ?: when (channel) {
            PaymentChannel.WECHAT -> "微信支付"
            PaymentChannel.ALIPAY -> "支付宝"
            PaymentChannel.UNIONPAY -> "云闪付"
            PaymentChannel.BANK -> "银行卡"
            else -> "待确认商户"
        }
        return fallbackName to ""
    }

    private fun findSecondaryItemTitle(
        tokens: List<String>,
        merchantIndex: Int,
        skipIndices: Set<Int>,
        merchant: String,
        platformName: String?,
        fundingMethod: String
    ): String {
        val nextIdx = merchantIndex + 1
        if (nextIdx !in tokens.indices || nextIdx in skipIndices) return ""
        val next = tokens[nextIdx]
        if (next == merchant || !isUsableMerchant(next, platformName, fundingMethod)) return ""
        return next
    }

    private fun collectSkipValueIndices(tokens: List<String>): Set<Int> {
        val allNonMerchantLabels = priorityAmountLabels + noiseAmountLabels + fundingLabels +
            orderIdLabels + timeLabels + otherLabels
        val indices = mutableSetOf<Int>()
        for (i in tokens.indices) {
            val pair = splitColon(tokens[i])
            if (pair != null && pair.first in allNonMerchantLabels) {
                indices += i
                if (pair.second.isEmpty() && i + 1 < tokens.size) indices += (i + 1)
            } else {
                val norm = trailingPunctRegex.replace(tokens[i], "")
                if (norm in allNonMerchantLabels) {
                    indices += i
                    if (i + 1 < tokens.size) indices += (i + 1)
                }
            }
        }
        return indices
    }

    private fun isUsableMerchant(
        raw: String,
        platformName: String?,
        fundingMethod: String
    ): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > 48) return false
        if (trimmed in uiChromeWords) return false
        val norm = trailingPunctRegex.replace(trimmed, "")
        if (norm in detailAnchors || norm in priorityAmountLabels || norm in noiseAmountLabels ||
            norm in fundingLabels || norm in orderIdLabels || norm in timeLabels || norm in otherLabels
        ) {
            return false
        }
        val pair = splitColon(trimmed)
        if (pair != null && (pair.first in timeLabels || pair.first in orderIdLabels ||
                pair.first in fundingLabels || pair.first in priorityAmountLabels ||
                pair.first in noiseAmountLabels || pair.first in otherLabels)
        ) {
            return false
        }
        if (platformName != null && trimmed == platformName) return false
        if (fundingMethod.isNotEmpty() && trimmed == fundingMethod) return false
        if (standaloneFundingRegex.matches(trimmed)) return false
        if (isStandaloneAmountToken(trimmed) || parseInlineCurrencyAmount(trimmed) != null) return false
        if (longNumericIdRegex.matches(trimmed)) return false
        if (fullDateTimeRegex.containsMatchIn(trimmed) || fullDateOnlyRegex.containsMatchIn(trimmed)) return false
        if (noiseAmountLineRegex.containsMatchIn(trimmed)) return false
        return true
    }

    private fun buildNote(platformName: String?, merchant: String, extra: String): String {
        val parts = mutableListOf<String>()
        if (!platformName.isNullOrBlank() &&
            platformName !in setOf("微信支付", "微信", "支付宝") &&
            !merchant.contains(platformName)
        ) {
            parts += platformName
        }
        if (extra.isNotBlank() && extra != merchant && extra != platformName) {
            parts += extra
        }
        return parts.joinToString(" · ")
    }

    private fun splitColon(text: String): Pair<String, String>? {
        val full = text.indexOf('：')
        val half = text.indexOf(':')
        val idx = when {
            full >= 0 && half >= 0 -> minOf(full, half)
            full >= 0 -> full
            else -> half
        }
        if (idx <= 0) return null
        val label = trailingPunctRegex.replace(text.substring(0, idx).trim(), "")
        val value = text.substring(idx + 1).trim()
        return label to value
    }
}
