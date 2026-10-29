package dev.stint.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class TimerStateTest {
    @Test
    fun elapsedMillisKeepsSubsecondProgressAndCapsCountdownAtItsTarget() {
        val timer = TimerState(
            status = TimerStatus.RUNNING,
            mode = TimerMode.COUNTDOWN,
            targetSeconds = 2,
            accumulatedMillis = 250,
            runningSinceEpochMs = 1_000,
        )

        assertEquals(1_375, timer.elapsedMillis(2_125))
        assertEquals(1, timer.elapsedSeconds(2_125))
        assertEquals(2_000, timer.elapsedMillis(20_000))
    }

    @Test
    fun elapsedMillisIgnoresNegativeClockSkewAndSaturatesOverflow() {
        val skewed = TimerState(
            status = TimerStatus.RUNNING,
            mode = TimerMode.STOPWATCH,
            accumulatedMillis = Long.MAX_VALUE - 5,
            runningSinceEpochMs = 2_000,
        )

        assertEquals(Long.MAX_VALUE - 5, skewed.elapsedMillis(1_000))
        assertEquals(Long.MAX_VALUE, skewed.elapsedMillis(3_000))
        assertEquals(Int.MAX_VALUE, skewed.elapsedSeconds(3_000))
    }
}
