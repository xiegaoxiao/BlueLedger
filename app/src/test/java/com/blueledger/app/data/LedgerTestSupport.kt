package com.blueledger.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.blueledger.app.core.model.AccountCommand
import com.blueledger.app.core.model.AccountKind
import com.blueledger.app.core.model.CategoryCommand
import com.blueledger.app.core.model.CategoryIcons
import com.blueledger.app.core.model.Defaults
import com.blueledger.app.core.model.LedgerError
import com.blueledger.app.core.model.MutationResult
import com.blueledger.app.core.model.Outcome
import com.blueledger.app.core.model.RestoreResult
import com.blueledger.app.core.model.SaveResult
import com.blueledger.app.core.model.TransactionType
import com.blueledger.app.core.model.ValidationCode
import com.blueledger.app.core.time.FixedClock
import com.blueledger.app.data.local.LedgerDatabase
import com.blueledger.app.data.local.TransactionEntity
import com.blueledger.app.data.repository.LedgerRepositoryImpl
import com.blueledger.app.data.repository.LedgerValidation
import com.blueledger.app.data.repository.SequentialLedgerIdGenerator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.Executors

/** §12 固定验收夹具中的一笔账单。 */
data class FixtureTransaction(
    val id: String,
    val date: LocalDate,
    val type: TransactionType,
    val amountCent: Long,
    val categoryId: String,
    val accountId: String,
    val note: String,
)

/** 夹具里的账户/分类 ID（初始化后填充）。 */
class FixtureIds {
    lateinit var accountA: String
    lateinit var accountB: String
    lateinit var housingCategoryId: String
}

/**
 * Room + 真实仓库的测试基类。
 *
 * 统一提供：内存数据库、冻结时钟（2026-10-07 Asia/Shanghai）、可预测 ID 生成器，
 * 以及 §12 的精确验收夹具。所有测试跑在同一套生产实现上，不使用任何 Fake 仓库。
 */
abstract class LedgerRoomTestBase {

    protected lateinit var db: LedgerDatabase
    protected lateinit var repository: LedgerRepositoryImpl
    protected lateinit var clock: FixedClock
    protected val fixtureIds = FixtureIds()

    /** 夹具基准时间：2026-10-07T00:00:00+08:00。 */
    protected val baseMillis: Long = FixedClock.DEFAULT_INSTANT.toEpochMilli()

    protected val today: LocalDate get() = LocalDate.of(2026, 10, 7)
    protected val october: YearMonth get() = YearMonth.of(2026, 10)
    protected val september: YearMonth get() = YearMonth.of(2026, 9)

    @Before
    fun setUpDatabase() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(Executors.newFixedThreadPool(2))
            .build()
        clock = FixedClock()
        repository = LedgerRepositoryImpl(
            db = db,
            clock = clock,
            idGenerator = SequentialLedgerIdGenerator("gen"),
        )
    }

    @After
    fun tearDownDatabase() {
        db.close()
    }

    /**
     * 统一在 runBlocking 中执行测试体。
     *
     * 返回类型固定为 Unit：JUnit4 要求 @Test 方法返回 void，
     * 而 `= runDb { ... }` 的 lambda 末尾若是非 Unit 表达式（例如 `requireFailure()` 返回 LedgerError），
     * 会让整个测试类报 `initializationError`。这里把返回类型收敛掉，避免这类隐性错误。
     */
    protected fun runDb(block: suspend () -> Unit): Unit = runBlocking { block() }

    // ───────────────────── 夹具 ─────────────────────

    /**
     * 按 docs/AI开发提示词.md §12 建立夹具：
     * A银行卡 期初 100,000 分、B钱包 期初 20,000 分；T1—T9；2026-10 预算 400,000 分。
     * 账单直接写表，保证 id 与 createdAt 完全可控；其余实体走真实仓库 API。
     */
    protected suspend fun seedAcceptanceFixture() {
        repository.initializeIfNeeded()

        fixtureIds.accountA = repository.upsertAccount(
            AccountCommand(name = "A银行卡", kind = AccountKind.BANK_CARD, openingBalanceCent = 100_000L),
        ).requireId()
        fixtureIds.accountB = repository.upsertAccount(
            AccountCommand(name = "B钱包", kind = AccountKind.E_WALLET, openingBalanceCent = 20_000L),
        ).requireId()
        // 将这两个默认项改名，继续验证自定义分类新增、同额排序及归档历史。
        repository.upsertCategory(CategoryCommand(id = "cat_expense_housing", type = TransactionType.EXPENSE, name = "居住", iconKey = CategoryIcons.HOUSING)).requireSuccess()
        repository.upsertCategory(CategoryCommand(id = "cat_expense_pet", type = TransactionType.EXPENSE, name = "宠物预置", iconKey = "pet")).requireSuccess()
        fixtureIds.housingCategoryId = repository.upsertCategory(
            CategoryCommand(
                type = TransactionType.EXPENSE,
                name = "住房",
                iconKey = CategoryIcons.HOUSING,
            ),
        ).requireId()

        insertFixtureTransactions()
        repository.setBudget(october, 400_000L).requireSuccess()
    }

    protected suspend fun insertFixtureTransactions() {
        fixtureTransactions(fixtureIds).forEachIndexed { index, item ->
            db.transactionDao().insert(
                TransactionEntity(
                    id = item.id,
                    type = item.type,
                    amountCent = item.amountCent,
                    categoryId = item.categoryId,
                    accountId = item.accountId,
                    occurredOnEpochDay = item.date.toEpochDay(),
                    note = item.note,
                    noteKey = LedgerValidation.normalizeKey(item.note),
                    createdAtEpochMillis = baseMillis + index * 1_000L,
                    updatedAtEpochMillis = baseMillis + index * 1_000L,
                ),
            )
        }
    }

    companion object {
        const val FOOD = "cat_expense_food"
        const val TRANSPORT = "cat_expense_transport"
        const val SALARY = "cat_income_salary"
        const val PART_TIME = "cat_income_parttime"
        const val GIFT = "cat_income_gift"

        /** §12 的 9 笔有效账单，顺序即 createdAt 递增顺序。 */
        fun fixtureTransactions(ids: FixtureIds): List<FixtureTransaction> = listOf(
            FixtureTransaction("T1", LocalDate.of(2026, 9, 30), TransactionType.INCOME, 500_000L, SALARY, ids.accountA, "九月工资"),
            FixtureTransaction("T2", LocalDate.of(2026, 9, 30), TransactionType.EXPENSE, 10_000L, FOOD, ids.accountA, "九月晚餐"),
            FixtureTransaction("T3", LocalDate.of(2026, 10, 1), TransactionType.INCOME, 1_000_000L, SALARY, ids.accountA, "十月工资"),
            FixtureTransaction("T4", LocalDate.of(2026, 10, 1), TransactionType.EXPENSE, 300_000L, ids.housingCategoryId, ids.accountA, "十月房租"),
            FixtureTransaction("T5", LocalDate.of(2026, 10, 2), TransactionType.EXPENSE, 10L, FOOD, ids.accountB, "早餐"),
            FixtureTransaction("T6", LocalDate.of(2026, 10, 2), TransactionType.EXPENSE, 20L, FOOD, ids.accountB, "午餐"),
            FixtureTransaction("T7", LocalDate.of(2026, 10, 3), TransactionType.EXPENSE, 5_000L, TRANSPORT, ids.accountB, "地铁"),
            FixtureTransaction("T8", LocalDate.of(2026, 10, 6), TransactionType.INCOME, 80_000L, PART_TIME, ids.accountB, "设计兼职"),
            FixtureTransaction("T9", LocalDate.of(2026, 10, 7), TransactionType.EXPENSE, 2_850L, FOOD, ids.accountB, "晚餐"),
        )

        /** 默认分类数量：9 支出 + 5 收入。 */
        val DEFAULT_CATEGORY_COUNT = Defaults.CATEGORIES.size

        fun instantAt(date: LocalDate): Instant = date.atStartOfDay(FixedClock.DEFAULT_ZONE).toInstant()

        /** 夹具断言常用：默认账户 ID。 */
        const val DEFAULT_ACCOUNT_ID = Defaults.DEFAULT_ACCOUNT_ID
    }
}

// ───────────────────── 结果断言助手 ─────────────────────

fun MutationResult.requireId(): String =
    (this as? MutationResult.Success)?.id ?: throw AssertionError("期望成功，实际是 $this")

fun MutationResult.requireSuccess(): MutationResult.Success =
    this as? MutationResult.Success ?: throw AssertionError("期望成功，实际是 $this")

fun MutationResult.requireFailure(): LedgerError =
    (this as? MutationResult.Failure)?.error ?: throw AssertionError("期望失败，实际是 $this")

fun <T> Outcome<T>.requireValue(): T =
    (this as? Outcome.Success<T>)?.value ?: throw AssertionError("期望成功，实际是 $this")

fun <T> Outcome<T>.requireFailure(): LedgerError =
    (this as? Outcome.Failure)?.error ?: throw AssertionError("期望失败，实际是 $this")

fun SaveResult.requireSaved(): SaveResult.Success =
    this as? SaveResult.Success ?: throw AssertionError("期望保存成功，实际是 $this")

fun SaveResult.requireFailure(): LedgerError =
    (this as? SaveResult.Failure)?.error ?: throw AssertionError("期望保存失败，实际是 $this")

fun RestoreResult.requireSuccess(): RestoreResult.Success =
    this as? RestoreResult.Success ?: throw AssertionError("期望恢复成功，实际是 $this")

fun LedgerError.requireValidationCode(expected: ValidationCode) {
    val actual = when (this) {
        is LedgerError.Validation -> code
        is LedgerError.Conflict -> code
        is LedgerError.Backup -> code
        is LedgerError.NotFound -> null
        is LedgerError.Storage -> null
    }
    assertEquals("错误码不匹配：$this", expected, actual)
    assertTrue("错误应带可读说明", message.isNotBlank())
}
