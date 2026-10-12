package com.blueledger.app.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.blueledger.app.app.ui.BlueLedgerTokens as T

/** Compact actions for headers, dialogs and inline toolbars; page-wide saves use LedgerPrimaryButton. */
enum class LedgerActionStyle { PRIMARY, SECONDARY, DESTRUCTIVE, DESTRUCTIVE_OUTLINED, NEUTRAL, TEXT }

@Composable
fun LedgerActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: LedgerActionStyle = LedgerActionStyle.PRIMARY,
    enabled: Boolean = true,
    testTag: String? = null,
    textStyle: TextStyle = LedgerTextStyles.button,
    containerColor: androidx.compose.ui.graphics.Color? = null,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(T.RadiusChip),
    contentPadding: androidx.compose.foundation.layout.PaddingValues? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val actionModifier = modifier.heightIn(min = T.MinTouchTarget)
        .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
    val content: @Composable () -> Unit = {
        if (leadingIcon != null) { leadingIcon(); Spacer(Modifier.width(6.dp)) }
        Text(text, style = textStyle)
    }
    when (style) {
        LedgerActionStyle.PRIMARY, LedgerActionStyle.DESTRUCTIVE -> Button(
            onClick = onClick, enabled = enabled, modifier = actionModifier, shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (style == LedgerActionStyle.DESTRUCTIVE) T.Risk else T.Primary,
                contentColor = T.OnBrand,
            ),
            contentPadding = contentPadding ?: ButtonDefaults.ContentPadding,
        ) { content() }
        LedgerActionStyle.SECONDARY, LedgerActionStyle.DESTRUCTIVE_OUTLINED -> {
            val color = if (style == LedgerActionStyle.DESTRUCTIVE_OUTLINED) T.Risk else T.Primary
            OutlinedButton(onClick = onClick, enabled = enabled, modifier = actionModifier, shape = shape,
                border = BorderStroke(1.dp, if (enabled) color.copy(alpha = .4f) else T.Border),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
                contentPadding = contentPadding ?: ButtonDefaults.ContentPadding,
            ) { content() }
        }
        LedgerActionStyle.NEUTRAL, LedgerActionStyle.TEXT -> TextButton(onClick = onClick, enabled = enabled, modifier = actionModifier, shape = shape,
            colors = ButtonDefaults.textButtonColors(contentColor = if (style == LedgerActionStyle.TEXT) T.Primary else T.TextSecondary, containerColor = containerColor ?: androidx.compose.ui.graphics.Color.Transparent),
            contentPadding = contentPadding ?: ButtonDefaults.TextButtonContentPadding,
        ) { content() }
    }
}
