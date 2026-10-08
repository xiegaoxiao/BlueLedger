package com.blueledger.app.app.navigation

import android.net.Uri
import com.blueledger.app.core.model.EntryLaunch
import com.blueledger.app.core.model.StatisticsTab
import com.blueledger.app.core.model.TransactionFilterSeed
import com.blueledger.app.core.model.TransactionType
import java.time.YearMonth

/**
 * 导航 route 与参数（总控独占，冻结）。
 *
 * 设计原则：
 * - 二级任务（记账、详情、管理）用显式参数，返回来源与编辑 id 都写在 route 上，
 *   不依赖任何全局变量碰巧保留。
 * - 一级导航 4 个 route 不带参数（见 [TOP_LEVEL_ROUTES]）；从统计/账户/首页钻取到
 *   账单列表时，把筛选种子编码进 [TRANSACTIONS] 的可选参数。
 * - 所有可选字符串参数用 [encode] 转义；null 一律省略而不是写 "null"。
 *
 * 注意：发生日期**永远不通过导航传递**。新增账单的日期一律取设备今日，
 * 浏览历史月份不得改变记账日期（PRD S01 明确要求）。
 */
object Routes {

    const val OVERVIEW = "overview"
    const val TRANSACTIONS = "transactions"
    const val STATISTICS = "statistics"
    const val MINE = "mine"

    /** 二级页面。 */
    const val ENTRY = "entry"
    const val DETAIL = "detail"
    const val CATEGORIES = "categories"
    const val BUDGET = "budget"
    const val ACCOUNTS = "accounts"
    const val DATA = "data"

    /** 一级导航 route，底部栏高亮与「是否显示底部栏」据此判断。 */
    val TOP_LEVEL_ROUTES: Set<String> = setOf(OVERVIEW, TRANSACTIONS, STATISTICS, MINE)

    // ─────────────── 参数名 ───────────────
    const val ARG_TRANSACTION_ID = "transactionId"
    const val ARG_EDIT_ID = "editId"
    const val ARG_TYPE = "type"
    const val ARG_CATEGORY_ID = "categoryId"
    const val ARG_ACCOUNT_ID = "accountId"
    const val ARG_YEAR_MONTH = "yearMonth"
    const val ARG_QUERY = "query"
    const val ARG_TAB = "tab"
    const val ARG_YEAR = "year"

    // ─────────────── route 模板 ───────────────

    const val TRANSACTIONS_PATTERN =
        "$TRANSACTIONS?$ARG_YEAR_MONTH={$ARG_YEAR_MONTH}&$ARG_TYPE={$ARG_TYPE}" +
            "&$ARG_CATEGORY_ID={$ARG_CATEGORY_ID}&$ARG_ACCOUNT_ID={$ARG_ACCOUNT_ID}&$ARG_QUERY={$ARG_QUERY}"

    const val STATISTICS_PATTERN =
        "$STATISTICS?$ARG_TAB={$ARG_TAB}&$ARG_YEAR_MONTH={$ARG_YEAR_MONTH}&$ARG_YEAR={$ARG_YEAR}"

    const val ENTRY_PATTERN =
        "$ENTRY?$ARG_EDIT_ID={$ARG_EDIT_ID}&$ARG_TYPE={$ARG_TYPE}&$ARG_CATEGORY_ID={$ARG_CATEGORY_ID}"

    const val DETAIL_PATTERN = "$DETAIL/{$ARG_TRANSACTION_ID}"

    const val BUDGET_PATTERN = "$BUDGET?$ARG_YEAR_MONTH={$ARG_YEAR_MONTH}"

    // ─────────────── route 构造器 ───────────────

    /** 账单列表（可带钻取筛选种子）。 */
    fun transactions(seed: TransactionFilterSeed? = null): String {
        if (seed == null) return TRANSACTIONS
        val parts = buildList {
            seed.yearMonth?.let { add("$ARG_YEAR_MONTH=${encode(it.toString())}") }
            seed.type?.let { add("$ARG_TYPE=${encode(it.name)}") }
            seed.categoryId?.let { add("$ARG_CATEGORY_ID=${encode(it)}") }
            seed.accountId?.let { add("$ARG_ACCOUNT_ID=${encode(it)}") }
            if (seed.query.isNotBlank()) add("$ARG_QUERY=${encode(seed.query)}")
        }
        return if (parts.isEmpty()) TRANSACTIONS else "$TRANSACTIONS?" + parts.joinToString("&")
    }

    /** 统计页。 */
    fun statistics(
        tab: StatisticsTab? = null,
        yearMonth: YearMonth? = null,
        year: Int? = null,
    ): String {
        val parts = buildList {
            tab?.let { add("$ARG_TAB=${it.name}") }
            yearMonth?.let { add("$ARG_YEAR_MONTH=${encode(it.toString())}") }
            year?.let { add("$ARG_YEAR=$it") }
        }
        return if (parts.isEmpty()) STATISTICS else "$STATISTICS?" + parts.joinToString("&")
    }

    /** 新增/编辑记账。 */
    fun entry(
        editTransactionId: String? = null,
        launch: EntryLaunch? = null,
    ): String {
        val parts = buildList {
            editTransactionId?.let { add("$ARG_EDIT_ID=${encode(it)}") }
            launch?.type?.let { add("$ARG_TYPE=${it.name}") }
            launch?.categoryId?.let { add("$ARG_CATEGORY_ID=${encode(it)}") }
        }
        return if (parts.isEmpty()) ENTRY else "$ENTRY?" + parts.joinToString("&")
    }

    /** 账单详情。 */
    fun detail(transactionId: String): String = "$DETAIL/${encode(transactionId)}"

    /** 月度预算。 */
    fun budget(yearMonth: YearMonth? = null): String =
        if (yearMonth == null) BUDGET else "$BUDGET?$ARG_YEAR_MONTH=${encode(yearMonth.toString())}"

    // ─────────────── 参数解析 ───────────────

    fun parseYearMonth(raw: String?): YearMonth? =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { YearMonth.parse(it) }.getOrNull() }

    fun parseType(raw: String?): TransactionType? =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { TransactionType.valueOf(it) }.getOrNull() }

    fun parseTab(raw: String?): StatisticsTab? =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { StatisticsTab.valueOf(it) }.getOrNull() }

    fun parseSeed(
        yearMonth: String?,
        type: String?,
        categoryId: String?,
        accountId: String?,
        query: String?,
    ): TransactionFilterSeed = TransactionFilterSeed(
        yearMonth = parseYearMonth(yearMonth),
        type = parseType(type),
        categoryId = categoryId?.takeIf { it.isNotBlank() },
        accountId = accountId?.takeIf { it.isNotBlank() },
        query = query.orEmpty(),
    )

    fun encode(value: String): String = Uri.encode(value)
}
