package com.blueledger.app.feature.backup

import com.blueledger.app.core.model.LedgerError

/** 写入结果。[bytesWritten] 只在真正写完（含 flush/close）后才有值。 */
sealed interface BackupFileWriteResult {
    data class Success(val bytesWritten: Long) : BackupFileWriteResult

    data class Failure(val error: LedgerError) : BackupFileWriteResult
}

/**
 * 系统文件访问端口（SAF）。
 *
 * 这一层是**唯一**接触 Android 文件 API 的地方：
 * - ViewModel 只依赖本接口，因此「取消选择不报错」「写入失败不提示成功」
 *   「读取过程中检查体积」等规则可以在纯 JVM 单测里用替身验证。
 * - 生产实现 [ContentResolverBackupFileGateway] 用 `ContentResolver` 读写
 *   `CreateDocument` / `OpenDocument` 返回的 content URI，**不申请任何存储权限**。
 */
interface BackupFileGateway {

    /**
     * 把 [text] 以 UTF-8 写入 [reference] 指向的目标。
     * 任何失败都必须返回 [BackupFileWriteResult.Failure]，绝不能默默吞掉异常后当作成功。
     */
    suspend fun writeText(reference: String, mimeType: String, text: String): BackupFileWriteResult

    /**
     * 读取 [reference] 的文本内容。
     * 实现必须在**读取过程中**做体积保护（见 [BackupTextReader]），而不是只看元数据。
     */
    suspend fun readText(reference: String): BackupTextReadResult

    /** 解析文件显示名（用于「最近备份」文案与恢复确认）；解析不到返回 null。 */
    suspend fun displayName(reference: String): String?
}
