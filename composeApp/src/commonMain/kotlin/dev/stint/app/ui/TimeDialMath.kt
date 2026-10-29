package dev.stint.app.ui

import kotlin.math.roundToInt

internal const val TIME_DIAL_FLING_PROJECTION_SECONDS = .12f

internal fun clampDialValue(value: Float, minimum: Int, maximum: Int): Float =
    value.coerceIn(minimum.toFloat(), maximum.toFloat())

/** Dragging left advances the dial; dragging right rewinds it. */
internal fun dialValueAfterDrag(
    startMinutes: Float,
    dragPixels: Float,
    pixelsPerMinute: Float,
    minimum: Int,
    maximum: Int,
): Float = clampDialValue(
    startMinutes - dragPixels / pixelsPerMinute.coerceAtLeast(1f),
    minimum,
    maximum,
)

/** Compose reports wheel/trackpad scroll in logical scroll units, not screen pixels. */
internal fun dialValueAfterScroll(
    startMinutes: Float,
    scrollUnits: Float,
    minimum: Int,
    maximum: Int,
): Float = clampDialValue(startMinutes - scrollUnits, minimum, maximum)

internal fun projectedDialValue(
    currentMinutes: Float,
    horizontalVelocityPixelsPerSecond: Float,
    pixelsPerMinute: Float,
    minimum: Int,
    maximum: Int,
): Float = clampDialValue(
    currentMinutes - horizontalVelocityPixelsPerSecond * TIME_DIAL_FLING_PROJECTION_SECONDS /
        pixelsPerMinute.coerceAtLeast(1f),
    minimum,
    maximum,
)

internal fun snappedDialMinutes(value: Float, minimum: Int, maximum: Int): Int =
    value.roundToInt().coerceIn(minimum, maximum)

internal fun dialEaseOutQuart(fraction: Float): Float {
    val tail = 1f - fraction.coerceIn(0f, 1f)
    return 1f - tail * tail * tail * tail
}
