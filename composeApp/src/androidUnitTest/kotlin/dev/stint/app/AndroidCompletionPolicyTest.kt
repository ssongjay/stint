package dev.stint.app

import dev.stint.app.client.TimerCompletionTracker
import dev.stint.shared.ClientState
import dev.stint.shared.ConnectionSettings
import dev.stint.shared.ConnectionStatus
import dev.stint.shared.FocusSession
import dev.stint.shared.Snapshot
import dev.stint.shared.Subject
import dev.stint.shared.TimerMode
import dev.stint.shared.TimerPhase
import dev.stint.shared.TimerState
import dev.stint.shared.TimerStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AndroidCompletionPolicyTest {
    @Test
    fun `running countdown arms elapsed realtime at synchronized deadline`() {
        val state = state(
            now = 12_000L,
            timer = timer(
                accumulatedMillis = 2_000L,
                runningSinceEpochMs = 10_000L,
                targetSeconds = 20,
            ),
        )

        val arm = assertNotNull(completionAlarmArm(
            state,
            "identity",
            elapsedRealtimeMs = 500_000L,
            wallClockEpochMs = 1_700_000_000_000L,
        ))

        assertEquals(28_000L, arm.deadlineEpochMs)
        assertEquals(516_000L, arm.triggerElapsedRealtimeMs)
        assertEquals(1_700_000_016_000L, arm.triggerWallClockEpochMs)
        assertEquals("timer|FOCUS|10000", arm.key)
    }

    @Test
    fun `pause offline stopwatch and elapsed countdown never arm`() {
        val running = timer(runningSinceEpochMs = 10_000L)

        assertNull(completionAlarmArm(state(timer = running.copy(status = TimerStatus.PAUSED)), "id", 1L))
        assertNull(completionAlarmArm(
            state(timer = running, connection = ConnectionStatus.OFFLINE), "id", 1L,
        ))
        assertNull(completionAlarmArm(
            state(timer = running.copy(mode = TimerMode.STOPWATCH)), "id", 1L,
        ))
        assertNull(completionAlarmArm(state(now = 40_000L, timer = running), "id", 1L))
    }

    @Test
    fun `stored arm recreates tracker baseline and confirms natural focus completion`() {
        val initial = state(now = 12_000L, timer = timer(runningSinceEpochMs = 10_000L, targetSeconds = 20))
        val arm = assertNotNull(completionAlarmArm(initial, "identity", 500_000L))
        val tracker = TimerCompletionTracker()
        assertNull(tracker.update(arm.baselineState(initial.settings)))

        val completed = state(
            now = arm.deadlineEpochMs,
            timer = timer(
                status = TimerStatus.RUNNING,
                phase = TimerPhase.SHORT_BREAK,
                runningSinceEpochMs = arm.deadlineEpochMs,
                startedAtEpochMs = arm.deadlineEpochMs,
                targetSeconds = 5,
            ),
            sessions = listOf(
                FocusSession(
                    id = arm.timerId,
                    subjectId = "subject",
                    startedAtEpochMs = arm.startedAtEpochMs,
                    endedAtEpochMs = arm.deadlineEpochMs,
                    durationSeconds = arm.targetSeconds,
                    mode = TimerMode.COUNTDOWN,
                ),
            ),
        )

        assertEquals("집중을 마쳤어요", tracker.update(completed)?.title)
    }

    @Test
    fun `alarm schedule signature includes identity key and deadline but not bookkeeping`() {
        val state = state(now = 12_000L, timer = timer(runningSinceEpochMs = 10_000L, targetSeconds = 20))
        val arm = assertNotNull(completionAlarmArm(state, "identity", 500_000L, 1_700_000_000_000L))

        assertEquals(true, arm.hasSameSchedule(arm.copy(
            revision = arm.revision + 1,
            triggerElapsedRealtimeMs = arm.triggerElapsedRealtimeMs + 10,
            triggerWallClockEpochMs = arm.triggerWallClockEpochMs + 10,
        )))
        assertEquals(false, arm.hasSameSchedule(arm.copy(deadlineEpochMs = arm.deadlineEpochMs + 1)))
        assertEquals(false, arm.hasSameSchedule(arm.copy(identityHash = "other")))
        assertEquals(false, arm.hasSameSchedule(arm.copy(key = "other-key")))
    }

    private fun state(
        now: Long = 12_000L,
        timer: TimerState,
        connection: ConnectionStatus = ConnectionStatus.CONNECTED,
        sessions: List<FocusSession> = emptyList(),
    ) = ClientState(
        settings = ConnectionSettings("https://stint.example", "test-token", "device", "Test"),
        snapshot = Snapshot(
            serverNowEpochMs = now,
            revision = 7,
            subjects = listOf(Subject("subject", "개발 공부")),
            timer = timer,
            sessions = sessions,
        ),
        serverNowEpochMs = now,
        connection = connection,
    )

    private fun timer(
        status: TimerStatus = TimerStatus.RUNNING,
        phase: TimerPhase = TimerPhase.FOCUS,
        mode: TimerMode = TimerMode.COUNTDOWN,
        targetSeconds: Int = 20,
        accumulatedMillis: Long = 0L,
        runningSinceEpochMs: Long? = 10_000L,
        startedAtEpochMs: Long? = 10_000L,
    ) = TimerState(
        id = "timer",
        subjectId = "subject",
        status = status,
        phase = phase,
        mode = mode,
        targetSeconds = targetSeconds,
        accumulatedMillis = accumulatedMillis,
        runningSinceEpochMs = runningSinceEpochMs,
        startedAtEpochMs = startedAtEpochMs,
    )
}
