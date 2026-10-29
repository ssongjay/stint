package dev.stint.app.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClockSandGeometryTest {
    private val fractions = listOf(0f, .001f, .01f, .1f, .3f, .5f, .7f, .99f, .999f, 1f)

    @Test
    fun upperAndLowerAmountsMatchIndependentAreaIntegration() {
        for (fraction in fractions) {
            val upperSurface = upperSandSurface(fraction)
            val upperArea = integrate(upperSurface, 1.0) { upperChamberHalfWidth(it) * 2.0 } /
                SAND_CHAMBER_AREA
            assertEquals(fraction.toDouble(), upperArea, .0001, "upper $fraction")

            for (mound in listOf(.12, .26, .35)) {
                val lowerSurface = lowerSandSurface(1f - fraction, mound)
                val lowerArea = independentLowerSandArea(lowerSurface) / SAND_CHAMBER_AREA
                assertEquals((1f - fraction).toDouble(), lowerArea, .001, "lower $fraction at $mound")
                assertEquals(1.0, upperArea + lowerArea, .0011, "conserved $fraction at $mound")
                assertFalse(lowerSurface.height.isNaN())
                assertFalse(lowerSurface.curvature.isNaN())
            }
        }
    }

    @Test
    fun solverHasExactEmptyAndFullBoundaries() {
        assertEquals(1.0, upperSandSurface(0f))
        assertEquals(0.0, upperSandSurface(1f))
        assertEquals(LowerSandSurface(0.0, 0.0), lowerSandSurface(0f))
        assertEquals(LowerSandSurface(1.0, 0.0), lowerSandSurface(1f))
    }

    @Test
    fun denserIntegrationDoesNotMoveTheSolvedAmountMaterially() {
        for (fraction in fractions.drop(1).dropLast(1)) {
            val surface = lowerSandSurface(fraction)
            val coarse = lowerSandArea(surface.height, surface.curvature, samples = 256)
            val dense = lowerSandArea(surface.height, surface.curvature, samples = 4096)
            assertTrue(abs(coarse - dense) / SAND_CHAMBER_AREA < .001)
        }
    }

    @Test
    fun chamberAreaMatchesAnalyticWidthIntegral() {
        val integrated = integrate(0.0, 1.0) { upperChamberHalfWidth(it) * 2.0 }
        assertEquals(SAND_CHAMBER_AREA, integrated, .00001)
        val analytic = SAND_OUTER_RADIUS + SAND_NECK_RADIUS +
            (SAND_OUTER_RADIUS - SAND_NECK_RADIUS) * sin(PI) / PI
        assertEquals(SAND_CHAMBER_AREA, analytic, .00001)
    }

    private fun integrate(start: Double, end: Double, samples: Int = 8192, value: (Double) -> Double): Double {
        var sum = 0.0
        repeat(samples) { index ->
            sum += value(start + (index + .5) / samples * (end - start))
        }
        return sum / samples * (end - start)
    }

    /** Cross-checks the production vertical integration along the independent horizontal axis. */
    private fun independentLowerSandArea(surface: LowerSandSurface, samples: Int = 8192): Double {
        val width = SAND_OUTER_RADIUS * 2.0
        return integrate(-SAND_OUTER_RADIUS, SAND_OUTER_RADIUS, samples) { x ->
            val wall = if (abs(x) <= SAND_NECK_RADIUS) {
                0.0
            } else {
                2.0 / PI * asin(
                    sqrt(
                        ((abs(x) - SAND_NECK_RADIUS) /
                            (SAND_OUTER_RADIUS - SAND_NECK_RADIUS)).coerceIn(0.0, 1.0),
                    ),
                )
            }
            val pile = 1.0 - surface.height +
                surface.curvature * (x / SAND_OUTER_RADIUS) * (x / SAND_OUTER_RADIUS)
            max(0.0, 1.0 - max(0.0, max(wall, pile)))
        }
    }
}
