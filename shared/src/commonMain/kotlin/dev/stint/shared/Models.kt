package dev.stint.shared

import kotlinx.serialization.Serializable

@Serializable
enum class TimerMode { COUNTDOWN, STOPWATCH }

@Serializable
enum class TimerPhase { FOCUS, SHORT_BREAK, LONG_BREAK }

@Serializable
enum class TimerStatus { IDLE, RUNNING, PAUSED }

@Serializable
enum class TimerAction { START, PAUSE, RESUME, FINISH, SKIP_BREAK }

@Serializable
data class Subject(
    val id: String,
    val name: String,
    val colorHex: String = "46735D",
    val focusSeconds: Int = 1500,
    val shortBreakSeconds: Int = 300,
    val longBreakSeconds: Int = 900,
    val longBreakEvery: Int = 4,
    val archived: Boolean = false,
)

@Serializable
data class TimerState(
    val id: String? = null,
    val subjectId: String? = null,
    val status: TimerStatus = TimerStatus.IDLE,
    val phase: TimerPhase = TimerPhase.FOCUS,
    val mode: TimerMode = TimerMode.COUNTDOWN,
    val targetSeconds: Int = 1500,
    val accumulatedMillis: Long = 0,
    val runningSinceEpochMs: Long? = null,
    val startedAtEpochMs: Long? = null,
    val completedFocusCount: Int = 0,
    val controllingDeviceId: String? = null,
    val controllingDeviceName: String? = null,
)

@Serializable
data class FocusSession(
    val id: String,
    val subjectId: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val durationSeconds: Int,
    val mode: TimerMode,
    val memo: String = "",
    val deviceName: String = "",
)

@Serializable
data class DeviceInfo(
    val id: String,
    val name: String,
    val lastSeenAtEpochMs: Long,
)

@Serializable
data class Snapshot(
    val serverNowEpochMs: Long,
    val revision: Long = 0,
    val subjects: List<Subject> = emptyList(),
    val timer: TimerState = TimerState(),
    val sessions: List<FocusSession> = emptyList(),
    val devices: List<DeviceInfo> = emptyList(),
)

@Serializable
data class MutationContext(
    val commandId: String,
    val expectedRevision: Long,
    val deviceId: String,
    val deviceName: String,
)

@Serializable
data class TimerCommand(
    val context: MutationContext,
    val action: TimerAction,
    val subjectId: String? = null,
    val mode: TimerMode = TimerMode.COUNTDOWN,
    val focusSeconds: Int? = null,
)

@Serializable
data class SaveSubjectRequest(val context: MutationContext, val subject: Subject)

@Serializable
data class SaveMemoRequest(val context: MutationContext, val memo: String)

@Serializable
data class ApiError(val code: String, val message: String, val snapshot: Snapshot? = null)

fun TimerState.elapsedSeconds(serverNowEpochMs: Long): Int {
    val running = if (status == TimerStatus.RUNNING && runningSinceEpochMs != null)
        (serverNowEpochMs - runningSinceEpochMs).coerceAtLeast(0)
    else 0L
    val elapsed = ((accumulatedMillis + running) / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return if (mode == TimerMode.COUNTDOWN) elapsed.coerceAtMost(targetSeconds) else elapsed
}

fun TimerState.remainingSeconds(serverNowEpochMs: Long): Int =
    if (mode == TimerMode.STOPWATCH) 0 else
        (targetSeconds - elapsedSeconds(serverNowEpochMs)).coerceAtLeast(0)
