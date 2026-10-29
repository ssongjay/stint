package dev.stint.app.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class TimeWheelField { MINUTES, SECONDS }

private data class ReleasedWheelGesture(val velocityY: Float, val interactionKey: String)
private const val WHEEL_SETTLE_MILLIS = 240L

@Composable
internal fun VerticalTimeWheelReadout(
    totalSeconds: Int,
    minimumSeconds: Int,
    maximumSeconds: Int,
    enabled: Boolean,
    interactionKey: String,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    onPreview: (Int) -> Unit,
    onSettled: (Int, String) -> Unit,
    onCancel: () -> Unit,
    onGestureActive: (Boolean) -> Unit,
    onTick: () -> Unit,
) {
    val values = splitTimeWheelSeconds(totalSeconds)
    val minuteMaximum = (maximumSeconds.coerceAtLeast(0) / 60).coerceAtLeast(0)
    val secondRange = timeWheelSecondRange(totalSeconds, minimumSeconds, maximumSeconds)
    val (minuteWeight, secondWeight) = timeWheelFieldWeights(maximumSeconds)
    var activeField by remember(interactionKey) { mutableStateOf<TimeWheelField?>(null) }
    LaunchedEffect(enabled, interactionKey) {
        if (!enabled) {
            activeField = null
            onGestureActive(false)
        }
    }
    val textMeasurer = rememberTextMeasurer()
    BoxWithConstraints(modifier.fillMaxSize()) {
        val availableWidth = constraints.maxWidth.toFloat()
        val availableHeight = constraints.maxHeight.toFloat()
        val minuteDigits = minuteWeight.toInt()
        val fittedStyle = remember(
            availableWidth, availableHeight, minuteDigits, minuteWeight, secondWeight, textStyle, textMeasurer,
        ) {
            var low = 1f
            var high = textStyle.fontSize.value.coerceAtLeast(1f)
            repeat(10) {
                val middle = (low + high) / 2f
                val candidate = textStyle.copy(
                    fontSize = middle.sp,
                    lineHeight = (middle * 1.08f).sp,
                )
                val colon = textMeasurer.measure(AnnotatedString(":"), candidate, maxLines = 1)
                val fieldWidth = (availableWidth - colon.size.width).coerceAtLeast(1f)
                val minuteWidth = fieldWidth * minuteWeight / (minuteWeight + secondWeight)
                val secondWidth = fieldWidth - minuteWidth
                val minuteFits = (0..9).all { digit ->
                    textMeasurer.measure(
                        AnnotatedString(digit.toString().repeat(minuteDigits)), candidate, maxLines = 1,
                    ).size.width <= minuteWidth
                }
                val secondFits = (0..9).all { digit ->
                    textMeasurer.measure(
                        AnnotatedString(digit.toString().repeat(2)), candidate, maxLines = 1,
                    ).size.width <= secondWidth
                }
                val heightFits = textMeasurer.measure(AnnotatedString("88"), candidate, maxLines = 1)
                    .size.height <= availableHeight
                if (minuteFits && secondFits && heightFits) low = middle else high = middle
            }
            textStyle.copy(fontSize = low.sp, lineHeight = (low * 1.08f).sp)
        }
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        VerticalTimeWheelField(
            field = TimeWheelField.MINUTES,
            value = values.minutes,
            minimum = 0,
            maximum = minuteMaximum,
            enabled = enabled && minuteMaximum > 0 &&
                (activeField == null || activeField == TimeWheelField.MINUTES),
            interactionKey = interactionKey,
            textStyle = fittedStyle,
            modifier = Modifier.weight(minuteWeight).fillMaxHeight(),
            onPreview = { minute ->
                onPreview(combineTimeWheelSeconds(minute, values.seconds, minimumSeconds, maximumSeconds))
            },
            onSettled = { minute, key ->
                onSettled(combineTimeWheelSeconds(minute, values.seconds, minimumSeconds, maximumSeconds), key)
            },
            onCancel = onCancel,
            onGestureActive = { active ->
                activeField = if (active) TimeWheelField.MINUTES
                else activeField.takeUnless { it == TimeWheelField.MINUTES }
                onGestureActive(activeField != null)
            },
            onTick = onTick,
        )
        Text(
            text = ":",
            color = Ink,
            style = fittedStyle,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        VerticalTimeWheelField(
            field = TimeWheelField.SECONDS,
            value = values.seconds,
            minimum = secondRange.first,
            maximum = secondRange.last,
            enabled = enabled && secondRange.first < secondRange.last &&
                (activeField == null || activeField == TimeWheelField.SECONDS),
            interactionKey = interactionKey,
            textStyle = fittedStyle,
            modifier = Modifier.weight(secondWeight).fillMaxHeight(),
            onPreview = { second ->
                onPreview(combineTimeWheelSeconds(values.minutes, second, minimumSeconds, maximumSeconds))
            },
            onSettled = { second, key ->
                onSettled(combineTimeWheelSeconds(values.minutes, second, minimumSeconds, maximumSeconds), key)
            },
            onCancel = onCancel,
            onGestureActive = { active ->
                activeField = if (active) TimeWheelField.SECONDS
                else activeField.takeUnless { it == TimeWheelField.SECONDS }
                onGestureActive(activeField != null)
            },
            onTick = onTick,
        )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun VerticalTimeWheelField(
    field: TimeWheelField,
    value: Int,
    minimum: Int,
    maximum: Int,
    enabled: Boolean,
    interactionKey: String,
    textStyle: TextStyle,
    modifier: Modifier,
    onPreview: (Int) -> Unit,
    onSettled: (Int, String) -> Unit,
    onCancel: () -> Unit,
    onGestureActive: (Boolean) -> Unit,
    onTick: () -> Unit,
) {
    val latestEnabled by rememberUpdatedState(enabled)
    val latestKey by rememberUpdatedState(interactionKey)
    val latestPreview by rememberUpdatedState(onPreview)
    val latestSettled by rememberUpdatedState(onSettled)
    val latestCancel by rememberUpdatedState(onCancel)
    val latestGestureActive by rememberUpdatedState(onGestureActive)
    val latestTick by rememberUpdatedState(onTick)
    val density = LocalDensity.current
    val stepPixels = with(density) { textStyle.lineHeight.toPx().coerceAtLeast(18f) }
    var wheelValue by remember(interactionKey, field) { mutableFloatStateOf(value.toFloat()) }
    var gestureActive by remember(interactionKey, field) { mutableStateOf(false) }
    var deferredSettle by remember(interactionKey, field) { mutableStateOf<Int?>(null) }
    var settleRevision by remember(interactionKey, field) { mutableIntStateOf(0) }

    LaunchedEffect(value, interactionKey, gestureActive) {
        if (!gestureActive) wheelValue = value.toFloat()
    }
    LaunchedEffect(deferredSettle, settleRevision, interactionKey) {
        val pending = deferredSettle ?: return@LaunchedEffect
        val revision = settleRevision
        delay(WHEEL_SETTLE_MILLIS)
        if (revision == settleRevision && latestEnabled) {
            deferredSettle = null
            latestSettled(pending, latestKey)
        }
    }

    fun publish(next: Float, emitTick: Boolean = true) {
        val bounded = clampWheelValue(next, minimum, maximum)
        val before = snappedWheelValue(wheelValue, minimum, maximum)
        val after = snappedWheelValue(bounded, minimum, maximum)
        wheelValue = bounded
        latestPreview(after)
        if (emitTick && before != after) latestTick()
    }

    suspend fun settle(release: ReleasedWheelGesture) {
        deferredSettle = null
        settleRevision++
        val motionScale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
        val start = wheelValue
        val projected = if (motionScale == 0f) start else projectedWheelValue(
            currentValue = start,
            verticalVelocityPixelsPerSecond = release.velocityY,
            pixelsPerStep = stepPixels,
            minimum = minimum,
            maximum = maximum,
        )
        val target = snappedWheelValue(projected, minimum, maximum).toFloat()
        if (motionScale == 0f || abs(target - start) < .001f) {
            publish(target)
        } else {
            val durationNanos = (220_000_000L * motionScale.coerceAtLeast(.01f)).toLong()
            val firstFrame = withFrameNanos { it }
            var elapsed: Long
            do {
                val frame = withFrameNanos { it }
                elapsed = frame - firstFrame
                publish(start + (target - start) * wheelEaseOutQuart(elapsed.toFloat() / durationNanos))
            } while (elapsed < durationNanos)
            publish(target)
        }
        gestureActive = false
        latestGestureActive(false)
        if (latestEnabled && release.interactionKey == latestKey) {
            latestSettled(target.roundToInt(), release.interactionKey)
        } else {
            wheelValue = value.toFloat()
            latestPreview(value)
        }
    }

    val label = if (field == TimeWheelField.MINUTES) "분" else "초"
    val shown = snappedWheelValue(wheelValue, minimum, maximum)
    val fractional = wheelValue - wheelValue.roundToInt()
    val fieldModifier = modifier
        .clipToBounds()
        .semantics {
            contentDescription = "$label 조절"
            stateDescription = "$shown$label"
            progressBarRangeInfo = ProgressBarRangeInfo(
                current = wheelValue,
                range = minimum.toFloat()..maximum.toFloat(),
                steps = (maximum - minimum - 1).coerceAtLeast(0),
            )
            if (!enabled) disabled()
            setProgress { requested ->
                if (!latestEnabled) return@setProgress false
                val next = requested.roundToInt().coerceIn(minimum, maximum)
                publish(next.toFloat())
                deferredSettle = next
                settleRevision++
                true
            }
        }
        .onKeyEvent { event ->
            if (!latestEnabled || event.type != KeyEventType.KeyDown) return@onKeyEvent false
            val delta = when (event.key) {
                Key.DirectionUp -> 1
                Key.DirectionDown -> -1
                else -> return@onKeyEvent false
            }
            val next = (shown + delta).coerceIn(minimum, maximum)
            publish(next.toFloat())
            deferredSettle = next
            settleRevision++
            true
        }
        .focusable(enabled)
        .pointerInput(enabled, interactionKey, minimum, maximum) {
            if (!enabled) return@pointerInput
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type != PointerEventType.Scroll) continue
                    val change = event.changes.firstOrNull() ?: continue
                    val scroll = change.scrollDelta
                    val dominant = if (abs(scroll.y) >= abs(scroll.x)) scroll.y else scroll.x
                    if (dominant != 0f) {
                        val next = wheelValueAfterScroll(wheelValue, dominant, minimum, maximum)
                        publish(next)
                        deferredSettle = snappedWheelValue(next, minimum, maximum)
                        settleRevision++
                        change.consume()
                    }
                }
            }
        }
        .pointerInput(enabled, interactionKey, minimum, maximum, stepPixels) {
            if (!enabled) return@pointerInput
            while (currentCoroutineContext().isActive) {
                val release = try {
                    awaitPointerEventScope {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        deferredSettle = null
                        settleRevision++
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        val start = wheelValue
                        val key = interactionKey
                        var totalX = 0f
                        var totalY = 0f
                        var vertical = false
                        var released = false
                        latestGestureActive(true)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            tracker.addPosition(change.uptimeMillis, change.position)
                            if (change.changedToUpIgnoreConsumed()) {
                                released = vertical && !change.isConsumed
                                break
                            }
                            if (change.isConsumed) break
                            val delta = change.positionChange()
                            totalX += delta.x
                            totalY += delta.y
                            if (!vertical) {
                                if (kotlin.math.hypot(totalX, totalY) < viewConfiguration.touchSlop) continue
                                if (abs(totalY) <= abs(totalX) * 1.15f) break
                                vertical = true
                                gestureActive = true
                                change.consume()
                                publish(wheelValueAfterVerticalDrag(
                                    start,
                                    totalY - viewConfiguration.touchSlop * if (totalY < 0) -1 else 1,
                                    stepPixels,
                                    minimum,
                                    maximum,
                                ))
                            } else {
                                change.consume()
                                publish(wheelValueAfterVerticalDrag(
                                    wheelValue, delta.y, stepPixels, minimum, maximum,
                                ))
                            }
                        }
                        if (released) ReleasedWheelGesture(tracker.calculateVelocity().y, key) else null
                    }
                } catch (cancelled: CancellationException) {
                    latestGestureActive(false)
                    throw cancelled
                }
                if (release == null) {
                    deferredSettle = null
                    settleRevision++
                    gestureActive = false
                    latestGestureActive(false)
                    wheelValue = value.toFloat()
                    latestCancel()
                } else {
                    settle(release)
                }
            }
        }

    Box(fieldModifier, contentAlignment = Alignment.Center) {
        for (offset in -1..1) {
            if (offset != 0 && !gestureActive) continue
            val candidate = wheelValue.roundToInt() + offset
            if (candidate !in minimum..maximum) continue
            val alpha = if (offset == 0) 1f else .22f
            val style = if (offset == 0) textStyle else textStyle.copy(
                fontSize = textStyle.fontSize * .62f,
                lineHeight = textStyle.lineHeight * .65f,
            )
            Text(
                text = if (field == TimeWheelField.SECONDS) candidate.toString().padStart(2, '0')
                else candidate.toString().padStart(2, '0'),
                modifier = Modifier.fillMaxWidth().graphicsLayer {
                    translationY = (offset - fractional) * stepPixels
                    this.alpha = alpha
                },
                color = Ink,
                style = style,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}
