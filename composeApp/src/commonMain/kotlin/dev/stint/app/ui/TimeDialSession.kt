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
    val valueSeconds: Int,
    val minimumSeconds: Int,
    val maximumSeconds: Int,
    val enabled: Boolean,
    val targetOffsetSeconds: Int,
)

internal fun timeDialSession(state: ClientState, subject: Subject?, selectedMode: TimerMode): TimeDialSession {
    val timer = state.snapshot?.timer ?: TimerState()
    val idle = timer.status == TimerStatus.IDLE
    val mode = if (idle) selectedMode else timer.mode
    val target = if (idle) subject?.focusSeconds ?: timer.targetSeconds else timer.targetSeconds
    val maximum = when {
        idle || timer.phase == TimerPhase.FOCUS -> 14_400
        timer.phase == TimerPhase.SHORT_BREAK -> 3_600
        else -> 7_200
    }
    // A paused readout edits the seconds actually shown; converting back retains fractional elapsed.
    val offset = if (idle) 0 else (timer.accumulatedMillis.coerceAtLeast(0) / 1000L)
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val minimum = 1
    val maximumRemaining = maximum - offset
    return TimeDialSession(
        interactionKey = listOf(state.snapshot?.revision, subject?.id, timer.id, timer.status,
            timer.phase, mode, target).joinToString("|"),
        valueSeconds = (target - offset).coerceAtLeast(0),
        minimumSeconds = minimum,
        maximumSeconds = maximumRemaining,
        targetOffsetSeconds = offset,
        enabled = subject != null && !subject.archived && state.snapshot != null &&
            state.connection == ConnectionStatus.CONNECTED && !state.working &&
            mode == TimerMode.COUNTDOWN && timer.status != TimerStatus.RUNNING && minimum <= maximumRemaining &&
            (idle || timer.subjectId == subject.id),
    )
}

/** Resolve against the controller's latest state, not the frame at which a long drag began. */
internal fun commitTimeDial(
    controller: ClientController,
    subjectId: String?,
    selectedMode: TimerMode,
    seconds: Int,
    interactionKey: String,
): Boolean {
    val latest = controller.state.value
    val snapshot = latest.snapshot ?: return false
    val subject = snapshot.subjects.firstOrNull { it.id == subjectId } ?: return false
    val session = timeDialSession(latest, subject, selectedMode)
    if (!session.enabled || session.interactionKey != interactionKey ||
        seconds !in session.minimumSeconds..session.maximumSeconds) return false
    val targetSeconds = seconds + session.targetOffsetSeconds
    if (snapshot.timer.status == TimerStatus.IDLE) {
        controller.setSubjectFocusDuration(subject.id, targetSeconds, snapshot.revision)
    } else {
        controller.setTimerDuration(targetSeconds, snapshot.revision)
    }
    return true
}
