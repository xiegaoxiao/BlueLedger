package com.blueledger.app.feature.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.blueledger.app.core.model.LedgerError
import com.blueledger.app.core.model.ValidationCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * 生产实现的系统文件访问：`ContentResolver` + SAF content URI。
 *
 * - 目标 URI 来自 `ActivityResultContracts.CreateDocument`，来源 URI 来自 `OpenDocument`，
 *   两者都由用户显式选择，因此**不需要也不申请任何存储权限**。
 * - 读写都在 [Dispatchers.IO] 上执行，不阻塞主线程。
 * - 只有 `write` + `flush` + `close` 全部成功才返回 [BackupFileWriteResult.Success]：
 *   真实写失败（磁盘满、权限撤销、provider 拒绝）一律是 Failure，界面不会提示成功。
 */
class ContentResolverBackupFileGateway(context: Context) : BackupFileGateway {

    private val resolver = context.applicationContext.contentResolver

    override suspend fun writeText(
        reference: String,
        mimeType: String,
        text: String,
    ): BackupFileWriteResult = withContext(Dispatchers.IO) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        try {
            val uri = Uri.parse(reference)
            val output: OutputStream = openOutput(uri)
                ?: return@withContext BackupFileWriteResult.Failure(
                    LedgerError.Backup(
                        code = ValidationCode.BACKUP_WRITE_FAILED,
                        message = "无法打开目标文件，导出未完成",
                    ),
                )
            output.use {
                it.write(bytes)
                it.flush()
            }
            BackupFileWriteResult.Success(bytes.size.toLong())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BackupFileWriteResult.Failure(
                LedgerError.Backup(
                    code = ValidationCode.BACKUP_WRITE_FAILED,
                    message = "写入文件失败，导出未完成：${e.readableReason() ?: e.javaClass.simpleName}",
                ),
            )
        }
    }

    override suspend fun readText(reference: String): BackupTextReadResult = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(reference)
            val input = resolver.openInputStream(uri)
                ?: return@withContext BackupTextReadResult.Failure(
                    LedgerError.Backup(
                        code = ValidationCode.STORAGE_FAILURE,
                        message = "无法打开所选文件，请重新选择",
                    ),
                )
            input.use { BackupTextReader.read(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BackupTextReadResult.Failure(
                LedgerError.Backup(
                    code = ValidationCode.STORAGE_FAILURE,
                    message = "读取文件失败：${e.readableReason() ?: e.javaClass.simpleName}",
                ),
            )
        }
    }

    override suspend fun displayName(reference: String): String? = withContext(Dispatchers.IO) {
        try {
            resolver.query(Uri.parse(reference), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 优先用 `"wt"`（truncate）打开：用户可能选择**已存在**的文件，
     * 若不截断会残留旧文件尾部的字节，生成一个损坏的备份。
     */
    private fun openOutput(uri: Uri): OutputStream? = try {
        resolver.openOutputStream(uri, "wt")
    } catch (e: Exception) {
        resolver.openOutputStream(uri)
    }
}
