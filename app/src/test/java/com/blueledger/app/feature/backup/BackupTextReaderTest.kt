package com.blueledger.app.feature.backup

import com.blueledger.app.core.contract.BackupLimits
import com.blueledger.app.core.model.ValidationCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * 读取期体积保护测试。
 *
 * 关键证据：用一个**没有 size/available 元数据**的流验证超限会在读取过程中立即失败，
 * 而不是读完整个文件、也不是依赖 `ContentResolver` 返回的 size。
 */
class BackupTextReaderTest {

    @Test
    fun `正常读取 UTF-8 文本`() {
        val text = "{\"product\":\"blueledger\",\"note\":\"中文备注\"}"
        val result = BackupTextReader.read(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        assertEquals(BackupTextReadResult.Success(text), result)
    }

    @Test
    fun `写入量与上限相等时仍然可以读取`() {
        val limit = 64L * 1024L
        val result = BackupTextReader.read(FixedLengthStream(limit), limit)
        assertTrue("恰好等于上限应当成功，实际=$result", result is BackupTextReadResult.Success)
        assertEquals(limit.toInt(), (result as BackupTextReadResult.Success).text.length)
    }

    @Test
    fun `超过上限在读取过程中立即拒绝，不依赖元数据也不读完整个流`() {
        val limit = 64L * 1024L
        val stream = EndlessStream()

        val result = BackupTextReader.read(stream, limit)

        val failure = result as? BackupTextReadResult.Failure
        requireNotNull(failure) { "超过上限必须失败，实际=$result" }
        assertEquals(ValidationCode.BACKUP_TOO_LARGE, failure.error.backupCode())
        assertTrue(failure.error.message.contains("上限"))
        // 关键：读到略超上限就停了。流是无限的，如果实现“先读完整再判断”会永远不返回。
        assertTrue(
            "应当在上限附近停止读取，实际读取 ${stream.bytesRead} 字节（上限 $limit）",
            stream.bytesRead <= limit + BackupTextReader.CHUNK_SIZE,
        )
    }

    @Test
    fun `真实默认上限：32 MiB 加 1 字节被拒绝`() {
        // 只做“拒绝”这一侧：成功侧需要构造 33.5M 字符的字符串，代价过大且已被小上限用例覆盖。
        val result = BackupTextReader.read(FixedLengthStream(BackupLimits.MAX_BYTES + 1L))

        val failure = result as? BackupTextReadResult.Failure
        requireNotNull(failure) { "超过 32 MiB 必须失败，实际=$result" }
        assertEquals(ValidationCode.BACKUP_TOO_LARGE, failure.error.backupCode())
        assertTrue(failure.error.message.contains("32 MiB"))
    }

    @Test
    fun `IO 异常返回失败而不是抛出`() {
        val result = BackupTextReader.read(FailingStream())
        val failure = result as? BackupTextReadResult.Failure
        requireNotNull(failure) { "I/O 失败必须返回 Failure，实际=$result" }
        assertEquals(ValidationCode.STORAGE_FAILURE, failure.error.backupCode())
    }

    @Test
    fun `非法 UTF-8 字节被拒绝为不可读文本`() {
        val bytes = byteArrayOf(0x7B, 0xC3.toByte(), 0x28, 0x7D) // { <invalid> ( }
        val result = BackupTextReader.read(ByteArrayInputStream(bytes))
        val failure = result as? BackupTextReadResult.Failure
        requireNotNull(failure) { "非法 UTF-8 必须失败，实际=$result" }
        assertEquals(ValidationCode.BACKUP_NOT_JSON, failure.error.backupCode())
    }

    /** 无限流：不提供 available/size，反复给出同一块内容。 */
    private class EndlessStream : InputStream() {
        var bytesRead: Long = 0L
            private set

        private val chunk = ByteArray(8 * 1024) { 'x'.code.toByte() }

        override fun read(): Int {
            bytesRead += 1
            return 'x'.code
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val count = minOf(len, chunk.size)
            System.arraycopy(chunk, 0, b, off, count)
            bytesRead += count
            return count
        }
    }

    /** 固定长度流：不预分配内存，长度由构造参数决定。 */
    private class FixedLengthStream(private val total: Long) : InputStream() {
        private var remaining: Long = total
        private val chunk = ByteArray(8 * 1024) { 'a'.code.toByte() }

        override fun read(): Int {
            if (remaining <= 0L) return -1
            remaining -= 1
            return 'a'.code
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0L) return -1
            val count = minOf(len.toLong(), chunk.size.toLong(), remaining).toInt()
            System.arraycopy(chunk, 0, b, off, count)
            remaining -= count
            return count
        }
    }

    private class FailingStream : InputStream() {
        override fun read(): Int = throw IOException("模拟磁盘错误")

        override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("模拟磁盘错误")
    }
}
