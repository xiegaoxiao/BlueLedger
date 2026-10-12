package com.blueledger.app.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.blueledger.app.app.ui.BlueLedgerTokens as T

/** Vector arrow and currency strokes for income / expense choices. */
@Composable
fun LedgerFlowDirectionIcon(incoming: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(16.dp)) {
        val unit = size.width / 16f
        val arrow = Path().apply {
            val points = listOf(8f to 1f, 15f to 8f, 11f to 8f, 11f to 15f,
                5f to 15f, 5f to 8f, 1f to 8f)
            points.forEachIndexed { index, (x, y) ->
                val py = (if (incoming) 16f - y else y) * unit
                if (index == 0) moveTo(x * unit, py) else lineTo(x * unit, py)
            }
            close()
        }
        drawPath(arrow, T.Primary)
        drawPath(arrow, T.TextPrimary, style = Stroke(1.25f * unit))
        val middle = if (incoming) 6.5f else 10.5f
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(T.OnBrand,
            Offset(x1 * unit, y1 * unit), Offset(x2 * unit, y2 * unit), .8f * unit)
        line(6.5f, middle - 2, 8f, middle - .5f)
        line(9.5f, middle - 2, 8f, middle - .5f)
        line(6.5f, middle, 9.5f, middle)
        line(6.5f, middle + 1.3f, 9.5f, middle + 1.3f)
        line(8f, middle - .5f, 8f, middle + 2.5f)
    }
}
