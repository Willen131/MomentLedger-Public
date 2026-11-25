package com.momentledger.app.domain.parser

import java.math.BigDecimal
import java.math.RoundingMode

object AmountParser {

    private val numericPattern = Regex("""^[+-]?(\d+(\.\d+)?|\.\d+)$""")
    private val stripPattern = Regex("""[¥￥$＄,，\s\u00A0\u2000-\u200A\u202F\u205F\u3000\u200B-\u200F\uFEFF\uE000-\uF8FF元]|(?i)(RMB|CNY)""")

    fun parseToCents(raw: String): Long? {
        val asciiSigned = raw.trim()
            .replace('－', '-')
            .replace('＋', '+')
        val normalized = stripPattern.replace(asciiSigned, "")
        if (normalized.isEmpty() || !numericPattern.matches(normalized)) {
            return null
        }
        return try {
            val cents = BigDecimal(normalized)
                .abs()
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
            if (cents > 0L) cents else null
        } catch (_: NumberFormatException) {
            null
        } catch (_: ArithmeticException) {
            null
        }
    }

    fun formatYuan(cents: Long, signedPrefix: String = ""): String {
        val amount = BigDecimal.valueOf(cents, 2)
        val prefix = if (signedPrefix.isNotEmpty()) {
            signedPrefix
        } else if (cents < 0L) {
            "-"
        } else {
            ""
        }
        return "${prefix}¥${amount.abs().toPlainString()}"
    }

    fun formatRoundedYuan(cents: Long): String {
        val rounded = BigDecimal.valueOf(cents, 2)
            .abs()
            .setScale(0, RoundingMode.HALF_UP)
        val prefix = if (cents < 0L && rounded.signum() > 0) "-" else ""
        return "${prefix}¥${rounded.toPlainString()}"
    }
}
