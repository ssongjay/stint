package dev.stint.app.ui

import kotlin.math.roundToInt

internal const val TIME_WHEEL_FLING_PROJECTION_SECONDS = .12f

internal data class TimeWheelValues(val minutes: Int, val seconds: Int) {
    val totalSeconds: Int get() = minutes * 60 + seconds
}

internal fun timeWheelFieldWeights(maximumSeconds: Int): Pair<Float, Float> =
    (maximumSeconds.coerceAtLeast(0) / 60).toString().length.coerceAtLeast(2).toFloat() to 2f

internal fun timeWheelSecondRange(
    totalSeconds: Int,
    minimumSeconds: Int,
    maximumSeconds: Int,
): IntRange {
    val minute = splitTimeWheelSeconds(totalSeconds.coerceIn(minimumSeconds, maximumSeconds)).minutes
    val minimum = if (minute == minimumSeconds / 60) minimumSeconds % 60 else 0
    val maximum = if (minute == maximumSeconds / 60) maximumSeconds % 60 else 59
    return minimum.coerceAtMost(maximum)..maximum
}

internal fun splitTimeWheelSeconds(totalSeconds: Int): TimeWheelValues {
    val safe = totalSeconds.coerceAtLeast(0)
    return TimeWheelValues(minutes = safe / 60, seconds = safe % 60)
}

internal fun combineTimeWheelSeconds(
    minutes: Int,
    seconds: Int,
    minimumSeconds: Int,
    maximumSeconds: Int,
): Int = (minutes.coerceAtLeast(0) * 60L + seconds.coerceIn(0, 59))
    .coerceIn(minimumSeconds.toLong(), maximumSeconds.toLong())
    .toInt()

internal fun clampWheelValue(value: Float, minimum: Int, maximum: Int): Float =
    value.coerceIn(minimum.toFloat(), maximum.toFloat())

/** Dragging up advances a field; dragging down rewinds it. */
internal fun wheelValueAfterVerticalDrag(
    startValue: Float,
    dragPixelsY: Float,
    pixelsPerStep: Float,
    minimum: Int,
    maximum: Int,
): Float = clampWheelValue(
    startValue - dragPixelsY / pixelsPerStep.coerceAtLeast(1f),
    minimum,
    maximum,
)

/** Compose reports wheel/trackpad movement in logical scroll units, not screen pixels. */
internal fun wheelValueAfterScroll(
    startValue: Float,
    scrollUnits: Float,
    minimum: Int,
    maximum: Int,
): Float = clampWheelValue(startValue - scrollUnits, minimum, maximum)

internal fun projectedWheelValue(
    currentValue: Float,
    verticalVelocityPixelsPerSecond: Float,
    pixelsPerStep: Float,
    minimum: Int,
    maximum: Int,
): Float = clampWheelValue(
    currentValue - verticalVelocityPixelsPerSecond * TIME_WHEEL_FLING_PROJECTION_SECONDS /
        pixelsPerStep.coerceAtLeast(1f),
    minimum,
    maximum,
)

internal fun snappedWheelValue(value: Float, minimum: Int, maximum: Int): Int =
    value.roundToInt().coerceIn(minimum, maximum)

internal fun wheelEaseOutQuart(fraction: Float): Float {
    val tail = 1f - fraction.coerceIn(0f, 1f)
    return 1f - tail * tail * tail * tail
}
