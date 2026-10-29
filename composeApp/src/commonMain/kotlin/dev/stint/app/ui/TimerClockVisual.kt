package dev.stint.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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

@Composable
internal fun TimerClockVisual(
    style: TimerClockStyle,
    reading: TimerClockReading,
    running: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 260.dp,
) {
    val label = if (reading.countingUp) "경과 시간" else "남은 시간"
    val caption = buildString {
        append(if (style == TimerClockStyle.ORBIT) "한 바퀴 60분" else "모래 전체 60분")
        if (reading.extraHours > 0) append(" · +${reading.extraHours}시간")
    }
    when (style) {
        TimerClockStyle.ORBIT -> OrbitClock(reading, label, caption, modifier.fillMaxWidth().height(height))
        TimerClockStyle.HOURGLASS -> HourglassClock(
            reading = reading,
            running = running,
            label = label,
            caption = caption,
            modifier = modifier.fillMaxWidth().height(height),
        )
    }
}

@Composable
private fun OrbitClock(
    reading: TimerClockReading,
    label: String,
    caption: String,
    modifier: Modifier,
) {
    val time = formatClockTime(reading.valueSeconds)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val diameter = minOf(maxWidth, maxHeight)
            Box(Modifier.size(diameter).align(Alignment.Center)) {
                Canvas(
                    Modifier.fillMaxSize().semantics {
                        contentDescription = "$label $time, 60분 중 ${(reading.ringFraction * 60).roundToInt()}분"
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
    running: Boolean,
    label: String,
    caption: String,
    modifier: Modifier,
) {
    val time = formatClockTime(reading.valueSeconds)
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
                        reading,
                        phase,
                        Modifier.weight(1.05f).fillMaxHeight(),
                    )
                    ClockReadout(
                        label,
                        time,
                        Modifier.weight(.95f).fillMaxHeight().padding(horizontal = 8.dp),
                        alignStart = true,
                        timeHeight = timeHeight,
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
                    )
                    HourglassDrawing(reading, phase, Modifier.weight(1f).fillMaxWidth())
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
) {
    val measurer = rememberTextMeasurer()
    val base = MaterialTheme.typography.displayLarge
    BoxWithConstraints(modifier, contentAlignment = if (textAlign == TextAlign.Start) Alignment.CenterStart else Alignment.Center) {
        val availableWidth = constraints.maxWidth.toFloat()
        val availableHeight = constraints.maxHeight.toFloat()
        val fontSize = remember(time, availableWidth, availableHeight, base, measurer) {
            var low = 1f
            var high = 76f
            repeat(10) {
                val middle = (low + high) / 2f
                val measured = measurer.measure(
                    text = AnnotatedString(time),
                    style = base.copy(fontSize = middle.sp, lineHeight = (middle * 1.08f).sp),
                    maxLines = 1,
                )
                if (measured.size.width <= availableWidth && measured.size.height <= availableHeight) low = middle else high = middle
            }
            low.sp
        }
        Text(
            time,
            modifier = Modifier.fillMaxWidth(),
            color = Ink,
            style = base.copy(fontSize = fontSize, lineHeight = fontSize * 1.08f),
            maxLines = 1,
            textAlign = textAlign,
        )
    }
}

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
            phase = (phase + elapsedSeconds * 1.35f) % 1f
        }
    }
    return phase
}

@Composable
private fun HourglassDrawing(
    reading: TimerClockReading,
    phase: Float,
    modifier: Modifier,
) {
    val upperKey = (reading.upperSandFraction.coerceIn(0f, 1f) * 10_000).roundToInt()
    val lowerKey = (reading.lowerSandFraction.coerceIn(0f, 1f) * 10_000).roundToInt()
    val upperSurface = remember(upperKey) { upperSandSurface(upperKey / 10_000f) }
    val lowerSurface = remember(lowerKey) { lowerSandSurface(lowerKey / 10_000f) }
    Canvas(
        modifier.semantics {
            contentDescription = "곡선 유리 모래시계, ${formatClockTime(reading.valueSeconds)}, 윗모래 ${(reading.upperSandFraction * 60).roundToInt()}분, 아랫모래 ${(reading.lowerSandFraction * 60).roundToInt()}분"
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
