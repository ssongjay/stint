package dev.stint.app

import androidx.compose.ui.window.application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.stint.shared.ClientController
import dev.stint.shared.ClientState
import dev.stint.shared.ConnectionSettings
import dev.stint.shared.ConnectionStatus
import dev.stint.shared.FocusSession
import dev.stint.shared.Snapshot
import dev.stint.shared.Subject
import dev.stint.shared.TimerClockStyle
import dev.stint.shared.TimerMode
import dev.stint.shared.TimerPhase
import dev.stint.shared.TimerState
import dev.stint.shared.TimerStatus
import java.awt.Point
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Isolated visual fixture: never opens production settings, storage, or network connections. */
object TimerPopoverAcceptance {
    @JvmStatic
    fun main(args: Array<String>) = application {
        val controller = remember { FakePopoverController(args.contains("--active")) }
        var selectedSubjectId by remember { mutableStateOf<String?>("qa-read") }
        DesktopTimerPopover(
            controller = controller,
            anchor = Point(600, 60),
            onDismiss = ::exitApplication,
            onOpen = {},
            onQuit = ::exitApplication,
            selectedSubjectId = selectedSubjectId,
            onSelectSubject = { selectedSubjectId = it },
        )
    }
}

private class FakePopoverController(active: Boolean) : ClientController {
    private val subject = Subject("qa-subject", "개발 공부", focusSeconds = 900)
    private val subjects = listOf(subject, Subject("qa-read", "읽기", focusSeconds = 1_500),
        Subject("qa-project", "아주 긴 이름의 프로젝트를 차분하게 진행하기", focusSeconds = 2_700),
        Subject("qa-archived", "보관한 주제", archived = true))
    private val now = System.currentTimeMillis()
    private val mutable = MutableStateFlow(clientState(if (active) pausedTimer() else TimerState(subjectId = subject.id)))
    override val state: StateFlow<ClientState> = mutable

    override fun start(subjectId: String, mode: TimerMode, focusSeconds: Int?) = update(
        TimerState("qa", subjectId, TimerStatus.RUNNING, TimerPhase.FOCUS, mode,
            focusSeconds ?: subjects.first { it.id == subjectId }.focusSeconds,
            runningSinceEpochMs = now, startedAtEpochMs = now),
    )
    override fun pause() = update(mutable.value.snapshot!!.timer.copy(
        status = TimerStatus.PAUSED, runningSinceEpochMs = null,
    ))
    override fun resume() = update(mutable.value.snapshot!!.timer.copy(
        status = TimerStatus.RUNNING, runningSinceEpochMs = now,
    ))
    override fun finish() = update(TimerState(subjectId = subject.id))
    override fun skipBreak() = finish()
    override fun setTimerDuration(targetSeconds: Int, expectedRevision: Long?) = Unit
    override fun setSubjectFocusDuration(subjectId: String, targetSeconds: Int, expectedRevision: Long) = Unit
    override fun saveSubject(subject: Subject) = Unit
    override fun saveMemo(sessionId: String, memo: String) = Unit
    override fun refresh() = Unit
    override fun dismissMessage() = Unit
    override fun setClockStyle(style: TimerClockStyle) = Unit
    override fun configure(baseUrl: String, token: String) = Unit
    override fun close() = Unit

    private fun pausedTimer() = TimerState(
        id = "qa", subjectId = subject.id, status = TimerStatus.PAUSED,
        phase = TimerPhase.FOCUS, mode = TimerMode.COUNTDOWN,
        targetSeconds = 900, accumulatedMillis = 18_000,
    )
    private fun update(timer: TimerState) {
        val current = mutable.value
        mutable.value = current.copy(snapshot = current.snapshot!!.copy(timer = timer))
    }
    private fun clientState(timer: TimerState) = ClientState(
        settings = ConnectionSettings(deviceName = "Popover QA"),
        snapshot = Snapshot(now, 1, subjects, timer, emptyList<FocusSession>()),
        serverNowEpochMs = now,
        connection = ConnectionStatus.CONNECTED,
    )
}
