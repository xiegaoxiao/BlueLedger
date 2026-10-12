package com.blueledger.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.blueledger.app.core.model.*
import com.blueledger.app.data.local.LedgerDatabase
import com.blueledger.app.data.repository.LedgerRepositoryImpl
import com.blueledger.app.feature.backup.JsonBackupCodec
import com.blueledger.app.feature.reference.assetPlatformGroups
import com.blueledger.app.feature.reference.assetTotals
import com.blueledger.app.feature.reference.visibleAssetAccounts
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssetAccountsTest : LedgerRoomTestBase() {
    @Test fun pocketsWalletsAndCardsGroupWithoutCountingHeadingsTwiceAndBackupRestoresLocations() = runDb {
        repository.initializeIfNeeded().requireSuccess()
        val balances = listOf(200000L, 600000L, 300000L, 150000L, 50000L, 80000L, 1000000L, 2000000L)
        val locations = listOf(AccountLocation.ALIPAY_BALANCE) + List(4) { AccountLocation.ALIPAY_POCKET } + listOf(AccountLocation.WECHAT_BALANCE, AccountLocation.BANK_CARD, AccountLocation.BANK_CARD)
        balances.forEachIndexed { i, balance -> repository.upsertAccount(AccountCommand(name = "虚构账户$i", kind = if (i >= 6) AccountKind.BANK_CARD else AccountKind.E_WALLET, openingBalanceCent = balance, location = locations[i])).requireSuccess() }
        val rows = visibleAssetAccounts(repository.observeAccounts(false).first())
        val groups = assetPlatformGroups(rows)
        assertEquals(8, rows.size)
        assertEquals(3, groups.size)
        assertEquals(1300000L, groups.first { it.first == AssetPlatform.ALIPAY }.second.sumOf { it.balanceCent })
        assertEquals(4380000L, assetTotals(rows.map { it.balanceCent }).first)
        assertEquals(rows.sumOf { it.balanceCent }, groups.sumOf { it.second.sumOf { row -> row.balanceCent } })
        val original = repository.exportConsistentSnapshot()
        val codec = JsonBackupCodec(clock)
        val decoded = codec.decode(codec.encode(original)) as BackupDecodeResult.Valid
        assertEquals(original.accounts, decoded.validated.snapshot.accounts)
        repository.restoreValidatedSnapshot(decoded.validated).requireSuccess()
        assertEquals(original.accounts, LedgerRepositoryImpl(db, clock).exportConsistentSnapshot().accounts)
        val legacy = JSONObject(codec.encode(original)).put("schemaVersion", 3)
        val accounts = legacy.getJSONArray("accounts")
        for (i in 0 until accounts.length()) accounts.getJSONObject(i).remove("location")
        assertTrue(codec.decode(legacy.toString()) is BackupDecodeResult.Valid)
        accounts.getJSONObject(1).put("location", "NOT_A_LOCATION")
        assertTrue(codec.decode(legacy.toString()) is BackupDecodeResult.Invalid)
    }
    @Test fun balanceReconciliationUsesLatestTransactionsAndNeverChangesIncomeOrExpenses() = runDb {
        repository.initializeIfNeeded().requireSuccess()
        val id = repository.upsertAccount(AccountCommand(name = "虚构微信", kind = AccountKind.E_WALLET, openingBalanceCent = 10000, location = AccountLocation.WECHAT_BALANCE)).requireId()
        val stale = repository.observeAccounts(false).first().first { it.account.id == id }
        repository.createTransaction(TransactionDraft(TransactionType.EXPENSE, 1500, "cat_expense_food", id, today, "虚构午餐"), "after-editor-open").let { assertTrue(it is SaveResult.Success) }
        val before = repository.exportConsistentSnapshot()
        val summary = repository.observeMonthSummary(october).first()
        repository.upsertAccount(AccountCommand(id, stale.account.name, stale.account.kind, stale.account.openingBalanceCent, currentBalanceCent = 7000)).requireSuccess()
        val updated = LedgerRepositoryImpl(db, clock).observeAccounts(false).first().first { it.account.id == id }
        assertEquals(7000L, updated.balanceCent)
        assertEquals(AccountLocation.WECHAT_BALANCE, updated.account.location)
        assertEquals(8500L, updated.account.openingBalanceCent)
        assertEquals(today, AssetHistory.parse(updated.account.openingHistory).last().first)
        assertEquals(before.transactions, repository.exportConsistentSnapshot().transactions)
        assertEquals(summary, repository.observeMonthSummary(october).first())
        val snapshot = repository.exportConsistentSnapshot()
        assertTrue(repository.upsertAccount(AccountCommand(id, stale.account.name, stale.account.kind, 10000, currentBalanceCent = Limits.MAX_OPENING_BALANCE_CENT + 1)) is MutationResult.Failure)
        assertEquals(snapshot, repository.exportConsistentSnapshot())
    }
    @Test fun schemaThreeUpgradeInfersPlatformsAndPreservesMoneyAndTransactions() = runDb {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "asset-schema3-upgrade.db"
        context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/com.blueledger.app.data.local.LedgerDatabase/3.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { legacy ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                legacy.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: JSONArray()
                for (j in 0 until indices.length()) legacy.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            listOf("支付宝余额", "旅行小荷包", "微信零钱", "虚构银行卡").forEachIndexed { i, accountName ->
                legacy.execSQL("INSERT INTO accounts VALUES (?,?,?,?,?,0,1,1,'','')", arrayOf<Any>("legacy$i", accountName, accountName, if (i == 3) "BANK_CARD" else "E_WALLET", (i + 1) * 10000))
            }
            legacy.execSQL("INSERT INTO transactions VALUES ('preserved','EXPENSE',100,'cat_expense_food','legacy0',?,'虚构','虚构',1,1,NULL,NULL)", arrayOf<Any>(today.toEpochDay()))
            legacy.version = 3
        }
        val upgraded = LedgerDatabase.create(context, name)
        try {
            assertEquals(4, upgraded.openHelper.readableDatabase.version)
            val rows = LedgerRepositoryImpl(upgraded, clock).observeAccounts(false).first()
            assertEquals(listOf(AccountLocation.ALIPAY_BALANCE, AccountLocation.ALIPAY_POCKET, AccountLocation.WECHAT_BALANCE, AccountLocation.BANK_CARD), rows.map { it.account.location })
            assertEquals(99900L, rows.sumOf { it.balanceCent })
            upgraded.openHelper.readableDatabase.query("SELECT amountCent FROM transactions WHERE id='preserved'").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals(100L, cursor.getLong(0)) }
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }

    @Test fun assetTransferUpdatesBothBalancesAtomicallyWithoutIncomeOrExpense() = runDb {
        repository.initializeIfNeeded().requireSuccess()
        val from = repository.upsertAccount(AccountCommand(name = "虚构现金", kind = AccountKind.CASH, openingBalanceCent = 10000)).requireId()
        val to = repository.upsertAccount(AccountCommand(name = "工商银行 · 1234", kind = AccountKind.BANK_CARD, openingBalanceCent = 20000, location = AccountLocation.BANK_CARD)).requireId()
        repository.transferAssets(from, to, 2500).requireSuccess()
        val rows = repository.observeAccounts(false).first()
        assertEquals(7500L, rows.first { it.account.id == from }.balanceCent)
        assertEquals(22500L, rows.first { it.account.id == to }.balanceCent)
        assertEquals(30000L, rows.filter { it.account.id in setOf(from, to) }.sumOf { it.balanceCent })
        assertEquals(0, repository.observeTransactions(TransactionFilter()).first().totalCount)
        val before = repository.exportConsistentSnapshot()
        assertTrue(repository.transferAssets(from, to, 9000) is MutationResult.Failure)
        assertTrue(repository.transferAssets(from, from, 100) is MutationResult.Failure)
        assertTrue(repository.transferAssets(from, "missing", 100) is MutationResult.Failure)
        assertEquals(before, repository.exportConsistentSnapshot())
    }

    @Test fun assetTransferRejectsDestinationOverflowWithoutChangingEitherAccount() = runDb {
        repository.initializeIfNeeded().requireSuccess()
        val from = repository.upsertAccount(AccountCommand(name = "虚构现金", kind = AccountKind.CASH, openingBalanceCent = 10000)).requireId()
        val to = repository.upsertAccount(AccountCommand(name = "虚构钱包", kind = AccountKind.E_WALLET, openingBalanceCent = Limits.MAX_OPENING_BALANCE_CENT)).requireId()
        val before = repository.exportConsistentSnapshot()
        assertTrue(repository.transferAssets(from, to, 1) is MutationResult.Failure)
        assertEquals(before, repository.exportConsistentSnapshot())
    }
}
