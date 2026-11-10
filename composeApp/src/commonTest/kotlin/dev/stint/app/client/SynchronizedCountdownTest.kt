package dev.stint.app.client

import dev.stint.shared.ClientState
import dev.stint.shared.ConnectionSettings
import dev.stint.shared.Snapshot
import dev.stint.shared.Subject
import dev.stint.shared.TimerMode
import dev.stint.shared.TimerPhase
import dev.stint.shared.TimerState
import dev.stint.shared.TimerStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SynchronizedCountdownTest {
    @Test
    fun `active countdown owns the shared completion identity and deadline`() {
        val countdown = state(
            TimerState(
                id = "timer",
                subjectId = "subject",
                status = TimerStatus.RUNNING,
                phase = TimerPhase.LONG_BREAK,
                mode = TimerMode.COUNTDOWN,
                targetSeconds = 20,
                accumulatedMillis = 2_000,
                runningSinceEpochMs = 10_000,
                startedAtEpochMs = 9_000,
            ),
        ).synchronizedCountdownOrNull()

        assertEquals("timer|LONG_BREAK|9000", countdown?.key)
        assertEquals("timer", countdown?.timerId)
        assertEquals("subject", countdown?.subjectId)
        assertEquals("개발 공부", countdown?.subjectName)
        assertEquals(28_000, countdown?.deadlineEpochMs)
    }

    @Test
    fun `deadline calculation saturates and ignores negative accumulated time`() {
        val saturated = state(running(
            runningSinceEpochMs = Long.MAX_VALUE - 10,
            targetSeconds = 20,
        )).synchronizedCountdownOrNull()
        val negative = state(running(
            runningSinceEpochMs = 10_000,
            targetSeconds = 20,
            accumulatedMillis = -5_000,
        )).synchronizedCountdownOrNull()

        assertEquals(Long.MAX_VALUE, saturated?.deadlineEpochMs)
        assertEquals(30_000, negative?.deadlineEpochMs)
    }

    @Test
    fun `missing subject keeps a platform neutral descriptor`() {
        val countdown = state(running().copy(subjectId = null)).synchronizedCountdownOrNull()

        assertNull(countdown?.subjectId)
        assertEquals("", countdown?.subjectName)
    }

    @Test
    fun `paused stopwatch and incomplete synchronized timers have no countdown descriptor`() {
        val running = running()

        assertNull(state(running.copy(status = TimerStatus.PAUSED)).synchronizedCountdownOrNull())
        assertNull(state(running.copy(mode = TimerMode.STOPWATCH)).synchronizedCountdownOrNull())
        assertNull(state(running.copy(id = null)).synchronizedCountdownOrNull())
        assertNull(state(running.copy(runningSinceEpochMs = null)).synchronizedCountdownOrNull())
        assertNull(state(running.copy(startedAtEpochMs = null)).synchronizedCountdownOrNull())
    }

    private fun state(timer: TimerState) = ClientState(
        settings = ConnectionSettings(),
        snapshot = Snapshot(
            serverNowEpochMs = 12_000,
            subjects = listOf(Subject("subject", "개발 공부")),
            timer = timer,
        ),
        serverNowEpochMs = 12_000,
    )

    private fun running(
        runningSinceEpochMs: Long = 10_000,
        targetSeconds: Int = 20,
        accumulatedMillis: Long = 0,
    ) = TimerState(
        id = "timer",
        subjectId = "subject",
        status = TimerStatus.RUNNING,
        phase = TimerPhase.FOCUS,
        mode = TimerMode.COUNTDOWN,
        targetSeconds = targetSeconds,
        accumulatedMillis = accumulatedMillis,
        runningSinceEpochMs = runningSinceEpochMs,
        startedAtEpochMs = 10_000,
    )
}
