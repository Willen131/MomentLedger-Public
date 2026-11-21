package com.momentledger.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class TransactionType {
    EXPENSE,
    INCOME,
    REFUND,
    TRANSFER
}

@Serializable
enum class PaymentChannel {
    WECHAT,
    ALIPAY,
    UNIONPAY,
    BANK,
    MANUAL,
    OTHER
}

const val HOUR_OF_DAY_UNKNOWN = -1

@Serializable
enum class CaptureSource {
    ACCESSIBILITY,
    NOTIFICATION,
    BOTH,
    IMPORT,
    OCR,
    MANUAL
}

@Serializable
enum class CategoryType {
    EXPENSE,
    INCOME
}

@Serializable
enum class RuleMatchType {
    EXACT_USER,
    KEYWORD_BUILTIN
}

@Serializable
data class ParsedPayment(
    val channel: PaymentChannel,
    val fundingMethod: String,
    val type: TransactionType,
    val amountCents: Long,
    val merchant: String,
    val timestampMillis: Long,
    val source: CaptureSource,
    val externalOrderId: String? = null,
    val note: String = "",
    val hasTimeOfDay: Boolean = true
)

@Serializable
data class TransactionRecord(
    val id: Long,
    val timestampMillis: Long,
    val localDate: String,
    val hourOfDay: Int,
    val amountCents: Long,
    val type: TransactionType,
    val channel: PaymentChannel,
    val fundingMethod: String,
    val merchant: String,
    val categoryId: String,
    val note: String,
    val excludedFromStats: Boolean,
    val captureSource: CaptureSource,
    val externalOrderId: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val customChannel: String? = null
)

@Serializable
data class Category(
    val id: String,
    val name: String,
    val categoryType: CategoryType,
    val iconKey: String,
    val colorHex: String,
    val sortOrder: Int,
    val isSystem: Boolean,
    val isArchived: Boolean = false
)

@Serializable
data class MerchantRule(
    val id: Long,
    val pattern: String,
    val categoryType: CategoryType,
    val matchType: RuleMatchType,
    val categoryId: String,
    val priority: Int
)

@Serializable
data class CaptureDebugLog(
    val id: Long = 0L,
    val timestampMillis: Long,
    val source: String,
    val packageName: String,
    val matched: Boolean,
    val summary: String
)

object DefaultCategories {
    const val EXPENSE_OTHER_ID = "exp_other"
    const val INCOME_OTHER_ID = "inc_other"

    const val REFUND_CATEGORY_ID = "inc_refund"

    val all: List<Category> = listOf(
        Category("exp_food", "餐饮", CategoryType.EXPENSE, "restaurant", "#FF9500", 1, true),
        Category("exp_transport", "交通", CategoryType.EXPENSE, "directions_car", "#007AFF", 2, true),
        Category("exp_shopping", "购物", CategoryType.EXPENSE, "shopping_bag", "#FF2D55", 3, true),
        Category("exp_daily", "日用", CategoryType.EXPENSE, "shopping_cart", "#34C759", 4, true),
        Category("exp_fun", "娱乐", CategoryType.EXPENSE, "sports_esports", "#AF52DE", 5, true),
        Category("exp_medical", "医疗", CategoryType.EXPENSE, "medical_services", "#FF3B30", 6, true),
        Category("exp_housing", "住房水电", CategoryType.EXPENSE, "home", "#5856D6", 7, true),
        Category("exp_telecom", "通讯", CategoryType.EXPENSE, "phone_iphone", "#32ADE6", 8, true),
        Category("exp_study", "学习", CategoryType.EXPENSE, "menu_book", "#FFCC00", 9, true),
        Category("exp_social", "人情", CategoryType.EXPENSE, "card_giftcard", "#FF6482", 10, true),
        Category(EXPENSE_OTHER_ID, "其他支出", CategoryType.EXPENSE, "more_horiz", "#8E8E93", 11, true),
        Category("inc_salary", "工资", CategoryType.INCOME, "payments", "#34C759", 1, true),
        Category("inc_redpacket", "红包转账", CategoryType.INCOME, "redeem", "#FF3B30", 2, true),
        Category("inc_reimburse", "报销", CategoryType.INCOME, "receipt_long", "#007AFF", 3, true),
        Category("inc_finance", "理财退款", CategoryType.INCOME, "trending_up", "#FF9500", 4, true),
        Category(INCOME_OTHER_ID, "其他收入", CategoryType.INCOME, "account_balance_wallet", "#8E8E93", 5, true),
        Category(REFUND_CATEGORY_ID, "退款", CategoryType.INCOME, "currency_exchange", "#00A889", 6, true)
    )

    private val expenseKeywordsByCategory: List<Pair<String, List<String>>> = listOf(
        "exp_food" to listOf(
            "餐饮", "咖啡", "瑞幸", "星巴克", "库迪", "奶茶", "喜茶", "霸王茶姬", "蜜雪冰城", "奈雪",
            "古茗", "茶百道", "沪上阿姨", "美团外卖", "美团优选", "美团买菜", "饿了么", "外卖", "大众点评",
            "麦当劳", "肯德基", "汉堡王", "必胜客", "海底捞", "塔斯汀", "华莱士", "萨莉亚",
            "餐厅", "饭店", "食堂", "烧烤", "火锅", "面馆", "早餐", "午餐", "晚餐",
            "水果", "生鲜", "盒马", "叮咚", "叮咚买菜", "朴朴超市", "小吃", "烘焙", "面包", "蛋糕", "茶饮"
        ),
        "exp_transport" to listOf(
            "交通", "地铁", "公交", "出租车", "滴滴", "打车", "高德打车", "花小猪", "曹操出行",
            "t3出行", "铁路", "12306", "高铁", "火车", "机票", "航空", "航司", "加油",
            "中石化", "中石油", "充电桩", "停车", "etc", "高速", "哈啰", "青桔", "共享单车"
        ),
        "exp_shopping" to listOf(
            "购物", "淘宝", "天猫", "京东", "拼多多", "抖音电商", "抖音商城", "唯品会", "小红书", "得物", "亚马逊",
            "商城", "旗舰店", "百货", "服饰", "优衣库", "zara", "耐克", "阿迪达斯", "迪卡侬",
            "apple store", "苹果", "小米之家", "华为商城", "数码"
        ),
        "exp_daily" to listOf(
            "日用", "超市", "便利店", "7-11", "全家", "罗森", "屈臣氏", "名创优品", "永辉",
            "沃尔玛", "山姆", "开市客", "大润发", "华润万家", "生活用品", "日用品", "洗衣",
            "保洁", "快递", "顺丰", "菜鸟"
        ),
        "exp_fun" to listOf(
            "娱乐", "电影", "猫眼", "淘票票", "影城", "ktv", "游戏", "steam", "腾讯充值",
            "网易游戏", "会员", "爱奇艺", "优酷", "腾讯视频", "哔哩哔哩", "b站", "qq音乐",
            "网易云音乐", "门票", "景点", "旅游", "酒店", "民宿", "携程", "去哪儿", "飞猪"
        ),
        "exp_medical" to listOf(
            "医疗", "医院", "诊所", "药店", "药房", "大药房", "体检", "口腔", "牙科",
            "眼科", "挂号", "医保", "叮当快药"
        ),
        "exp_housing" to listOf(
            "住房", "房租", "物业", "水费", "电费", "燃气", "煤气", "供暖", "宽带",
            "国家电网", "南方电网", "水务", "装修", "宜家"
        ),
        "exp_telecom" to listOf(
            "通讯", "话费", "流量", "中国移动", "中国联通", "中国电信", "充值中心"
        ),
        "exp_study" to listOf(
            "学习", "书店", "图书", "当当", "课程", "网课", "教育", "培训", "考试",
            "报名费", "知网", "文具", "打印"
        ),
        "exp_social" to listOf(
            "人情", "礼金", "份子钱", "红包", "转账", "鲜花", "礼品", "礼物", "聚会", "请客"
        )
    )

    private val incomeKeywordsByCategory: List<Pair<String, List<String>>> = listOf(
        "inc_salary" to listOf(
            "工资", "薪资", "薪酬", "奖金", "年终奖", "绩效", "津贴", "补贴", "劳务费", "稿费"
        ),
        "inc_redpacket" to listOf(
            "红包", "转账", "收款", "微信红包", "支付宝红包", "亲友"
        ),
        "inc_reimburse" to listOf(
            "报销", "差旅费", "费用报销"
        ),
        "inc_finance" to listOf(
            "理财", "收益", "分红", "利息", "余额宝", "零钱通", "基金", "股票", "派息", "返现", "退款"
        )
    )

    val builtInRules: List<MerchantRule> = buildRules()

    private fun buildRules(): List<MerchantRule> {
        var nextId = 1L
        val result = mutableListOf<MerchantRule>()
        fun appendGroup(type: CategoryType, groups: List<Pair<String, List<String>>>) {
            for ((categoryId, keywords) in groups) {
                for (keyword in keywords) {
                    result += MerchantRule(
                        id = nextId++,
                        pattern = keyword,
                        categoryType = type,
                        matchType = RuleMatchType.KEYWORD_BUILTIN,
                        categoryId = categoryId,
                        priority = 100
                    )
                }
            }
        }
        appendGroup(CategoryType.EXPENSE, expenseKeywordsByCategory)
        appendGroup(CategoryType.INCOME, incomeKeywordsByCategory)
        return result
    }
}
