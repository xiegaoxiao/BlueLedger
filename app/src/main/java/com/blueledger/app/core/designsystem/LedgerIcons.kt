package com.blueledger.app.core.designsystem

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.ui.graphics.vector.ImageVector
import com.blueledger.app.core.model.AccountKind
import com.blueledger.app.core.model.CategoryIcons

/**
 * iconKey → 图形 的唯一映射。
 *
 * 合法 key 由 [CategoryIcons] 定义（总控维护的唯一权威列表）；
 * 本对象只负责渲染，未识别的 key 回落到兜底图形，绝不因为备份里
 * 出现未知 key 而崩溃。分类图标统一使用蓝色与浅蓝底
 * （docs/UI设计说明.md §3.1），不为每个分类引入新的高饱和品牌色。
 */
object LedgerIcons {

    /** 用户可为分类选择的图形（顺序与 [CategoryIcons.SELECTABLE] 一致，供 S07 图标选择器使用）。 */
    val selectableKeys: List<String> = CategoryIcons.SELECTABLE

    /** 分类图形。 */
    fun category(iconKey: String): ImageVector = when (CategoryIcons.coerceForDisplay(iconKey)) {
        "vegetables" -> Icons.Outlined.Eco
        "fruit" -> Icons.Outlined.LocalGroceryStore
        "snacks" -> Icons.Outlined.Icecream
        "sports" -> Icons.Outlined.FitnessCenter
        "phone" -> Icons.Outlined.Phone
        "clothes" -> Icons.Outlined.Checkroom
        "beauty" -> Icons.Outlined.Face
        "household" -> Icons.Outlined.Chair
        "children" -> Icons.Outlined.ChildCare
        "elders" -> Icons.Outlined.Elderly
        "social" -> Icons.Outlined.People
        "travel" -> Icons.Outlined.Flight
        "tobacco" -> Icons.Outlined.LocalBar
        "digital" -> Icons.Outlined.Devices
        "car" -> Icons.Outlined.DirectionsCar
        "books" -> Icons.Outlined.MenuBook
        "pet" -> Icons.Outlined.Pets
        "cashgift" -> Icons.Outlined.Payments
        "present" -> Icons.Outlined.CardGiftcard
        "office" -> Icons.Outlined.Business
        "investment" -> Icons.Outlined.TrendingUp

        CategoryIcons.RESTAURANT -> Icons.Outlined.Restaurant
        CategoryIcons.TRANSPORT -> Icons.Outlined.DirectionsBus
        CategoryIcons.SHOPPING -> Icons.Outlined.ShoppingBag
        CategoryIcons.DAILY -> Icons.Outlined.ShoppingCart
        CategoryIcons.HOUSING -> Icons.Outlined.Home
        CategoryIcons.ENTERTAINMENT -> Icons.Outlined.SportsEsports
        CategoryIcons.MEDICAL -> Icons.Outlined.LocalHospital
        CategoryIcons.STUDY -> Icons.Outlined.MenuBook
        CategoryIcons.SALARY -> Icons.Outlined.AccountBalanceWallet
        CategoryIcons.BONUS -> Icons.Outlined.Star
        CategoryIcons.PART_TIME -> Icons.Outlined.Work
        CategoryIcons.GIFT -> Icons.Outlined.CardGiftcard
        CategoryIcons.CATEGORY_OTHER, CategoryIcons.INCOME_OTHER -> Icons.Outlined.MoreHoriz
        else -> Icons.Outlined.MoreHoriz
    }

    /** 图形选择器里显示的中文名。 */
    fun iconLabel(iconKey: String): String = when (CategoryIcons.coerceForDisplay(iconKey)) {
        "vegetables" -> "蔬菜"
        "fruit" -> "水果"
        "snacks" -> "零食"
        "sports" -> "运动"
        "phone" -> "通讯"
        "clothes" -> "服饰"
        "beauty" -> "美容"
        "household" -> "居家"
        "children" -> "孩子"
        "elders" -> "长辈"
        "social" -> "社交"
        "travel" -> "旅行"
        "tobacco" -> "烟酒"
        "digital" -> "数码"
        "car" -> "汽车"
        "books" -> "书籍"
        "pet" -> "宠物"
        "cashgift" -> "礼金"
        "present" -> "礼物"
        "office" -> "办公"
        "investment" -> "理财"

        CategoryIcons.RESTAURANT -> "餐饮"
        CategoryIcons.TRANSPORT -> "出行"
        CategoryIcons.SHOPPING -> "购物"
        CategoryIcons.DAILY -> "日用"
        CategoryIcons.HOUSING -> "居住"
        CategoryIcons.ENTERTAINMENT -> "娱乐"
        CategoryIcons.MEDICAL -> "医疗"
        CategoryIcons.STUDY -> "学习"
        CategoryIcons.SALARY -> "工资"
        CategoryIcons.BONUS -> "奖金"
        CategoryIcons.PART_TIME -> "兼职"
        CategoryIcons.GIFT -> "礼金"
        CategoryIcons.CATEGORY_OTHER, CategoryIcons.INCOME_OTHER -> "其他"
        else -> "其他"
    }

    /** 账户类型图标。 */
    fun accountKindIcon(kind: AccountKind): ImageVector = when (kind) {
        AccountKind.CASH -> Icons.Outlined.AccountBalanceWallet
        AccountKind.BANK_CARD -> Icons.Outlined.AccountBalance
        AccountKind.E_WALLET -> Icons.Outlined.Smartphone
        AccountKind.OTHER -> Icons.Outlined.Category
        AccountKind.CREDIT_CARD, AccountKind.LIABILITY -> Icons.Outlined.AccountBalance
        AccountKind.INVESTMENT, AccountKind.RECEIVABLE -> Icons.Outlined.Savings
    }

    /** 账户类型中文名。 */
    fun accountKindLabel(kind: AccountKind): String = when (kind) {
        AccountKind.CASH -> "现金"
        AccountKind.BANK_CARD -> "储蓄卡"
        AccountKind.E_WALLET -> "虚拟账户"
        AccountKind.OTHER -> "自定义资产"
        AccountKind.CREDIT_CARD -> "信用卡"
        AccountKind.INVESTMENT -> "投资账户"
        AccountKind.LIABILITY -> "负债"
        AccountKind.RECEIVABLE -> "债权"
    }
}
