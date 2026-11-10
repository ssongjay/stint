package dev.stint.app

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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimerNotificationPolicyTest {
    @Test
    fun `countdown chronometer uses local clock plus server-derived remaining duration`() {
        val state = state(
            timer = TimerState(
                id = "timer-a",
                subjectId = "subject",
                status = TimerStatus.RUNNING,
                mode = TimerMode.COUNTDOWN,
                targetSeconds = 1_500,
                runningSinceEpochMs = 990_250,
            ),
            serverNow = 1_000_000,
        )

        val presentation = assertNotNull(timerNotificationPresentation(state, localNowEpochMs = 7_000))

        assertEquals(1_497_250L, presentation.whenEpochMs)
        assertEquals("24:51", presentation.timeText)
        assertEquals(9, presentation.progress)
        assertEquals(1_500, presentation.progressMax)
        assertTrue(presentation.usesChronometer)
        assertTrue(presentation.chronometerCountDown)
        assertTrue(presentation.liveUpdateRequested)
        assertTrue(presentation.actionsEnabled)
    }

    @Test
    fun `paused countdown keeps fractional elapsed and shows whole remaining second ceiling`() {
        val state = state(
            timer = TimerState(
                id = "timer-a",
                subjectId = "subject",
                status = TimerStatus.PAUSED,
                mode = TimerMode.COUNTDOWN,
                targetSeconds = 30,
                accumulatedMillis = 1_001,
            ),
        )

        val presentation = assertNotNull(timerNotificationPresentation(state, localNowEpochMs = 500))

        assertEquals("00:29", presentation.timeText)
        assertEquals("일시정지 · 00:29 남음", presentation.contentText)
        assertFalse(presentation.usesChronometer)
        assertFalse(presentation.liveUpdateRequested)
        assertNull(presentation.timeoutAfterMs)
        assertEquals(1, presentation.progress)
    }

    @Test
    fun `prepared break is presented as waiting to start`() {
        val timer = TimerState(
            id = "timer-a", subjectId = "subject", status = TimerStatus.PAUSED,
            phase = TimerPhase.SHORT_BREAK, mode = TimerMode.COUNTDOWN,
            targetSeconds = 300, accumulatedMillis = 0, runningSinceEpochMs = null,
            startedAtEpochMs = 100_000,
        )

        val presentation = assertNotNull(timerNotificationPresentation(state(timer), localNowEpochMs = 500))

        assertEquals("05:00", presentation.timeText)
        assertEquals("시작 대기 · 05:00 휴식", presentation.contentText)
        assertEquals("휴식 시작", timerResumeActionLabel(timer))
        assertEquals(0, presentation.progress)
        assertFalse(presentation.usesChronometer)
        assertFalse(presentation.liveUpdateRequested)
        assertNull(presentation.timeoutAfterMs)
    }

    @Test
    fun `paused break with progress remains an ordinary paused timer`() {
        val timer = TimerState(
            id = "timer-a", subjectId = "subject", status = TimerStatus.PAUSED,
            phase = TimerPhase.LONG_BREAK, mode = TimerMode.COUNTDOWN,
            targetSeconds = 900, accumulatedMillis = 2_000, startedAtEpochMs = 100_000,
        )

        val presentation = assertNotNull(timerNotificationPresentation(state(timer), localNowEpochMs = 500))

        assertEquals("일시정지 · 14:58 남음", presentation.contentText)
        assertEquals("계속하기", timerResumeActionLabel(timer))
        assertEquals(2, presentation.progress)
    }

    @Test
    fun `stopwatch converts server elapsed duration onto local clock epoch`() {
        val state = state(
            timer = TimerState(
                id = "timer-a",
                subjectId = "subject",
                status = TimerStatus.RUNNING,
                mode = TimerMode.STOPWATCH,
                accumulatedMillis = 3_600_123,
                runningSinceEpochMs = 98_000,
            ),
            serverNow = 100_345,
        )

        val presentation = assertNotNull(timerNotificationPresentation(state, localNowEpochMs = 10_000_000))

        assertEquals("60:02", presentation.timeText)
        assertEquals(6_397_532L, presentation.whenEpochMs)
        assertTrue(presentation.usesChronometer)
        assertFalse(presentation.chronometerCountDown)
        assertTrue(presentation.liveUpdateRequested)
    }

    @Test
    fun `offline cached timer never appears as a live chronometer or accepts actions`() {
        val state = state(
            timer = TimerState(
                id = "timer-a",
                subjectId = "subject",
                status = TimerStatus.RUNNING,
                runningSinceEpochMs = 90_000,
            ),
            serverNow = 100_000,
            connection = ConnectionStatus.OFFLINE,
        )

        val presentation = assertNotNull(timerNotificationPresentation(state, localNowEpochMs = 1_000))

        assertEquals("—", presentation.timeText)
        assertTrue(presentation.contentText.startsWith("오프라인"))
        assertFalse(presentation.usesChronometer)
        assertFalse(presentation.liveUpdateRequested)
        assertFalse(presentation.actionsEnabled)
    }

    @Test
    fun `idle and identity-less timers do not produce notifications`() {
        assertNull(timerNotificationPresentation(state(TimerState()), localNowEpochMs = 0))
        assertNull(
            timerNotificationPresentation(
                state(TimerState(id = null, status = TimerStatus.RUNNING)),
                localNowEpochMs = 0,
            ),
        )
    }

    @Test
    fun `notification action requires exact synchronized timer context`() {
        val active = state(
            timer = TimerState(
                id = "timer-a",
                subjectId = "subject",
                status = TimerStatus.RUNNING,
                phase = TimerPhase.FOCUS,
            ),
            revision = 12,
        )
        val guard = assertNotNull(notificationCommandGuard(active))

        assertTrue(notificationCommandMatches(TimerNotificationPolicy.ACTION_PAUSE, guard, active))
        assertTrue(notificationCommandMatches(TimerNotificationPolicy.ACTION_FINISH, guard, active))
        assertFalse(notificationCommandMatches(TimerNotificationPolicy.ACTION_RESUME, guard, active))
        assertFalse(notificationCommandMatches(TimerNotificationPolicy.ACTION_SKIP_BREAK, guard, active))
        assertFalse(notificationCommandMatches(TimerNotificationPolicy.ACTION_PAUSE, guard, active.copy(working = true)))
        assertFalse(
            notificationCommandMatches(
                TimerNotificationPolicy.ACTION_PAUSE,
                guard,
                active.copy(snapshot = active.snapshot?.copy(revision = 13)),
            ),
        )
    }

    @Test
    fun `notification action guard rejects offline replacement and phase changes`() {
        val active = state(
            timer = TimerState(
                id = "timer-a",
                subjectId = "subject",
                status = TimerStatus.PAUSED,
                phase = TimerPhase.SHORT_BREAK,
            ),
            revision = 44,
        )
        val guard = assertNotNull(notificationCommandGuard(active))

        assertTrue(notificationCommandMatches(TimerNotificationPolicy.ACTION_RESUME, guard, active))
        assertTrue(notificationCommandMatches(TimerNotificationPolicy.ACTION_SKIP_BREAK, guard, active))
        val activeSnapshot = assertNotNull(active.snapshot)
        assertFalse(
            notificationCommandMatches(
                TimerNotificationPolicy.ACTION_RESUME,
                guard,
                active.copy(connection = ConnectionStatus.OFFLINE),
            ),
        )
        assertFalse(
            notificationCommandMatches(
                TimerNotificationPolicy.ACTION_RESUME,
                guard,
                active.copy(
                    snapshot = activeSnapshot.copy(
                        timer = activeSnapshot.timer.copy(id = "timer-b", phase = TimerPhase.LONG_BREAK),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `forged idle guard cannot invoke an end action`() {
        val idle = state(
            timer = TimerState(
                id = "timer-a",
                subjectId = "subject",
                status = TimerStatus.IDLE,
                phase = TimerPhase.FOCUS,
            ),
            revision = 7,
        )
        val forged = NotificationCommandGuard(
            timerId = "timer-a",
            revision = 7,
            phase = TimerPhase.FOCUS,
            status = TimerStatus.IDLE,
        )

        assertFalse(notificationCommandMatches(TimerNotificationPolicy.ACTION_FINISH, forged, idle))
    }

    @Test
    fun `render key coalesces sub-second running ticks`() {
        val running = state(
            timer = TimerState(
                id = "timer-a",
                subjectId = "subject",
                status = TimerStatus.RUNNING,
                runningSinceEpochMs = 90_000L,
            ),
            serverNow = 100_000L,
            revision = 8L,
        )

        assertEquals(
            timerNotificationRenderKey(running),
            timerNotificationRenderKey(running.copy(serverNowEpochMs = 100_999L)),
        )
        assertTrue(
            timerNotificationRenderKey(running) !=
                timerNotificationRenderKey(running.copy(serverNowEpochMs = 101_000L)),
        )
    }

    @Test
    fun `render key names every state change that requires a new notification`() {
        val running = state(
            timer = TimerState(
                id = "timer-a",
                subjectId = "subject",
                status = TimerStatus.RUNNING,
                runningSinceEpochMs = 90_000L,
            ),
            serverNow = 100_000L,
            revision = 8L,
        )
        val baseline = timerNotificationRenderKey(running)

        assertTrue(baseline != timerNotificationRenderKey(running.copy(connection = ConnectionStatus.OFFLINE)))
        assertTrue(baseline != timerNotificationRenderKey(running.copy(working = true)))
        assertTrue(
            baseline != timerNotificationRenderKey(
                running.copy(snapshot = running.snapshot?.copy(revision = 9L)),
            ),
        )
        val snapshot = assertNotNull(running.snapshot)
        val paused = running.copy(
            snapshot = snapshot.copy(timer = snapshot.timer.copy(status = TimerStatus.PAUSED)),
        )
        assertEquals(
            timerNotificationRenderKey(paused),
            timerNotificationRenderKey(paused.copy(serverNowEpochMs = 101_000L)),
        )
    }

    @Test
    fun `service stop ignores a stale idle observation after a newer timer starts`() {
        val idle = state(TimerState(), revision = 10L)
        val running = state(
            timer = TimerState(
                id = "timer-new",
                subjectId = "subject",
                status = TimerStatus.RUNNING,
            ),
            revision = 11L,
        )

        assertEquals(7, timerStatusServiceStopStartId(idle, idle, latestStartId = 7))
        assertNull(timerStatusServiceStopStartId(idle, running, latestStartId = 8))
        assertNull(timerStatusServiceStopStartId(idle, idle, latestStartId = 0))
    }

    private fun state(
        timer: TimerState,
        serverNow: Long = 100_000,
        revision: Long = 1,
        connection: ConnectionStatus = ConnectionStatus.CONNECTED,
    ): ClientState = ClientState(
        settings = ConnectionSettings(),
        snapshot = Snapshot(
            serverNowEpochMs = serverNow,
            revision = revision,
            subjects = listOf(Subject("subject", "집중하기")),
            timer = timer,
        ),
        serverNowEpochMs = serverNow,
        connection = connection,
    )
}
