package com.momentledger.app.domain.parser

import com.momentledger.app.domain.model.CaptureSource
import com.momentledger.app.domain.model.ParsedPayment
import com.momentledger.app.domain.model.PaymentChannel
import com.momentledger.app.domain.model.TransactionType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.IdentityHashMap
import kotlin.math.abs

data class PdfTextToken(
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

data class PdfPageContent(
    val pageNumber: Int,
    val width: Float = 842f,
    val height: Float = 595f,
    val rawText: String = "",
    val tokens: List<PdfTextToken> = emptyList()
)

/**
 * Pure-Kotlin, Android-ICU-safe (zero lookbehind) parser for Chinese bank PDF statements.
 *
 * Supports structured PDFBox coordinate tokens as well as extracted text lines across:
 * - ICBC (中国工商银行借记账户历史明细)
 * - CMB (招商银行交易流水)
 * - Generic Chinese bank statements (CCB, ABC, BOC, BCM, PSBC, SPDB, Ping An, CITIC, etc.)
 */
object BankPdfParser {

    private enum class BankColRole {
        DATE,
        TIME,
        CURRENCY,
        SUMMARY,
        AMOUNT,
        DEBIT,
        CREDIT,
        BALANCE,
        COUNTERPARTY,
        CHANNEL,
        IGNORE
    }

    private data class ColumnSpec(
        val role: BankColRole,
        val startX: Float,
        val endX: Float
    )

    private data class BankHeaderMeta(
        val bankName: String,
        val cardLast4: String,
        val accountHolder: String
    ) {
        val fundingMethod: String
            get() = when {
                bankName.isNotEmpty() && cardLast4.isNotEmpty() -> "$bankName($cardLast4)"
                bankName.isNotEmpty() -> bankName
                cardLast4.isNotEmpty() -> "银行卡($cardLast4)"
                else -> "银行卡"
            }
    }

    private data class RawBankRecord(
        val timestampMillis: Long,
        val hasTimeOfDay: Boolean,
        val signedAmountCents: Long,
        val summary: String,
        val counterparty: String,
        val channelHint: String = ""
    )

    private val bankNameCatalog: List<Pair<String, String>> = listOf(
        "中国工商银行" to "工商银行",
        "工商银行" to "工商银行",
        "招商银行" to "招商银行",
        "中国建设银行" to "建设银行",
        "建设银行" to "建设银行",
        "中国农业银行" to "农业银行",
        "农业银行" to "农业银行",
        "中国银行" to "中国银行",
        "交通银行" to "交通银行",
        "中国邮政储蓄银行" to "邮储银行",
        "邮储银行" to "邮储银行",
        "浦发银行" to "浦发银行",
        "浦东发展银行" to "浦发银行",
        "中信银行" to "中信银行",
        "平安银行" to "平安银行",
        "兴业银行" to "兴业银行",
        "民生银行" to "民生银行",
        "光大银行" to "光大银行",
        "广发银行" to "广发银行",
        "华夏银行" to "华夏银行",
        "北京银行" to "北京银行",
        "上海银行" to "上海银行",
        "宁波银行" to "宁波银行"
    )

    val knownBankNames: Set<String> = bankNameCatalog.mapTo(LinkedHashSet()) { it.second }

    private val cardNumberRegex = Regex(
        """(?:卡\s*号|账\s*号|账户|Account\s*No)[:：\s]*([0-9*＊][0-9*＊\s-]{5,27})""",
        RegexOption.IGNORE_CASE
    )
    private val holderNameRegex = Regex(
        """户[\s\u00A0]*名[:：\s]*([\u4e00-\u9fa5A-Za-z·]{2,20})"""
    )
    private val invalidHolderValues = setOf("对方账号", "对方户名", "账号", "卡号", "账户", "币种", "钞汇", "余额")

    private val dateStartRegex = Regex("""^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?:\s+(\d{1,2}):(\d{2})(?::(\d{2}))?)?(?:\s+|$)""")
    private val compactDateStartRegex = Regex("""^(\d{4})(\d{2})(\d{2})(?:\s+(\d{1,2}):(\d{2})(?::(\d{2}))?)?(?:\s+|$)""")
    private val dateRangeLineRegex = Regex("""^\d{4}[-/.]\d{1,2}[-/.]\d{1,2}\s*[-—–~至]{1,3}\s*\d{4}[-/.]\d{1,2}[-/.]\d{1,2}""")
    private val timeOnlyTokenRegex = Regex("""^(\d{1,2}):(\d{2})(?::(\d{2}))?$""")

    private val signedAmountTokenRegex = Regex("""^(?:[¥￥$]\s*)?[+\-＋－]\s*(?:[¥￥$]\s*)?\d[\d,]*\.\d{2}$""")
    private val plainAmountTokenRegex = Regex("""^(?:[¥￥$]\s*)?[+\-＋－]?\s*(?:[¥￥$]\s*)?\d[\d,]*\.\d{2}$""")
    private val currencyCodeRegex = Regex("""^(?:CNY|RMB|USD|HKD|EUR|GBP|JPY|人民币)$""", RegexOption.IGNORE_CASE)
    private val fourOrMoreDigitsRegex = Regex("""^\d{4,}$""")
    private val maskedAccountTokenRegex = Regex("""^[0-9A-Za-z*＊]{6,}$""")
    private val unsignedCreditCueRegex = Regex("""工资|薪资|报销|收款|入账|汇入|转入|利息|结息|退回|退款|分红|返现|贷方|存入""")

    // ICBC structured row regex after flattening a date-anchored record
    private val icbcRowRegex = Regex(
        """^(\d{4}-\d{2}-\d{2})\s+(\d{2}:\d{2}:\d{2})\s+\d{10,24}\s+(?:活期|定期|零存整取|通知存款)\s+\d+\s+人民币\s*[钞汇]?\s+(.+?)\s+(\d{4})\s+([+\-＋－]\d[\d,]*\.\d{2})\s+(-?\d[\d,]*\.\d{2})\s+(.+?)\s+([0-9A-Za-z*＊]{4,24}|[（(]空[）)])\s+(\S+)$"""
    )

    // CMB structured row regex after flattening a date-anchored record
    private val cmbRowRegex = Regex(
        """^(\d{4}-\d{2}-\d{2})\s+(?:CNY|RMB|USD|HKD|EUR|GBP|JPY)\s+([+\-＋－]?\d[\d,]*\.\d{2})\s+(-?\d[\d,]*\.\d{2})\s+(\S+)\s+(.+)$""",
        RegexOption.IGNORE_CASE
    )

    private val watermarkOrFooterRegex = Regex(
        """本页支出算术合计|本页收入算术合计|本页交易笔数|下单时间[:：]|请扫描二维码|识别明细真伪|""" +
            """第\s*\d+\s*页(?:\s*[/／]?\s*共\s*\d+\s*页)?|共\s*\d+\s*页|^\d+\s*/\s*\d+$|温馨提示|交易流水验真|提交系统验真|电子回单专用章|业务专用章|""" +
            """Transaction\s+Statement|Verification\s+Code|Account\s+Type|Sub\s+Branch|""" +
            """中国工商银行\s+\d{5,}\s+\d{4}-\d{2}-\d{2}"""
    )

    private val pageFooterCutoffRegex = Regex(
        """本页支出算术合计|本页收入算术合计|本页交易笔数|温馨提示|交易流水验真"""
    )

    private val englishSubtitleTokens = setOf(
        "Date", "Currency", "Transaction", "Amount", "Balance", "Type", "Counter", "Party",
        "Name", "Account", "No", "Sub", "Branch", "Verification", "Code",
        "Transaction Amount", "Transaction Type", "Counter Party", "Account No"
    )

    private val tableHeaderLineRegex = Regex(
        """(?:交易日期|记账日期|交易时间).*(?:交易金额|收入/支出金额|发生额|支出金额|借方发生额|余额)"""
    )

    private val cjkSpaceBridgeRegex = Regex("""([\u4e00-\u9fa5（）()、，。])\s+([\u4e00-\u9fa5（）()、，。])""")
    private val multiSpaceRegex = Regex("""[\s\u00A0]+""")
    private val placeholderCounterparties = setOf("（空）", "(空)", "空", "/", "--", "-")

    private val refundSummaryRegex = Regex("""退款|冲正|退回|退费""")
    private val strongTransferSummaryRegex = Regex(
        """提现|零钱充值|余额充值|还款|理财|基金|定存|活期转定期|定期转活期|数币兑出|数币兑回|""" +
            """ATM存款|ATM取款|卡内互转|本人账户互转|同名账户转账|转入余额宝|转出余额宝|转入零钱通|转出零钱通"""
    )
    private val walletSettlementCounterpartyRegex = Regex("""支付宝支付科技|财付通|微信零钱|零钱""")
    private val walletNetInboundSummaryRegex = Regex("""银联入账|网联收款""")
    private val ownAccountTransferSummaryRegex = Regex("""转账|跨行|行内|汇款|转出|转入|网联付款|快捷支付|充值""")
    private val debitBeforeCreditHeaderRegex = Regex("""(?:支出|借方|转出)[^\n]{0,20}(?:收入|贷方|转入)""")
    private val creditBeforeDebitHeaderRegex = Regex("""(?:收入|贷方|转入)[^\n]{0,20}(?:支出|借方|转出)""")
    private val currencyPrefixRegex = Regex("""^(?:CNY|RMB|USD|HKD|EUR|GBP|JPY|[¥￥$])+\s*""", RegexOption.IGNORE_CASE)

    fun parsePages(
        pages: List<PdfPageContent>,
        fileName: String = "bank.pdf",
        zoneId: ZoneId = ZoneId.systemDefault()
    ): BillImportParseResult {
        if (pages.isEmpty()) {
            return BillImportParseResult(emptyList(), 0, "BANK_PDF")
        }

        val preambleText = extractFirstPagePreamble(pages.first())
        val meta = extractHeaderMeta(preambleText = preambleText, fileName = fileName)

        val allRecords = mutableListOf<RawBankRecord>()
        var cachedColumns: List<ColumnSpec>? = null
        var cachedDebitFirst: Boolean? = null

        for (page in pages) {
            val detectedDebitFirst = detectDualColumnDirection(page.rawText)
            if (detectedDebitFirst != null) cachedDebitFirst = detectedDebitFirst

            val pageRecords = if (page.tokens.isNotEmpty()) {
                val (parsed, cols) = parsePageFromTokens(page.tokens, cachedColumns, zoneId)
                if (cols != null) cachedColumns = cols
                val tokenHasDescriptor = parsed.any { it.summary.isNotBlank() || it.counterparty.isNotBlank() }
                if (parsed.isNotEmpty() && tokenHasDescriptor) {
                    parsed
                } else if (page.rawText.isNotBlank()) {
                    val rawParsed = parsePageFromRawText(page.rawText, zoneId, cachedDebitFirst)
                    if (rawParsed.isNotEmpty() && (parsed.isEmpty() || rawParsed.any { it.summary.isNotBlank() || it.counterparty.isNotBlank() })) {
                        rawParsed
                    } else {
                        parsed
                    }
                } else {
                    parsed
                }
            } else if (page.rawText.isNotBlank()) {
                parsePageFromRawText(page.rawText, zoneId, cachedDebitFirst)
            } else {
                emptyList()
            }
            allRecords += pageRecords
        }

        val payments = allRecords.mapNotNull { rec ->
            toParsedPayment(rec, meta)
        }
        return BillImportParseResult(
            items = payments,
            skippedCount = (allRecords.size - payments.size).coerceAtLeast(0),
            detectedFormat = "BANK_PDF"
        )
    }

    private fun extractFirstPagePreamble(firstPage: PdfPageContent): String {
        val lines = if (firstPage.rawText.isNotBlank()) {
            firstPage.rawText.lines().map { it.replace('\u00A0', ' ').trim() }.filter { it.isNotEmpty() }
        } else if (firstPage.tokens.isNotEmpty()) {
            clusterTokensIntoLines(firstPage.tokens).map { line ->
                line.joinToString(" ") { it.text.trim() }.trim()
            }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
        val sb = StringBuilder()
        for (line in lines) {
            if (tableHeaderLineRegex.containsMatchIn(line)) break
            val isDataRowStart = !dateRangeLineRegex.containsMatchIn(line) &&
                (dateStartRegex.containsMatchIn(line) || compactDateStartRegex.containsMatchIn(line))
            if (isDataRowStart) break
            sb.append(line).append('\n')
        }
        return sb.toString()
    }

    private fun detectDualColumnDirection(rawText: String): Boolean? {
        for (line in rawText.lines()) {
            if (!tableHeaderLineRegex.containsMatchIn(line)) continue
            if (debitBeforeCreditHeaderRegex.containsMatchIn(line)) return true
            if (creditBeforeDebitHeaderRegex.containsMatchIn(line)) return false
        }
        return null
    }

    private fun findEarliestBankName(text: String): String {
        if (text.isBlank()) return ""
        var bestIdx = Int.MAX_VALUE
        var bestName = ""
        for ((pattern, canonical) in bankNameCatalog) {
            val idx = text.indexOf(pattern)
            if (idx in 0 until bestIdx) {
                bestIdx = idx
                bestName = canonical
            }
        }
        return bestName
    }

    private fun extractHeaderMeta(preambleText: String, fileName: String): BankHeaderMeta {
        val bankName = findEarliestBankName(preambleText).ifEmpty { findEarliestBankName(fileName) }

        val cardLast4 = cardNumberRegex.findAll(preambleText)
            .firstNotNullOfOrNull { match ->
                val prefix = preambleText.substring(maxOf(0, match.range.first - 4), match.range.first)
                if (prefix.endsWith("对方") || prefix.endsWith("收款") || prefix.endsWith("付款")) {
                    null
                } else {
                    val digits = match.groupValues[1].filter { it.isDigit() }
                    if (digits.length >= 4) digits.takeLast(4) else null
                }
            }
            .orEmpty()

        val accountHolder = holderNameRegex.findAll(preambleText)
            .firstNotNullOfOrNull { match ->
                val prefix = preambleText.substring(maxOf(0, match.range.first - 4), match.range.first)
                val value = match.groupValues[1].trim()
                if (prefix.endsWith("对方") || prefix.endsWith("收款人") || prefix.endsWith("付款人") ||
                    value in invalidHolderValues
                ) {
                    null
                } else {
                    value
                }
            }
            .orEmpty()

        return BankHeaderMeta(
            bankName = bankName,
            cardLast4 = cardLast4,
            accountHolder = accountHolder
        )
    }

    private fun parsePageFromTokens(
        tokens: List<PdfTextToken>,
        inheritedColumns: List<ColumnSpec>?,
        zoneId: ZoneId
    ): Pair<List<RawBankRecord>, List<ColumnSpec>?> {
        val lines = clusterTokensIntoLines(tokens)
        if (lines.isEmpty()) return emptyList<RawBankRecord>() to inheritedColumns

        var headerLineIdx = -1
        var columns: List<ColumnSpec>? = inheritedColumns
        for (i in lines.indices) {
            val detected = detectHeaderColumns(lines[i])
            if (detected != null) {
                headerLineIdx = i
                columns = detected
                break
            }
        }
        val activeCols = columns ?: return emptyList<RawBankRecord>() to null

        val dataLines = if (headerLineIdx >= 0) lines.drop(headerLineIdx + 1) else lines
        val bodyLines = mutableListOf<List<PdfTextToken>>()
        var seenDateLine = false
        for (line in dataLines) {
            val lineText = line.joinToString(" ") { it.text.trim() }.trim()
            if (lineText.isEmpty()) continue
            if (seenDateLine && isPageFooterCutoff(lineText)) {
                break
            }
            if (isNoiseLine(lineText) || detectHeaderColumns(line) != null) {
                continue
            }
            if (isEnglishSubtitleLine(lineText)) continue

            val firstDateToken = findRowDateToken(line, lineText, activeCols)
            if (firstDateToken != null) {
                seenDateLine = true
            }
            bodyLines += line
        }

        val dateAnchors = mutableListOf<Pair<Int, PdfTextToken>>()
        for ((idx, line) in bodyLines.withIndex()) {
            val lineText = line.joinToString(" ") { it.text.trim() }.trim()
            val dateTok = findRowDateToken(line, lineText, activeCols)
            if (dateTok != null) {
                dateAnchors += idx to dateTok
            }
        }
        if (dateAnchors.isEmpty()) return emptyList<RawBankRecord>() to activeCols

        // Detect vertically-centered wrapped table layouts (e.g. CMB & ICBC):
        // - Either a 2-line DATE + TIME stack (ICBC), or
        // - Wrapped SUMMARY/COUNTERPARTY tokens sitting slightly above a row's date Y (CMB).
        val allBodyTokens = bodyLines.flatten()
        val rowCenterY = FloatArray(dateAnchors.size)
        var hasTwoLineDateTime = false
        for (k in dateAnchors.indices) {
            val dateTok = dateAnchors[k].second
            val nextDateY = if (k + 1 < dateAnchors.size) dateAnchors[k + 1].second.y else Float.MAX_VALUE
            val timeBelow = allBodyTokens.firstOrNull { tok ->
                tok !== dateTok &&
                    findColumnForToken(tok, activeCols)?.role == BankColRole.DATE &&
                    timeOnlyTokenRegex.matches(tok.text.trim()) &&
                    tok.y > dateTok.y + 1f &&
                    tok.y < minOf(dateTok.y + 14f, nextDateY - 2f)
            }
            if (timeBelow != null) {
                hasTwoLineDateTime = true
                rowCenterY[k] = (dateTok.y + timeBelow.y) / 2f
            } else {
                rowCenterY[k] = dateTok.y
            }
        }

        val hasWrappedAboveDate = dateAnchors.indices.any { k ->
            val dateY = dateAnchors[k].second.y
            val prevY = if (k > 0) rowCenterY[k - 1] else Float.NEGATIVE_INFINITY
            allBodyTokens.any { tok ->
                val role = findColumnForToken(tok, activeCols)?.role
                (role == BankColRole.SUMMARY || role == BankColRole.COUNTERPARTY || role == BankColRole.CHANNEL) &&
                    tok.y < dateY - 3f &&
                    tok.y >= dateY - 14f &&
                    abs(tok.y - rowCenterY[k]) < abs(tok.y - prevY)
            }
        }

        val groupedRows: List<MutableList<PdfTextToken>> = if (hasTwoLineDateTime || hasWrappedAboveDate) {
            val buckets = List(dateAnchors.size) { mutableListOf<PdfTextToken>() }
            val pitches = (1 until rowCenterY.size).map { rowCenterY[it] - rowCenterY[it - 1] }.filter { it > 4f }.sorted()
            val medianPitch = if (pitches.isNotEmpty()) pitches[pitches.size / 2] else 28f
            val maxRowRadius = maxOf(16f, minOf(medianPitch * 0.7f, 42f))
            val anchorTokenToRow = IdentityHashMap<PdfTextToken, Int>()
            for (k in dateAnchors.indices) {
                anchorTokenToRow[dateAnchors[k].second] = k
            }
            for (tok in allBodyTokens) {
                val pinned = anchorTokenToRow[tok]
                if (pinned != null) {
                    buckets[pinned] += tok
                    continue
                }
                var bestIdx = 0
                var bestDist = abs(tok.y - rowCenterY[0])
                for (k in 1 until rowCenterY.size) {
                    val dist = abs(tok.y - rowCenterY[k])
                    if (dist < bestDist) {
                        bestDist = dist
                        bestIdx = k
                    }
                }
                if (bestDist <= maxRowRadius) {
                    buckets[bestIdx] += tok
                }
            }
            buckets
        } else {
            val rows = mutableListOf<MutableList<PdfTextToken>>()
            val anchorLineSet = dateAnchors.mapTo(HashSet()) { it.first }
            for ((idx, line) in bodyLines.withIndex()) {
                if (idx in anchorLineSet) {
                    rows += line.toMutableList()
                } else if (rows.isNotEmpty()) {
                    rows.last() += line
                }
            }
            rows
        }

        val records = groupedRows.mapNotNull { rowTokens ->
            buildRecordFromColumnTokens(rowTokens, activeCols, zoneId)
        }
        return records to activeCols
    }

    private fun findRowDateToken(
        line: List<PdfTextToken>,
        lineText: String,
        activeCols: List<ColumnSpec>
    ): PdfTextToken? {
        if (dateRangeLineRegex.containsMatchIn(lineText)) return null
        val candidate = line.firstOrNull { tok ->
            findColumnForToken(tok, activeCols)?.role == BankColRole.DATE
        } ?: return null
        val text = candidate.text.trim()
        return if (dateStartRegex.containsMatchIn(text) || compactDateStartRegex.containsMatchIn(text)) {
            candidate
        } else {
            null
        }
    }

    private fun clusterTokensIntoLines(tokens: List<PdfTextToken>): List<List<PdfTextToken>> {
        val cleaned = tokens
            .mapNotNull { t ->
                val trimmed = t.text.trim()
                if (trimmed.isEmpty()) null else t.copy(text = trimmed)
            }
            .sortedWith(compareBy<PdfTextToken> { it.y }.thenBy { it.x })

        val groups = mutableListOf<MutableList<PdfTextToken>>()
        val groupY = mutableListOf<Float>()
        val groupH = mutableListOf<Float>()

        for (tok in cleaned) {
            var matched = -1
            for (i in groups.indices) {
                val threshold = maxOf(4.5f, minOf(groupH[i], tok.height.coerceAtLeast(6f)) * 0.65f)
                if (abs(groupY[i] - tok.y) <= threshold) {
                    matched = i
                    break
                }
            }
            if (matched >= 0) {
                groups[matched] += tok
                groupY[matched] = (groupY[matched] + tok.y) / 2f
                groupH[matched] = maxOf(groupH[matched], tok.height.coerceAtLeast(6f))
            } else {
                groups += mutableListOf(tok)
                groupY += tok.y
                groupH += tok.height.coerceAtLeast(6f)
            }
        }

        return groups.map { it.sortedBy { tok -> tok.x } }
    }

    private fun detectHeaderColumns(line: List<PdfTextToken>): List<ColumnSpec>? {
        val mergedLine = mergeSplitHeaderTokens(line)
        val mapped = mergedLine.map { tok -> tok to classifyHeaderRole(tok.text) }
        val hasDate = mapped.any { it.second == BankColRole.DATE }
        val hasAmount = mapped.any {
            it.second == BankColRole.AMOUNT ||
                it.second == BankColRole.DEBIT ||
                it.second == BankColRole.CREDIT
        }
        if (!hasDate || !hasAmount) return null

        val sorted = mapped.sortedBy { it.first.x }
        val specs = mutableListOf<ColumnSpec>()
        for (i in sorted.indices) {
            val (tok, role) = sorted[i]
            val curCenter = tok.x + tok.width / 2f
            val prevMid = if (i == 0) {
                0f
            } else {
                val prev = sorted[i - 1].first
                val prevCenter = prev.x + prev.width / 2f
                (prevCenter + curCenter) / 2f
            }
            val nextMid = if (i == sorted.lastIndex) {
                Float.MAX_VALUE
            } else {
                val next = sorted[i + 1].first
                val nextCenter = next.x + next.width / 2f
                (curCenter + nextCenter) / 2f
            }
            specs += ColumnSpec(role = role, startX = prevMid, endX = nextMid)
        }
        return specs
    }

    private fun mergeSplitHeaderTokens(line: List<PdfTextToken>): List<PdfTextToken> {
        if (line.size < 2) return line
        val sorted = line.sortedBy { it.x }
        val out = mutableListOf<PdfTextToken>()
        var i = 0
        while (i < sorted.size) {
            val cur = sorted[i]
            if (i + 1 < sorted.size) {
                val next = sorted[i + 1]
                val gap = next.x - (cur.x + cur.width)
                val maxGap = maxOf(14f, cur.height.coerceAtLeast(8f) * 1.8f)
                val curRole = classifyHeaderRole(cur.text)
                val nextRole = classifyHeaderRole(next.text)
                val combined = "${cur.text}${next.text}"
                val combinedRole = classifyHeaderRole(combined)
                val shouldMerge = gap <= maxGap && (
                    (curRole == BankColRole.IGNORE && nextRole == BankColRole.IGNORE && combinedRole != BankColRole.IGNORE) ||
                        (combinedRole == BankColRole.AMOUNT && (curRole != BankColRole.AMOUNT || nextRole != BankColRole.AMOUNT))
                    )
                if (shouldMerge) {
                    val rightEdge = maxOf(cur.x + cur.width, next.x + next.width)
                    out += cur.copy(
                        text = combined,
                        width = (rightEdge - cur.x).coerceAtLeast(1f),
                        height = maxOf(cur.height, next.height)
                    )
                    i += 2
                    continue
                }
            }
            out += cur
            i++
        }
        return out
    }

    private fun classifyHeaderRole(raw: String): BankColRole {
        val norm = raw.replace(multiSpaceRegex, "")
        return when {
            norm in setOf("交易日期", "记账日期", "日期", "交易时间", "记账时间") -> BankColRole.DATE
            norm in setOf("时间", "交易时刻") -> BankColRole.TIME
            norm in setOf("币种", "货币", "币别") -> BankColRole.CURRENCY
            norm in setOf("摘要", "交易摘要", "业务摘要", "交易类型", "用途") -> BankColRole.SUMMARY
            norm in setOf("收入/支出金额", "交易金额", "发生额", "金额", "交易额", "收支金额") -> BankColRole.AMOUNT
            norm in setOf("支出", "支出金额", "借方发生额", "借方金额", "转出金额") -> BankColRole.DEBIT
            norm in setOf("收入", "收入金额", "贷方发生额", "贷方金额", "转入金额") -> BankColRole.CREDIT
            norm in setOf("余额", "联机余额", "账户余额", "本次余额", "活期余额") -> BankColRole.BALANCE
            norm in setOf("对方户名", "对手信息", "交易对方", "对方名称", "收/付款人名称", "对方账户名称", "商户名称") ->
                BankColRole.COUNTERPARTY
            norm in setOf("渠道", "交易渠道", "交易场所", "交易地点") -> BankColRole.CHANNEL
            else -> BankColRole.IGNORE
        }
    }

    private fun findColumnForToken(token: PdfTextToken, columns: List<ColumnSpec>): ColumnSpec? {
        val trimmed = token.text.trim()
        val isNumericOrShort = plainAmountTokenRegex.matches(trimmed) ||
            currencyCodeRegex.matches(trimmed) ||
            currencyPrefixRegex.containsMatchIn(trimmed)
        val anchorX = if (isNumericOrShort) {
            token.x + token.width / 2f
        } else {
            token.x + minOf(token.width * 0.25f, 18f)
        }
        return columns.firstOrNull { anchorX >= it.startX && anchorX < it.endX }
            ?: columns.minByOrNull { abs(anchorX - (it.startX + minOf(it.endX, it.startX + 120f)) / 2f) }
    }

    private fun buildRecordFromColumnTokens(
        rowTokens: List<PdfTextToken>,
        columns: List<ColumnSpec>,
        zoneId: ZoneId
    ): RawBankRecord? {
        val byRole = HashMap<BankColRole, MutableList<PdfTextToken>>()
        for (tok in rowTokens) {
            val col = findColumnForToken(tok, columns) ?: continue
            if (col.role != BankColRole.IGNORE) {
                byRole.getOrPut(col.role) { mutableListOf() } += tok
            }
        }

        val dateText = joinTokensOrdered(byRole[BankColRole.DATE].orEmpty(), joinSpace = true)
        val timeText = joinTokensOrdered(byRole[BankColRole.TIME].orEmpty(), joinSpace = true)
        val combinedDateTime = listOf(dateText, timeText).filter { it.isNotBlank() }.joinToString(" ")
        val (timestampMillis, hasTimeOfDay) = parseBankDateTime(combinedDateTime, zoneId) ?: return null

        val signedAmountCents = resolveColumnSignedAmount(byRole) ?: return null
        val summary = cleanCjkJoinedText(joinTokensOrdered(byRole[BankColRole.SUMMARY].orEmpty(), joinSpace = false))
        val counterparty = cleanCjkJoinedText(joinTokensOrdered(byRole[BankColRole.COUNTERPARTY].orEmpty(), joinSpace = false))
        val channelHint = cleanCjkJoinedText(joinTokensOrdered(byRole[BankColRole.CHANNEL].orEmpty(), joinSpace = false))

        return RawBankRecord(
            timestampMillis = timestampMillis,
            hasTimeOfDay = hasTimeOfDay,
            signedAmountCents = signedAmountCents,
            summary = summary,
            counterparty = counterparty,
            channelHint = channelHint
        )
    }

    private fun joinTokensOrdered(tokens: List<PdfTextToken>, joinSpace: Boolean): String {
        if (tokens.isEmpty()) return ""
        val sorted = tokens.sortedWith(compareBy<PdfTextToken> { it.y }.thenBy { it.x })
        val sb = StringBuilder()
        var prev: PdfTextToken? = null
        for (tok in sorted) {
            val text = tok.text.trim()
            if (text.isEmpty()) continue
            if (sb.isNotEmpty() && prev != null) {
                val sameLine = abs(tok.y - prev.y) <= maxOf(4.5f, tok.height * 0.65f)
                if (joinSpace) {
                    sb.append(' ')
                } else if (sameLine && needsSpaceBetween(sb.last(), text.first())) {
                    sb.append(' ')
                }
            }
            sb.append(text)
            prev = tok
        }
        return sb.toString().trim()
    }

    private fun resolveColumnSignedAmount(byRole: Map<BankColRole, List<PdfTextToken>>): Long? {
        val debitText = joinTokensOrdered(byRole[BankColRole.DEBIT].orEmpty(), joinSpace = false)
        val creditText = joinTokensOrdered(byRole[BankColRole.CREDIT].orEmpty(), joinSpace = false)
        val debitCents = parseAbsoluteBankAmount(debitText)
        val creditCents = parseAbsoluteBankAmount(creditText)
        if (debitCents != null && creditCents == null) {
            val trimmedDebit = debitText.trim()
            return if (trimmedDebit.startsWith("+") || trimmedDebit.startsWith("＋")) {
                debitCents
            } else {
                -debitCents
            }
        }
        if (creditCents != null && debitCents == null) {
            val trimmedCredit = creditText.trim()
            return if (trimmedCredit.startsWith("-") || trimmedCredit.startsWith("－")) {
                -creditCents
            } else {
                creditCents
            }
        }

        val amtText = joinTokensOrdered(byRole[BankColRole.AMOUNT].orEmpty(), joinSpace = false)
        return parseSignedBankAmount(amtText)
    }

    private fun parsePageFromRawText(
        rawText: String,
        zoneId: ZoneId,
        debitFirst: Boolean? = null
    ): List<RawBankRecord> {
        val rawLines = rawText.lines()
            .map { it.replace('\u00A0', ' ').trim() }
            .filter { it.isNotEmpty() }

        val resolvedDebitFirst = detectDualColumnDirection(rawText) ?: debitFirst
        val recordBlocks = mutableListOf<MutableList<String>>()
        var acceptingContinuation = false
        for (line in rawLines) {
            if (isNoiseLine(line) || tableHeaderLineRegex.containsMatchIn(line) || isEnglishSubtitleLine(line)) {
                acceptingContinuation = false
                continue
            }
            if (dateRangeLineRegex.containsMatchIn(line)) {
                acceptingContinuation = false
                continue
            }

            val startsWithDate = dateStartRegex.containsMatchIn(line) || compactDateStartRegex.containsMatchIn(line)
            if (startsWithDate) {
                recordBlocks += mutableListOf(line)
                acceptingContinuation = true
            } else if (acceptingContinuation && recordBlocks.isNotEmpty()) {
                recordBlocks.last() += line
            }
        }

        return recordBlocks.mapNotNull { block ->
            parseTextRecordBlock(block, zoneId, resolvedDebitFirst)
        }
    }

    private fun parseTextRecordBlock(
        lines: List<String>,
        zoneId: ZoneId,
        debitFirst: Boolean? = null
    ): RawBankRecord? {
        if (lines.isEmpty()) return null
        val flattened = buildString {
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                if (isNotEmpty()) {
                    val prevChar = last()
                    val nextChar = trimmed.first()
                    // Rejoin CJK words wrapped directly across lines (e.g. "微信零钱提\n现", "内部\n户", "移动支付\n鼓励金专户")
                    if (isCjk(prevChar) && isCjk(nextChar) && !trimmed.startsWith("钞 ") && !trimmed.startsWith("汇 ")) {
                        append(trimmed)
                    } else {
                        append(' ').append(trimmed)
                    }
                } else {
                    append(trimmed)
                }
            }
        }.replace(multiSpaceRegex, " ").trim()

        // 1. ICBC exact row format
        icbcRowRegex.matchEntire(flattened)?.let { m ->
            val dtStr = "${m.groupValues[1]} ${m.groupValues[2]}"
            val (ts, hasTime) = parseBankDateTime(dtStr, zoneId) ?: return null
            val summary = cleanCjkJoinedText(m.groupValues[3])
            val signedCents = parseSignedBankAmount(m.groupValues[5]) ?: return null
            val counterparty = cleanCjkJoinedText(m.groupValues[7])
            val channelHint = m.groupValues[9].trim()
            return RawBankRecord(
                timestampMillis = ts,
                hasTimeOfDay = hasTime,
                signedAmountCents = signedCents,
                summary = summary,
                counterparty = counterparty,
                channelHint = channelHint
            )
        }

        // 2. CMB exact row format
        cmbRowRegex.matchEntire(flattened)?.let { m ->
            val (ts, hasTime) = parseBankDateTime(m.groupValues[1], zoneId) ?: return null
            val signedCents = parseSignedBankAmount(m.groupValues[2]) ?: return null
            val summary = cleanCjkJoinedText(m.groupValues[4])
            val counterparty = cleanCjkJoinedText(m.groupValues[5])
            return RawBankRecord(
                timestampMillis = ts,
                hasTimeOfDay = hasTime,
                signedAmountCents = signedCents,
                summary = summary,
                counterparty = counterparty
            )
        }

        // 3. Generic Chinese bank statement row fallback
        return parseGenericBankRow(flattened, zoneId, debitFirst)
    }

    private fun parseGenericBankRow(
        flattened: String,
        zoneId: ZoneId,
        debitFirst: Boolean? = null
    ): RawBankRecord? {
        val dateMatch = dateStartRegex.find(flattened) ?: compactDateStartRegex.find(flattened) ?: return null
        val (ts, hasTime) = parseBankDateTime(dateMatch.value.trim(), zoneId) ?: return null

        val rest = flattened.substring(dateMatch.range.last + 1).trim()
        if (rest.isEmpty()) return null

        val tokens = rest.split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null

        var startIdx = 0
        var resolvedTs = ts
        var resolvedHasTime = hasTime
        if (!resolvedHasTime && timeOnlyTokenRegex.matches(tokens[0])) {
            val combined = "${dateMatch.value.trim()} ${tokens[0]}"
            parseBankDateTime(combined, zoneId)?.let { (t, h) ->
                resolvedTs = t
                resolvedHasTime = h
                startIdx = 1
            }
        }

        val body = tokens.drop(startIdx)
        val signedIdx = body.indexOfFirst { signedAmountTokenRegex.matches(it) }
        val amountIdx = if (signedIdx >= 0) {
            signedIdx
        } else {
            body.indexOfFirst { plainAmountTokenRegex.matches(it) }
        }
        if (amountIdx < 0) return null

        var signedCents: Long?
        var afterAmountStart: Int

        if (signedIdx < 0 && debitFirst != null &&
            amountIdx + 1 < body.size && plainAmountTokenRegex.matches(body[amountIdx + 1])
        ) {
            val firstVal = parseSignedBankAmountOrZero(body[amountIdx])
            val secondVal = parseSignedBankAmountOrZero(body[amountIdx + 1])
            val hasThirdBalance = amountIdx + 2 < body.size && plainAmountTokenRegex.matches(body[amountIdx + 2])
            if (firstVal != null && secondVal != null && ((firstVal == 0L) xor (secondVal == 0L))) {
                signedCents = if (firstVal != 0L) {
                    if (debitFirst) -abs(firstVal) else abs(firstVal)
                } else {
                    if (debitFirst) abs(secondVal) else -abs(secondVal)
                }
                afterAmountStart = if (hasThirdBalance) amountIdx + 3 else amountIdx + 2
            } else {
                // When a dual-column statement leaves the unused debit/credit cell blank instead of 0.00,
                // secondVal is the balance; infer sign from row cues instead of assuming positive INCOME.
                val rawAmt = firstVal?.takeIf { it != 0L }
                val rowTextHint = body.joinToString(" ")
                signedCents = rawAmt?.let {
                    if (unsignedCreditCueRegex.containsMatchIn(rowTextHint)) abs(it) else -abs(it)
                }
                afterAmountStart = amountIdx + 2
            }
        } else {
            signedCents = parseSignedBankAmount(body[amountIdx])
            val hasTrailingBalance = amountIdx + 1 < body.size && plainAmountTokenRegex.matches(body[amountIdx + 1])
            afterAmountStart = if (hasTrailingBalance) amountIdx + 2 else amountIdx + 1
        }
        val finalSignedCents = signedCents ?: return null

        val beforeTokens = body.take(amountIdx).filterNot { tok ->
            currencyCodeRegex.matches(tok) || tok in setOf("钞", "汇", "活期", "定期") || fourOrMoreDigitsRegex.matches(tok)
        }
        val afterTokens = body.drop(afterAmountStart).filterNot { tok ->
            maskedAccountTokenRegex.matches(tok)
        }

        val summary = cleanCjkJoinedText(beforeTokens.joinToString(" "))
        val counterparty = cleanCjkJoinedText(afterTokens.joinToString(" "))

        return RawBankRecord(
            timestampMillis = resolvedTs,
            hasTimeOfDay = resolvedHasTime,
            signedAmountCents = finalSignedCents,
            summary = summary,
            counterparty = counterparty
        )
    }

    private fun toParsedPayment(record: RawBankRecord, meta: BankHeaderMeta): ParsedPayment? {
        val absCents = abs(record.signedAmountCents)
        if (absCents <= 0L) return null

        val summary = record.summary.trim()
        val rawCounterparty = record.counterparty.trim()
        val counterparty = if (rawCounterparty in placeholderCounterparties) "" else rawCounterparty

        val txType = classifyBankTransaction(
            summary = summary,
            counterparty = counterparty,
            isOutflow = record.signedAmountCents < 0L,
            accountHolder = meta.accountHolder
        )

        val merchant = when {
            counterparty.isNotEmpty() -> counterparty
            summary.isNotEmpty() -> summary
            else -> meta.bankName.ifEmpty { "银行流水" }
        }

        val noteParts = mutableListOf<String>()
        if (summary.isNotEmpty() && summary != merchant) {
            noteParts += summary
        }
        if (record.channelHint.isNotEmpty() &&
            record.channelHint != "其他" &&
            record.channelHint != summary &&
            record.channelHint != merchant
        ) {
            noteParts += record.channelHint
        }
        if (txType == TransactionType.TRANSFER &&
            noteParts.none { it.contains("资金方向") }
        ) {
            noteParts += if (record.signedAmountCents < 0L) {
                "资金方向：转出"
            } else {
                "资金方向：转入"
            }
        }

        return ParsedPayment(
            channel = PaymentChannel.BANK,
            fundingMethod = meta.fundingMethod,
            type = txType,
            amountCents = absCents,
            merchant = merchant,
            timestampMillis = record.timestampMillis,
            source = CaptureSource.IMPORT,
            externalOrderId = null,
            note = noteParts.joinToString(" · "),
            hasTimeOfDay = record.hasTimeOfDay
        )
    }

    private fun classifyBankTransaction(
        summary: String,
        counterparty: String,
        isOutflow: Boolean,
        accountHolder: String
    ): TransactionType {
        val isOwnAccount = accountHolder.isNotEmpty() &&
            counterparty.isNotEmpty() &&
            counterparty.equals(accountHolder, ignoreCase = true)

        // 1. Refunds (inflow carrying refund/reversal summary)
        if (!isOutflow && refundSummaryRegex.containsMatchIn(summary)) {
            return TransactionType.REFUND
        }

        // 2. Own-account / wealth / credit-repayment / e-CNY-exchange transfers
        if (strongTransferSummaryRegex.containsMatchIn(summary)) {
            return TransactionType.TRANSFER
        }
        if (isOwnAccount && ownAccountTransferSummaryRegex.containsMatchIn(summary)) {
            return TransactionType.TRANSFER
        }
        if (!isOutflow && walletNetInboundSummaryRegex.containsMatchIn(summary) &&
            (isOwnAccount || walletSettlementCounterpartyRegex.containsMatchIn(counterparty))
        ) {
            return TransactionType.TRANSFER
        }
        if (summary == "充值" && (isOwnAccount || walletSettlementCounterpartyRegex.containsMatchIn(counterparty))) {
            return TransactionType.TRANSFER
        }

        // 3. Standard outflow -> EXPENSE; standard inflow -> INCOME
        return if (isOutflow) TransactionType.EXPENSE else TransactionType.INCOME
    }

    private fun parseBankDateTime(raw: String, zoneId: ZoneId): Pair<Long, Boolean>? {
        val trimmed = raw.trim()
        dateStartRegex.find(trimmed)?.let { m ->
            val y = m.groupValues[1].toIntOrNull() ?: return null
            val mo = m.groupValues[2].toIntOrNull() ?: return null
            val d = m.groupValues[3].toIntOrNull() ?: return null
            val date = runCatching { LocalDate.of(y, mo, d) }.getOrNull() ?: return null
            val h = m.groupValues[4].toIntOrNull()
            val min = m.groupValues[5].toIntOrNull()
            val sec = m.groupValues[6].toIntOrNull() ?: 0
            return if (h != null && min != null) {
                val time = runCatching { LocalTime.of(h, min, sec) }.getOrNull() ?: return null
                LocalDateTime.of(date, time).atZone(zoneId).toInstant().toEpochMilli() to true
            } else {
                date.atTime(12, 0, 0).atZone(zoneId).toInstant().toEpochMilli() to false
            }
        }
        compactDateStartRegex.find(trimmed)?.let { m ->
            val y = m.groupValues[1].toIntOrNull() ?: return null
            val mo = m.groupValues[2].toIntOrNull() ?: return null
            val d = m.groupValues[3].toIntOrNull() ?: return null
            val date = runCatching { LocalDate.of(y, mo, d) }.getOrNull() ?: return null
            val h = m.groupValues[4].toIntOrNull()
            val min = m.groupValues[5].toIntOrNull()
            val sec = m.groupValues[6].toIntOrNull() ?: 0
            return if (h != null && min != null) {
                val time = runCatching { LocalTime.of(h, min, sec) }.getOrNull() ?: return null
                LocalDateTime.of(date, time).atZone(zoneId).toInstant().toEpochMilli() to true
            } else {
                date.atTime(12, 0, 0).atZone(zoneId).toInstant().toEpochMilli() to false
            }
        }
        return null
    }

    private fun parseSignedBankAmount(raw: String): Long? {
        val cleaned = raw.trim()
            .replace('－', '-')
            .replace('＋', '+')
            .replace(currencyPrefixRegex, "")
            .trim()
        if (cleaned.isEmpty()) return null
        val isNegative = cleaned.startsWith("-")
        val normalized = cleaned.removePrefix("-").removePrefix("+").replace(currencyPrefixRegex, "").trim()
        val cents = AmountParser.parseToCents(normalized) ?: return null
        return if (isNegative) -cents else cents
    }

    private fun parseSignedBankAmountOrZero(raw: String): Long? {
        val cleaned = raw.trim()
            .replace('－', '-')
            .replace('＋', '+')
            .replace(currencyPrefixRegex, "")
            .trim()
        if (cleaned.isEmpty()) return null
        val normalized = cleaned.removePrefix("-").removePrefix("+").replace(currencyPrefixRegex, "").trim()
        if (normalized == "0" || normalized == "0.0" || normalized == "0.00") return 0L
        return parseSignedBankAmount(raw)
    }

    private fun parseAbsoluteBankAmount(raw: String): Long? {
        if (raw.isBlank()) return null
        return AmountParser.parseToCents(raw)
    }

    private fun isPageFooterCutoff(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.length >= 10 && trimmed.all { it == '—' || it == '-' || it == '_' || it == '=' }) {
            return true
        }
        return pageFooterCutoffRegex.containsMatchIn(trimmed)
    }

    private fun isNoiseLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed.all { it == '—' || it == '-' || it == '_' || it == '=' }) return true
        return watermarkOrFooterRegex.containsMatchIn(trimmed)
    }

    private fun isEnglishSubtitleLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed in englishSubtitleTokens) return true
        val words = trimmed.split(multiSpaceRegex)
        return words.isNotEmpty() && words.all { it in englishSubtitleTokens }
    }

    private fun cleanCjkJoinedText(raw: String): String {
        var result = raw.replace('\u00A0', ' ').replace(multiSpaceRegex, " ").trim()
        // Collapse stray spaces inserted by PDF wrapping between Chinese characters
        while (cjkSpaceBridgeRegex.containsMatchIn(result)) {
            result = cjkSpaceBridgeRegex.replace(result, "$1$2")
        }
        return result
    }

    private fun isCjk(ch: Char): Boolean = ch in '\u4E00'..'\u9FFF'

    private fun needsSpaceBetween(a: Char, b: Char): Boolean =
        (a.isLetterOrDigit() && a.code < 128) && (b.isLetterOrDigit() && b.code < 128)
}
