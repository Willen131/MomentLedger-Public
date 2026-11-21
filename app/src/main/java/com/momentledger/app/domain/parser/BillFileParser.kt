package com.momentledger.app.domain.parser

import com.momentledger.app.domain.model.CaptureSource
import com.momentledger.app.domain.model.ParsedPayment
import com.momentledger.app.domain.model.PaymentChannel
import com.momentledger.app.domain.model.TransactionType
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParser
import javax.xml.parsers.SAXParserFactory
import kotlin.math.roundToLong

/**
 * Result of parsing an official WeChat or Alipay bill export file.
 */
data class BillImportParseResult(
    val items: List<ParsedPayment>,
    val skippedCount: Int,
    val detectedFormat: String
)

/**
 * Pure-JVM offline parser for Alipay CSV (UTF-8 BOM / GB18030) and WeChat XLSX / CSV exports.
 * Uses JDK [ZipInputStream] + [SAXParserFactory] with XXE disabled instead of Apache POI.
 */
object BillFileParser {

    internal const val MAX_BILL_BYTES = 20 * 1024 * 1024

    private val gb18030Charset: Charset = Charset.forName("GB18030")

    private val billSignatureKeywords = listOf(
        "交易时间", "下单时间", "付款时间", "记账日期", "交易日期",
        "交易类型", "交易分类", "交易对方", "店铺名称", "商户名称", "对方户名",
        "收/支", "金额", "实付款", "实付金额", "支出金额", "收入金额", "发生额",
        "交易状态", "当前状态", "订单状态", "交易单号", "交易订单号", "订单编号", "流水号"
    )

    private val skippedStatusKeywords = listOf(
        "交易关闭", "已关闭", "退款关闭", "支付失败", "扣款失败", "交易失败", "转账失败",
        "提现失败", "还款失败", "充值失败", "退款失败", "已撤销", "已取消",
        "未支付", "待支付", "待付款", "等待付款", "订单失效", "已作废"
    )

    private val neutralDirections = setOf("不计收支", "中性交易")

    private val nonTransferRechargeRegex = Regex(
        """(?:话费|流量|电费|水费|燃气|油卡|游戏|会员|视频|音乐|点卡)充值"""
    )

    private val billTransferRegex = Regex(
        """零钱提现|余额提现|提现到银行卡|零钱充值|余额充值|充值到零钱|""" +
            """(?:余额宝|零钱通|余利宝)(?:转入|转出)|转入(?:余额宝|零钱通|余利宝)|转出(?:余额宝|零钱通|余利宝)|""" +
            """信用卡还款|还信用卡|花呗还款|花呗自动还款|白条还款|本人账户互转|同名账户转账|卡内互转"""
    )

    private val timestampFormatters = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy-M-d H:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-M-d H:mm"),
        DateTimeFormatter.ofPattern("yyyy/M/d H:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy/M/d H:mm"),
        DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm"),
        DateTimeFormatter.ISO_LOCAL_DATE_TIME
    )

    private val dateOnlyFormatters = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd"),
        DateTimeFormatter.ofPattern("yyyy-M-d"),
        DateTimeFormatter.ofPattern("yyyy/M/d"),
        DateTimeFormatter.ofPattern("yyyyMMdd"),
        DateTimeFormatter.ofPattern("yyyy年M月d日")
    )

    private val excelSerialRegex = Regex("""^\d{5}(?:\.\d+)?$""")

    private data class ParsedTimestamp(
        val timestampMillis: Long,
        val hasTimeOfDay: Boolean
    )

    private data class ColumnMap(
        val timeIdx: Int,
        val amountIdx: Int,
        val debitAmountIdx: Int,
        val creditAmountIdx: Int,
        val typeIdx: Int,
        val merchantIdx: Int,
        val productIdx: Int,
        val directionIdx: Int,
        val fundingIdx: Int,
        val statusIdx: Int,
        val orderIdx: Int,
        val merchantOrderIdx: Int,
        val noteIdx: Int
    )

    fun parse(
        fileName: String,
        inputStream: InputStream,
        zoneId: ZoneId = ZoneId.of("Asia/Shanghai")
    ): BillImportParseResult {
        val bytes = readBytesBounded(inputStream)
        if (bytes.isEmpty()) {
            return BillImportParseResult(emptyList(), 0, "UNKNOWN")
        }

        val isZipHeader = bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
        if (isZipHeader || fileName.endsWith(".xlsx", ignoreCase = true)) {
            if (!isZipHeader) {
                return BillImportParseResult(emptyList(), 0, "WECHAT_XLSX")
            }
            val rows = parseXlsxRows(bytes)
            return parseTabularRows(rows, isXlsx = true, fileName = fileName, zoneId = zoneId)
        }

        val decoded = decodeCsvText(bytes)
        val rows = parseRfc4180Csv(decoded)
        return parseTabularRows(rows, isXlsx = false, fileName = fileName, zoneId = zoneId)
    }

    private fun decodeCsvText(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }

        val utf8Candidate = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }

        if (utf8Candidate != null) {
            if (containsBillKeywords(utf8Candidate)) {
                return utf8Candidate.removePrefix("\uFEFF")
            }
            val gbCandidate = String(bytes, gb18030Charset)
            if (containsBillKeywords(gbCandidate)) {
                return gbCandidate
            }
            return utf8Candidate.removePrefix("\uFEFF")
        }

        return String(bytes, gb18030Charset)
    }

    private fun containsBillKeywords(text: String): Boolean =
        billSignatureKeywords.any { text.contains(it) }

    private fun parseRfc4180Csv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var currentRow = mutableListOf<String>()
        val cellBuf = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < text.length) {
            val ch = text[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        cellBuf.append('"')
                        i += 2
                        continue
                    } else {
                        inQuotes = false
                    }
                } else {
                    cellBuf.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> {
                        if (cellBuf.isEmpty()) {
                            inQuotes = true
                        } else {
                            cellBuf.append(ch)
                        }
                    }
                    ',' -> {
                        currentRow += cellBuf.toString().trim()
                        cellBuf.setLength(0)
                    }
                    '\r', '\n' -> {
                        if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') {
                            i++
                        }
                        currentRow += cellBuf.toString().trim()
                        cellBuf.setLength(0)
                        if (currentRow.any { it.isNotEmpty() }) {
                            rows += currentRow
                        }
                        currentRow = mutableListOf()
                    }
                    else -> cellBuf.append(ch)
                }
            }
            i++
        }

        if (cellBuf.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow += cellBuf.toString().trim()
            if (currentRow.any { it.isNotEmpty() }) {
                rows += currentRow
            }
        }
        return rows
    }

    private fun parseXlsxRows(bytes: ByteArray): List<List<String>> {
        var sharedStringsBytes: ByteArray? = null
        var preferredSheetBytes: ByteArray? = null
        var fallbackSheetName: String? = null
        var fallbackSheetBytes: ByteArray? = null

        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name.replace('\\', '/')
                    when {
                        name.equals("xl/sharedStrings.xml", ignoreCase = true) -> {
                            sharedStringsBytes = readBytesBounded(zis)
                        }
                        name.equals("xl/worksheets/sheet1.xml", ignoreCase = true) -> {
                            preferredSheetBytes = readBytesBounded(zis)
                        }
                        name.startsWith("xl/worksheets/", ignoreCase = true) &&
                            name.endsWith(".xml", ignoreCase = true) -> {
                            if (fallbackSheetName == null || name < fallbackSheetName!!) {
                                fallbackSheetName = name
                                fallbackSheetBytes = readBytesBounded(zis)
                            }
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val sheetBytes = preferredSheetBytes ?: fallbackSheetBytes ?: return emptyList()
        val factory = createSecureSaxParserFactory()
        val sharedStrings = sharedStringsBytes?.let { parseSharedStringsXml(factory.newSAXParser(), it) }.orEmpty()
        return parseWorksheetXml(factory.newSAXParser(), sheetBytes, sharedStrings)
    }

    internal fun readBytesBounded(
        stream: InputStream,
        maxBytes: Int = MAX_BILL_BYTES
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > maxBytes) {
                throw IllegalArgumentException("账单文件过大（上限 20MB）")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun createSecureSaxParserFactory(): SAXParserFactory {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        trySetFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true)
        trySetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
        trySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false)
        trySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false)
        trySetFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        return factory
    }

    private fun trySetFeature(factory: SAXParserFactory, feature: String, enabled: Boolean) {
        try {
            factory.setFeature(feature, enabled)
        } catch (_: Exception) {
            // Some Android XML parsers ignore specific Xerces-style feature URIs.
        }
    }

    private fun parseSharedStringsXml(saxParser: SAXParser, xmlBytes: ByteArray): List<String> {
        val strings = mutableListOf<String>()
        val handler = object : DefaultHandler() {
            private var inSi = false
            private var inRPh = false
            private var inT = false
            private val siBuf = StringBuilder()
            private val tBuf = StringBuilder()

            override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
                InputSource(StringReader(""))

            override fun startElement(
                uri: String?,
                localName: String?,
                qName: String?,
                attributes: Attributes?
            ) {
                when (elementTagName(localName, qName)) {
                    "si" -> {
                        inSi = true
                        siBuf.setLength(0)
                    }
                    "rPh" -> if (inSi) inRPh = true
                    "t" -> if (inSi && !inRPh) {
                        inT = true
                        tBuf.setLength(0)
                    }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inT) {
                    tBuf.append(ch, start, length)
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (elementTagName(localName, qName)) {
                    "t" -> if (inT) {
                        siBuf.append(tBuf)
                        inT = false
                    }
                    "rPh" -> inRPh = false
                    "si" -> if (inSi) {
                        strings += siBuf.toString()
                        inSi = false
                    }
                }
            }
        }
        saxParser.parse(ByteArrayInputStream(xmlBytes), handler)
        return strings
    }

    private fun parseWorksheetXml(
        saxParser: SAXParser,
        xmlBytes: ByteArray,
        sharedStrings: List<String>
    ): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val handler = object : DefaultHandler() {
            private var inRow = false
            private var inCell = false
            private var inValue = false
            private var inInlineStr = false
            private var inInlineT = false
            private var inRPh = false
            private var cellType = ""
            private var cellColIdx = 0
            private var nextColIdx = 0
            private var currentRow = mutableListOf<String>()
            private val valueBuf = StringBuilder()

            override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
                InputSource(StringReader(""))

            override fun startElement(
                uri: String?,
                localName: String?,
                qName: String?,
                attributes: Attributes?
            ) {
                when (elementTagName(localName, qName)) {
                    "row" -> {
                        inRow = true
                        currentRow = mutableListOf()
                        nextColIdx = 0
                    }
                    "c" -> if (inRow) {
                        inCell = true
                        cellType = attributes?.getValue("t")?.trim().orEmpty()
                        val ref = attributes?.getValue("r")
                        cellColIdx = if (!ref.isNullOrBlank()) {
                            parseColumnIndex(ref)
                        } else {
                            nextColIdx
                        }
                        nextColIdx = cellColIdx + 1
                        valueBuf.setLength(0)
                    }
                    "v" -> if (inCell) {
                        inValue = true
                        valueBuf.setLength(0)
                    }
                    "is" -> if (inCell) {
                        inInlineStr = true
                        valueBuf.setLength(0)
                    }
                    "rPh" -> if (inInlineStr) {
                        inRPh = true
                    }
                    "t" -> if (inInlineStr && !inRPh) {
                        inInlineT = true
                    }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inValue || inInlineT) {
                    valueBuf.append(ch, start, length)
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (elementTagName(localName, qName)) {
                    "t" -> inInlineT = false
                    "rPh" -> inRPh = false
                    "is" -> inInlineStr = false
                    "v" -> inValue = false
                    "c" -> if (inCell) {
                        val raw = valueBuf.toString()
                        val resolved = when (cellType) {
                            "s" -> raw.trim().toIntOrNull()?.let { sharedStrings.getOrNull(it) }.orEmpty()
                            "e" -> ""
                            else -> raw
                        }.trim()
                        while (currentRow.size < cellColIdx) {
                            currentRow += ""
                        }
                        if (cellColIdx < currentRow.size) {
                            currentRow[cellColIdx] = resolved
                        } else {
                            currentRow += resolved
                        }
                        inCell = false
                    }
                    "row" -> if (inRow) {
                        if (currentRow.any { it.isNotEmpty() }) {
                            rows += currentRow
                        }
                        inRow = false
                    }
                }
            }
        }
        saxParser.parse(ByteArrayInputStream(xmlBytes), handler)
        return rows
    }

    private fun elementTagName(localName: String?, qName: String?): String =
        localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty().substringAfter(':')

    private fun parseColumnIndex(cellRef: String): Int {
        var index = 0
        for (ch in cellRef) {
            if (ch in 'A'..'Z') {
                index = index * 26 + (ch - 'A' + 1)
            } else if (ch in 'a'..'z') {
                index = index * 26 + (ch - 'a' + 1)
            } else {
                break
            }
        }
        return (index - 1).coerceAtLeast(0)
    }

    private fun parseTabularRows(
        rows: List<List<String>>,
        isXlsx: Boolean,
        fileName: String,
        zoneId: ZoneId
    ): BillImportParseResult {
        if (rows.isEmpty()) {
            return BillImportParseResult(
                items = emptyList(),
                skippedCount = 0,
                detectedFormat = if (isXlsx) "WECHAT_XLSX" else "UNKNOWN"
            )
        }

        val headerIndex = findHeaderRowIndex(rows)
        if (headerIndex < 0) {
            return BillImportParseResult(
                items = emptyList(),
                skippedCount = 0,
                detectedFormat = if (isXlsx) "WECHAT_XLSX" else "UNKNOWN"
            )
        }

        val preambleText = rows.take(headerIndex).flatten().joinToString(" ")
        val headerRow = rows[headerIndex].map(::normalizeHeader)
        val channel = detectChannel(preambleText, headerRow, fileName, isXlsx)
        val format = detectFormat(preambleText, headerRow, channel, isXlsx)

        val cols = buildColumnMap(headerRow)
        val hasAnyAmountCol = cols.amountIdx >= 0 || cols.debitAmountIdx >= 0 || cols.creditAmountIdx >= 0
        if (cols.timeIdx < 0 || !hasAnyAmountCol) {
            return BillImportParseResult(emptyList(), 0, format)
        }

        val items = mutableListOf<ParsedPayment>()
        var skippedCount = 0

        // Pre-scan successful refund rows so paid-then-fully-refunded "交易关闭" original expense rows
        // (e.g. Alipay CSV where an order's status becomes "交易关闭" paired with a "<orderId>_xxx"
        // "退款成功" row) are retained to cancel out the refund row rather than producing negative spend.
        val refundedOrderIds = ArrayList<String>()
        val refundedBaseOrderIds = HashSet<String>()
        for (rowIdx in (headerIndex + 1) until rows.size) {
            val row = rows[rowIdx]
            if (row.all { it.isBlank() }) continue
            val rawTime = cellAt(row, cols.timeIdx)
            if (isPostHeaderDivider(rawTime)) break
            if (isFooterOrDividerRow(rawTime, row)) continue
            val rowStatus = cleanCell(cellAt(row, cols.statusIdx))
            val rowCat = cleanCell(cellAt(row, cols.typeIdx))
            if (rowStatus.contains("退款成功") || (rowCat == "退款" && !isSkippedStatus(rowStatus))) {
                val oid = cleanCell(cellAt(row, cols.orderIdx))
                    .ifEmpty { cleanCell(cellAt(row, cols.merchantOrderIdx)) }
                if (oid.isNotEmpty()) {
                    refundedOrderIds += oid
                    refundedBaseOrderIds += oid.substringBefore('_')
                }
            }
        }

        for (rowIdx in (headerIndex + 1) until rows.size) {
            val row = rows[rowIdx]
            if (row.all { it.isBlank() }) continue

            val rawTime = cellAt(row, cols.timeIdx)
            if (isPostHeaderDivider(rawTime)) break
            if (isFooterOrDividerRow(rawTime, row)) continue

            val status = cleanCell(cellAt(row, cols.statusIdx))
            val rawDirection = cleanCell(cellAt(row, cols.directionIdx))
            val rawFundingMethod = cleanCell(cellAt(row, cols.fundingIdx))
            val rawRemark = cleanCell(cellAt(row, cols.noteIdx))
            val externalOrderId = cleanCell(cellAt(row, cols.orderIdx))
                .ifEmpty { cleanCell(cellAt(row, cols.merchantOrderIdx)) }
                .ifEmpty { null }
            val isPaidThenRefundedClosedOrder = (status.contains("交易关闭") || status.contains("已关闭")) &&
                externalOrderId != null &&
                (externalOrderId in refundedBaseOrderIds ||
                    refundedOrderIds.any { it.startsWith("${externalOrderId}_") })
            val isRetainedClosedExpense = isRetainedAlipayClosedExpenseOrder(
                format = format,
                status = status,
                direction = rawDirection,
                fundingMethod = rawFundingMethod,
                remark = rawRemark,
                txCategory = cleanCell(cellAt(row, cols.typeIdx))
            )
            if (isSkippedStatus(status) && !isPaidThenRefundedClosedOrder && !isRetainedClosedExpense) {
                skippedCount++
                continue
            }

            val parsedTime = parseTimestamp(rawTime, zoneId)
            val resolvedAmount = resolveRowAmount(row, cols)
            if (parsedTime == null || resolvedAmount == null) {
                skippedCount++
                continue
            }

            val (amountCents, inferredTypeFromAmount) = resolvedAmount
            val txCategory = cleanCell(cellAt(row, cols.typeIdx))
            val rawMerchant = cleanCell(cellAt(row, cols.merchantIdx))
            val product = cleanCell(cellAt(row, cols.productIdx))
            val direction = rawDirection
            val fundingMethod = rawFundingMethod
            val remark = rawRemark

            val txType = resolveTransactionType(
                direction = direction,
                status = status,
                txCategory = txCategory,
                merchant = rawMerchant,
                product = product,
                inferredTypeFromAmount = inferredTypeFromAmount,
                isGenericFormat = format.startsWith("GENERIC_"),
                isPaidThenRefundedClosedOrder = isPaidThenRefundedClosedOrder
            )
            if (txType == null) {
                skippedCount++
                continue
            }

            val merchant = resolveMerchant(
                rawMerchant = rawMerchant,
                product = product,
                txCategory = txCategory,
                txType = txType,
                channel = channel
            )
            val note = buildNote(
                merchant = merchant,
                product = product,
                remark = remark,
                txCategory = txCategory,
                txType = txType
            )

            items += ParsedPayment(
                channel = channel,
                fundingMethod = fundingMethod,
                type = txType,
                amountCents = amountCents,
                merchant = merchant,
                timestampMillis = parsedTime.timestampMillis,
                source = CaptureSource.IMPORT,
                externalOrderId = externalOrderId,
                note = note,
                hasTimeOfDay = parsedTime.hasTimeOfDay
            )
        }

        return BillImportParseResult(
            items = items,
            skippedCount = skippedCount,
            detectedFormat = format
        )
    }

    private fun resolveRowAmount(row: List<String>, cols: ColumnMap): Pair<Long, TransactionType?>? {
        if (cols.debitAmountIdx >= 0 || cols.creditAmountIdx >= 0) {
            val rawDebit = cellAt(row, cols.debitAmountIdx).trim()
            val rawCredit = cellAt(row, cols.creditAmountIdx).trim()
            val debitCents = AmountParser.parseToCents(rawDebit)
            val creditCents = AmountParser.parseToCents(rawCredit)
            if (debitCents != null && creditCents == null) {
                return debitCents to TransactionType.EXPENSE
            }
            if (creditCents != null && debitCents == null) {
                return creditCents to TransactionType.INCOME
            }
            if (debitCents != null && creditCents != null) {
                return if (debitCents >= creditCents) {
                    debitCents to TransactionType.EXPENSE
                } else {
                    creditCents to TransactionType.INCOME
                }
            }
        }
        val rawAmount = cellAt(row, cols.amountIdx).trim()
        val cents = AmountParser.parseToCents(rawAmount) ?: return null
        val signStripped = rawAmount
            .replace('－', '-')
            .replace('＋', '+')
            .replace(Regex("""^[¥￥$＄\s]+|(?i)^(RMB|CNY)\s*"""), "")
            .trim()
        val signHint = when {
            signStripped.startsWith("-") -> TransactionType.EXPENSE
            signStripped.startsWith("+") -> TransactionType.INCOME
            else -> null
        }
        return cents to signHint
    }

    private fun findHeaderRowIndex(rows: List<List<String>>): Int {
        for (i in rows.indices) {
            val normalized = rows[i].map(::normalizeHeader)
            val hasTime = normalized.any { it in timeHeaders }
            val hasAmount = normalized.any {
                it in preferredActualAmountHeaders ||
                    it in amountHeaders ||
                    it in debitAmountHeaders ||
                    it in creditAmountHeaders
            }
            val hasSecondary = normalized.any {
                it in directionHeaders ||
                    it in statusHeaders ||
                    it in merchantHeaders ||
                    it in productHeaders ||
                    it in typeHeaders
            }
            if (hasTime && hasAmount && hasSecondary) {
                return i
            }
        }
        return -1
    }

    private fun detectFormat(
        preambleText: String,
        headerRow: List<String>,
        channel: PaymentChannel,
        isXlsx: Boolean
    ): String {
        val headerSet = headerRow.toSet()
        val isStrongAlipayHeader = "交易订单号" in headerSet ||
            "交易号" in headerSet ||
            "收/付款方式" in headerSet ||
            ("交易分类" in headerSet && "交易状态" in headerSet)
        val isStrongWechatHeader = ("交易单号" in headerSet && ("当前状态" in headerSet || "商户单号" in headerSet || "支付方式" in headerSet)) ||
            ("商户单号" in headerSet && "当前状态" in headerSet)
        return when {
            channel == PaymentChannel.BANK || channel == PaymentChannel.UNIONPAY ->
                if (isXlsx) "GENERIC_XLSX" else "GENERIC_CSV"
            preambleText.contains("微信支付") || preambleText.contains("微信昵称") ->
                if (isXlsx) "WECHAT_XLSX" else "WECHAT_CSV"
            preambleText.contains("支付宝") ->
                if (isXlsx) "ALIPAY_XLSX" else "ALIPAY_CSV"
            isStrongWechatHeader && !isStrongAlipayHeader -> if (isXlsx) "WECHAT_XLSX" else "WECHAT_CSV"
            isStrongAlipayHeader && !isStrongWechatHeader -> if (isXlsx) "ALIPAY_XLSX" else "ALIPAY_CSV"
            channel == PaymentChannel.WECHAT -> if (isXlsx) "WECHAT_XLSX" else "WECHAT_CSV"
            channel == PaymentChannel.ALIPAY -> if (isXlsx) "ALIPAY_XLSX" else "ALIPAY_CSV"
            isXlsx -> "GENERIC_XLSX"
            else -> "GENERIC_CSV"
        }
    }

    private fun detectChannel(
        preambleText: String,
        headerRow: List<String>,
        fileName: String,
        isXlsx: Boolean
    ): PaymentChannel {
        val headerSet = headerRow.toSet()
        val isStrongAlipayHeader = "交易订单号" in headerSet ||
            "交易号" in headerSet ||
            "收/付款方式" in headerSet ||
            ("交易分类" in headerSet && "交易状态" in headerSet)
        val isStrongWechatHeader = ("交易单号" in headerSet && ("当前状态" in headerSet || "商户单号" in headerSet || "支付方式" in headerSet)) ||
            ("商户单号" in headerSet && "当前状态" in headerSet)
        val combinedHints = "$preambleText $fileName"

        return when {
            preambleText.contains("微信支付") || preambleText.contains("微信昵称") -> PaymentChannel.WECHAT
            preambleText.contains("支付宝") -> PaymentChannel.ALIPAY
            fileName.contains("wechat", ignoreCase = true) || fileName.contains("微信") -> PaymentChannel.WECHAT
            fileName.contains("alipay", ignoreCase = true) || fileName.contains("支付宝") -> PaymentChannel.ALIPAY
            combinedHints.contains("云闪付") || combinedHints.contains("银联") ||
                fileName.contains("unionpay", ignoreCase = true) -> PaymentChannel.UNIONPAY
            combinedHints.contains("银行") ||
                PaymentPackages.defaultBankPackages.values.any { combinedHints.contains(it) } ||
                bankFilePrefixRegex.containsMatchIn(fileName) -> PaymentChannel.BANK
            combinedHints.contains("京东") || combinedHints.contains("淘宝") ||
                combinedHints.contains("天猫") || combinedHints.contains("拼多多") ||
                combinedHints.contains("美团") || combinedHints.contains("饿了么") ||
                combinedHints.contains("抖音") || fileName.contains("jd", ignoreCase = true) ||
                fileName.contains("taobao", ignoreCase = true) -> PaymentChannel.OTHER
            isStrongWechatHeader && !isStrongAlipayHeader -> PaymentChannel.WECHAT
            isStrongAlipayHeader && !isStrongWechatHeader -> PaymentChannel.ALIPAY
            isXlsx -> PaymentChannel.OTHER
            else -> PaymentChannel.OTHER
        }
    }

    private fun buildColumnMap(headers: List<String>): ColumnMap {
        val preferredAmountIdx = findColumn(headers, preferredActualAmountHeaders)
        val resolvedAmountIdx = if (preferredAmountIdx >= 0) {
            preferredAmountIdx
        } else {
            findColumn(headers, amountHeaders)
        }
        return ColumnMap(
            timeIdx = findColumn(headers, timeHeaders),
            amountIdx = resolvedAmountIdx,
            debitAmountIdx = findColumn(headers, debitAmountHeaders),
            creditAmountIdx = findColumn(headers, creditAmountHeaders),
            typeIdx = findColumn(headers, typeHeaders),
            merchantIdx = findColumn(headers, merchantHeaders),
            productIdx = findColumn(headers, productHeaders),
            directionIdx = findColumn(headers, directionHeaders),
            fundingIdx = findColumn(headers, fundingHeaders),
            statusIdx = findColumn(headers, statusHeaders),
            orderIdx = findColumn(headers, orderHeaders),
            merchantOrderIdx = findColumn(headers, merchantOrderHeaders),
            noteIdx = findColumn(headers, noteHeaders)
        )
    }

    private fun findColumn(headers: List<String>, candidates: Set<String>): Int =
        headers.indexOfFirst { it in candidates }

    private fun normalizeHeader(raw: String): String =
        raw.removePrefix("\uFEFF")
            .trim()
            .replace("（", "(")
            .replace("）", ")")

    private fun cellAt(row: List<String>, index: Int): String =
        if (index in row.indices) row[index] else ""

    private fun cleanCell(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed == "/" || trimmed == "--") "" else trimmed
    }

    private fun isPostHeaderDivider(rawTime: String): Boolean {
        val trimmedTime = rawTime.trim()
        return trimmedTime.startsWith("---") || trimmedTime.startsWith("===")
    }

    private fun isFooterOrDividerRow(rawTime: String, row: List<String>): Boolean {
        val trimmedTime = rawTime.trim()
        if (trimmedTime.startsWith("---") ||
            trimmedTime.startsWith("===") ||
            trimmedTime.startsWith("合计") ||
            trimmedTime.startsWith("共") ||
            trimmedTime.startsWith("注：") ||
            trimmedTime.startsWith("注:") ||
            trimmedTime.startsWith("导出时间") ||
            trimmedTime.startsWith("特别提示") ||
            trimmedTime.startsWith("已支出") ||
            trimmedTime.startsWith("已收入") ||
            trimmedTime.startsWith("不计收支")
        ) {
            return true
        }
        return row.count { cleanCell(it).isNotEmpty() } <= 1
    }

    private fun isSkippedStatus(status: String): Boolean =
        status.isNotEmpty() && skippedStatusKeywords.any { status.contains(it) }

    /**
     * Alipay can export a previously-paid order as "交易关闭" after a refund that is not part of
     * the same file. Keep such outgoing originals so a later refund import can cancel them, while
     * still filtering unpaid/income/failed/refund-closed rows.
     */
    private fun isRetainedAlipayClosedExpenseOrder(
        format: String,
        status: String,
        direction: String,
        fundingMethod: String,
        remark: String,
        txCategory: String
    ): Boolean {
        if (!format.startsWith("ALIPAY")) return false
        if (!status.contains("交易关闭") && !status.contains("已关闭")) return false
        if (status.contains("退款关闭") || status.contains("退款失败")) return false
        if (txCategory.contains("退款")) return false
        if (!direction.contains("支出")) return false
        if (fundingMethod.isBlank()) return false
        val unpaidHint = listOf(
            "未付款", "未支付", "未成交", "付款失败", "支付失败", "扣款失败"
        ).any { remark.contains(it) || status.contains(it) }
        return !unpaidHint
    }

    private fun resolveTransactionType(
        direction: String,
        status: String,
        txCategory: String,
        merchant: String,
        product: String,
        inferredTypeFromAmount: TransactionType?,
        isGenericFormat: Boolean,
        isPaidThenRefundedClosedOrder: Boolean = false
    ): TransactionType? {
        val combinedMeta = "$txCategory $merchant $product".trim()
        val isInternalTransfer = !nonTransferRechargeRegex.containsMatchIn(combinedMeta) &&
            billTransferRegex.containsMatchIn(combinedMeta)

        // Original outgoing expenses in WeChat/Alipay carry post-hoc statuses such as "已退款(¥1.11)"
        // or "已全额退款" alongside a SEPARATE refund credit row; keep the original row as EXPENSE.
        if (direction.contains("支出") || direction == "借" || direction == "借方" || direction == "转出") {
            return if (isInternalTransfer) TransactionType.TRANSFER else TransactionType.EXPENSE
        }
        if (isPaidThenRefundedClosedOrder && !isInternalTransfer && (direction in neutralDirections || direction.isEmpty())) {
            return TransactionType.EXPENSE
        }
        if (status.contains("退款") || direction.contains("退款") || txCategory.contains("退款")) {
            return TransactionType.REFUND
        }
        if (direction in neutralDirections) {
            return TransactionType.TRANSFER
        }
        if (isInternalTransfer) {
            return TransactionType.TRANSFER
        }
        if (direction.contains("收入") || direction == "贷" || direction == "贷方" || direction == "转入") {
            return TransactionType.INCOME
        }

        if (inferredTypeFromAmount != null) {
            return inferredTypeFromAmount
        }

        if (direction.isEmpty()) {
            return when {
                status.contains("存入零钱") || status.contains("已收钱") || status.contains("收款") ->
                    TransactionType.INCOME
                txCategory.contains("消费") || status.contains("支付成功") || status.contains("已完成") || status.contains("交易成功") ->
                    TransactionType.EXPENSE
                isGenericFormat -> PaymentTextClassifier.classifyTransactionType("$status $combinedMeta")
                    ?: TransactionType.EXPENSE
                else -> TransactionType.TRANSFER
            }
        }

        return PaymentTextClassifier.classifyTransactionType("$direction $status $combinedMeta")
    }

    private fun resolveMerchant(
        rawMerchant: String,
        product: String,
        txCategory: String,
        txType: TransactionType,
        channel: PaymentChannel
    ): String {
        if (rawMerchant.isNotEmpty()) return rawMerchant
        if (product.isNotEmpty()) return product
        if (txType == TransactionType.TRANSFER) {
            val inferred = PaymentTextClassifier.inferTransferTitle("$txCategory $product")
            if (inferred != "内部转账" || txCategory.isEmpty()) return inferred
        }
        if (txCategory.isNotEmpty()) return txCategory
        return when (channel) {
            PaymentChannel.WECHAT -> "微信支付"
            PaymentChannel.ALIPAY -> "支付宝"
            PaymentChannel.UNIONPAY -> "云闪付"
            PaymentChannel.BANK -> "银行卡"
            else -> "未知商户"
        }
    }

    private fun buildNote(
        merchant: String,
        product: String,
        remark: String,
        txCategory: String,
        txType: TransactionType
    ): String {
        val parts = mutableListOf<String>()
        if (product.isNotEmpty() && product != merchant) {
            parts += product
        }
        if (remark.isNotEmpty() && remark != merchant && remark != product) {
            parts += remark
        }
        // Transfers such as 提现/充值 must keep their business category as evidence for the
        // wallet <-> bank reconciliation pass, even when the row has no explicit remark.
        if (txType == TransactionType.TRANSFER &&
            txCategory.isNotEmpty() &&
            parts.none { it.contains(txCategory) }
        ) {
            parts += txCategory
        }
        return parts.joinToString(" · ")
    }

    private fun parseTimestamp(raw: String, zoneId: ZoneId): ParsedTimestamp? {
        val cleaned = raw.trim().trim('"')
        if (cleaned.isEmpty()) return null

        for (formatter in timestampFormatters) {
            try {
                val millis = LocalDateTime.parse(cleaned, formatter)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli()
                return ParsedTimestamp(millis, hasTimeOfDay = true)
            } catch (_: DateTimeParseException) {
                // Try next pattern
            }
        }

        try {
            return ParsedTimestamp(OffsetDateTime.parse(cleaned).toInstant().toEpochMilli(), hasTimeOfDay = true)
        } catch (_: DateTimeParseException) {
            // Continue
        }

        try {
            return ParsedTimestamp(Instant.parse(cleaned).toEpochMilli(), hasTimeOfDay = true)
        } catch (_: DateTimeParseException) {
            // Continue
        }

        for (formatter in dateOnlyFormatters) {
            try {
                val localDate = LocalDate.parse(cleaned, formatter)
                val millis = localDate.atTime(12, 0, 0).atZone(zoneId).toInstant().toEpochMilli()
                return ParsedTimestamp(millis, hasTimeOfDay = false)
            } catch (_: DateTimeParseException) {
                // Try next pattern
            }
        }

        if (excelSerialRegex.matches(cleaned)) {
            val serialDays = cleaned.toDoubleOrNull() ?: return null
            val hasFraction = cleaned.contains('.') && (serialDays % 1.0) > 1e-6
            val seconds = (serialDays * 86_400.0).roundToLong()
            val dt = if (hasFraction) {
                LocalDateTime.of(1899, 12, 30, 0, 0, 0).plusSeconds(seconds)
            } else {
                LocalDate.of(1899, 12, 30).plusDays(serialDays.toLong()).atTime(12, 0, 0)
            }
            return ParsedTimestamp(dt.atZone(zoneId).toInstant().toEpochMilli(), hasTimeOfDay = hasFraction)
        }

        return null
    }

    private val bankFilePrefixRegex = Regex("""(?i)(?:^|[_\-\s])(icbc|ccb|abc|boc|cmb|bocom|spdb|citic|pingan|psbc|bank)(?:[_\-\s\d.]|$)""")

    private val timeHeaders = setOf(
        "交易时间", "下单时间", "付款时间", "支付时间", "创建时间", "消费时间",
        "记账日期", "交易日期", "消费日期", "日期", "时间"
    )
    private val preferredActualAmountHeaders = setOf("实付款", "实付金额", "实际支付", "实际付款")
    private val amountHeaders = setOf("金额", "金额(元)", "交易金额", "发生额", "人民币金额", "订单金额")
    private val debitAmountHeaders = setOf("支出金额", "借方发生额", "支出(元)", "借方金额")
    private val creditAmountHeaders = setOf("收入金额", "贷方发生额", "收入(元)", "贷方金额")
    private val typeHeaders = setOf("交易类型", "交易分类", "业务类型", "交易摘要")
    private val merchantHeaders = setOf(
        "交易对方", "收款方", "商户名称", "店铺名称", "商家名称", "对方户名", "对方名称", "商户", "门店"
    )
    private val productHeaders = setOf("商品", "商品说明", "商品名称", "订单内容", "摘要", "交易描述", "消费说明")
    private val directionHeaders = setOf("收/支", "收支", "收支类型", "交易方向", "借贷标志", "借/贷", "资金流向")
    private val fundingHeaders = setOf("支付方式", "收/付款方式", "付款方式", "交易卡号", "卡号")
    private val statusHeaders = setOf("当前状态", "交易状态", "订单状态", "状态")
    private val orderHeaders = setOf("交易单号", "交易订单号", "交易号", "订单号", "订单编号", "流水号")
    private val merchantOrderHeaders = setOf("商户单号", "商家订单号")
    private val noteHeaders = setOf("备注", "附言")
}
