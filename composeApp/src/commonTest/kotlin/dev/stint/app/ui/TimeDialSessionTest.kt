package dev.stint.app.ui

import dev.stint.shared.ClientState
import dev.stint.shared.ConnectionSettings
import dev.stint.shared.ConnectionStatus
import dev.stint.shared.Snapshot
import dev.stint.shared.Subject
import dev.stint.shared.TimerMode
import dev.stint.shared.TimerPhase
import dev.stint.shared.TimerState
import dev.stint.shared.TimerStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TimeDialSessionTest {
    private val subject = Subject("study", "Study")
    private fun state(timer: TimerState = TimerState(), revision: Long = 1) = ClientState(
        settings = ConnectionSettings(),
        snapshot = Snapshot(0, revision, listOf(subject), timer.copy(subjectId = timer.subjectId ?: subject.id)),
        connection = ConnectionStatus.CONNECTED,
    )

    @Test fun pausedRangePreservesExactElapsedAndPhaseLimit() {
        val timer = TimerState(status = TimerStatus.PAUSED, accumulatedMillis = 1_800_001)
        val session = timeDialSession(state(timer), subject, TimerMode.COUNTDOWN)
        assertEquals(31, session.minimumMinutes)
        assertEquals(240, session.maximumMinutes)
        assertTrue(session.enabled)
        assertEquals(60, timeDialSession(state(timer.copy(phase = TimerPhase.SHORT_BREAK)), subject,
            TimerMode.COUNTDOWN).maximumMinutes)
        assertEquals(120, timeDialSession(state(timer.copy(phase = TimerPhase.LONG_BREAK)), subject,
            TimerMode.COUNTDOWN).maximumMinutes)
    }

    @Test fun runningStopwatchOfflineAndExhaustedRangeCannotAdjust() {
        assertFalse(timeDialSession(state(TimerState(status = TimerStatus.RUNNING)), subject,
            TimerMode.COUNTDOWN).enabled)
        assertFalse(timeDialSession(state(), subject, TimerMode.STOPWATCH).enabled)
        assertFalse(timeDialSession(state().copy(connection = ConnectionStatus.OFFLINE), subject,
            TimerMode.COUNTDOWN).enabled)
        assertFalse(timeDialSession(state(TimerState(status = TimerStatus.PAUSED,
            accumulatedMillis = 14_400_000)), subject, TimerMode.COUNTDOWN).enabled)
        assertFalse(timeDialSession(state(TimerState(subjectId = "other", status = TimerStatus.PAUSED)),
            subject, TimerMode.COUNTDOWN).enabled)
    }

    @Test fun anyAuthoritativeReplacementInvalidatesGestureIdentity() {
        val original = state(TimerState(id = "A", status = TimerStatus.PAUSED))
        val snapshot = requireNotNull(original.snapshot)
        val key = timeDialSession(original, subject, TimerMode.COUNTDOWN).interactionKey
        assertNotEquals(key, timeDialSession(original.copy(snapshot = snapshot.copy(
            timer = snapshot.timer.copy(id = "B"))), subject, TimerMode.COUNTDOWN).interactionKey)
        assertNotEquals(key, timeDialSession(original.copy(snapshot = snapshot.copy(
            revision = 2)), subject, TimerMode.COUNTDOWN).interactionKey)
        assertNotEquals(key, timeDialSession(original, subject.copy(id = "other"),
            TimerMode.COUNTDOWN).interactionKey)
    }
}
