package com.blueledger.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.blueledger.app.core.model.*
import com.blueledger.app.data.local.LedgerDatabase
import com.blueledger.app.feature.backup.JsonBackupCodec
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReferencePersistenceTest : LedgerRoomTestBase() {
    @Test fun monthlyBudgetRepeatsAndRetainsIndependentHistoricalBudgets() = runDb {
        seedAcceptanceFixture()
        repository.setBudget(september, 12000).requireSuccess()
        repository.setMonthlyBudget(80000, october).requireSuccess()
        assertEquals(80000L, repository.observeBudget(october).first().budgetCent)
        assertEquals(80000L, repository.observeBudget(october.plusMonths(1)).first().budgetCent)
        assertEquals(12000L, repository.observeBudget(september).first().budgetCent)
        val snapshot = repository.exportConsistentSnapshot()
        repository.setMonthlyBudget(0, october).requireFailure()
        assertEquals(snapshot, repository.exportConsistentSnapshot())
        val codec = JsonBackupCodec(clock)
        val decoded = codec.decode(codec.encode(snapshot)) as BackupDecodeResult.Valid
        repository.setMonthlyBudget(null, october).requireSuccess()
        assertNull(repository.observeBudget(october.plusMonths(1)).first().budgetCent)
        repository.restoreValidatedSnapshot(decoded.validated).requireSuccess()
        assertEquals(80000L, repository.observeBudget(october.plusMonths(1)).first().budgetCent)
        assertEquals(12000L, repository.observeBudget(september).first().budgetCent)
    }
    @Test fun categoryMigrationIsAtomicAndKeepsIdentityAndAmounts() = runDb {
        seedAcceptanceFixture()
        val before = repository.observeTransactions(TransactionFilter()).first().items.map { it.transaction }
        repository.migrateCategory(FOOD, TRANSPORT).requireSuccess()
        val after = repository.observeTransactions(TransactionFilter()).first().items.map { it.transaction }
        assertEquals(before.map { it.id }, after.map { it.id })
        assertEquals(before.map { it.amountCent }, after.map { it.amountCent })
        assertEquals(before.map { it.occurredOn }, after.map { it.occurredOn })
        assertTrue(after.none { it.categoryId == FOOD })
        assertTrue(after.filter { before.first { old -> old.id == it.id }.categoryId == FOOD }.all { it.categoryId == TRANSPORT })
        val snapshot = repository.exportConsistentSnapshot()
        repository.migrateCategory(TRANSPORT, SALARY).requireFailure()
        assertEquals(snapshot.transactions, repository.exportConsistentSnapshot().transactions)
    }
    @Test fun archivedTargetRejectsWithoutChangingRows() = runDb {
        seedAcceptanceFixture(); repository.setCategoryArchived(TRANSPORT, true).requireSuccess()
        val before = repository.exportConsistentSnapshot()
        repository.migrateCategory(FOOD, TRANSPORT).requireFailure()
        assertEquals(before.transactions, repository.exportConsistentSnapshot().transactions)
    }
    @Test fun noteHistoryAndNewTypesSurviveBackupAndRestore() = runDb {
        repository.initializeIfNeeded()
        val id = repository.upsertAccount(AccountCommand(name = "贷款", kind = AccountKind.LIABILITY, openingBalanceCent = -5000, note = "每月还款")).requireId()
        clock.advanceMillis(86_400_000)
        repository.upsertAccount(AccountCommand(id, "贷款", AccountKind.LIABILITY, -4000, "每月还款")).requireSuccess()
        val codec = JsonBackupCodec(clock)
        val snapshot = repository.exportConsistentSnapshot()
        val decoded = codec.decode(codec.encode(snapshot)) as BackupDecodeResult.Valid
        repository.restoreValidatedSnapshot(decoded.validated).requireSuccess()
        val restored = repository.observeAccounts(false).first().first { it.account.id == id }
        assertEquals(-4000L, restored.balanceCent)
        assertEquals("每月还款", restored.account.note)
        assertEquals(2, AssetHistory.parse(restored.account.openingHistory).size)
        assertEquals(-5000L, AssetHistory.openingAt(restored.account, LocalDate.of(2026, 10, 7)))
        assertEquals(0L, repository.observeMonthSummary(october).first().expenseCent)
    }
    @Test fun olderVersionOneBackupStillLoads() = runDb {
        repository.initializeIfNeeded()
        val codec = JsonBackupCodec(clock)
        val old = JSONObject(codec.encode(repository.exportConsistentSnapshot())).apply {
            put("schemaVersion", 1)
            getJSONObject("settings").remove("monthlyBudgetCent")
            val accounts = getJSONArray("accounts")
            for (i in 0 until accounts.length()) { accounts.getJSONObject(i).remove("note"); accounts.getJSONObject(i).remove("openingHistory") }
        }
        assertTrue(codec.decode(old.toString()) is BackupDecodeResult.Valid)
    }
    @Test fun schemaOneUpgradePreservesUserRowsAndNames() = runDb {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "reference-v1-upgrade.db"
        context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/com.blueledger.app.data.local.LedgerDatabase/1.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { legacy ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                legacy.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) legacy.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            legacy.execSQL("INSERT INTO accounts VALUES ('acc_default','默认账户','默认账户','CASH',5000,0,1,1)")
            legacy.execSQL("INSERT INTO categories VALUES ('cat_expense_food','EXPENSE','家常饭','家常饭','restaurant',0,0,0,1,1)")
            legacy.execSQL("INSERT INTO transactions VALUES ('original','EXPENSE',1250,'cat_expense_food','acc_default',?, '升级保留','升级保留',1,1,NULL,NULL)", arrayOf<Any>(today.toEpochDay()))
            legacy.version = 1
        }
        val upgraded = LedgerDatabase.create(context, name)
        try {
            val sql = upgraded.openHelper.readableDatabase
            assertEquals(LedgerDatabase.VERSION, sql.version)
            sql.query("SELECT amountCent,note FROM transactions WHERE id='original'").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals(1250L, cursor.getLong(0)); assertEquals("升级保留", cursor.getString(1)) }
            sql.query("SELECT name FROM categories WHERE id='cat_expense_food'").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals("家常饭", cursor.getString(0)) }
            sql.query("SELECT openingBalanceCent,note,openingHistory FROM accounts WHERE id='acc_default'").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals(5000L, cursor.getLong(0)); assertEquals("", cursor.getString(1)); assertEquals("", cursor.getString(2)) }
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
}
