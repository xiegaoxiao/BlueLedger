package com.blueledger.app.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.blueledger.app.app.ui.BlueLedgerTokens as T

private val WindowOrigin = object : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
}

/** Anchored full-width selection panel; the scrim also covers the app's bottom navigation. */
@Composable
fun LedgerTopDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "top_dropdown",
    anchor: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    var anchorBottom by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val view = LocalView.current
    Box(modifier.onGloballyPositioned {
        val frame = android.graphics.Rect()
        view.getWindowVisibleDisplayFrame(frame)
        val screen = IntArray(2); val window = IntArray(2)
        view.getLocationOnScreen(screen); view.getLocationInWindow(window)
        // Popup origin is the visible window, while layout coordinates include the status inset.
        anchorBottom = it.positionInWindow().y + it.size.height + screen[1] - window[1] - frame.top
    }) {
        anchor()
        if (expanded && anchorBottom > 0f) {
            Popup(popupPositionProvider = WindowOrigin, onDismissRequest = onDismiss,
                properties = PopupProperties(focusable = true)) {
                Box(Modifier.fillMaxSize()) {
                    // Transparent space over the header remains a dismissal target.
                    Box(Modifier.fillMaxSize().clickable(onClick = onDismiss))
                    Box(Modifier.fillMaxSize().padding(top = with(density) { anchorBottom.toDp() })
                        .background(Color.Black.copy(alpha = .6f)).clickable(onClick = onDismiss)
                        .testTag("${tag}_scrim"))
                    Column(Modifier.fillMaxWidth().padding(top = with(density) { anchorBottom.toDp() })
                        .background(T.Surface).testTag(tag), content = content)
                }
            }
        }
    }
}
