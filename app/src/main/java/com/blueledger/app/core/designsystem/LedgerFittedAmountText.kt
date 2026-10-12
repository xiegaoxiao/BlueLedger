package com.blueledger.app.core.designsystem

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.blueledger.app.app.ui.BlueLedgerTokens as T
import com.blueledger.app.app.ui.LocalHideAmounts
import com.blueledger.app.core.money.Money

/** Fit by measuring candidate sizes: Android's large-font scaling is not linear. */
@Composable
fun LedgerFittedAmountText(cent: Long, modifier: Modifier = Modifier, large: Boolean = false, alignEnd: Boolean = false, color: Color = T.TextPrimary, alignCenter: Boolean = false, prefix: String = "", fontSizeSp: Float? = null, groupThousands: Boolean = true, weight: FontWeight = FontWeight.Medium) {
    val text = if (LocalHideAmounts.current) "••••" else prefix + Money.format(cent).let { if (groupThousands) it else it.replace(",", "") }
    val measurer = rememberTextMeasurer()
    val baseSize = fontSizeSp ?: if (large) 26f else 16f
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val style = TextStyle(fontSize = baseSize.sp, fontWeight = weight, letterSpacing = 0.sp)
        val fitted = remember(text, constraints.maxWidth, baseSize, measurer, LocalDensity.current) {
            var low = .1f; var high = baseSize
            repeat(12) {
                val candidate = (low + high) / 2
                if (measurer.measure(text, style.copy(fontSize = candidate.sp), softWrap = false).size.width <= constraints.maxWidth - 2) low = candidate else high = candidate
            }
            low
        }
        Text(text, modifier.fillMaxWidth(), style = style.copy(fontSize = fitted.sp, lineHeight = (fitted * 1.25f).sp), maxLines = 1, softWrap = false, color = color,
            textAlign = when { alignCenter -> TextAlign.Center; alignEnd -> TextAlign.End; else -> TextAlign.Start })
    }
}
