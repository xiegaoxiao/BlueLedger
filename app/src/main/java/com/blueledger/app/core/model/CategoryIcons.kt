package com.blueledger.app.core.model

/**
 * 分类图标 key 的唯一权威列表。
 *
 * 用途：
 * - A2 的 core/designsystem 依此把 iconKey 映射为 Compose 图标（未知 key 必须有兜底图标）。
 * - A1 的数据层在写入分类时校验 key 合法。
 * - A6 的备份校验把非法 iconKey 作为拒绝理由（[ValidationCode.BACKUP_ICON_INVALID]）。
 *
 * 因此这个列表**只能由总控修改**；新增图标必须同时通知 A2（补映射）与 A1/A6（校验自动跟随）。
 */
object CategoryIcons {

    // 支出
    const val RESTAURANT = "restaurant"
    const val TRANSPORT = "transport"
    const val SHOPPING = "shopping"
    const val DAILY = "daily"
    const val HOUSING = "housing"
    const val ENTERTAINMENT = "entertainment"
    const val MEDICAL = "medical"
    const val STUDY = "study"
    const val CATEGORY_OTHER = "category_other"

    // 收入
    const val SALARY = "salary"
    const val BONUS = "bonus"
    const val PART_TIME = "part_time"
    const val GIFT = "gift"
    const val INCOME_OTHER = "income_other"

    /** 新建分类时可选的图标（用户可选集合，顺序即选择面板顺序）。 */
    val SELECTABLE: List<String> = listOf(
        RESTAURANT, TRANSPORT, SHOPPING, DAILY, HOUSING,
        ENTERTAINMENT, MEDICAL, STUDY,
        SALARY, BONUS, PART_TIME, GIFT,
        CATEGORY_OTHER, INCOME_OTHER,
        "vegetables", "fruit", "snacks", "sports", "phone", "clothes", "beauty", "household", "children", "elders", "social", "travel", "tobacco", "digital", "car", "books", "pet", "cashgift", "present", "office", "investment",
    )

    private val ALL: Set<String> = SELECTABLE.toSet()

    /** 兜底：未知 key 用哪个图标渲染。 */
    const val FALLBACK: String = CATEGORY_OTHER

    fun isValidKey(key: String): Boolean = key in ALL

    /** 把任意输入收敛为合法 key，用于渲染兜底（不用于写入校验）。 */
    fun coerceForDisplay(key: String): String = if (isValidKey(key)) key else FALLBACK
}
