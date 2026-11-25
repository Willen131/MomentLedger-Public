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
            "餐饮", "美食", "咖啡", "瑞幸", "luckin", "星巴克", "starbucks", "库迪", "manner",
            "奶茶", "喜茶", "霸王茶姬", "蜜雪冰城", "奈雪", "茶颜悦色", "一点点", "书亦", "益禾堂",
            "古茗", "茶百道", "沪上阿姨", "茶饮", "饮品", "甜品", "冰淇淋", "酸奶",
            "美团外卖", "美团优选", "美团买菜", "饿了么", "外卖", "大众点评", "美团",
            "麦当劳", "mcdonald", "肯德基", "kfc", "汉堡王", "必胜客", "海底捞", "塔斯汀", "华莱士",
            "萨莉亚", "乡村基", "大米先生", "老乡鸡", "真功夫", "吉野家", "永和大王", "沙县", "杨国福", "张亮",
            "餐厅", "饭店", "食堂", "快餐", "小炒", "川菜", "湘菜", "粤菜", "东北菜", "本帮菜", "家常菜",
            "烧烤", "烤肉", "火锅", "串串", "麻辣烫", "冒菜", "砂锅", "焖锅", "干锅",
            "面馆", "拉面", "米线", "米粉", "螺蛳粉", "酸辣粉", "牛肉面", "盖饭", "炒饭", "便当", "盒饭",
            "日料", "寿司", "披萨", "汉堡", "炸鸡", "卤味", "烤鸭", "烧鹅", "烤鱼", "酸菜鱼", "小龙虾",
            "饺子", "馄饨", "包子", "馒头", "豆浆", "粥", "早餐", "午餐", "晚餐", "夜宵", "自助餐",
            "水果", "生鲜", "菜市场", "买菜", "盒马", "叮咚", "叮咚买菜", "朴朴超市", "小象超市",
            "小吃", "零食", "烘焙", "面包", "蛋糕", "鲍师傅", "好利来", "酒楼", "茶楼", "大排档"
        ),
        "exp_transport" to listOf(
            "交通", "出行", "美团打车", "美团单车", "地铁", "公交", "通卡", "乘车码", "出租车",
            "滴滴", "打车", "高德打车", "花小猪", "曹操出行", "t3出行", "享道出行", "嘀嗒",
            "铁路", "12306", "高铁", "火车", "机票", "航空", "航司", "加油", "油站",
            "中石化", "中石油", "壳牌", "充电桩", "特来电", "星星充电", "停车", "泊车",
            "etc", "高速", "哈啰", "青桔", "共享单车", "代驾", "洗车", "修车"
        ),
        "exp_shopping" to listOf(
            "购物", "网购", "淘宝", "天猫", "京东", "拼多多", "抖音电商", "抖音商城", "抖音小店",
            "唯品会", "小红书", "得物", "亚马逊", "闲鱼", "转转", "商城", "旗舰店", "专卖店",
            "百货", "服饰", "服装", "鞋", "优衣库", "zara", "hm", "耐克", "阿迪达斯", "迪卡侬",
            "apple store", "苹果", "小米之家", "华为商城", "数码", "家电", "美妆", "护肤", "丝芙兰"
        ),
        "exp_daily" to listOf(
            "日用", "超市", "便利店", "7-11", "711", "全家", "罗森", "美宜佳", "今日便利",
            "屈臣氏", "名创优品", "无印良品", "muji", "永辉", "沃尔玛", "山姆", "开市客", "大润发",
            "华润万家", "物美", "胖东来", "生活用品", "日用品", "百货店", "杂货",
            "洗衣", "干洗", "保洁", "家政", "理发", "美发", "剪发", "快递", "顺丰", "菜鸟", "丰巢", "中通", "圆通", "韵达"
        ),
        "exp_fun" to listOf(
            "娱乐", "休闲", "电影", "猫眼", "淘票票", "影城", "影院", "美团酒店", "美团门票",
            "ktv", "密室", "剧本杀", "桌游", "网吧", "电玩", "盲盒", "泡泡玛特", "谷子",
            "游戏", "steam", "腾讯充值", "腾讯游戏", "网易游戏", "米哈游", "原神", "崩坏", "王者",
            "会员", "大会员", "爱奇艺", "优酷", "腾讯视频", "芒果tv", "哔哩哔哩", "b站",
            "qq音乐", "网易云音乐", "spotify", "门票", "景点", "乐园", "迪士尼", "环球影城",
            "旅游", "酒店", "民宿", "携程", "去哪儿", "飞猪", "同程", "健身", "游泳", "羽毛球", "台球"
        ),
        "exp_medical" to listOf(
            "医疗", "健康", "美团买药", "京东健康", "阿里健康", "医院", "门诊", "急诊", "诊所",
            "药店", "药房", "大药房", "益丰", "老百姓", "大参林", "一心堂", "海王星辰",
            "体检", "口腔", "牙科", "眼科", "配镜", "挂号", "医保", "叮当快药", "疫苗"
        ),
        "exp_housing" to listOf(
            "住房", "房租", "租金", "自如", "链家", "贝壳", "物业", "水费", "电费", "水电",
            "燃气", "煤气", "天然气", "供暖", "暖气", "宽带", "网费",
            "国家电网", "南方电网", "网上国网", "水务", "装修", "宜家", "家具", "五金"
        ),
        "exp_telecom" to listOf(
            "通讯", "通信", "话费", "流量", "手机充值", "中国移动", "中国联通", "中国电信", "中国广电", "充值中心"
        ),
        "exp_study" to listOf(
            "学习", "书店", "图书", "新华书店", "西西弗", "当当", "微信读书", "得到",
            "课程", "网课", "教育", "培训", "考试", "报名费", "学费", "知网", "文具", "打印", "复印", "考研"
        ),
        "exp_social" to listOf(
            "人情", "礼金", "份子钱", "随礼", "红包", "转账", "鲜花", "花店", "礼品", "礼物", "聚会", "请客"
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
