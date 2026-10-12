package com.blueledger.app.core.model

/** Each location is a real account. Platform headings are derived totals only. */
enum class AssetPlatform(val label: String) { ALIPAY("支付宝"), WECHAT("微信"), BANK("银行卡"), OTHER("其他") }
enum class AccountLocation(val label: String, val platform: AssetPlatform) {
    ALIPAY_BALANCE("支付宝余额", AssetPlatform.ALIPAY),
    ALIPAY_POCKET("支付宝小荷包", AssetPlatform.ALIPAY),
    ALIPAY_YUEBAO("余额宝", AssetPlatform.ALIPAY),
    WECHAT_BALANCE("微信零钱", AssetPlatform.WECHAT),
    WECHAT_LINGQI("零钱通", AssetPlatform.WECHAT),
    BANK_CARD("银行卡", AssetPlatform.BANK),
    OTHER("其他资产", AssetPlatform.OTHER);

    companion object {
        fun infer(name: String, kind: AccountKind): AccountLocation = when {
            "小荷包" in name -> ALIPAY_POCKET
            "余额宝" in name -> ALIPAY_YUEBAO
            "支付宝" in name -> ALIPAY_BALANCE
            "零钱通" in name -> WECHAT_LINGQI
            "微信" in name -> WECHAT_BALANCE
            kind == AccountKind.BANK_CARD || kind == AccountKind.CREDIT_CARD -> BANK_CARD
            else -> OTHER
        }
    }
}
