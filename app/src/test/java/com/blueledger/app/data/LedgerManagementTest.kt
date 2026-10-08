package com.blueledger.app.data

import com.blueledger.app.core.model.AccountCommand
import com.blueledger.app.core.model.AccountKind
import com.blueledger.app.core.model.BudgetStatus
import com.blueledger.app.core.model.CategoryCommand
import com.blueledger.app.core.model.CategoryIcons
import com.blueledger.app.core.model.Defaults
import com.blueledger.app.core.model.TransactionDraft
import com.blueledger.app.core.model.TransactionType
import com.blueledger.app.core.model.ValidationCode
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth

/** 分类、账户、预算的写操作与业务约束（AI 提示词 §7 A1.4、PRD §9.2 B01—B05）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerManagementTest : LedgerRoomTestBase() {

    @Before
    fun seed() = runDb { seedAcceptanceFixture() }

    private fun categoryCommand(
        id: String? = null,
        type: TransactionType = TransactionType.EXPENSE,
        name: String = "宠物",
        iconKey: String = CategoryIcons.DAILY,
    ) = CategoryCommand(id = id, type = type, name = name, iconKey = iconKey)

    // ───────────────────── 分类 ─────────────────────

    @Test
    fun `category_name_is_trimmed_and_limited_to_12_chars`() = runDb {
        val id = repository.upsertCategory(categoryCommand(name = "  宠物  ")).requireId()
        val category = repository.observeCategories(TransactionType.EXPENSE, includeArchived = true).first()
            .first { it.id == id }
        assertEquals("宠物", category.name)

        repository.upsertCategory(categoryCommand(name = ""))
            .requireFailure().requireValidationCode(ValidationCode.CATEGORY_NAME_EMPTY)
        repository.upsertCategory(categoryCommand(name = " ".repeat(3)))
            .requireFailure().requireValidationCode(ValidationCode.CATEGORY_NAME_EMPTY)
        repository.upsertCategory(categoryCommand(name = "一".repeat(13)))
            .requireFailure().requireValidationCode(ValidationCode.CATEGORY_NAME_TOO_LONG)
        // 12 个字符可以保存
        repository.upsertCategory(categoryCommand(name = "一".repeat(12))).requireSuccess()
    }

    @Test
    fun `duplicate_category_name_within_type_is_rejected_including_archived`() = runDb {
        repository.upsertCategory(categoryCommand(name = "餐饮"))
            .requireFailure().requireValidationCode(ValidationCode.CATEGORY_NAME_DUPLICATE)

        // 归档分类也参与查重，避免恢复时出现歧义
        repository.setCategoryArchived(FOOD, true).requireSuccess()
        repository.upsertCategory(categoryCommand(name = "餐饮"))
            .requireFailure().requireValidationCode(ValidationCode.CATEGORY_NAME_DUPLICATE)

        // 收入与支出是两套命名空间
        repository.upsertCategory(categoryCommand(type = TransactionType.INCOME, name = "餐饮")).requireSuccess()
    }

    @Test
    fun `fallback_category_cannot_be_archived_and_type_keeps_a_selectable_category`() = runDb {
        repository.setCategoryArchived(Defaults.FALLBACK_EXPENSE_CATEGORY_ID, true)
            .requireFailure().requireValidationCode(ValidationCode.CATEGORY_FALLBACK_PROTECTED)

        // 兜底分类不可归档 ⇒「每类型至少一个可用分类」恒成立；
        // 因此归档全部非兜底分类是被允许的，剩余可选项正是兜底分类。
        val nonFallbackIds = repository.observeCategories(TransactionType.EXPENSE, includeArchived = true).first()
            .filter { !it.isFallback }
            .map { it.id }
        nonFallbackIds.forEach { repository.setCategoryArchived(it, true).requireSuccess() }

        val selectable = repository.observeCategories(TransactionType.EXPENSE, includeArchived = false).first()
        assertEquals(1, selectable.size)
        assertEquals(Defaults.FALLBACK_EXPENSE_CATEGORY_ID, selectable.single().id)
        assertTrue(selectable.single().isFallback)

        // 兜底分类是最后一道防线，任何情况下都不能归档
        repository.setCategoryArchived(Defaults.FALLBACK_EXPENSE_CATEGORY_ID, true)
            .requireFailure().requireValidationCode(ValidationCode.CATEGORY_FALLBACK_PROTECTED)

        // 归档不排除历史有效账单
        assertEquals(307_880L, repository.observeMonthSummary(october).first().expenseCent)
        assertEquals(
            listOf("住房", "交通", "餐饮"),
            repository.observeMonthAnalysis(october, TransactionType.EXPENSE).first().slices.map { it.name },
        )
    }

    @Test
    fun `category_type_is_immutable_and_icon_must_be_known`() = runDb {
        repository.upsertCategory(categoryCommand(id = FOOD, type = TransactionType.INCOME, name = "餐饮"))
            .requireFailure().requireValidationCode(ValidationCode.CATEGORY_TYPE_IMMUTABLE)

        repository.upsertCategory(categoryCommand(id = FOOD, name = "餐饮", iconKey = "not_an_icon"))
            .requireFailure().requireValidationCode(ValidationCode.ICON_KEY_INVALID)

        // 重命名后历史与统计统一使用新名称，ID 不变
        repository.upsertCategory(categoryCommand(id = FOOD, name = "吃饭")).requireSuccess()
        val slices = repository.observeMonthAnalysis(october, TransactionType.EXPENSE).first().slices
        assertEquals(listOf("住房", "交通", "吃饭"), slices.map { it.name })
        val t5 = repository.observeTransactions(
            com.blueledger.app.core.model.TransactionFilter(yearMonth = october),
        ).first().items.first { it.id == "T5" }
        assertEquals(FOOD, t5.transaction.categoryId)
        assertEquals("吃饭", t5.categoryName)
    }

    @Test
    fun `reorder_requires_full_list_and_updates_sort_order`() = runDb {
        val all = repository.observeCategories(TransactionType.EXPENSE, includeArchived = true).first()
        val ids = all.map { it.id }

        repository.reorderCategories(TransactionType.EXPENSE, ids.dropLast(1))
            .requireFailure().requireValidationCode(ValidationCode.CATEGORY_REORDER_INVALID)
        repository.reorderCategories(TransactionType.EXPENSE, ids + ids.first())
            .requireFailure().requireValidationCode(ValidationCode.CATEGORY_REORDER_INVALID)
        repository.reorderCategories(TransactionType.EXPENSE, ids.reversed()).requireSuccess()

        val reordered = repository.observeCategories(TransactionType.EXPENSE, includeArchived = true).first()
        assertEquals(ids.reversed(), reordered.map { it.id })

        // 新增分类排在最后
        val newId = repository.upsertCategory(categoryCommand(name = "宠物")).requireId()
        val afterInsert = repository.observeCategories(TransactionType.EXPENSE, includeArchived = true).first()
        assertEquals(newId, afterInsert.last().id)
    }

    @Test
    fun `archived_category_keeps_history_and_ties_break_by_sort_order`() = runDb {
        // 归档不排除历史有效账单
        repository.setCategoryArchived(TRANSPORT, true).requireSuccess()
        val slices = repository.observeMonthAnalysis(october, TransactionType.EXPENSE).first().slices
        assertEquals(listOf("住房", "交通", "餐饮"), slices.map { it.name })
        assertEquals(listOf(300_000L, 5_000L, 2_880L), slices.map { it.amountCent })

        // 同额时按 sortOrder 再按 id 稳定排序：交通 sortOrder=10 在宠物（新建，排在最后）之前
        val petId = repository.upsertCategory(categoryCommand(name = "宠物")).requireId()
        repository.createTransaction(
            TransactionDraft(
                type = TransactionType.EXPENSE,
                amountCent = 5_000L,
                categoryId = petId,
                accountId = fixtureIds.accountB,
                occurredOn = LocalDate.of(2026, 10, 3),
                note = "打车",
            ),
            "req-tie",
        ).requireSaved()
        val tied = repository.observeMonthAnalysis(october, TransactionType.EXPENSE).first().slices
        assertEquals(listOf("住房", "交通", "宠物", "餐饮"), tied.map { it.name })
        assertEquals(listOf(300_000L, 5_000L, 5_000L, 2_880L), tied.map { it.amountCent })
    }

    // ───────────────────── 账户 ─────────────────────

    @Test
    fun `account_name_rules_and_negative_opening_balance`() = runDb {
        repository.upsertAccount(AccountCommand(name = "  ", kind = AccountKind.CASH, openingBalanceCent = 0L))
            .requireFailure().requireValidationCode(ValidationCode.ACCOUNT_NAME_EMPTY)
        repository.upsertAccount(AccountCommand(name = "名".repeat(21), kind = AccountKind.CASH, openingBalanceCent = 0L))
            .requireFailure().requireValidationCode(ValidationCode.ACCOUNT_NAME_TOO_LONG)
        repository.upsertAccount(AccountCommand(name = "A银行卡", kind = AccountKind.CASH, openingBalanceCent = 0L))
            .requireFailure().requireValidationCode(ValidationCode.ACCOUNT_NAME_DUPLICATE)
        repository.upsertAccount(
            AccountCommand(name = "超额", kind = AccountKind.CASH, openingBalanceCent = 1_000_000_000L),
        ).requireFailure().requireValidationCode(ValidationCode.OPENING_BALANCE_OUT_OF_RANGE)

        // 期初余额允许负数：账户余额变化，但收支摘要完全不变
        repository.upsertAccount(
            AccountCommand(id = fixtureIds.accountB, name = "B钱包", kind = AccountKind.E_WALLET, openingBalanceCent = -20_000L),
        ).requireSuccess()
        val accountB = repository.observeAccounts(includeArchived = true).first().first { it.account.name == "B钱包" }
        assertEquals(52_120L, accountB.balanceCent)
        assertEquals(-20_000L, accountB.openingBalanceCent)

        val octoberSummary = repository.observeMonthSummary(october).first()
        assertEquals(1_080_000L, octoberSummary.incomeCent)
        assertEquals(307_880L, octoberSummary.expenseCent)
        assertEquals(1_580_000L, repository.observeYearAnalysis(2026).first().incomeCent)
    }

    @Test
    fun `last_active_account_cannot_be_archived`() = runDb {
        // 夹具里共有 3 个可用账户：默认账户、A、B
        repository.setAccountArchived(DEFAULT_ACCOUNT_ID, true, fixtureIds.accountA).requireSuccess()
        repository.setAccountArchived(fixtureIds.accountB, true, null).requireSuccess()
        repository.setAccountArchived(fixtureIds.accountA, true, null)
            .requireFailure().requireValidationCode(ValidationCode.ACCOUNT_LAST_ACTIVE)
    }

    @Test
    fun `archiving_default_account_requires_replacement`() = runDb {
        repository.setAccountArchived(DEFAULT_ACCOUNT_ID, true, null)
            .requireFailure().requireValidationCode(ValidationCode.ACCOUNT_ARCHIVE_REQUIRES_REPLACEMENT)
        repository.setAccountArchived(DEFAULT_ACCOUNT_ID, true, "missing")
            .requireFailure().requireValidationCode(ValidationCode.ACCOUNT_NOT_FOUND_FOR_DEFAULT)

        repository.setAccountArchived(DEFAULT_ACCOUNT_ID, true, fixtureIds.accountA).requireSuccess()
        val settings = repository.observeSettings().first()
        assertEquals(fixtureIds.accountA, settings.defaultAccountId)
    }

    @Test
    fun `unarchiving_account_with_duplicate_name_returns_conflict`() = runDb {
        // 归档 B，再用同名新建一个账户，然后尝试恢复归档账户 → Conflict
        repository.setAccountArchived(fixtureIds.accountB, true, null).requireSuccess()
        repository.upsertAccount(
            AccountCommand(name = "B钱包", kind = AccountKind.E_WALLET, openingBalanceCent = 0L),
        ).requireSuccess()

        repository.setAccountArchived(fixtureIds.accountB, false, null)
            .requireFailure().requireValidationCode(ValidationCode.ACCOUNT_NAME_DUPLICATE)

        // 重命名后可以恢复
        repository.upsertAccount(
            AccountCommand(id = fixtureIds.accountB, name = "B钱包旧", kind = AccountKind.E_WALLET, openingBalanceCent = 20_000L),
        ).requireSuccess()
        repository.setAccountArchived(fixtureIds.accountB, false, null).requireSuccess()
    }

    @Test
    fun `archived_account_name_can_be_reused_by_a_new_active_account`() = runDb {
        repository.setAccountArchived(fixtureIds.accountB, true, null).requireSuccess()
        val newId = repository.upsertAccount(
            AccountCommand(name = "B钱包", kind = AccountKind.CASH, openingBalanceCent = 0L),
        ).requireId()
        assertTrue(newId.isNotEmpty())
    }

    @Test
    fun `default_account_must_be_active_and_exist`() = runDb {
        repository.setDefaultAccount("missing")
            .requireFailure().requireValidationCode(ValidationCode.ACCOUNT_NOT_FOUND_FOR_DEFAULT)
        repository.setAccountArchived(fixtureIds.accountB, true, null).requireSuccess()
        repository.setDefaultAccount(fixtureIds.accountB)
            .requireFailure().requireValidationCode(ValidationCode.ACCOUNT_NOT_FOUND_FOR_DEFAULT)
        repository.setDefaultAccount(fixtureIds.accountA).requireSuccess()
        assertEquals(fixtureIds.accountA, repository.observeSettings().first().defaultAccountId)
    }

    // ───────────────────── 预算 ─────────────────────

    @Test
    fun `budget_is_per_month_and_requires_positive_amount`() = runDb {
        repository.setBudget(YearMonth.of(2026, 9), 0L)
            .requireFailure().requireValidationCode(ValidationCode.BUDGET_NOT_POSITIVE)
        repository.setBudget(YearMonth.of(2026, 9), -1L)
            .requireFailure().requireValidationCode(ValidationCode.BUDGET_NOT_POSITIVE)
        repository.setBudget(YearMonth.of(2026, 9), 1_000_000_000L)
            .requireFailure().requireValidationCode(ValidationCode.BUDGET_OUT_OF_RANGE)

        // 九月独立预算，不影响十月
        repository.setBudget(YearMonth.of(2026, 9), 100_000L).requireSuccess()
        assertEquals(100_000L, repository.observeBudget(september).first().budgetCent)
        assertEquals(400_000L, repository.observeBudget(october).first().budgetCent)

        // 修改十月不改九月
        repository.setBudget(october, 500_000L).requireSuccess()
        assertEquals(100_000L, repository.observeBudget(september).first().budgetCent)
        assertEquals(500_000L, repository.observeBudget(october).first().budgetCent)
    }

    @Test
    fun `budget_status_covers_normal_near_limit_exhausted_and_exceeded`() = runDb {
        // 十月已用 307,880 分
        repository.setBudget(october, 307_880L * 10).requireSuccess()
        assertEquals(BudgetStatus.NORMAL, repository.observeBudget(october).first().status)

        // 80% 接近预算：307880 / 384850 = 80.0%
        repository.setBudget(october, 384_850L).requireSuccess()
        assertEquals(BudgetStatus.NEAR_LIMIT, repository.observeBudget(october).first().status)

        // 刚好用完
        repository.setBudget(october, 307_880L).requireSuccess()
        val exhausted = repository.observeBudget(october).first()
        assertEquals(BudgetStatus.EXHAUSTED, exhausted.status)
        assertEquals(0L, exhausted.remainingCent)
        assertNull(exhausted.exceededCent)
        assertEquals(10_000L, exhausted.ratioBasisPoint)

        // 超支：进度条封顶 1.0，文字保留真实比例
        repository.setBudget(october, 200_000L).requireSuccess()
        val exceeded = repository.observeBudget(october).first()
        assertEquals(BudgetStatus.EXCEEDED, exceeded.status)
        assertEquals(107_880L, exceeded.exceededCent)
        assertEquals(-107_880L, exceeded.remainingCent)
        assertEquals(15_394L, exceeded.ratioBasisPoint)
        assertEquals(1.0f, exceeded.progressFraction!!, 0.0001f)
    }

    @Test
    fun `removing_budget_returns_to_unset_without_touching_other_months`() = runDb {
        repository.setBudget(september, 100_000L).requireSuccess()
        repository.removeBudget(october).requireSuccess()
        assertNull(repository.observeBudget(october).first().budgetCent)
        assertEquals(BudgetStatus.UNSET, repository.observeBudget(october).first().status)
        assertEquals(100_000L, repository.observeBudget(september).first().budgetCent)

        // 移除不存在的预算也是幂等成功
        repository.removeBudget(YearMonth.of(2030, 1)).requireSuccess()
    }

    @Test
    fun `budget_does_not_block_expense_saving`() = runDb {
        repository.setBudget(october, 1L).requireSuccess()
        repository.createTransaction(
            com.blueledger.app.core.model.TransactionDraft(
                type = TransactionType.EXPENSE,
                amountCent = 100_000L,
                categoryId = FOOD,
                accountId = fixtureIds.accountB,
                occurredOn = today,
                note = "超预算也要能记",
            ),
            "req-over-budget",
        ).requireSaved()
        assertEquals(BudgetStatus.EXCEEDED, repository.observeBudget(october).first().status)
    }

    @Test
    fun `budget_state_observes_expense_changes`() = runDb {
        val before = repository.observeBudget(october).first()
        assertEquals(307_880L, before.usedCent)
        repository.softDeleteTransaction("T9").requireValue()
        assertEquals(305_030L, repository.observeBudget(october).first().usedCent)
    }
}
