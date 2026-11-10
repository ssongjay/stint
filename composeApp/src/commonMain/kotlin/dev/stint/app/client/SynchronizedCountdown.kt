package dev.stint.app.client

import dev.stint.shared.ClientState
import dev.stint.shared.TimerMode
import dev.stint.shared.TimerPhase
import dev.stint.shared.TimerStatus

/**
 * The platform-neutral facts needed to recognize one synchronized countdown.
 * Connection health, mutation work, overdue handling, and OS scheduling remain consumer policies.
 */
internal data class SynchronizedCountdown(
    val key: String,
    val timerId: String,
    val subjectId: String?,
    val subjectName: String,
    val phase: TimerPhase,
    val startedAtEpochMs: Long,
    val runningSinceEpochMs: Long,
    val accumulatedMillis: Long,
    val targetSeconds: Int,
    val deadlineEpochMs: Long,
)

/** Returns timer facts only; callers decide whether the current state is safe to deliver or schedule. */
internal fun ClientState.synchronizedCountdownOrNull(): SynchronizedCountdown? {
    val snapshot = this.snapshot ?: return null
    val timer = snapshot.timer
    if (timer.status != TimerStatus.RUNNING || timer.mode != TimerMode.COUNTDOWN) return null
    val timerId = timer.id ?: return null
    val runningSince = timer.runningSinceEpochMs ?: return null
    val startedAt = timer.startedAtEpochMs ?: return null
    val remainingAtResume = (
        timer.targetSeconds.coerceAtLeast(0).toLong() * 1_000L -
            timer.accumulatedMillis.coerceAtLeast(0L)
        ).coerceAtLeast(0L)
    val deadline = addSaturated(runningSince, remainingAtResume)
    return SynchronizedCountdown(
        key = "$timerId|${timer.phase.name}|$startedAt",
        timerId = timerId,
        subjectId = timer.subjectId,
        subjectName = snapshot.subjects.firstOrNull { it.id == timer.subjectId }?.name.orEmpty(),
        phase = timer.phase,
        startedAtEpochMs = startedAt,
        runningSinceEpochMs = runningSince,
        accumulatedMillis = timer.accumulatedMillis,
        targetSeconds = timer.targetSeconds,
        deadlineEpochMs = deadline,
    )
}

private fun addSaturated(left: Long, right: Long): Long =
    if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
