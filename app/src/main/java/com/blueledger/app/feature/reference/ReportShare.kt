package com.blueledger.app.feature.reference

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.blueledger.app.core.money.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.YearMonth

/** 独立绘制完整报告，避免截取可视区域导致长账单被裁掉。 */
internal suspend fun monthlyReportShareIntent(context: Context, month: YearMonth, state: ReferenceLedgerState, previous: ReferenceLedgerState, hidden: Boolean,
    uriForFile: (Context, File) -> android.net.Uri = { app, file -> FileProvider.getUriForFile(app, "${app.packageName}.reports", file) },
): Intent {
    val appContext = context.applicationContext
    val bitmap = withContext(Dispatchers.Default) { renderMonthlyReport(month, state, previous, hidden) }
    val file = try {
        withContext(Dispatchers.IO) {
            val folder = File(appContext.cacheDir, "reports").apply { mkdirs() }
            File(folder, "blueledger-$month.png").also { target ->
                target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
        }
    } finally { bitmap.recycle() }
    val uri = uriForFile(appContext, file)
    return Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("月账单", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

internal fun renderMonthlyReport(month: YearMonth, state: ReferenceLedgerState, previous: ReferenceLedgerState, hidden: Boolean): Bitmap {
    val width = 1080
    val categories = state.expenseCategories
    val height = 1840 + categories.size * 112 + state.expenseRanking.size * 100
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val blue = Color.rgb(51, 116, 232)
    val muted = Color.rgb(117, 127, 146)
    val dark = Color.rgb(31, 42, 62)
    fun text(value: String, x: Float, y: Float, size: Float = 36f, color: Int = dark, bold: Boolean = false, centered: Boolean = false) {
        paint.color = color; paint.textSize = size
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.style = Paint.Style.FILL
        paint.textAlign = if (centered) Paint.Align.CENTER else Paint.Align.LEFT
        canvas.drawText(value, x, y, paint)
    }
    fun amount(cent: Long) = if (hidden) "••••" else Money.format(cent)
    fun rect(left: Float, top: Float, right: Float, bottom: Float, color: Int) {
        paint.color = color; paint.style = Paint.Style.FILL
        canvas.drawRect(left, top, right, bottom, paint)
    }
    canvas.drawColor(Color.WHITE)
    rect(0f, 0f, width.toFloat(), 560f, Color.rgb(234, 242, 255))
    text("蓝记 · ${month.year}年${month.monthValue}月账单", 540f, 110f, 46f, blue, true, true)
    text("本月结余", 540f, 210f, centered = true)
    text(amount(state.summary.balanceCent), 540f, 305f, 78f, dark, true, true)
    text("上月结余 ${amount(previous.summary.balanceCent)}", 540f, 380f, 32f, muted, centered = true)
    text("记录了 ${state.days.size} 天 · ${state.totalCount} 笔账单", 540f, 465f, 34f, muted, centered = true)
    text("收支对比", 60f, 650f, 46f, bold = true)
    text("收入 ${amount(state.summary.incomeCent)}", 60f, 740f, 38f)
    text("支出 ${amount(state.summary.expenseCent)}", 550f, 740f, 38f)
    val largest = maxOf(state.summary.incomeCent, state.summary.expenseCent, previous.summary.incomeCent, previous.summary.expenseCent, 1L)
    listOf("本月收入" to state.summary.incomeCent, "上月收入" to previous.summary.incomeCent, "本月支出" to state.summary.expenseCent, "上月支出" to previous.summary.expenseCent).forEachIndexed { i, (label, cent) ->
        val y = 810f + i * 62f
        text(label, 60f, y + 20f, 30f, muted)
        rect(240f, y, 1000f, y + 20f, Color.rgb(238, 240, 244))
        rect(240f, y, 240f + (cent.toDouble() / largest * 760).toFloat(), y + 20f, if (i % 2 == 0) blue else Color.rgb(181, 198, 221))
    }
    text("支出分类", 60f, 1130f, 46f, bold = true)
    val colors = listOf(blue, Color.rgb(112, 165, 248), Color.rgb(143, 207, 192), Color.rgb(167, 155, 223), Color.rgb(229, 179, 109))
    val total = categories.sumOf { it.cent }
    paint.style = Paint.Style.STROKE; paint.strokeWidth = 52f
    val ring = RectF(390f, 1200f, 690f, 1500f)
    if (total == 0L) { paint.color = Color.LTGRAY; canvas.drawOval(ring, paint) }
    else {
        var start = -90f
        categories.forEachIndexed { i, category ->
            val sweep = (category.cent.toDouble() / total * 360).toFloat()
            paint.color = colors[i % colors.size]; canvas.drawArc(ring, start, sweep, false, paint); start += sweep
        }
    }
    if (total == 0L) text("暂无数据", 540f, 1365f, 32f, muted, centered = true)
    var y = 1600f
    categories.forEachIndexed { i, category ->
        text(category.name.take(20), 60f, y, 36f)
        text("${category.permille / 10f}%", 450f, y, 30f, muted)
        text(amount(category.cent), 740f, y, 36f)
        val max = categories.first().cent.coerceAtLeast(1)
        rect(60f, y + 26f, 60f + (category.cent.toDouble() / max * 940).toFloat(), y + 36f, colors[i % colors.size])
        y += 112f
    }
    text("支出排行榜", 60f, y + 30f, 46f, bold = true); y += 120f
    state.expenseRanking.forEach { row ->
        text((row.note.ifBlank { row.categoryName }).take(18), 60f, y, 36f)
        text(amount(row.amountCent), 740f, y, 36f)
        text(row.occurredOn.toString(), 60f, y + 40f, 26f, muted); y += 100f
    }
    text("蓝记 · 每一笔，都清楚", 540f, (height - 35).toFloat(), 28f, muted, centered = true)
    return bitmap
}
