package com.blueledger.app.acceptance

import com.blueledger.app.core.contract.Clock
import com.blueledger.app.core.contract.LedgerRepository
import com.blueledger.app.core.model.AccountCommand
import com.blueledger.app.core.model.AccountKind
import com.blueledger.app.core.model.AccountWithBalance
import com.blueledger.app.core.model.Defaults
import com.blueledger.app.core.model.DeleteReceipt
import com.blueledger.app.core.model.LedgerError
import com.blueledger.app.core.model.Limits
import com.blueledger.app.core.model.MutationResult
import com.blueledger.app.core.model.Outcome
import com.blueledger.app.core.model.SaveResult
import com.blueledger.app.core.model.TransactionDraft
import com.blueledger.app.core.model.TransactionType
import kotlinx.coroutines.flow.first
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * A7 独立验收夹具 —— docs/AI开发提示词.md §12 的可执行版本。
 *
 * 纪律（不得放宽）：
 * 1. 这里的期望值全部是 §12 的**字面**精确值，写成整数分常量；不允许用浮点计算“算出来”当作期望，
 *    也不允许在测试里为了让实现通过而调整期望。
 * 2. 夹具只通过**冻结契约**（[Clock] + [LedgerRepository]）写入真实实现，
 *    不自己造一套内存仓库；真实数据层合入后由 Room 实现承接同一份夹具。
 * 3. 生产逻辑不得引用本文件；本文件只在 test 源集。
 */
object AcceptanceClock : Clock {
    val ZONE_ID: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2026-10-07T00:00:00+08:00[Asia/Shanghai] == 2026-10-06T16:00:00Z */
    val INSTANT: Instant = LocalDate.of(2026, 10, 7).atStartOfDay(ZONE_ID).toInstant()

    override fun now(): Instant = INSTANT

    override fun zoneId(): ZoneId = ZONE_ID
}

/** §12 夹具的原始输入数据（账户 / 分类 ID / T1—T9 / 预算）。 */
object Fx {

    val TODAY: LocalDate = LocalDate.of(2026, 10, 7)
    val SEP_2026: YearMonth = YearMonth.of(2026, 9)
    val OCT_2026: YearMonth = YearMonth.of(2026, 10)
    val NOV_2026: YearMonth = YearMonth.of(2026, 11)
    val DEC_2026: YearMonth = YearMonth.of(2026, 12)
    const val YEAR_2026: Int = 2026

    // ── 账户 ───────────────────────────────────────────────────────────────
    const val ACCOUNT_A_REF = "A"
    const val ACCOUNT_A_NAME = "银行卡"
    val ACCOUNT_A_KIND: AccountKind = AccountKind.BANK_CARD
    const val ACCOUNT_A_OPENING_CENT = 100_000L // 1,000.00 元

    const val ACCOUNT_B_REF = "B"
    const val ACCOUNT_B_NAME = "钱包"
    val ACCOUNT_B_KIND: AccountKind = AccountKind.E_WALLET
    const val ACCOUNT_B_OPENING_CENT = 20_000L // 200.00 元

    /** 期初总额：1,200.00 元。 */
    const val TOTAL_OPENING_CENT = ACCOUNT_A_OPENING_CENT + ACCOUNT_B_OPENING_CENT

    // ── 分类 ID（默认分类的稳定 ID，见 core/model/Defaults.kt；由自检测试核对） ──
    const val CAT_SALARY = "cat_income_salary" // 工资（收入）
    const val CAT_PART_TIME = "cat_income_parttime" // 兼职（收入）
    const val CAT_GIFT = "cat_income_gift" // 礼金（收入）
    const val CAT_FOOD = "cat_expense_food" // 餐饮（支出）
    const val CAT_HOUSING = "cat_expense_housing" // 居住（支出，§12 写作“住房”）
    const val CAT_TRANSPORT = "cat_expense_transport" // 交通（支出）

    // ── 预算：2026-10 设 400,000 分（4,000.00 元）；2026-09 未设置 ────────────
    const val OCT_BUDGET_CENT = 400_000L // 4,000.00 元

    /** §12 有效账单。顺序即 T1—T9 的语义顺序，不用于断言列表排序。 */
    data class TxSeed(
        val label: String,
        val occurredOn: LocalDate,
        val type: TransactionType,
        val categoryId: String,
        val accountRef: String,
        val amountCent: Long,
        val note: String,
    )

    val transactions: List<TxSeed> = listOf(
        TxSeed("T1", LocalDate.of(2026, 9, 30), TransactionType.INCOME, CAT_SALARY, ACCOUNT_A_REF, 500_000L, "九月工资"),
        TxSeed("T2", LocalDate.of(2026, 9, 30), TransactionType.EXPENSE, CAT_FOOD, ACCOUNT_A_REF, 10_000L, "九月晚餐"),
        TxSeed("T3", LocalDate.of(2026, 10, 1), TransactionType.INCOME, CAT_SALARY, ACCOUNT_A_REF, 1_000_000L, "十月工资"),
        TxSeed("T4", LocalDate.of(2026, 10, 1), TransactionType.EXPENSE, CAT_HOUSING, ACCOUNT_A_REF, 300_000L, "十月房租"),
        TxSeed("T5", LocalDate.of(2026, 10, 2), TransactionType.EXPENSE, CAT_FOOD, ACCOUNT_B_REF, 10L, "早餐"),
        TxSeed("T6", LocalDate.of(2026, 10, 2), TransactionType.EXPENSE, CAT_FOOD, ACCOUNT_B_REF, 20L, "午餐"),
        TxSeed("T7", LocalDate.of(2026, 10, 3), TransactionType.EXPENSE, CAT_TRANSPORT, ACCOUNT_B_REF, 5_000L, "地铁"),
        TxSeed("T8", LocalDate.of(2026, 10, 6), TransactionType.INCOME, CAT_PART_TIME, ACCOUNT_B_REF, 80_000L, "设计兼职"),
        TxSeed("T9", LocalDate.of(2026, 10, 7), TransactionType.EXPENSE, CAT_FOOD, ACCOUNT_B_REF, 2_850L, "晚餐"),
    )

    fun seed(label: String): TxSeed = transactions.first { it.label == label }

    /** 夹具写入用的稳定 requestId（同一夹具重复写入不会产生重复账单）。 */
    fun requestId(label: String): String = "acceptance-$label"

    const val OCT_TRANSACTION_COUNT = 7 // T3—T9
    val OCT_VALID_LABELS: List<String> = listOf("T3", "T4", "T5", "T6", "T7", "T8", "T9")
}

/**
 * §12「精确期望」。所有值以**整数分**书写；元值写在行尾注释里。
 *
 * 命名约定：`C1_` … `C8_` 对应 §12 末尾「后续修改独立用例」的第 1—8 条。
 */
object Expect {

    // ── 原始夹具 ───────────────────────────────────────────────────────────
    const val SEP_INCOME_CENT = 500_000L // 5,000.00
    const val SEP_EXPENSE_CENT = 10_000L // 100.00
    const val SEP_BALANCE_CENT = 490_000L // 4,900.00

    const val OCT_INCOME_CENT = 1_080_000L // 10,800.00
    const val OCT_EXPENSE_CENT = 307_880L // 3,078.80
    const val OCT_BALANCE_CENT = 772_120L // 7,721.20
    const val OCT_COUNT = 7

    const val YEAR_INCOME_CENT = 1_580_000L // 15,800.00
    const val YEAR_EXPENSE_CENT = 317_880L // 3,178.80
    const val YEAR_BALANCE_CENT = 1_262_120L // 12,621.20

    const val ACCOUNT_A_BALANCE_CENT = 1_290_000L // 12,900.00
    const val ACCOUNT_B_BALANCE_CENT = 92_120L // 921.20
    const val ALL_ACCOUNTS_BALANCE_CENT = 1_382_120L // 13,821.20 == 1,200.00 期初 + 全年结余

    const val OCT_FOOD_CENT = 2_880L // 28.80
    const val OCT_FOOD_COUNT = 3
    const val OCT_TRANSPORT_CENT = 5_000L // 50.00
    const val OCT_TRANSPORT_COUNT = 1
    const val OCT_HOUSING_CENT = 300_000L // 3,000.00
    const val OCT_HOUSING_COUNT = 1

    const val OCT_BUDGET_REMAINING_CENT = 92_120L // 921.20
    /** 使用率 76.97% → 万分比 7697；一位小数展示为 77.0%。 */
    const val OCT_BUDGET_RATIO_BASIS_POINT = 7_697L
    /** 一位小数展示值：77.0% == 770 千分比（**四舍五入**，不是 76.9%）。 */
    const val OCT_BUDGET_RATIO_PERMILLE_DISPLAY = 770
    /** 真实比例（未舍入）的万分比 7697；B 账户余额与本值同形是巧合，不要互相替代。 */
    const val OCT_BUDGET_PROGRESS_FRACTION_MILLI = 7_697 // 0.7697 × 10000

    /** 十月日支出 1—7 日：3,000.00 / 0.30 / 50.00 / 0 / 0 / 0 / 28.50。 */
    val OCT_DAILY_EXPENSE_CENT: List<Long> = listOf(300_000L, 30L, 5_000L, 0L, 0L, 0L, 2_850L)

    /** 十月日收入 1—7 日：10,000.00 / 0 / 0 / 0 / 0 / 800.00 / 0。 */
    val OCT_DAILY_INCOME_CENT: List<Long> = listOf(1_000_000L, 0L, 0L, 0L, 0L, 80_000L, 0L)

    /** 筛选「支出 + 餐饮 + 账户 B」= T5/T6/T9，合计 28.80 元。 */
    val FILTER_EXPENSE_FOOD_B_LABELS: List<String> = listOf("T5", "T6", "T9")
    const val FILTER_EXPENSE_FOOD_B_TOTAL_CENT = 2_880L // 28.80

    /** 2026 年已进入 10 个月（11/12 月为未到月份，不计入月均分母）。 */
    const val YEAR_2026_REACHED_MONTHS = 10
    /** 截至 2026-10-07 的月均支出 = 3178.80 / 10 = 317.88 元（整数分截断后 31788 分）。 */
    const val YEAR_2026_AVG_MONTHLY_EXPENSE_FLOOR_CENT = 31_788L

    // ── §12-1 将 T4 改为 3200.00 ───────────────────────────────────────────
    const val C1_T4_AMOUNT_CENT = 320_000L // 3,200.00
    const val C1_OCT_EXPENSE_CENT = 327_880L // 3,278.80
    const val C1_OCT_BALANCE_CENT = 752_120L // 7,521.20
    const val C1_YEAR_EXPENSE_CENT = 337_880L // 3,378.80
    const val C1_YEAR_BALANCE_CENT = 1_242_120L // 12,421.20
    const val C1_ACCOUNT_A_BALANCE_CENT = 1_270_000L // 12,700.00
    const val C1_OCT_BUDGET_REMAINING_CENT = 72_120L // 721.20

    // ── §12-2 将 T4 日期改为 2026-09-30 ────────────────────────────────────
    val C2_T4_DATE: LocalDate = LocalDate.of(2026, 9, 30)
    const val C2_SEP_EXPENSE_CENT = 310_000L // 3,100.00
    const val C2_SEP_BALANCE_CENT = 190_000L // 1,900.00
    const val C2_OCT_EXPENSE_CENT = 7_880L // 78.80
    const val C2_OCT_BALANCE_CENT = 1_072_120L // 10,721.20
    const val C2_OCT_BUDGET_REMAINING_CENT = 392_120L // 3,921.20
    // 年度与账户余额不变：沿用 YEAR_* / ACCOUNT_* 常量。

    // ── §12-3 软删除 T9 ───────────────────────────────────────────────────
    const val C3_OCT_EXPENSE_CENT = 305_030L // 3,050.30
    const val C3_OCT_BALANCE_CENT = 774_970L // 7,749.70
    const val C3_ACCOUNT_B_BALANCE_CENT = 94_970L // 949.70

    // ── §12-4 将 B 期初改为 -200.00 ───────────────────────────────────────
    const val C4_ACCOUNT_B_OPENING_CENT = -20_000L // -200.00
    const val C4_ACCOUNT_B_BALANCE_CENT = 52_120L // 521.20

    // ── §12-6 支出 2500.00、预算 2000.00 ──────────────────────────────────
    const val C6_EXPENSE_CENT = 250_000L // 2,500.00
    const val C6_BUDGET_CENT = 200_000L // 2,000.00
    const val C6_EXCEEDED_CENT = 50_000L // 500.00
    const val C6_RATIO_BASIS_POINT = 12_500L // 125.00%
    const val C6_RATIO_PERMILLE_DISPLAY = 1_250 // 125.0%

    // ── §12-7 预算 2000.00：1600.00 接近 / 2000.00 已用完 ─────────────────
    const val C7_BUDGET_CENT = 200_000L // 2,000.00
    const val C7_NEAR_LIMIT_EXPENSE_CENT = 160_000L // 1,600.00（正好 80%）
    const val C7_EXHAUSTED_EXPENSE_CENT = 200_000L // 2,000.00（正好 100%）

    // ── §12-8 将 T9 改为收入礼金 ──────────────────────────────────────────
    const val C8_OCT_INCOME_CENT = 1_082_850L // 10,828.50
    const val C8_OCT_EXPENSE_CENT = 305_030L // 3,050.30
    const val C8_OCT_BALANCE_CENT = 777_820L // 7,778.20

    // ── §12 附加独立夹具 ──────────────────────────────────────────────────
    /** 软删除 100 元记录：删除后所有汇总不含它，撤销后回到原值。 */
    const val EXTRA_DELETED_AMOUNT_CENT = 10_000L // 100.00
    /** 2024-02-29 合法（闰年），2025-02-29 非法（平年）。 */
    val LEAP_VALID_DATE: LocalDate = LocalDate.of(2024, 2, 29)
    /** 跨年边界：2025-12-31 属 2025 年，2026-01-01 属 2026 年。 */
    val YEAR_BOUNDARY_BEFORE: LocalDate = LocalDate.of(2025, 12, 31)
    val YEAR_BOUNDARY_AFTER: LocalDate = LocalDate.of(2026, 1, 1)
    /** 未来日期（冻结时钟今日为 2026-10-07）必须被拒绝。 */
    val FUTURE_DATE: LocalDate = LocalDate.of(2026, 10, 8)
    val FAR_FUTURE_DATE: LocalDate = LocalDate.of(2027, 1, 1)
    /** 上限与下限金额。 */
    const val MAX_AMOUNT_CENT = Limits.MAX_TRANSACTION_CENT // 999,999,999
    const val MIN_AMOUNT_CENT = Limits.MIN_TRANSACTION_CENT // 1
    const val OVER_MAX_AMOUNT_CENT = Limits.MAX_TRANSACTION_CENT + 1L

    // ── A02/A15：12.50 与 0.10+0.20 ───────────────────────────────────────
    const val KEYPAD_INPUT_TEXT = "12.50"
    const val KEYPAD_INPUT_CENT = 1_250L // 12.50 元
    const val A15_FIRST_CENT = 10L // 0.10
    const val A15_SECOND_CENT = 20L // 0.20
    const val A15_TOTAL_CENT = 30L // 0.30，精确，无浮点误差
}

/** 独立（不依赖生产实现）的金额文本 ↔ 整数分换算，只用于测试消息与自检。 */
object Cent {
    /** 307880 -> "3078.80"；把分转成两位小数的元文本，不用 Double。 */
    fun toYuanText(cent: Long): String = BigDecimal.valueOf(cent, 2).toPlainString()

    /** "3078.80" -> 307880；超出两位小数或非数字直接抛异常。 */
    fun fromYuanTextExact(text: String): Long =
        BigDecimal(text.trim()).movePointRight(2).toBigIntegerExact().longValueExact()

    fun describe(cent: Long): String = "${toYuanText(cent)} 元（$cent 分）"
}

/** 断言消息里同时给出分与元，避免出现"看起来只差一分"的模糊失败。 */
fun assertCent(expectedCent: Long, actualCent: Long, label: String) {
    org.junit.Assert.assertEquals(
        "$label：期望 ${Cent.describe(expectedCent)}，实际 ${Cent.describe(actualCent)}",
        expectedCent,
        actualCent,
    )
}

/** 同上，标签在前（两种书写顺序都允许，语义完全相同）。 */
fun assertCent(label: String, expectedCent: Long, actualCent: Long) =
    assertCent(expectedCent, actualCent, label)

fun assertCentList(expected: List<Long>, actual: List<Long>, label: String) {
    org.junit.Assert.assertEquals(
        "$label：期望 ${expected.map { Cent.toYuanText(it) }}，实际 ${actual.map { Cent.toYuanText(it) }}",
        expected,
        actual,
    )
}

/**
 * 验收严格模式。
 *
 * - 默认（阶段一，功能尚未交付）：缺失的能力用 JUnit `assumeTrue` 跳过，
 *   报告里必须记为「未验证」，**绝不计入通过**。
 * - `-Dblueledger.acceptance.strict=true`（阶段二/G4 最终门禁）：跳过即失败，
 *   任何未交付能力都会让门禁变红。
 */
object AcceptancePolicy {
    const val STRICT_PROPERTY: String = "blueledger.acceptance.strict"

    /**
     * 环境变量开关：Gradle 命令行 `-D` 只作用于 Gradle 自身 JVM，
     * 不会自动传给 fork 出来的测试 JVM；环境变量会被测试 JVM 继承，
     * 因此最终门禁用 `$env:BLUELEDGER_ACCEPTANCE_STRICT='true'` 打开严格模式。
     */
    const val STRICT_ENV: String = "BLUELEDGER_ACCEPTANCE_STRICT"

    val strict: Boolean
        get() = java.lang.Boolean.getBoolean(STRICT_PROPERTY) ||
            System.getenv(STRICT_ENV)?.equals("true", ignoreCase = true) == true

    /**
     * 能力门禁：[available] 为 false 表示被依赖的实现尚未交付。
     */
    fun gate(available: Boolean, what: String) {
        if (available) return
        if (strict) {
            org.junit.Assert.fail("严格模式：依赖项「$what」尚未交付，不允许跳过")
        }
        org.junit.Assume.assumeTrue("依赖项「$what」尚未交付：本用例跳过（记为未验证，不计通过）", false)
    }

    /** 按类名探测依赖是否已交付（不反射调用，只判断存在性）。 */
    fun classPresent(className: String): Boolean = runCatching {
        Class.forName(className, false, AcceptancePolicy::class.java.classLoader) != null
    }.getOrDefault(false)
}

// ───────────────────────── 夹具写入（只依赖冻结契约） ─────────────────────────

/** 夹具写入后的 ID 映射：账目标签/账户引用 → 真实写入产生的稳定 ID。 */
class SeededFixture internal constructor(
    val repository: LedgerRepository,
    val accountIds: Map<String, String>,
    val transactionIds: Map<String, String>,
) {
    fun accountId(ref: String): String =
        requireNotNull(accountIds[ref]) { "夹具账户 $ref 没有 ID" }

    fun transactionId(label: String): String =
        requireNotNull(transactionIds[label]) { "夹具账单 $label 没有 ID" }

    fun seed(label: String): Fx.TxSeed = Fx.seed(label)

    /** 由原始夹具生成草稿，可覆盖字段（用于 §12 的 8 条独立修改用例）。 */
    fun draftFor(
        label: String,
        amountCent: Long = seed(label).amountCent,
        occurredOn: LocalDate = seed(label).occurredOn,
        type: TransactionType = seed(label).type,
        categoryId: String = seed(label).categoryId,
        note: String = seed(label).note,
    ): TransactionDraft = TransactionDraft(
        type = type,
        amountCent = amountCent,
        categoryId = categoryId,
        accountId = accountId(seed(label).accountRef),
        occurredOn = occurredOn,
        note = note,
    )

    suspend fun update(
        label: String,
        amountCent: Long = seed(label).amountCent,
        occurredOn: LocalDate = seed(label).occurredOn,
        type: TransactionType = seed(label).type,
        categoryId: String = seed(label).categoryId,
        note: String = seed(label).note,
    ): SaveResult = repository.updateTransaction(
        transactionId(label),
        draftFor(label, amountCent, occurredOn, type, categoryId, note),
    )

    // ── §12 的 8 条独立用例（每条都从原始夹具重置后单独执行） ──────────────
    /** §12-1 T4 → 3200.00。 */
    suspend fun c1_changeT4Amount(): SaveResult = update("T4", amountCent = Expect.C1_T4_AMOUNT_CENT)

    /** §12-2 T4 日期 → 2026-09-30。 */
    suspend fun c2_moveT4ToSeptember(): SaveResult = update("T4", occurredOn = Expect.C2_T4_DATE)

    /** §12-3 软删除 T9（返回撤销凭据）。 */
    suspend fun c3_deleteT9(): Outcome<DeleteReceipt> = repository.softDeleteTransaction(transactionId("T9"))

    suspend fun c3_undoDelete(receipt: DeleteReceipt): MutationResult = repository.undoDelete(receipt)

    /** §12-4 B 期初 → -200.00。 */
    suspend fun c4_setAccountBOpening(cent: Long = Expect.C4_ACCOUNT_B_OPENING_CENT): MutationResult =
        repository.upsertAccount(
            AccountCommand(
                id = accountId(Fx.ACCOUNT_B_REF),
                name = Fx.ACCOUNT_B_NAME,
                kind = Fx.ACCOUNT_B_KIND,
                openingBalanceCent = cent,
            ),
        )

    /** §12-5 归档餐饮分类 / 账户 B。 */
    suspend fun c5_archiveFoodCategory(archived: Boolean = true): MutationResult =
        repository.setCategoryArchived(Fx.CAT_FOOD, archived)

    suspend fun c5_archiveAccountB(archived: Boolean = true): MutationResult =
        repository.setAccountArchived(accountId(Fx.ACCOUNT_B_REF), archived, replacementDefaultId = null)

    /** §12-6/7 预算场景。 */
    suspend fun setBudget(month: YearMonth, cent: Long): MutationResult = repository.setBudget(month, cent)

    suspend fun removeBudget(month: YearMonth): MutationResult = repository.removeBudget(month)

    /** §12-8 T9 → 收入礼金（同一金额，仅改类型与分类）。 */
    suspend fun c8_changeT9ToIncomeGift(): SaveResult = update(
        "T9",
        type = TransactionType.INCOME,
        categoryId = Fx.CAT_GIFT,
    )

    suspend fun accounts(includeArchived: Boolean = true): List<AccountWithBalance> =
        repository.observeAccounts(includeArchived).first()
}

/**
 * 把 §12 夹具写进真实仓库。
 *
 * 前提：仓库已经完成幂等初始化（本函数会先调用 [LedgerRepository.initializeIfNeeded]）。
 * 之后写入两个夹具账户、T1—T9、2026-10 预算；**不写入 2026-09 预算**。
 */
suspend fun LedgerRepository.seedAcceptanceFixture(): SeededFixture {
    initializeIfNeeded().requireSuccess("initializeIfNeeded")

    val accountIds = LinkedHashMap<String, String>()
    accountIds[Fx.ACCOUNT_A_REF] = upsertAccount(
        AccountCommand(
            id = null,
            name = Fx.ACCOUNT_A_NAME,
            kind = Fx.ACCOUNT_A_KIND,
            openingBalanceCent = Fx.ACCOUNT_A_OPENING_CENT,
        ),
    ).requireId("upsertAccount(银行卡)", this, Fx.ACCOUNT_A_NAME)

    accountIds[Fx.ACCOUNT_B_REF] = upsertAccount(
        AccountCommand(
            id = null,
            name = Fx.ACCOUNT_B_NAME,
            kind = Fx.ACCOUNT_B_KIND,
            openingBalanceCent = Fx.ACCOUNT_B_OPENING_CENT,
        ),
    ).requireId("upsertAccount(钱包)", this, Fx.ACCOUNT_B_NAME)

    val transactionIds = LinkedHashMap<String, String>()
    for (seed in Fx.transactions) {
        val draft = TransactionDraft(
            type = seed.type,
            amountCent = seed.amountCent,
            categoryId = seed.categoryId,
            accountId = accountIds.getValue(seed.accountRef),
            occurredOn = seed.occurredOn,
            note = seed.note,
        )
        when (val result = createTransaction(draft, Fx.requestId(seed.label))) {
            is SaveResult.Success -> transactionIds[seed.label] = result.transactionId
            is SaveResult.Failure -> error(
                "夹具写入 ${seed.label} 失败：${result.error.message}（${result.error.javaClass.simpleName}）",
            )
        }
    }

    setBudget(Fx.OCT_2026, Fx.OCT_BUDGET_CENT).requireSuccess("setBudget(2026-10, 4000.00 元)")

    // 2026-09 明确不设置预算。
    return SeededFixture(this, accountIds, transactionIds)
}

// ───────────────────────── 契约结果断言小工具 ─────────────────────────

fun MutationResult.requireSuccess(what: String): MutationResult.Success = when (this) {
    is MutationResult.Success -> this
    is MutationResult.Failure -> throw AssertionError("$what 失败：${error.message}（${error.javaClass.simpleName}）")
}

fun MutationResult.errorOrNull(): LedgerError? = (this as? MutationResult.Failure)?.error

fun SaveResult.errorOrNull(): LedgerError? = (this as? SaveResult.Failure)?.error

fun Outcome<*>.errorOrNull(): LedgerError? = (this as? Outcome.Failure)?.error

/** 账户写入结果：优先用返回的 ID；实现返回 null 时按名称回到仓库里查。 */
private suspend fun MutationResult.requireId(
    what: String,
    repository: LedgerRepository,
    accountName: String,
): String {
    val id = (this as? MutationResult.Success)?.id
    if (!id.isNullOrBlank()) return id
    requireSuccess(what)
    return repository.observeAccounts(includeArchived = true).first()
        .firstOrNull { it.account.name == accountName }
        ?.account
        ?.id
        ?: error("$what 未返回 ID，且按名称「$accountName」也找不到账户")
}

/** 默认分类的稳定 ID 是否真的存在于 [Defaults]（自检用；防止夹具与默认数据脱节）。 */
fun defaultCategoryOrNull(id: String) = Defaults.CATEGORIES.firstOrNull { it.id == id }
