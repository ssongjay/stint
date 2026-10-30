package dev.stint.app

import dev.stint.shared.ClientState
import dev.stint.shared.ConnectionStatus
import dev.stint.shared.TimerMode
import dev.stint.shared.TimerPhase
import dev.stint.shared.TimerStatus
import dev.stint.shared.elapsedMillis

internal object TimerNotificationPolicy {
    const val ACTION_PAUSE = "dev.stint.app.PAUSE"
    const val ACTION_RESUME = "dev.stint.app.RESUME"
    const val ACTION_FINISH = "dev.stint.app.FINISH"
    const val ACTION_SKIP_BREAK = "dev.stint.app.SKIP_BREAK"

    const val EXTRA_TIMER_ID = "dev.stint.app.extra.TIMER_ID"
    const val EXTRA_REVISION = "dev.stint.app.extra.REVISION"
    const val EXTRA_PHASE = "dev.stint.app.extra.PHASE"
    const val EXTRA_STATUS = "dev.stint.app.extra.STATUS"

    // Public platform value from Android's Notification API. The compileSdk 36.0
    // stubs expose the promoted flag but not the 36.1 convenience constant yet.
    const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
}

internal data class NotificationCommandGuard(
    val timerId: String,
    val revision: Long,
    val phase: TimerPhase,
    val status: TimerStatus,
)

internal data class TimerNotificationPresentation(
    val timeText: String,
    val contentText: String,
    val usesChronometer: Boolean,
    val chronometerCountDown: Boolean,
    val whenEpochMs: Long,
    val timeoutAfterMs: Long?,
    val progressMax: Int,
    val progress: Int,
    val liveUpdateRequested: Boolean,
    val actionsEnabled: Boolean,
)

internal fun notificationCommandGuard(state: ClientState): NotificationCommandGuard? {
    val snapshot = state.snapshot ?: return null
    val timer = snapshot.timer
    val timerId = timer.id ?: return null
    if (timer.status == TimerStatus.IDLE) return null
    return NotificationCommandGuard(timerId, snapshot.revision, timer.phase, timer.status)
}

internal fun notificationCommandMatches(
    action: String?,
    guard: NotificationCommandGuard?,
    state: ClientState,
): Boolean {
    if (guard == null || state.connection != ConnectionStatus.CONNECTED || state.working) return false
    val snapshot = state.snapshot ?: return false
    val timer = snapshot.timer
    if (timer.status == TimerStatus.IDLE ||
        snapshot.revision != guard.revision ||
        timer.id != guard.timerId ||
        timer.phase != guard.phase ||
        timer.status != guard.status
    ) return false

    return when (action) {
        TimerNotificationPolicy.ACTION_PAUSE -> timer.status == TimerStatus.RUNNING
        TimerNotificationPolicy.ACTION_RESUME -> timer.status == TimerStatus.PAUSED
        TimerNotificationPolicy.ACTION_FINISH -> timer.phase == TimerPhase.FOCUS
        TimerNotificationPolicy.ACTION_SKIP_BREAK -> timer.phase != TimerPhase.FOCUS
        else -> false
    }
}

internal fun timerNotificationPresentation(
    state: ClientState,
    localNowEpochMs: Long,
): TimerNotificationPresentation? {
    val timer = state.snapshot?.timer ?: return null
    if (timer.status == TimerStatus.IDLE || timer.id == null) return null

    val live = state.connection == ConnectionStatus.CONNECTED
    if (!live) {
        val connectionText = when (state.connection) {
            ConnectionStatus.SETUP -> "연결 설정이 필요해요"
            ConnectionStatus.CONNECTING -> "최신 상태를 동기화하고 있어요"
            ConnectionStatus.OFFLINE -> "오프라인 · 앱을 열어 연결을 확인해 주세요"
            ConnectionStatus.CONNECTED -> error("Handled above")
        }
        return TimerNotificationPresentation(
            timeText = "—",
            contentText = connectionText,
            usesChronometer = false,
            chronometerCountDown = false,
            whenEpochMs = 0L,
            timeoutAfterMs = null,
            progressMax = 0,
            progress = 0,
            liveUpdateRequested = false,
            actionsEnabled = false,
        )
    }

    val elapsedMillis = timer.elapsedMillis(state.serverNowEpochMs)
    val elapsedSeconds = (elapsedMillis / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val running = timer.status == TimerStatus.RUNNING
    val actionsEnabled = !state.working

    if (timer.mode == TimerMode.STOPWATCH) {
        val localStart = subtractSaturated(localNowEpochMs, elapsedMillis)
        val time = formatNotificationDuration(elapsedSeconds)
        return TimerNotificationPresentation(
            timeText = time,
            contentText = if (running) "경과 $time" else "일시정지 · $time 경과",
            usesChronometer = running,
            chronometerCountDown = false,
            whenEpochMs = localStart,
            timeoutAfterMs = null,
            progressMax = 0,
            progress = 0,
            liveUpdateRequested = running,
            actionsEnabled = actionsEnabled,
        )
    }

    val targetMillis = timer.targetSeconds.coerceAtLeast(0).toLong() * 1_000L
    val remainingMillis = (targetMillis - elapsedMillis).coerceAtLeast(0L)
    val remainingSeconds = ((remainingMillis + 999L) / 1_000L)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
    val time = formatNotificationDuration(remainingSeconds)
    return TimerNotificationPresentation(
        timeText = time,
        contentText = if (running) "$time 남음" else "일시정지 · $time 남음",
        usesChronometer = running,
        chronometerCountDown = running,
        whenEpochMs = addSaturated(localNowEpochMs, remainingMillis),
        timeoutAfterMs = remainingMillis.takeIf { running && it > 0L }?.let(::addOneSecondSaturated),
        progressMax = timer.targetSeconds.coerceAtLeast(0),
        progress = elapsedSeconds.coerceIn(0, timer.targetSeconds.coerceAtLeast(0)),
        liveUpdateRequested = running && remainingMillis > 0L,
        actionsEnabled = actionsEnabled,
    )
}

private fun formatNotificationDuration(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    val minutes = safe / 60
    return "${minutes.toString().padStart(2, '0')}:${(safe % 60).toString().padStart(2, '0')}"
}

private fun addSaturated(left: Long, right: Long): Long =
    if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

private fun subtractSaturated(left: Long, right: Long): Long =
    if (right > 0L && left < Long.MIN_VALUE + right) Long.MIN_VALUE else left - right

private fun addOneSecondSaturated(value: Long): Long =
    if (value > Long.MAX_VALUE - 1_000L) Long.MAX_VALUE else value + 1_000L
