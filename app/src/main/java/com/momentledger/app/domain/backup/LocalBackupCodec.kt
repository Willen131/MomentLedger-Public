package com.momentledger.app.domain.backup

import com.momentledger.app.domain.engine.AnalyticsCalculator
import com.momentledger.app.domain.model.CaptureSource
import com.momentledger.app.domain.model.Category
import com.momentledger.app.domain.model.HOUR_OF_DAY_UNKNOWN
import com.momentledger.app.domain.model.MerchantRule
import com.momentledger.app.domain.model.TransactionRecord
import com.momentledger.app.domain.model.TransactionType
import com.momentledger.app.domain.parser.AmountParser
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable
data class LocalBackupSnapshot(
    val schemaVersion: Int = 1,
    val exportedAtMillis: Long,
    val categories: List<Category>,
    val merchantRules: List<MerchantRule>,
    val transactions: List<TransactionRecord>
)

object LocalBackupCodec {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val csvDateTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private val csvHeader =
        "时间,类型,金额(元),分类,商户,支付渠道,资金来源,来源通道,不计入统计,备注,官方单号"

    fun encodeJson(snapshot: LocalBackupSnapshot): String =
        json.encodeToString(snapshot)

    fun decodeJson(raw: String): LocalBackupSnapshot =
        json.decodeFromString(raw)

    fun exportTransactionsCsv(
        transactions: List<TransactionRecord>,
        categoryNames: Map<String, String>,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val builder = StringBuilder()
        builder.append('\uFEFF')
        builder.append(csvHeader).append('\n')

        for (record in transactions) {
            val formattedTime = if (record.hourOfDay == HOUR_OF_DAY_UNKNOWN) {
                record.localDate.ifBlank {
                    Instant.ofEpochMilli(record.timestampMillis)
                        .atZone(zoneId)
                        .toLocalDate()
                        .toString()
                }
            } else {
                Instant.ofEpochMilli(record.timestampMillis)
                    .atZone(zoneId)
                    .format(csvDateTimeFormatter)
            }
            val categoryLabel = categoryNames[record.categoryId] ?: record.categoryId
            val fields = listOf(
                formattedTime,
                transactionTypeLabel(record.type),
                AmountParser.formatYuan(record.amountCents),
                categoryLabel,
                record.merchant,
                AnalyticsCalculator.channelLabel(record),
                record.fundingMethod,
                captureSourceLabel(record.captureSource),
                if (record.excludedFromStats) "是" else "否",
                record.note,
                record.externalOrderId.orEmpty()
            )
            builder.append(fields.joinToString(",") { escapeCsvField(it) }).append('\n')
        }
        return builder.toString()
    }

    private fun transactionTypeLabel(type: TransactionType): String = when (type) {
        TransactionType.EXPENSE -> "支出"
        TransactionType.INCOME -> "收入"
        TransactionType.REFUND -> "退款"
        TransactionType.TRANSFER -> "转账"
    }

    private fun captureSourceLabel(source: CaptureSource): String = when (source) {
        CaptureSource.ACCESSIBILITY -> "无障碍识别"
        CaptureSource.NOTIFICATION -> "通知栏识别"
        CaptureSource.BOTH -> "无障碍+通知"
        CaptureSource.IMPORT -> "账单导入"
        CaptureSource.OCR -> "截图识别"
        CaptureSource.MANUAL -> "手动录入"
    }

    private fun escapeCsvField(value: String): String {
        val sanitized = if (value.isNotEmpty() && value[0] in "=+-@\t\r") {
            "'$value"
        } else {
            value
        }
        val needsQuoting = sanitized.any { it == ',' || it == '"' || it == '\r' || it == '\n' }
        if (!needsQuoting) return sanitized
        val escaped = sanitized.replace("\"", "\"\"")
        return "\"$escaped\""
    }
}
