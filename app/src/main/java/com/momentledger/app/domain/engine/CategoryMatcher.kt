package com.momentledger.app.domain.engine

import com.momentledger.app.domain.model.Category
import com.momentledger.app.domain.model.CategoryType
import com.momentledger.app.domain.model.DefaultCategories
import com.momentledger.app.domain.model.MerchantRule
import com.momentledger.app.domain.model.RuleMatchType
import com.momentledger.app.domain.model.TransactionType
import java.util.Locale

object CategoryMatcher {

    // Semantic synonym clusters so user-created custom categories (e.g. "奶茶", "咖啡", "外卖",
    // "宠物", "游戏", "美妆", "健身", "零食") are automatically matched from OCR merchant & note text!
    private val customCategorySynonyms: List<Pair<List<String>, List<String>>> = listOf(
        listOf("奶茶", "茶饮", "饮品", "饮料") to listOf(
            "奶茶", "喜茶", "霸王茶姬", "蜜雪冰城", "奈雪", "古茗", "茶百道", "沪上阿姨", "茶颜悦色",
            "一点点", "书亦", "益禾堂", "茶饮", "果茶", "柠檬茶", "酸奶"
        ),
        listOf("咖啡") to listOf("咖啡", "瑞幸", "luckin", "星巴克", "starbucks", "库迪", "manner", "拿铁", "美式"),
        listOf("外卖") to listOf("外卖", "美团外卖", "饿了么", "闪送"),
        listOf("零食", "甜品", "下午茶") to listOf(
            "零食", "三只松鼠", "良品铺子", "好想来", "赵一鸣", "零食很忙", "甜品", "蛋糕", "面包", "烘焙", "冰淇淋", "好利来", "鲍师傅"
        ),
        listOf("买菜", "生鲜", "水果") to listOf("买菜", "生鲜", "水果", "盒马", "叮咚", "朴朴", "美团买菜", "美团优选", "小象超市", "菜市场"),
        listOf("宠物", "猫", "狗") to listOf("宠物", "猫粮", "狗粮", "猫砂", "宠医", "兽医", "宠物医院", "宠物店"),
        listOf("美妆", "护肤", "化妆") to listOf("美妆", "护肤", "化妆", "口红", "面膜", "丝芙兰", "屈臣氏", "兰蔻", "雅诗兰黛"),
        listOf("服饰", "衣服", "穿搭", "鞋") to listOf("服饰", "服装", "优衣库", "zara", "耐克", "阿迪达斯", "鞋", "外套", "裤"),
        listOf("数码", "电子", "电脑", "手机") to listOf("数码", "苹果", "apple", "小米之家", "华为", "耳机", "键盘", "鼠标", "充电器", "显卡"),
        listOf("游戏", "二次元", "谷子", "手办") to listOf(
            "游戏", "steam", "米哈游", "原神", "崩坏", "腾讯游戏", "腾讯充值", "网易游戏", "点券", "盲盒", "泡泡玛特", "谷子", "手办"
        ),
        listOf("健身", "运动", "球") to listOf("健身", "运动", "羽毛球", "游泳", "瑜伽", "迪卡侬", "球馆", "台球", "跑步"),
        listOf("旅游", "旅行", "酒店", "出行") to listOf("旅游", "旅行", "酒店", "民宿", "携程", "去哪儿", "飞猪", "同程", "景点", "门票"),
        listOf("汽车", "养车", "加油", "停车") to listOf("加油", "中石化", "中石油", "充电桩", "停车", "etc", "高速", "洗车", "修车", "代驾"),
        listOf("房租", "水电", "物业") to listOf("房租", "租金", "物业", "水费", "电费", "燃气", "国家电网", "南方电网", "宽带"),
        listOf("理发", "美发", "美容") to listOf("理发", "美发", "剪发", "烫发", "染发", "美容", "美甲")
    )

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
        rules: List<MerchantRule>,
        categories: List<Category> = emptyList()
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

        // 1. Highest priority: user's exact learned merchant rules
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

        val activeCategories = categories.filter { !it.isArchived && it.categoryType == targetType }

        // 2. User custom categories (direct name match or semantic synonym match)
        val customMatch = matchCustomCategory(normalizedMerchant, normalizedNote, activeCategories)
        if (customMatch != null) {
            return customMatch
        }

        // 3. Scored built-in keyword rules (prefer merchant hits & longer/more specific keywords,
        //    e.g. "美团买药" -> medical, "美团打车" -> transport, "乡村基/川菜/小炒" -> food)
        val bestRule = scopedRules
            .asSequence()
            .filter { it.matchType == RuleMatchType.KEYWORD_BUILTIN }
            .mapNotNull { rule ->
                val pattern = rule.pattern.trim().lowercase(Locale.ROOT)
                if (pattern.isEmpty()) return@mapNotNull null
                val inMerchant = normalizedMerchant.contains(pattern)
                val inNote = normalizedNote.contains(pattern)
                if (!inMerchant && !inNote) return@mapNotNull null
                val specificity = pattern.length * 10
                val sourceBonus = if (inMerchant) 30 else 0
                Triple(rule, rule.priority + specificity + sourceBonus, -rule.id)
            }
            .maxWithOrNull(compareBy<Triple<MerchantRule, Int, Long>> { it.second }.thenBy { it.third })
            ?.first
        if (bestRule != null) {
            return bestRule.categoryId
        }

        // 4. Direct match against active default category names (excluding "其他支出"/"其他收入")
        val directCategory = activeCategories
            .asSequence()
            .filter { it.id != fallbackId && it.name.trim().length >= 2 }
            .sortedByDescending { it.name.trim().length }
            .firstOrNull { cat ->
                val catName = cat.name.trim().lowercase(Locale.ROOT)
                normalizedMerchant.contains(catName) || normalizedNote.contains(catName)
            }
        if (directCategory != null) {
            return directCategory.id
        }

        return fallbackId
    }

    private fun matchCustomCategory(
        normalizedMerchant: String,
        normalizedNote: String,
        activeCategories: List<Category>
    ): String? {
        val customCategories = activeCategories.filter { !it.isSystem && it.name.trim().length >= 2 }
        if (customCategories.isEmpty()) return null

        var bestId: String? = null
        var bestScore = 0

        for (cat in customCategories) {
            val name = cat.name.trim().lowercase(Locale.ROOT)
            // Direct hit on the custom category's own name (e.g. category "奶茶" or "川菜" or "宠物")
            if (normalizedMerchant.contains(name)) {
                val score = 200 + name.length * 12
                if (score > bestScore) {
                    bestScore = score
                    bestId = cat.id
                }
                continue
            }
            if (normalizedNote.contains(name)) {
                val score = 170 + name.length * 12
                if (score > bestScore) {
                    bestScore = score
                    bestId = cat.id
                }
                continue
            }

            // Semantic synonym hit for custom category
            for ((triggers, keywords) in customCategorySynonyms) {
                if (triggers.none { name.contains(it) }) continue
                for (kw in keywords) {
                    val inMerchant = normalizedMerchant.contains(kw)
                    val inNote = normalizedNote.contains(kw)
                    if (inMerchant || inNote) {
                        val score = (if (inMerchant) 150 else 120) + kw.length * 10
                        if (score > bestScore) {
                            bestScore = score
                            bestId = cat.id
                        }
                    }
                }
            }
        }
        return bestId
    }
}
