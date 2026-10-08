package com.blueledger.app.core.model

/**
 * 首次启动时初始化的默认分类与默认账户。
 *
 * 这是 A1（数据层幂等初始化）、A2（记账页分类网格）、A6（备份校验里的兜底分类检查）
 * 与验收夹具共用的**唯一**默认数据定义。稳定 ID 一经确定不得更改：
 * 备份文件与历史账单都按 ID 关联，改名/改 ID 会破坏已导出的备份可恢复性。
 *
 * 注意：这里只定义“默认分类 + 默认账户 + 设置”，**不包含任何演示账单**。
 * 正式首次启动的账单必须为零（PRD §4.1、AI提示词 §1 冲突裁决）。
 */
object Defaults {

    const val DEFAULT_ACCOUNT_ID: String = "acc_default"
    const val DEFAULT_ACCOUNT_NAME: String = "默认账户"

    const val FALLBACK_EXPENSE_CATEGORY_ID: String = "cat_expense_other"
    const val FALLBACK_INCOME_CATEGORY_ID: String = "cat_income_other"

    /** 默认分类定义。[isFallback] 为 true 的“其他”不可归档，保证每种类型都始终有可选分类。 */
    data class CategorySpec(
        val id: String,
        val type: TransactionType,
        val name: String,
        val iconKey: String,
        val sortOrder: Int,
        val isFallback: Boolean = false,
    ) {
        fun toCategory(): LedgerCategory = LedgerCategory(
            id = id,
            type = type,
            name = name,
            iconKey = iconKey,
            sortOrder = sortOrder,
            isArchived = false,
            isFallback = isFallback,
        )
    }

    /** 默认分类按参考页面顺序排列，既有 ID 保持稳定。 */
    val EXPENSE_CATEGORIES: List<CategorySpec> = listOf(
        CategorySpec("cat_expense_food", TransactionType.EXPENSE, "餐饮", "restaurant", 0),
        CategorySpec("cat_expense_shopping", TransactionType.EXPENSE, "购物", "shopping", 10),
        CategorySpec("cat_expense_daily", TransactionType.EXPENSE, "日用", "daily", 20),
        CategorySpec("cat_expense_transport", TransactionType.EXPENSE, "交通", "transport", 30),
        CategorySpec("cat_expense_vegetables", TransactionType.EXPENSE, "蔬菜", "vegetables", 40),
        CategorySpec("cat_expense_fruit", TransactionType.EXPENSE, "水果", "fruit", 50),
        CategorySpec("cat_expense_snacks", TransactionType.EXPENSE, "零食", "snacks", 60),
        CategorySpec("cat_expense_sports", TransactionType.EXPENSE, "运动", "sports", 70),
        CategorySpec("cat_expense_entertainment", TransactionType.EXPENSE, "娱乐", "entertainment", 80),
        CategorySpec("cat_expense_phone", TransactionType.EXPENSE, "通讯", "phone", 90),
        CategorySpec("cat_expense_clothes", TransactionType.EXPENSE, "服饰", "clothes", 100),
        CategorySpec("cat_expense_beauty", TransactionType.EXPENSE, "美容", "beauty", 110),
        CategorySpec("cat_expense_housing", TransactionType.EXPENSE, "住房", "housing", 120),
        CategorySpec("cat_expense_household", TransactionType.EXPENSE, "居家", "household", 130),
        CategorySpec("cat_expense_children", TransactionType.EXPENSE, "孩子", "children", 140),
        CategorySpec("cat_expense_elders", TransactionType.EXPENSE, "长辈", "elders", 150),
        CategorySpec("cat_expense_social", TransactionType.EXPENSE, "社交", "social", 160),
        CategorySpec("cat_expense_travel", TransactionType.EXPENSE, "旅行", "travel", 170),
        CategorySpec("cat_expense_tobacco", TransactionType.EXPENSE, "烟酒", "tobacco", 180),
        CategorySpec("cat_expense_digital", TransactionType.EXPENSE, "数码", "digital", 190),
        CategorySpec("cat_expense_car", TransactionType.EXPENSE, "汽车", "car", 200),
        CategorySpec("cat_expense_medical", TransactionType.EXPENSE, "医疗", "medical", 210),
        CategorySpec("cat_expense_books", TransactionType.EXPENSE, "书籍", "books", 220),
        CategorySpec("cat_expense_study", TransactionType.EXPENSE, "学习", "study", 230),
        CategorySpec("cat_expense_pet", TransactionType.EXPENSE, "宠物", "pet", 240),
        CategorySpec("cat_expense_cashgift", TransactionType.EXPENSE, "礼金", "cashgift", 250),
        CategorySpec("cat_expense_present", TransactionType.EXPENSE, "礼物", "present", 260),
        CategorySpec("cat_expense_office", TransactionType.EXPENSE, "办公", "office", 270),
        CategorySpec(FALLBACK_EXPENSE_CATEGORY_ID, TransactionType.EXPENSE, "其他", CategoryIcons.CATEGORY_OTHER, 900, isFallback = true),
    )

    val INCOME_CATEGORIES: List<CategorySpec> = listOf(
        CategorySpec("cat_income_salary", TransactionType.INCOME, "工资", "salary", 0),
        CategorySpec("cat_income_parttime", TransactionType.INCOME, "兼职", "part_time", 10),
        CategorySpec("cat_income_investment", TransactionType.INCOME, "理财", "investment", 20),
        CategorySpec("cat_income_gift", TransactionType.INCOME, "礼金", "gift", 30),
        CategorySpec("cat_income_bonus", TransactionType.INCOME, "奖金", "bonus", 40),
        CategorySpec(FALLBACK_INCOME_CATEGORY_ID, TransactionType.INCOME, "其他", CategoryIcons.INCOME_OTHER, 900, isFallback = true),
    )

    val CATEGORIES: List<CategorySpec> = EXPENSE_CATEGORIES + INCOME_CATEGORIES

    val DEFAULT_ACCOUNT: LedgerAccount = LedgerAccount(
        id = DEFAULT_ACCOUNT_ID,
        name = DEFAULT_ACCOUNT_NAME,
        kind = AccountKind.CASH,
        openingBalanceCent = 0L,
        isArchived = false,
    )

    /** 首次启动写入的设置。 */
    fun defaultSettings(): LedgerSettings = LedgerSettings(
        defaultAccountId = DEFAULT_ACCOUNT_ID,
        lastUsedAccountId = null,
        hideAmounts = false,
        currency = LedgerSettings.CURRENCY_CNY,
    )

    /**
     * 首页「快捷分类」的推荐顺序（PRD S01：餐饮、交通、购物、日用）。
     * 用 ID 表达，避免按名称匹配。
     */
    val QUICK_PICK_EXPENSE_CATEGORY_IDS: List<String> = listOf(
        "cat_expense_food",
        "cat_expense_transport",
        "cat_expense_shopping",
        "cat_expense_daily",
        "cat_expense_housing",
    )
}
