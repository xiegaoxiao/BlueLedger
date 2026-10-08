package com.blueledger.app.feature.backup

import com.blueledger.app.core.contract.BackupLimits
import com.blueledger.app.core.model.LedgerError
import com.blueledger.app.core.model.ValidationCode
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** 备份文本的读取结果。 */
sealed interface BackupTextReadResult {
    data class Success(val text: String) : BackupTextReadResult

    data class Failure(val error: LedgerError) : BackupTextReadResult
}

/**
 * 带**读取过程体积保护**的文本读取器。
 *
 * 关键要求（docs/AI提示词 §7 A6-10、docs/实现决策.md §8）：
 * 体积上限必须在**读取过程中**累计检查，不能只信 `ContentResolver` 返回的 size 元数据
 * （它可能是 -1，也可能与实际内容不一致）。这里的实现每读一块就累加已读字节数，
 * 一旦超过 [BackupLimits.MAX_BYTES] 立即停止并返回 [ValidationCode.BACKUP_TOO_LARGE]，
 * **绝不截断后当作成功**。
 *
 * 其他失败（I/O 异常、非 UTF-8 字节）都返回 [BackupTextReadResult.Failure]，不抛异常，
 * 也不修改任何业务数据。
 */
object BackupTextReader {

    /** 每次读取的块大小：8 KiB，兼顾小文件延迟与大文件吞吐。 */
    const val CHUNK_SIZE: Int = 8 * 1024

    private const val INITIAL_CAPACITY: Int = 1 shl 20

    fun read(input: InputStream, maxBytes: Long = BackupLimits.MAX_BYTES): BackupTextReadResult {
        require(maxBytes > 0L) { "maxBytes 必须为正数" }
        val buffer = ByteArray(CHUNK_SIZE)
        val sink = ByteArrayOutputStream(minOf(maxBytes, INITIAL_CAPACITY.toLong()).toInt())
        var total = 0L
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                if (total > maxBytes) {
                    return BackupTextReadResult.Failure(
                        LedgerError.Backup(
                            code = ValidationCode.BACKUP_TOO_LARGE,
                            message = "备份文件超过 ${maxBytes / (1024 * 1024)} MiB 上限，已拒绝（读取过程中检测）",
                            detail = "已读取 $total 字节",
                        ),
                    )
                }
                sink.write(buffer, 0, read)
            }
        } catch (e: IOException) {
            return BackupTextReadResult.Failure(
                LedgerError.Backup(
                    code = ValidationCode.STORAGE_FAILURE,
                    message = "读取备份文件失败，请重试",
                    detail = e.readableReason() ?: e.javaClass.simpleName,
                ),
            )
        }

        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(sink.toByteArray()))
                .toString()
        } catch (e: CharacterCodingException) {
            return BackupTextReadResult.Failure(
                LedgerError.Backup(
                    code = ValidationCode.BACKUP_NOT_JSON,
                    message = "文件不是有效的 UTF-8 文本，无法作为备份读取",
                ),
            )
        }
        return BackupTextReadResult.Success(text)
    }
}
