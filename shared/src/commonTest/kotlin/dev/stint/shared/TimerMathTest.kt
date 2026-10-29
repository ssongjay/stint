package dev.stint.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class TimerMathTest {
    @Test
    fun pausedTimerDoesNotAdvanceWithWallTime() {
        val timer = TimerState(status = TimerStatus.PAUSED, accumulatedMillis = 12_750, targetSeconds = 60)
        assertEquals(12, timer.elapsedSeconds(100_000))
        assertEquals(48, timer.remainingSeconds(9_000_000))
    }

    @Test
    fun resumePreservesPartialSecondAcrossPause() {
        val timer = TimerState(status = TimerStatus.RUNNING, accumulatedMillis = 7_500, runningSinceEpochMs = 1_000)
        assertEquals(10, timer.elapsedSeconds(3_500))
    }

    @Test
    fun countdownClampsAtDeadlineWhileStopwatchContinues() {
        val timer = TimerState(status = TimerStatus.RUNNING, runningSinceEpochMs = 1_000, targetSeconds = 10)
        assertEquals(0, timer.remainingSeconds(100_000))
        assertEquals(10, timer.elapsedSeconds(100_000))
        assertEquals(99, timer.copy(mode = TimerMode.STOPWATCH).elapsedSeconds(100_000))
        assertEquals(0, timer.copy(mode = TimerMode.STOPWATCH).remainingSeconds(100_000))
    }

    @Test
    fun clockBeforeAnchorCannotCreateNegativeElapsed() {
        val timer = TimerState(status = TimerStatus.RUNNING, runningSinceEpochMs = 50_000, accumulatedMillis = 2_000)
        assertEquals(2, timer.elapsedSeconds(10_000))
    }

    @Test
    fun persistedSnapshotRoundTripsRunningStateAndMemo() {
        val snapshot = Snapshot(
            serverNowEpochMs = 3000,
            revision = 8,
            timer = TimerState(id = "active", status = TimerStatus.PAUSED, accumulatedMillis = 1750),
            sessions = listOf(FocusSession("done", "study", 0, 1000, 1, TimerMode.COUNTDOWN, "복구 확인")),
        )
        assertEquals(snapshot, Json.decodeFromString<Snapshot>(Json.encodeToString(snapshot)))
    }

    @Test
    fun ordinarySettingsJsonNeverContainsTheConnectionSecret() {
        val settings = ConnectionSettings(baseUrl = "https://stint.example", token = "must-not-serialize")
        val text = Json.encodeToString(settings)
        kotlin.test.assertFalse(text.contains("must-not-serialize"))
        assertEquals("", Json.decodeFromString<ConnectionSettings>(text).token)
    }
}
