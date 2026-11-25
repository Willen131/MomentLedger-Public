package com.momentledger.app.domain.engine

import com.momentledger.app.domain.model.Category
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

    @Test
    fun infersUserCustomCategoriesAndSpecificBuiltInCategoriesBeforeFallback() {
        val customMilkTea = Category(
            id = "custom_milktea",
            name = "奶茶",
            categoryType = CategoryType.EXPENSE,
            iconKey = "local_cafe",
            colorHex = "#FF88AA",
            sortOrder = 20,
            isSystem = false
        )
        val customPet = Category(
            id = "custom_pet",
            name = "宠物",
            categoryType = CategoryType.EXPENSE,
            iconKey = "pets",
            colorHex = "#66BB6A",
            sortOrder = 21,
            isSystem = false
        )
        val allCats = DefaultCategories.all + listOf(customMilkTea, customPet)

        // Custom category semantic match (霸王茶姬 -> user custom "奶茶")
        assertEquals(
            "custom_milktea",
            CategoryMatcher.matchCategoryId("霸王茶姬(万象城店)", "", TransactionType.EXPENSE, DefaultCategories.builtInRules, allCats)
        )
        // Custom category semantic match (猫粮 -> user custom "宠物")
        assertEquals(
            "custom_pet",
            CategoryMatcher.matchCategoryId("皇家成猫猫粮", "", TransactionType.EXPENSE, DefaultCategories.builtInRules, allCats)
        )
        // Everyday Chinese dining (乡村基·川菜小炒 -> exp_food, NOT exp_other!)
        assertEquals(
            "exp_food",
            CategoryMatcher.matchCategoryId("乡村基·川菜小炒(隆兴店)", "美团", TransactionType.EXPENSE, DefaultCategories.builtInRules, allCats)
        )
        // Specificity beats shorter generic keyword: "美团买药" -> exp_medical, "美团打车" -> exp_transport
        assertEquals(
            "exp_medical",
            CategoryMatcher.matchCategoryId("美团买药大药房", "", TransactionType.EXPENSE, DefaultCategories.builtInRules, allCats)
        )
        assertEquals(
            "exp_transport",
            CategoryMatcher.matchCategoryId("美团打车", "", TransactionType.EXPENSE, DefaultCategories.builtInRules, allCats)
        )
    }
}
