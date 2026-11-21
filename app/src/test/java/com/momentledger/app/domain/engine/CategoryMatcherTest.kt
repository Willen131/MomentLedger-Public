package com.momentledger.app.domain.engine

import com.momentledger.app.domain.model.CategoryType
import com.momentledger.app.domain.model.DefaultCategories
import com.momentledger.app.domain.model.MerchantRule
import com.momentledger.app.domain.model.RuleMatchType
import com.momentledger.app.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryMatcherTest {

    @Test
    fun exactUserRuleOverridesBuiltInKeywordRuleForSameMerchant() {
        val rules = DefaultCategories.builtInRules + listOf(
            MerchantRule(
                id = 1001L,
                pattern = "瑞幸咖啡",
                categoryType = CategoryType.EXPENSE,
                matchType = RuleMatchType.EXACT_USER,
                categoryId = "exp_daily",
                priority = 10
            )
        )

        val matched = CategoryMatcher.matchCategoryId(
            merchant = "瑞幸咖啡",
            note = "",
            txType = TransactionType.EXPENSE,
            rules = rules
        )

        assertEquals("exp_daily", matched)
    }

    @Test
    fun expenseRulesNeverBleedIntoIncomeTransactions() {
        val matchedCafeIncome = CategoryMatcher.matchCategoryId(
            merchant = "瑞幸咖啡",
            note = "",
            txType = TransactionType.INCOME,
            rules = DefaultCategories.builtInRules
        )
        assertEquals(DefaultCategories.INCOME_OTHER_ID, matchedCafeIncome)

        val matchedSalaryIncome = CategoryMatcher.matchCategoryId(
            merchant = "公司工资",
            note = "",
            txType = TransactionType.INCOME,
            rules = DefaultCategories.builtInRules
        )
        assertEquals("inc_salary", matchedSalaryIncome)
    }

    @Test
    fun refundUsesExpenseRulesToMatchOriginalExpenseCategory() {
        assertEquals(CategoryType.EXPENSE, CategoryMatcher.categoryTypeFor(TransactionType.REFUND))
        assertEquals(CategoryType.EXPENSE, CategoryMatcher.categoryTypeFor(TransactionType.EXPENSE))
        assertEquals(CategoryType.INCOME, CategoryMatcher.categoryTypeFor(TransactionType.INCOME))

        val matchedRefund = CategoryMatcher.matchCategoryId(
            merchant = "瑞幸咖啡",
            note = "订单退款",
            txType = TransactionType.REFUND,
            rules = DefaultCategories.builtInRules
        )

        assertEquals("exp_food", matchedRefund)
    }

    @Test
    fun unknownExpenseMerchantFallsBackToExpenseOther() {
        val matched = CategoryMatcher.matchCategoryId(
            merchant = "某未知小铺XYZ",
            note = "",
            txType = TransactionType.EXPENSE,
            rules = DefaultCategories.builtInRules
        )

        assertEquals(DefaultCategories.EXPENSE_OTHER_ID, matched)
    }

    @Test
    fun keywordRuleCanMatchNoteWhenMerchantIsGeneric() {
        val matched = CategoryMatcher.matchCategoryId(
            merchant = "个人商户",
            note = "地铁充值",
            txType = TransactionType.EXPENSE,
            rules = DefaultCategories.builtInRules
        )

        assertEquals("exp_transport", matched)
    }

    @Test
    fun defaultCategoriesIncludeRefundInIncomeWithUniqueIcons() {
        val expenseCategories = DefaultCategories.all.filter { it.categoryType == CategoryType.EXPENSE }
        val incomeCategories = DefaultCategories.all.filter { it.categoryType == CategoryType.INCOME }

        assertEquals(11, expenseCategories.size)
        assertEquals(6, incomeCategories.size)
        assertEquals("退款", incomeCategories.single { it.id == DefaultCategories.REFUND_CATEGORY_ID }.name)
        assertEquals(DefaultCategories.all.size, DefaultCategories.all.map { it.iconKey }.toSet().size)
    }
}
