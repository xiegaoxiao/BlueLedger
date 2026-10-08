package com.blueledger.app.feature.backup

import com.blueledger.app.core.contract.Clock
import com.blueledger.app.core.model.AccountKind
import com.blueledger.app.core.model.BackupDecodeResult
import com.blueledger.app.core.model.CategoryIcons
import com.blueledger.app.core.model.Defaults
import com.blueledger.app.core.model.LedgerAccount
import com.blueledger.app.core.model.LedgerCategory
import com.blueledger.app.core.model.LedgerError
import com.blueledger.app.core.model.LedgerSettings
import com.blueledger.app.core.model.LedgerSnapshot
import com.blueledger.app.core.model.LedgerTransaction
import com.blueledger.app.core.model.MonthlyBudget
import com.blueledger.app.core.model.TransactionType
import com.blueledger.app.core.model.ValidationCode
import com.blueledger.app.demo.InMemoryLedgerRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

// ───────────────────────── 时钟 ─────────────────────────

/**
 * A6 测试固定时钟：冻结在验收夹具时间 2026-10-07T00:00:00+08:00[Asia/Shanghai]。
 *
 * 定义在 A6 自己的测试目录内，避免依赖 `demo/` 下 G0 引导期的实现（该实现会被删除），
 * 也避免依赖 A7 的验收文件。
 */
internal class BackupTestClock(
    private val instant: Instant = DEFAULT_INSTANT,
    private val zone: ZoneId = DEFAULT_ZONE,
) : Clock {
    override fun now(): Instant = instant

    override fun zoneId(): ZoneId = zone

    companion object {
        val DEFAULT_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
        val DEFAULT_TODAY: LocalDate = LocalDate.of(2026, 10, 7)
        val DEFAULT_INSTANT: Instant = DEFAULT_TODAY.atStartOfDay(DEFAULT_ZONE).toInstant()
    }
}

// ───────────────────────── 夹具 ─────────────────────────

/**
 * A6 夹具：默认分类（来自 [Defaults]）+ 两个额外归档分类（一个被历史账单引用、一个完全没被引用）
 * 与四个账户（同样覆盖“被引用/无引用”的归档项），三笔有效账单 + 一笔软删除账单。
 *
 * 覆盖目标：
 * - 归档历史引用必须保留；
 * - **没有任何有效账单引用**的归档分类/账户也必须导出（配置本身就是数据）；
 * - 软删除账单不得进入备份与 CSV。
 */
internal object BackupFx {

    val TODAY: LocalDate = BackupTestClock.DEFAULT_TODAY
    val ZONE: ZoneId = BackupTestClock.DEFAULT_ZONE
    val SEP_2026: YearMonth = YearMonth.of(2026, 9)
    val OCT_2026: YearMonth = YearMonth.of(2026, 10)

    const val ACCOUNT_MAIN = "acc_main"
    const val ACCOUNT_WALLET = "acc_wallet"
    const val ACCOUNT_ARCHIVED = "acc_archived"
    const val ACCOUNT_UNUSED = "acc_unused"

    const val CAT_FOOD = "cat_expense_food"
    const val CAT_HOUSING = "cat_expense_housing"
    const val CAT_INCOME_SALARY = "cat_income_salary"
    const val CAT_INCOME_FALLBACK = Defaults.FALLBACK_INCOME_CATEGORY_ID
    const val CAT_ARCHIVED = "cat_expense_archived"
    const val CAT_UNUSED = "cat_expense_unused"

    const val TX_SALARY = "tx_salary"
    const val TX_FOOD = "tx_food"
    const val TX_ARCHIVED_REF = "tx_archived_ref"
    const val TX_DELETED = "tx_deleted"

    /** 有效账单数（软删除那笔不算）。 */
    const val EFFECTIVE_TRANSACTION_COUNT = 3

    /** 当前月（2026-10）有效账单数：TX_SALARY + TX_FOOD。 */
    const val CURRENT_MONTH_TRANSACTION_COUNT = 2

    const val OCT_BUDGET_CENT = 400_000L

    private fun at(date: LocalDate, hour: Int): Instant = date.atTime(hour, 0).atZone(ZONE).toInstant()

    fun categories(): List<LedgerCategory> = Defaults.CATEGORIES.map { it.toCategory() } + listOf(
        // 被 9 月的历史账单引用（归档历史引用）。
        LedgerCategory(
            id = CAT_ARCHIVED,
            type = TransactionType.EXPENSE,
            name = "旧分类",
            iconKey = CategoryIcons.SHOPPING,
            sortOrder = 90,
            isArchived = true,
        ),
        // 完全没有任何有效账单引用，但仍属于用户维护的配置。
        LedgerCategory(
            id = CAT_UNUSED,
            type = TransactionType.EXPENSE,
            name = "闲置分类",
            iconKey = CategoryIcons.MEDICAL,
            sortOrder = 91,
            isArchived = true,
        ),
    )

    fun accounts(): List<LedgerAccount> = listOf(
        LedgerAccount(ACCOUNT_MAIN, "主账户", AccountKind.BANK_CARD, 100_000L),
        LedgerAccount(ACCOUNT_WALLET, "钱包", AccountKind.E_WALLET, -20_000L),
        LedgerAccount(ACCOUNT_ARCHIVED, "旧账户", AccountKind.CASH, 0L, isArchived = true),
        LedgerAccount(ACCOUNT_UNUSED, "闲置账户", AccountKind.OTHER, 5L, isArchived = true),
    )

    fun transactions(): List<LedgerTransaction> = listOf(
        LedgerTransaction(
            id = TX_SALARY,
            type = TransactionType.INCOME,
            amountCent = 1_000_000L,
            categoryId = CAT_INCOME_SALARY,
            accountId = ACCOUNT_MAIN,
            occurredOn = LocalDate.of(2026, 10, 1),
            note = "十月工资",
            createdAt = at(LocalDate.of(2026, 10, 1), 9),
            updatedAt = at(LocalDate.of(2026, 10, 1), 9),
        ),
        LedgerTransaction(
            id = TX_FOOD,
            type = TransactionType.EXPENSE,
            amountCent = 2_850L,
            categoryId = CAT_FOOD,
            accountId = ACCOUNT_WALLET,
            occurredOn = TODAY,
            note = "晚餐",
            createdAt = at(TODAY, 20),
            updatedAt = at(TODAY, 20),
        ),
        LedgerTransaction(
            id = TX_ARCHIVED_REF,
            type = TransactionType.EXPENSE,
            amountCent = 100L,
            categoryId = CAT_ARCHIVED,
            accountId = ACCOUNT_ARCHIVED,
            occurredOn = LocalDate.of(2026, 9, 30),
            note = "历史账单",
            createdAt = at(LocalDate.of(2026, 9, 30), 12),
            updatedAt = at(LocalDate.of(2026, 9, 30), 12),
        ),
        LedgerTransaction(
            id = TX_DELETED,
            type = TransactionType.EXPENSE,
            amountCent = 500L,
            categoryId = CAT_FOOD,
            accountId = ACCOUNT_MAIN,
            occurredOn = LocalDate.of(2026, 10, 5),
            note = "已删除",
            createdAt = at(LocalDate.of(2026, 10, 5), 8),
            updatedAt = at(LocalDate.of(2026, 10, 5), 9),
            deletedAt = at(LocalDate.of(2026, 10, 5), 9),
        ),
    )

    /** 设置里刻意带一个“上一次成功备份时间”，用来证明它**不会**写进备份文件。 */
    fun settings(): LedgerSettings = LedgerSettings(
        defaultAccountId = ACCOUNT_MAIN,
        lastUsedAccountId = ACCOUNT_WALLET,
        hideAmounts = false,
        currency = LedgerSettings.CURRENCY_CNY,
        lastBackupAt = at(LocalDate.of(2026, 10, 6), 21),
        lastBackupFileName = "旧备份.blueledger.json",
    )

    fun budgets(): List<MonthlyBudget> = listOf(MonthlyBudget(OCT_2026, OCT_BUDGET_CENT))

    fun snapshot(exportedAt: Instant = BackupTestClock.DEFAULT_INSTANT): LedgerSnapshot = LedgerSnapshot(
        exportedAt = exportedAt,
        currency = LedgerSettings.CURRENCY_CNY,
        transactions = transactions(),
        categories = categories(),
        accounts = accounts(),
        budgets = budgets(),
        settings = settings(),
    )

    fun repository(clock: Clock = BackupTestClock()): InMemoryLedgerRepository = InMemoryLedgerRepository(
        clock = clock,
        initialCategories = categories(),
        initialAccounts = accounts(),
        initialTransactions = transactions(),
        initialBudgets = budgets().associate { it.yearMonth to it.amountCent },
        initialSettings = settings(),
    )

    fun codec(clock: Clock = BackupTestClock()): JsonBackupCodec = JsonBackupCodec(clock)

    fun validBackupText(): String = codec().encode(snapshot())

    /** 自定义备注的账单集合（保留分类/账户/金额合法），用于 CSV 转义与公式防护测试。 */
    fun snapshotWithNotes(notes: List<String>): LedgerSnapshot {
        val base = snapshot()
        val template = base.transactions.first { it.id == TX_FOOD }
        val transactions = notes.mapIndexed { index, note ->
            template.copy(
                id = "tx_note_$index",
                note = note,
                occurredOn = TODAY.minusDays(index.toLong()),
                createdAt = at(TODAY.minusDays(index.toLong()), 10),
                updatedAt = at(TODAY.minusDays(index.toLong()), 10),
            )
        }
        return base.copy(transactions = transactions)
    }
}

// ───────────────────────── JSON 篡改工具 ─────────────────────────

/** 在夹具 JSON 上做最小改动，用于逐条拒绝用例。 */
internal object BackupJson {

    private val pretty = Json { prettyPrint = true }
    private val compact = Json

    fun parse(text: String): JsonObject = pretty.parseToJsonElement(text).jsonObject

    fun render(root: JsonObject): String = pretty.encodeToString(JsonObject.serializer(), root)

    /** 紧凑输出：用于构造超过体积巨大但不超过 32 MiB 的“条目超限”文件。 */
    fun renderCompact(root: JsonObject): String = compact.encodeToString(JsonObject.serializer(), root)

    fun edit(text: String, block: (JsonObject) -> JsonObject): String = render(block(parse(text)))

    fun editCompact(text: String, block: (JsonObject) -> JsonObject): String =
        renderCompact(block(parse(text)))
}

internal fun JsonObject.with(key: String, value: JsonElement): JsonObject =
    JsonObject(toMutableMap().apply { put(key, value) })

internal fun JsonObject.withString(key: String, value: String): JsonObject = with(key, JsonPrimitive(value))

internal fun JsonObject.withInt(key: String, value: Int): JsonObject = with(key, JsonPrimitive(value))

internal fun JsonObject.withBoolean(key: String, value: Boolean): JsonObject =
    with(key, JsonPrimitive(value))

internal fun JsonObject.without(key: String): JsonObject = JsonObject(filterKeys { it != key })

internal fun JsonObject.transactionObjects(): List<JsonObject> =
    getValue("transactions").jsonArray.map { it.jsonObject }

internal fun JsonObject.categoryObjects(): List<JsonObject> =
    getValue("categories").jsonArray.map { it.jsonObject }

internal fun JsonObject.accountObjects(): List<JsonObject> =
    getValue("accounts").jsonArray.map { it.jsonObject }

internal fun JsonObject.budgetObjects(): List<JsonObject> =
    getValue("budgets").jsonArray.map { it.jsonObject }

internal fun JsonObject.withTransactions(items: List<JsonObject>): JsonObject =
    with("transactions", JsonArray(items))

internal fun JsonObject.withCategories(items: List<JsonObject>): JsonObject =
    with("categories", JsonArray(items))

internal fun JsonObject.withAccounts(items: List<JsonObject>): JsonObject = with("accounts", JsonArray(items))

internal fun JsonObject.withBudgets(items: List<JsonObject>): JsonObject = with("budgets", JsonArray(items))

internal fun JsonObject.withSettings(block: (JsonObject) -> JsonObject): JsonObject =
    with("settings", block(getValue("settings").jsonObject))

internal fun JsonObject.mapTransaction(id: String, block: (JsonObject) -> JsonObject): JsonObject =
    withTransactions(
        transactionObjects().map { if (it.idValue() == id) block(it) else it },
    )

internal fun JsonObject.mapCategory(id: String, block: (JsonObject) -> JsonObject): JsonObject =
    withCategories(
        categoryObjects().map { if (it.idValue() == id) block(it) else it },
    )

internal fun JsonObject.mapAccount(id: String, block: (JsonObject) -> JsonObject): JsonObject =
    withAccounts(
        accountObjects().map { if (it.idValue() == id) block(it) else it },
    )

internal fun JsonObject.idValue(): String = getValue("id").jsonPrimitive.content

// ───────────────────────── 断言小工具 ─────────────────────────

internal fun LedgerError.backupCode(): ValidationCode? = (this as? LedgerError.Backup)?.code

internal fun BackupDecodeResult.invalidError(): LedgerError.Backup? =
    (this as? BackupDecodeResult.Invalid)?.error as? LedgerError.Backup

internal fun BackupDecodeResult.validOrNull(): BackupDecodeResult.Valid? = this as? BackupDecodeResult.Valid

// ───────────────────────── 文件网关替身 ─────────────────────────

/**
 * 文件访问替身：可注入写入失败、读取结果与显示名，并可让写入挂起（验证“操作中禁用重复操作”）。
 */
internal class FakeBackupFileGateway : BackupFileGateway {

    data class WriteCall(val reference: String, val mimeType: String, val text: String)

    val writes = mutableListOf<WriteCall>()

    var writeFailure: LedgerError? = null

    var readResult: BackupTextReadResult? = null

    var displayNameValue: String? = null

    /** 非 null 时写入会挂起，直到测试 complete。 */
    var writeGate: CompletableDeferred<Unit>? = null

    override suspend fun writeText(
        reference: String,
        mimeType: String,
        text: String,
    ): BackupFileWriteResult {
        writeGate?.await()
        writeFailure?.let { return BackupFileWriteResult.Failure(it) }
        writes += WriteCall(reference, mimeType, text)
        return BackupFileWriteResult.Success(text.toByteArray(Charsets.UTF_8).size.toLong())
    }

    override suspend fun readText(reference: String): BackupTextReadResult =
        readResult ?: BackupTextReadResult.Failure(LedgerError.Storage("替身未配置读取内容"))

    override suspend fun displayName(reference: String): String? = displayNameValue
}

// ───────────────────────── 主线程调度器规则 ─────────────────────────

/**
 * ViewModel 使用 `Dispatchers.Main.immediate`；JVM 单测里换成 Unconfined 便于同步断言。
 *
 * 注意：JUnit4 要求 `@Rule` 字段 public，因此这个类型必须是 public（不能是 internal），
 * 否则 public 测试类会暴露 internal 类型而编译失败。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

// ───────────────────────── CSV 解析（RFC 4180） ─────────────────────────

/** 测试用 CSV 解析器：用来证明导出的文件能被“表格软件式”的读取器正确解析。 */
internal object CsvParser {

    fun parse(text: String): List<List<String>> {
        val content = text.removePrefix(LedgerCsvExporter.BOM)
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < content.length) {
            val ch = content[index]
            when {
                inQuotes && ch == '"' && index + 1 < content.length && content[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }

                ch == '"' -> inQuotes = !inQuotes

                !inQuotes && ch == ',' -> {
                    row.add(field.toString())
                    field.clear()
                }

                !inQuotes && (ch == '\r' || ch == '\n') -> {
                    if (ch == '\r' && index + 1 < content.length && content[index + 1] == '\n') index++
                    row.add(field.toString())
                    field.clear()
                    rows.add(row)
                    row = mutableListOf()
                }

                else -> field.append(ch)
            }
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows
    }
}
