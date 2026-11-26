package dev.stint.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

internal data class PlanningDialSegment(
    /** Clockwise turns measured from twelve o'clock. Values may cross the zero boundary. */
    val from: Float,
    val to: Float,
    val color: Color,
)

internal fun planningDialSegmentAt(
    position: Offset,
    size: Size,
    segments: List<PlanningDialSegment>,
    innerRadiusFraction: Float = .285f,
    outerRadiusFraction: Float = .405f,
): Int? {
    if (size.minDimension <= 0f) return null
    val center = Offset(size.width / 2f, size.height / 2f)
    val relative = position - center
    val radius = relative.getDistance()
    val base = size.minDimension
    if (radius !in base * innerRadiusFraction..base * outerRadiusFraction) return null
    val turn = normalizePlanningTurn(
        ((atan2(relative.y, relative.x) + PI / 2.0) / (2.0 * PI)).toFloat(),
    )
    return segments.indexOfFirst { planningTurnContains(it.from, it.to, turn) }
        .takeIf { it >= 0 }
}

internal fun planningTurnContains(from: Float, to: Float, turn: Float): Boolean {
    val start = normalizePlanningTurn(from)
    val sweep = positivePlanningSweep(from, to)
    val distance = normalizePlanningTurn(turn - start)
    return distance < sweep || sweep >= 1f
}

internal fun positivePlanningSweep(from: Float, to: Float): Float {
    val raw = to - from
    val normalized = normalizePlanningTurn(raw)
    return if (normalized == 0f && raw != 0f) 1f else normalized
}

private fun normalizePlanningTurn(turn: Float): Float = ((turn % 1f) + 1f) % 1f

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun PlanningDial(
    segments: List<PlanningDialSegment>,
    isMinuteDial: Boolean,
    centerCaption: String,
    centerTime: String,
    centerTitle: String,
    highlightedIndex: Int?,
    selectedIndex: Int?,
    onHighlight: (Int?) -> Unit,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    startHour: Int = 0,
) {
    val currentHighlight by rememberUpdatedState(onHighlight)
    val currentSelect by rememberUpdatedState(onSelect)
    val lifts = remember(segments.size) { List(segments.size) { Animatable(0f) } }
    LaunchedEffect(highlightedIndex, segments.size) {
        coroutineScope {
            lifts.forEachIndexed { index, lift ->
                launch {
                    lift.animateTo(
                        if (index == highlightedIndex) 1f else 0f,
                        tween(durationMillis = 170, easing = FastOutSlowInEasing),
                    )
                }
            }
        }
    }

    BoxWithConstraints(modifier.aspectRatio(1f)) {
        val diameter = minOf(maxWidth, maxHeight)
        val textMeasurer = rememberTextMeasurer()
        val tickStyle = TextStyle(
            color = InkMuted,
            fontSize = if (isMinuteDial) 11.sp else 12.sp,
            fontWeight = FontWeight.Medium,
        )
        val semanticDescription = buildString {
            append(if (isMinuteDial) "한 시간 계획" else "12시간 계획")
            append(", ")
            append(centerTime)
            if (centerTitle.isNotBlank()) {
                append(", ")
                append(centerTitle)
            }
        }
        var downPosition: Offset? = null
        var downIndex: Int? = null

        Box(Modifier.width(diameter).aspectRatio(1f).align(Alignment.Center)) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = semanticDescription }
                    .pointerInput(segments) {
                        val touchSlop = viewConfiguration.touchSlop
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull()
                                when (event.type) {
                                    PointerEventType.Exit -> currentHighlight(null)
                                    PointerEventType.Move,
                                    PointerEventType.Enter,
                                    -> if (change?.type == PointerType.Mouse) {
                                        currentHighlight(
                                            planningDialSegmentAt(
                                                change.position,
                                                Size(size.width.toFloat(), size.height.toFloat()),
                                                segments,
                                            ),
                                        )
                                    }
                                    PointerEventType.Press -> if (change != null) {
                                        downPosition = change.position
                                        downIndex = planningDialSegmentAt(
                                            change.position,
                                            Size(size.width.toFloat(), size.height.toFloat()),
                                            segments,
                                        )
                                    }
                                    PointerEventType.Release -> if (change != null) {
                                        val start = downPosition
                                        val released = planningDialSegmentAt(
                                            change.position,
                                            Size(size.width.toFloat(), size.height.toFloat()),
                                            segments,
                                        )
                                        if (start != null &&
                                            (change.position - start).getDistance() <= touchSlop &&
                                            released != null && released == downIndex
                                        ) {
                                            currentSelect(released)
                                        }
                                        downPosition = null
                                        downIndex = null
                                    }
                                    else -> Unit
                                }
                            }
                        }
                    },
            ) {
                val base = size.minDimension
                val center = this.center
                val outerRadius = base * .395f
                val ringWidth = base * .115f
                val liftDistance = 7.dp.toPx()
                val gapDegrees = if (isMinuteDial) 1.6f else 2.1f

                val drawSegment: (Int, PlanningDialSegment) -> Unit = drawSegment@{ index, segment ->
                    val sweep = positivePlanningSweep(segment.from, segment.to) * 360f
                    if (sweep <= gapDegrees) return@drawSegment
                    val startAngle = segment.from * 360f - 90f + gapDegrees / 2f
                    val visibleSweep = sweep - gapDegrees
                    val midAngle = (startAngle + visibleSweep / 2f) * PI / 180.0
                    val lift = lifts.getOrNull(index)?.value ?: 0f
                    val offset = Offset(
                        cos(midAngle).toFloat() * liftDistance * lift,
                        sin(midAngle).toFloat() * liftDistance * lift,
                    )
                    val topLeft = Offset(
                        center.x - outerRadius + ringWidth / 2f,
                        center.y - outerRadius + ringWidth / 2f,
                    )
                    val arcSize = Size(
                        (outerRadius - ringWidth / 2f) * 2f,
                        (outerRadius - ringWidth / 2f) * 2f,
                    )
                    if (lift > .02f) {
                        translate(offset.x, offset.y + 3.dp.toPx() * lift) {
                            drawArc(
                                color = Ink.copy(alpha = .13f * lift),
                                startAngle = startAngle,
                                sweepAngle = visibleSweep,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(ringWidth + 2.dp.toPx(), cap = StrokeCap.Butt),
                            )
                        }
                    }
                    translate(offset.x, offset.y) {
                        if (index == selectedIndex) {
                            drawArc(
                                color = Ink,
                                startAngle = startAngle,
                                sweepAngle = visibleSweep,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(ringWidth + 2.dp.toPx(), cap = StrokeCap.Butt),
                            )
                        }
                        drawArc(
                            color = segment.color,
                            startAngle = startAngle,
                            sweepAngle = visibleSweep,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(
                                if (index == selectedIndex) ringWidth - 2.dp.toPx() else ringWidth,
                                cap = StrokeCap.Butt,
                            ),
                        )
                    }
                }
                segments.indices.filterNot { it == highlightedIndex }.forEach { index ->
                    drawSegment(index, segments[index])
                }
                highlightedIndex?.takeIf { it in segments.indices }?.let { index ->
                    drawSegment(index, segments[index])
                }

                val outerTick = outerRadius + 4.dp.toPx()
                val tickCount = if (isMinuteDial) 60 else 12
                repeat(tickCount) { index ->
                    val major = if (isMinuteDial) index % 5 == 0 else true
                    val angle = -PI / 2.0 + index * 2.0 * PI / tickCount
                    val inside = outerTick - if (major) 5.dp.toPx() else 2.dp.toPx()
                    drawLine(
                        color = if (major) InkMuted.copy(alpha = .7f) else Rule,
                        start = Offset(
                            center.x + cos(angle).toFloat() * outerTick,
                            center.y + sin(angle).toFloat() * outerTick,
                        ),
                        end = Offset(
                            center.x + cos(angle).toFloat() * inside,
                            center.y + sin(angle).toFloat() * inside,
                        ),
                        strokeWidth = if (major) 1.2.dp.toPx() else .7.dp.toPx(),
                    )
                }

                val labels = if (isMinuteDial) {
                    listOf(0 to "00", 15 to "15", 30 to "30", 45 to "45")
                } else {
                    (0 until 12).map { it to ((startHour + it) % 24).toString().padStart(2, '0') }
                }
                val labelRadius = minOf(
                    outerTick + if (isMinuteDial) 12.dp.toPx() else 15.dp.toPx(),
                    base / 2f - 7.dp.toPx(),
                )
                labels.forEach { (index, label) ->
                    val fraction = if (isMinuteDial) index / 60f else index / 12f
                    val angle = -PI / 2.0 + fraction * 2.0 * PI
                    val measured = textMeasurer.measure(AnnotatedString(label), tickStyle)
                    drawText(
                        measured,
                        topLeft = Offset(
                            center.x + cos(angle).toFloat() * labelRadius - measured.size.width / 2f,
                            center.y + sin(angle).toFloat() * labelRadius - measured.size.height / 2f,
                        ),
                    )
                }
            }

            val compactCenter = diameter < 170.dp
            val centerTimeStyle = MaterialTheme.typography.headlineMedium.copy(
                fontSize = when {
                    diameter < 140.dp -> 11.sp
                    diameter < 200.dp -> 15.sp
                    else -> 22.sp
                },
                lineHeight = when {
                    diameter < 140.dp -> 14.sp
                    diameter < 200.dp -> 19.sp
                    else -> 28.sp
                },
            )
            Column(
                Modifier.align(Alignment.Center).width(diameter * .68f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!compactCenter) {
                    Text(
                        centerCaption,
                        color = InkMuted,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
                Text(
                    centerTime,
                    color = Ink,
                    style = centerTimeStyle,
                    maxLines = 1,
                )
                if (!compactCenter) {
                    Text(
                        centerTitle,
                        color = InkMuted,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
