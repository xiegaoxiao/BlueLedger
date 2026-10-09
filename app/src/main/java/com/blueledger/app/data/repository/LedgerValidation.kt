package com.blueledger.app.data.repository

import com.blueledger.app.core.model.validationError
import com.blueledger.app.core.model.AccountKind
import com.blueledger.app.core.model.CategoryIcons
import com.blueledger.app.core.model.FieldRef
import com.blueledger.app.core.model.LedgerCategory
import com.blueledger.app.core.model.LedgerError
import com.blueledger.app.core.model.LedgerSettings
import com.blueledger.app.core.model.LedgerSnapshot
import com.blueledger.app.core.model.LedgerTransaction
import com.blueledger.app.core.model.Limits
import com.blueledger.app.core.model.TransactionType
import com.blueledger.app.core.model.ValidationCode
import com.blueledger.app.core.time.DateRanges
import java.time.LocalDate

/**
 * 数据层业务校验（不信任 UI）。
 *
 * 这里实现的是**业务边界**：UI 的即时反馈不能替代本层校验，
 * 备份恢复写入前也必须再过一遍同样的规则。
 */
internal object LedgerValidation {

    /** 归一化键：trim + lowercase（与语言环境无关），用于重名判断与包含搜索。 */
    fun normalizeKey(text: String): String = text.trim().lowercase()

    /** SQL LIKE 转义：`%`、`_`、`\` 都按普通字符处理。 */
    fun escapeLike(text: String): String {
        val builder = StringBuilder(text.length + 8)
        for (ch in text) {
            if (ch == '\\' || ch == '%' || ch == '_') builder.append('\\')
            builder.append(ch)
        }
        return builder.toString()
    }

    // ───────────────────────── 账单 ─────────────────────────

    fun amount(amountCent: Long): LedgerError.Validation? = when {
        // 负金额必须与「零金额」区分：§4.2 要求空/零/负/超限分别给出可理解提示。
        amountCent < 0L ->
            LedgerError.Validation(ValidationCode.AMOUNT_NEGATIVE, "金额必须大于 0", FieldRef.AMOUNT)

        amountCent < Limits.MIN_TRANSACTION_CENT ->
            LedgerError.Validation(ValidationCode.AMOUNT_ZERO, "金额必须大于 0", FieldRef.AMOUNT)

        amountCent > Limits.MAX_TRANSACTION_CENT ->
            LedgerError.Validation(
                ValidationCode.AMOUNT_OUT_OF_RANGE,
                "金额不能超过 9,999,999.99 元",
                FieldRef.AMOUNT,
            )

        else -> null
    }

    fun occurredOn(date: LocalDate, today: LocalDate): LedgerError.Validation? = when {
        !DateRanges.isSupportedDate(date) ->
            LedgerError.Validation(ValidationCode.DATE_INVALID, "日期不合法", FieldRef.DATE)

        date.isAfter(today) ->
            LedgerError.Validation(ValidationCode.DATE_FUTURE, "日期不能晚于今天", FieldRef.DATE)

        else -> null
    }

    fun note(note: String): LedgerError.Validation? =
        if (note.length > Limits.MAX_NOTE_LENGTH) {
            LedgerError.Validation(
                ValidationCode.NOTE_TOO_LONG,
                "备注不能超过 ${Limits.MAX_NOTE_LENGTH} 个字符",
                FieldRef.NOTE,
            )
        } else {
            null
        }

    // ───────────────────────── 分类 ─────────────────────────

    fun categoryName(trimmedName: String): LedgerError.Validation? = when {
        trimmedName.isEmpty() ->
            LedgerError.Validation(ValidationCode.CATEGORY_NAME_EMPTY, "请输入分类名称", FieldRef.NAME)

        trimmedName.length > Limits.MAX_CATEGORY_NAME_LENGTH ->
            LedgerError.Validation(
                ValidationCode.CATEGORY_NAME_TOO_LONG,
                "分类名称不能超过 ${Limits.MAX_CATEGORY_NAME_LENGTH} 个字符",
                FieldRef.NAME,
            )

        else -> null
    }

    fun iconKey(iconKey: String): LedgerError.Validation? =
        if (!CategoryIcons.isValidKey(iconKey)) {
            LedgerError.Validation(ValidationCode.ICON_KEY_INVALID, "请选择有效图标", FieldRef.ICON)
        } else {
            null
        }

    // ───────────────────────── 账户 ─────────────────────────

    fun accountName(trimmedName: String): LedgerError.Validation? = when {
        trimmedName.isEmpty() ->
            LedgerError.Validation(ValidationCode.ACCOUNT_NAME_EMPTY, "请输入账户名称", FieldRef.NAME)

        trimmedName.length > Limits.MAX_ACCOUNT_NAME_LENGTH ->
            LedgerError.Validation(
                ValidationCode.ACCOUNT_NAME_TOO_LONG,
                "账户名称不能超过 ${Limits.MAX_ACCOUNT_NAME_LENGTH} 个字符",
                FieldRef.NAME,
            )

        else -> null
    }

    fun openingBalance(amountCent: Long): LedgerError.Validation? =
        if (amountCent > Limits.MAX_OPENING_BALANCE_CENT || amountCent < -Limits.MAX_OPENING_BALANCE_CENT) {
            LedgerError.Validation(
                ValidationCode.OPENING_BALANCE_OUT_OF_RANGE,
                "期初余额超出允许范围",
                FieldRef.OPENING_BALANCE,
            )
        } else {
            null
        }

    // ───────────────────────── 预算 ─────────────────────────

    fun budget(amountCent: Long): LedgerError.Validation? = when {
        amountCent <= 0L ->
            LedgerError.Validation(ValidationCode.BUDGET_NOT_POSITIVE, "预算必须大于 0", FieldRef.BUDGET)

        amountCent > Limits.MAX_BUDGET_CENT ->
            LedgerError.Validation(ValidationCode.BUDGET_OUT_OF_RANGE, "预算超出上限", FieldRef.BUDGET)

        else -> null
    }
}

/**
 * 恢复写入前的**二次校验**。
 *
 * 备份编解码器已经校验过一次，但 `ValidatedLedgerSnapshot` 是可被构造的类型，
 * 数据层不单方面信任它：写入事务内再次核对日期、引用与业务限制，任何一项失败都拒绝写入并保留旧库。
 */
internal object SnapshotValidator {

    fun validate(snapshot: LedgerSnapshot, today: LocalDate): LedgerError? {
        if (snapshot.currency != LedgerSettings.CURRENCY_CNY) {
            return backup(ValidationCode.BACKUP_CURRENCY_MISMATCH, "备份币种不是 CNY")
        }

        val categoryById = HashMap<String, LedgerCategory>(snapshot.categories.size)
        for (category in snapshot.categories) {
            if (category.id.isBlank()) {
                return backup(ValidationCode.BACKUP_FIELD_TYPE_INVALID, "分类 ID 为空")
            }
            if (categoryById.put(category.id, category) != null) {
                return backup(ValidationCode.BACKUP_DUPLICATE_ID, "备份中存在重复的分类 ID")
            }
        }

        val accountById = HashMap<String, com.blueledger.app.core.model.LedgerAccount>(snapshot.accounts.size)
        for (account in snapshot.accounts) {
            if (account.id.isBlank()) {
                return backup(ValidationCode.BACKUP_FIELD_TYPE_INVALID, "账户 ID 为空")
            }
            if (accountById.put(account.id, account) != null) {
                return backup(ValidationCode.BACKUP_DUPLICATE_ID, "备份中存在重复的账户 ID")
            }
        }

        validateCategories(snapshot)?.let { return it }
        validateAccounts(snapshot, today)?.let { return it }
        validateTransactions(snapshot.copy(transactions = snapshot.transactions + snapshot.recycleBin), categoryById, today)?.let { return it }
        // 时间来自设备墙钟，回拨后删除时间可早于创建时间；不可因此拒绝合法旧账本。
        if (snapshot.recycleBin.any { it.deletedAt == null }) return backup(ValidationCode.BACKUP_DATE_INVALID, "回收站缺少删除时间")
        snapshot.advanced.validationError(snapshot.categories, snapshot.accounts, (snapshot.transactions + snapshot.recycleBin).map { it.id }.toSet())?.let {
            return backup(ValidationCode.BACKUP_FIELD_TYPE_INVALID, it)
        }
        validateBudgets(snapshot)?.let { return it }

        val defaultAccount = accountById[snapshot.settings.defaultAccountId]
            ?: return backup(ValidationCode.BACKUP_DEFAULT_ACCOUNT_INVALID, "默认账户不存在")
        if (defaultAccount.isArchived) {
            return backup(ValidationCode.BACKUP_DEFAULT_ACCOUNT_INVALID, "默认账户已被归档")
        }
        if (snapshot.settings.currency != LedgerSettings.CURRENCY_CNY) {
            return backup(ValidationCode.BACKUP_CURRENCY_MISMATCH, "设置币种不是 CNY")
        }
        snapshot.settings.monthlyBudgetCent?.let {
            if (LedgerValidation.budget(it) != null) return backup(ValidationCode.BACKUP_AMOUNT_OUT_OF_RANGE, "每月预算金额超出范围")
        }
        return null
    }

    private fun validateCategories(snapshot: LedgerSnapshot): LedgerError? {
        val seenKeys = HashSet<String>(snapshot.categories.size)
        val hasFallback = HashMap<TransactionType, Boolean>()
        for (category in snapshot.categories) {
            val name = category.name.trim()
            LedgerValidation.categoryName(name)?.let {
                return backup(ValidationCode.BACKUP_NAME_CONSTRAINT, "分类名称不满足 1—12 字符约束")
            }
            LedgerValidation.iconKey(category.iconKey)?.let {
                return backup(ValidationCode.BACKUP_ICON_INVALID, "分类图标不合法：${category.iconKey}")
            }
            val key = "${category.type.name}#${LedgerValidation.normalizeKey(category.name)}"
            if (!seenKeys.add(key)) {
                return backup(ValidationCode.BACKUP_NAME_CONSTRAINT, "同类型分类名称重复：$name")
            }
            if (category.isFallback) hasFallback[category.type] = true
        }
        for (type in TransactionType.entries) {
            if (hasFallback[type] != true) {
                return backup(ValidationCode.BACKUP_NO_FALLBACK_CATEGORY, "缺少${typeLabel(type)}兜底分类")
            }
        }
        return null
    }

    private fun validateAccounts(snapshot: LedgerSnapshot, today: LocalDate): LedgerError? {
        val activeNames = HashSet<String>(snapshot.accounts.size)
        var activeCount = 0
        for (account in snapshot.accounts) {
            val name = account.name.trim()
            LedgerValidation.accountName(name)?.let {
                return backup(ValidationCode.BACKUP_NAME_CONSTRAINT, "账户名称不满足 1—20 字符约束")
            }
            if (account.kind !in AccountKind.entries) {
                return backup(ValidationCode.BACKUP_FIELD_TYPE_INVALID, "账户类型不合法")
            }
            LedgerValidation.openingBalance(account.openingBalanceCent)?.let {
                return backup(ValidationCode.BACKUP_AMOUNT_OUT_OF_RANGE, "期初余额超出范围")
            }
            if (account.note.length > Limits.MAX_NOTE_LENGTH || runCatching {
                val history = com.blueledger.app.core.model.AssetHistory.parse(account.openingHistory)
                history.all { !it.first.isAfter(today) } && (history.isEmpty() || history.last().second == account.openingBalanceCent)
            }.getOrDefault(false).not()) {
                return backup(ValidationCode.BACKUP_FIELD_TYPE_INVALID, "资产备注或余额历史不合法")
            }
            if (!account.isArchived) {
                activeCount += 1
                if (!activeNames.add(LedgerValidation.normalizeKey(account.name))) {
                    return backup(ValidationCode.BACKUP_NAME_CONSTRAINT, "可用账户名称重复：$name")
                }
            }
        }
        if (activeCount == 0) {
            return backup(ValidationCode.BACKUP_NO_ACTIVE_ACCOUNT, "备份中没有可用的未归档账户")
        }
        return null
    }

    private fun validateTransactions(
        snapshot: LedgerSnapshot,
        categoryById: Map<String, LedgerCategory>,
        today: LocalDate,
    ): LedgerError? {
        val seenIds = HashSet<String>(snapshot.transactions.size)
        for (transaction in snapshot.transactions) {
            if (transaction.id.isBlank() || !seenIds.add(transaction.id)) {
                return backup(ValidationCode.BACKUP_DUPLICATE_ID, "账单 ID 为空或重复")
            }
            LedgerValidation.amount(transaction.amountCent)?.let {
                return backup(ValidationCode.BACKUP_AMOUNT_OUT_OF_RANGE, "账单金额超出 1..999,999,999 分")
            }
            LedgerValidation.note(transaction.note)?.let {
                return backup(ValidationCode.BACKUP_FIELD_TYPE_INVALID, "备注超过 200 字符")
            }
            if (!DateRanges.isSupportedDate(transaction.occurredOn)) {
                return backup(ValidationCode.BACKUP_DATE_INVALID, "发生日期不合法")
            }
            if (transaction.occurredOn.isAfter(today)) {
                return backup(ValidationCode.BACKUP_DATE_FUTURE, "发生日期不能晚于今天")
            }
            val category = categoryById[transaction.categoryId]
                ?: return backup(ValidationCode.BACKUP_MISSING_REFERENCE, "账单引用了不存在的分类")
            if (category.type != transaction.type) {
                return backup(ValidationCode.BACKUP_CATEGORY_TYPE_MISMATCH, "账单类型与分类类型不一致")
            }
            if (!accountExists(snapshot, transaction.accountId)) {
                return backup(ValidationCode.BACKUP_MISSING_REFERENCE, "账单引用了不存在的账户")
            }
        }
        return null
    }

    private fun validateBudgets(snapshot: LedgerSnapshot): LedgerError? {
        val seenMonths = HashSet<String>(snapshot.budgets.size)
        for (budget in snapshot.budgets) {
            if (!seenMonths.add(budget.yearMonth.toString())) {
                return backup(ValidationCode.BACKUP_BUDGET_DUPLICATE_MONTH, "同一月份出现多条预算")
            }
            LedgerValidation.budget(budget.amountCent)?.let {
                return backup(ValidationCode.BACKUP_AMOUNT_OUT_OF_RANGE, "预算金额必须大于 0 且不超上限")
            }
        }
        return null
    }

    private fun accountExists(snapshot: LedgerSnapshot, accountId: String): Boolean =
        snapshot.accounts.any { it.id == accountId }

    private fun typeLabel(type: TransactionType): String =
        if (type == TransactionType.EXPENSE) "支出" else "收入"

    private fun backup(code: ValidationCode, message: String): LedgerError.Backup =
        LedgerError.Backup(code = code, message = message)
}

/** 账单载荷比较，用于同 requestId 的幂等判定。 */
internal fun LedgerTransaction.matchesDraft(
    type: TransactionType,
    amountCent: Long,
    categoryId: String,
    accountId: String,
    occurredOn: LocalDate,
    note: String,
): Boolean =
    this.type == type &&
        this.amountCent == amountCent &&
        this.categoryId == categoryId &&
        this.accountId == accountId &&
        this.occurredOn == occurredOn &&
        this.note == note
