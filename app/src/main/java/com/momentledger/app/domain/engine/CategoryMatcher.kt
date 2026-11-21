package com.momentledger.app.domain.engine

import com.momentledger.app.domain.model.CategoryType
import com.momentledger.app.domain.model.DefaultCategories
import com.momentledger.app.domain.model.MerchantRule
import com.momentledger.app.domain.model.RuleMatchType
import com.momentledger.app.domain.model.TransactionType
import java.util.Locale

object CategoryMatcher {

    fun categoryTypeFor(txType: TransactionType): CategoryType = when (txType) {
        TransactionType.INCOME -> CategoryType.INCOME
        TransactionType.EXPENSE,
        TransactionType.REFUND,
        TransactionType.TRANSFER -> CategoryType.EXPENSE
    }

    fun matchCategoryId(
        merchant: String,
        note: String,
        txType: TransactionType,
        rules: List<MerchantRule>
    ): String {
        val targetType = categoryTypeFor(txType)
        val fallbackId = when (targetType) {
            CategoryType.INCOME -> DefaultCategories.INCOME_OTHER_ID
            CategoryType.EXPENSE -> DefaultCategories.EXPENSE_OTHER_ID
        }

        val normalizedMerchant = merchant.trim().lowercase(Locale.ROOT)
        val normalizedNote = note.trim().lowercase(Locale.ROOT)
        if (normalizedMerchant.isEmpty() && normalizedNote.isEmpty()) {
            return fallbackId
        }

        val scopedRules = rules.filter {
            it.categoryType == targetType && it.pattern.isNotBlank()
        }

        if (normalizedMerchant.isNotEmpty()) {
            val exactMatch = scopedRules
                .asSequence()
                .filter { it.matchType == RuleMatchType.EXACT_USER }
                .sortedWith(
                    compareByDescending<MerchantRule> { it.priority }
                        .thenByDescending { it.id }
                )
                .firstOrNull { rule ->
                    normalizedMerchant == rule.pattern.trim().lowercase(Locale.ROOT)
                }
            if (exactMatch != null) {
                return exactMatch.categoryId
            }
        }

        val keywordMatch = scopedRules
            .asSequence()
            .filter { it.matchType == RuleMatchType.KEYWORD_BUILTIN }
            .sortedWith(
                compareByDescending<MerchantRule> { it.priority }
                    .thenByDescending { it.pattern.trim().length }
                    .thenBy { it.id }
            )
            .firstOrNull { rule ->
                val pattern = rule.pattern.trim().lowercase(Locale.ROOT)
                normalizedMerchant.contains(pattern) || normalizedNote.contains(pattern)
            }

        return keywordMatch?.categoryId ?: fallbackId
    }
}
