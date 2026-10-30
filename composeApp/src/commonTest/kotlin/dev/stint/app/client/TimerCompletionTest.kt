package dev.stint.app.client

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
import kotlin.test.assertNull

class TimerCompletionTest {
    @Test
    fun `focus countdown emits once when its deadline is reached`() {
        val tracker = TimerCompletionTracker()
        assertNull(tracker.update(state(now = 10_000, timer = running(targetSeconds = 5))))

        val event = tracker.update(state(now = 15_000, timer = running(targetSeconds = 5)))

        assertEquals("집중을 마쳤어요", event?.title)
        assertEquals("개발 공부 · 잠시 쉬어가세요.", event?.body)
        assertEquals(TimerPhase.FOCUS, event?.phase)
        assertNull(tracker.update(state(now = 16_000, timer = running(targetSeconds = 5))))
    }

    @Test
    fun `short and long breaks use the break completion copy`() {
        listOf(TimerPhase.SHORT_BREAK, TimerPhase.LONG_BREAK).forEach { phase ->
            val tracker = TimerCompletionTracker()
            assertNull(tracker.update(state(10_000, running(phase = phase, targetSeconds = 2))))
            val event = tracker.update(state(12_000, running(phase = phase, targetSeconds = 2)))
            assertEquals("휴식이 끝났어요", event?.title)
            assertEquals("개발 공부 · 다음 집중을 시작할 준비가 됐어요.", event?.body)
        }
    }

    @Test
    fun `first connected overdue state and reconnect never replay old completion`() {
        val tracker = TimerCompletionTracker()
        assertNull(tracker.update(state(20_000, running(targetSeconds = 5))))
        assertNull(tracker.update(state(20_100, running(targetSeconds = 5), ConnectionStatus.OFFLINE)))
        assertNull(tracker.update(state(30_000, running(targetSeconds = 5))))
        assertNull(tracker.update(state(31_000, TimerState(status = TimerStatus.IDLE))))
    }

    @Test
    fun `pause at the deadline cancels the pending completion`() {
        val tracker = TimerCompletionTracker()
        assertNull(tracker.update(state(10_000, running(targetSeconds = 5))))
        assertNull(tracker.update(state(15_000, running(targetSeconds = 5).copy(
            status = TimerStatus.PAUSED,
            accumulatedMillis = 5_000,
            runningSinceEpochMs = null,
        ))))
        assertNull(tracker.update(state(16_000, TimerState(status = TimerStatus.IDLE))))
    }

    @Test
    fun `duration change while paused uses the resumed deadline`() {
        val tracker = TimerCompletionTracker()
        assertNull(tracker.update(state(10_000, running(targetSeconds = 5))))
        val paused = running(targetSeconds = 10).copy(
            status = TimerStatus.PAUSED,
            accumulatedMillis = 2_000,
            runningSinceEpochMs = null,
        )
        assertNull(tracker.update(state(12_000, paused)))
        val resumed = paused.copy(status = TimerStatus.RUNNING, runningSinceEpochMs = 20_000)
        assertNull(tracker.update(state(20_000, resumed)))
        assertNull(tracker.update(state(27_999, resumed)))
        assertEquals("집중을 마쳤어요", tracker.update(state(28_000, resumed))?.title)
    }

    @Test
    fun `fractional accumulated time keeps the exact millisecond deadline`() {
        val tracker = TimerCompletionTracker()
        val timer = running(targetSeconds = 5).copy(accumulatedMillis = 1_234)
        assertNull(tracker.update(state(10_000, timer)))
        assertNull(tracker.update(state(13_765, timer)))
        assertEquals("집중을 마쳤어요", tracker.update(state(13_766, timer))?.title)
    }

    @Test
    fun `server focus transition confirms a missed deadline exactly once`() {
        val tracker = TimerCompletionTracker()
        val focus = running(targetSeconds = 5)
        assertNull(tracker.update(state(10_000, focus)))
        val breakTimer = focus.copy(
            phase = TimerPhase.SHORT_BREAK,
            targetSeconds = 300,
            accumulatedMillis = 0,
            runningSinceEpochMs = 15_000,
            startedAtEpochMs = 15_000,
        )
        val session = FocusSession(
            id = "timer", subjectId = "subject", startedAtEpochMs = 10_000,
            endedAtEpochMs = 15_000, durationSeconds = 5, mode = TimerMode.COUNTDOWN,
        )

        val event = tracker.update(state(15_100, breakTimer, sessions = listOf(session)))

        assertEquals(TimerPhase.FOCUS, event?.phase)
        assertNull(tracker.update(state(15_200, breakTimer, sessions = listOf(session))))
    }

    @Test
    fun `confirmed focus transition is not lost while another mutation is working`() {
        val tracker = TimerCompletionTracker()
        val focus = running(targetSeconds = 5)
        assertNull(tracker.update(state(10_000, focus)))
        val breakTimer = focus.copy(
            phase = TimerPhase.SHORT_BREAK,
            targetSeconds = 300,
            runningSinceEpochMs = 15_000,
            startedAtEpochMs = 15_000,
        )
        val session = FocusSession(
            id = "timer", subjectId = "subject", startedAtEpochMs = 10_000,
            endedAtEpochMs = 15_000, durationSeconds = 5, mode = TimerMode.COUNTDOWN,
        )

        val event = tracker.update(
            state(15_100, breakTimer, sessions = listOf(session)).copy(working = true),
        )

        assertEquals(TimerPhase.FOCUS, event?.phase)
    }

    @Test
    fun `server break transition confirms a missed deadline`() {
        val tracker = TimerCompletionTracker()
        val rest = running(phase = TimerPhase.SHORT_BREAK, targetSeconds = 5)
        assertNull(tracker.update(state(10_000, rest)))

        val event = tracker.update(state(
            now = 15_100,
            timer = rest.copy(status = TimerStatus.IDLE, phase = TimerPhase.FOCUS, runningSinceEpochMs = null),
        ))

        assertEquals("휴식이 끝났어요", event?.title)
    }

    @Test
    fun `confirmed break transition is not lost while another mutation is working`() {
        val tracker = TimerCompletionTracker()
        val rest = running(phase = TimerPhase.LONG_BREAK, targetSeconds = 5)
        assertNull(tracker.update(state(10_000, rest)))

        val event = tracker.update(state(
            now = 15_100,
            timer = rest.copy(status = TimerStatus.IDLE, phase = TimerPhase.FOCUS, runningSinceEpochMs = null),
        ).copy(working = true))

        assertEquals(TimerPhase.LONG_BREAK, event?.phase)
    }

    @Test
    fun `manual finish and skipped break do not look like natural completion`() {
        val focusTracker = TimerCompletionTracker()
        val focus = running(targetSeconds = 10)
        assertNull(focusTracker.update(state(10_000, focus)))
        val shortSession = FocusSession(
            id = "timer", subjectId = "subject", startedAtEpochMs = 10_000,
            endedAtEpochMs = 15_000, durationSeconds = 5, mode = TimerMode.COUNTDOWN,
        )
        assertNull(focusTracker.update(state(
            now = 15_000,
            timer = focus.copy(status = TimerStatus.IDLE, runningSinceEpochMs = null),
            sessions = listOf(shortSession),
        )))

        val breakTracker = TimerCompletionTracker()
        val breakTimer = running(phase = TimerPhase.SHORT_BREAK, targetSeconds = 10)
        assertNull(breakTracker.update(state(10_000, breakTimer)))
        assertNull(breakTracker.update(state(
            now = 15_000,
            timer = breakTimer.copy(status = TimerStatus.IDLE, phase = TimerPhase.FOCUS),
        )))
    }

    @Test
    fun `stopwatch and working mutation never emit countdown completion`() {
        val stopwatch = running(targetSeconds = 1).copy(mode = TimerMode.STOPWATCH)
        val stopwatchTracker = TimerCompletionTracker()
        assertNull(stopwatchTracker.update(state(10_000, stopwatch)))
        assertNull(stopwatchTracker.update(state(20_000, stopwatch)))

        val workingTracker = TimerCompletionTracker()
        val countdown = running(targetSeconds = 2)
        assertNull(workingTracker.update(state(10_000, countdown)))
        assertNull(workingTracker.update(state(12_000, countdown).copy(working = true)))
        assertEquals("집중을 마쳤어요", workingTracker.update(state(12_001, countdown))?.title)
    }

    @Test
    fun `same timer id can complete focus and break because phase start is part of key`() {
        val tracker = TimerCompletionTracker()
        val focus = running(targetSeconds = 2)
        assertNull(tracker.update(state(10_000, focus)))
        assertEquals(TimerPhase.FOCUS, tracker.update(state(12_000, focus))?.phase)
        val rest = focus.copy(
            phase = TimerPhase.LONG_BREAK,
            targetSeconds = 3,
            runningSinceEpochMs = 12_000,
            startedAtEpochMs = 12_000,
        )
        assertNull(tracker.update(state(12_100, rest)))
        assertEquals(TimerPhase.LONG_BREAK, tracker.update(state(15_000, rest))?.phase)
        assertEquals(2, tracker.deliveredKeys().size)
    }

    @Test
    fun `restored delivered key suppresses the same completion`() {
        val first = TimerCompletionTracker()
        val timer = running(targetSeconds = 2)
        assertNull(first.update(state(10_000, timer)))
        val event = first.update(state(12_000, timer))
        val restored = TimerCompletionTracker(setOf(requireNotNull(event).key))

        assertNull(restored.update(state(10_000, timer)))
        assertNull(restored.update(state(12_000, timer)))
    }

    @Test
    fun `identity change establishes a new baseline without replay`() {
        val tracker = TimerCompletionTracker()
        assertNull(tracker.update(state(10_000, running(targetSeconds = 5))))
        val other = state(20_000, running(targetSeconds = 5)).copy(
            settings = ConnectionSettings(baseUrl = "https://other.example", token = "other"),
        )
        assertNull(tracker.update(other))
        assertNull(tracker.update(other.copy(snapshot = other.snapshot?.copy(timer = TimerState()))))
    }

    private fun state(
        now: Long,
        timer: TimerState,
        connection: ConnectionStatus = ConnectionStatus.CONNECTED,
        sessions: List<FocusSession> = emptyList(),
    ): ClientState = ClientState(
        settings = ConnectionSettings(baseUrl = "https://stint.example", token = "token"),
        snapshot = Snapshot(
            serverNowEpochMs = now,
            revision = 1,
            subjects = listOf(Subject("subject", "개발 공부")),
            timer = timer,
            sessions = sessions,
        ),
        serverNowEpochMs = now,
        connection = connection,
    )

    private fun running(
        phase: TimerPhase = TimerPhase.FOCUS,
        targetSeconds: Int,
    ): TimerState = TimerState(
        id = "timer",
        subjectId = "subject",
        status = TimerStatus.RUNNING,
        phase = phase,
        mode = TimerMode.COUNTDOWN,
        targetSeconds = targetSeconds,
        accumulatedMillis = 0,
        runningSinceEpochMs = 10_000,
        startedAtEpochMs = 10_000,
    )
}
