package dev.stint.shared

import kotlinx.serialization.Serializable

@Serializable
enum class TimerMode { COUNTDOWN, STOPWATCH }

@Serializable
enum class TimerPhase { FOCUS, SHORT_BREAK, LONG_BREAK }

@Serializable
enum class TimerStatus { IDLE, RUNNING, PAUSED }

@Serializable
enum class TimerAction { START, PAUSE, RESUME, FINISH, SKIP_BREAK, SET_DURATION }

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
    val pomodoroEnabled: Boolean = true,
    val autoStartBreak: Boolean = true,
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

/** 완료된 집중의 시간 기록이다. 진행 중인 [TimerState]와 구분하며 회고는 완료 후에도 편집할 수 있다. */
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
    val recentMemos: List<String> = emptyList(),
)

/**
 * 모든 변경 요청에 붙는 동시성 계약이다.
 * commandId는 같은 요청의 재실행을 막고, expectedRevision은 다른 기기의 선행 변경을 덮어쓰지 않게 한다.
 */
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
    val targetSeconds: Int? = null,
)

@Serializable
data class SaveSubjectRequest(
    val context: MutationContext,
    val subject: Subject,
    /**
     * Older clients do not send the Pomodoro fields. Keeping this false for a
     * missing field prevents an unrelated subject edit from resetting settings
     * that were saved by a newer client.
     */
    val includesFlowSettings: Boolean = false,
)

@Serializable
data class SaveMemoRequest(val context: MutationContext, val memo: String)

@Serializable
data class ApiError(val code: String, val message: String, val snapshot: Snapshot? = null)

/** 저장된 누적 시간과 현재 실행 구간을 합친다. 서버 시각을 입력받아 기기 시계 차이를 계산에서 제외한다. */
fun TimerState.elapsedMillis(serverNowEpochMs: Long): Long {
    val running = if (status == TimerStatus.RUNNING && runningSinceEpochMs != null)
        (serverNowEpochMs - runningSinceEpochMs).coerceAtLeast(0)
    else 0L
    val stored = accumulatedMillis.coerceAtLeast(0)
    val elapsed = if (stored > Long.MAX_VALUE - running) Long.MAX_VALUE else stored + running
    return if (mode == TimerMode.COUNTDOWN) {
        elapsed.coerceAtMost(targetSeconds.coerceAtLeast(0) * 1000L)
    } else {
        elapsed
    }
}

fun TimerState.elapsedSeconds(serverNowEpochMs: Long): Int =
    (elapsedMillis(serverNowEpochMs) / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

fun TimerState.remainingSeconds(serverNowEpochMs: Long): Int =
    if (mode == TimerMode.STOPWATCH) 0 else
        (targetSeconds - elapsedSeconds(serverNowEpochMs)).coerceAtLeast(0)
