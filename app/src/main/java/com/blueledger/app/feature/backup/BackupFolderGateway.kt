package com.blueledger.app.feature.backup

import com.blueledger.app.core.model.LedgerError
import kotlinx.coroutines.flow.StateFlow

data class BackupFolder(val reference: String, val label: String)

sealed interface BackupFolderSelectionResult {
    data class Success(val folder: BackupFolder) : BackupFolderSelectionResult
    data class Failure(val error: LedgerError) : BackupFolderSelectionResult
}

sealed interface BackupFolderExportResult {
    data class Success(val fileName: String, val folderLabel: String) : BackupFolderExportResult
    data class Failure(val error: LedgerError) : BackupFolderExportResult
}

/** A user-authorized folder survives navigation and process restarts. */
interface BackupFolderGateway {
    val selectedFolder: StateFlow<BackupFolder?>
    suspend fun rememberFolder(reference: String): BackupFolderSelectionResult
    suspend fun exportNewFile(fileName: String, mimeType: String, text: String): BackupFolderExportResult
}
