package dev.stint.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class TimeWheelField { MINUTES, SECONDS }

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
    activatedField: TimeWheelField? = null,
    onActivateField: (TimeWheelField) -> Unit = {},
    onDeactivateField: (TimeWheelField) -> Unit = {},
    onPreview: (Int) -> Unit,
    onSettled: (Int, String) -> Unit,
    onCancel: () -> Unit,
    onGestureActive: (Boolean) -> Unit,
    onTick: () -> Unit,
) {
    // 한 번에 한 필드만 제스처를 소유해 분·초가 동시에 움직이지 않게 한다.
    val values = splitTimeWheelSeconds(totalSeconds)
    val minuteMaximum = (maximumSeconds.coerceAtLeast(0) / 60).coerceAtLeast(0)
    val secondRange = timeWheelSecondRange(totalSeconds, minimumSeconds, maximumSeconds)
    val minuteDigits = values.minutes.toString().length.coerceAtLeast(2)
    var gestureField by remember(interactionKey) { mutableStateOf<TimeWheelField?>(null) }
    LaunchedEffect(enabled, interactionKey) {
        if (!enabled) {
            gestureField = null
            onGestureActive(false)
        }
    }
    MinuteSecondClockLayout(
        minuteSlotDigits = minuteDigits,
        textStyle = textStyle,
        modifier = modifier.fillMaxSize(),
        minuteModifier = Modifier.fillMaxHeight(),
        secondModifier = Modifier.fillMaxHeight(),
        minuteContent = {
            VerticalTimeWheelField(
                field = TimeWheelField.MINUTES,
                value = values.minutes,
                minimum = 0,
                maximum = minuteMaximum,
                enabled = enabled && minuteMaximum > 0 &&
                    (gestureField == null || gestureField == TimeWheelField.MINUTES),
                activated = activatedField == TimeWheelField.MINUTES,
                onActivate = { onActivateField(TimeWheelField.MINUTES) },
                onDeactivate = { onDeactivateField(TimeWheelField.MINUTES) },
                interactionKey = interactionKey,
                textStyle = textStyle,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxSize(),
                onPreview = { minute ->
                    onPreview(combineTimeWheelSeconds(minute, values.seconds, minimumSeconds, maximumSeconds))
                },
                onSettled = { minute, key ->
                    onSettled(combineTimeWheelSeconds(minute, values.seconds, minimumSeconds, maximumSeconds), key)
                },
                onCancel = onCancel,
                onGestureActive = { active ->
                    gestureField = if (active) TimeWheelField.MINUTES
                    else gestureField.takeUnless { it == TimeWheelField.MINUTES }
                    onGestureActive(gestureField != null)
                },
                onTick = onTick,
            )
        },
        secondContent = {
            VerticalTimeWheelField(
                field = TimeWheelField.SECONDS,
                value = values.seconds,
                minimum = secondRange.first,
                maximum = secondRange.last,
                enabled = enabled && secondRange.first < secondRange.last &&
                    (gestureField == null || gestureField == TimeWheelField.SECONDS),
                activated = activatedField == TimeWheelField.SECONDS,
                onActivate = { onActivateField(TimeWheelField.SECONDS) },
                onDeactivate = { onDeactivateField(TimeWheelField.SECONDS) },
                interactionKey = interactionKey,
                textStyle = textStyle,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxSize(),
                onPreview = { second ->
                    onPreview(combineTimeWheelSeconds(values.minutes, second, minimumSeconds, maximumSeconds))
                },
                onSettled = { second, key ->
                    onSettled(combineTimeWheelSeconds(values.minutes, second, minimumSeconds, maximumSeconds), key)
                },
                onCancel = onCancel,
                onGestureActive = { active ->
                    gestureField = if (active) TimeWheelField.SECONDS
                    else gestureField.takeUnless { it == TimeWheelField.SECONDS }
                    onGestureActive(gestureField != null)
                },
                onTick = onTick,
            )
        },
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun VerticalTimeWheelField(
    field: TimeWheelField,
    value: Int,
    minimum: Int,
    maximum: Int,
    enabled: Boolean,
    activated: Boolean,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
    interactionKey: String,
    textStyle: TextStyle,
    textAlign: TextAlign,
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
    val focusRequester = remember(field) { FocusRequester() }
    val interactionSource = remember(field) { MutableInteractionSource() }
    val stepPixels = with(density) { textStyle.lineHeight.toPx().coerceAtLeast(18f) }
    var wheelValue by remember(interactionKey, field) { mutableFloatStateOf(value.toFloat()) }
    var gestureActive by remember(interactionKey, field) { mutableStateOf(false) }
    var deferredSettle by remember(interactionKey, field) { mutableStateOf<Int?>(null) }
    var settleRevision by remember(interactionKey, field) { mutableIntStateOf(0) }

    LaunchedEffect(activated, enabled) {
        if (activated && enabled) focusRequester.requestFocus()
    }

    LaunchedEffect(value, interactionKey, gestureActive) {
        if (!gestureActive) wheelValue = value.toFloat()
    }
    LaunchedEffect(deferredSettle, settleRevision, interactionKey) {
        // 키보드·휠 입력의 연속 눈금은 240ms 동안 모아 마지막 값만 서버에 보낸다.
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
        // 손을 뗀 속도를 짧게 투영하고 정수 눈금에 맞춘 뒤 한 번만 저장한다.
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
        .drawBehind {
            if (activated) {
                val markerWidth = 14.dp.toPx()
                val y = size.height - 5.dp.toPx()
                drawLine(
                    color = Clay,
                    start = androidx.compose.ui.geometry.Offset((size.width - markerWidth) / 2f, y),
                    end = androidx.compose.ui.geometry.Offset((size.width + markerWidth) / 2f, y),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
        .semantics {
            contentDescription = "$label 조절"
            stateDescription = if (activated) "$shown$label, 선택됨" else "$shown$label"
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
        .focusRequester(focusRequester)
        .focusProperties { canFocus = enabled }
        .onFocusChanged { focusState ->
            if (focusState.isFocused) onActivate()
            else if (enabled && activated) onDeactivate()
        }
        .clickable(
            enabled = enabled,
            interactionSource = interactionSource,
            indication = null,
        ) {
            onActivate()
            focusRequester.requestFocus()
        }
        .pointerInput(enabled, activated, interactionKey, minimum, maximum) {
            // 클릭·포커스로 활성화되기 전에는 이벤트를 소비하지 않아 부모 화면이 스크롤된다.
            if (!enabled || !activated) return@pointerInput
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
        .pointerInput(enabled, activated, interactionKey, minimum, maximum, stepPixels) {
            if (!enabled || !activated) return@pointerInput
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
                                latestGestureActive(true)
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
                textAlign = textAlign,
            )
        }
    }
}
