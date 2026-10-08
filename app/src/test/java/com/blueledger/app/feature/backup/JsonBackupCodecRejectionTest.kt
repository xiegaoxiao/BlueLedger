package com.blueledger.app.feature.backup

import com.blueledger.app.core.contract.BackupLimits
import com.blueledger.app.core.model.TransactionFilter
import com.blueledger.app.core.model.ValidationCode
import com.blueledger.app.demo.InMemoryLedgerRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 备份拒绝矩阵。
 *
 * 每条用例都同时断言两件事：
 * 1. 返回**具体** [ValidationCode]（不是笼统失败）；
 * 2. **原库完全不变**——用共享的 [InMemoryLedgerRepository] 替身在解码前后对比快照与账单数。
 *
 * 用例覆盖 docs/AI开发提示词.md §13「文件与恢复」列出的全部拒绝理由。
 */
class JsonBackupCodecRejectionTest {

    private val clock = BackupTestClock()
    private val codec = JsonBackupCodec(clock)
    private val valid: String = BackupFx.validBackupText()

    private lateinit var repository: InMemoryLedgerRepository

    @Before
    fun setUp() {
        repository = BackupFx.repository(clock)
    }

    private fun assertRejected(label: String, tampered: String, expected: ValidationCode) = runBlocking {
        val beforeSnapshot = repository.exportConsistentSnapshot()
        val beforeCount = repository.observeTransactions(TransactionFilter()).first().totalCount

        val result = codec.decode(tampered)
        val error = requireNotNull(result.invalidError()) { "[$label] 应当被拒绝，实际结果=$result" }
        assertEquals("[$label] 错误码", expected, error.code)
        assertTrue("[$label] 必须有可读的中文原因", error.message.isNotBlank())

        assertEquals(
            "[$label] 拒绝后原库快照必须完全不变",
            beforeSnapshot,
            repository.exportConsistentSnapshot(),
        )
        assertEquals(
            "[$label] 拒绝后原库账单数必须不变",
            beforeCount,
            repository.observeTransactions(TransactionFilter()).first().totalCount,
        )
    }

    /** 对照组：未篡改的备份必须能通过，证明上面每条失败都来自那一处改动。 */
    @Test
    fun `未篡改的备份可以通过校验`() = runBlocking {
        val result = codec.decode(valid)
        assertTrue("原始备份必须有效，实际=$result", result is com.blueledger.app.core.model.BackupDecodeResult.Valid)
    }

    // ───────────────────────── 解析与顶层标识 ─────────────────────────

    @Test
    fun `损坏的 JSON 被拒绝`() =
        assertRejected("损坏JSON", "{ 这不是 JSON", ValidationCode.BACKUP_NOT_JSON)

    @Test
    fun `空文本被拒绝`() = assertRejected("空文本", "", ValidationCode.BACKUP_NOT_JSON)

    @Test
    fun `顶层不是对象被拒绝`() =
        assertRejected("顶层数组", "[1, 2, 3]", ValidationCode.BACKUP_NOT_JSON)

    @Test
    fun `产品标识不匹配被拒绝`() = assertRejected(
        "product=other",
        BackupJson.edit(valid) { it.withString("product", "other-app") },
        ValidationCode.BACKUP_PRODUCT_MISMATCH,
    )

    @Test
    fun `产品标识缺失被拒绝`() = assertRejected(
        "product 缺失",
        BackupJson.edit(valid) { it.without("product") },
        ValidationCode.BACKUP_PRODUCT_MISMATCH,
    )

    @Test
    fun `版本不支持被拒绝 v2`() = assertRejected(
        "schemaVersion=3",
        BackupJson.edit(valid) { it.withInt("schemaVersion", 3) },
        ValidationCode.BACKUP_UNSUPPORTED_VERSION,
    )

    @Test
    fun `版本不支持被拒绝 v0`() = assertRejected(
        "schemaVersion=0",
        BackupJson.edit(valid) { it.withInt("schemaVersion", 0) },
        ValidationCode.BACKUP_UNSUPPORTED_VERSION,
    )

    @Test
    fun `版本号缺失被拒绝`() = assertRejected(
        "schemaVersion 缺失",
        BackupJson.edit(valid) { it.without("schemaVersion") },
        ValidationCode.BACKUP_UNSUPPORTED_VERSION,
    )

    @Test
    fun `版本号类型错误被拒绝`() = assertRejected(
        "schemaVersion=\"1\"",
        BackupJson.edit(valid) { it.withString("schemaVersion", "1") },
        ValidationCode.BACKUP_FIELD_TYPE_INVALID,
    )

    @Test
    fun `币种不匹配被拒绝`() = assertRejected(
        "currency=USD",
        BackupJson.edit(valid) { it.withString("currency", "USD") },
        ValidationCode.BACKUP_CURRENCY_MISMATCH,
    )

    @Test
    fun `币种缺失被拒绝`() = assertRejected(
        "currency 缺失",
        BackupJson.edit(valid) { it.without("currency") },
        ValidationCode.BACKUP_CURRENCY_MISMATCH,
    )

    // ───────────────────────── 结构与字段类型 ─────────────────────────

    @Test
    fun `transactions 不是数组被拒绝`() = assertRejected(
        "transactions 类型错",
        BackupJson.edit(valid) { it.with("transactions", JsonPrimitive(1)) },
        ValidationCode.BACKUP_FIELD_TYPE_INVALID,
    )

    @Test
    fun `缺少 budgets 数组被拒绝`() = assertRejected(
        "缺少 budgets",
        BackupJson.edit(valid) { it.without("budgets") },
        ValidationCode.BACKUP_FIELD_TYPE_INVALID,
    )

    @Test
    fun `缺少 settings 对象被拒绝`() = assertRejected(
        "缺少 settings",
        BackupJson.edit(valid) { it.without("settings") },
        ValidationCode.BACKUP_FIELD_TYPE_INVALID,
    )

    @Test
    fun `未知字段被拒绝`() = assertRejected(
        "未知字段",
        BackupJson.edit(valid) { it.with("unknownFieldFromFuture", JsonPrimitive("x")) },
        ValidationCode.BACKUP_FIELD_TYPE_INVALID,
    )

    @Test
    fun `金额字段类型错误被拒绝`() = assertRejected(
        "amountCent 是字符串",
        BackupJson.edit(valid) { it.mapTransaction(BackupFx.TX_FOOD) { tx -> tx.withString("amountCent", "2850") } },
        ValidationCode.BACKUP_FIELD_TYPE_INVALID,
    )

    @Test
    fun `生成时间格式非法被拒绝`() = assertRejected(
        "exportedAt 非 ISO",
        BackupJson.edit(valid) { it.withString("exportedAt", "2026-10-07 00:00") },
        ValidationCode.BACKUP_DATE_INVALID,
    )

    @Test
    fun `账单时间戳格式非法被拒绝`() = assertRejected(
        "createdAt 非 ISO",
        BackupJson.edit(valid) { it.mapTransaction(BackupFx.TX_FOOD) { tx -> tx.withString("createdAt", "昨天") } },
        ValidationCode.BACKUP_FIELD_TYPE_INVALID,
    )

    @Test
    fun `分类收支类型非法被拒绝`() = assertRejected(
        "category.type=BOTH",
        BackupJson.edit(valid) { it.mapCategory(BackupFx.CAT_FOOD) { c -> c.withString("type", "BOTH") } },
        ValidationCode.BACKUP_FIELD_TYPE_INVALID,
    )

    @Test
    fun `账户类型非法被拒绝`() = assertRejected(
        "account.kind=BITCOIN",
        BackupJson.edit(valid) { it.mapAccount(BackupFx.ACCOUNT_MAIN) { a -> a.withString("kind", "BITCOIN") } },
        ValidationCode.BACKUP_FIELD_TYPE_INVALID,
    )

    @Test
    fun `备注超过 200 字符被拒绝`() = assertRejected(
        "note 201 字符",
        BackupJson.edit(valid) {
            it.mapTransaction(BackupFx.TX_FOOD) { tx -> tx.withString("note", "x".repeat(201)) }
        },
        ValidationCode.BACKUP_FIELD_TYPE_INVALID,
    )

    // ───────────────────────── ID 与引用 ─────────────────────────

    @Test
    fun `重复账单 ID 被拒绝`() = assertRejected(
        "重复账单 ID",
        BackupJson.edit(valid) { root ->
            val items = root.transactionObjects()
            root.withTransactions(items + items.first())
        },
        ValidationCode.BACKUP_DUPLICATE_ID,
    )

    @Test
    fun `重复分类 ID 被拒绝`() = assertRejected(
        "重复分类 ID",
        BackupJson.edit(valid) { root ->
            val items = root.categoryObjects()
            root.withCategories(items + items.first())
        },
        ValidationCode.BACKUP_DUPLICATE_ID,
    )

    @Test
    fun `重复账户 ID 被拒绝`() = assertRejected(
        "重复账户 ID",
        BackupJson.edit(valid) { root ->
            val items = root.accountObjects()
            root.withAccounts(items + items.first())
        },
        ValidationCode.BACKUP_DUPLICATE_ID,
    )

    @Test
    fun `分类引用缺失被拒绝`() = assertRejected(
        "categoryId 缺失",
        BackupJson.edit(valid) {
            it.mapTransaction(BackupFx.TX_FOOD) { tx -> tx.withString("categoryId", "cat_not_exists") }
        },
        ValidationCode.BACKUP_MISSING_REFERENCE,
    )

    @Test
    fun `账户引用缺失被拒绝`() = assertRejected(
        "accountId 缺失",
        BackupJson.edit(valid) {
            it.mapTransaction(BackupFx.TX_FOOD) { tx -> tx.withString("accountId", "acc_not_exists") }
        },
        ValidationCode.BACKUP_MISSING_REFERENCE,
    )

    @Test
    fun `分类类型与账单类型不一致被拒绝`() = assertRejected(
        "支出账单用收入分类",
        BackupJson.edit(valid) {
            it.mapTransaction(BackupFx.TX_FOOD) { tx -> tx.withString("type", "INCOME") }
        },
        ValidationCode.BACKUP_CATEGORY_TYPE_MISMATCH,
    )

    @Test
    fun `分类 ID 为空被拒绝`() = assertRejected(
        "分类 ID 空",
        BackupJson.edit(valid) { it.mapCategory(BackupFx.CAT_FOOD) { c -> c.withString("id", "") } },
        ValidationCode.BACKUP_FIELD_TYPE_INVALID,
    )

    // ───────────────────────── 日期 ─────────────────────────

    @Test
    fun `未来日期被拒绝`() = assertRejected(
        "2026-10-08 未来",
        BackupJson.edit(valid) {
            it.mapTransaction(BackupFx.TX_FOOD) { tx -> tx.withString("occurredOn", "2026-10-08") }
        },
        ValidationCode.BACKUP_DATE_FUTURE,
    )

    @Test
    fun `无效日期 2025-02-29 被拒绝`() = assertRejected(
        "2025-02-29 非法",
        BackupJson.edit(valid) {
            it.mapTransaction(BackupFx.TX_FOOD) { tx -> tx.withString("occurredOn", "2025-02-29") }
        },
        ValidationCode.BACKUP_DATE_INVALID,
    )

    @Test
    fun `非补零日期被拒绝`() = assertRejected(
        "2026-2-9",
        BackupJson.edit(valid) {
            it.mapTransaction(BackupFx.TX_FOOD) { tx -> tx.withString("occurredOn", "2026-2-9") }
        },
        ValidationCode.BACKUP_DATE_INVALID,
    )

    @Test
    fun `预算月份非法被拒绝`() = assertRejected(
        "2026-13",
        BackupJson.edit(valid) {
            it.withBudgets(it.budgetObjects().map { b -> b.withString("yearMonth", "2026-13") })
        },
        ValidationCode.BACKUP_DATE_INVALID,
    )

    // ───────────────────────── 金额与名称 ─────────────────────────

    @Test
    fun `金额为零被拒绝`() = assertRejected(
        "amountCent=0",
        BackupJson.edit(valid) {
            it.mapTransaction(BackupFx.TX_FOOD) { tx -> tx.withInt("amountCent", 0) }
        },
        ValidationCode.BACKUP_AMOUNT_OUT_OF_RANGE,
    )

    @Test
    fun `负金额被拒绝`() = assertRejected(
        "amountCent=-2850",
        BackupJson.edit(valid) {
            it.mapTransaction(BackupFx.TX_FOOD) { tx -> tx.withInt("amountCent", -2_850) }
        },
        ValidationCode.BACKUP_AMOUNT_OUT_OF_RANGE,
    )

    @Test
    fun `金额超过上限被拒绝`() = assertRejected(
        "amountCent 超上限",
        BackupJson.edit(valid) {
            it.mapTransaction(BackupFx.TX_FOOD) { tx -> tx.withInt("amountCent", 1_000_000_000) }
        },
        ValidationCode.BACKUP_AMOUNT_OUT_OF_RANGE,
    )

    @Test
    fun `期初余额超出范围被拒绝`() = assertRejected(
        "openingBalance 超限",
        BackupJson.edit(valid) {
            it.mapAccount(BackupFx.ACCOUNT_MAIN) { a -> a.withInt("openingBalanceCent", 1_000_000_000) }
        },
        ValidationCode.BACKUP_AMOUNT_OUT_OF_RANGE,
    )

    @Test
    fun `分类名超过 12 字符被拒绝`() = assertRejected(
        "分类名 13 字符",
        BackupJson.edit(valid) {
            it.mapCategory(BackupFx.CAT_FOOD) { c -> c.withString("name", "x".repeat(13)) }
        },
        ValidationCode.BACKUP_NAME_CONSTRAINT,
    )

    @Test
    fun `分类名为空白被拒绝`() = assertRejected(
        "分类名空白",
        BackupJson.edit(valid) {
            it.mapCategory(BackupFx.CAT_FOOD) { c -> c.withString("name", "   ") }
        },
        ValidationCode.BACKUP_NAME_CONSTRAINT,
    )

    @Test
    fun `同类型分类名重复被拒绝`() = assertRejected(
        "支出分类重名",
        BackupJson.edit(valid) {
            it.mapCategory(BackupFx.CAT_HOUSING) { c -> c.withString("name", "餐饮") }
        },
        ValidationCode.BACKUP_NAME_CONSTRAINT,
    )

    @Test
    fun `账户名超过 20 字符被拒绝`() = assertRejected(
        "账户名 21 字符",
        BackupJson.edit(valid) {
            it.mapAccount(BackupFx.ACCOUNT_MAIN) { a -> a.withString("name", "x".repeat(21)) }
        },
        ValidationCode.BACKUP_NAME_CONSTRAINT,
    )

    @Test
    fun `可用账户重名被拒绝`() = assertRejected(
        "可用账户重名",
        BackupJson.edit(valid) {
            it.mapAccount(BackupFx.ACCOUNT_WALLET) { a -> a.withString("name", "主账户") }
        },
        ValidationCode.BACKUP_NAME_CONSTRAINT,
    )

    @Test
    fun `非法图标被拒绝`() = assertRejected(
        "iconKey 非法",
        BackupJson.edit(valid) {
            it.mapCategory(BackupFx.CAT_FOOD) { c -> c.withString("iconKey", "not_an_icon") }
        },
        ValidationCode.BACKUP_ICON_INVALID,
    )

    // ───────────────────────── 预算 设置 兜底 ─────────────────────────

    @Test
    fun `预算月份重复被拒绝`() = assertRejected(
        "预算月份重复",
        BackupJson.edit(valid) { root ->
            val items = root.budgetObjects()
            root.withBudgets(items + items.first())
        },
        ValidationCode.BACKUP_BUDGET_DUPLICATE_MONTH,
    )

    @Test
    fun `预算金额非正被拒绝`() = assertRejected(
        "预算 0",
        BackupJson.edit(valid) {
            it.withBudgets(it.budgetObjects().map { b -> b.withInt("amountCent", 0) })
        },
        ValidationCode.BACKUP_AMOUNT_OUT_OF_RANGE,
    )

    @Test
    fun `默认账户不存在被拒绝`() = assertRejected(
        "defaultAccountId 不存在",
        BackupJson.edit(valid) {
            it.withSettings { s -> s.withString("defaultAccountId", "acc_not_exists") }
        },
        ValidationCode.BACKUP_DEFAULT_ACCOUNT_INVALID,
    )

    @Test
    fun `默认账户已归档被拒绝`() = assertRejected(
        "默认账户已归档",
        BackupJson.edit(valid) {
            it.withSettings { s -> s.withString("defaultAccountId", BackupFx.ACCOUNT_UNUSED) }
        },
        ValidationCode.BACKUP_DEFAULT_ACCOUNT_INVALID,
    )

    @Test
    fun `没有可用账户被拒绝`() = assertRejected(
        "全部账户归档",
        BackupJson.edit(valid) { root ->
            root.withAccounts(root.accountObjects().map { a -> a.withBoolean("isArchived", true) })
        },
        ValidationCode.BACKUP_NO_ACTIVE_ACCOUNT,
    )

    @Test
    fun `缺少收入兜底分类被拒绝`() = assertRejected(
        "收入兜底缺失",
        BackupJson.edit(valid) {
            it.mapCategory(BackupFx.CAT_INCOME_FALLBACK) { c -> c.withBoolean("isFallback", false) }
        },
        ValidationCode.BACKUP_NO_FALLBACK_CATEGORY,
    )

    // ───────────────────────── 条目数上限 ─────────────────────────

    @Test
    fun `分类条目超限被拒绝`() = assertRejected(
        "分类超限",
        BackupJson.editCompact(valid) { root ->
            val first = root.categoryObjects().first()
            root.withCategories(List(BackupLimits.MAX_CATEGORIES + 1) { first })
        },
        ValidationCode.BACKUP_TOO_MANY_ENTRIES,
    )

    @Test
    fun `账户条目超限被拒绝`() = assertRejected(
        "账户超限",
        BackupJson.editCompact(valid) { root ->
            val first = root.accountObjects().first()
            root.withAccounts(List(BackupLimits.MAX_ACCOUNTS + 1) { first })
        },
        ValidationCode.BACKUP_TOO_MANY_ENTRIES,
    )

    @Test
    fun `预算条目超限被拒绝`() = assertRejected(
        "预算超限",
        BackupJson.editCompact(valid) { root ->
            val first = root.budgetObjects().first()
            root.withBudgets(List(BackupLimits.MAX_BUDGETS + 1) { first })
        },
        ValidationCode.BACKUP_TOO_MANY_ENTRIES,
    )

    @Test
    fun `账单条目超限被拒绝`() = assertRejected(
        "账单超限",
        BackupJson.editCompact(valid) { root ->
            val minimal = buildJsonObject {
                put("id", "tx")
                put("type", "EXPENSE")
                put("amountCent", 1)
                put("categoryId", BackupFx.CAT_FOOD)
                put("accountId", BackupFx.ACCOUNT_MAIN)
                put("occurredOn", "2026-10-07")
                put("createdAt", "2026-10-07T00:00:00Z")
                put("updatedAt", "2026-10-07T00:00:00Z")
            }
            root.withTransactions(List(BackupLimits.MAX_TRANSACTIONS + 1) { minimal })
        },
        ValidationCode.BACKUP_TOO_MANY_ENTRIES,
    )
}
