package dev.stint.app.ui

import dev.stint.shared.ClientController
import dev.stint.shared.ClientState
import dev.stint.shared.ConnectionStatus
import dev.stint.shared.Subject
import dev.stint.shared.TimerMode
import dev.stint.shared.TimerPhase
import dev.stint.shared.TimerState
import dev.stint.shared.TimerStatus

internal data class TimeDialSession(
    val interactionKey: String,
    val valueMinutes: Int,
    val minimumMinutes: Int,
    val maximumMinutes: Int,
    val enabled: Boolean,
)

internal fun timeDialSession(state: ClientState, subject: Subject?, selectedMode: TimerMode): TimeDialSession {
    val timer = state.snapshot?.timer ?: TimerState()
    val idle = timer.status == TimerStatus.IDLE
    val mode = if (idle) selectedMode else timer.mode
    val target = if (idle) subject?.focusSeconds ?: timer.targetSeconds else timer.targetSeconds
    val maximum = when {
        idle || timer.phase == TimerPhase.FOCUS -> 240
        timer.phase == TimerPhase.SHORT_BREAK -> 60
        else -> 120
    }
    // The next whole minute must remain strictly greater than the exact stored elapsed time.
    val minimum = if (idle) 1 else (timer.accumulatedMillis.coerceAtLeast(0) / 60_000L + 1)
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return TimeDialSession(
        interactionKey = listOf(state.snapshot?.revision, subject?.id, timer.id, timer.status,
            timer.phase, mode, target).joinToString("|"),
        valueMinutes = ((target.coerceAtLeast(0).toLong() + 59) / 60).toInt(),
        minimumMinutes = minimum,
        maximumMinutes = maximum,
        enabled = subject != null && !subject.archived && state.snapshot != null &&
            state.connection == ConnectionStatus.CONNECTED && !state.working &&
            mode == TimerMode.COUNTDOWN && timer.status != TimerStatus.RUNNING && minimum <= maximum &&
            (idle || timer.subjectId == subject.id),
    )
}

/** Resolve against the controller's latest state, not the frame at which a long drag began. */
internal fun commitTimeDial(
    controller: ClientController,
    subjectId: String?,
    selectedMode: TimerMode,
    minutes: Int,
    interactionKey: String,
): Boolean {
    val latest = controller.state.value
    val snapshot = latest.snapshot ?: return false
    val subject = snapshot.subjects.firstOrNull { it.id == subjectId } ?: return false
    val session = timeDialSession(latest, subject, selectedMode)
    if (!session.enabled || session.interactionKey != interactionKey ||
        minutes !in session.minimumMinutes..session.maximumMinutes) return false
    if (snapshot.timer.status == TimerStatus.IDLE) {
        controller.setSubjectFocusDuration(subject.id, minutes * 60, snapshot.revision)
    } else {
        controller.setTimerDuration(minutes * 60, snapshot.revision)
    }
    return true
}
