package dev.stint.app

import dev.stint.app.client.TimerCompletionEvent
import dev.stint.app.client.TimerCompletionTracker
import dev.stint.shared.ClientState
import dev.stint.shared.ConnectionStatus
import dev.stint.shared.TimerMode
import dev.stint.shared.TimerPhase
import dev.stint.shared.TimerStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class DesktopCompletionPlan(
    val identifier: String,
    val title: String,
    val body: String,
    val deadlineEpochMs: Long,
    val delayMillis: Long,
)

private data class DesktopCompletionSignature(
    val identifier: String,
    val title: String,
    val body: String,
    val deadlineEpochMs: Long,
)

internal interface DesktopNotificationBridge {
    suspend fun authorize(): String?
    suspend fun sync(scheduled: DesktopCompletionPlan?, immediate: TimerCompletionEvent?): String?
    suspend fun status(): String? = null
    suspend fun test(): String? = null
}

internal class DesktopCompletionNotifications(
    private val bridge: DesktopNotificationBridge = NativeDesktopNotificationBridge(),
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val tracker = TimerCompletionTracker()
    private val operationMutex = Mutex()
    private var lastPlan: DesktopCompletionSignature? = null
    private var pendingImmediate: PendingImmediate? = null
    private var nextRetryAtMillis = 0L
    private var closed = false

    suspend fun authorize(): String? = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            if (closed) null else bridge.authorize()
        }
    }

    suspend fun update(state: ClientState) = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            if (closed) return@withLock
            val now = monotonicMillis()
            val event = tracker.update(state)
            if (event != null) pendingImmediate = PendingImmediate(event, now + IMMEDIATE_RETRY_WINDOW_MILLIS)
            if (pendingImmediate?.expiresAtMillis?.let { now >= it } == true) pendingImmediate = null
            val plan = desktopCompletionPlan(state)
            val signature = plan?.signature()
            if (signature == lastPlan && pendingImmediate == null) return@withLock
            if (now < nextRetryAtMillis) return@withLock
            val response = bridge.sync(plan, pendingImmediate?.event)
            if (nativeCommandSucceeded(response)) {
                lastPlan = signature
                pendingImmediate = null
                nextRetryAtMillis = 0L
            } else {
                nextRetryAtMillis = now + RETRY_DELAY_MILLIS
            }
        }
    }

    /** Terminal shutdown: no later state update can recreate a notification after this returns. */
    suspend fun close() = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            if (closed) return@withLock
            closed = true
            bridge.sync(null, null)
            lastPlan = null
            pendingImmediate = null
            nextRetryAtMillis = 0L
        }
    }

    suspend fun testNotification(): String = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            if (closed) return@withLock "맥 알림 기능을 준비하지 못했어요."
            val response = bridge.test() ?: return@withLock "맥 알림 기능을 준비하지 못했어요."
            val result = runCatching { Json.parseToJsonElement(response).jsonObject }.getOrNull()
                ?: return@withLock "맥 알림 테스트 결과를 확인하지 못했어요."
            val authorization = result["authorization"]?.jsonPrimitive?.contentOrNull
            if (authorization == "denied") return@withLock "시스템 설정에서 Stint 알림을 허용해 주세요."
            if (result["ok"]?.jsonPrimitive?.booleanOrNull == true) {
                "테스트 알림을 보냈어요."
            } else {
                "맥 알림을 보내지 못했어요."
            }
        }
    }

    suspend fun status(): String? = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            if (closed) null else bridge.status()
        }
    }

    private data class PendingImmediate(
        val event: TimerCompletionEvent,
        val expiresAtMillis: Long,
    )

    private companion object {
        const val RETRY_DELAY_MILLIS = 5_000L
        const val IMMEDIATE_RETRY_WINDOW_MILLIS = 30_000L
    }
}

internal fun desktopCompletionPlan(state: ClientState): DesktopCompletionPlan? {
    if (state.connection != ConnectionStatus.CONNECTED || state.working) return null
    val snapshot = state.snapshot ?: return null
    val timer = snapshot.timer
    if (timer.status != TimerStatus.RUNNING || timer.mode != TimerMode.COUNTDOWN) return null
    val timerId = timer.id ?: return null
    val startedAt = timer.startedAtEpochMs ?: return null
    val runningSince = timer.runningSinceEpochMs ?: return null
    val remainingAtResume = (
        timer.targetSeconds.coerceAtLeast(0).toLong() * 1_000L -
            timer.accumulatedMillis.coerceAtLeast(0L)
        ).coerceAtLeast(0L)
    val deadline = addSaturated(runningSince, remainingAtResume)
    if (deadline <= state.serverNowEpochMs) return null
    val subjectName = snapshot.subjects.firstOrNull { it.id == timer.subjectId }?.name.orEmpty()
    val subjectPrefix = subjectName.takeIf(String::isNotBlank)?.let { "$it · " }.orEmpty()
    val (title, body) = when (timer.phase) {
        TimerPhase.FOCUS -> "집중을 마쳤어요" to "${subjectPrefix}잠시 쉬어가세요."
        TimerPhase.SHORT_BREAK,
        TimerPhase.LONG_BREAK,
        -> "휴식이 끝났어요" to "${subjectPrefix}다음 집중을 시작할 준비가 됐어요."
    }
    return DesktopCompletionPlan(
        identifier = nativeNotificationIdentifier(timerId, timer.phase, startedAt),
        title = title,
        body = body,
        deadlineEpochMs = deadline,
        delayMillis = (deadline - state.serverNowEpochMs).coerceAtLeast(100L),
    )
}

private class NativeDesktopNotificationBridge : DesktopNotificationBridge {
    private val available = runCatching {
        val resources = System.getProperty("compose.application.resources.dir").orEmpty()
        val library = Path.of(resources, LIBRARY_NAME)
        if (!Files.isRegularFile(library)) return@runCatching false
        System.load(library.toAbsolutePath().toString())
        true
    }.getOrDefault(false)

    override suspend fun authorize(): String? = command(buildJsonObject {
        put("command", "authorize")
    }.toString())

    override suspend fun status(): String? = command(buildJsonObject {
        put("command", "status")
    }.toString())

    override suspend fun test(): String? = command(buildJsonObject {
        put("command", "test")
    }.toString())

    override suspend fun sync(
        scheduled: DesktopCompletionPlan?,
        immediate: TimerCompletionEvent?,
    ): String? {
        return command(buildJsonObject {
            put("command", "sync")
            scheduled?.let { notice ->
                put("scheduled", buildJsonObject {
                    put("identifier", notice.identifier)
                    put("title", notice.title)
                    put("body", notice.body)
                    put("delaySeconds", notice.delayMillis / 1_000.0)
                })
            }
            immediate?.let { notice ->
                put("immediate", buildJsonObject {
                    put("identifier", nativeNotificationIdentifier(notice.key))
                    put("title", notice.title)
                    put("body", notice.body)
                })
            }
        }.toString())
    }

    private suspend fun command(json: String): String? {
        if (!available) return UNAVAILABLE_RESPONSE
        return withContext(Dispatchers.IO) {
            runCatching { nativeCommand(json) }.getOrDefault(UNAVAILABLE_RESPONSE)
        }
    }

    private external fun nativeCommand(json: String): String

    private companion object {
        const val LIBRARY_NAME = "libStintCompletionNotifications.dylib"
        const val UNAVAILABLE_RESPONSE = "{\"ok\":false,\"error\":\"unavailable\"}"
    }
}

private fun nativeNotificationIdentifier(timerId: String, phase: TimerPhase, startedAtEpochMs: Long): String =
    nativeNotificationIdentifier("$timerId|${phase.name}|$startedAtEpochMs")

private fun nativeNotificationIdentifier(key: String): String =
    "stint.timer." + key.replace(Regex("[^A-Za-z0-9._-]"), "_")

private fun addSaturated(left: Long, right: Long): Long =
    if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

private fun DesktopCompletionPlan.signature() = DesktopCompletionSignature(
    identifier = identifier,
    title = title,
    body = body,
    deadlineEpochMs = deadlineEpochMs,
)

private fun nativeCommandSucceeded(response: String?): Boolean = runCatching {
    response != null &&
        Json.parseToJsonElement(response).jsonObject["ok"]?.jsonPrimitive?.booleanOrNull == true
}.getOrDefault(false)
