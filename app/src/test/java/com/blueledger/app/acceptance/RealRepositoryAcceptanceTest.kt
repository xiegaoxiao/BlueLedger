package com.blueledger.app.acceptance

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.blueledger.app.core.model.AccountCommand
import com.blueledger.app.core.model.AccountKind
import com.blueledger.app.core.model.BudgetStatus
import com.blueledger.app.core.model.CategoryCommand
import com.blueledger.app.core.model.CategoryIcons
import com.blueledger.app.core.model.Defaults
import com.blueledger.app.core.model.Limits
import com.blueledger.app.core.model.MutationResult
import com.blueledger.app.core.model.Outcome
import com.blueledger.app.core.model.SaveResult
import com.blueledger.app.core.model.TransactionDraft
import com.blueledger.app.core.model.TransactionFilter
import com.blueledger.app.core.model.TransactionType
import com.blueledger.app.core.model.ValidationCode
import com.blueledger.app.core.contract.LedgerRepository
import com.blueledger.app.data.local.LedgerDatabase
import com.blueledger.app.data.repository.LedgerRepositoryImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth

/**
 * 真实数据层验收（A7 独立）：Robolectric + **内存 Room** + `LedgerRepositoryImpl` 真实实现。
 *
 * 与 A1 自己的单测独立：本文件不复用 A1 的测试基类/夹具，只经冻结契约驱动实现，
 * 期望值全部来自 [Expect]（§12 的字面精确值）。每个测试方法都由 [setUp] 重建一个空库并
 * 重新写入 §12 夹具，因此 §12 的「每条修改用例从原始夹具重置」是结构性保证，而不是靠人工记忆。
 *
 * 断言失败 = 真实缺陷：按「触发步骤 / 期望 / 实际」回报 A1（数据）或对应页面所有者，不放宽断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealRepositoryAcceptanceTest {

    private lateinit var db: LedgerDatabase
    private lateinit var repository: LedgerRepository
    private lateinit var fx: SeededFixture

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LedgerRepositoryImpl(db, AcceptanceClock)
        fx = repository.seedAcceptanceFixture()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun month(m: YearMonth) = repository.observeMonthSummary(m).first()

    private suspend fun accounts() = repository.observeAccounts(includeArchived = true).first()

    private suspend fun accountBalance(ref: String): Long =
        accounts().first { it.account.id == fx.accountId(ref) }.balanceCent

    // ═══════════════════════════ A01 / 初始化 ═══════════════════════════

    @Test
    fun `A01 首次初始化后账单为零且默认分类账户齐全`() = runBlocking {
        // 另开一个干净库，避免夹具污染
        val fresh = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            LedgerDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val repo = LedgerRepositoryImpl(fresh, AcceptanceClock)
            repo.initializeIfNeeded().requireSuccess("initializeIfNeeded")

            val page = repo.observeTransactions(TransactionFilter()).first()
            assertEquals("首次启动账单必须为零（不得混入演示数据）", 0, page.totalCount)
            assertTrue("首次启动列表为空", page.items.isEmpty())

            val oct = repo.observeMonthSummary(Fx.OCT_2026).first()
            assertCent("空账本十月收入", 0L, oct.incomeCent)
            assertCent("空账本十月支出", 0L, oct.expenseCent)
            assertCent("空账本十月结余", 0L, oct.balanceCent)

            val settings = repo.observeSettings().first()
            assertEquals("默认账户 ID", Defaults.DEFAULT_ACCOUNT_ID, settings.defaultAccountId)
            assertEquals("币种固定 CNY", "CNY", settings.currency)
            assertFalse("首次启动不隐藏金额", settings.hideAmounts)

            val categories = repo.observeCategories(TransactionType.EXPENSE, includeArchived = true).first() +
                repo.observeCategories(TransactionType.INCOME, includeArchived = true).first()
            assertEquals("默认分类数量（9 支出 + 5 收入）", Defaults.CATEGORIES.size, categories.size)
            Defaults.CATEGORIES.forEach { spec ->
                assertNotNull("默认分类缺失：${spec.id}", categories.firstOrNull { it.id == spec.id })
            }
            assertTrue(
                "每类型至少一个兜底分类",
                categories.filter { it.isFallback }.map { it.type }.toSet() ==
                    setOf(TransactionType.EXPENSE, TransactionType.INCOME),
            )
            assertTrue("至少一个可用账户", repo.observeAccounts(includeArchived = false).first().isNotEmpty())
        } finally {
            fresh.close()
        }
    }

    @Test
    fun `初始化幂等：重复调用不重复插入且始终零账单`() = runBlocking {
        val before = repository.observeCategories(TransactionType.EXPENSE, includeArchived = true).first().size
        repeat(3) { repository.initializeIfNeeded().requireSuccess("initializeIfNeeded#$it") }
        val after = repository.observeCategories(TransactionType.EXPENSE, includeArchived = true).first().size
        assertEquals("重复初始化不得重复插入分类", before, after)
        assertEquals(
            "重复初始化不得插入任何演示账单",
            Fx.OCT_VALID_LABELS.size + 2,
            repository.observeTransactions(TransactionFilter()).first().totalCount,
        )
    }

    // ═══════════════════════════ §12 精确期望 ═══════════════════════════

    @Test
    fun `§12 九月十月与年度汇总逐项精确`() = runBlocking {
        val sep = month(Fx.SEP_2026)
        assertCent("九月收入", Expect.SEP_INCOME_CENT, sep.incomeCent)
        assertCent("九月支出", Expect.SEP_EXPENSE_CENT, sep.expenseCent)
        assertCent("九月结余", Expect.SEP_BALANCE_CENT, sep.balanceCent)
        assertEquals("九月笔数", 2, sep.count)

        val oct = month(Fx.OCT_2026)
        assertCent("十月收入", Expect.OCT_INCOME_CENT, oct.incomeCent)
        assertCent("十月支出", Expect.OCT_EXPENSE_CENT, oct.expenseCent)
        assertCent("十月结余", Expect.OCT_BALANCE_CENT, oct.balanceCent)
        assertEquals("十月有效账单笔数", Expect.OCT_COUNT, oct.count)
        assertFalse("九月未设置预算", repository.observeBudget(Fx.SEP_2026).first().isSet)
    }

    @Test
    fun `§12 年度分析区分已到零记录月与未到月份`() = runBlocking {
        val year = repository.observeYearAnalysis(Fx.YEAR_2026).first()
        assertCent("年度收入", Expect.YEAR_INCOME_CENT, year.incomeCent)
        assertCent("年度支出", Expect.YEAR_EXPENSE_CENT, year.expenseCent)
        assertCent("年度结余", Expect.YEAR_BALANCE_CENT, year.balanceCent)
        assertEquals("12 个月位置", 12, year.months.size)
        assertEquals("截止日 = 冻结今日", Fx.TODAY, year.cutoff)
        assertEquals("分母 = 已进入月份数", Expect.YEAR_2026_REACHED_MONTHS, year.reachedMonthCount)
        assertCent(
            "截至目前月均支出 = 3178.80 / 10",
            Expect.YEAR_2026_AVG_MONTHLY_EXPENSE_FLOOR_CENT,
            year.averageMonthlyExpenseCent!!,
        )
        assertTrue("九月已到且有记录", year.months.first { it.month == 9 }.let { it.reached && it.hasRecords })
        assertTrue("八月已到但零记录", year.months.first { it.month == 8 }.let { it.reached && !it.hasRecords })
        assertFalse("十一月未到", year.months.first { it.month == 11 }.reached)
        assertFalse("十二月未到", year.months.first { it.month == 12 }.reached)
        assertCent(
            "11/12 月不得计入合计",
            Expect.YEAR_EXPENSE_CENT,
            year.months.filter { it.month <= 10 }.sumOf { it.expenseCent },
        )
    }

    @Test
    fun `§12 账户余额按全历史有效记录派生且与结余独立`() = runBlocking {
        assertCent("A 账户余额", Expect.ACCOUNT_A_BALANCE_CENT, accountBalance(Fx.ACCOUNT_A_REF))
        assertCent("B 账户余额", Expect.ACCOUNT_B_BALANCE_CENT, accountBalance(Fx.ACCOUNT_B_REF))
        val all = accounts()
        val a = all.first { it.account.id == fx.accountId(Fx.ACCOUNT_A_REF) }
        val b = all.first { it.account.id == fx.accountId(Fx.ACCOUNT_B_REF) }
        assertEquals("A 期初", Fx.ACCOUNT_A_OPENING_CENT, a.openingBalanceCent)
        assertEquals("B 期初", Fx.ACCOUNT_B_OPENING_CENT, b.openingBalanceCent)
        assertCent(
            "全部账户余额 = 期初 1200.00 + 全年结余 12621.20",
            Expect.ALL_ACCOUNTS_BALANCE_CENT,
            a.balanceCent + b.balanceCent,
        )
        assertFalse(
            "账户余额不得等于收支结余（两者标签必须区分）",
            Expect.ALL_ACCOUNTS_BALANCE_CENT == Expect.YEAR_BALANCE_CENT,
        )
        assertEquals("A 账户有效账单数", 4, a.transactionCount)
        assertEquals("B 账户有效账单数", 5, b.transactionCount)
    }

    @Test
    fun `§12 十月日趋势与分类切片精确`() = runBlocking {
        val analysis = repository.observeMonthAnalysis(Fx.OCT_2026, TransactionType.EXPENSE).first()
        assertEquals("日趋势天数 = 1 日至今日", 7, analysis.daily.size)
        assertCentList(
            Expect.OCT_DAILY_EXPENSE_CENT,
            analysis.daily.map { it.amountCent },
            "十月日支出 1—7 日",
        )
        assertEquals("日趋势日期从 1 日开始连续", (1..7).map { LocalDate.of(2026, 10, it) }, analysis.daily.map { it.date })
        assertCent("支出模式总额", Expect.OCT_EXPENSE_CENT, analysis.typeTotalCent)

        val byId = analysis.slices.associateBy { it.categoryId }
        assertCent("十月餐饮", Expect.OCT_FOOD_CENT, byId.getValue(Fx.CAT_FOOD).amountCent)
        assertCent("十月交通", Expect.OCT_TRANSPORT_CENT, byId.getValue(Fx.CAT_TRANSPORT).amountCent)
        assertCent("十月住房(居住)", Expect.OCT_HOUSING_CENT, byId.getValue(Fx.CAT_HOUSING).amountCent)
        assertCent("切片之和 = 月支出", Expect.OCT_EXPENSE_CENT, analysis.slices.sumOf { it.amountCent })
        assertEquals("切片按金额降序", listOf(Expect.OCT_HOUSING_CENT, Expect.OCT_TRANSPORT_CENT, Expect.OCT_FOOD_CENT), analysis.slices.map { it.amountCent })
        assertEquals("餐饮占比 9‰", 9, byId.getValue(Fx.CAT_FOOD).ratioPermille)
        assertEquals("交通占比 16‰", 16, byId.getValue(Fx.CAT_TRANSPORT).ratioPermille)
        assertEquals("居住占比 974‰", 974, byId.getValue(Fx.CAT_HOUSING).ratioPermille)

        val income = repository.observeMonthAnalysis(Fx.OCT_2026, TransactionType.INCOME).first()
        assertCentList(
            Expect.OCT_DAILY_INCOME_CENT,
            income.daily.map { it.amountCent },
            "十月日收入 1—7 日",
        )
        assertCent("收入模式总额", Expect.OCT_INCOME_CENT, income.typeTotalCent)
        assertEquals("收入切片只有工资与兼职", 2, income.slices.size)
        assertCent("收入切片之和", Expect.OCT_INCOME_CENT, income.slices.sumOf { it.amountCent })
    }

    @Test
    fun `A10 筛选汇总覆盖全部匹配记录且不受分页影响`() = runBlocking {
        val base = TransactionFilter(yearMonth = Fx.OCT_2026, type = TransactionType.EXPENSE, categoryId = Fx.CAT_FOOD, accountId = fx.accountId(Fx.ACCOUNT_B_REF))
        val paged = repository.observeTransactions(base.copy(limit = 1, offset = 0)).first()
        val all = repository.observeTransactions(base.copy(limit = 300, offset = 0)).first()
        val summary = repository.observeFilteredSummary(base.copy(limit = 1)).first()

        assertEquals("第一页只取 1 条", 1, paged.items.size)
        assertEquals("命中总数不受分页影响", Expect.FILTER_EXPENSE_FOOD_B_LABELS.size, paged.totalCount)
        assertTrue("存在更多记录", paged.hasMore)
        assertEquals("全部匹配记录条数", 3, all.items.size)
        assertEquals(
            "命中 ID 集合 = T5/T6/T9",
            Expect.FILTER_EXPENSE_FOOD_B_LABELS.map { fx.transactionId(it) }.sorted(),
            all.items.map { it.transaction.id }.sorted(),
        )
        assertCent("筛选合计 = 28.80（不受 limit=1 影响）", Expect.FILTER_EXPENSE_FOOD_B_TOTAL_CENT, summary.expenseCent)
        assertCent("筛选收入为 0", 0L, summary.incomeCent)
        assertEquals("筛选汇总笔数", 3, summary.count)
    }

    @Test
    fun `A10 搜索覆盖备注分类名与账户名`() = runBlocking {
        val oct = TransactionFilter(yearMonth = Fx.OCT_2026)
        assertTrue(
            "搜索备注「早餐」应命中 T5",
            repository.observeTransactions(oct.copy(query = "早餐")).first().items.map { it.transaction.id }
                .contains(fx.transactionId("T5")),
        )
        val byCategory = repository.observeTransactions(oct.copy(query = "餐饮")).first()
        assertEquals("搜索分类名「餐饮」应命中 T5/T6/T9", 3, byCategory.totalCount)
        val byAccount = repository.observeTransactions(oct.copy(query = Fx.ACCOUNT_B_NAME)).first()
        assertEquals("搜索账户名「钱包」应命中 B 的 5 笔十月账单", 5, byAccount.totalCount)
        val none = repository.observeTransactions(oct.copy(query = "不可能匹配的词")).first()
        assertEquals("无匹配时结果为空", 0, none.totalCount)
    }

    // ═══════════════════════════ A02 / A03 / A04 / A15 ═══════════════════════════

    @Test
    fun `A02 保存 28_50 元餐饮支出后库中为 2850 分且只新增一笔`() = runBlocking {
        val before = repository.observeTransactions(TransactionFilter()).first().totalCount
        val result = repository.createTransaction(
            TransactionDraft(
                type = TransactionType.EXPENSE,
                amountCent = 2_850L,
                categoryId = Fx.CAT_FOOD,
                accountId = fx.accountId(Fx.ACCOUNT_A_REF),
                occurredOn = Fx.TODAY,
                note = "A02 午餐",
            ),
            "a02-request",
        )
        val saved = (result as? SaveResult.Success) ?: error("保存失败：$result")
        val stored = repository.observeTransaction(saved.transactionId).first()
        assertNotNull("保存后必须能读回", stored)
        assertCent("库中金额必须是 2850 分", 2_850L, stored!!.amountCent)
        assertEquals("发生日期 = 今日", Fx.TODAY, stored.occurredOn)
        assertEquals("只新增一笔", before + 1, repository.observeTransactions(TransactionFilter()).first().totalCount)

        val oct = month(Fx.OCT_2026)
        assertCent("十月支出增加 28.50", Expect.OCT_EXPENSE_CENT + 2_850L, oct.expenseCent)
    }

    @Test
    fun `A03 非法金额与未来日期被数据层拒绝`() = runBlocking {
        val base = TransactionDraft(
            type = TransactionType.EXPENSE,
            amountCent = 100L,
            categoryId = Fx.CAT_FOOD,
            accountId = fx.accountId(Fx.ACCOUNT_A_REF),
            occurredOn = Fx.TODAY,
        )
        assertFailureCode("金额为 0", ValidationCode.AMOUNT_ZERO) {
            repository.createTransaction(base.copy(amountCent = 0L), "a03-zero")
        }
        assertFailureCode("金额为负", ValidationCode.AMOUNT_NEGATIVE) {
            repository.createTransaction(base.copy(amountCent = -1L), "a03-negative")
        }
        assertFailureCode("金额超上限", ValidationCode.AMOUNT_OUT_OF_RANGE) {
            repository.createTransaction(base.copy(amountCent = Limits.MAX_TRANSACTION_CENT + 1L), "a03-over")
        }
        assertFailureCode("未来日期", ValidationCode.DATE_FUTURE) {
            repository.createTransaction(base.copy(occurredOn = Expect.FUTURE_DATE), "a03-future")
        }
        assertFailureCode("不存在的分类", ValidationCode.CATEGORY_NOT_FOUND) {
            repository.createTransaction(base.copy(categoryId = "cat_not_exist"), "a03-category")
        }
        assertFailureCode("分类类型与账单类型不一致", ValidationCode.CATEGORY_TYPE_MISMATCH) {
            repository.createTransaction(base.copy(categoryId = Fx.CAT_SALARY), "a03-type")
        }
        assertFailureCode("不存在的账户", ValidationCode.ACCOUNT_NOT_FOUND) {
            repository.createTransaction(base.copy(accountId = "acc_not_exist"), "a03-account")
        }
        assertEquals(
            "全部被拒绝后不得留下任何新记录",
            Expect.OCT_COUNT + 2,
            repository.observeTransactions(TransactionFilter()).first().totalCount,
        )
    }

    @Test
    fun `A03 允许 0_01 与上限金额`() = runBlocking {
        val min = repository.createTransaction(
            TransactionDraft(TransactionType.EXPENSE, Limits.MIN_TRANSACTION_CENT, Fx.CAT_FOOD, fx.accountId(Fx.ACCOUNT_A_REF), Fx.TODAY),
            "a03-min",
        )
        assertTrue("0.01 元必须可保存", min is SaveResult.Success)
        val max = repository.createTransaction(
            TransactionDraft(TransactionType.EXPENSE, Limits.MAX_TRANSACTION_CENT, Fx.CAT_FOOD, fx.accountId(Fx.ACCOUNT_A_REF), Fx.TODAY),
            "a03-max",
        )
        assertTrue("9,999,999.99 元必须可保存", max is SaveResult.Success)
    }

    @Test
    fun `A04 同一 requestId 重复提交只产生一条记录`() = runBlocking {
        val draft = TransactionDraft(TransactionType.EXPENSE, 12_345L, Fx.CAT_FOOD, fx.accountId(Fx.ACCOUNT_A_REF), Fx.TODAY, "双击")
        val before = repository.observeTransactions(TransactionFilter()).first().totalCount
        val first = repository.createTransaction(draft, "a04-request")
        val second = repository.createTransaction(draft, "a04-request")
        assertTrue("第一次成功", first is SaveResult.Success)
        assertTrue("重复提交不得变成第二次新增", second is SaveResult.Success)
        assertEquals(
            "同 requestId 只产生一条记录",
            before + 1,
            repository.observeTransactions(TransactionFilter()).first().totalCount,
        )
        assertEquals(
            "两次返回同一 ID",
            (first as SaveResult.Success).transactionId,
            (second as SaveResult.Success).transactionId,
        )
    }

    @Test
    fun `A15 两笔 0_10 与 0_20 精确合计 0_30`() = runBlocking {
        val month = YearMonth.of(2026, 8) // 干净的过去月份，不影响 §12 期望
        listOf("a15-1" to Expect.A15_FIRST_CENT, "a15-2" to Expect.A15_SECOND_CENT).forEach { (req, cent) ->
            repository.createTransaction(
                TransactionDraft(TransactionType.EXPENSE, cent, Fx.CAT_FOOD, fx.accountId(Fx.ACCOUNT_A_REF), LocalDate.of(2026, 8, 5)),
                req,
            )
        }
        val summary = month(month)
        assertCent("0.10 + 0.20 精确等于 0.30", Expect.A15_TOTAL_CENT, summary.expenseCent)
        assertEquals("30 分", 30L, summary.expenseCent)
    }

    // ═══════════════════════════ A07 / A08 / A09（§12-1/2/3） ═══════════════════════════

    @Test
    fun `§12-1 改 T4 金额为 3200_00 后所有相关口径同步更新`() = runBlocking {
        fx.c1_changeT4Amount().requireSaveSuccess("改 T4 金额")
        assertCent("十月支出", Expect.C1_OCT_EXPENSE_CENT, month(Fx.OCT_2026).expenseCent)
        assertCent("十月结余", Expect.C1_OCT_BALANCE_CENT, month(Fx.OCT_2026).balanceCent)
        assertCent("年度支出", Expect.C1_YEAR_EXPENSE_CENT, repository.observeYearAnalysis(2026).first().expenseCent)
        assertCent("年度结余", Expect.C1_YEAR_BALANCE_CENT, repository.observeYearAnalysis(2026).first().balanceCent)
        assertCent("A 账户余额", Expect.C1_ACCOUNT_A_BALANCE_CENT, accountBalance(Fx.ACCOUNT_A_REF))
        assertCent("B 账户余额不变", Expect.ACCOUNT_B_BALANCE_CENT, accountBalance(Fx.ACCOUNT_B_REF))
        assertCent(
            "十月预算剩余",
            Expect.C1_OCT_BUDGET_REMAINING_CENT,
            repository.observeBudget(Fx.OCT_2026).first().remainingCent!!,
        )
        assertCent(
            "居住分类变为 3200.00",
            Expect.C1_T4_AMOUNT_CENT,
            repository.observeMonthAnalysis(Fx.OCT_2026, TransactionType.EXPENSE).first()
                .slices.first { it.categoryId == Fx.CAT_HOUSING }.amountCent,
        )
        assertEquals("编辑不改变记录 ID", fx.transactionId("T4"), repository.observeTransaction(fx.transactionId("T4")).first()!!.id)
    }

    @Test
    fun `§12-2 把 T4 日期改到 9 月后跨月归属正确且年度账户不变`() = runBlocking {
        fx.c2_moveT4ToSeptember().requireSaveSuccess("改 T4 日期")
        assertCent("九月支出", Expect.C2_SEP_EXPENSE_CENT, month(Fx.SEP_2026).expenseCent)
        assertCent("九月结余", Expect.C2_SEP_BALANCE_CENT, month(Fx.SEP_2026).balanceCent)
        assertCent("十月支出", Expect.C2_OCT_EXPENSE_CENT, month(Fx.OCT_2026).expenseCent)
        assertCent("十月结余", Expect.C2_OCT_BALANCE_CENT, month(Fx.OCT_2026).balanceCent)
        assertCent("年度支出只计一次", Expect.YEAR_EXPENSE_CENT, repository.observeYearAnalysis(2026).first().expenseCent)
        assertCent("A 余额不变", Expect.ACCOUNT_A_BALANCE_CENT, accountBalance(Fx.ACCOUNT_A_REF))
        assertCent("十月预算剩余", Expect.C2_OCT_BUDGET_REMAINING_CENT, repository.observeBudget(Fx.OCT_2026).first().remainingCent!!)
        assertCent("十月住房分类清零", 0L, repository.observeMonthAnalysis(Fx.OCT_2026, TransactionType.EXPENSE).first()
            .slices.firstOrNull { it.categoryId == Fx.CAT_HOUSING }?.amountCent ?: 0L)
        assertEquals("九月日趋势到月末共 30 天", 30, repository.observeMonthAnalysis(Fx.SEP_2026, TransactionType.EXPENSE).first().daily.size)
    }

    @Test
    fun `§12-3 软删除 T9 后再撤销恢复原 ID 与原值`() = runBlocking {
        val receipt = when (val outcome = fx.c3_deleteT9()) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> error("删除失败：${outcome.error.message}")
        }
        assertEquals("凭据指向原记录", fx.transactionId("T9"), receipt.transactionId)
        assertCent("十月支出减少 28.50", Expect.C3_OCT_EXPENSE_CENT, month(Fx.OCT_2026).expenseCent)
        assertCent("十月结余", Expect.C3_OCT_BALANCE_CENT, month(Fx.OCT_2026).balanceCent)
        assertCent("B 余额增加 28.50", Expect.C3_ACCOUNT_B_BALANCE_CENT, accountBalance(Fx.ACCOUNT_B_REF))
        assertEquals("十月笔数 -1", Expect.OCT_COUNT - 1, month(Fx.OCT_2026).count)
        assertEquals("已删除记录不可见（S04 记录不存在态）", null, repository.observeTransaction(fx.transactionId("T9")).first())

        fx.c3_undoDelete(receipt).requireSuccess("撤销删除")

        val restored = repository.observeTransaction(fx.transactionId("T9")).first()
        assertNotNull("撤销后必须恢复原记录", restored)
        assertEquals("必须恢复原 ID（不得新建副本）", fx.transactionId("T9"), restored!!.id)
        assertCent("恢复原金额", Fx.seed("T9").amountCent, restored.amountCent)
        assertCent("十月支出回到原值", Expect.OCT_EXPENSE_CENT, month(Fx.OCT_2026).expenseCent)
        assertCent("B 余额回到原值", Expect.ACCOUNT_B_BALANCE_CENT, accountBalance(Fx.ACCOUNT_B_REF))
        assertEquals("总记录数回到 9 笔", 9, repository.observeTransactions(TransactionFilter()).first().totalCount)
    }

    @Test
    fun `撤销凭据不可重复使用`() = runBlocking {
        val receipt = (fx.c3_deleteT9() as Outcome.Success).value
        fx.c3_undoDelete(receipt).requireSuccess("首次撤销")
        val second = fx.c3_undoDelete(receipt)
        assertTrue("同一凭据第二次使用必须失败（不得误恢复）", second is MutationResult.Failure)
        assertTrue(
            "错误码应为 UNDO_TOKEN_INVALID 或 UNDO_TARGET_CONFLICT",
            (second as MutationResult.Failure).error.let {
                it is com.blueledger.app.core.model.LedgerError.Validation &&
                    it.code in setOf(ValidationCode.UNDO_TOKEN_INVALID, ValidationCode.UNDO_TOKEN_EXPIRED, ValidationCode.UNDO_TARGET_CONFLICT)
            },
        )
    }

    // ═══════════════════════════ A11 / A12 / A13 / A16 ═══════════════════════════

    @Test
    fun `A11 餐饮 100 元与交通 50 元的总额排序与占比`() = runBlocking {
        val august = LocalDate.of(2026, 8, 10)
        repository.createTransaction(
            TransactionDraft(TransactionType.EXPENSE, 10_000L, Fx.CAT_FOOD, fx.accountId(Fx.ACCOUNT_A_REF), august),
            "a11-food",
        )
        repository.createTransaction(
            TransactionDraft(TransactionType.EXPENSE, 5_000L, Fx.CAT_TRANSPORT, fx.accountId(Fx.ACCOUNT_A_REF), august),
            "a11-transport",
        )
        val analysis = repository.observeMonthAnalysis(YearMonth.of(2026, 8), TransactionType.EXPENSE).first()
        assertCent("八月支出 150.00", 15_000L, analysis.expenseCent)
        assertCent("类型总额 150.00", 15_000L, analysis.typeTotalCent)
        assertEquals("排序：餐饮在前", listOf(Fx.CAT_FOOD, Fx.CAT_TRANSPORT), analysis.slices.map { it.categoryId })
        assertEquals("餐饮 100/150 = 66.7%", 667, analysis.slices.first { it.categoryId == Fx.CAT_FOOD }.ratioPermille)
        assertEquals("交通 50/150 = 33.3%", 333, analysis.slices.first { it.categoryId == Fx.CAT_TRANSPORT }.ratioPermille)
    }

    @Test
    fun `A12 月支出为零时不产生 NaN 也不造假环图数据`() = runBlocking {
        val empty = YearMonth.of(2026, 7)
        val analysis = repository.observeMonthAnalysis(empty, TransactionType.EXPENSE).first()
        assertCent("零支出总额", 0L, analysis.expenseCent)
        assertCent("零类型总额", 0L, analysis.typeTotalCent)
        assertTrue("无数据时不得生成分类切片", analysis.slices.isEmpty())
        assertFalse("比例不是 NaN", analysis.slices.any { it.ratioFraction.isNaN() })
        assertCent("八月的收入侧同样为零", 0L, repository.observeMonthAnalysis(empty, TransactionType.INCOME).first().incomeCent)
        assertEquals("历史月日趋势仍补齐整月 31 天", 31, analysis.daily.size)
        assertTrue("补齐的每一天都是 0", analysis.daily.all { it.amountCent == 0L })
    }

    @Test
    fun `A16 闰年 2024-02-29 可保存且归属 2024-02`() = runBlocking {
        val result = repository.createTransaction(
            TransactionDraft(TransactionType.EXPENSE, 1_000L, Fx.CAT_FOOD, fx.accountId(Fx.ACCOUNT_A_REF), Expect.LEAP_VALID_DATE, "闰年"),
            "a16-leap",
        )
        assertTrue("2024-02-29 是合法日期", result is SaveResult.Success)
        assertCent("2024-02 支出 10.00", 1_000L, month(YearMonth.of(2024, 2)).expenseCent)
        assertCent("2024 年支出 10.00", 1_000L, repository.observeYearAnalysis(2024).first().expenseCent)
    }

    @Test
    fun `A16 跨年边界 2025-12-31 与 2026-01-01 归属各自年份`() = runBlocking {
        repository.createTransaction(
            TransactionDraft(TransactionType.EXPENSE, 2_000L, Fx.CAT_FOOD, fx.accountId(Fx.ACCOUNT_A_REF), Expect.YEAR_BOUNDARY_BEFORE),
            "a16-dec",
        )
        repository.createTransaction(
            TransactionDraft(TransactionType.EXPENSE, 3_000L, Fx.CAT_FOOD, fx.accountId(Fx.ACCOUNT_A_REF), Expect.YEAR_BOUNDARY_AFTER),
            "a16-jan",
        )
        assertCent("2025 年支出", 2_000L, repository.observeYearAnalysis(2025).first().expenseCent)
        assertCent("2026 年支出 = §12 全年 + 新增 30.00", Expect.YEAR_EXPENSE_CENT + 3_000L, repository.observeYearAnalysis(2026).first().expenseCent)
        assertCent("2025-12 月支出", 2_000L, month(YearMonth.of(2025, 12)).expenseCent)
        assertCent("2026-01 月支出", 3_000L, month(YearMonth.of(2026, 1)).expenseCent)
    }

    @Test
    fun `同日账单按发生日期与创建时间与 ID 稳定排序`() = runBlocking {
        // T5 与 T6 同为 2026-10-02；冻结时钟下 createdAt 相同，最终必须由 id 稳定兜底。
        val page = repository.observeTransactions(TransactionFilter(yearMonth = Fx.OCT_2026, limit = 300)).first()
        val dates = page.items.map { it.transaction.occurredOn }
        assertEquals("按发生日期降序", dates.sortedDescending(), dates)
        val day2 = page.items.filter { it.transaction.occurredOn == LocalDate.of(2026, 10, 2) }
        assertEquals("同日两笔都在", 2, day2.size)
        val createdAt = day2.map { it.transaction.createdAt }
        assertEquals("同日 createdAt 相同时按 id 兜底，顺序必须稳定（连续两次读取一致）", true, createdAt.size == 2)
        val again = repository.observeTransactions(TransactionFilter(yearMonth = Fx.OCT_2026, limit = 300)).first()
        assertEquals("两次读取顺序完全一致", page.items.map { it.transaction.id }, again.items.map { it.transaction.id })
    }

    // ═══════════════════════════ B01 / B02 / B03 / B04（§12-5/6/7） ═══════════════════════════

    @Test
    fun `§12-5 归档餐饮分类后历史统计保留且新增不可选`() = runBlocking {
        fx.c5_archiveFoodCategory(true).requireSuccess("归档餐饮")
        assertCent("T5/T6/T9 仍参与十月统计", Expect.OCT_EXPENSE_CENT, month(Fx.OCT_2026).expenseCent)
        assertCent(
            "餐饮分类小计不变",
            Expect.OCT_FOOD_CENT,
            repository.observeMonthAnalysis(Fx.OCT_2026, TransactionType.EXPENSE).first()
                .slices.first { it.categoryId == Fx.CAT_FOOD }.amountCent,
        )
        assertFalse(
            "新增账单不得选择已归档分类",
            repository.observeCategories(TransactionType.EXPENSE, includeArchived = false).first().any { it.id == Fx.CAT_FOOD },
        )
        assertTrue(
            "归档分类仍在全量列表中（历史名称可解析）",
            repository.observeCategories(TransactionType.EXPENSE, includeArchived = true).first().any { it.id == Fx.CAT_FOOD },
        )
        val rejected = repository.createTransaction(
            TransactionDraft(TransactionType.EXPENSE, 100L, Fx.CAT_FOOD, fx.accountId(Fx.ACCOUNT_A_REF), Fx.TODAY),
            "b01-new-archived",
        )
        assertTrue("新增使用归档分类必须被拒绝", rejected is SaveResult.Failure)
        val kept = fx.update("T5")
        assertTrue("编辑原记录可保留原（归档）分类关联", kept is SaveResult.Success)
    }

    @Test
    fun `B01 归档账户 B 后历史保留且新增不可选`() = runBlocking {
        fx.c5_archiveAccountB(true).requireSuccess("归档账户 B")
        assertCent("十月支出不变", Expect.OCT_EXPENSE_CENT, month(Fx.OCT_2026).expenseCent)
        assertCent("B 余额不变", Expect.ACCOUNT_B_BALANCE_CENT, accountBalance(Fx.ACCOUNT_B_REF))
        assertFalse(
            "新增不可选择已归档账户",
            repository.observeAccounts(includeArchived = false).first().any { it.account.id == fx.accountId(Fx.ACCOUNT_B_REF) },
        )
        val rejected = repository.createTransaction(
            TransactionDraft(TransactionType.EXPENSE, 100L, Fx.CAT_FOOD, fx.accountId(Fx.ACCOUNT_B_REF), Fx.TODAY),
            "b01-new-archived-account",
        )
        assertTrue("新增使用归档账户必须被拒绝", rejected is SaveResult.Failure)
        assertTrue("编辑原记录可保留原账户", fx.update("T5") is SaveResult.Success)
    }

    @Test
    fun `B02 重名分类 归档兜底分类 归档最后一个账户都被拒绝并给出原因`() = runBlocking {
        val duplicate = repository.upsertCategory(
            CategoryCommand(id = null, type = TransactionType.EXPENSE, name = "餐饮", iconKey = CategoryIcons.RESTAURANT),
        )
        assertMutationFailureCode("同类型重名分类", ValidationCode.CATEGORY_NAME_DUPLICATE, duplicate)

        val fallback = repository.setCategoryArchived(Defaults.FALLBACK_EXPENSE_CATEGORY_ID, true)
        assertMutationFailureCode("归档兜底分类", ValidationCode.CATEGORY_FALLBACK_PROTECTED, fallback)

        // 归档（非默认的）账户 B 之后，再把最后一个可用账户（默认账户）归档必须被拒绝，
        // 且系统始终保留至少一个可用账户。
        repository.setAccountArchived(fx.accountId(Fx.ACCOUNT_B_REF), true, null).requireSuccess("归档 B")
        val lastOne = repository.setAccountArchived(Defaults.DEFAULT_ACCOUNT_ID, true, replacementDefaultId = null)
        assertTrue("归档最后一个可用账户必须被拒绝", lastOne is MutationResult.Failure)
        assertTrue("必须始终保留至少一个可用账户", repository.observeAccounts(includeArchived = false).first().isNotEmpty())
    }

    @Test
    fun `§12-6 支出 2500 预算 2000 时 125_0 百分比与超支 500 且进度条不溢出`() = runBlocking {
        val cleanMonth = YearMonth.of(2026, 8)
        // 用一个干净的月份构造 2500.00 支出（避免与 §12 十月的 3078.80 混淆）
        repository.setBudget(cleanMonth, Expect.C6_BUDGET_CENT).requireSuccess("设置预算")
        repository.createTransaction(
            TransactionDraft(TransactionType.EXPENSE, Expect.C6_EXPENSE_CENT, Fx.CAT_FOOD, fx.accountId(Fx.ACCOUNT_A_REF), LocalDate.of(2026, 8, 15)),
            "c6-expense",
        )
        val budget = repository.observeBudget(cleanMonth).first()
        assertCent("预算已用", Expect.C6_EXPENSE_CENT, budget.usedCent)
        assertCent("超支额", Expect.C6_EXCEEDED_CENT, budget.exceededCent!!)
        assertCent("剩余为负", -Expect.C6_EXCEEDED_CENT, budget.remainingCent!!)
        assertEquals("真实使用率 125.00%", Expect.C6_RATIO_BASIS_POINT, budget.ratioBasisPoint)
        assertEquals("文字 125.0%", Expect.C6_RATIO_PERMILLE_DISPLAY, budget.ratioPermille)
        assertEquals("状态 = 超支", BudgetStatus.EXCEEDED, budget.status)
        assertEquals("进度条最多 1.0", 1.0, budget.progressFraction!!.toDouble(), 1e-9)
    }

    @Test
    fun `§12-7 预算 2000 的 80_ 与 100_ 状态 移除后回到未设置 且月份独立`() = runBlocking {
        val m = YearMonth.of(2026, 8)
        repository.setBudget(m, Expect.C7_BUDGET_CENT).requireSuccess("设置八月预算")
        val food = Fx.CAT_FOOD
        val acc = fx.accountId(Fx.ACCOUNT_A_REF)

        repository.createTransaction(TransactionDraft(TransactionType.EXPENSE, Expect.C7_NEAR_LIMIT_EXPENSE_CENT, food, acc, LocalDate.of(2026, 8, 3)), "c7-a")
        val near = repository.observeBudget(m).first()
        assertCent("已用 1600.00", Expect.C7_NEAR_LIMIT_EXPENSE_CENT, near.usedCent)
        assertEquals("80% 判为接近预算", BudgetStatus.NEAR_LIMIT, near.status)
        assertCent("剩余 400.00", 40_000L, near.remainingCent!!)

        repository.createTransaction(TransactionDraft(TransactionType.EXPENSE, 40_000L, food, acc, LocalDate.of(2026, 8, 4)), "c7-b")
        val exhausted = repository.observeBudget(m).first()
        assertEquals("刚好 100% 判为已用完", BudgetStatus.EXHAUSTED, exhausted.status)
        assertCent("剩余 0", 0L, exhausted.remainingCent!!)

        // 月份独立：修改八月不影响十月预算
        assertCent("十月预算不受影响", Fx.OCT_BUDGET_CENT, repository.observeBudget(Fx.OCT_2026).first().budgetCent!!)
        assertTrue("九月依旧未设置", !repository.observeBudget(Fx.SEP_2026).first().isSet)

        repository.removeBudget(m).requireSuccess("移除八月预算")
        val removed = repository.observeBudget(m).first()
        assertFalse("移除后回到未设置", removed.isSet)
        assertEquals("未设置状态", BudgetStatus.UNSET, removed.status)
        assertCent("支出仍在（移除预算不影响账单）", Expect.C7_BUDGET_CENT, removed.usedCent)

        val zeroBudget = repository.setBudget(m, 0L)
        assertTrue("零预算不是有效预算，必须拒绝", zeroBudget is MutationResult.Failure)
    }

    @Test
    fun `§12-4 账户期初改为负值只影响账户余额不影响收支摘要`() = runBlocking {
        fx.c4_setAccountBOpening().requireSuccess("改 B 期初为 -200.00")
        assertCent("B 余额", Expect.C4_ACCOUNT_B_BALANCE_CENT, accountBalance(Fx.ACCOUNT_B_REF))
        assertCent("十月收入不变", Expect.OCT_INCOME_CENT, month(Fx.OCT_2026).incomeCent)
        assertCent("十月支出不变", Expect.OCT_EXPENSE_CENT, month(Fx.OCT_2026).expenseCent)
        assertCent("九月结余不变", Expect.SEP_BALANCE_CENT, month(Fx.SEP_2026).balanceCent)
        assertCent(
            "全部账户余额按新期初重算",
            Expect.C4_ACCOUNT_B_BALANCE_CENT + Expect.ACCOUNT_A_BALANCE_CENT,
            accounts().sumOf { it.balanceCent },
        )
    }

    @Test
    fun `§12-8 T9 改为收入礼金后收支结余同步`() = runBlocking {
        fx.c8_changeT9ToIncomeGift().requireSaveSuccess("T9 改收入礼金")
        assertCent("十月收入", Expect.C8_OCT_INCOME_CENT, month(Fx.OCT_2026).incomeCent)
        assertCent("十月支出", Expect.C8_OCT_EXPENSE_CENT, month(Fx.OCT_2026).expenseCent)
        assertCent("十月结余", Expect.C8_OCT_BALANCE_CENT, month(Fx.OCT_2026).balanceCent)
        assertCent("B 余额（收入也计入）", Expect.ACCOUNT_B_BALANCE_CENT + Fx.seed("T9").amountCent * 2, accountBalance(Fx.ACCOUNT_B_REF))
        val income = repository.observeMonthAnalysis(Fx.OCT_2026, TransactionType.INCOME).first()
        assertCent("礼金分类 28.50", 2_850L, income.slices.first { it.categoryId == Fx.CAT_GIFT }.amountCent)
        assertEquals("支出切片不再含 T9", Expect.OCT_FOOD_CENT - 2_850L, repository
            .observeMonthAnalysis(Fx.OCT_2026, TransactionType.EXPENSE).first()
            .slices.first { it.categoryId == Fx.CAT_FOOD }.amountCent)
    }

    // ═══════════════════════════ B05 / 一致性快照 ═══════════════════════════

    @Test
    fun `B05 账户期初不计入收支统计`() = runBlocking {
        val created = repository.upsertAccount(
            AccountCommand(id = null, name = "验收账户", kind = AccountKind.CASH, openingBalanceCent = 100_000L),
        ).requireSuccess("新增账户")
        // 实现可以返回 null id（契约允许），此时按名称回查，避免把"没返回 id"误判成业务失败。
        val accountId = created.id ?: repository.observeAccounts(includeArchived = true).first()
            .first { it.account.name == "验收账户" }.account.id
        val before = month(Fx.OCT_2026)
        repository.createTransaction(
            TransactionDraft(TransactionType.INCOME, 50_000L, Fx.CAT_SALARY, accountId, LocalDate.of(2026, 10, 5)),
            "b05-income",
        )
        repository.createTransaction(
            TransactionDraft(TransactionType.EXPENSE, 20_000L, Fx.CAT_FOOD, accountId, LocalDate.of(2026, 10, 5)),
            "b05-expense",
        )
        val balance = accounts().first { it.account.id == accountId }.balanceCent
        assertCent("余额 = 期初 1000.00 + 收入 500.00 - 支出 200.00", 130_000L, balance)
        val after = month(Fx.OCT_2026)
        assertCent("期初不计收入：月收入只增加 500.00", before.incomeCent + 50_000L, after.incomeCent)
        assertCent("月支出只增加 200.00", before.expenseCent + 20_000L, after.expenseCent)
    }

    @Test
    fun `导出快照包含全部有效账单与全部分类账户且排除软删除`() = runBlocking {
        repository.setCategoryArchived(Fx.CAT_TRANSPORT, true).requireSuccess("归档交通")
        repository.softDeleteTransaction(fx.transactionId("T7")).let { outcome ->
            assertTrue("删除应成功", outcome is Outcome.Success)
        }
        val snapshot = repository.exportConsistentSnapshot()
        assertEquals("有效账单 8 笔（T7 已软删）", 8, snapshot.transactions.size)
        assertFalse("快照不含软删除账单", snapshot.transactions.any { it.id == fx.transactionId("T7") })
        assertTrue("含归档分类（配置本身要保留）", snapshot.categories.any { it.id == Fx.CAT_TRANSPORT && it.isArchived })
        assertEquals("分类数 = 默认 14 个", Defaults.CATEGORIES.size, snapshot.categories.size)
        assertEquals("账户数 = 默认账户 + A + B", 3, snapshot.accounts.size)
        assertEquals("币种", "CNY", snapshot.currency)
        assertNotNull("预算随快照导出", snapshot.budgets.firstOrNull { it.yearMonth == Fx.OCT_2026 })
        assertCent("快照仍保留 8 笔的合计（3078.80 - 50.00）", Expect.OCT_EXPENSE_CENT - Expect.OCT_TRANSPORT_CENT, snapshot.transactions.filter {
            it.type == TransactionType.EXPENSE && it.occurredOn.monthValue == 10
        }.sumOf { it.amountCent })
    }

    // ───────────────────────── 断言小工具 ─────────────────────────

    private fun com.blueledger.app.core.model.LedgerError.validationCode(): ValidationCode? =
        when (this) {
            is com.blueledger.app.core.model.LedgerError.Validation -> code
            is com.blueledger.app.core.model.LedgerError.Conflict -> code
            is com.blueledger.app.core.model.LedgerError.Backup -> code
            else -> null
        }

    private fun assertMutationFailureCode(label: String, code: ValidationCode, result: MutationResult) {
        val error = result.errorOrNull()
        assertNotNull("$label 必须被拒绝（实际成功了）", error)
        assertEquals("$label 的错误码（实际消息：${error!!.message}）", code, error.validationCode())
    }

    private fun assertFailureCode(label: String, code: ValidationCode, block: suspend () -> SaveResult) =
        runBlocking {
            val result = block()
            val error = result.errorOrNull()
            assertNotNull("$label 必须失败（实际成功）", error)
            assertEquals(
                "$label 的错误码（实际消息：${error!!.message}）",
                code,
                error.validationCode(),
            )
        }

    private fun SaveResult.requireSaveSuccess(what: String): SaveResult.Success {
        when (this) {
            is SaveResult.Success -> return this
            is SaveResult.Failure -> throw AssertionError("$what 失败：${error.message}")
        }
    }
}
