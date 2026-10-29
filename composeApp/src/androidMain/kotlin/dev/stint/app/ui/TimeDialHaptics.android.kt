package dev.stint.app.ui

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

@Composable
internal actual fun rememberTimeDialHaptics(): () -> Unit {
    val view = LocalView.current
    return remember(view) {
        var lastTickAtMillis = Long.MIN_VALUE
        {
            val nowMillis = SystemClock.uptimeMillis()
            if (shouldEmitTimeDialHaptic(nowMillis, lastTickAtMillis)) {
                lastTickAtMillis = nowMillis
                val feedback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
                } else {
                    HapticFeedbackConstants.CLOCK_TICK
                }
                view.performHapticFeedback(feedback)
            }
        }
    }
}
