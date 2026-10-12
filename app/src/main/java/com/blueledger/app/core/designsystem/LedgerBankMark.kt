package com.blueledger.app.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.blueledger.app.app.ui.BlueLedgerTokens as T

/** Compact vector bank marks using the app's blue palette. */
@Composable
fun LedgerBankMark(pattern: Int, diameter: Dp = 32.dp) {
    Canvas(Modifier.size(diameter)) {
        val u = size.width / 32f
        val white = T.OnBrand
        fun line(x: Float, y: Float, xx: Float, yy: Float, width: Float = 2f) =
            drawLine(white, Offset(x*u, y*u), Offset(xx*u, yy*u), width*u)
        fun ring(radius: Float = 11f) = drawCircle(white, radius*u, style = Stroke(2f*u))
        fun polygon(vararg points: Pair<Float, Float>) {
            val p = Path().apply { points.forEachIndexed { i, (x,y) -> if (i == 0) moveTo(x*u,y*u) else lineTo(x*u,y*u) }; close() }
            drawPath(p, white)
        }
        drawCircle(T.Primary)
        when (pattern) {
            0 -> { ring(); listOf(10f,16f,22f).forEach { y -> line(10f,y,22f,y) }; line(13f,10f,13f,16f); line(19f,16f,19f,22f) }
            1 -> { drawArc(white, 45f, 285f, false, topLeft = Offset(6*u,6*u), size = androidx.compose.ui.geometry.Size(20*u,20*u), style = Stroke(4*u)); polygon(10f to 16f,16f to 10f,22f to 16f,16f to 22f) }
            2 -> { ring(); line(16f,5f,16f,27f); line(12f,8f,12f,19f); line(20f,13f,20f,24f); line(10f,20f,20f,20f) }
            3 -> { ring(); drawRoundRect(white, Offset(10*u,12*u), androidx.compose.ui.geometry.Size(12*u,8*u), androidx.compose.ui.geometry.CornerRadius(2*u), style = Stroke(2*u)); line(16f,6f,16f,26f) }
            4 -> { val p = Path().apply { moveTo(7*u,23*u); lineTo(12*u,9*u); lineTo(17*u,23*u); lineTo(22*u,9*u); lineTo(27*u,23*u); close() }; drawPath(p, white, style = Stroke(2*u)) }
            5 -> { polygon(7f to 10f,12f to 6f,12f to 24f,7f to 24f); drawCircle(white,8*u,Offset(19*u,19*u)); drawCircle(T.Primary,4.5f*u,Offset(18*u,20*u)) }
            6 -> { ring(); listOf(10f,16f,22f).forEach { x -> line(x,9f,x,24f) }; line(10f,9f,16f,13f); line(22f,9f,16f,13f) }
            7 -> { drawCircle(white,5*u,Offset(12*u,14*u)); drawCircle(white,5*u,Offset(21*u,18*u)); drawCircle(T.Primary,4*u,Offset(15*u,12*u)); drawCircle(T.Primary,4*u,Offset(18*u,20*u)); line(8f,15f,8f,25f); line(25f,7f,25f,17f) }
            8 -> { line(11f,7f,11f,25f,3f); val p=Path().apply { moveTo(11*u,8*u); lineTo(19*u,8*u); quadraticTo(27*u,12*u,19*u,16*u); quadraticTo(28*u,23*u,19*u,25*u); lineTo(11*u,25*u) }; drawPath(p,white,style=Stroke(2*u)); (10..23 step 3).forEach { y -> line(12f,y.toFloat(),22f,y.toFloat(),.7f) } }
            9 -> { polygon(16f to 6f,10f to 17f,22f to 17f); polygon(9f to 18f,4f to 26f,15f to 23f); polygon(23f to 18f,28f to 26f,17f to 23f) }
        }
    }
}
