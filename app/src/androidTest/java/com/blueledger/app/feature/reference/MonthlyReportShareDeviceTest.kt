package com.blueledger.app.feature.reference

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.core.model.TransactionFilter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth

/** 真正的 Android 路径与 FileProvider 权限边界；不打开分享面板、不发送消息。 */
@RunWith(AndroidJUnit4::class)
class MonthlyReportShareDeviceTest {
    @Test fun reportCanBeReadThroughNativeFileProvider() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val month = YearMonth.of(2026, 10)
        val state = ReferenceLedgerState(TransactionFilter(yearMonth = month), loaded = true)
        val intent = monthlyReportShareIntent(context, month, state, state, true)
        val uri = intent.clipData!!.getItemAt(0).uri
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.reports", uri.authority)
        assertEquals("image/png", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        context.contentResolver.openInputStream(uri).use { stream ->
            val bitmap = BitmapFactory.decodeStream(stream)
            assertNotNull(bitmap); assertEquals(1080, bitmap.width); bitmap.recycle()
        }
    }
}
