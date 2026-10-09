package com.blueledger.app.core.model

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.YearMonth

/** 本地高级功能，与账本同库保存并进入完整备份。 */
@Serializable
data class AdvancedLedgerSettings(
    val monthStartDay: Int = 1,
    val categoryBudgets: List<CategoryBudgetRule> = emptyList(),
    val tags: List<LedgerTag> = emptyList(),
    val transactionTags: Map<String, List<String>> = emptyMap(),
    val recurringRules: List<RecurringLedgerRule> = emptyList(),
)

@Serializable
data class CategoryBudgetRule(val categoryId: String, val amountCent: Long)

@Serializable
data class LedgerTag(val id: String, val name: String)

@Serializable
enum class RecurrenceFrequency { DAILY, WEEKLY, MONTHLY, YEARLY }

@Serializable
data class RecurringLedgerRule(
    val id: String,
    val name: String,
    val type: String,
    val amountCent: Long,
    val categoryId: String,
    val accountId: String,
    val note: String = "",
    val startDate: String,
    val nextDate: String,
    val frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
    val interval: Int = 1,
    val enabled: Boolean = true,
    val lastError: String? = null,
) {
    /** 使用原始日期作月/年锚点，避免 31 日经过短月后永久漂移成 28 日。 */
    fun following(date: LocalDate): LocalDate = when (frequency) {
        RecurrenceFrequency.DAILY -> date.plusDays(interval.toLong())
        RecurrenceFrequency.WEEKLY -> date.plusWeeks(interval.toLong())
        RecurrenceFrequency.MONTHLY -> YearMonth.from(date).plusMonths(interval.toLong()).let {
            it.atDay(minOf(LocalDate.parse(startDate).dayOfMonth, it.lengthOfMonth()))
        }
        RecurrenceFrequency.YEARLY -> LocalDate.parse(startDate).let {
            val month = YearMonth.of(date.year + interval, it.monthValue)
            month.atDay(minOf(it.dayOfMonth, month.lengthOfMonth()))
        }
    }
}

object LedgerPeriods {
    fun start(month: YearMonth, day: Int): LocalDate = month.atDay(minOf(day.coerceIn(1, 31), month.lengthOfMonth()))
    fun range(month: YearMonth, day: Int): ClosedRange<LocalDate> = start(month, day)..start(month.plusMonths(1), day).minusDays(1)
    fun monthOf(date: LocalDate, day: Int): YearMonth = YearMonth.from(date).let { if (date < start(it, day)) it.minusMonths(1) else it }
}

/** 写入和恢复共同使用；任何非法引用或配置都拒绝整次修改。 */
fun AdvancedLedgerSettings.validationError(
    categories: List<LedgerCategory>, accounts: List<LedgerAccount>, transactionIds: Set<String>,
): String? {
    if (monthStartDay !in 1..31) return "月起始日必须为 1—31"
    if (tags.size > 2000 || categoryBudgets.size > 2000 || recurringRules.size > 1000 || transactionTags.size > 100_000) return "高级设置条目超过上限"
    if (tags.any { it.id.isBlank() || it.id.length > 100 || it.name.trim().isEmpty() || it.name.length > 20 }) return "标签名称必须为 1—20 字符"
    if (tags.map { it.id }.distinct().size != tags.size || tags.map { it.name.trim().lowercase() }.distinct().size != tags.size) return "标签 ID 或名称重复"
    val tagIds = tags.map { it.id }.toSet()
    if (transactionTags.any { (id, ids) -> id !in transactionIds || ids.size > 20 || ids.distinct().size != ids.size || ids.any { it !in tagIds } }) return "账单标签引用不合法"
    if (categoryBudgets.map { it.categoryId }.distinct().size != categoryBudgets.size) return "分类预算重复"
    if (categoryBudgets.any { b -> b.amountCent !in 1..Limits.MAX_BUDGET_CENT || categories.none { it.id == b.categoryId && it.type.isExpense } }) return "分类预算金额或分类不合法"
    if (recurringRules.map { it.id }.distinct().size != recurringRules.size) return "周期规则 ID 重复"
    for (rule in recurringRules) {
        if (rule.id.isBlank() || rule.id.length > 100 || rule.name.trim().isEmpty() || rule.name.length > 40 || rule.interval !in 1..365 || rule.amountCent !in 1..Limits.MAX_TRANSACTION_CENT || rule.note.length > Limits.MAX_NOTE_LENGTH) return "周期规则名称、间隔或金额不合法"
        val type = runCatching { TransactionType.valueOf(rule.type) }.getOrNull() ?: return "周期规则收支类型不合法"
        if (categories.none { it.id == rule.categoryId && it.type == type } || accounts.none { it.id == rule.accountId }) return "周期规则引用不存在"
        val from = runCatching { LocalDate.parse(rule.startDate) }.getOrNull() ?: return "周期开始日期不合法"
        val next = runCatching { LocalDate.parse(rule.nextDate) }.getOrNull() ?: return "周期下次日期不合法"
        if (from.year !in 1900..9998 || next < from || next.year !in 1900..9998) return "周期日期超出范围"
        if (rule.lastError != null && rule.lastError.length > 200) return "周期错误说明过长"
    }
    return null
}
