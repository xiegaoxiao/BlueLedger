package com.blueledger.app.feature.entry

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import com.blueledger.app.core.designsystem.TAG_LEDGER_AMOUNT_KEYBOARD
import com.blueledger.app.feature.reference.ReferencePreferences

const val TAG_ENTRY_KEYBOARD = TAG_LEDGER_AMOUNT_KEYBOARD

/** Preference policy belongs to the page; the shared keyboard only emits a press event. */
@Composable
internal fun rememberAmountKeyboardFeedback(): () -> Unit {
    val view = LocalView.current
    val preferences = remember(view) { ReferencePreferences(view.context) }
    return remember(view, preferences) {
        {
            if (preferences.sound) view.playSoundEffect(android.view.SoundEffectConstants.CLICK)
            if (preferences.haptic) view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }
}
