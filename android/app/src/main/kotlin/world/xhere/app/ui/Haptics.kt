package world.xhere.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/** A short confirmation buzz for a deliberate action: post, confirm, start, drop. */
@Composable
internal fun hapticClick(onClick: () -> Unit): () -> Unit {
    val haptic = LocalHapticFeedback.current
    return {
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        onClick()
    }
}
