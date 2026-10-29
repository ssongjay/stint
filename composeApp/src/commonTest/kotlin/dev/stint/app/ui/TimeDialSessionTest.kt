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
        val timer = TimerState(status = TimerStatus.PAUSED, targetSeconds = 3_600, accumulatedMillis = 1_800_001)
        val session = timeDialSession(state(timer), subject, TimerMode.COUNTDOWN)
        assertEquals(1, session.minimumSeconds)
        assertEquals(1_800, session.valueSeconds)
        assertEquals(1_800, session.targetOffsetSeconds)
        assertEquals(12_600, session.maximumSeconds)
        assertTrue(session.enabled)
        assertEquals(1_800, timeDialSession(state(timer.copy(phase = TimerPhase.SHORT_BREAK)), subject,
            TimerMode.COUNTDOWN).maximumSeconds)
        assertEquals(5_400, timeDialSession(state(timer.copy(phase = TimerPhase.LONG_BREAK)), subject,
            TimerMode.COUNTDOWN).maximumSeconds)
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

    @Test fun secondPickerChangesDisplayedRemainingWithoutDiscardingFractionalElapsed() {
        val timer = TimerState(status = TimerStatus.PAUSED, targetSeconds = 1_517,
            accumulatedMillis = 12_500)
        val session = timeDialSession(state(timer), subject, TimerMode.COUNTDOWN)
        assertEquals(1_505, session.valueSeconds)
        val pickedRemaining = 30
        val updatedTarget = session.targetOffsetSeconds + pickedRemaining
        assertEquals(42, updatedTarget)
        assertTrue(updatedTarget * 1000L > timer.accumulatedMillis)
        assertEquals(30, updatedTarget - timer.accumulatedMillis.div(1000L).toInt())
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
