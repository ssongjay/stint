package dev.stint.app.ui

import dev.stint.shared.ConnectionStatus
import dev.stint.shared.TimerMode
import dev.stint.shared.TimerPhase
import dev.stint.shared.TimerState
import dev.stint.shared.TimerStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimerActionAvailabilityTest {
    @Test
    fun connectedSaveLocksActionsWithoutShowingOfflineLayout() {
        val availability = timerActionAvailability(
            connection = ConnectionStatus.CONNECTED,
            working = true,
            dialAdjusting = false,
        )

        assertFalse(availability.canMutate)
        assertFalse(availability.showOfflineMessage)
        assertTrue(availability.operationPending)
        assertEquals(
            "저장 중…",
            primaryTimerActionLabel(TimerState(status = TimerStatus.IDLE), TimerMode.COUNTDOWN, true),
        )
    }

    @Test
    fun connectedDialAdjustmentLocksActionsWithoutChangingOfflineLayout() {
        val availability = timerActionAvailability(
            connection = ConnectionStatus.CONNECTED,
            working = false,
            dialAdjusting = true,
        )

        assertFalse(availability.canMutate)
        assertFalse(availability.showOfflineMessage)
        assertFalse(availability.operationPending)
    }

    @Test
    fun actualOfflineStateKeepsTheExistingGuidance() {
        ConnectionStatus.entries.filter { it != ConnectionStatus.CONNECTED }.forEach { connection ->
            val availability = timerActionAvailability(connection, working = false, dialAdjusting = false)
            assertFalse(availability.canMutate)
            assertTrue(availability.showOfflineMessage)
            assertFalse(availability.operationPending)
        }
    }

    @Test
    fun primaryLabelsPreserveEveryTimerActionWhenIdle() {
        assertEquals("집중 시작", primaryTimerActionLabel(TimerState(), TimerMode.COUNTDOWN, false))
        assertEquals("측정 시작", primaryTimerActionLabel(TimerState(), TimerMode.STOPWATCH, false))
        assertEquals(
            "일시정지",
            primaryTimerActionLabel(TimerState(status = TimerStatus.RUNNING), TimerMode.COUNTDOWN, false),
        )
        assertEquals(
            "휴식 시작",
            primaryTimerActionLabel(
                TimerState(status = TimerStatus.PAUSED, phase = TimerPhase.SHORT_BREAK, accumulatedMillis = 0),
                TimerMode.COUNTDOWN,
                false,
            ),
        )
        assertEquals(
            "이어서 하기",
            primaryTimerActionLabel(TimerState(status = TimerStatus.PAUSED), TimerMode.COUNTDOWN, false),
        )
    }
}
