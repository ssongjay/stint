package dev.stint.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimeDialMathTest {
    @Test
    fun leftwardDragAdvancesByOneMinutePerDetent() {
        assertEquals(
            27f,
            dialValueAfterDrag(25f, dragPixels = -36f, pixelsPerMinute = 18f, minimum = 1, maximum = 240),
        )
        assertEquals(
            23f,
            dialValueAfterDrag(25f, dragPixels = 36f, pixelsPerMinute = 18f, minimum = 1, maximum = 240),
        )
    }

    @Test
    fun dragAndFlingNeverEscapeThePhaseRange() {
        assertEquals(60f, dialValueAfterDrag(59f, -1_000f, 18f, 1, 60))
        assertEquals(1f, dialValueAfterDrag(2f, 1_000f, 18f, 1, 60))
        assertEquals(60f, projectedDialValue(59f, -4_000f, 18f, 1, 60))
        assertEquals(1f, projectedDialValue(2f, 4_000f, 18f, 1, 60))
    }

    @Test
    fun wheelUnitsMapDirectlyToMinuteDetentsAndKeepTrackpadFractions() {
        assertEquals(24f, dialValueAfterScroll(25f, scrollUnits = 1f, minimum = 1, maximum = 60))
        assertEquals(26f, dialValueAfterScroll(25f, scrollUnits = -1f, minimum = 1, maximum = 60))
        val firstTrackpadEvent = dialValueAfterScroll(25f, .25f, 1, 60)
        assertEquals(24.75f, firstTrackpadEvent)
        assertEquals(24.5f, dialValueAfterScroll(firstTrackpadEvent, .25f, 1, 60))
        assertEquals(1f, dialValueAfterScroll(1f, 10f, 1, 60))
    }

    @Test
    fun releaseProjectionFollowsVelocityAndSnapsToOneMinute() {
        val projected = projectedDialValue(25.2f, -300f, 18f, 1, 240)
        assertTrue(projected > 25.2f)
        assertEquals(27, snappedDialMinutes(projected, 1, 240))
        assertEquals(1, snappedDialMinutes(-20f, 1, 240))
        assertEquals(240, snappedDialMinutes(400f, 1, 240))
    }

    @Test
    fun settleCurveIsBoundedAndEndsExactlyOnTarget() {
        assertEquals(0f, dialEaseOutQuart(-1f))
        assertEquals(1f, dialEaseOutQuart(1f))
        assertTrue(dialEaseOutQuart(.5f) in .9f..1f)
    }
}
