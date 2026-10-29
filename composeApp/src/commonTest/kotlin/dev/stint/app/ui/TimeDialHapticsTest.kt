package dev.stint.app.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimeDialHapticsTest {
    @Test
    fun firstDetentAlwaysEmitsAndRapidTicksAreRateLimited() {
        assertTrue(shouldEmitTimeDialHaptic(1_000, Long.MIN_VALUE))
        assertFalse(shouldEmitTimeDialHaptic(1_027, 1_000))
        assertTrue(shouldEmitTimeDialHaptic(1_028, 1_000))
    }

    @Test
    fun monotonicClockResetDoesNotSuppressTheNextDetent() {
        assertTrue(shouldEmitTimeDialHaptic(5, 10_000))
    }
}
