package dev.stint.server

import dev.stint.shared.FocusSession
import dev.stint.shared.Subject
import dev.stint.shared.TimerMode
import dev.stint.shared.TimerPhase
import dev.stint.shared.TimerState
import dev.stint.shared.TimerStatus
import java.util.UUID

data class Settlement(
    val timer: TimerState,
    val session: FocusSession? = null,
)

object TimerEngine {
    fun elapsedMillis(timer: TimerState, nowEpochMs: Long): Long {
        val runningSince = timer.runningSinceEpochMs
        val running = if (timer.status == TimerStatus.RUNNING && runningSince != null) {
            (nowEpochMs - runningSince).coerceAtLeast(0)
        } else {
            0L
        }
        return timer.accumulatedMillis + running
    }

    fun pause(timer: TimerState, nowEpochMs: Long): TimerState {
        require(timer.status == TimerStatus.RUNNING) { "Only a running timer can be paused" }
        return timer.copy(
            status = TimerStatus.PAUSED,
            accumulatedMillis = elapsedMillis(timer, nowEpochMs),
            runningSinceEpochMs = null,
        )
    }

    fun resume(timer: TimerState, nowEpochMs: Long, deviceId: String, deviceName: String): TimerState {
        require(timer.status == TimerStatus.PAUSED) { "Only a paused timer can be resumed" }
        return timer.copy(
            status = TimerStatus.RUNNING,
            runningSinceEpochMs = nowEpochMs,
            controllingDeviceId = deviceId,
            controllingDeviceName = deviceName,
        )
    }

    fun setDuration(timer: TimerState, targetSeconds: Int): TimerState {
        require(timer.status == TimerStatus.PAUSED) { "Only a paused timer duration can be changed" }
        require(timer.mode == TimerMode.COUNTDOWN) { "Only a countdown duration can be changed" }
        require(targetSeconds in 1..14_400) { "targetSeconds must be between 1 and 14400" }
        require(targetSeconds * 1000L > timer.accumulatedMillis) {
            "The new duration must be longer than the elapsed time"
        }
        return timer.copy(targetSeconds = targetSeconds)
    }

    fun finish(
        timer: TimerState,
        subject: Subject?,
        nowEpochMs: Long,
        deviceName: String,
        newId: () -> String = { UUID.randomUUID().toString() },
    ): Settlement {
        val elapsedMillis = elapsedMillis(timer, nowEpochMs)
        val rawDurationSeconds = (elapsedMillis / 1000L).toInt()
        val durationSeconds = if (timer.mode == TimerMode.COUNTDOWN) {
            rawDurationSeconds.coerceAtMost(timer.targetSeconds)
        } else {
            rawDurationSeconds
        }
        val subjectId = timer.subjectId
        val startedAt = timer.startedAtEpochMs
        val session = if (
            timer.phase == TimerPhase.FOCUS &&
            subjectId != null &&
            startedAt != null &&
            durationSeconds > 0
        ) {
            FocusSession(
                id = timer.id ?: newId(),
                subjectId = subjectId,
                startedAtEpochMs = startedAt,
                endedAtEpochMs = nowEpochMs,
                durationSeconds = durationSeconds,
                mode = timer.mode,
                deviceName = deviceName,
            )
        } else null

        return Settlement(timer = idleFocus(timer, subject), session = session)
    }

    fun settleExpired(
        timer: TimerState,
        subject: Subject,
        nowEpochMs: Long,
        newId: () -> String = { UUID.randomUUID().toString() },
    ): Settlement? {
        if (timer.status != TimerStatus.RUNNING || timer.mode != TimerMode.COUNTDOWN) return null
        val runningSince = timer.runningSinceEpochMs ?: return null
        val remainingMillis = timer.targetSeconds * 1000L - timer.accumulatedMillis
        val deadline = runningSince + remainingMillis.coerceAtLeast(0)
        if (nowEpochMs < deadline) return null

        if (timer.phase != TimerPhase.FOCUS) {
            return Settlement(timer = idleFocus(timer, subject))
        }

        val completedCount = timer.completedFocusCount + 1
        val breakPhase = if (completedCount % subject.longBreakEvery == 0) {
            TimerPhase.LONG_BREAK
        } else {
            TimerPhase.SHORT_BREAK
        }
        val breakSeconds = if (breakPhase == TimerPhase.LONG_BREAK) {
            subject.longBreakSeconds
        } else {
            subject.shortBreakSeconds
        }
        val session = FocusSession(
            id = timer.id ?: newId(),
            subjectId = subject.id,
            startedAtEpochMs = timer.startedAtEpochMs ?: (deadline - timer.targetSeconds * 1000L),
            endedAtEpochMs = deadline,
            durationSeconds = timer.targetSeconds,
            mode = timer.mode,
            deviceName = timer.controllingDeviceName.orEmpty(),
        )
        val breakEnd = deadline + breakSeconds * 1000L
        val nextTimer = if (nowEpochMs >= breakEnd) {
            idleFocus(timer.copy(completedFocusCount = completedCount), subject)
        } else {
            timer.copy(
                status = TimerStatus.RUNNING,
                phase = breakPhase,
                mode = TimerMode.COUNTDOWN,
                targetSeconds = breakSeconds,
                accumulatedMillis = 0,
                runningSinceEpochMs = deadline,
                startedAtEpochMs = deadline,
                completedFocusCount = completedCount,
            )
        }
        return Settlement(timer = nextTimer, session = session)
    }

    fun idleFocus(timer: TimerState, subject: Subject?): TimerState = timer.copy(
        status = TimerStatus.IDLE,
        phase = TimerPhase.FOCUS,
        mode = TimerMode.COUNTDOWN,
        targetSeconds = subject?.focusSeconds ?: timer.targetSeconds,
        accumulatedMillis = 0,
        runningSinceEpochMs = null,
        startedAtEpochMs = null,
        controllingDeviceId = null,
        controllingDeviceName = null,
    )
}
