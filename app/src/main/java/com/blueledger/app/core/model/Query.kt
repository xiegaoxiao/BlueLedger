package com.blueledger.app.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/**
 * 账单查询条件。日期、类型、分类、账户、搜索词按 AND 组合。
 *
 * 关于分页：
 * - [limit]/[offset] **只影响** [TransactionPageState.items] 的取回范围。
 * - [LedgerRepository.observeFilteredSummary] 必须忽略分页，
 *   对“同一谓词命中的全部记录”求和，不能只汇总已加载的那一页。
 *
 * 关于日期范围：[yearMonth] 与 [from]/[to] 同时出现时取交集；
 * 常规用法是二选一（月份栏用 [yearMonth]，账户详情等自定义区间用 [from]/[to]）。
 */
data class TransactionFilter(
    val yearMonth: YearMonth? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val type: TransactionType? = null,
    val categoryId: String? = null,
    val accountId: String? = null,
    val query: String = "",
    val limit: Int = DEFAULT_LIMIT,
    val offset: Int = 0,
) {
    /** 去掉分页的谓词，供汇总与计数复用。 */
    fun withoutPaging(): TransactionFilter = copy(limit = Int.MAX_VALUE, offset = 0)

    /** 是否没有任何附加筛选（月份不算附加筛选，见 PRD「重置」语义）。 */
    val hasExtraFilters: Boolean
        get() = type != null || categoryId != null || accountId != null || query.isNotBlank()

    companion object {
        const val DEFAULT_LIMIT: Int = 300
        const val PAGE_SIZE: Int = 50
    }
}

/**
 * 页面间钻取时携带的筛选种子。
 * 统计分类排行 → 账单列表，账户详情 → 账单列表，首页「查看全部」→ 账单列表。
 */
data class TransactionFilterSeed(
    val yearMonth: YearMonth? = null,
    val type: TransactionType? = null,
    val categoryId: String? = null,
    val accountId: String? = null,
    val query: String = "",
)

/**
 * 「记一笔」的启动参数。首页快捷分类传入 [categoryId] 并预选；
 * [type] 为 null 时使用默认（支出）。
 * 注意：绝不携带日期——发生日期永远默认设备今日，浏览历史月份不得改变记账日期。
 */
data class EntryLaunch(
    val type: TransactionType? = null,
    val categoryId: String? = null,
)

/** 统计页的两个分段。 */
enum class StatisticsTab { MONTH, YEAR }

/** 备份/导出的范围。 */
enum class CsvScope { CURRENT_MONTH, ALL }

/** 最近一次备份/导出的结果记录（仅用于界面显示，不参与业务统计）。 */
data class BackupRecord(
    val succeededAt: Instant?,
    val fileName: String?,
    val transactionCount: Int = 0,
)
