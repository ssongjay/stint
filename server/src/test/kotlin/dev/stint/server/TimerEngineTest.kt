package dev.stint.server

import dev.stint.shared.Subject
import dev.stint.shared.TimerMode
import dev.stint.shared.TimerPhase
import dev.stint.shared.TimerState
import dev.stint.shared.TimerStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TimerEngineTest {
    private val subject = Subject(
        id = "study",
        name = "공부",
        focusSeconds = 10,
        shortBreakSeconds = 3,
        longBreakSeconds = 5,
        longBreakEvery = 2,
    )

    @Test
    fun `pause preserves millisecond precision`() {
        val timer = runningTimer(runningSince = 1_000, accumulated = 250)

        val paused = TimerEngine.pause(timer, 2_125)

        assertEquals(TimerStatus.PAUSED, paused.status)
        assertEquals(1_375, paused.accumulatedMillis)
        assertNull(paused.runningSinceEpochMs)
    }

    @Test
    fun `focus completion records once and starts the proper break at the deadline`() {
        val timer = runningTimer(runningSince = 1_000).copy(completedFocusCount = 1)

        val result = TimerEngine.settleExpired(timer, subject, 11_500) { "session-1" }

        assertNotNull(result)
        assertEquals("timer", result.session?.id)
        assertEquals(10, result.session?.durationSeconds)
        assertEquals(11_000, result.session?.endedAtEpochMs)
        assertEquals(TimerPhase.LONG_BREAK, result.timer.phase)
        assertEquals(5, result.timer.targetSeconds)
        assertEquals(11_000, result.timer.runningSinceEpochMs)
        assertEquals(2, result.timer.completedFocusCount)
    }

    @Test
    fun `a long sleeping process settles one focus and does not create repeated focus sessions`() {
        val timer = runningTimer(runningSince = 1_000)

        val result = TimerEngine.settleExpired(timer, subject, 100_000) { "only-session" }

        assertNotNull(result)
        assertEquals("timer", result.session?.id)
        assertEquals(TimerStatus.IDLE, result.timer.status)
        assertEquals(TimerPhase.FOCUS, result.timer.phase)
        assertEquals(1, result.timer.completedFocusCount)
        assertNull(TimerEngine.settleExpired(result.timer, subject, 200_000))
    }

    @Test
    fun `finishing a countdown caps the record at its target`() {
        val timer = runningTimer(runningSince = 1_000)

        val result = TimerEngine.finish(timer, subject, 50_000, "Mac") { "manual" }

        assertEquals(10, result.session?.durationSeconds)
        assertEquals(TimerStatus.IDLE, result.timer.status)
    }

    private fun runningTimer(runningSince: Long, accumulated: Long = 0) = TimerState(
        id = "timer",
        subjectId = subject.id,
        status = TimerStatus.RUNNING,
        phase = TimerPhase.FOCUS,
        mode = TimerMode.COUNTDOWN,
        targetSeconds = subject.focusSeconds,
        accumulatedMillis = accumulated,
        runningSinceEpochMs = runningSince,
        startedAtEpochMs = runningSince,
        controllingDeviceId = "mac",
        controllingDeviceName = "Mac",
    )
}
