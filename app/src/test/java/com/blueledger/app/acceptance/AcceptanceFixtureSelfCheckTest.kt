package com.blueledger.app.acceptance

import com.blueledger.app.core.model.Defaults
import com.blueledger.app.core.model.Limits
import com.blueledger.app.core.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/**
 * §12 夹具自检 —— **纯 JVM，无 Android、无数据层依赖**。
 *
 * 目的：在任何功能实现合入之前，先证明「夹具与期望值」本身是对的：
 * 1. 每个期望常量都能与 §12 文档里的元字符串精确对上（防手写分值时漏零）；
 * 2. 期望值之间彼此自洽（分月/分年/分类/日序列/账户余额互为验算）；
 * 3. 夹具引用的默认分类 ID 真实存在于 [Defaults]；
 * 4. 8 条后续修改用例的期望在该夹具数据上算术成立。
 *
 * 这不是"功能已验证"，但它是后面所有验收断言可信的前提：
 * 夹具错了，红灯会指向错误的实现。
 */
class AcceptanceFixtureSelfCheckTest {

    // ───────────────────────── 时钟 ─────────────────────────

    @Test
    fun `时钟冻结在 2026-10-07 Asia-Shanghai`() {
        assertEquals("冻结瞬时", Instant.parse("2026-10-06T16:00:00Z"), AcceptanceClock.now())
        assertEquals("冻结今日", LocalDate.of(2026, 10, 7), AcceptanceClock.today())
        assertEquals("冻结当前年月", YearMonth.of(2026, 10), AcceptanceClock.currentYearMonth())
        assertEquals("冻结时区", "Asia/Shanghai", AcceptanceClock.zoneId().id)
    }

    // ───────────────────────── 期望值 ↔ §12 元文本 ─────────────────────────

    @Test
    fun `精确期望常量与文档元值逐条一致`() {
        // (文档里的元文本, 夹具常量, 说明)
        val table = listOf(
            Triple("5000.00", Expect.SEP_INCOME_CENT, "九月收入"),
            Triple("100.00", Expect.SEP_EXPENSE_CENT, "九月支出"),
            Triple("4900.00", Expect.SEP_BALANCE_CENT, "九月结余"),
            Triple("10800.00", Expect.OCT_INCOME_CENT, "十月收入"),
            Triple("3078.80", Expect.OCT_EXPENSE_CENT, "十月支出"),
            Triple("7721.20", Expect.OCT_BALANCE_CENT, "十月结余"),
            Triple("15800.00", Expect.YEAR_INCOME_CENT, "2026 累计收入"),
            Triple("3178.80", Expect.YEAR_EXPENSE_CENT, "2026 累计支出"),
            Triple("12621.20", Expect.YEAR_BALANCE_CENT, "2026 累计结余"),
            Triple("12900.00", Expect.ACCOUNT_A_BALANCE_CENT, "A 账户余额"),
            Triple("921.20", Expect.ACCOUNT_B_BALANCE_CENT, "B 账户余额"),
            Triple("13821.20", Expect.ALL_ACCOUNTS_BALANCE_CENT, "全部账户余额"),
            Triple("28.80", Expect.OCT_FOOD_CENT, "十月餐饮支出"),
            Triple("50.00", Expect.OCT_TRANSPORT_CENT, "十月交通支出"),
            Triple("3000.00", Expect.OCT_HOUSING_CENT, "十月住房支出"),
            Triple("921.20", Expect.OCT_BUDGET_REMAINING_CENT, "十月预算剩余"),
            Triple("4000.00", Fx.OCT_BUDGET_CENT, "十月预算"),
            Triple("3200.00", Expect.C1_T4_AMOUNT_CENT, "§12-1 T4 新金额"),
            Triple("3278.80", Expect.C1_OCT_EXPENSE_CENT, "§12-1 十月支出"),
            Triple("7521.20", Expect.C1_OCT_BALANCE_CENT, "§12-1 十月结余"),
            Triple("3378.80", Expect.C1_YEAR_EXPENSE_CENT, "§12-1 年度支出"),
            Triple("12421.20", Expect.C1_YEAR_BALANCE_CENT, "§12-1 年度结余"),
            Triple("12700.00", Expect.C1_ACCOUNT_A_BALANCE_CENT, "§12-1 A 账户余额"),
            Triple("721.20", Expect.C1_OCT_BUDGET_REMAINING_CENT, "§12-1 预算剩余"),
            Triple("3100.00", Expect.C2_SEP_EXPENSE_CENT, "§12-2 九月支出"),
            Triple("1900.00", Expect.C2_SEP_BALANCE_CENT, "§12-2 九月结余"),
            Triple("78.80", Expect.C2_OCT_EXPENSE_CENT, "§12-2 十月支出"),
            Triple("10721.20", Expect.C2_OCT_BALANCE_CENT, "§12-2 十月结余"),
            Triple("3921.20", Expect.C2_OCT_BUDGET_REMAINING_CENT, "§12-2 预算剩余"),
            Triple("3050.30", Expect.C3_OCT_EXPENSE_CENT, "§12-3 十月支出"),
            Triple("7749.70", Expect.C3_OCT_BALANCE_CENT, "§12-3 十月结余"),
            Triple("949.70", Expect.C3_ACCOUNT_B_BALANCE_CENT, "§12-3 B 账户余额"),
            Triple("-200.00", Expect.C4_ACCOUNT_B_OPENING_CENT, "§12-4 B 期初"),
            Triple("521.20", Expect.C4_ACCOUNT_B_BALANCE_CENT, "§12-4 B 账户余额"),
            Triple("2500.00", Expect.C6_EXPENSE_CENT, "§12-6 支出"),
            Triple("2000.00", Expect.C6_BUDGET_CENT, "§12-6 预算"),
            Triple("500.00", Expect.C6_EXCEEDED_CENT, "§12-6 超支额"),
            Triple("2000.00", Expect.C7_BUDGET_CENT, "§12-7 预算"),
            Triple("1600.00", Expect.C7_NEAR_LIMIT_EXPENSE_CENT, "§12-7 接近预算支出"),
            Triple("2000.00", Expect.C7_EXHAUSTED_EXPENSE_CENT, "§12-7 用完预算支出"),
            Triple("10828.50", Expect.C8_OCT_INCOME_CENT, "§12-8 十月收入"),
            Triple("3050.30", Expect.C8_OCT_EXPENSE_CENT, "§12-8 十月支出"),
            Triple("7778.20", Expect.C8_OCT_BALANCE_CENT, "§12-8 十月结余"),
            Triple("1000.00", Fx.ACCOUNT_A_OPENING_CENT, "A 期初"),
            Triple("200.00", Fx.ACCOUNT_B_OPENING_CENT, "B 期初"),
            Triple("1200.00", Fx.TOTAL_OPENING_CENT, "期初总额"),
            Triple("12.50", Expect.KEYPAD_INPUT_CENT, "键盘输入 12.50"),
            Triple("0.10", Expect.A15_FIRST_CENT, "A15 第一笔"),
            Triple("0.20", Expect.A15_SECOND_CENT, "A15 第二笔"),
            Triple("0.30", Expect.A15_TOTAL_CENT, "A15 合计"),
        )
        val mismatches = table.filter { (yuan, cent, _) ->
            val parsed = runCatching { Cent.fromYuanTextExact(yuan) }.getOrNull()
            parsed != cent
        }
        assertTrue(
            "以下期望常量与 §12 文档元值不一致：" +
                mismatches.joinToString { (yuan, cent, label) -> "$label 文档=$yuan 解析=${runCatching { Cent.fromYuanTextExact(yuan) }.getOrNull()} 常量=$cent" },
            mismatches.isEmpty(),
        )
    }

    @Test
    fun `日序列期望与文档元值逐项一致`() {
        val expectExpense = listOf("3000.00", "0.30", "50.00", "0", "0", "0", "28.50")
        val expectIncome = listOf("10000.00", "0", "0", "0", "0", "800.00", "0")
        assertEquals(expectExpense.map { Cent.fromYuanTextExact(it) }, Expect.OCT_DAILY_EXPENSE_CENT)
        assertEquals(expectIncome.map { Cent.fromYuanTextExact(it) }, Expect.OCT_DAILY_INCOME_CENT)
        assertEquals("十月 1—7 日共 7 天", 7, Expect.OCT_DAILY_EXPENSE_CENT.size)
        assertEquals("十月 1—7 日共 7 天", 7, Expect.OCT_DAILY_INCOME_CENT.size)
    }

    // ───────────────────────── 夹具内部自洽 ─────────────────────────

    private fun sumOf(
        type: TransactionType? = null,
        from: LocalDate? = null,
        to: LocalDate? = null,
        categoryId: String? = null,
        accountRef: String? = null,
        labels: List<String>? = null,
    ): Long = Fx.transactions
        .filter { labels == null || it.label in labels }
        .filter { type == null || it.type == type }
        .filter { categoryId == null || it.categoryId == categoryId }
        .filter { accountRef == null || it.accountRef == accountRef }
        .filter { from == null || !it.occurredOn.isBefore(from) }
        .filter { to == null || !it.occurredOn.isAfter(to) }
        .sumOf { it.amountCent }

    @Test
    fun `九月与十月与年度汇总由夹具逐笔加总可复现`() {
        val sepFrom = LocalDate.of(2026, 9, 1)
        val sepTo = LocalDate.of(2026, 9, 30)
        val octFrom = LocalDate.of(2026, 10, 1)
        val octTo = LocalDate.of(2026, 10, 31)

        assertCent(Expect.SEP_INCOME_CENT, sumOf(TransactionType.INCOME, sepFrom, sepTo), "九月收入")
        assertCent(Expect.SEP_EXPENSE_CENT, sumOf(TransactionType.EXPENSE, sepFrom, sepTo), "九月支出")
        assertCent(Expect.OCT_INCOME_CENT, sumOf(TransactionType.INCOME, octFrom, octTo), "十月收入")
        assertCent(Expect.OCT_EXPENSE_CENT, sumOf(TransactionType.EXPENSE, octFrom, octTo), "十月支出")
        assertCent(
            Expect.YEAR_INCOME_CENT,
            sumOf(TransactionType.INCOME, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)),
            "2026 收入",
        )
        assertCent(
            Expect.YEAR_EXPENSE_CENT,
            sumOf(TransactionType.EXPENSE, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)),
            "2026 支出",
        )

        assertCent(Expect.SEP_BALANCE_CENT, Expect.SEP_INCOME_CENT - Expect.SEP_EXPENSE_CENT, "九月结余")
        assertCent(Expect.OCT_BALANCE_CENT, Expect.OCT_INCOME_CENT - Expect.OCT_EXPENSE_CENT, "十月结余")
        assertCent(Expect.YEAR_BALANCE_CENT, Expect.YEAR_INCOME_CENT - Expect.YEAR_EXPENSE_CENT, "年度结余")
        assertCent(
            Expect.YEAR_EXPENSE_CENT,
            Expect.SEP_EXPENSE_CENT + Expect.OCT_EXPENSE_CENT,
            "年度支出 = 九月 + 十月",
        )
        assertCent(
            Expect.YEAR_INCOME_CENT,
            Expect.SEP_INCOME_CENT + Expect.OCT_INCOME_CENT,
            "年度收入 = 九月 + 十月",
        )
    }

    @Test
    fun `十月分类小计与笔数由夹具可复现`() {
        val octFrom = LocalDate.of(2026, 10, 1)
        val octTo = LocalDate.of(2026, 10, 31)

        assertCent(Expect.OCT_FOOD_CENT, sumOf(TransactionType.EXPENSE, octFrom, octTo, Fx.CAT_FOOD), "十月餐饮")
        assertEquals(
            "十月餐饮笔数",
            Expect.OCT_FOOD_COUNT,
            Fx.transactions.count {
                it.type == TransactionType.EXPENSE && it.categoryId == Fx.CAT_FOOD &&
                    !it.occurredOn.isBefore(octFrom) && !it.occurredOn.isAfter(octTo)
            },
        )
        assertCent(Expect.OCT_TRANSPORT_CENT, sumOf(TransactionType.EXPENSE, octFrom, octTo, Fx.CAT_TRANSPORT), "十月交通")
        assertCent(Expect.OCT_HOUSING_CENT, sumOf(TransactionType.EXPENSE, octFrom, octTo, Fx.CAT_HOUSING), "十月住房")
        assertCent(
            Expect.OCT_EXPENSE_CENT,
            Expect.OCT_FOOD_CENT + Expect.OCT_TRANSPORT_CENT + Expect.OCT_HOUSING_CENT,
            "十月支出 = 三个分类之和",
        )
        assertCent(Expect.OCT_BUDGET_REMAINING_CENT, Fx.OCT_BUDGET_CENT - Expect.OCT_EXPENSE_CENT, "十月预算剩余")

        val octLabels = Fx.transactions.filter { !it.occurredOn.isBefore(octFrom) && !it.occurredOn.isAfter(octTo) }
        assertEquals("十月有效账单笔数", Expect.OCT_COUNT, octLabels.size)
        assertEquals("十月有效账目标签", Fx.OCT_VALID_LABELS.sorted(), octLabels.map { it.label }.sorted())
    }

    @Test
    fun `十月日趋势序列由夹具可复现且已补零`() {
        val days = (1..7).map { LocalDate.of(2026, 10, it) }
        val expense = days.map { day -> sumOf(TransactionType.EXPENSE, day, day) }
        val income = days.map { day -> sumOf(TransactionType.INCOME, day, day) }

        assertCentList(Expect.OCT_DAILY_EXPENSE_CENT, expense, "十月日支出 1—7 日")
        assertCentList(Expect.OCT_DAILY_INCOME_CENT, income, "十月日收入 1—7 日")

        assertCent(
            Expect.OCT_EXPENSE_CENT,
            expense.sum(),
            "日支出序列之和 = 十月支出（说明补齐的零没有丢数据）",
        )
        assertCent(
            Expect.OCT_INCOME_CENT,
            income.sum(),
            "日收入序列之和 = 十月收入",
        )
        assertTrue("4/5/6 日支出为 0 且必须补零而不是跳过", expense.subList(3, 6).all { it == 0L })
    }

    @Test
    fun `账户余额与期初加净变动一致且与收支结余独立`() {
        val accountANet = sumOf(TransactionType.INCOME, accountRef = Fx.ACCOUNT_A_REF) -
            sumOf(TransactionType.EXPENSE, accountRef = Fx.ACCOUNT_A_REF)
        val accountBNet = sumOf(TransactionType.INCOME, accountRef = Fx.ACCOUNT_B_REF) -
            sumOf(TransactionType.EXPENSE, accountRef = Fx.ACCOUNT_B_REF)

        assertCent(Expect.ACCOUNT_A_BALANCE_CENT, Fx.ACCOUNT_A_OPENING_CENT + accountANet, "A 余额")
        assertCent(Expect.ACCOUNT_B_BALANCE_CENT, Fx.ACCOUNT_B_OPENING_CENT + accountBNet, "B 余额")
        assertCent(
            Expect.ALL_ACCOUNTS_BALANCE_CENT,
            Expect.ACCOUNT_A_BALANCE_CENT + Expect.ACCOUNT_B_BALANCE_CENT,
            "全部账户余额",
        )
        assertCent(
            Expect.ALL_ACCOUNTS_BALANCE_CENT,
            Fx.TOTAL_OPENING_CENT + Expect.YEAR_BALANCE_CENT,
            "全部账户余额 = 期初总额 + 全年结余（§12 明确关系）",
        )
        assertTrue(
            "收支结余（7721.20）与账户余额（13821.20）是不同量，不能互相替代",
            Expect.OCT_BALANCE_CENT != Expect.ALL_ACCOUNTS_BALANCE_CENT,
        )
    }

    @Test
    fun `筛选 支出加餐饮加账户B 命中 T5 T6 T9`() {
        val octFrom = LocalDate.of(2026, 10, 1)
        val octTo = LocalDate.of(2026, 10, 31)
        val matched = Fx.transactions.filter {
            it.type == TransactionType.EXPENSE && it.categoryId == Fx.CAT_FOOD &&
                it.accountRef == Fx.ACCOUNT_B_REF &&
                !it.occurredOn.isBefore(octFrom) && !it.occurredOn.isAfter(octTo)
        }.map { it.label }

        assertEquals("筛选命中账目", Expect.FILTER_EXPENSE_FOOD_B_LABELS.sorted(), matched.sorted())
        assertCent(
            Expect.FILTER_EXPENSE_FOOD_B_TOTAL_CENT,
            sumOf(labels = matched),
            "筛选合计",
        )
        assertEquals(
            "T2 是餐饮但属于 9 月与账户 A，不得进入该筛选",
            false,
            matched.contains("T2"),
        )
    }

    @Test
    fun `十一月十二月对冻结时钟是未到月份`() {
        val today = AcceptanceClock.today()
        assertTrue("2026-11 未到", Fx.NOV_2026.atEndOfMonth().isAfter(today))
        assertTrue("2026-12 未到", Fx.DEC_2026.atEndOfMonth().isAfter(today))
        assertEquals("当年已进入月份数（分母）", Expect.YEAR_2026_REACHED_MONTHS, today.monthValue)
        assertCent(
            "截至 10-07 的月均支出（整数分截断）",
            Expect.YEAR_2026_AVG_MONTHLY_EXPENSE_FLOOR_CENT,
            Expect.YEAR_EXPENSE_CENT / Expect.YEAR_2026_REACHED_MONTHS,
        )
    }

    // ───────────────────────── 8 条后续修改用例的算术自洽 ─────────────────────────

    @Test
    fun `用例1 改 T4 金额后各口径自洽`() {
        val delta = Expect.C1_T4_AMOUNT_CENT - Fx.seed("T4").amountCent // +20,000 分
        assertCent(Expect.C1_OCT_EXPENSE_CENT, Expect.OCT_EXPENSE_CENT + delta, "十月支出 +200.00")
        assertCent(Expect.C1_OCT_BALANCE_CENT, Expect.OCT_INCOME_CENT - Expect.C1_OCT_EXPENSE_CENT, "十月结余")
        assertCent(Expect.C1_YEAR_EXPENSE_CENT, Expect.YEAR_EXPENSE_CENT + delta, "年度支出 +200.00")
        assertCent(Expect.C1_YEAR_BALANCE_CENT, Expect.YEAR_INCOME_CENT - Expect.C1_YEAR_EXPENSE_CENT, "年度结余")
        assertCent(Expect.C1_ACCOUNT_A_BALANCE_CENT, Expect.ACCOUNT_A_BALANCE_CENT - delta, "A 余额 -200.00")
        assertCent(Expect.C1_OCT_BUDGET_REMAINING_CENT, Fx.OCT_BUDGET_CENT - Expect.C1_OCT_EXPENSE_CENT, "预算剩余")
        assertCent(
            "B 余额不受 T4 影响（B 只有支出与一笔兼职收入）",
            Expect.ACCOUNT_B_BALANCE_CENT,
            Fx.ACCOUNT_B_OPENING_CENT +
                sumOf(TransactionType.INCOME, accountRef = Fx.ACCOUNT_B_REF) -
                sumOf(TransactionType.EXPENSE, accountRef = Fx.ACCOUNT_B_REF),
        )
    }

    @Test
    fun `用例2 把 T4 移到九月后跨月归属正确且年度账户不变`() {
        val t4 = Fx.seed("T4").amountCent
        assertCent(Expect.C2_SEP_EXPENSE_CENT, Expect.SEP_EXPENSE_CENT + t4, "九月支出 +3000.00")
        assertCent(Expect.C2_SEP_BALANCE_CENT, Expect.SEP_INCOME_CENT - Expect.C2_SEP_EXPENSE_CENT, "九月结余")
        assertCent(Expect.C2_OCT_EXPENSE_CENT, Expect.OCT_EXPENSE_CENT - t4, "十月支出 -3000.00")
        assertCent(Expect.C2_OCT_BALANCE_CENT, Expect.OCT_INCOME_CENT - Expect.C2_OCT_EXPENSE_CENT, "十月结余")
        assertCent(Expect.C2_OCT_BUDGET_REMAINING_CENT, Fx.OCT_BUDGET_CENT - Expect.C2_OCT_EXPENSE_CENT, "预算剩余")
        assertCent("年度支出不变（只换月份，年度只计一次）", Expect.YEAR_EXPENSE_CENT, Expect.YEAR_EXPENSE_CENT)
        assertCent("A 余额不变", Expect.ACCOUNT_A_BALANCE_CENT, Expect.ACCOUNT_A_BALANCE_CENT)
    }

    @Test
    fun `用例3 软删除 T9 后十月与 B 余额同步减少`() {
        val t9 = Fx.seed("T9").amountCent
        assertCent(Expect.C3_OCT_EXPENSE_CENT, Expect.OCT_EXPENSE_CENT - t9, "十月支出 -28.50")
        assertCent(Expect.C3_OCT_BALANCE_CENT, Expect.OCT_INCOME_CENT - Expect.C3_OCT_EXPENSE_CENT, "十月结余")
        assertCent(Expect.C3_ACCOUNT_B_BALANCE_CENT, Expect.ACCOUNT_B_BALANCE_CENT + t9, "B 余额 +28.50")
        assertCent("撤销后回到原值", Expect.OCT_EXPENSE_CENT, Expect.C3_OCT_EXPENSE_CENT + t9)
    }

    @Test
    fun `用例4 负期初只改账户余额不影响任何收支摘要`() {
        val netB = Expect.ACCOUNT_B_BALANCE_CENT - Fx.ACCOUNT_B_OPENING_CENT
        assertCent(
            Expect.C4_ACCOUNT_B_BALANCE_CENT,
            Expect.C4_ACCOUNT_B_OPENING_CENT + netB,
            "B 余额 = 负期初 + 净变动",
        )
        assertCent(
            "净变动与期初无关：期初改了 400.00 元，净变动一分不变",
            netB,
            Expect.C4_ACCOUNT_B_BALANCE_CENT - Expect.C4_ACCOUNT_B_OPENING_CENT,
        )
        // 收支摘要由账单决定，与账户期初完全无关：把期初改成负数不改变任何一条摘要。
        assertCent("十月收入不受期初影响", Expect.OCT_INCOME_CENT, sumOf(TransactionType.INCOME, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)))
        assertCent("十月支出不受期初影响", Expect.OCT_EXPENSE_CENT, sumOf(TransactionType.EXPENSE, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)))
        assertCent(
            "全部账户余额随期初减少 400.00 元",
            Expect.ALL_ACCOUNTS_BALANCE_CENT + (Expect.C4_ACCOUNT_B_OPENING_CENT - Fx.ACCOUNT_B_OPENING_CENT),
            Expect.C4_ACCOUNT_B_BALANCE_CENT + Expect.ACCOUNT_A_BALANCE_CENT,
        )
    }

    @Test
    fun `用例8 T9 改为收入礼金后十月收支与结余正确`() {
        val t9 = Fx.seed("T9").amountCent
        assertCent(Expect.C8_OCT_INCOME_CENT, Expect.OCT_INCOME_CENT + t9, "十月收入 +28.50")
        assertCent(Expect.C8_OCT_EXPENSE_CENT, Expect.OCT_EXPENSE_CENT - t9, "十月支出 -28.50")
        assertCent(Expect.C8_OCT_BALANCE_CENT, Expect.C8_OCT_INCOME_CENT - Expect.C8_OCT_EXPENSE_CENT, "十月结余")
        assertEquals(
            "原分类「餐饮」是支出分类：T9 切换为收入后不能继续使用它，必须重新选择有效收入分类",
            TransactionType.EXPENSE,
            defaultCategoryOrNull(Fx.CAT_FOOD)?.type,
        )
        assertNotNull("礼金分类必须存在于默认收入分类", defaultCategoryOrNull(Fx.CAT_GIFT))
        assertEquals(
            "礼金分类必须是收入类型",
            TransactionType.INCOME,
            defaultCategoryOrNull(Fx.CAT_GIFT)?.type,
        )
        assertEquals("§12-8 使用的收入分类 ID", Fx.CAT_GIFT, "cat_income_gift")
    }

    // ───────────────────────── 夹具引用的默认数据必须真实存在 ─────────────────────────

    @Test
    fun `夹具引用的默认分类 ID 与名称类型一致`() {
        val expected = mapOf(
            Fx.CAT_SALARY to (TransactionType.INCOME to "工资"),
            Fx.CAT_PART_TIME to (TransactionType.INCOME to "兼职"),
            Fx.CAT_GIFT to (TransactionType.INCOME to "礼金"),
            Fx.CAT_FOOD to (TransactionType.EXPENSE to "餐饮"),
            Fx.CAT_TRANSPORT to (TransactionType.EXPENSE to "交通"),
            Fx.CAT_HOUSING to (TransactionType.EXPENSE to "住房"),
        )
        val missing = expected.keys.filter { defaultCategoryOrNull(it) == null }
        assertTrue("以下夹具分类 ID 在 Defaults 中不存在：$missing", missing.isEmpty())
        expected.forEach { (id, want) ->
            val spec = defaultCategoryOrNull(id)
            assertEquals("分类 $id 类型", want.first, spec?.type)
            assertEquals("分类 $id 名称", want.second, spec?.name)
        }
        assertEquals("默认账户 ID", "acc_default", Defaults.DEFAULT_ACCOUNT_ID)
        assertFalse(
            "夹具用「餐饮」做 §12-5 归档用例，它不能是受保护的兜底分类",
            defaultCategoryOrNull(Fx.CAT_FOOD)?.isFallback ?: true,
        )
        assertEquals(
            "每种类型都必须存在一个兜底分类（备份校验依赖此前提）",
            setOf(TransactionType.EXPENSE, TransactionType.INCOME),
            Defaults.CATEGORIES.filter { it.isFallback }.map { it.type }.toSet(),
        )
    }

    @Test
    fun `夹具账单满足金额日期与备注约束`() {
        assertEquals("账目条数", 9, Fx.transactions.size)
        assertEquals("账目标签唯一", 9, Fx.transactions.map { it.label }.toSet().size)
        Fx.transactions.forEach { seed ->
            assertTrue(
                "${seed.label} 金额 ${seed.amountCent} 必须在 ${Limits.MIN_TRANSACTION_CENT}..${Limits.MAX_TRANSACTION_CENT}",
                seed.amountCent in Limits.MIN_TRANSACTION_CENT..Limits.MAX_TRANSACTION_CENT,
            )
            assertFalse("${seed.label} 不得晚于冻结今日", seed.occurredOn.isAfter(AcceptanceClock.today()))
            assertTrue("${seed.label} 备注长度", seed.note.length <= Limits.MAX_NOTE_LENGTH)
            val spec = defaultCategoryOrNull(seed.categoryId)
            assertNotNull("${seed.label} 的分类必须存在", spec)
            assertEquals("${seed.label} 分类类型必须与账单类型一致", seed.type, spec?.type)
        }
        assertTrue("账户 A/B 引用都在夹具里", setOf(Fx.ACCOUNT_A_REF, Fx.ACCOUNT_B_REF).containsAll(Fx.transactions.map { it.accountRef }))
    }

    // ───────────────────────── 测试工具本身 ─────────────────────────

    @Test
    fun `分元换算工具精确且拒绝三位小数`() {
        assertEquals("0.01", Cent.toYuanText(1L))
        assertEquals("12.50", Cent.toYuanText(1_250L))
        assertEquals("1200.00", Cent.toYuanText(120_000L))
        assertEquals("-200.00", Cent.toYuanText(-20_000L))
        assertEquals(1_250L, Cent.fromYuanTextExact("12.50"))
        assertEquals(1_200L, Cent.fromYuanTextExact("12"))
        assertEquals(1_250L, Cent.fromYuanTextExact("12.5"))
        assertEquals(999_999_999L, Cent.fromYuanTextExact("9999999.99"))
        // 1,382,112.00 元 = 138,211,200 分（这里曾把常量多写一位数，自检把它抓出来了：
        // 正是这类"期望值打错"的失败，必须由夹具自检拦住，而不是让它去冤枉实现）
        assertEquals("大额往返不丢精度", 138_211_200L, Cent.fromYuanTextExact("1382112.00"))
        assertThrows("三位小数必须被视为不可精确换算", ArithmeticException::class.java) {
            Cent.fromYuanTextExact("12.505")
        }
    }

    @Test
    fun `严格模式开关按声明的开关生效且默认关闭`() {
        assertEquals(
            "严格模式属性名是门禁契约的一部分，不得改名",
            "blueledger.acceptance.strict",
            AcceptancePolicy.STRICT_PROPERTY,
        )
        assertEquals(
            "严格模式环境变量名是门禁契约的一部分，不得改名",
            "BLUELEDGER_ACCEPTANCE_STRICT",
            AcceptancePolicy.STRICT_ENV,
        )

        // 本用例必须同时成立两种环境下的事实，不能在严格模式里因为"现在确实是严格模式"而失败：
        // - 显式打开（系统属性或环境变量）时：strict 必须为 true（证明开关真的生效，门禁不是摆设）；
        // - 未打开时：strict 必须为 false（证明默认不会把"未验证"混成"通过"）。
        val explicitlyEnabled = java.lang.Boolean.getBoolean(AcceptancePolicy.STRICT_PROPERTY) ||
            System.getenv(AcceptancePolicy.STRICT_ENV)?.equals("true", ignoreCase = true) == true

        if (explicitlyEnabled) {
            assertTrue(
                "已显式开启（-D${AcceptancePolicy.STRICT_PROPERTY} 或 $${AcceptancePolicy.STRICT_ENV}=true）时，" +
                    "严格模式必须为 true，否则门禁形同虚设",
                AcceptancePolicy.strict,
            )
        } else {
            assertFalse(
                "默认（阶段一）非严格：缺失能力记为未验证而不是假通过；" +
                    "阶段二/G4 门禁必须用环境变量打开严格模式后再判定通过",
                AcceptancePolicy.strict,
            )
        }
    }
}
