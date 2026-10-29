package dev.stint.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay

private const val WHEEL_PENDING_TIMEOUT_MILLIS = 12_000L

/**
 * Keeps a minute/second wheel preview local until one settled value is accepted by the server.
 * The clock supplies the fitted text style, so the wheels occupy the existing numeric readout.
 */
@Composable
internal fun ClockTimeDial(
    valueSeconds: Int,
    minimumSeconds: Int,
    maximumSeconds: Int,
    enabled: Boolean,
    interactionKey: String,
    busy: Boolean,
    feedbackMessage: String?,
    onCommit: (Int, String) -> Boolean,
    height: Dp,
    modifier: Modifier = Modifier,
    onTick: (() -> Unit)? = null,
    onAdjustingChange: (Boolean) -> Unit = {},
    content: @Composable (
        previewSeconds: Int?,
        faceHeight: Dp,
        timeContent: ClockTimeContent?,
    ) -> Unit,
) {
    val hasRange = minimumSeconds <= maximumSeconds
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val dialEnabled = enabled && hasRange && windowFocused
    val platformTick = rememberTimeDialHaptics()
    val tick = onTick ?: platformTick
    val latestCommit by rememberUpdatedState(onCommit)
    val latestAdjustingChange by rememberUpdatedState(onAdjustingChange)
    val latestKey by rememberUpdatedState(interactionKey)
    val latestEnabled by rememberUpdatedState(dialEnabled)

    var previewSeconds by remember(interactionKey) { mutableStateOf<Int?>(null) }
    var pendingSeconds by remember(interactionKey) { mutableStateOf<Int?>(null) }
    var pendingSawBusy by remember(interactionKey) { mutableStateOf(false) }
    var pendingFeedback by remember(interactionKey) { mutableStateOf<String?>(null) }
    var gestureActive by remember(interactionKey) { mutableStateOf(false) }
    val adjusting = gestureActive || previewSeconds != null || pendingSeconds != null

    fun resetToAuthoritative() {
        previewSeconds = null
        pendingSeconds = null
        pendingSawBusy = false
        pendingFeedback = null
        gestureActive = false
    }

    fun submit(seconds: Int, key: String) {
        val bounded = seconds.coerceIn(minimumSeconds, maximumSeconds)
        previewSeconds = bounded
        if (bounded == valueSeconds) {
            resetToAuthoritative()
        } else if (latestEnabled && latestCommit(bounded, key)) {
            pendingSeconds = bounded
            pendingSawBusy = busy
            pendingFeedback = feedbackMessage
            gestureActive = false
        } else {
            resetToAuthoritative()
        }
    }

    LaunchedEffect(valueSeconds, interactionKey, pendingSeconds) {
        val pending = pendingSeconds
        if (pending == null) {
            previewSeconds = null
        } else if (valueSeconds == pending && !busy) {
            resetToAuthoritative()
        }
    }
    LaunchedEffect(busy, feedbackMessage, pendingSeconds) {
        if (pendingSeconds != null) {
            if (busy) pendingSawBusy = true
            val feedbackChanged = feedbackMessage != null && feedbackMessage != pendingFeedback
            if (!busy && (pendingSawBusy || feedbackChanged) && valueSeconds != pendingSeconds) {
                resetToAuthoritative()
            }
        }
    }
    LaunchedEffect(pendingSeconds) {
        if (pendingSeconds != null) {
            delay(WHEEL_PENDING_TIMEOUT_MILLIS)
            if (pendingSeconds != null && valueSeconds != pendingSeconds) resetToAuthoritative()
        }
    }
    LaunchedEffect(dialEnabled, interactionKey) {
        if (!dialEnabled && pendingSeconds == null) resetToAuthoritative()
    }
    LaunchedEffect(adjusting) { latestAdjustingChange(adjusting) }
    DisposableEffect(Unit) { onDispose { latestAdjustingChange(false) } }

    val displayedSeconds = previewSeconds ?: pendingSeconds ?: valueSeconds
    val timeContent: ClockTimeContent? = if (dialEnabled || previewSeconds != null || pendingSeconds != null) ({ style: TextStyle ->
        VerticalTimeWheelReadout(
            totalSeconds = displayedSeconds,
            minimumSeconds = if (hasRange) minimumSeconds else valueSeconds,
            maximumSeconds = if (hasRange) maximumSeconds else valueSeconds,
            enabled = dialEnabled && pendingSeconds == null,
            interactionKey = interactionKey,
            textStyle = style,
            modifier = Modifier.fillMaxSize(),
            onPreview = { previewSeconds = it },
            onSettled = ::submit,
            onCancel = {
                if (pendingSeconds == null) previewSeconds = null
                gestureActive = false
            },
            onGestureActive = { gestureActive = it },
            onTick = tick,
        )
    }) else null
    Box(modifier.fillMaxWidth().height(height)) {
        content(previewSeconds ?: pendingSeconds, height, timeContent)
    }
}
