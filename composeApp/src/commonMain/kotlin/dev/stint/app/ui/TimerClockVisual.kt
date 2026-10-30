package dev.stint.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stint.shared.TimerClockReading
import dev.stint.shared.TimerClockStyle
import dev.stint.shared.formatClockTime
import kotlinx.coroutines.currentCoroutineContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private val SandGold = Color(0xFFAE7B49)
private val SandDark = Color(0xFF755B3B)

internal typealias ClockTimeContent = @Composable BoxScope.(TextStyle) -> Unit

@Composable
internal fun TimerClockVisual(
    style: TimerClockStyle,
    reading: TimerClockReading,
    running: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 260.dp,
    sizingReferenceSeconds: Int = reading.valueSeconds,
    readoutLabel: String? = null,
    timeContent: ClockTimeContent? = null,
) {
    val label = readoutLabel ?: if (reading.countingUp) "경과 시간" else "남은 시간"
    val renderedReading = animatedClockReading(reading, running)
    val sizingReferenceTime = formatTimerClockReadout(sizingReferenceSeconds, reading.countingUp)
    val caption = buildString {
        append(if (style == TimerClockStyle.ORBIT) "한 바퀴 60분" else "모래 전체 60분")
        if (reading.extraHours > 0) append(" · +${reading.extraHours}시간")
    }
    when (style) {
        TimerClockStyle.ORBIT -> OrbitClock(
            reading = renderedReading,
            semanticReading = reading,
            label = label,
            caption = caption,
            sizingReferenceTime = sizingReferenceTime,
            timeContent = timeContent,
            modifier = modifier.fillMaxWidth().height(height),
        )
        TimerClockStyle.HOURGLASS -> HourglassClock(
            reading = renderedReading,
            semanticReading = reading,
            running = running,
            label = label,
            caption = caption,
            sizingReferenceTime = sizingReferenceTime,
            timeContent = timeContent,
            modifier = modifier.fillMaxWidth().height(height),
        )
    }
}

@Composable
private fun OrbitClock(
    reading: TimerClockReading,
    semanticReading: TimerClockReading,
    label: String,
    caption: String,
    sizingReferenceTime: String,
    timeContent: ClockTimeContent?,
    modifier: Modifier,
) {
    val time = formatTimerClockReadout(reading.valueSeconds, reading.countingUp)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val diameter = minOf(maxWidth, maxHeight)
            Box(Modifier.size(diameter).align(Alignment.Center)) {
                Canvas(
                    Modifier.fillMaxSize().semantics {
                        contentDescription = "$label $time, 60분 중 ${(semanticReading.ringFraction * 60).roundToInt()}분"
                    },
                ) {
                    val center = this.center
                    val radius = min(size.width, size.height) * .43f
                    val trackWidth = min(size.width, size.height) * .034f
                    drawCircle(PaperDeep, radius, center, style = Stroke(trackWidth))
                    repeat(60) { index ->
                        val major = index % 5 == 0
                        val angle = -PI / 2.0 + index * 2.0 * PI / 60.0
                        val outside = radius - trackWidth * 1.65f
                        val inside = outside - if (major) trackWidth * .7f else trackWidth * .28f
                        drawLine(
                            color = if (major) InkMuted.copy(alpha = .72f) else Rule.copy(alpha = .62f),
                            start = Offset(
                                center.x + cos(angle).toFloat() * outside,
                                center.y + sin(angle).toFloat() * outside,
                            ),
                            end = Offset(
                                center.x + cos(angle).toFloat() * inside,
                                center.y + sin(angle).toFloat() * inside,
                            ),
                            strokeWidth = if (major) 1.5.dp.toPx() else 1.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                    val fraction = reading.ringFraction.coerceIn(0f, 1f)
                    if (fraction > 0f) {
                        drawArc(
                            color = Forest,
                            startAngle = -90f,
                            sweepAngle = fraction * 360f,
                            useCenter = false,
                            topLeft = Offset(center.x - radius, center.y - radius),
                            size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
                            style = Stroke(trackWidth, cap = StrokeCap.Round),
                        )
                        val endAngle = -PI / 2.0 + fraction * 2.0 * PI
                        drawCircle(
                            Clay,
                            radius = trackWidth * .46f,
                            center = Offset(
                                center.x + cos(endAngle).toFloat() * radius,
                                center.y + sin(endAngle).toFloat() * radius,
                            ),
                        )
                    }
                }
                BoxWithConstraints(
                    Modifier.align(Alignment.Center).width(diameter * .66f).height(diameter * .56f),
                ) {
                    val fontScale = LocalDensity.current.fontScale
                    val detailHeight = (32f * fontScale + 8f).dp
                    val centerHeight = maxHeight
                    val showDetails = centerHeight >= detailHeight + 20.dp
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (showDetails) {
                            Text(label, color = InkMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            Spacer(Modifier.height(5.dp))
                        }
                        FittedClockTime(
                            time,
                            Modifier.fillMaxWidth().height(
                                if (showDetails) (centerHeight - detailHeight).coerceAtLeast(1.dp) else centerHeight,
                            ),
                            sizingReferenceTime = sizingReferenceTime,
                            content = timeContent,
                        )
                        if (showDetails) {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                if (time.count { it == ':' } > 1) "시 : 분 : 초" else "분 : 초",
                                color = InkMuted,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        ClockCaption(caption)
    }
}

@Composable
private fun HourglassClock(
    reading: TimerClockReading,
    semanticReading: TimerClockReading,
    running: Boolean,
    label: String,
    caption: String,
    sizingReferenceTime: String,
    timeContent: ClockTimeContent?,
    modifier: Modifier,
) {
    val time = formatTimerClockReadout(reading.valueSeconds, reading.countingUp)
    val phase = animatedSandPhase(
        running && reading.upperSandFraction > 0f && reading.lowerSandFraction < 1f,
    )
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // A wide but shallow tabletop pane must remain side-by-side; stacking the readout
            // would consume most of the available height before the glass is drawn.
            val horizontal = maxWidth >= 360.dp
            if (horizontal) {
                val timeHeight = (maxHeight - 40.dp).coerceIn(28.dp, 70.dp)
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    HourglassDrawing(
                        reading = reading,
                        semanticReading = semanticReading,
                        phase = phase,
                        modifier = Modifier.weight(1.05f).fillMaxHeight(),
                    )
                    ClockReadout(
                        label,
                        time,
                        Modifier.weight(.95f).fillMaxHeight().padding(horizontal = 8.dp),
                        alignStart = true,
                        timeHeight = timeHeight,
                        sizingReferenceTime = sizingReferenceTime,
                        timeContent = timeContent,
                    )
                }
            } else {
                val readoutHeight = minOf(88.dp, maxOf(56.dp, maxHeight * .36f))
                val timeHeight = readoutHeight - 32.dp
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    ClockReadout(
                        label,
                        time,
                        Modifier.fillMaxWidth().height(readoutHeight),
                        alignStart = false,
                        timeHeight = timeHeight,
                        sizingReferenceTime = sizingReferenceTime,
                        timeContent = timeContent,
                    )
                    HourglassDrawing(
                        reading = reading,
                        semanticReading = semanticReading,
                        phase = phase,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
        }
        ClockCaption(caption)
    }
}

@Composable
private fun ClockReadout(
    label: String,
    time: String,
    modifier: Modifier,
    alignStart: Boolean,
    timeHeight: Dp,
    sizingReferenceTime: String,
    timeContent: ClockTimeContent?,
) {
    BoxWithConstraints(modifier) {
        val fontScale = LocalDensity.current.fontScale
        val detailHeight = (32f * fontScale).dp
        val showDetails = maxHeight >= detailHeight + 24.dp
        val effectiveTimeHeight = if (showDetails) {
            minOf(timeHeight, (maxHeight - detailHeight).coerceAtLeast(1.dp))
        } else {
            maxHeight
        }
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = if (alignStart) Alignment.Start else Alignment.CenterHorizontally,
        ) {
            if (showDetails) Text(label, color = InkMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            FittedClockTime(
                time,
                Modifier.fillMaxWidth().height(effectiveTimeHeight),
                if (alignStart) TextAlign.Start else TextAlign.Center,
                sizingReferenceTime,
                timeContent,
            )
            if (showDetails) {
                Text(
                    if (time.count { it == ':' } > 1) "시 : 분 : 초" else "분 : 초",
                    color = InkMuted,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ClockCaption(caption: String) {
    Text(
        caption,
        modifier = Modifier.fillMaxWidth(),
        color = InkMuted,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun FittedClockTime(
    time: String,
    modifier: Modifier,
    textAlign: TextAlign = TextAlign.Center,
    sizingReferenceTime: String = time,
    content: ClockTimeContent? = null,
) {
    val measurer = rememberTextMeasurer()
    val base = MaterialTheme.typography.displayLarge
    BoxWithConstraints(modifier, contentAlignment = if (textAlign == TextAlign.Start) Alignment.CenterStart else Alignment.Center) {
        val availableWidth = constraints.maxWidth.toFloat()
        val availableHeight = constraints.maxHeight.toFloat()
        val sizingSamples = clockTimeSizingSamples(time, sizingReferenceTime)
        val sizingKey = sizingSamples.first()
        val fontSize = remember(sizingKey, availableWidth, availableHeight, base, measurer) {
            var low = 1f
            var high = 76f
            repeat(10) {
                val middle = (low + high) / 2f
                val style = base.copy(
                    fontSize = middle.sp,
                    lineHeight = (middle * 1.08f).sp,
                    fontFeatureSettings = "tnum",
                )
                val fits = if (sizingReferenceTime.count { it == ':' } == 1) {
                    val minuteDigits = countdownMinuteSlotDigits(time, sizingReferenceTime)
                    minuteSecondClockWidth(measurer, style, minuteDigits) <= availableWidth &&
                        measurer.measure(AnnotatedString("88"), style, maxLines = 1).size.height <= availableHeight
                } else {
                    sizingSamples.all { sample ->
                        val measured = measurer.measure(
                            text = AnnotatedString(sample),
                            style = style,
                            maxLines = 1,
                        )
                        measured.size.width <= availableWidth && measured.size.height <= availableHeight
                    }
                }
                if (fits) low = middle else high = middle
            }
            low.sp
        }
        val fittedStyle = base.copy(
            fontSize = fontSize,
            lineHeight = fontSize * 1.08f,
            fontFeatureSettings = "tnum",
        )
        if (content == null && time.count { it == ':' } == 1 && textAlign == TextAlign.Center) {
            val parts = time.split(':', limit = 2)
            MinuteSecondClockLayout(
                minuteSlotDigits = countdownMinuteSlotDigits(time),
                textStyle = fittedStyle,
                modifier = Modifier.fillMaxSize(),
                minuteContent = {
                    Text(
                        text = parts[0], modifier = Modifier.fillMaxWidth(), color = Ink, style = fittedStyle,
                        maxLines = 1, textAlign = TextAlign.End,
                    )
                },
                secondContent = {
                    Text(
                        text = parts[1], modifier = Modifier.fillMaxWidth(), color = Ink, style = fittedStyle,
                        maxLines = 1, textAlign = TextAlign.Start,
                    )
                },
            )
        } else if (content == null) {
            Text(
                time,
                modifier = Modifier.fillMaxWidth(),
                color = Ink,
                style = fittedStyle,
                maxLines = 1,
                textAlign = textAlign,
            )
        } else {
            content(fittedStyle)
        }
    }
}

/** Centers visible digits; the separate phase reference controls font fitting only. */
@Composable
internal fun MinuteSecondClockLayout(
    minuteSlotDigits: Int,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    minuteModifier: Modifier = Modifier,
    secondModifier: Modifier = Modifier,
    minuteContent: @Composable BoxScope.() -> Unit,
    secondContent: @Composable BoxScope.() -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val safeMinuteDigits = minuteSlotDigits.coerceAtLeast(2)
    val widths = remember(safeMinuteDigits, textStyle, measurer) {
        widestRepeatedDigitWidth(measurer, textStyle, safeMinuteDigits) to
            widestRepeatedDigitWidth(measurer, textStyle, 2)
    }
    Layout(
        modifier = modifier,
        content = {
            Box(minuteModifier, contentAlignment = Alignment.CenterEnd, content = minuteContent)
            Text(":", color = Ink, style = textStyle, maxLines = 1)
            Box(secondModifier, contentAlignment = Alignment.CenterStart, content = secondContent)
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val minute = measurables[0].measure(loose.copy(minWidth = widths.first, maxWidth = widths.first))
        val colon = measurables[1].measure(loose)
        val second = measurables[2].measure(loose.copy(minWidth = widths.second, maxWidth = widths.second))
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val groupWidth = minute.width + colon.width + second.width
        val groupX = ((width - groupWidth) / 2).coerceAtLeast(0)
        layout(width, height) {
            minute.placeRelative(groupX, (height - minute.height) / 2)
            colon.placeRelative(groupX + minute.width, (height - colon.height) / 2)
            second.placeRelative(groupX + minute.width + colon.width, (height - second.height) / 2)
        }
    }
}

private fun widestRepeatedDigitWidth(
    measurer: androidx.compose.ui.text.TextMeasurer,
    style: TextStyle,
    digits: Int,
): Int = (0..9).maxOf { digit ->
    measurer.measure(AnnotatedString(digit.toString().repeat(digits)), style, maxLines = 1).size.width
}

private fun minuteSecondClockWidth(
    measurer: androidx.compose.ui.text.TextMeasurer,
    style: TextStyle,
    minuteDigits: Int,
): Int = widestRepeatedDigitWidth(measurer, style, minuteDigits) +
    measurer.measure(AnnotatedString(":"), style, maxLines = 1).size.width +
    widestRepeatedDigitWidth(measurer, style, 2)

internal fun countdownMinuteSlotDigits(time: String, sizingReferenceTime: String = time): Int =
    maxOf(time.indexOf(':'), sizingReferenceTime.indexOf(':'), 2)

/**
 * Stable samples for every numeral in the current time format. Measuring these rather than the
 * current second keeps the readout size fixed even when a font does not support tabular figures.
 */
internal fun clockTimeSizingSamples(time: String, sizingReferenceTime: String = time): List<String> {
    val sizingFormat = if (sizingReferenceTime.length >= time.length) sizingReferenceTime else time
    val hasHours = sizingFormat.count { it == ':' } >= 2
    val hourDigits = if (hasHours) sizingFormat.indexOf(':').coerceAtLeast(1) else 0
    val minuteDigits = if (hasHours) 0 else sizingFormat.indexOf(':').coerceAtLeast(2)
    return (0..9).map { digit ->
        val value = digit.toString()
        if (hasHours) value.repeat(hourDigits) + ":" + value.repeat(2) + ":" + value.repeat(2)
        else value.repeat(minuteDigits) + ":" + value.repeat(2)
    }
}

/** Countdown editing uses total minutes; stopwatch keeps its elapsed-hour representation. */
internal fun formatTimerClockReadout(seconds: Int, countingUp: Boolean): String {
    val safe = seconds.coerceAtLeast(0)
    if (countingUp) return formatClockTime(safe)
    val minutes = safe / 60
    return "${minutes.toString().padStart(2, '0')}:${(safe % 60).toString().padStart(2, '0')}"
}

private data class ClockFractions(
    val ring: Float,
    val upperSand: Float,
    val lowerSand: Float,
)

@Composable
private fun animatedClockReading(reading: TimerClockReading, running: Boolean): TimerClockReading {
    val target = ClockFractions(
        ring = reading.ringFraction.coerceIn(0f, 1f),
        upperSand = reading.upperSandFraction.coerceIn(0f, 1f),
        lowerSand = reading.lowerSandFraction.coerceIn(0f, 1f),
    )
    var rendered by remember { mutableStateOf(target) }
    var renderedHour by remember { mutableStateOf(reading.extraHours) }
    LaunchedEffect(target, running, reading.extraHours) {
        val durationScale = currentCoroutineContext()[MotionDurationScale]
        val hourChanged = renderedHour != reading.extraHours
        renderedHour = reading.extraHours
        if (!running || hourChanged || durationScale?.scaleFactor == 0f) {
            rendered = target
            return@LaunchedEffect
        }
        val start = rendered
        var previousFrame = withFrameNanos { it }
        var elapsed = 0L
        val baseDurationNanos = 230_000_000L
        val minimumFrameNanos = 1_000_000_000L / 30L
        while (elapsed < baseDurationNanos) {
            val now = withFrameNanos { it }
            val frameNanos = now - previousFrame
            val scaleFactor = durationScale?.scaleFactor ?: 1f
            if (scaleFactor == 0f) {
                rendered = target
                return@LaunchedEffect
            }
            if (frameNanos < minimumFrameNanos) continue
            previousFrame = now
            elapsed += (frameNanos / scaleFactor).toLong().coerceAtMost(baseDurationNanos)
            val progress = (elapsed.toFloat() / baseDurationNanos).coerceIn(0f, 1f)
            rendered = ClockFractions(
                ring = lerp(start.ring, target.ring, progress),
                upperSand = lerp(start.upperSand, target.upperSand, progress),
                lowerSand = lerp(start.lowerSand, target.lowerSand, progress),
            )
        }
        rendered = target
    }
    return reading.copy(
        ringFraction = rendered.ring,
        upperSandFraction = rendered.upperSand,
        lowerSandFraction = rendered.lowerSand,
    )
}

private fun lerp(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction.coerceIn(0f, 1f)

@Composable
private fun animatedSandPhase(enabled: Boolean): Float {
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(enabled) {
        if (!enabled) {
            phase = 0f
            return@LaunchedEffect
        }
        val durationScale = currentCoroutineContext()[MotionDurationScale]
        var previousUpdate = withFrameNanos { it }
        val minimumFrameNanos = 1_000_000_000L / 30L
        while (true) {
            val now = withFrameNanos { it }
            val scaleFactor = durationScale?.scaleFactor ?: 1f
            if (scaleFactor == 0f) {
                if (phase != 0f) phase = 0f
                previousUpdate = now
                continue
            }
            if (now - previousUpdate < minimumFrameNanos) continue
            val elapsedSeconds = ((now - previousUpdate).coerceAtMost(2_000_000_000L) / 1_000_000_000f) / scaleFactor
            previousUpdate = now
            phase = (phase + elapsedSeconds * .82f) % 1f
        }
    }
    return phase
}

@Composable
private fun HourglassDrawing(
    reading: TimerClockReading,
    semanticReading: TimerClockReading,
    phase: Float,
    modifier: Modifier,
) {
    val upperKey = (reading.upperSandFraction.coerceIn(0f, 1f) * 10_000).roundToInt()
    val lowerKey = (reading.lowerSandFraction.coerceIn(0f, 1f) * 10_000).roundToInt()
    val upperSurface = remember(upperKey) { upperSandSurface(upperKey / 10_000f) }
    val lowerSurface = remember(lowerKey) { lowerSandSurface(lowerKey / 10_000f) }
    Canvas(
        modifier.semantics {
            contentDescription = "곡선 유리 모래시계, ${formatClockTime(semanticReading.valueSeconds)}, 윗모래 ${(semanticReading.upperSandFraction * 60).roundToInt()}분, 아랫모래 ${(semanticReading.lowerSandFraction * 60).roundToInt()}분"
        },
    ) {
        drawHourglass(reading, upperSurface, lowerSurface, phase)
    }
}

private fun DrawScope.drawHourglass(
    reading: TimerClockReading,
    upperSurface: Double,
    lowerSurface: LowerSandSurface,
    phase: Float,
) {
    val modelLeft = 70f
    val modelTop = 40f
    val modelWidth = 180f
    val modelHeight = 240f
    val scale = min(size.width / modelWidth, size.height / modelHeight)
    val left = (size.width - modelWidth * scale) / 2f
    val top = (size.height - modelHeight * scale) / 2f
    fun x(value: Double) = left + (value.toFloat() - modelLeft) * scale
    fun y(value: Double) = top + (value.toFloat() - modelTop) * scale

    val upper = chamberPath(true, ::x, ::y)
    val lower = chamberPath(false, ::x, ::y)
    val outline = hourglassOutline(::x, ::y)
    drawPath(outline, PaperRaised)

    val upperLevel = 58.0 + upperSurface * SAND_CHAMBER_HEIGHT
    clipPath(upper) {
        clipRect(top = y(upperLevel), bottom = y(152.0)) {
            drawRect(SandGold, topLeft = Offset(x(93.0), y(upperLevel)), size = androidx.compose.ui.geometry.Size(x(227.0) - x(93.0), y(152.0) - y(upperLevel)))
            drawSandTexture(::x, ::y, 58.0, 152.0)
        }
    }

    val lowerSand = lowerSandPath(lowerSurface, ::x, ::y)
    if (!lowerSand.isEmpty) {
        clipPath(lower) {
            clipPath(lowerSand) {
                drawPath(lowerSand, SandGold)
                drawSandTexture(::x, ::y, 168.0, 262.0)
            }
        }
    }

    drawPath(outline, InkMuted.copy(alpha = .64f), style = Stroke(1.6f * scale, cap = StrokeCap.Round))
    drawLine(PaperRaised, Offset(x(104.0), y(66.0)), Offset(x(99.0), y(91.0)), 2.4f * scale, StrokeCap.Round)
    drawRoundRect(
        Ink,
        Offset(x(81.0), y(52.0)),
        androidx.compose.ui.geometry.Size(x(239.0) - x(81.0), y(58.0) - y(52.0)),
        CornerRadius(3f * scale, 3f * scale),
    )
    drawRoundRect(
        Ink,
        Offset(x(81.0), y(262.0)),
        androidx.compose.ui.geometry.Size(x(239.0) - x(81.0), y(268.0) - y(262.0)),
        CornerRadius(3f * scale, 3f * scale),
    )

    val flowing = reading.upperSandFraction > 0f && reading.lowerSandFraction < 1f && phase > 0f
    if (flowing) {
        val landing = maxOf(169.0, 168.0 + (1.0 - lowerSurface.height) * SAND_CHAMBER_HEIGHT)
        repeat(9) { index ->
            val t = (phase + index / 9f) % 1f
            val grainY = 153.0 + (landing - 153.0) * t * t
            val grainX = 160.0 + sin(index * 2.4) * 1.2
            drawCircle(
                color = if (index % 3 == 0) SandDark else SandGold,
                radius = (0.7f + index % 3 * .16f) * scale,
                center = Offset(x(grainX), y(grainY)),
            )
        }
    }
}

private fun DrawScope.drawSandTexture(
    x: (Double) -> Float,
    y: (Double) -> Float,
    top: Double,
    bottom: Double,
) {
    var row = 0
    var modelY = top + 4.0
    while (modelY < bottom) {
        var modelX = 98.0 + (row % 2) * 5.0
        while (modelX < 222.0) {
            drawCircle(
                color = if ((row + (modelX / 10).toInt()) % 3 == 0) PaperRaised.copy(alpha = .34f) else SandDark.copy(alpha = .18f),
                radius = .55.dp.toPx(),
                center = Offset(x(modelX), y(modelY)),
            )
            modelX += 11.0
        }
        modelY += 9.0
        row++
    }
}

private fun chamberPath(
    upper: Boolean,
    x: (Double) -> Float,
    y: (Double) -> Float,
): Path {
    val startY = if (upper) 58.0 else 168.0
    val width: (Double) -> Double = if (upper) ::upperChamberHalfWidth else ::lowerChamberHalfWidth
    return Path().apply {
        repeat(73) { index ->
            val u = index / 72.0
            val px = x(160.0 + width(u))
            val py = y(startY + u * SAND_CHAMBER_HEIGHT)
            if (index == 0) moveTo(px, py) else lineTo(px, py)
        }
        for (index in 72 downTo 0) {
            val u = index / 72.0
            lineTo(x(160.0 - width(u)), y(startY + u * SAND_CHAMBER_HEIGHT))
        }
        close()
    }
}

private fun hourglassOutline(x: (Double) -> Float, y: (Double) -> Float): Path {
    val points = mutableListOf<Pair<Double, Double>>()
    repeat(73) { index ->
        val u = index / 72.0
        points += 160.0 + upperChamberHalfWidth(u) to 58.0 + u * SAND_CHAMBER_HEIGHT
    }
    points += 160.0 + SAND_NECK_RADIUS to 168.0
    for (index in 1..72) {
        val u = index / 72.0
        points += 160.0 + lowerChamberHalfWidth(u) to 168.0 + u * SAND_CHAMBER_HEIGHT
    }
    for (index in 72 downTo 0) {
        val u = index / 72.0
        points += 160.0 - lowerChamberHalfWidth(u) to 168.0 + u * SAND_CHAMBER_HEIGHT
    }
    points += 160.0 - SAND_NECK_RADIUS to 152.0
    for (index in 71 downTo 0) {
        val u = index / 72.0
        points += 160.0 - upperChamberHalfWidth(u) to 58.0 + u * SAND_CHAMBER_HEIGHT
    }
    return pathOf(points, x, y)
}

private fun lowerSandPath(surface: LowerSandSurface, x: (Double) -> Float, y: (Double) -> Float): Path {
    if (surface.height <= 0.0) return Path()
    if (surface.height >= 1.0 && surface.curvature == 0.0) return chamberPath(false, x, y)
    val points = mutableListOf<Pair<Double, Double>>()
    repeat(73) { index ->
        val modelX = -SAND_OUTER_RADIUS + index / 72.0 * SAND_OUTER_RADIUS * 2.0
        val surfaceY = 168.0 +
            (1.0 - surface.height + surface.curvature * (modelX / SAND_OUTER_RADIUS) * (modelX / SAND_OUTER_RADIUS)) *
            SAND_CHAMBER_HEIGHT
        points += 160.0 + modelX to surfaceY
    }
    points += 160.0 + SAND_OUTER_RADIUS to 262.0
    points += 160.0 - SAND_OUTER_RADIUS to 262.0
    return pathOf(points, x, y)
}

private fun pathOf(points: List<Pair<Double, Double>>, x: (Double) -> Float, y: (Double) -> Float): Path =
    Path().apply {
        points.forEachIndexed { index, point ->
            if (index == 0) moveTo(x(point.first), y(point.second)) else lineTo(x(point.first), y(point.second))
        }
        close()
    }
