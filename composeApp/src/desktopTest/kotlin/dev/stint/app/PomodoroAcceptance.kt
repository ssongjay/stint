package dev.stint.app

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.stint.app.ui.StintApp
import dev.stint.shared.ClientController
import dev.stint.shared.ClientState
import dev.stint.shared.ConnectionSettings
import dev.stint.shared.ConnectionStatus
import dev.stint.shared.Snapshot
import dev.stint.shared.Subject
import dev.stint.shared.TimerClockStyle
import dev.stint.shared.TimerMode
import dev.stint.shared.TimerPhase
import dev.stint.shared.TimerState
import dev.stint.shared.TimerStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Isolated visual and interaction fixture for the synchronized Pomodoro preferences.
 *
 * It uses the production workbench UI but never opens production storage or a network connection.
 * The two switches update an in-memory subject and increment the fake server revision so a reviewer
 * can exercise the same state round-trip as the Mac and foldable clients.
 */
object PomodoroAcceptance {
    @JvmStatic
    fun main(args: Array<String>) = application {
        val configuration = remember(args.toList()) { AcceptanceConfiguration.parse(args) }
        val controller = remember(configuration) {
            FakePomodoroController(configuration.ackDelayMillis, configuration.offline)
        }
        val windowState = rememberWindowState(
            size = DpSize(configuration.width.dp, configuration.height.dp),
        )

        Window(
            onCloseRequest = {
                controller.close()
                exitApplication()
            },
            state = windowState,
            title = "Stint Pomodoro QA",
        ) {
            StintApp(controller)
        }
    }

    private data class AcceptanceConfiguration(
        val width: Int,
        val height: Int,
        val ackDelayMillis: Long,
        val offline: Boolean,
    ) {
        companion object {
            fun parse(args: Array<String>): AcceptanceConfiguration {
                val values = args.mapNotNull { argument ->
                    argument.removePrefix("--").split('=', limit = 2)
                        .takeIf { it.size == 2 }
                        ?.let { it[0] to it[1] }
                }.toMap()
                val width = values["width"]?.toIntOrNull() ?: DEFAULT_WIDTH
                val height = values["height"]?.toIntOrNull() ?: DEFAULT_HEIGHT
                val ackDelayMillis = values["ack-delay-ms"]?.toLongOrNull() ?: 0L
                val offline = values["offline"]?.toBooleanStrictOrNull() ?: false
                require(width in 360..2_400) { "width must be between 360 and 2400" }
                require(height in 640..1_800) { "height must be between 640 and 1800" }
                require(ackDelayMillis in 0L..10_000L) { "ack-delay-ms must be between 0 and 10000" }
                return AcceptanceConfiguration(
                    width = width,
                    height = height,
                    ackDelayMillis = ackDelayMillis,
                    offline = offline,
                )
            }
        }
    }

    private const val DEFAULT_WIDTH = 1_180
    private const val DEFAULT_HEIGHT = 880
}

private class FakePomodoroController(
    private val ackDelayMillis: Long,
    offline: Boolean,
) : ClientController {
    private val now = System.currentTimeMillis()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutable = MutableStateFlow(
        ClientState(
            settings = ConnectionSettings(
                baseUrl = "https://stint.example",
                token = "isolated-qa-token",
                deviceId = "pomodoro-qa",
                deviceName = "Pomodoro QA",
            ),
            snapshot = Snapshot(
                serverNowEpochMs = now,
                revision = 1,
                subjects = listOf(
                    Subject(
                        id = SUBJECT_ID,
                        name = "개발 공부",
                        focusSeconds = 25 * 60,
                        shortBreakSeconds = 5 * 60,
                        longBreakSeconds = 15 * 60,
                        pomodoroEnabled = true,
                        autoStartBreak = true,
                    ),
                ),
                timer = TimerState(subjectId = SUBJECT_ID, targetSeconds = 25 * 60),
            ),
            serverNowEpochMs = now,
            connection = if (offline) ConnectionStatus.OFFLINE else ConnectionStatus.CONNECTED,
        ),
    )
    override val state: StateFlow<ClientState> = mutable

    override fun saveSubject(subject: Subject) {
        require(subject.id == SUBJECT_ID) { "The fixture may only update its isolated subject" }
        mutate { snapshot ->
            snapshot.copy(subjects = snapshot.subjects.map { if (it.id == subject.id) subject else it })
        }
    }

    override fun start(subjectId: String, mode: TimerMode, focusSeconds: Int?) {
        mutate { snapshot ->
            val startedAt = System.currentTimeMillis()
            snapshot.copy(
                timer = TimerState(
                    id = "pomodoro-qa-timer",
                    subjectId = subjectId,
                    status = TimerStatus.RUNNING,
                    phase = TimerPhase.FOCUS,
                    mode = mode,
                    targetSeconds = focusSeconds ?: snapshot.subjects.first().focusSeconds,
                    runningSinceEpochMs = startedAt,
                    startedAtEpochMs = startedAt,
                    controllingDeviceId = "pomodoro-qa",
                    controllingDeviceName = "Pomodoro QA",
                ),
            )
        }
    }

    override fun pause() = mutateTimer { timer ->
        timer.copy(status = TimerStatus.PAUSED, runningSinceEpochMs = null)
    }

    override fun resume() = mutateTimer { timer ->
        timer.copy(status = TimerStatus.RUNNING, runningSinceEpochMs = System.currentTimeMillis())
    }

    override fun finish() = mutateTimer { timer ->
        TimerState(subjectId = timer.subjectId, targetSeconds = timer.targetSeconds)
    }

    override fun skipBreak() = finish()

    override fun setTimerDuration(targetSeconds: Int, expectedRevision: Long?) = mutateTimer { timer ->
        timer.copy(targetSeconds = targetSeconds)
    }

    override fun setSubjectFocusDuration(subjectId: String, targetSeconds: Int, expectedRevision: Long) {
        val subject = requireNotNull(state.value.snapshot?.subjects?.firstOrNull { it.id == subjectId })
        saveSubject(subject.copy(focusSeconds = targetSeconds))
    }

    override fun saveMemo(sessionId: String, memo: String) = Unit
    override fun refresh() = Unit
    override fun dismissMessage() = Unit
    override fun configure(baseUrl: String, token: String) = Unit

    override fun setClockStyle(style: TimerClockStyle) {
        mutable.value = mutable.value.copy(clockStyle = style)
    }

    override fun close() = scope.cancel()

    private fun mutateTimer(transform: (TimerState) -> TimerState) = mutate { snapshot ->
        snapshot.copy(timer = transform(snapshot.timer))
    }

    private fun mutate(transform: (Snapshot) -> Snapshot) {
        val current = mutable.value
        if (current.working || current.connection != ConnectionStatus.CONNECTED) return
        mutable.value = current.copy(working = true)
        scope.launch {
            delay(ackDelayMillis)
            val pending = mutable.value
            val snapshot = requireNotNull(pending.snapshot)
            val updated = transform(snapshot).copy(
                serverNowEpochMs = System.currentTimeMillis(),
                revision = snapshot.revision + 1,
            )
            mutable.value = pending.copy(
                snapshot = updated,
                serverNowEpochMs = updated.serverNowEpochMs,
                working = false,
            )
        }
    }

    private companion object {
        const val SUBJECT_ID = "pomodoro-qa-subject"
    }
}
