package dev.stint.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimeDialMathTest {
    @Test fun upwardDragAdvancesAndDownwardDragRewindsOneField() {
        assertEquals(27f, wheelValueAfterVerticalDrag(25f, -36f, 18f, 0, 240))
        assertEquals(23f, wheelValueAfterVerticalDrag(25f, 36f, 18f, 0, 240))
    }

    @Test fun minuteAndSecondFieldsFormAClampedTotalWithoutCarry() {
        assertEquals(TimeWheelValues(120, 7), splitTimeWheelSeconds(7_207))
        assertEquals(7_207, combineTimeWheelSeconds(120, 7, 1, 14_400))
        assertEquals(12 * 60 + 35, combineTimeWheelSeconds(2, 0, 12 * 60 + 35, 14_400))
        assertEquals(14_400, combineTimeWheelSeconds(240, 59, 1, 14_400))
        assertEquals(119, combineTimeWheelSeconds(1, 65, 1, 14_400))
    }

    @Test fun wheelAndTrackpadUnitsStayDensityIndependent() {
        assertEquals(24f, wheelValueAfterScroll(25f, 1f, 0, 240))
        assertEquals(26f, wheelValueAfterScroll(25f, -1f, 0, 240))
        val first = wheelValueAfterScroll(25f, .25f, 0, 240)
        assertEquals(24.75f, first)
        assertEquals(24.5f, wheelValueAfterScroll(first, .25f, 0, 240))
    }

    @Test fun fieldWidthsReserveStableSpaceForThreeDigitTotalMinutes() {
        assertEquals(2f to 2f, timeWheelFieldWeights(60 * 60))
        assertEquals(3f to 2f, timeWheelFieldWeights(240 * 60))
        assertEquals(3f to 2f, timeWheelFieldWeights(100 * 60))
    }

    @Test fun secondRangeNeverOffersAnImpossibleBoundaryValue() {
        assertEquals(1..59, timeWheelSecondRange(totalSeconds = 1, minimumSeconds = 1, maximumSeconds = 14_400))
        assertEquals(0..0, timeWheelSecondRange(totalSeconds = 14_400, minimumSeconds = 1, maximumSeconds = 14_400))
        assertEquals(0..48, timeWheelSecondRange(totalSeconds = 14_388, minimumSeconds = 1, maximumSeconds = 14_388))
        assertEquals(35..59, timeWheelSecondRange(totalSeconds = 12 * 60 + 35,
            minimumSeconds = 12 * 60 + 35, maximumSeconds = 14_400))
        assertEquals(0..59, timeWheelSecondRange(totalSeconds = 25 * 60 + 17,
            minimumSeconds = 1, maximumSeconds = 14_400))
    }

    @Test fun releaseProjectionIsBoundedAndSnapsExactly() {
        val projected = projectedWheelValue(25.2f, -300f, 18f, 0, 240)
        assertTrue(projected > 25.2f)
        assertEquals(27, snappedWheelValue(projected, 0, 240))
        assertEquals(0, snappedWheelValue(-20f, 0, 240))
        assertEquals(59, snappedWheelValue(100f, 0, 59))
    }

    @Test fun settleCurveIsBoundedAndEndsExactlyOnTarget() {
        assertEquals(0f, wheelEaseOutQuart(-1f))
        assertEquals(1f, wheelEaseOutQuart(1f))
        assertTrue(wheelEaseOutQuart(.5f) in .9f..1f)
    }
}
