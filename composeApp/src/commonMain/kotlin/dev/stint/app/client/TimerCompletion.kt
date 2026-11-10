package dev.stint.app.client

import dev.stint.shared.ClientState
import dev.stint.shared.ConnectionStatus
import dev.stint.shared.FocusSession
import dev.stint.shared.TimerMode
import dev.stint.shared.TimerPhase
import dev.stint.shared.TimerStatus

internal data class TimerCompletionEvent(
    val key: String,
    val phase: TimerPhase,
    val subjectName: String,
    val title: String,
    val body: String,
)

/**
 * Turns the synchronized timer stream into one-shot, natural countdown completion events.
 *
 * A newly created tracker and every reconnect use the first connected state as a baseline. This
 * prevents an overdue cached timer from producing a stale alert when the app starts or reconnects.
 */
internal class TimerCompletionTracker(
    initialDeliveredKeys: Set<String> = emptySet(),
) {
    private val delivered = linkedSetOf<String>()
    private var connectionIdentity: ConnectionIdentity? = null
    private var connectedBaselineSeen = false
    private var tracked: SynchronizedCountdown? = null

    init {
        initialDeliveredKeys.toList().takeLast(MAX_DELIVERED_KEYS).forEach(delivered::add)
    }

    fun update(state: ClientState): TimerCompletionEvent? {
        val identity = ConnectionIdentity(state.settings.baseUrl, state.settings.token)
        if (identity != connectionIdentity) {
            connectionIdentity = identity
            connectedBaselineSeen = false
            tracked = null
        }

        val snapshot = state.snapshot
        if (state.connection != ConnectionStatus.CONNECTED || snapshot == null) {
            connectedBaselineSeen = false
            tracked = null
            return null
        }

        val now = state.serverNowEpochMs
        val current = state.synchronizedCountdownOrNull()
        if (!connectedBaselineSeen) {
            connectedBaselineSeen = true
            tracked = current?.takeIf { it.deadlineEpochMs > now && it.key !in delivered }
            return null
        }

        val previous = tracked
        if (previous == null) {
            tracked = current?.takeIf { it.deadlineEpochMs > now && it.key !in delivered }
            return null
        }

        if (current?.key == previous.key) {
            tracked = current
            if (!state.working && now >= current.deadlineEpochMs) {
                tracked = null
                return deliver(current)
            }
            return null
        }

        tracked = current?.takeIf { it.deadlineEpochMs > now && it.key !in delivered }
        if (now < previous.deadlineEpochMs) return null
        if (!naturalTransitionConfirmed(previous, state)) return null
        return deliver(previous)
    }

    fun deliveredKeys(): Set<String> = delivered.toSet()

    private fun deliver(countdown: SynchronizedCountdown): TimerCompletionEvent? {
        if (!delivered.add(countdown.key)) return null
        while (delivered.size > MAX_DELIVERED_KEYS) delivered.remove(delivered.first())
        val subjectPrefix = countdown.subjectName.takeIf(String::isNotBlank)?.let { "$it · " }.orEmpty()
        return when (countdown.phase) {
            TimerPhase.FOCUS -> TimerCompletionEvent(
                key = countdown.key,
                phase = countdown.phase,
                subjectName = countdown.subjectName,
                title = "집중을 마쳤어요",
                body = "${subjectPrefix}잠시 쉬어가세요.",
            )
            TimerPhase.SHORT_BREAK,
            TimerPhase.LONG_BREAK -> TimerCompletionEvent(
                key = countdown.key,
                phase = countdown.phase,
                subjectName = countdown.subjectName,
                title = "휴식이 끝났어요",
                body = "${subjectPrefix}다음 집중을 시작할 준비가 됐어요.",
            )
        }
    }

    private fun naturalTransitionConfirmed(previous: SynchronizedCountdown, state: ClientState): Boolean {
        val snapshot = state.snapshot ?: return false
        if (snapshot.timer.id != previous.timerId) return false
        return when (previous.phase) {
            TimerPhase.FOCUS -> snapshot.sessions.any { it.confirms(previous) }
            TimerPhase.SHORT_BREAK,
            TimerPhase.LONG_BREAK ->
                snapshot.timer.status == TimerStatus.IDLE && snapshot.timer.phase == TimerPhase.FOCUS
        }
    }

    private fun FocusSession.confirms(previous: SynchronizedCountdown): Boolean =
        id == previous.timerId &&
            startedAtEpochMs == previous.startedAtEpochMs &&
            endedAtEpochMs == previous.deadlineEpochMs &&
            durationSeconds == previous.targetSeconds &&
            mode == TimerMode.COUNTDOWN

    private data class ConnectionIdentity(val baseUrl: String, val token: String)

    private companion object {
        const val MAX_DELIVERED_KEYS = 64
    }
}
