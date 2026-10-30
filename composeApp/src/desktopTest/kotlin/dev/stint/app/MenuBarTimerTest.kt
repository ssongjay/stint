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
import java.awt.image.MultiResolutionImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MenuBarTimerTest {
    @Test
    fun `paused countdown keeps fractional elapsed and renders total minutes`() {
        val state = state(
            TimerState(
                id = "timer",
                subjectId = "archived",
                status = TimerStatus.PAUSED,
                phase = TimerPhase.FOCUS,
                mode = TimerMode.COUNTDOWN,
                targetSeconds = 7_200,
                accumulatedMillis = 1_800_001,
            ),
            subjects = listOf(Subject("archived", "논문 읽기", archived = true)),
        )

        val model = menuBarTimer(state)

        assertEquals("90:00", model.timeLabel)
        assertEquals("논문 읽기", model.subjectLabel)
        assertEquals("집중 · 일시정지", model.statusLabel)
        assertEquals("계속하기", model.toggleLabel)
        assertTrue(model.progressLabel.contains("경과 30:00 / 120:00"))
        assertTrue(model.canToggle)
        assertTrue(model.canFinish)
        assertFalse(model.canSkipBreak)
    }

    @Test
    fun `running stopwatch uses clock time and break controls`() {
        val state = state(
            TimerState(
                id = "timer",
                subjectId = "study",
                status = TimerStatus.RUNNING,
                phase = TimerPhase.LONG_BREAK,
                mode = TimerMode.STOPWATCH,
                accumulatedMillis = 3_600_000,
                runningSinceEpochMs = 9_000,
            ),
            now = 11_345,
        )

        val model = menuBarTimer(state)

        assertEquals("1:00:02", model.timeLabel)
        assertEquals("경과 1:00:02", model.progressLabel)
        assertEquals("긴 휴식 · 진행 중", model.statusLabel)
        assertEquals("일시정지", model.toggleLabel)
        assertFalse(model.canFinish)
        assertTrue(model.canSkipBreak)
    }

    @Test
    fun `offline cached timer never appears as live time`() {
        val active = state(
            TimerState(status = TimerStatus.RUNNING, runningSinceEpochMs = 5_000),
            now = 10_000,
            connection = ConnectionStatus.OFFLINE,
        )

        val model = menuBarTimer(active)

        assertEquals("Stint", model.title)
        assertEquals("—", model.timeLabel)
        assertFalse(model.renderTime)
        assertTrue(model.disconnected)
        assertEquals("오프라인", model.connectionLabel)
        assertEquals("Stint", model.subjectLabel)
        assertFalse(model.canToggle)
        assertFalse(model.canFinish)
    }

    @Test
    fun `reconnecting cached timer is also withheld until synchronization`() {
        val model = menuBarTimer(
            state(
                TimerState(status = TimerStatus.RUNNING, runningSinceEpochMs = 5_000),
                now = 10_000,
                connection = ConnectionStatus.CONNECTING,
            ),
        )

        assertEquals("Stint", model.title)
        assertEquals("—", model.timeLabel)
        assertEquals("동기화 중", model.connectionLabel)
        assertFalse(model.renderTime)
        assertFalse(model.disconnected)
    }

    @Test
    fun `working timer remains visible but disables mutations`() {
        val model = menuBarTimer(
            state(TimerState(status = TimerStatus.RUNNING), working = true),
        )

        assertTrue(model.renderTime)
        assertEquals("25:00", model.timeLabel)
        assertFalse(model.canToggle)
        assertFalse(model.canFinish)
    }

    @Test
    fun `updates within the same displayed second reuse an equal model`() {
        val timer = TimerState(status = TimerStatus.RUNNING, runningSinceEpochMs = 1_000)

        val early = menuBarTimer(state(timer, now = 1_010))
        val late = menuBarTimer(state(timer, now = 1_990))

        assertEquals(early, late)
        assertEquals("25:00", early.timeLabel)
    }

    @Test
    fun `idle timer shows Stint without exposing actions`() {
        val model = menuBarTimer(state(TimerState(status = TimerStatus.IDLE)))

        assertEquals("Stint", model.title)
        assertFalse(model.renderTime)
        assertFalse(model.canToggle)
        assertFalse(model.canFinish)
        assertFalse(model.canSkipBreak)
    }

    @Test
    fun `missing snapshot describes loading without controls`() {
        val model = menuBarTimer(
            ClientState(
                settings = ConnectionSettings(),
                snapshot = null,
                connection = ConnectionStatus.CONNECTED,
            ),
        )

        assertEquals("불러오는 중", model.connectionLabel)
        assertEquals("Stint", model.title)
        assertFalse(model.renderTime)
        assertFalse(model.canToggle)
    }

    @Test
    fun `renderer reserves countdown width and provides retina variant`() {
        val underTen = menuBarTimer(state(TimerState(status = TimerStatus.PAUSED, targetSeconds = 599)))
        val overTen = menuBarTimer(state(TimerState(status = TimerStatus.PAUSED, targetSeconds = 600)))
        val underHundred = menuBarTimer(state(TimerState(status = TimerStatus.PAUSED, targetSeconds = 5_999)))
        val overHundred = menuBarTimer(state(TimerState(status = TimerStatus.PAUSED, targetSeconds = 6_000)))

        val underTenImage = renderMenuBarTimer(underTen)
        val overTenImage = renderMenuBarTimer(overTen)
        val first = renderMenuBarTimer(underHundred)
        val second = renderMenuBarTimer(overHundred)
        val multi = assertIs<MultiResolutionImage>(first)
        val variants = multi.resolutionVariants

        assertEquals("09:59", underTen.timeLabel)
        assertEquals("10:00", overTen.timeLabel)
        assertEquals(underTenImage.getWidth(null), overTenImage.getWidth(null))
        assertEquals(first.getWidth(null), second.getWidth(null))
        assertEquals(2, variants.size)
        assertEquals(variants[0].getWidth(null) * 2, variants[1].getWidth(null))
        assertEquals(variants[0].getHeight(null) * 2, variants[1].getHeight(null))
        assertEquals(22, variants[0].getHeight(null))
    }

    @Test
    fun `presentation does not mutate client input`() {
        val original = state(TimerState(status = TimerStatus.RUNNING, runningSinceEpochMs = 5_000), now = 10_000)
        val copy = original.copy(snapshot = original.snapshot?.copy())

        menuBarTimer(original)

        assertEquals(copy, original)
    }

    private fun state(
        timer: TimerState,
        subjects: List<Subject> = listOf(Subject("study", "개발하기")),
        now: Long = 10_000,
        connection: ConnectionStatus = ConnectionStatus.CONNECTED,
        working: Boolean = false,
    ): ClientState = ClientState(
        settings = ConnectionSettings(),
        snapshot = Snapshot(
            serverNowEpochMs = now,
            subjects = subjects,
            timer = timer.copy(subjectId = timer.subjectId ?: subjects.firstOrNull()?.id),
        ),
        serverNowEpochMs = now,
        connection = connection,
        working = working,
    )
}
