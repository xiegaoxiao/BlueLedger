package com.blueledger.app.feature.backup

import com.blueledger.app.core.model.validationError
import com.blueledger.app.core.contract.BackupDeletedTransactionDto
import com.blueledger.app.core.contract.BackupAccountDto
import com.blueledger.app.core.contract.BackupBudgetDto
import com.blueledger.app.core.contract.BackupCategoryDto
import com.blueledger.app.core.contract.BackupEnvelope
import com.blueledger.app.core.contract.BackupLimits
import com.blueledger.app.core.contract.BackupSettingsDto
import com.blueledger.app.core.contract.BackupTransactionDto
import com.blueledger.app.core.contract.Clock
import com.blueledger.app.core.contract.LedgerBackupCodec
import com.blueledger.app.core.model.AccountKind
import com.blueledger.app.core.model.BackupDecodeResult
import com.blueledger.app.core.model.BackupSummary
import com.blueledger.app.core.model.CategoryIcons
import com.blueledger.app.core.model.LedgerAccount
import com.blueledger.app.core.model.LedgerCategory
import com.blueledger.app.core.model.LedgerError
import com.blueledger.app.core.model.LedgerSettings
import com.blueledger.app.core.model.LedgerSnapshot
import com.blueledger.app.core.model.LedgerTransaction
import com.blueledger.app.core.model.Limits
import com.blueledger.app.core.model.MonthlyBudget
import com.blueledger.app.core.model.TransactionType
import com.blueledger.app.core.model.ValidationCode
import com.blueledger.app.core.model.ValidatedLedgerSnapshot
import com.blueledger.app.core.time.DateRanges
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 「蓝记」正式备份格式（`.blueledger.json`）的编解码器。
 *
 * 线格式由 `core/contract/BackupContract.kt` 冻结：本类**只做**序列化与校验，
 * 不改动任何 DTO 字段。
 *
 * 设计要点：
 * - [Json] 配置 `ignoreUnknownKeys = false`：未知字段直接拒绝。备份文件是**全量替换**
 *   的输入，静默忽略未知字段会让用户误以为某个更新版本写的数据已经被恢复。
 * - [decode] 先探测顶层标识/版本/币种与必需结构，再判条目数上限，最后逐条做业务校验；
 *   **只有全部通过**才调用 [ValidatedLedgerSnapshot.fromVerified]。
 * - 任何失败都返回 [BackupDecodeResult.Invalid]，**不触碰任何当前数据**：本类没有任何写入口，
 *   整库替换只可能发生在 UI 拿到 [BackupDecodeResult.Valid] 并由用户确认之后。
 * - 日期上限使用注入的 [Clock]（`clock.today()`），不使用 `LocalDate.now()`，方便冻结测试。
 * - 校验口径与 A1 数据层恢复前的二次校验保持一致：能通过本类的快照，不应在恢复事务里
 *   因为同一条规则再被拒绝。两处重叠是**有意**的纵深防御。
 */
class JsonBackupCodec(private val clock: Clock) : LedgerBackupCodec {

    private val json = Json {
        prettyPrint = true
        // 未知字段 = 不可信内容，直接拒绝，不做“尽力而为”的兼容。
        ignoreUnknownKeys = false
        isLenient = false
        // null 不得被强制成默认值（否则 "amountCent": null 会被当成 0 之类的默认值）。
        coerceInputValues = false
        // 默认值也写出来：备份文件应当自描述，缺失字段由下面的探测规则明确报错。
        encodeDefaults = true
    }

    // ───────────────────────── 导出 ─────────────────────────

    override fun encode(snapshot: LedgerSnapshot): String {
        val envelope = BackupEnvelope(
            product = BackupLimits.PRODUCT_ID,
            schemaVersion = BackupLimits.BACKUP_SCHEMA_VERSION,
            exportedAt = IsoTimestamp.format(snapshot.exportedAt),
            currency = snapshot.currency.ifBlank { LedgerSettings.CURRENCY_CNY },
            // 软删除账单不进入有效账单集合，版本 3 在独立 recycleBin 中保存。
            // A1 的一致快照已经过滤过，这里再挡一次，防止调用方直接传原始快照。
            transactions = snapshot.transactions
                .filter { it.deletedAt == null }
                .map {
                    BackupTransactionDto(
                        id = it.id,
                        type = it.type.name,
                        amountCent = it.amountCent,
                        categoryId = it.categoryId,
                        accountId = it.accountId,
                        occurredOn = it.occurredOn.toString(),
                        note = it.note,
                        createdAt = IsoTimestamp.format(it.createdAt),
                        updatedAt = IsoTimestamp.format(it.updatedAt),
                    )
                },
            // 全部分类与账户都导出（含没有任何有效账单引用的归档项）：
            // 用户的分类/账户配置本身就是需要保留的数据。
            categories = snapshot.categories.map {
                BackupCategoryDto(
                    id = it.id,
                    type = it.type.name,
                    name = it.name,
                    iconKey = it.iconKey,
                    sortOrder = it.sortOrder,
                    isArchived = it.isArchived,
                    isFallback = it.isFallback,
                )
            },
            accounts = snapshot.accounts.map {
                BackupAccountDto(
                    id = it.id,
                    name = it.name,
                    kind = it.kind.name,
                    openingBalanceCent = it.openingBalanceCent,
                    isArchived = it.isArchived,
                    note = it.note,
                    openingHistory = it.openingHistory,
                )
            },
            budgets = snapshot.budgets
                .sortedBy { it.yearMonth }
                .map { BackupBudgetDto(yearMonth = it.yearMonth.toString(), amountCent = it.amountCent) },
            // settings 里**不含** lastBackupAt / lastBackupFileName（DTO 也没有这两个字段）：
            // 恢复后保留设备当前的“最近成功备份时间”，不能把文件里的旧时间当成刚备份成功。
            advanced = snapshot.advanced,
            recycleBin = snapshot.recycleBin.map { t -> BackupDeletedTransactionDto(
                BackupTransactionDto(t.id, t.type.name, t.amountCent, t.categoryId, t.accountId, t.occurredOn.toString(), t.note, IsoTimestamp.format(t.createdAt), IsoTimestamp.format(t.updatedAt)),
                IsoTimestamp.format(requireNotNull(t.deletedAt)),
            ) },
            settings = BackupSettingsDto(
                defaultAccountId = snapshot.settings.defaultAccountId,
                lastUsedAccountId = snapshot.settings.lastUsedAccountId,
                hideAmounts = snapshot.settings.hideAmounts,
                currency = snapshot.settings.currency.ifBlank { LedgerSettings.CURRENCY_CNY },
                monthlyBudgetCent = snapshot.settings.monthlyBudgetCent,
            ),
        )
        return json.encodeToString(BackupEnvelope.serializer(), envelope)
    }

    // ───────────────────────── 导入（完整校验） ─────────────────────────

    override fun decode(text: String): BackupDecodeResult {
        // 读取层（BackupTextReader）按**字节**在读取过程中限流；这里按字符数再做一次保守兜底
        // （UTF-8 字节数 >= UTF-16 字符数），防止调用方绕过读取层直接 decode 超大字符串。
        if (text.length.toLong() > BackupLimits.MAX_BYTES) {
            return invalid(
                ValidationCode.BACKUP_TOO_LARGE,
                "备份文件超过 ${BackupLimits.MAX_BYTES / (1024 * 1024)} MiB 上限，已拒绝",
            )
        }

        val root = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            return invalidWithDetail(ValidationCode.BACKUP_NOT_JSON, "这不是有效的 JSON 文件", e)
        } catch (e: IllegalArgumentException) {
            return invalidWithDetail(ValidationCode.BACKUP_NOT_JSON, "这不是有效的 JSON 文件", e)
        }
        if (root !is JsonObject) {
            return invalid(ValidationCode.BACKUP_NOT_JSON, "备份文件的顶层必须是 JSON 对象")
        }

        // ── 顶层标识 / 版本 / 币种 / 生成时间 ──
        // 这些字段在 DTO 上带默认值，靠反序列化无法发现“字段缺失”，必须显式探测。
        val product = root.stringField("product")
        if (product != BackupLimits.PRODUCT_ID) {
            return invalid(
                ValidationCode.BACKUP_PRODUCT_MISMATCH,
                "这不是蓝记的备份文件（产品标识不匹配）",
                detail = "product=${product ?: "缺失"}",
            )
        }

        val versionElement = root["schemaVersion"]
            ?: return invalid(ValidationCode.BACKUP_UNSUPPORTED_VERSION, "备份文件缺少版本号，无法确认格式")
        val version = (versionElement as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: return invalid(ValidationCode.BACKUP_FIELD_TYPE_INVALID, "备份文件的版本号不是整数")
        if (version !in 1..BackupLimits.BACKUP_SCHEMA_VERSION) {
            return invalid(
                ValidationCode.BACKUP_UNSUPPORTED_VERSION,
                "备份文件版本不受支持（文件为 v$version，当前支持 v${BackupLimits.BACKUP_SCHEMA_VERSION}），未做猜测式迁移",
            )
        }

        val currencyElement = root["currency"]
            ?: return invalid(ValidationCode.BACKUP_CURRENCY_MISMATCH, "备份文件缺少币种")
        val currency = (currencyElement as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return invalid(ValidationCode.BACKUP_FIELD_TYPE_INVALID, "备份文件的币种字段类型不正确")
        if (currency != LedgerSettings.CURRENCY_CNY) {
            return invalid(ValidationCode.BACKUP_CURRENCY_MISMATCH, "备份币种为 $currency，本应用仅支持 CNY")
        }

        val exportedAtText = root.stringField("exportedAt")
            ?: return invalid(ValidationCode.BACKUP_DATE_INVALID, "备份文件缺少生成时间或格式不正确")
        val exportedAt = IsoTimestamp.parseOrNull(exportedAtText)
            ?: return invalid(
                ValidationCode.BACKUP_DATE_INVALID,
                "备份文件的生成时间不是合法的 ISO-8601 UTC 时间",
                detail = "exportedAt=$exportedAtText",
            )

        // ── 结构存在性 ──
        for (key in ENTRY_ARRAY_KEYS) {
            when (val element = root[key]) {
                null -> return invalid(ValidationCode.BACKUP_FIELD_TYPE_INVALID, "备份文件缺少 $key 数组")
                is JsonArray -> Unit
                else -> return invalid(ValidationCode.BACKUP_FIELD_TYPE_INVALID, "备份文件的 $key 不是数组")
            }
        }
        if (root["settings"] !is JsonObject) {
            return invalid(ValidationCode.BACKUP_FIELD_TYPE_INVALID, "备份文件缺少 settings 对象")
        }

        // ── 条目数上限：先挡大的，避免为超大文件做逐条解析 ──
        val transactionEntries = (root["transactions"] as JsonArray).size
        if (transactionEntries > BackupLimits.MAX_TRANSACTIONS) {
            return tooManyEntries("账单", transactionEntries, BackupLimits.MAX_TRANSACTIONS)
        }
        val categoryEntries = (root["categories"] as JsonArray).size
        if (categoryEntries > BackupLimits.MAX_CATEGORIES) {
            return tooManyEntries("分类", categoryEntries, BackupLimits.MAX_CATEGORIES)
        }
        val accountEntries = (root["accounts"] as JsonArray).size
        if (accountEntries > BackupLimits.MAX_ACCOUNTS) {
            return tooManyEntries("账户", accountEntries, BackupLimits.MAX_ACCOUNTS)
        }
        val budgetEntries = (root["budgets"] as JsonArray).size
        if (budgetEntries > BackupLimits.MAX_BUDGETS) {
            return tooManyEntries("预算", budgetEntries, BackupLimits.MAX_BUDGETS)
        }

        // ── 字段类型（严格）：kotlinx 会把「带引号的数字/布尔」按内容解析成数字/布尔，
        //    只靠反序列化无法拒绝 "amountCent": "2850" 这类类型错误，必须显式核对 ──
        try {
            verifyPrimitiveTypes(root)
            if (version >= 3 && (root["advanced"] !is JsonObject || root["recycleBin"] !is JsonArray)) fail(ValidationCode.BACKUP_FIELD_TYPE_INVALID, "v3 备份缺少高级设置或回收站")
            (root["recycleBin"] as? JsonArray)?.forEach { value ->
                val item = value as? JsonObject ?: fail(ValidationCode.BACKUP_FIELD_TYPE_INVALID, "回收站格式不合法")
                checkObjectTypes(item, mapOf("deletedAt" to FieldKind.STRING), "recycleBin")
                checkObjectTypes(item["transaction"] as? JsonObject ?: fail(ValidationCode.BACKUP_FIELD_TYPE_INVALID, "回收站缺少账单"), TRANSACTION_FIELD_KINDS, "recycleBin.transaction")
            }
            (root["advanced"] as? JsonObject)?.let { verifyAdvancedTypes(it) }
        } catch (rejection: Rejection) {
            return BackupDecodeResult.Invalid(rejection.error)
        }

        // ── 反序列化（未知字段 / 其余类型错误在这里被拒绝） ──
        val envelope = try {
            json.decodeFromJsonElement(BackupEnvelope.serializer(), root)
        } catch (e: SerializationException) {
            return invalidWithDetail(
                ValidationCode.BACKUP_FIELD_TYPE_INVALID,
                "备份文件字段类型不正确或存在未知字段，无法解析",
                e,
            )
        } catch (e: IllegalArgumentException) {
            return invalidWithDetail(
                ValidationCode.BACKUP_FIELD_TYPE_INVALID,
                "备份文件字段类型不正确或存在未知字段，无法解析",
                e,
            )
        }

        // ── 业务校验：任何一条失败都不产生 ValidatedLedgerSnapshot ──
        return try {
            var snapshot = verify(envelope, exportedAt, currency)
            if (envelope.transactions.size + envelope.recycleBin.size > BackupLimits.MAX_TRANSACTIONS) fail(ValidationCode.BACKUP_TOO_MANY_ENTRIES, "有效账单与回收站合计超过上限")
            val all = verify(envelope.copy(transactions = envelope.transactions + envelope.recycleBin.map { it.transaction }), exportedAt, currency)
            val deleted = all.transactions.drop(envelope.transactions.size).zip(envelope.recycleBin).map { (transaction, dto) ->
                val at = IsoTimestamp.parseOrNull(dto.deletedAt) ?: fail(ValidationCode.BACKUP_DATE_INVALID, "回收站删除时间不合法")
                // 与创建/更新时间一样，保留实际墙钟值，兼容设备时间回拨。
                transaction.copy(deletedAt = at)
            }
            envelope.advanced.validationError(all.categories, all.accounts, all.transactions.map { it.id }.toSet())?.let { fail(ValidationCode.BACKUP_FIELD_TYPE_INVALID, it) }
            snapshot = snapshot.copy(advanced = envelope.advanced, recycleBin = deleted)
            BackupDecodeResult.Valid(
                validated = ValidatedLedgerSnapshot.fromVerified(snapshot),
                summary = summarize(envelope, exportedAt, currency, snapshot),
            )
        } catch (rejection: Rejection) {
            BackupDecodeResult.Invalid(rejection.error)
        }
    }

    // ───────────────────────── 校验主体 ─────────────────────────

    /**
     * 逐字段核对 JSON 原始类型。
     *
     * 为什么需要：kotlinx.serialization 对**数值/布尔字段**不区分「带引号」与「不带引号」，
     * `"amountCent": "2850"` 会被成功解析成 2850。备份是整库替换的输入，这类类型错误必须
     * 明确拒绝（[ValidationCode.BACKUP_FIELD_TYPE_INVALID]），不能静默接受。
     *
     * `null` 不在这里判定：是否允许 null 由反序列化（`coerceInputValues = false`）决定。
     */
    private fun verifyPrimitiveTypes(root: JsonObject) {
        checkEntryTypes(root, "transactions", TRANSACTION_FIELD_KINDS)
        checkEntryTypes(root, "categories", CATEGORY_FIELD_KINDS)
        checkEntryTypes(root, "accounts", ACCOUNT_FIELD_KINDS)
        checkEntryTypes(root, "budgets", BUDGET_FIELD_KINDS)
        (root["settings"] as? JsonObject)?.let { checkObjectTypes(it, SETTINGS_FIELD_KINDS, "settings") }
    }

    private fun checkEntryTypes(root: JsonObject, key: String, kinds: Map<String, FieldKind>) {
        val array = root[key] as? JsonArray ?: return
        array.forEachIndexed { index, element ->
            val entry = element as? JsonObject
                ?: fail(ValidationCode.BACKUP_FIELD_TYPE_INVALID, "$key[$index] 不是 JSON 对象")
            checkObjectTypes(entry, kinds, "$key[$index]")
        }
    }

    private fun checkObjectTypes(entry: JsonObject, kinds: Map<String, FieldKind>, where: String) {
        for ((field, kind) in kinds) {
            val element = entry[field] ?: continue
            if (element is JsonNull) continue
            val primitive = element as? JsonPrimitive
                ?: fail(ValidationCode.BACKUP_FIELD_TYPE_INVALID, "$where.$field 字段类型不正确（需要${kind.label}）")
            val matchesKind = when (kind) {
                FieldKind.STRING -> primitive.isString
                FieldKind.NUMBER -> !primitive.isString && primitive.longOrNull != null
                FieldKind.BOOLEAN -> !primitive.isString &&
                    (primitive.content == "true" || primitive.content == "false")
            }
            if (!matchesKind) {
                fail(ValidationCode.BACKUP_FIELD_TYPE_INVALID, "$where.$field 字段类型不正确（需要${kind.label}）")
            }
        }
    }

    private fun verifyAdvancedTypes(value: JsonObject) {
        checkObjectTypes(value, mapOf("monthStartDay" to FieldKind.NUMBER), "advanced")
        for (name in listOf("tags", "categoryBudgets", "recurringRules")) {
            (value[name] as? JsonArray)?.forEach { item ->
                val entry = item as? JsonObject ?: fail(ValidationCode.BACKUP_FIELD_TYPE_INVALID, "高级设置条目不是对象")
                checkObjectTypes(entry, mapOf(
                    "id" to FieldKind.STRING, "name" to FieldKind.STRING, "type" to FieldKind.STRING,
                    "categoryId" to FieldKind.STRING, "accountId" to FieldKind.STRING, "note" to FieldKind.STRING,
                    "startDate" to FieldKind.STRING, "nextDate" to FieldKind.STRING, "frequency" to FieldKind.STRING,
                    "lastError" to FieldKind.STRING, "amountCent" to FieldKind.NUMBER, "interval" to FieldKind.NUMBER,
                    "enabled" to FieldKind.BOOLEAN,
                ), "advanced.$name")
            }
        }
        (value["transactionTags"] as? JsonObject)?.values?.forEach { element ->
            val ids = element as? JsonArray ?: fail(ValidationCode.BACKUP_FIELD_TYPE_INVALID, "账单标签不是数组")
            if (ids.any { it !is JsonPrimitive || !it.isString }) fail(ValidationCode.BACKUP_FIELD_TYPE_INVALID, "标签 ID 不是字符串")
        }
    }

    private enum class FieldKind(val label: String) {
        STRING("字符串"),
        NUMBER("整数"),
        BOOLEAN("布尔值"),
    }

    private fun verify(envelope: BackupEnvelope, exportedAt: Instant, currency: String): LedgerSnapshot {
        val today = clock.today()

        envelope.settings.monthlyBudgetCent?.let { amount ->
            if (amount <= 0 || amount > Limits.MAX_BUDGET_CENT) fail(ValidationCode.BACKUP_AMOUNT_OUT_OF_RANGE, "每月预算金额超出范围")
        }
        // 分类
        val categories = ArrayList<LedgerCategory>(envelope.categories.size)
        val categoryById = HashMap<String, LedgerCategory>(envelope.categories.size)
        val categoryNameKeys = HashSet<String>(envelope.categories.size)
        val fallbackSeen = HashMap<TransactionType, Boolean>()
        for (dto in envelope.categories) {
            if (dto.id.isBlank()) fail(ValidationCode.BACKUP_FIELD_TYPE_INVALID, "分类 ID 不能为空")
            // 先判 ID，再判名称：与 A1 的二次校验同序，重复元素不会被误报成“名称重复”。
            if (categoryById.containsKey(dto.id)) {
                fail(ValidationCode.BACKUP_DUPLICATE_ID, "备份中存在重复的分类 ID：${dto.id}")
            }
            val type = transactionTypeOf(dto.type)
                ?: fail(ValidationCode.BACKUP_FIELD_TYPE_INVALID, "分类 ${dto.id} 的收支类型不合法：${dto.type}")
            val name = dto.name.trim()
            if (name.isEmpty() || name.length > Limits.MAX_CATEGORY_NAME_LENGTH) {
                fail(
                    ValidationCode.BACKUP_NAME_CONSTRAINT,
                    "分类名称必须为 1—${Limits.MAX_CATEGORY_NAME_LENGTH} 个字符",
                )
            }
            if (!CategoryIcons.isValidKey(dto.iconKey)) {
                fail(ValidationCode.BACKUP_ICON_INVALID, "分类「$name」的图标不合法：${dto.iconKey}")
            }
            // 同类型（含归档）名称不得重复，与 PRD S07 与 A1 数据层口径一致。
            if (!categoryNameKeys.add(type.name + "#" + name.lowercase())) {
                fail(ValidationCode.BACKUP_NAME_CONSTRAINT, "同一收支类型下分类名称重复：$name")
            }
            val category = LedgerCategory(
                id = dto.id,
                type = type,
                name = name,
                iconKey = dto.iconKey,
                sortOrder = dto.sortOrder,
                isArchived = dto.isArchived,
                isFallback = dto.isFallback,
            )
            categoryById[dto.id] = category
            categories += category
            if (dto.isFallback) fallbackSeen[type] = true
        }
        for (type in TransactionType.entries) {
            if (fallbackSeen[type] != true) {
                fail(
                    ValidationCode.BACKUP_NO_FALLBACK_CATEGORY,
                    "备份中缺少${typeLabel(type)}兜底分类，“其他”不可缺失",
                )
            }
        }

        // 账户
        val accounts = ArrayList<LedgerAccount>(envelope.accounts.size)
        val accountById = HashMap<String, LedgerAccount>(envelope.accounts.size)
        val activeAccountNames = HashSet<String>(envelope.accounts.size)
        var activeAccountCount = 0
        for (dto in envelope.accounts) {
            if (dto.id.isBlank()) fail(ValidationCode.BACKUP_FIELD_TYPE_INVALID, "账户 ID 不能为空")
            if (accountById.containsKey(dto.id)) {
                fail(ValidationCode.BACKUP_DUPLICATE_ID, "备份中存在重复的账户 ID：${dto.id}")
            }
            val kind = AccountKind.entries.firstOrNull { it.name == dto.kind }
                ?: fail(ValidationCode.BACKUP_FIELD_TYPE_INVALID, "账户类型不合法：${dto.kind}")
            val name = dto.name.trim()
            if (name.isEmpty() || name.length > Limits.MAX_ACCOUNT_NAME_LENGTH) {
                fail(
                    ValidationCode.BACKUP_NAME_CONSTRAINT,
                    "账户名称必须为 1—${Limits.MAX_ACCOUNT_NAME_LENGTH} 个字符",
                )
            }
            if (dto.openingBalanceCent > Limits.MAX_OPENING_BALANCE_CENT ||
                dto.openingBalanceCent < -Limits.MAX_OPENING_BALANCE_CENT
            ) {
                fail(
                    ValidationCode.BACKUP_AMOUNT_OUT_OF_RANGE,
                    "账户「$name」的期初余额超出 ±${Limits.MAX_OPENING_BALANCE_CENT} 分",
                )
            }
            if (!dto.isArchived) {
                activeAccountCount += 1
                if (!activeAccountNames.add(name.lowercase())) {
                    fail(ValidationCode.BACKUP_NAME_CONSTRAINT, "可用账户名称重复：$name")
                }
            }
            val account = LedgerAccount(
                id = dto.id,
                name = name,
                kind = kind,
                openingBalanceCent = dto.openingBalanceCent,
                isArchived = dto.isArchived,
                note = dto.note,
                openingHistory = dto.openingHistory,
            )
            accountById[dto.id] = account
            if (dto.note.length > Limits.MAX_NOTE_LENGTH || runCatching {
                    val history = com.blueledger.app.core.model.AssetHistory.parse(dto.openingHistory)
                    history.all { !it.first.isAfter(clock.today()) } && (history.isEmpty() || history.last().second == dto.openingBalanceCent)
                }.getOrDefault(false).not()) {
                fail(ValidationCode.BACKUP_FIELD_TYPE_INVALID, "资产备注或余额历史不合法")
            }
            accounts += account
        }
        // 与 A1 二次校验同序：先判定“有没有可用账户”，再判定默认账户是否有效。
        if (activeAccountCount == 0) {
            fail(ValidationCode.BACKUP_NO_ACTIVE_ACCOUNT, "备份中没有未归档账户，恢复后无法记账")
        }

        // 账单
        val transactions = ArrayList<LedgerTransaction>(envelope.transactions.size)
        val transactionIds = HashSet<String>(envelope.transactions.size)
        for (dto in envelope.transactions) {
            if (dto.id.isBlank() || !transactionIds.add(dto.id)) {
                fail(ValidationCode.BACKUP_DUPLICATE_ID, "账单 ID 为空或重复：${dto.id}")
            }
            val type = transactionTypeOf(dto.type)
                ?: fail(ValidationCode.BACKUP_FIELD_TYPE_INVALID, "账单 ${dto.id} 的收支类型不合法：${dto.type}")
            if (dto.amountCent < Limits.MIN_TRANSACTION_CENT || dto.amountCent > Limits.MAX_TRANSACTION_CENT) {
                fail(
                    ValidationCode.BACKUP_AMOUNT_OUT_OF_RANGE,
                    "账单 ${dto.id} 的金额必须落在 ${Limits.MIN_TRANSACTION_CENT}..${Limits.MAX_TRANSACTION_CENT} 分",
                )
            }
            if (dto.note.length > Limits.MAX_NOTE_LENGTH) {
                fail(
                    ValidationCode.BACKUP_FIELD_TYPE_INVALID,
                    "账单 ${dto.id} 的备注超过 ${Limits.MAX_NOTE_LENGTH} 个字符",
                )
            }
            val occurredOn = parseLocalDate(dto.occurredOn)
                ?: fail(ValidationCode.BACKUP_DATE_INVALID, "账单 ${dto.id} 的发生日期不合法：${dto.occurredOn}")
            if (!DateRanges.isSupportedDate(occurredOn)) {
                fail(ValidationCode.BACKUP_DATE_INVALID, "账单 ${dto.id} 的发生日期超出可支持范围")
            }
            if (occurredOn.isAfter(today)) {
                fail(
                    ValidationCode.BACKUP_DATE_FUTURE,
                    "账单 ${dto.id} 的发生日期晚于设备今日（$today），已拒绝",
                )
            }
            val createdAt = IsoTimestamp.parseOrNull(dto.createdAt)
                ?: fail(ValidationCode.BACKUP_FIELD_TYPE_INVALID, "账单 ${dto.id} 的创建时间不是合法的 ISO-8601 时间")
            val updatedAt = IsoTimestamp.parseOrNull(dto.updatedAt)
                ?: fail(ValidationCode.BACKUP_FIELD_TYPE_INVALID, "账单 ${dto.id} 的更新时间不是合法的 ISO-8601 时间")

            val category = categoryById[dto.categoryId]
                ?: fail(
                    ValidationCode.BACKUP_MISSING_REFERENCE,
                    "账单 ${dto.id} 引用了不存在的分类：${dto.categoryId}",
                )
            if (category.type != type) {
                fail(
                    ValidationCode.BACKUP_CATEGORY_TYPE_MISMATCH,
                    "账单 ${dto.id} 的类型与分类「${category.name}」的类型不一致",
                )
            }
            if (!accountById.containsKey(dto.accountId)) {
                fail(
                    ValidationCode.BACKUP_MISSING_REFERENCE,
                    "账单 ${dto.id} 引用了不存在的账户：${dto.accountId}",
                )
            }

            transactions += LedgerTransaction(
                id = dto.id,
                type = type,
                amountCent = dto.amountCent,
                categoryId = dto.categoryId,
                accountId = dto.accountId,
                occurredOn = occurredOn,
                note = dto.note,
                createdAt = createdAt,
                updatedAt = updatedAt,
                deletedAt = null,
            )
        }

        // 预算
        val budgets = ArrayList<MonthlyBudget>(envelope.budgets.size)
        val budgetMonths = HashSet<YearMonth>(envelope.budgets.size)
        for (dto in envelope.budgets) {
            val month = parseYearMonth(dto.yearMonth)
                ?: fail(ValidationCode.BACKUP_DATE_INVALID, "预算月份不合法（应为 yyyy-MM）：${dto.yearMonth}")
            if (!budgetMonths.add(month)) {
                fail(ValidationCode.BACKUP_BUDGET_DUPLICATE_MONTH, "同一月份出现多条预算：$month")
            }
            if (dto.amountCent <= 0L || dto.amountCent > Limits.MAX_BUDGET_CENT) {
                fail(ValidationCode.BACKUP_AMOUNT_OUT_OF_RANGE, "预算 $month 的金额必须大于 0 且不超过上限")
            }
            budgets += MonthlyBudget(yearMonth = month, amountCent = dto.amountCent)
        }

        // 设置
        if (envelope.settings.currency != LedgerSettings.CURRENCY_CNY) {
            fail(ValidationCode.BACKUP_CURRENCY_MISMATCH, "备份设置中的币种不是 CNY")
        }
        val defaultAccount = accountById[envelope.settings.defaultAccountId]
            ?: fail(
                ValidationCode.BACKUP_DEFAULT_ACCOUNT_INVALID,
                "默认账户不存在：${envelope.settings.defaultAccountId}",
            )
        if (defaultAccount.isArchived) {
            fail(ValidationCode.BACKUP_DEFAULT_ACCOUNT_INVALID, "默认账户已被归档：${defaultAccount.name}")
        }
        // lastUsedAccountId 有意放宽：账户归档后“最近使用账户”可能指向归档项，
        // A1 的恢复事务会把它归一化为 null 而不是失败，这里跟随数据层口径。

        return LedgerSnapshot(
            exportedAt = exportedAt,
            currency = currency,
            transactions = transactions,
            categories = categories,
            accounts = accounts,
            budgets = budgets.sortedBy { it.yearMonth },
            settings = LedgerSettings(
                defaultAccountId = envelope.settings.defaultAccountId,
                lastUsedAccountId = envelope.settings.lastUsedAccountId,
                hideAmounts = envelope.settings.hideAmounts,
                monthlyBudgetCent = envelope.settings.monthlyBudgetCent,
                currency = LedgerSettings.CURRENCY_CNY,
                // 备份文件不含最近成功备份时间：恢复后保留设备当前值。
                lastBackupAt = null,
                lastBackupFileName = null,
            ),
        )
    }

    private fun summarize(
        envelope: BackupEnvelope,
        exportedAt: Instant,
        currency: String,
        snapshot: LedgerSnapshot,
    ): BackupSummary = BackupSummary(
        product = envelope.product,
        schemaVersion = envelope.schemaVersion,
        exportedAt = exportedAt,
        currency = currency,
        transactionCount = snapshot.transactions.size,
        categoryCount = snapshot.categories.size,
        accountCount = snapshot.accounts.size,
        budgetCount = snapshot.budgets.size,
        earliestOccurredOn = snapshot.transactions.minOfOrNull { it.occurredOn },
        latestOccurredOn = snapshot.transactions.maxOfOrNull { it.occurredOn },
        budgetMonths = snapshot.budgets.map { it.yearMonth }.sorted(),
        fileName = null,
    )

    // ───────────────────────── 小工具 ─────────────────────────

    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun transactionTypeOf(raw: String): TransactionType? =
        TransactionType.entries.firstOrNull { it.name == raw }

    /** ISO_LOCAL_DATE 严格解析：`2025-02-29`、`2026-2-9`、带时间的串都会失败。 */
    private fun parseLocalDate(raw: String): LocalDate? = try {
        LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE)
    } catch (e: DateTimeException) {
        null
    }

    /** `yyyy-MM` 严格解析：位数不足、月份越界都会失败。 */
    private fun parseYearMonth(raw: String): YearMonth? = try {
        YearMonth.parse(raw)
    } catch (e: DateTimeException) {
        null
    }

    private fun tooManyEntries(label: String, actual: Int, limit: Int): BackupDecodeResult = invalid(
        ValidationCode.BACKUP_TOO_MANY_ENTRIES,
        "备份中${label}条目数 $actual 超过上限 $limit，已拒绝（不做截断）",
    )

    private fun invalid(
        code: ValidationCode,
        message: String,
        detail: String? = null,
    ): BackupDecodeResult = BackupDecodeResult.Invalid(
        LedgerError.Backup(code = code, message = message, detail = detail),
    )

    private fun invalidWithDetail(
        code: ValidationCode,
        message: String,
        cause: Throwable,
    ): BackupDecodeResult = invalid(code, message, cause.readableReason())

    /** 校验失败信号，只承载 [LedgerError.Backup]；无堆栈（预期路径，且避免逐条校验时反复构造）。 */
    private class Rejection(val error: LedgerError.Backup) : RuntimeException(error.message) {
        override fun fillInStackTrace(): Throwable = this
    }

    private fun fail(code: ValidationCode, message: String, detail: String? = null): Nothing =
        throw Rejection(LedgerError.Backup(code = code, message = message, detail = detail))

    private companion object {
        val ENTRY_ARRAY_KEYS = listOf("transactions", "categories", "accounts", "budgets")

        val TRANSACTION_FIELD_KINDS = mapOf(
            "id" to FieldKind.STRING,
            "type" to FieldKind.STRING,
            "amountCent" to FieldKind.NUMBER,
            "categoryId" to FieldKind.STRING,
            "accountId" to FieldKind.STRING,
            "occurredOn" to FieldKind.STRING,
            "note" to FieldKind.STRING,
            "createdAt" to FieldKind.STRING,
            "updatedAt" to FieldKind.STRING,
        )

        val CATEGORY_FIELD_KINDS = mapOf(
            "id" to FieldKind.STRING,
            "type" to FieldKind.STRING,
            "name" to FieldKind.STRING,
            "iconKey" to FieldKind.STRING,
            "sortOrder" to FieldKind.NUMBER,
            "isArchived" to FieldKind.BOOLEAN,
            "isFallback" to FieldKind.BOOLEAN,
        )

        val ACCOUNT_FIELD_KINDS = mapOf(
            "id" to FieldKind.STRING,
            "name" to FieldKind.STRING,
            "kind" to FieldKind.STRING,
            "openingBalanceCent" to FieldKind.NUMBER,
            "isArchived" to FieldKind.BOOLEAN,
            "note" to FieldKind.STRING,
            "openingHistory" to FieldKind.STRING,
        )

        val BUDGET_FIELD_KINDS = mapOf(
            "yearMonth" to FieldKind.STRING,
            "amountCent" to FieldKind.NUMBER,
        )

        val SETTINGS_FIELD_KINDS = mapOf(
            "defaultAccountId" to FieldKind.STRING,
            "lastUsedAccountId" to FieldKind.STRING,
            "hideAmounts" to FieldKind.BOOLEAN,
            "currency" to FieldKind.STRING,
            "monthlyBudgetCent" to FieldKind.NUMBER,
        )

        fun typeLabel(type: TransactionType): String =
            if (type == TransactionType.EXPENSE) "支出" else "收入"
    }
}

/**
 * 时间戳格式化/解析的唯一点。
 *
 * - 写出：ISO-8601 UTC，毫秒精度（`2026-10-07T00:00:00Z` / `…T00:00:00.123Z`）。
 * - 读入：必须是带 Z 或偏移的 ISO-8601；缺偏移的本地时间会被拒绝，避免含义不明的数据。
 */
internal object IsoTimestamp {

    private val WRITER: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

    fun format(instant: Instant): String = WRITER.format(instant.truncatedTo(ChronoUnit.MILLIS))

    fun parseOrNull(raw: String): Instant? = try {
        OffsetDateTime.parse(raw).toInstant()
    } catch (e: DateTimeException) {
        try {
            Instant.parse(raw)
        } catch (e2: DateTimeException) {
            null
        }
    }
}

/** 截断过的异常原因，只用于错误提示/detail，不包含用户账单内容。 */
internal fun Throwable.readableReason(): String? =
    message?.lineSequence()?.firstOrNull()?.trim()?.take(160)?.takeIf { it.isNotEmpty() }
