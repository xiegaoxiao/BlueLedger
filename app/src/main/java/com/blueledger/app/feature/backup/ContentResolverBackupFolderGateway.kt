package com.blueledger.app.feature.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.blueledger.app.core.model.LedgerError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class ContentResolverBackupFolderGateway(context: Context, private val files: BackupFileGateway) : BackupFolderGateway {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val preferences = app.getSharedPreferences("backup_export_folder", Context.MODE_PRIVATE)
    private val folderState = MutableStateFlow(preferences.getString("uri", null)?.let { reference ->
        BackupFolder(reference, preferences.getString("label", null) ?: "已选择的文件夹")
    })
    override val selectedFolder = folderState.asStateFlow()

    override suspend fun rememberFolder(reference: String): BackupFolderSelectionResult = withContext(Dispatchers.IO) {
        try {
            val tree = Uri.parse(reference)
            require(tree.scheme == "content" && DocumentsContract.isTreeUri(tree))
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            resolver.takePersistableUriPermission(tree, flags)
            check(resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission })
            val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val columns = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS)
            val name = resolver.query(document, columns, null, null, null)?.use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR)
                check(cursor.getLong(2) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE.toLong() != 0L)
                cursor.getString(0)
            } ?: error("无法读取所选文件夹")
            val id = DocumentsContract.getTreeDocumentId(tree)
            val label = if (tree.authority == "com.android.externalstorage.documents" && id.startsWith("primary:")) {
                "内部存储 / " + id.removePrefix("primary:")
            } else name
            check(preferences.edit().putString("uri", reference).putString("label", label).commit())
            val previous = folderState.value
            val folder = BackupFolder(reference, label)
            folderState.value = folder
            // Only release the grant managed by this setting, after the new setting is committed.
            if (previous != null && previous.reference != reference) {
                runCatching { resolver.releasePersistableUriPermission(Uri.parse(previous.reference), flags) }
            }
            BackupFolderSelectionResult.Success(folder)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            BackupFolderSelectionResult.Failure(LedgerError.Storage("无法保存这个位置，请选择一个可写文件夹"))
        }
    }

    override suspend fun exportNewFile(fileName: String, mimeType: String, text: String): BackupFolderExportResult = withContext(Dispatchers.IO) {
        var created: Uri? = null
        try {
            val folder = folderState.value ?: error("尚未选择保存文件夹")
            val tree = Uri.parse(folder.reference)
            check(resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission })
            val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            // Check for collisions before creating; existing documents are never opened for writing.
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) check(cursor.getString(0) != fileName) { "文件名已存在，请重新导出" }
            } ?: error("无法读取保存文件夹")
            val uri = DocumentsContract.createDocument(resolver, document, mimeType, fileName)
                ?: error("无法创建备份文件")
            check(DocumentsContract.getDocumentId(uri) != DocumentsContract.getTreeDocumentId(tree))
            created = uri
            when (val result = files.writeText(uri.toString(), mimeType, text)) {
                is BackupFileWriteResult.Failure -> {
                    removeIncomplete(uri)
                    created = null
                    BackupFolderExportResult.Failure(result.error)
                }
                is BackupFileWriteResult.Success -> {
                    val actualName = files.displayName(uri.toString()) ?: fileName
                    created = null
                    BackupFolderExportResult.Success(actualName, folder.label)
                }
            }
        } catch (e: CancellationException) {
            created?.let(::removeIncomplete)
            throw e
        } catch (_: Exception) {
            created?.let(::removeIncomplete)
            BackupFolderExportResult.Failure(LedgerError.Storage("保存未完成：文件夹可能已移动或授权已失效，请重新选择保存位置"))
        }
    }

    private fun removeIncomplete(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
    }
}
