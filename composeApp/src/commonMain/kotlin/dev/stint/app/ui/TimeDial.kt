package dev.stint.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt

private const val DIAL_SETTLE_MILLIS = 240L
private const val DIAL_PENDING_TIMEOUT_MILLIS = 12_000L

private enum class DialChangeSource { NONE, POINTER, WHEEL, KEYBOARD, ACCESSIBILITY }

private data class ReleasedDialGesture(
    val velocityX: Float,
    val interactionKey: String,
)

/**
 * A detented time wheel layered under the clock face. One network mutation is requested only
 * after the gesture, fling, wheel burst, keyboard sequence, or accessibility adjustment settles.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ClockTimeDial(
    valueMinutes: Int,
    minimumMinutes: Int,
    maximumMinutes: Int,
    enabled: Boolean,
    interactionKey: String,
    busy: Boolean,
    feedbackMessage: String?,
    onCommit: (Int, String) -> Boolean,
    height: Dp,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onTick: (() -> Unit)? = null,
    onAdjustingChange: (Boolean) -> Unit = {},
    content: @Composable (previewMinutes: Int?, faceHeight: Dp) -> Unit,
) {
    val hasRange = minimumMinutes <= maximumMinutes
    val dialMinimum = if (hasRange) minimumMinutes else valueMinutes
    val dialMaximum = if (hasRange) maximumMinutes else valueMinutes
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val dialEnabled = enabled && hasRange && windowFocused
    val labelHeight = with(LocalDensity.current) {
        MaterialTheme.typography.labelSmall.lineHeight.toPx().toDp()
    }
    val rulerHeight = if (compact) 20.dp else maxOf(36.dp, labelHeight + 18.dp)
    val faceHeight = (height - rulerHeight).coerceAtLeast(48.dp)
    val pixelsPerMinute = with(LocalDensity.current) { (if (compact) 14.dp else 18.dp).toPx() }
    val platformTick = rememberTimeDialHaptics()
    val tick = onTick ?: platformTick
    val latestCommit by rememberUpdatedState(onCommit)
    val latestTick by rememberUpdatedState(tick)
    val latestInteractionKey by rememberUpdatedState(interactionKey)
    val latestEnabled by rememberUpdatedState(dialEnabled)
    val latestValueMinutes by rememberUpdatedState(valueMinutes)
    val latestAdjustingChange by rememberUpdatedState(onAdjustingChange)

    var dialValue by remember(interactionKey) { mutableFloatStateOf(valueMinutes.toFloat()) }
    var previewMinutes by remember(interactionKey) { mutableStateOf<Int?>(null) }
    var pendingMinutes by remember(interactionKey) { mutableStateOf<Int?>(null) }
    var pendingSawBusy by remember(interactionKey) { mutableStateOf(false) }
    var pendingFeedback by remember(interactionKey) { mutableStateOf<String?>(null) }
    var changeSource by remember(interactionKey) { mutableStateOf(DialChangeSource.NONE) }
    var settleRevision by remember(interactionKey) { mutableIntStateOf(0) }
    var keyboardFocused by remember(interactionKey) { mutableStateOf(false) }
    var gestureActive by remember(interactionKey) { mutableStateOf(false) }
    val adjusting = gestureActive || previewMinutes != null || pendingMinutes != null

    fun resetToAuthoritative() {
        dialValue = valueMinutes.toFloat()
        previewMinutes = null
        pendingMinutes = null
        pendingSawBusy = false
        pendingFeedback = null
        changeSource = DialChangeSource.NONE
        gestureActive = false
        settleRevision++
    }

    fun publishValue(value: Float, source: DialChangeSource, emitTick: Boolean = true) {
        val bounded = clampDialValue(value, dialMinimum, dialMaximum)
        val before = snappedDialMinutes(dialValue, dialMinimum, dialMaximum)
        val after = snappedDialMinutes(bounded, dialMinimum, dialMaximum)
        dialValue = bounded
        previewMinutes = after
        changeSource = source
        if (emitTick && before != after) latestTick()
    }

    fun submit(minutes: Int, key: String) {
        val bounded = minutes.coerceIn(dialMinimum, dialMaximum)
        dialValue = bounded.toFloat()
        previewMinutes = bounded
        if (bounded == latestValueMinutes) {
            resetToAuthoritative()
            return
        }
        if (latestEnabled && latestCommit(bounded, key)) {
            pendingMinutes = bounded
            pendingSawBusy = busy
            pendingFeedback = feedbackMessage
            changeSource = DialChangeSource.NONE
        } else {
            resetToAuthoritative()
        }
    }

    LaunchedEffect(valueMinutes, interactionKey, pendingMinutes) {
        val pending = pendingMinutes
        if (pending == null) {
            dialValue = valueMinutes.toFloat()
            previewMinutes = null
        } else if (valueMinutes == pending && !busy) {
            resetToAuthoritative()
        }
    }
    LaunchedEffect(busy, feedbackMessage, pendingMinutes) {
        if (pendingMinutes != null) {
            if (busy) pendingSawBusy = true
            val feedbackChanged = feedbackMessage != null && feedbackMessage != pendingFeedback
            if (!busy && (pendingSawBusy || feedbackChanged) && valueMinutes != pendingMinutes) {
                resetToAuthoritative()
            }
        }
    }
    LaunchedEffect(pendingMinutes) {
        if (pendingMinutes != null) {
            delay(DIAL_PENDING_TIMEOUT_MILLIS)
            if (pendingMinutes != null && valueMinutes != pendingMinutes) resetToAuthoritative()
        }
    }
    LaunchedEffect(dialEnabled, interactionKey) {
        if (!dialEnabled && pendingMinutes == null) resetToAuthoritative()
    }
    LaunchedEffect(adjusting) {
        latestAdjustingChange(adjusting)
    }
    DisposableEffect(Unit) {
        onDispose { latestAdjustingChange(false) }
    }
    LaunchedEffect(settleRevision, changeSource) {
        val revision = settleRevision
        if (changeSource in setOf(DialChangeSource.WHEEL, DialChangeSource.KEYBOARD, DialChangeSource.ACCESSIBILITY)) {
            delay(DIAL_SETTLE_MILLIS)
            if (revision == settleRevision && latestEnabled) {
                submit(snappedDialMinutes(dialValue, dialMinimum, dialMaximum), latestInteractionKey)
            }
        }
    }
    suspend fun settlePointerGesture(release: ReleasedDialGesture) {
        val motionScale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
        val start = dialValue
        val projected = if (motionScale == 0f) start else projectedDialValue(
            currentMinutes = start,
            horizontalVelocityPixelsPerSecond = release.velocityX,
            pixelsPerMinute = pixelsPerMinute,
            minimum = dialMinimum,
            maximum = dialMaximum,
        )
        val target = snappedDialMinutes(projected, dialMinimum, dialMaximum).toFloat()
        if (motionScale == 0f || abs(target - start) < .001f) {
            publishValue(target, DialChangeSource.POINTER)
        } else {
            val durationNanos = (220_000_000L * motionScale.coerceAtLeast(.01f)).toLong()
            val firstFrame = withFrameNanos { it }
            var elapsed: Long
            do {
                val frame = withFrameNanos { it }
                elapsed = frame - firstFrame
                val eased = dialEaseOutQuart(elapsed.toFloat() / durationNanos)
                publishValue(start + (target - start) * eased, DialChangeSource.POINTER)
            } while (elapsed < durationNanos)
            publishValue(target, DialChangeSource.POINTER)
        }
        if (latestEnabled && release.interactionKey == latestInteractionKey) {
            submit(target.roundToInt(), release.interactionKey)
        } else {
            resetToAuthoritative()
        }
    }

    val interactionModifier = Modifier
        .heightIn(min = 48.dp)
        .semantics {
            contentDescription = "전체 목표 시간 조절"
            stateDescription = "${snappedDialMinutes(dialValue, dialMinimum, dialMaximum)}분"
            progressBarRangeInfo = ProgressBarRangeInfo(
                current = dialValue,
                range = dialMinimum.toFloat()..dialMaximum.toFloat(),
                steps = (dialMaximum - dialMinimum - 1).coerceAtLeast(0),
            )
            if (!dialEnabled) disabled()
            setProgress { requested ->
                if (!latestEnabled || pendingMinutes != null) return@setProgress false
                publishValue(requested, DialChangeSource.ACCESSIBILITY)
                settleRevision++
                true
            }
        }
        .onFocusChanged { state ->
            if (keyboardFocused && !state.isFocused && changeSource == DialChangeSource.KEYBOARD) {
                resetToAuthoritative()
            }
            keyboardFocused = state.isFocused
        }
        .onKeyEvent { event ->
            if (!latestEnabled || pendingMinutes != null || event.type != KeyEventType.KeyDown) return@onKeyEvent false
            val direction = when (event.key) {
                Key.DirectionRight, Key.DirectionUp -> 1
                Key.DirectionLeft, Key.DirectionDown -> -1
                else -> return@onKeyEvent false
            }
            publishValue(dialValue + direction, DialChangeSource.KEYBOARD)
            settleRevision++
            true
        }
        .focusable(dialEnabled)
        .pointerInput(dialEnabled, interactionKey, pendingMinutes, pixelsPerMinute) {
            if (!dialEnabled || pendingMinutes != null) return@pointerInput
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type != PointerEventType.Scroll) continue
                    val change = event.changes.firstOrNull() ?: continue
                    val scroll = change.scrollDelta
                    val dominant = if (abs(scroll.x) > abs(scroll.y)) scroll.x else scroll.y
                    if (dominant != 0f) {
                        publishValue(
                            dialValueAfterScroll(dialValue, dominant, dialMinimum, dialMaximum),
                            DialChangeSource.WHEEL,
                        )
                        settleRevision++
                        change.consume()
                    }
                }
            }
        }
        .pointerInput(dialEnabled, interactionKey, dialMinimum, dialMaximum, pendingMinutes) {
            if (!dialEnabled || pendingMinutes != null) return@pointerInput
            while (currentCoroutineContext().isActive) {
                val release = try {
                    awaitPointerEventScope {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        gestureActive = true
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        val startValue = dialValue
                        val startKey = interactionKey
                        var total = Offset.Zero
                        var horizontal = false
                        var released = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change: PointerInputChange = event.changes.firstOrNull { it.id == down.id }
                                ?: break
                            tracker.addPosition(change.uptimeMillis, change.position)
                            if (change.changedToUpIgnoreConsumed()) {
                                // Android ACTION_CANCEL is surfaced as a pre-consumed synthetic up.
                                // Only an unconsumed up is a deliberate release that may save.
                                released = horizontal && !change.isConsumed
                                break
                            }
                            if (change.isConsumed) break
                            val delta = change.positionChange()
                            if (!horizontal) {
                                total += delta
                                if (total.getDistance() >= viewConfiguration.touchSlop) {
                                    if (abs(total.x) <= abs(total.y) * 1.15f) break
                                    horizontal = true
                                    change.consume()
                                    publishValue(
                                        dialValueAfterDrag(
                                            startValue,
                                            total.x - viewConfiguration.touchSlop * if (total.x < 0) -1 else 1,
                                            pixelsPerMinute,
                                            dialMinimum,
                                            dialMaximum,
                                        ),
                                        DialChangeSource.POINTER,
                                    )
                                }
                            } else {
                                change.consume()
                                publishValue(
                                    dialValueAfterDrag(
                                        dialValue,
                                        delta.x,
                                        pixelsPerMinute,
                                        dialMinimum,
                                        dialMaximum,
                                    ),
                                    DialChangeSource.POINTER,
                                )
                            }
                        }
                        if (released) ReleasedDialGesture(tracker.calculateVelocity().x, startKey) else null
                    }
                } catch (cancelled: CancellationException) {
                    // A key change or disposal cancels the gesture without turning it into a save.
                    throw cancelled
                }
                if (release == null) {
                    if (pendingMinutes == null) resetToAuthoritative()
                } else {
                    settlePointerGesture(release)
                }
            }
        }

    Column(modifier.height(height).fillMaxWidth().then(interactionModifier)) {
        content(previewMinutes, faceHeight)
        TimeDialRuler(
            value = dialValue,
            minimum = dialMinimum,
            maximum = dialMaximum,
            compact = compact,
            enabled = dialEnabled,
            modifier = Modifier.fillMaxWidth().height(rulerHeight),
        )
    }
}

@Composable
private fun TimeDialRuler(
    value: Float,
    minimum: Int,
    maximum: Int,
    compact: Boolean,
    enabled: Boolean,
    modifier: Modifier,
) {
    val minute = snappedDialMinutes(value, minimum, maximum)
    val spacing = with(LocalDensity.current) { (if (compact) 14.dp else 18.dp).toPx() }
    Box(modifier) {
        Canvas(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            val center = size.width / 2f
            val fractional = value - value.roundToInt()
            val radius = (size.width / spacing / 2f).toInt() + 2
            for (offset in -radius..radius) {
                val tickMinute = value.roundToInt() + offset
                if (tickMinute !in minimum..maximum) continue
                val x = center + (offset - fractional) * spacing
                val major = tickMinute % 5 == 0
                drawLine(
                    color = if (enabled) InkMuted.copy(alpha = if (major) .7f else .34f) else Rule,
                    start = Offset(x, 1.dp.toPx()),
                    end = Offset(x, (if (major) 10.dp else 6.dp).toPx()),
                    strokeWidth = (if (major) 1.5.dp else 1.dp).toPx(),
                    cap = StrokeCap.Round,
                )
            }
            drawLine(
                color = if (enabled) Clay else InkMuted.copy(alpha = .45f),
                start = Offset(center, 0f),
                end = Offset(center, (if (compact) 14.dp else 17.dp).toPx()),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        if (!compact) {
            Text(
                text = "전체 목표 ${minute}분",
                modifier = Modifier.fillMaxWidth().padding(top = 15.dp),
                color = if (enabled) InkMuted else InkMuted.copy(alpha = .55f),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}
