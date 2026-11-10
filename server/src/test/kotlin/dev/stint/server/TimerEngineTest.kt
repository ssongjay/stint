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
import kotlin.test.assertFailsWith

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
    fun `pause shares stopwatch overflow and negative progress guards`() {
        val overflowing = runningTimer(runningSince = 1_000, accumulated = Long.MAX_VALUE - 5)
            .copy(mode = TimerMode.STOPWATCH)
        val negative = runningTimer(runningSince = 2_000, accumulated = -500)
            .copy(mode = TimerMode.STOPWATCH)

        assertEquals(Long.MAX_VALUE, TimerEngine.pause(overflowing, 3_000).accumulatedMillis)
        assertEquals(0L, TimerEngine.pause(negative, 1_000).accumulatedMillis)
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
    fun `focus completion ends at idle when pomodoro flow is disabled`() {
        val timer = runningTimer(runningSince = 1_000).copy(completedFocusCount = 1)

        val result = TimerEngine.settleExpired(
            timer,
            subject.copy(pomodoroEnabled = false),
            11_500,
        )

        assertNotNull(result)
        assertEquals(10, result.session?.durationSeconds)
        assertEquals(TimerStatus.IDLE, result.timer.status)
        assertEquals(TimerPhase.FOCUS, result.timer.phase)
        assertEquals(2, result.timer.completedFocusCount)
        assertNull(TimerEngine.settleExpired(result.timer, subject, 20_000))
    }

    @Test
    fun `focus completion prepares a paused break when automatic start is disabled`() {
        val timer = runningTimer(runningSince = 1_000).copy(completedFocusCount = 1)

        val result = TimerEngine.settleExpired(
            timer,
            subject.copy(autoStartBreak = false),
            100_000,
        )

        assertNotNull(result)
        assertEquals(TimerStatus.PAUSED, result.timer.status)
        assertEquals(TimerPhase.LONG_BREAK, result.timer.phase)
        assertEquals(5, result.timer.targetSeconds)
        assertEquals(0, result.timer.accumulatedMillis)
        assertNull(result.timer.runningSinceEpochMs)
        assertEquals(11_000, result.timer.startedAtEpochMs)
        assertEquals(2, result.timer.completedFocusCount)
        assertNull(TimerEngine.settleExpired(result.timer, subject, 200_000))
    }

    @Test
    fun `a prepared break starts from zero when resumed manually`() {
        val prepared = TimerEngine.settleExpired(
            runningTimer(runningSince = 1_000),
            subject.copy(autoStartBreak = false),
            11_500,
        )!!.timer

        val resumed = TimerEngine.resume(prepared, 20_000, "android", "Fold")

        assertEquals(TimerStatus.RUNNING, resumed.status)
        assertEquals(TimerPhase.SHORT_BREAK, resumed.phase)
        assertEquals(0, resumed.accumulatedMillis)
        assertEquals(20_000, resumed.runningSinceEpochMs)
        assertEquals("android", resumed.controllingDeviceId)
    }

    @Test
    fun `finishing a countdown caps the record at its target`() {
        val timer = runningTimer(runningSince = 1_000)

        val result = TimerEngine.finish(timer, subject, 50_000, "Mac") { "manual" }

        assertEquals(10, result.session?.durationSeconds)
        assertEquals(TimerStatus.IDLE, result.timer.status)
    }

    @Test
    fun `finishing a very long stopwatch keeps the largest representable duration`() {
        val timer = runningTimer(runningSince = 1_000, accumulated = Long.MAX_VALUE - 5)
            .copy(mode = TimerMode.STOPWATCH)

        val result = TimerEngine.finish(timer, subject, 3_000, "Mac")

        assertEquals(Int.MAX_VALUE, result.session?.durationSeconds)
    }

    @Test
    fun `finishing a countdown still caps elapsed time at its target`() {
        val timer = runningTimer(runningSince = 1_000, accumulated = Long.MAX_VALUE - 5)

        val result = TimerEngine.finish(timer, subject, 3_000, "Mac")

        assertEquals(timer.targetSeconds, result.session?.durationSeconds)
    }

    @Test
    fun `changing a paused countdown duration preserves its exact progress and identity`() {
        val paused = TimerEngine.pause(runningTimer(runningSince = 1_000, accumulated = 250), 2_125)

        val changed = TimerEngine.setDuration(paused, 20)

        assertEquals(paused.copy(targetSeconds = 20), changed)
        assertEquals(1_375, changed.accumulatedMillis)
        assertEquals("timer", changed.id)
        assertEquals(1_000, changed.startedAtEpochMs)
    }

    @Test
    fun `duration change rejects running stopwatch and elapsed boundary`() {
        val paused = TimerEngine.pause(runningTimer(runningSince = 1_000), 2_125)

        assertFailsWith<IllegalArgumentException> { TimerEngine.setDuration(paused.copy(status = TimerStatus.RUNNING), 20) }
        assertFailsWith<IllegalArgumentException> { TimerEngine.setDuration(paused.copy(mode = TimerMode.STOPWATCH), 20) }
        assertFailsWith<IllegalArgumentException> { TimerEngine.setDuration(paused, 1) }
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
