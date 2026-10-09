package com.blueledger.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.blueledger.app.core.model.*
import com.blueledger.app.core.contract.BackupLimits
import com.blueledger.app.data.local.LedgerDatabase
import com.blueledger.app.data.repository.LedgerRepositoryImpl
import com.blueledger.app.feature.backup.JsonBackupCodec
import com.blueledger.app.feature.backup.LedgerCsvExporter
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdvancedLedgerFeaturesTest : LedgerRoomTestBase() {
    private fun rule(date: String = "2026-10-05", frequency: RecurrenceFrequency = RecurrenceFrequency.DAILY) = RecurringLedgerRule("rule-1", "固定餐费", "EXPENSE", 1250, "cat_expense_food", "acc_default", "自动账单", date, date, frequency)
    private suspend fun create(date: LocalDate = today, request: String = "test"): String {
        repository.initializeIfNeeded()
        return (repository.createTransaction(TransactionDraft(TransactionType.EXPENSE, 1250, "cat_expense_food", "acc_default", date, "午餐"), request) as SaveResult.Success).transactionId
    }

    @Test fun dueRulesBackfillExactlyOnceAcrossRepositoryRestartAndDeletedOccurrence() = runDb {
        repository.initializeIfNeeded()
        repository.updateAdvancedSettings { it.copy(recurringRules = listOf(rule())) }.requireSuccess()
        repository.processRecurringTransactions().requireSuccess()
        val rows = repository.observeTransactions(TransactionFilter()).first().items
        assertEquals(3, rows.size)
        assertEquals(setOf("2026-10-05", "2026-10-06", "2026-10-07"), rows.map { it.occurredOn.toString() }.toSet())
        assertEquals("2026-10-08", repository.observeAdvancedSettings().first().recurringRules.single().nextDate)
        repository.softDeleteTransaction(rows.first().id)
        LedgerRepositoryImpl(db, clock).processRecurringTransactions().requireSuccess()
        assertEquals(2, repository.observeTransactions(TransactionFilter()).first().totalCount)
        assertEquals(1, repository.observeRecycleBin().first().size)
    }

    @Test fun pausedAndFutureRulesDoNotCreateTransactions() = runDb {
        repository.initializeIfNeeded()
        repository.updateAdvancedSettings { it.copy(recurringRules = listOf(rule().copy(enabled = false), rule("2026-10-08").copy(id = "future"))) }.requireSuccess()
        repository.processRecurringTransactions().requireSuccess()
        assertEquals(0, repository.observeTransactions(TransactionFilter()).first().totalCount)
    }

    @Test fun archivedReferencePausesRuleInsteadOfWritingInvalidTransaction() = runDb {
        repository.initializeIfNeeded()
        repository.updateAdvancedSettings { it.copy(recurringRules = listOf(rule())) }.requireSuccess()
        repository.setCategoryArchived("cat_expense_food", true).requireSuccess()
        repository.processRecurringTransactions().requireSuccess()
        val value = repository.observeAdvancedSettings().first().recurringRules.single()
        assertFalse(value.enabled)
        assertNotNull(value.lastError)
        assertEquals(0, repository.observeTransactions(TransactionFilter()).first().totalCount)
    }

    @Test fun monthlyAndYearlyRecurrenceKeepOriginalDayAnchor() {
        val monthly = rule("2024-01-31", RecurrenceFrequency.MONTHLY)
        val february = monthly.following(LocalDate.of(2024, 1, 31))
        assertEquals(LocalDate.of(2024, 2, 29), february)
        assertEquals(LocalDate.of(2024, 3, 31), monthly.following(february))
        val yearly = rule("2024-02-29", RecurrenceFrequency.YEARLY)
        var date = LocalDate.of(2024, 2, 29)
        repeat(4) { date = yearly.following(date) }
        assertEquals(LocalDate.of(2028, 2, 29), date)
    }

    @Test fun customMonthAffectsRowsSummaryBudgetYearAndCsvWithoutChangingDates() = runDb {
        create(LocalDate.of(2026, 9, 27), "sept")
        create(LocalDate.of(2026, 10, 2), "oct")
        repository.updateAdvancedSettings { it.copy(monthStartDay = 25, categoryBudgets = listOf(CategoryBudgetRule("cat_expense_food", 5000))) }.requireSuccess()
        assertEquals(2, repository.observeTransactions(TransactionFilter(yearMonth = september)).first().totalCount)
        assertEquals(2500L, repository.observeMonthSummary(september).first().expenseCent)
        assertEquals(2500L, repository.observeBudget(september).first().usedCent)
        assertEquals(0L, repository.observeMonthSummary(october).first().expenseCent)
        val year = repository.observeYearAnalysis(2026).first()
        assertEquals(2500L, year.months.first { it.month == 9 }.expenseCent)
        assertFalse(year.months.first { it.month == 10 }.reached)
        val snapshot = repository.exportConsistentSnapshot()
        assertEquals(2, LedgerCsvExporter.build(snapshot, CsvScope.CURRENT_MONTH, september).rowCount)
        assertEquals(listOf("2026-10-02", "2026-09-27"), snapshot.transactions.map { it.occurredOn.toString() })
    }

    @Test fun shortMonthsHaveContinuousNonOverlappingAccountingPeriods() {
        val jan = LedgerPeriods.range(YearMonth.of(2024, 1), 31)
        val feb = LedgerPeriods.range(YearMonth.of(2024, 2), 31)
        assertEquals(LocalDate.of(2024, 1, 31), jan.start)
        assertEquals(LocalDate.of(2024, 2, 28), jan.endInclusive)
        assertEquals(jan.endInclusive.plusDays(1), feb.start)
        assertEquals(LocalDate.of(2024, 3, 30), feb.endInclusive)
        assertEquals(YearMonth.of(2024, 2), LedgerPeriods.monthOf(LocalDate.of(2024, 3, 30), 31))
    }

    @Test fun invalidAdvancedSettingsAreRejectedAtomically() = runDb {
        create()
        val before = repository.observeAdvancedSettings().first()
        repository.updateAdvancedSettings { it.copy(monthStartDay = 0) }.requireFailure()
        repository.updateAdvancedSettings { it.copy(categoryBudgets = listOf(CategoryBudgetRule("cat_income_salary", 5000))) }.requireFailure()
        repository.updateAdvancedSettings { it.copy(tags = listOf(LedgerTag("a", "重复"), LedgerTag("b", "重复"))) }.requireFailure()
        repository.updateAdvancedSettings { it.copy(transactionTags = mapOf("missing" to listOf("unknown"))) }.requireFailure()
        assertEquals(before, repository.observeAdvancedSettings().first())
    }

    @Test fun tagsRecycleBinBudgetsAndRulesSurviveFullBackupRestore() = runDb {
        val id = create()
        repository.updateAdvancedSettings { it.copy(tags = listOf(LedgerTag("travel", "旅行")), transactionTags = mapOf(id to listOf("travel")), categoryBudgets = listOf(CategoryBudgetRule("cat_expense_food", 8000)), recurringRules = listOf(rule("2026-10-08")), monthStartDay = 15) }.requireSuccess()
        repository.softDeleteTransaction(id)
        val snapshot = repository.exportConsistentSnapshot()
        assertEquals(0, snapshot.transactions.size)
        assertEquals(1, snapshot.recycleBin.size)
        val codec = JsonBackupCodec(clock)
        val decoded = codec.decode(codec.encode(snapshot)) as BackupDecodeResult.Valid
        assertEquals(3, decoded.summary.schemaVersion)
        repository.permanentlyDeleteTransaction(id).requireSuccess()
        repository.restoreValidatedSnapshot(decoded.validated).requireSuccess()
        assertEquals(snapshot.advanced, repository.observeAdvancedSettings().first())
        assertEquals(id, repository.observeRecycleBin().first().single().id)
        repository.restoreFromRecycleBin(id).requireSuccess()
        val restored = repository.observeTransaction(id).first()!!
        assertEquals(snapshot.recycleBin.single().copy(deletedAt = null), restored)
        assertEquals(listOf("travel"), repository.observeAdvancedSettings().first().transactionTags[id])
    }

    @Test fun permanentDeletionOnlyDeletesTrashAndUnlinksItsTags() = runDb {
        val id = create()
        repository.updateAdvancedSettings { it.copy(tags = listOf(LedgerTag("tag", "工作")), transactionTags = mapOf(id to listOf("tag"))) }.requireSuccess()
        repository.permanentlyDeleteTransaction(id).requireFailure()
        assertNotNull(repository.observeTransaction(id).first())
        repository.softDeleteTransaction(id)
        repository.permanentlyDeleteTransaction(id).requireSuccess()
        assertTrue(repository.observeRecycleBin().first().isEmpty())
        assertFalse(repository.observeAdvancedSettings().first().transactionTags.containsKey(id))
    }

    @Test fun recycleBinBackupRestoresOriginalWallTimeAfterDeviceClockMovesBackwards() = runDb {
        val id = create()
        repository.softDeleteTransaction(id)
        val before = repository.exportConsistentSnapshot()
        val deleted = before.recycleBin.single().copy(deletedAt = before.recycleBin.single().createdAt.minusSeconds(60))
        val codec = JsonBackupCodec(clock)
        val decoded = codec.decode(codec.encode(before.copy(recycleBin = listOf(deleted)))) as BackupDecodeResult.Valid
        repository.restoreValidatedSnapshot(decoded.validated).requireSuccess()
        assertEquals(deleted, repository.observeRecycleBin().first().single())
        assertEquals(0, repository.observeTransactions(TransactionFilter()).first().totalCount)
    }

    @Test fun oldSchemaTwoBackupsRestoreWithEmptyNewSettings() = runDb {
        create()
        val codec = JsonBackupCodec(clock)
        val root = JSONObject(codec.encode(repository.exportConsistentSnapshot()))
        root.put("schemaVersion", 2); root.remove("advanced"); root.remove("recycleBin")
        val decoded = codec.decode(root.toString()) as BackupDecodeResult.Valid
        assertEquals(AdvancedLedgerSettings(), decoded.validated.snapshot.advanced)
        assertTrue(decoded.validated.snapshot.recycleBin.isEmpty())
    }

    @Test fun malformedNewBackupTypesAndReferencesAreRejected() = runDb {
        create()
        val codec = JsonBackupCodec(clock)
        val validText = codec.encode(repository.exportConsistentSnapshot())
        fun json() = JSONObject(validText)
        val wrongType = json().apply { getJSONObject("advanced").put("monthStartDay", "15") }
        assertTrue(codec.decode(wrongType.toString()) is BackupDecodeResult.Invalid)
        val missing = json().apply { remove("advanced") }
        assertTrue(codec.decode(missing.toString()) is BackupDecodeResult.Invalid)
        val broken = json().apply { getJSONObject("advanced").put("categoryBudgets", JSONArray().put(JSONObject().put("categoryId", "missing").put("amountCent", 200))) }
        assertTrue(codec.decode(broken.toString()) is BackupDecodeResult.Invalid)
    }

    @Test fun repositoryRestoreRechecksNewReferencesAndPreservesExistingBook() = runDb {
        create()
        val before = repository.exportConsistentSnapshot()
        val forged = before.copy(advanced = AdvancedLedgerSettings(tags = listOf(LedgerTag("tag", "旅行")), transactionTags = mapOf("missing" to listOf("tag"))))
        assertTrue(repository.restoreValidatedSnapshot(ValidatedLedgerSnapshot.fromVerified(forged)) is RestoreResult.Failure)
        assertEquals(before, repository.exportConsistentSnapshot())
    }

    @Test fun schemaTwoUpgradePreservesDeletedRecordsAndAllOldTables() = runDb {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "advanced-v2-upgrade.db"
        context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/com.blueledger.app.data.local.LedgerDatabase/2.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: JSONArray()
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            old.execSQL("INSERT INTO transactions (id,type,amountCent,categoryId,accountId,occurredOnEpochDay,note,noteKey,createdAtEpochMillis,updatedAtEpochMillis,deletedAtEpochMillis,requestId) VALUES ('deleted-old','EXPENSE',1250,'cat_expense_food','acc_default',?,'保留','保留',1,2,2,'original-request')", arrayOf<Any>(today.toEpochDay()))
            old.version = 2
        }
        val upgraded = LedgerDatabase.create(context, name)
        try {
            val sql = upgraded.openHelper.readableDatabase
            assertEquals(3, sql.version)
            sql.query("SELECT amountCent,deletedAtEpochMillis,requestId FROM transactions WHERE id='deleted-old'").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals(1250L, cursor.getLong(0)); assertEquals(2L, cursor.getLong(1)); assertEquals("original-request", cursor.getString(2)) }
            assertEquals(AdvancedLedgerSettings(), LedgerRepositoryImpl(upgraded, clock).observeAdvancedSettings().first())
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
}
