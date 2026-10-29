package dev.stint.app.client

import dev.stint.shared.ApiError
import dev.stint.shared.ClientController
import dev.stint.shared.ClientState
import dev.stint.shared.ConnectionSettings
import dev.stint.shared.ConnectionStatus
import dev.stint.shared.LocalStore
import dev.stint.shared.MutationContext
import dev.stint.shared.SaveMemoRequest
import dev.stint.shared.SaveSubjectRequest
import dev.stint.shared.Snapshot
import dev.stint.shared.Subject
import dev.stint.shared.TimerAction
import dev.stint.shared.TimerClockStyle
import dev.stint.shared.TimerCommand
import dev.stint.shared.TimerMode
import dev.stint.shared.TimerStatus
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

class StintController(
    private val client: HttpClient,
    private val localStore: LocalStore,
    private val clock: () -> Long,
    private val newId: () -> String,
    externalScope: CoroutineScope? = null,
    private val realtimeEnabled: Boolean = true,
    private val allowInsecureLoopback: Boolean = false,
) : ClientController {
    private val ownedJob = if (externalScope == null) SupervisorJob() else null
    private val scope = externalScope ?: CoroutineScope(ownedJob!! + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutationMutex = Mutex()
    private val snapshotMutex = Mutex()
    private var syncJob: Job? = null
    private var tickerJob: Job? = null
    @Volatile private var identityGeneration = 0L
    private var identityJob = SupervisorJob(scope.coroutineContext[Job])
    private var identityScope = CoroutineScope(scope.coroutineContext + identityJob)
    @Volatile private var closed = false
    @Volatile private var serverOffsetMs = 0L
    @Volatile private var lastSuccessAtMs = 0L

    private val initialSettings = localStore.loadSettings().ensureDevice(newId)
    private val initialValidation = if (initialSettings.isComplete()) {
        validateConnection(initialSettings.baseUrl, initialSettings.token, allowInsecureLoopback)
    } else null
    private val initialConnectionAllowed = initialSettings.isComplete() && initialValidation == null
    private val initialSnapshot = initialSettings.takeIf { initialConnectionAllowed }?.let { localStore.loadSnapshot() }
    private val initialClockStyle = runCatching { localStore.loadClockStyle() }.getOrDefault(TimerClockStyle.ORBIT)
    private val _state = MutableStateFlow(
        ClientState(
            settings = initialSettings,
            snapshot = initialSnapshot,
            serverNowEpochMs = initialSnapshot?.serverNowEpochMs ?: clock(),
            connection = if (initialConnectionAllowed) ConnectionStatus.CONNECTING else ConnectionStatus.SETUP,
            message = initialValidation,
            clockStyle = initialClockStyle,
        ),
    )
    override val state: StateFlow<ClientState> = _state.asStateFlow()

    init {
        localStore.saveSettings(initialSettings)
        tickerJob = scope.launch {
            while (isActive) {
                _state.update { it.copy(serverNowEpochMs = clock() + serverOffsetMs) }
                delay(250)
            }
        }
        if (initialConnectionAllowed) restartSync()
    }

    override fun configure(baseUrl: String, token: String) {
        if (closed) return
        val normalized = normalizeUrl(baseUrl)
        val validationMessage = validateConnection(normalized, token, allowInsecureLoopback)
        if (validationMessage != null) {
            _state.update { it.copy(message = validationMessage) }
            return
        }
        scope.launch {
            snapshotMutex.withLock {
                if (closed) return@withLock
                val previous = _state.value.settings
                val next = previous.copy(baseUrl = normalized, token = token.trim()).ensureDevice(newId)
                val identityChanged = previous.baseUrl != next.baseUrl || previous.token != next.token
                try {
                    localStore.saveSettings(next)
                } catch (_: Throwable) {
                    _state.update { it.copy(message = "연결 키를 이 기기에 안전하게 저장하지 못했어요.") }
                    return@withLock
                }
                identityGeneration += 1
                identityJob.cancel()
                identityJob = SupervisorJob(scope.coroutineContext[Job])
                identityScope = CoroutineScope(scope.coroutineContext + identityJob)
                lastSuccessAtMs = 0L
                val cached = if (identityChanged) localStore.loadSnapshot() else _state.value.snapshot
                _state.update {
                    it.copy(
                        settings = next,
                        snapshot = cached,
                        serverNowEpochMs = cached?.serverNowEpochMs ?: clock(),
                        connection = ConnectionStatus.CONNECTING,
                        message = null,
                    )
                }
                restartSync()
            }
        }
    }

    override fun start(subjectId: String, mode: TimerMode, focusSeconds: Int?) =
        timerMutation(TimerAction.START, subjectId, mode, focusSeconds)

    override fun pause() = timerMutation(TimerAction.PAUSE)
    override fun resume() = timerMutation(TimerAction.RESUME)
    override fun finish() = timerMutation(TimerAction.FINISH)
    override fun skipBreak() = timerMutation(TimerAction.SKIP_BREAK)
    override fun setTimerDuration(targetSeconds: Int, expectedRevision: Long?) {
        val snapshot = _state.value.snapshot
        if (snapshot == null) {
            _state.update { it.copy(message = "최신 타이머를 불러온 뒤 다시 시도해 주세요.") }
            return
        }
        if (expectedRevision != null && snapshot.revision != expectedRevision) {
            _state.update { it.copy(message = "다른 기기에서 상태가 변경됐어요. 시간을 다시 확인해 주세요.") }
            return
        }
        timerMutation(
            TimerAction.SET_DURATION,
            targetSeconds = targetSeconds,
            guard = TimerMutationGuard(
                revision = expectedRevision ?: snapshot.revision,
                timerId = snapshot.timer.id,
                phase = snapshot.timer.phase,
            ),
        )
    }

    override fun setSubjectFocusDuration(subjectId: String, targetSeconds: Int, expectedRevision: Long) {
        val invoked = _state.value
        if (invoked.connection != ConnectionStatus.CONNECTED) {
            _state.update { it.copy(message = "연결이 복구된 뒤 다시 시도해 주세요.") }
            return
        }
        val snapshot = invoked.snapshot
        if (snapshot == null) {
            _state.update { it.copy(message = "최신 타이머를 불러온 뒤 다시 시도해 주세요.") }
            return
        }
        if (snapshot.revision != expectedRevision) {
            _state.update { it.copy(message = "다른 기기에서 상태가 변경됐어요. 시간을 다시 확인해 주세요.") }
            return
        }
        if (snapshot.timer.status != TimerStatus.IDLE) {
            _state.update { it.copy(message = "진행 중인 타이머를 마친 뒤 기본 시간을 바꿀 수 있어요.") }
            return
        }
        val subject = snapshot.subjects.firstOrNull { it.id == subjectId && !it.archived }
        if (subject == null) {
            _state.update { it.copy(message = "선택한 주제를 찾지 못했어요. 다시 확인해 주세요.") }
            return
        }
        if (targetSeconds !in 1..14_400) {
            _state.update { it.copy(message = "집중 시간은 1초부터 4시간까지 설정할 수 있어요.") }
            return
        }
        val guard = TimerMutationGuard(
            revision = snapshot.revision,
            timerId = snapshot.timer.id,
            phase = snapshot.timer.phase,
        )
        launchMutation(guard) { settings, context ->
            mutate(
                settings,
                "/v1/subjects",
                SaveSubjectRequest(context, subject.copy(focusSeconds = targetSeconds)),
                HttpMethod.POST,
            )
        }
    }

    override fun saveSubject(subject: Subject) = launchMutation { settings, context ->
        mutate(settings, "/v1/subjects", SaveSubjectRequest(context, subject), HttpMethod.POST)
    }

    override fun saveMemo(sessionId: String, memo: String) = launchMutation { settings, context ->
        mutate(settings, "/v1/sessions/$sessionId/memo", SaveMemoRequest(context, memo), HttpMethod.PUT)
    }

    override fun refresh() {
        if (closed || !_state.value.settings.isComplete()) return
        val settings = _state.value.settings
        val generation = identityGeneration
        identityScope.launch { fetchSnapshot(settings, generation, showConnecting = true) }
    }

    override fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    override fun setClockStyle(style: TimerClockStyle) {
        if (closed || style == _state.value.clockStyle) return
        try {
            localStore.saveClockStyle(style)
            _state.update { it.copy(clockStyle = style, message = null) }
        } catch (_: Throwable) {
            _state.update { it.copy(message = "시계 모양을 이 기기에 저장하지 못했어요.") }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        syncJob?.cancel()
        identityJob.cancel()
        tickerJob?.cancel()
        ownedJob?.cancel()
        client.close()
    }

    private fun timerMutation(
        action: TimerAction,
        subjectId: String? = null,
        mode: TimerMode = TimerMode.COUNTDOWN,
        focusSeconds: Int? = null,
        targetSeconds: Int? = null,
        guard: TimerMutationGuard? = null,
    ) = launchMutation(guard) { settings, context ->
        mutate(
            settings,
            "/v1/timer/commands",
            TimerCommand(context, action, subjectId, mode, focusSeconds, targetSeconds),
            HttpMethod.POST,
        )
    }

    private fun launchMutation(
        guard: TimerMutationGuard? = null,
        block: suspend (ConnectionSettings, MutationContext) -> Snapshot,
    ) {
        if (closed) return
        val invoked = _state.value
        if (invoked.connection != ConnectionStatus.CONNECTED) {
            _state.update { it.copy(message = "연결이 복구된 뒤 다시 시도해 주세요.") }
            return
        }
        val settings = invoked.settings
        val generation = identityGeneration
        identityScope.launch {
            mutationMutex.withLock {
                if (generation != identityGeneration) return@withLock
                val current = _state.value
                val snapshot = current.snapshot ?: return@withLock
                if (guard != null && !guard.matches(snapshot)) {
                    _state.update {
                        it.copy(
                            connection = ConnectionStatus.CONNECTED,
                            message = "다른 기기에서 상태가 바뀌어 시간 변경을 보내지 않았어요. 다시 확인해 주세요.",
                        )
                    }
                    return@withLock
                }
                val context = MutationContext(
                    commandId = newId(),
                    expectedRevision = guard?.revision ?: snapshot.revision,
                    deviceId = settings.deviceId,
                    deviceName = settings.deviceName,
                )
                _state.update { it.copy(working = true, message = null) }
                try {
                    applySnapshot(block(settings, context), generation)
                } catch (conflict: RevisionConflict) {
                    conflict.snapshot?.let { applySnapshot(it, generation) }
                    if (generation == identityGeneration) {
                        _state.update {
                            it.copy(
                                connection = ConnectionStatus.CONNECTED,
                                message = "다른 기기에서 상태가 변경됐어요. 최신 상태를 불러왔습니다.",
                            )
                        }
                    }
                } catch (failure: ClientRejected) {
                    if (generation == identityGeneration) {
                        _state.update {
                            it.copy(
                                connection = ConnectionStatus.CONNECTED,
                                message = failure.message ?: "변경 내용을 저장하지 못했어요.",
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    markOffline(generation, "변경 내용을 전송하지 못했어요. 서버 상태를 확인해 주세요.")
                } finally {
                    if (generation == identityGeneration) _state.update { it.copy(working = false) }
                }
            }
        }
    }

    private suspend inline fun <reified T> mutate(
        settings: ConnectionSettings,
        path: String,
        payload: T,
        method: HttpMethod,
    ): Snapshot {
        var lastFailure: Throwable? = null
        repeat(2) { attempt ->
            try {
                val response = when (method) {
                    HttpMethod.POST -> client.post(url(settings, path)) {
                        authenticatedHeaders(settings)
                        contentType(ContentType.Application.Json)
                        setBody(payload)
                    }
                    HttpMethod.PUT -> client.put(url(settings, path)) {
                        authenticatedHeaders(settings)
                        contentType(ContentType.Application.Json)
                        setBody(payload)
                    }
                }
                return decodeMutationResponse(response)
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                lastFailure = failure
                if (attempt == 0 && failure.isTimeout()) return@repeat
                throw failure
            }
        }
        throw lastFailure ?: IllegalStateException("Mutation failed")
    }

    private suspend fun decodeMutationResponse(response: HttpResponse): Snapshot {
        if (response.status == HttpStatusCode.Conflict) {
            val error = response.body<ApiError>()
            throw RevisionConflict(error.snapshot)
        }
        if (response.status == HttpStatusCode.BadRequest) {
            val message = runCatching { response.body<ApiError>().message }.getOrDefault("변경 내용을 확인해 주세요.")
            throw ClientRejected(message)
        }
        if (!response.status.isSuccess()) {
            val message = runCatching { response.body<ApiError>().message }.getOrDefault("서버 요청이 실패했어요.")
            throw RemoteFailure(message)
        }
        return response.body()
    }

    private fun restartSync() {
        syncJob?.cancel()
        if (!_state.value.settings.isComplete() || closed) return
        val settings = _state.value.settings
        val generation = identityGeneration
        syncJob = identityScope.launch {
            if (!realtimeEnabled) {
                fetchSnapshot(settings, generation, showConnecting = true)
                return@launch
            }
            launch {
                fetchSnapshot(settings, generation, showConnecting = true)
                while (isActive) {
                    delay(10_000)
                    fetchSnapshot(settings, generation, showConnecting = false)
                }
            }
            launch {
                var retryDelayMs = 1_000L
                while (isActive) {
                    try {
                        client.webSocket(
                            urlString = websocketUrl(settings.baseUrl, "/v1/events"),
                            request = { authenticatedHeaders(settings) },
                        ) {
                            if (generation != identityGeneration) return@webSocket
                            lastSuccessAtMs = clock()
                            _state.update { it.copy(connection = ConnectionStatus.CONNECTED) }
                            retryDelayMs = 1_000L
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    applySnapshot(json.decodeFromString<Snapshot>(frame.readText()), generation)
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        if (clock() - lastSuccessAtMs > 12_000) markOffline(generation)
                    }
                    delay(retryDelayMs)
                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(10_000)
                }
            }
        }
    }

    private suspend fun fetchSnapshot(
        settings: ConnectionSettings,
        generation: Long,
        showConnecting: Boolean,
    ) {
        if (generation != identityGeneration) return
        if (showConnecting && _state.value.connection != ConnectionStatus.CONNECTED) {
            _state.update { it.copy(connection = ConnectionStatus.CONNECTING) }
        }
        try {
            val response = client.get(url(settings, "/v1/snapshot")) { authenticatedHeaders(settings) }
            if (!response.status.isSuccess()) throw RemoteFailure("서버 인증 또는 연결 설정을 확인해 주세요.")
            applySnapshot(response.body(), generation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            if (clock() - lastSuccessAtMs > 12_000) markOffline(generation)
        }
    }

    private suspend fun applySnapshot(snapshot: Snapshot, generation: Long): Boolean = snapshotMutex.withLock {
        if (generation != identityGeneration) return@withLock false
        val currentSnapshot = _state.value.snapshot
        if (currentSnapshot != null &&
            (snapshot.revision < currentSnapshot.revision ||
                (snapshot.revision == currentSnapshot.revision &&
                    snapshot.serverNowEpochMs < currentSnapshot.serverNowEpochMs))
        ) return@withLock false
        val receivedAt = clock()
        serverOffsetMs = snapshot.serverNowEpochMs - receivedAt
        lastSuccessAtMs = receivedAt
        runCatching { localStore.saveSnapshot(snapshot) }
        _state.update {
            it.copy(snapshot = snapshot, serverNowEpochMs = snapshot.serverNowEpochMs, connection = ConnectionStatus.CONNECTED)
        }
        true
    }

    private fun markOffline(generation: Long, message: String? = null) {
        if (generation != identityGeneration) return
        _state.update {
            it.copy(
                connection = ConnectionStatus.OFFLINE,
                message = message ?: it.message,
            )
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authenticatedHeaders(settings: ConnectionSettings) {
        header(HttpHeaders.Authorization, "Bearer ${settings.token}")
        header("X-Device-Id", settings.deviceId)
        header("X-Device-Name", settings.deviceName.encodeURLParameter())
    }

    private fun url(settings: ConnectionSettings, path: String): String = settings.baseUrl + path

    private enum class HttpMethod { POST, PUT }
    private data class TimerMutationGuard(
        val revision: Long,
        val timerId: String?,
        val phase: dev.stint.shared.TimerPhase,
    ) {
        fun matches(snapshot: Snapshot): Boolean =
            snapshot.revision == revision && snapshot.timer.id == timerId && snapshot.timer.phase == phase
    }
    private class RevisionConflict(val snapshot: Snapshot?) : Exception()
    private class ClientRejected(message: String) : Exception(message)
    private class RemoteFailure(message: String) : Exception(message)
}

private fun ConnectionSettings.ensureDevice(newId: () -> String): ConnectionSettings = copy(
    deviceId = deviceId.ifBlank(newId),
    deviceName = deviceName.ifBlank { "새 기기" },
)

private fun ConnectionSettings.isComplete(): Boolean =
    baseUrl.isNotBlank() && token.isNotBlank() && deviceId.isNotBlank()

private fun normalizeUrl(value: String): String = value.trim().trimEnd('/')

private fun validateConnection(baseUrl: String, token: String, allowInsecureLoopback: Boolean): String? {
    if (baseUrl.isBlank() || token.isBlank()) return "서버 주소와 연결 키를 입력해 주세요."
    if ('\n' in token || '\r' in token) return "연결 키에 줄바꿈을 사용할 수 없어요."
    val parsed = runCatching { Url(baseUrl) }.getOrNull() ?: return "올바른 서버 주소를 입력해 주세요."
    val host = parsed.host.lowercase()
    val isLoopback = host == "localhost" || host == "::1" || host.isLoopbackIpv4() || host == "10.0.2.2"
    if (parsed.protocol != URLProtocol.HTTPS &&
        !(allowInsecureLoopback && parsed.protocol == URLProtocol.HTTP && isLoopback)
    ) {
        return "외부 서버는 HTTPS 주소만 사용할 수 있어요."
    }
    return null
}

private fun String.isLoopbackIpv4(): Boolean {
    val octets = split('.').map { it.toIntOrNull() ?: return false }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    return octets[0] == 127
}

private fun websocketUrl(baseUrl: String, path: String): String = when {
    baseUrl.startsWith("https://") -> "wss://${baseUrl.removePrefix("https://")}$path"
    baseUrl.startsWith("http://") -> "ws://${baseUrl.removePrefix("http://")}$path"
    else -> error("Unsupported server URL")
}

private fun Throwable.isTimeout(): Boolean =
    generateSequence(this) { it.cause }.any { it::class.simpleName?.contains("Timeout", ignoreCase = true) == true }
