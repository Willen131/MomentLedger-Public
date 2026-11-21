package com.momentledger.app.domain.parser

import com.momentledger.app.domain.model.TransactionType

/**
 * Known Chinese payment wallet and mainstream bank identifiers/names.
 */
object PaymentPackages {
    const val WECHAT = "com.tencent.mm"
    const val ALIPAY = "com.eg.android.AlipayGphone"
    const val UNIONPAY = "com.unionpay"

    val defaultBankPackages: Map<String, String> = linkedMapOf(
        "com.icbc" to "工商银行",
        "com.chinamworld.main" to "建设银行",
        "com.android.bankabc" to "农业银行",
        "com.chinamworld.bocmbci" to "中国银行",
        "cmb.pb" to "招商银行",
        "com.bankcomm.Bankcomm" to "交通银行",
        "com.ecitic.bank.mobile" to "中信银行",
        "cn.com.spdb.mobilebank.per" to "浦发银行",
        "com.pingan.paces.ccms" to "平安银行",
        "com.yitong.mbank.psbc" to "邮储银行"
    )
}

/**
 * Shared Android-ICU-safe (zero lookbehind) transaction-direction classifier and text helpers.
 */
internal object PaymentTextClassifier {

    private val hardFailureRegex = Regex(
        """支付失败|扣款失败|交易失败|转账失败|提现失败|还款失败|充值失败|交易关闭|未支付|待支付|待付款|已取消|余额不足|验证码|动态密码|校验码|""" +
            """请输入支付密码|请输入密码|忘记密码|添加转账说明|确认转账|立即支付|确认支付|""" +
            """待你收款|立即退还|退还给对方|请收款|请你确认收款"""
    )

    private val pendingConfirmActionNodes = setOf(
        "确认收款",
        "确认收钱",
        "立即收款",
        "立即退还",
        "待你收款"
    )

    private val billReminderRegex = Regex(
        """账单已出|最后还款日|最低还款|还款提醒|(?:本期|剩余|当前)?应还|待还"""
    )

    private val repaymentDoneRegex = Regex("""还款成功|已还清|自动还款成功""")

    private val futureRefundNoticeStripRegex = Regex(
        """(?:1天内|24小时内|逾期)[^，。\n]*退还[^，。\n]*|将退还"""
    )

    private val negatedRefundStripRegex = Regex("""(?:不支持|不可|不)(?:退款|退回|退还|冲正|退费)""")
    private val nonCompletedRefundStripRegex = Regex(
        """(?:申请|我要|去|发起|可|支持|极速|闪电|快速|7天无理由|七天无理由|无理由退货|售后/)退款|退款/售后|退款售后|申请售后|退换货"""
    )
    private val refundRegex = Regex("""退款|退回|退还|冲正|退费""")

    private val nonWalletRechargeRegex = Regex(
        """(?:话费|流量|电费|水费|燃气|油卡|游戏|会员|视频|音乐|点卡)充值"""
    )

    private val transferRegex = Regex(
        """零钱提现|余额提现|提现申请已提交|提现到银行卡|提现成功|提现已到账|""" +
            """零钱充值|余额充值|充值到零钱|零钱转入|""" +
            """(?:余额宝|零钱通|余利宝)(?:转入|转出)|转入(?:余额宝|零钱通|余利宝)|转出(?:余额宝|零钱通|余利宝)|""" +
            """信用卡还款|还信用卡|花呗还款|白条还款|本人账户互转|同名账户转账|卡内互转"""
    )

    private val walletRechargeGenericRegex = Regex("""(?:零钱|余额|钱包).*充值成功|充值成功.*(?:零钱|余额|钱包)""")
    private val genericRepaymentRegex = Regex("""(?:信用卡|花呗|白条|账单)?还款成功""")

    private val outgoingFriendTransferRegex = Regex(
        """待(?!你)[\u4e00-\u9fa5A-Za-z0-9_·\-.()（）\s]{1,24}(?:确认)?收款|待确认收款|""" +
            """朋友已收款|对方已收款|(?:^|\n)(?!你)[\u4e00-\u9fa5A-Za-z0-9_·\-.()（）]{1,20}已收[款钱]|""" +
            """已存入(?:对方|朋友)的?零钱|1天内(?:朋友|对方)?未确认|退还给你|重发转账消息|""" +
            """转账成功|已转账|向.{1,20}转账|转账给"""
    )

    private val receiverOwnWalletCueRegex = Regex(
        """你已收[款钱]|已存入(?:你的)?零钱|查看零钱|收款到账|微信收款|收款成功|收钱码"""
    )

    private val incomeExclusionStripRegex = Regex(
        """收款(?:方|商户|人|账户)|已存入(?:对方|朋友)的?零钱|(?:对方|朋友)的?存入零钱|(?:对方|朋友)存入|(?:朋友|对方|待)已收钱"""
    )

    private val strongIncomeRegex = Regex(
        """收款到账|微信收款|收款成功|一笔.{0,10}收款|已存入(?:你的)?零钱|存入零钱|你已收[款钱]|已收钱|收钱码|""" +
            """收到.{0,30}(?:转账|红包|汇款|工资|薪资|报销|款项|来款|付款)|""" +
            """入账|汇入|代发|工资|薪资|薪酬|奖金|报销|分红|派息|返现|收益"""
    )

    private val expenseRegex = Regex(
        """支出|消费|付款|支付|扣款|扣费|买单|下单|代扣|快捷支付|网上支付|扫码支付|免密支付|自动扣费|转账|实付"""
    )

    private val genericIncomeRegex = Regex("""存入|转入|收入|到账""")

    fun isRejectedPrePaymentOrPendingNodes(cleanNodes: List<String>, rawText: String): Boolean {
        if (hardFailureRegex.containsMatchIn(rawText)) return true
        val normalizedNodes = cleanNodes.map { it.trim().trimEnd('!', '！', '。', '.', ':', '：') }
        if (normalizedNodes.any { it in pendingConfirmActionNodes }) return true
        if (normalizedNodes.any { it == "待确认收款" || it == "待收款" }) {
            val hasSenderCue = normalizedNodes.contains("完成") ||
                rawText.contains("退还给你") ||
                rawText.contains("重发转账消息")
            if (!hasSenderCue) return true
        }
        return false
    }

    fun classifyTransactionType(rawText: String): TransactionType? {
        if (rawText.isBlank()) return null
        if (hardFailureRegex.containsMatchIn(rawText)) return null
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (isRejectedPrePaymentOrPendingNodes(lines, rawText)) return null
        if (billReminderRegex.containsMatchIn(rawText) && !repaymentDoneRegex.containsMatchIn(rawText)) {
            return null
        }

        val sanitizedForRefund = nonCompletedRefundStripRegex.replace(
            negatedRefundStripRegex.replace(
                futureRefundNoticeStripRegex.replace(rawText, ""),
                ""
            ),
            ""
        )
        if (refundRegex.containsMatchIn(sanitizedForRefund)) {
            return TransactionType.REFUND
        }

        if (transferRegex.containsMatchIn(rawText) ||
            genericRepaymentRegex.containsMatchIn(rawText) ||
            (!nonWalletRechargeRegex.containsMatchIn(rawText) &&
                walletRechargeGenericRegex.containsMatchIn(rawText))
        ) {
            return TransactionType.TRANSFER
        }

        if (outgoingFriendTransferRegex.containsMatchIn(rawText)) {
            return TransactionType.EXPENSE
        }

        val sanitizedForIncome = incomeExclusionStripRegex.replace(rawText, "")
        if (strongIncomeRegex.containsMatchIn(sanitizedForIncome)) {
            return TransactionType.INCOME
        }

        if (rawText.contains("已收款")) {
            return if (receiverOwnWalletCueRegex.containsMatchIn(rawText)) {
                TransactionType.INCOME
            } else {
                TransactionType.EXPENSE
            }
        }

        if (expenseRegex.containsMatchIn(rawText)) {
            return TransactionType.EXPENSE
        }

        if (genericIncomeRegex.containsMatchIn(sanitizedForIncome)) {
            return TransactionType.INCOME
        }

        return null
    }

    fun inferTransferTitle(text: String): String = when {
        text.contains("信用卡还款") || text.contains("还信用卡") -> "信用卡还款"
        text.contains("花呗还款") -> "花呗还款"
        text.contains("白条还款") -> "白条还款"
        text.contains("零钱提现") -> "零钱提现"
        text.contains("余额提现") -> "余额提现"
        text.contains("提现") -> "零钱提现"
        text.contains("零钱充值") -> "零钱充值"
        text.contains("余额宝转入") || text.contains("转入余额宝") -> "余额宝转入"
        text.contains("余额宝转出") || text.contains("转出余额宝") -> "余额宝转出"
        text.contains("零钱通转入") || text.contains("转入零钱通") -> "零钱通转入"
        text.contains("零钱通转出") || text.contains("转出零钱通") -> "零钱通转出"
        else -> "内部转账"
    }
}
