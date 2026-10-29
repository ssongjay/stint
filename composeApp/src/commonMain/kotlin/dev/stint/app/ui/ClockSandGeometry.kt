package dev.stint.app.ui

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal const val SAND_OUTER_RADIUS = 67.0
internal const val SAND_NECK_RADIUS = 5.0
internal const val SAND_CHAMBER_HEIGHT = 94.0
internal const val SAND_CHAMBER_AREA = SAND_OUTER_RADIUS + SAND_NECK_RADIUS

internal data class LowerSandSurface(val height: Double, val curvature: Double)

internal fun upperChamberHalfWidth(verticalFraction: Double): Double {
    val u = verticalFraction.coerceIn(0.0, 1.0)
    val cosine = cos(PI * u / 2.0)
    return SAND_NECK_RADIUS + (SAND_OUTER_RADIUS - SAND_NECK_RADIUS) * cosine * cosine
}

internal fun lowerChamberHalfWidth(verticalFraction: Double): Double =
    upperChamberHalfWidth(1.0 - verticalFraction)

/** Surface coordinate in [0, 1], measured from the top of the upper chamber. */
internal fun upperSandSurface(fraction: Float): Double {
    val amount = fraction.coerceIn(0f, 1f).toDouble()
    if (amount <= 0.0) return 1.0
    if (amount >= 1.0) return 0.0
    val targetEmptyArea = (1.0 - amount) * SAND_CHAMBER_AREA
    var low = 0.0
    var high = 1.0
    repeat(28) {
        val middle = (low + high) / 2.0
        val area = (SAND_OUTER_RADIUS + SAND_NECK_RADIUS) * middle +
            (SAND_OUTER_RADIUS - SAND_NECK_RADIUS) * sin(PI * middle) / PI
        if (area < targetEmptyArea) low = middle else high = middle
    }
    return (low + high) / 2.0
}

/**
 * Solves a shallow parabolic pile with the requested area. The integration starts at the
 * occupied interval so very thin deposits stay accurate instead of disappearing between bins.
 */
internal fun lowerSandSurface(fraction: Float, mound: Double = 0.26): LowerSandSurface {
    val amount = fraction.coerceIn(0f, 1f).toDouble()
    if (amount <= 0.0) return LowerSandSurface(0.0, 0.0)
    if (amount >= 1.0) return LowerSandSurface(1.0, 0.0)
    val curvature = mound.coerceIn(0.0, 0.5) * sin(PI * amount)
    val target = amount * SAND_CHAMBER_AREA
    var low = 0.0
    var high = 1.0 + curvature
    repeat(28) {
        val middle = (low + high) / 2.0
        if (lowerSandArea(middle, curvature) < target) low = middle else high = middle
    }
    return LowerSandSurface((low + high) / 2.0, curvature)
}

internal fun lowerSandArea(height: Double, curvature: Double, samples: Int = 256): Double {
    require(samples > 0)
    val start = max(0.0, 1.0 - height)
    val length = 1.0 - start
    if (length <= 0.0) return 0.0
    var sum = 0.0
    repeat(samples) { index ->
        val u = start + (index + 0.5) / samples * length
        val pileHalfWidth = if (curvature > 1e-8) {
            SAND_OUTER_RADIUS * sqrt(max(0.0, (u - 1.0 + height) / curvature))
        } else if (u >= 1.0 - height) {
            SAND_OUTER_RADIUS
        } else {
            0.0
        }
        sum += 2.0 * min(lowerChamberHalfWidth(u), pileHalfWidth)
    }
    return sum / samples * length
}
