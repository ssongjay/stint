package dev.stint.app.ui

import androidx.compose.runtime.Composable

internal const val TIME_DIAL_HAPTIC_INTERVAL_MILLIS = 28L

/** Platform feedback for crossing one discrete minute on the clock dial. */
@Composable
internal expect fun rememberTimeDialHaptics(): () -> Unit

internal fun shouldEmitTimeDialHaptic(nowMillis: Long, lastMillis: Long): Boolean =
    lastMillis == Long.MIN_VALUE ||
        nowMillis < lastMillis ||
        nowMillis - lastMillis >= TIME_DIAL_HAPTIC_INTERVAL_MILLIS
