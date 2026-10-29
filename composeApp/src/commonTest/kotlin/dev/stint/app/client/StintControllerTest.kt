package dev.stint.app.client

import dev.stint.shared.ApiError
import dev.stint.shared.ConnectionSettings
import dev.stint.shared.ConnectionStatus
import dev.stint.shared.LocalStore
import dev.stint.shared.SaveSubjectRequest
import dev.stint.shared.Snapshot
import dev.stint.shared.Subject
import dev.stint.shared.TimerState
import dev.stint.shared.TimerStatus
import dev.stint.shared.TimerClockStyle
import dev.stint.shared.TimerPhase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StintControllerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `snapshot aligns the local clock to server time`() = runBlocking {
        var now = 1_000L
        val snapshot = snapshot(serverNow = 9_000L, revision = 3)
        val store = FakeStore(settings(), snapshot)
        val controller = StintController(
            client = client { jsonResponse(snapshot) },
            localStore = store,
            clock = { now },
            newId = { "command" },
            realtimeEnabled = false,
        )

        withTimeout(2_000) { controller.state.first { it.connection == ConnectionStatus.CONNECTED } }
        assertEquals(ConnectionStatus.CONNECTED, controller.state.value.connection)
        assertEquals(9_000L, controller.state.value.serverNowEpochMs)
        now = 1_750L
        delay(300)
        assertEquals(9_750L, controller.state.value.serverNowEpochMs)
        controller.close()
    }

    @Test
    fun `revision conflict applies server snapshot without replaying the command`() = runBlocking {
        val initial = snapshot(serverNow = 1_000L, revision = 4, status = TimerStatus.RUNNING)
        val latest = snapshot(serverNow = 1_100L, revision = 5, status = TimerStatus.PAUSED)
        var mutationCount = 0
        val controller = StintController(
            client = client { request ->
                if (request.url.encodedPath.endsWith("/timer/commands")) {
                    mutationCount += 1
                    jsonResponse(ApiError("revision_conflict", "changed", latest), HttpStatusCode.Conflict)
                } else jsonResponse(initial)
            },
            localStore = FakeStore(settings(), initial),
            clock = { 1_000L },
            newId = { "command-1" },
            realtimeEnabled = false,
        )
        withTimeout(2_000) { controller.state.first { it.connection == ConnectionStatus.CONNECTED } }

        controller.pause()
        withTimeout(2_000) { controller.state.first { it.message?.contains("다른 기기") == true } }

        assertEquals(1, mutationCount)
        assertEquals(5, controller.state.value.snapshot?.revision)
        assertEquals(TimerStatus.PAUSED, controller.state.value.snapshot?.timer?.status)
        assertTrue(controller.state.value.message.orEmpty().contains("다른 기기"))
        controller.close()
    }

    @Test
    fun `timeout retries once with the same idempotency command id`() = runBlocking {
        val initial = snapshot(serverNow = 1_000L, revision = 1, status = TimerStatus.RUNNING)
        val completed = snapshot(serverNow = 1_100L, revision = 2, status = TimerStatus.PAUSED)
        var mutationCount = 0
        val bodies = mutableListOf<String>()
        val controller = StintController(
            client = client { request ->
                if (request.url.encodedPath.endsWith("/timer/commands")) {
                    bodies += when (val body = request.body) {
                        is TextContent -> body.text
                        is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
                        else -> body.toString()
                    }
                    mutationCount += 1
                    if (mutationCount == 1) throw TestTimeout()
                    jsonResponse(completed)
                } else jsonResponse(initial)
            },
            localStore = FakeStore(settings(), initial),
            clock = { 1_000L },
            newId = { "stable-command-id" },
            realtimeEnabled = false,
        )
        withTimeout(2_000) { controller.state.first { it.connection == ConnectionStatus.CONNECTED } }

        controller.pause()
        withTimeout(2_000) { controller.state.first { it.snapshot?.revision == 2L } }

        assertEquals(2, mutationCount)
        assertEquals(2, bodies.size)
        assertTrue(bodies.all { it.contains("stable-command-id") })
        assertEquals(2, controller.state.value.snapshot?.revision)
        controller.close()
    }

    @Test
    fun `duration validation remains connected and sends the new target once`() = runBlocking {
        val initial = snapshot(serverNow = 1_000L, revision = 3, status = TimerStatus.PAUSED)
        var mutationCount = 0
        var body = ""
        val controller = StintController(
            client = client { request ->
                if (request.url.encodedPath.endsWith("/timer/commands")) {
                    mutationCount += 1
                    body = when (val outgoing = request.body) {
                        is TextContent -> outgoing.text
                        is OutgoingContent.ByteArrayContent -> outgoing.bytes().decodeToString()
                        else -> outgoing.toString()
                    }
                    jsonResponse(
                        ApiError("invalid_request", "지난 시간보다 길게 설정해 주세요."),
                        HttpStatusCode.BadRequest,
                    )
                } else {
                    jsonResponse(initial)
                }
            },
            localStore = FakeStore(settings(), initial),
            clock = { 1_000L },
            newId = { "duration-command" },
            realtimeEnabled = false,
        )
        withTimeout(2_000) { controller.state.first { it.connection == ConnectionStatus.CONNECTED } }

        controller.setTimerDuration(1_200)
        withTimeout(2_000) { controller.state.first { it.message?.contains("지난 시간") == true } }

        assertEquals(1, mutationCount)
        assertTrue(body.contains("\"action\":\"SET_DURATION\""))
        assertTrue(body.contains("\"targetSeconds\":1200"))
        assertEquals(ConnectionStatus.CONNECTED, controller.state.value.connection)
        assertFalse(controller.state.value.working)
        assertEquals(3, controller.state.value.snapshot?.revision)
        controller.close()
    }

    @Test
    fun `stale paused duration intent is rejected without a timer request`() = runBlocking {
        val initial = snapshot(serverNow = 1_000L, revision = 3, status = TimerStatus.PAUSED)
        var timerRequests = 0
        val controller = StintController(
            client = client { request ->
                if (request.url.encodedPath.endsWith("/timer/commands")) timerRequests += 1
                jsonResponse(initial)
            },
            localStore = FakeStore(settings(), initial),
            clock = { 1_000L },
            newId = { "duration-command" },
            realtimeEnabled = false,
        )
        withTimeout(2_000) { controller.state.first { it.connection == ConnectionStatus.CONNECTED } }

        controller.setTimerDuration(1_200, expectedRevision = 2)

        assertEquals(0, timerRequests)
        assertEquals(ConnectionStatus.CONNECTED, controller.state.value.connection)
        assertTrue(controller.state.value.message.orEmpty().contains("다른 기기"))
        assertFalse(controller.state.value.working)
        controller.close()
    }

    @Test
    fun `authentication rejection is not treated as a local validation error`() = runBlocking {
        val initial = snapshot(serverNow = 1_000L, revision = 3, status = TimerStatus.PAUSED)
        var mutationCount = 0
        val controller = StintController(
            client = client { request ->
                if (request.url.encodedPath.endsWith("/timer/commands")) {
                    mutationCount += 1
                    jsonResponse(ApiError("unauthorized", "연결 키를 확인해 주세요."), HttpStatusCode.Unauthorized)
                } else {
                    jsonResponse(initial)
                }
            },
            localStore = FakeStore(settings(), initial),
            clock = { 1_000L },
            newId = { "duration-command" },
            realtimeEnabled = false,
        )
        withTimeout(2_000) { controller.state.first { it.connection == ConnectionStatus.CONNECTED } }

        controller.setTimerDuration(1_200)
        withTimeout(2_000) { controller.state.first { it.connection == ConnectionStatus.OFFLINE } }

        assertEquals(1, mutationCount)
        assertFalse(controller.state.value.working)
        assertTrue(controller.state.value.message.orEmpty().contains("서버 상태"))
        controller.close()
    }

    @Test
    fun `queued duration change does not rebase onto a replaced timer`() = runBlocking {
        val initial = snapshot(serverNow = 1_000L, revision = 1, status = TimerStatus.PAUSED).copy(
            timer = TimerState(
                id = "timer-a",
                subjectId = "study",
                status = TimerStatus.PAUSED,
                phase = TimerPhase.FOCUS,
                targetSeconds = 1_500,
                accumulatedMillis = 12_000,
            ),
        )
        val replaced = initial.copy(
            serverNowEpochMs = 1_100L,
            revision = 2,
            timer = initial.timer.copy(id = "timer-b", phase = TimerPhase.SHORT_BREAK),
        )
        val firstRequestStarted = CompletableDeferred<Unit>()
        val releaseFirstRequest = CompletableDeferred<Unit>()
        var durationRequests = 0
        val controller = StintController(
            client = client { request ->
                when {
                    request.url.encodedPath.endsWith("/subjects") -> {
                        firstRequestStarted.complete(Unit)
                        releaseFirstRequest.await()
                        jsonResponse(replaced)
                    }
                    request.url.encodedPath.endsWith("/timer/commands") -> {
                        durationRequests += 1
                        jsonResponse(replaced)
                    }
                    else -> jsonResponse(initial)
                }
            },
            localStore = FakeStore(settings(), initial),
            clock = { 1_000L },
            newId = { "command" },
            realtimeEnabled = false,
        )
        withTimeout(2_000) { controller.state.first { it.connection == ConnectionStatus.CONNECTED } }

        controller.saveSubject(initial.subjects.single())
        withTimeout(2_000) { firstRequestStarted.await() }
        controller.setTimerDuration(1_800)
        releaseFirstRequest.complete(Unit)

        withTimeout(2_000) { controller.state.first { it.message?.contains("시간 변경을 보내지 않았어요") == true } }

        assertEquals(0, durationRequests)
        assertEquals("timer-b", controller.state.value.snapshot?.timer?.id)
        assertEquals(2, controller.state.value.snapshot?.revision)
        assertEquals(ConnectionStatus.CONNECTED, controller.state.value.connection)
        assertFalse(controller.state.value.working)
        controller.close()
    }

    @Test
    fun `stale idle focus duration is rejected without a subject request`() = runBlocking {
        val initial = snapshot(serverNow = 1_000L, revision = 3)
        var subjectRequests = 0
        val controller = StintController(
            client = client { request ->
                if (request.url.encodedPath.endsWith("/subjects")) subjectRequests += 1
                jsonResponse(initial)
            },
            localStore = FakeStore(settings(), initial),
            clock = { 1_000L },
            newId = { "command" },
            realtimeEnabled = false,
        )
        withTimeout(2_000) { controller.state.first { it.connection == ConnectionStatus.CONNECTED } }

        controller.setSubjectFocusDuration("study", 1_800, expectedRevision = 2)

        assertEquals(0, subjectRequests)
        assertEquals(ConnectionStatus.CONNECTED, controller.state.value.connection)
        assertTrue(controller.state.value.message.orEmpty().contains("다른 기기"))
        controller.close()
    }

    @Test
    fun `idle focus duration preserves the subject and pins its context revision`() = runBlocking {
        val subject = Subject(
            id = "study",
            name = "알고리즘",
            colorHex = "B9684C",
            focusSeconds = 1_500,
            shortBreakSeconds = 420,
            longBreakSeconds = 1_200,
            longBreakEvery = 3,
        )
        val initial = snapshot(serverNow = 1_000L, revision = 3).copy(subjects = listOf(subject))
        val updated = initial.copy(revision = 4, subjects = listOf(subject.copy(focusSeconds = 1_800)))
        var saved: SaveSubjectRequest? = null
        val controller = StintController(
            client = client { request ->
                if (request.url.encodedPath.endsWith("/subjects")) {
                    val body = when (val outgoing = request.body) {
                        is TextContent -> outgoing.text
                        is OutgoingContent.ByteArrayContent -> outgoing.bytes().decodeToString()
                        else -> outgoing.toString()
                    }
                    saved = json.decodeFromString<SaveSubjectRequest>(body)
                    jsonResponse(updated)
                } else {
                    jsonResponse(initial)
                }
            },
            localStore = FakeStore(settings(), initial),
            clock = { 1_000L },
            newId = { "focus-duration" },
            realtimeEnabled = false,
        )
        withTimeout(2_000) { controller.state.first { it.connection == ConnectionStatus.CONNECTED } }

        controller.setSubjectFocusDuration("study", 1_800, expectedRevision = 3)
        withTimeout(2_000) { controller.state.first { it.snapshot?.revision == 4L } }

        assertEquals(3, saved?.context?.expectedRevision)
        assertEquals(subject.copy(focusSeconds = 1_800), saved?.subject)
        assertEquals(ConnectionStatus.CONNECTED, controller.state.value.connection)
        controller.close()
    }

    @Test
    fun `queued idle focus duration does not apply after timer and subject replacement`() = runBlocking {
        val initial = snapshot(serverNow = 1_000L, revision = 1).copy(
            timer = TimerState(id = "timer-a", subjectId = "study", status = TimerStatus.IDLE),
        )
        val replaced = initial.copy(
            serverNowEpochMs = 1_100L,
            revision = 2,
            subjects = listOf(initial.subjects.single().copy(name = "다른 기기에서 수정")),
            timer = initial.timer.copy(id = "timer-b"),
        )
        val blockerStarted = CompletableDeferred<Unit>()
        val releaseBlocker = CompletableDeferred<Unit>()
        var subjectRequests = 0
        val controller = StintController(
            client = client { request ->
                when {
                    request.url.encodedPath.endsWith("/timer/commands") -> {
                        blockerStarted.complete(Unit)
                        releaseBlocker.await()
                        jsonResponse(replaced)
                    }
                    request.url.encodedPath.endsWith("/subjects") -> {
                        subjectRequests += 1
                        jsonResponse(replaced)
                    }
                    else -> jsonResponse(initial)
                }
            },
            localStore = FakeStore(settings(), initial),
            clock = { 1_000L },
            newId = { "command" },
            realtimeEnabled = false,
        )
        withTimeout(2_000) { controller.state.first { it.connection == ConnectionStatus.CONNECTED } }

        controller.pause()
        withTimeout(2_000) { blockerStarted.await() }
        controller.setSubjectFocusDuration("study", 1_800, expectedRevision = 1)
        releaseBlocker.complete(Unit)
        withTimeout(2_000) { controller.state.first { it.message?.contains("시간 변경을 보내지 않았어요") == true } }

        assertEquals(0, subjectRequests)
        assertEquals("timer-b", controller.state.value.snapshot?.timer?.id)
        assertEquals("다른 기기에서 수정", controller.state.value.snapshot?.subjects?.single()?.name)
        assertEquals(2, controller.state.value.snapshot?.revision)
        assertEquals(ConnectionStatus.CONNECTED, controller.state.value.connection)
        controller.close()
    }

    @Test
    fun `changing server identity does not display another account cache`() = runBlocking {
        val old = settings(baseUrl = "https://old.example")
        val store = FakeStore(old, snapshot(1_000L, 7))
        val controller = StintController(
            client = client { throw TestTimeout() },
            localStore = store,
            clock = { 1_000L },
            newId = { "id" },
            realtimeEnabled = false,
        )
        assertNotNull(controller.state.value.snapshot)

        store.delayLoads = true
        controller.configure("https://new.example", "new-token")

        withTimeout(2_000) { controller.state.first { it.settings.baseUrl == "https://new.example" } }
        assertNull(controller.state.value.snapshot)
        controller.close()
    }

    @Test
    fun `late lower revision cannot replace a newer mutation response`() = runBlocking {
        val revision10 = snapshot(1_000L, 10, TimerStatus.RUNNING)
        val revision11 = snapshot(1_100L, 11, TimerStatus.PAUSED)
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        var getCount = 0
        val store = FakeStore(settings(), revision10)
        val controller = StintController(
            client = client { request ->
                if (request.url.encodedPath.endsWith("/timer/commands")) jsonResponse(revision11)
                else {
                    getCount += 1
                    if (getCount == 1) jsonResponse(revision10) else {
                        refreshStarted.complete(Unit)
                        releaseRefresh.await()
                        jsonResponse(revision10)
                    }
                }
            },
            localStore = store,
            clock = { 1_000L },
            newId = { "command" },
            realtimeEnabled = false,
        )
        withTimeout(2_000) { controller.state.first { it.connection == ConnectionStatus.CONNECTED } }

        controller.refresh()
        withTimeout(2_000) { refreshStarted.await() }
        controller.pause()
        withTimeout(2_000) { controller.state.first { it.snapshot?.revision == 11L } }
        releaseRefresh.complete(Unit)
        delay(100)

        assertEquals(11, controller.state.value.snapshot?.revision)
        assertEquals(11, store.savedRevisions.last())
        controller.close()
    }

    @Test
    fun `response from previous server identity is discarded`() = runBlocking {
        val oldSnapshot = snapshot(1_000L, 7)
        val newSnapshot = snapshot(2_000L, 20)
        val oldRefreshStarted = CompletableDeferred<Unit>()
        val releaseOldRefresh = CompletableDeferred<Unit>()
        var oldGets = 0
        val store = FakeStore(settings("https://old.example"), oldSnapshot)
        val controller = StintController(
            client = client { request ->
                when (request.url.host) {
                    "old.example" -> {
                        oldGets += 1
                        if (oldGets == 1) jsonResponse(oldSnapshot) else {
                            oldRefreshStarted.complete(Unit)
                            releaseOldRefresh.await()
                            jsonResponse(oldSnapshot.copy(revision = 99))
                        }
                    }
                    "new.example" -> jsonResponse(newSnapshot)
                    else -> error("unexpected host")
                }
            },
            localStore = store,
            clock = { 1_000L },
            newId = { "command" },
            realtimeEnabled = false,
        )
        withTimeout(2_000) { controller.state.first { it.snapshot?.revision == 7L } }
        controller.refresh()
        withTimeout(2_000) { oldRefreshStarted.await() }

        controller.configure("https://new.example", "new-token")
        withTimeout(2_000) { controller.state.first { it.snapshot?.revision == 20L } }
        releaseOldRefresh.complete(Unit)
        delay(100)

        assertEquals("https://new.example", controller.state.value.settings.baseUrl)
        assertEquals(20, controller.state.value.snapshot?.revision)
        assertEquals(20, store.savedRevisions.last())
        controller.close()
    }

    @Test
    fun `identity switch waits for an accepted snapshot cache write`() = runBlocking {
        val oldSnapshot = snapshot(1_000L, 1, TimerStatus.RUNNING)
        val mutationSnapshot = snapshot(1_100L, 2, TimerStatus.PAUSED)
        val newSnapshot = snapshot(2_000L, 10)
        val saveEntered = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        val store = FakeStore(settings("https://old.example"), oldSnapshot)
        val controller = StintController(
            client = client { request ->
                when {
                    request.url.host == "new.example" -> jsonResponse(newSnapshot)
                    request.url.encodedPath.endsWith("/timer/commands") -> jsonResponse(mutationSnapshot)
                    else -> jsonResponse(oldSnapshot)
                }
            },
            localStore = store,
            clock = { 1_000L },
            newId = { "command" },
            realtimeEnabled = false,
        )
        withTimeout(2_000) { controller.state.first { it.connection == ConnectionStatus.CONNECTED } }
        store.beforeSnapshotSave = { candidate ->
            if (candidate.revision == 2L) runBlocking {
                saveEntered.complete(Unit)
                releaseSave.await()
            }
        }

        controller.pause()
        withTimeout(2_000) { saveEntered.await() }
        controller.configure("https://new.example", "new-token")
        delay(100)
        assertEquals("https://old.example", controller.state.value.settings.baseUrl)

        releaseSave.complete(Unit)
        withTimeout(2_000) { controller.state.first { it.snapshot?.revision == 10L } }

        assertTrue(store.savedIdentityRevisions.contains("https://old.example:old-token" to 2L))
        assertFalse(store.savedIdentityRevisions.contains("https://new.example:new-token" to 2L))
        controller.close()
    }

    @Test
    fun `production connections reject cleartext even on private networks`() = runBlocking {
        val store = FakeStore(ConnectionSettings(deviceId = "device", deviceName = "test"), null)
        val controller = StintController(
            client = client { error("network should not be reached") },
            localStore = store,
            clock = { 1_000L },
            newId = { "id" },
            realtimeEnabled = false,
        )

        controller.configure("http://192.168.0.3:8787", "token")

        assertTrue(controller.state.value.message.orEmpty().contains("HTTPS"))
        assertEquals(ConnectionStatus.SETUP, controller.state.value.connection)
        controller.close()
    }

    @Test
    fun `release cold boot never contacts a stored cleartext server`() = runBlocking {
        var networkRequests = 0
        val cleartext = ConnectionSettings(
            baseUrl = "http://127.0.0.1:8787",
            token = "stored-token",
            deviceId = "device",
            deviceName = "test",
        )
        val controller = StintController(
            client = client {
                networkRequests += 1
                jsonResponse(snapshot(1_000L, 1))
            },
            localStore = FakeStore(cleartext, snapshot(1_000L, 1)),
            clock = { 1_000L },
            newId = { "id" },
            realtimeEnabled = false,
        )

        delay(100)

        assertEquals(0, networkRequests)
        assertEquals(ConnectionStatus.SETUP, controller.state.value.connection)
        assertNull(controller.state.value.snapshot)
        assertTrue(controller.state.value.message.orEmpty().contains("HTTPS"))
        controller.close()
    }

    @Test
    fun `clock style defaults locally and loads a persisted choice without network`() {
        var networkRequests = 0
        val blankSettings = ConnectionSettings(deviceId = "device", deviceName = "test")
        val defaultController = StintController(
            client = client { networkRequests += 1; error("clock preference must not use network") },
            localStore = FakeStore(blankSettings, null),
            clock = { 1_000L },
            newId = { "id" },
            realtimeEnabled = false,
        )
        assertEquals(TimerClockStyle.ORBIT, defaultController.state.value.clockStyle)
        defaultController.close()

        val persistedController = StintController(
            client = client { networkRequests += 1; error("clock preference must not use network") },
            localStore = FakeStore(blankSettings, null, TimerClockStyle.HOURGLASS),
            clock = { 1_000L },
            newId = { "id" },
            realtimeEnabled = false,
        )
        assertEquals(TimerClockStyle.HOURGLASS, persistedController.state.value.clockStyle)
        assertEquals(0, networkRequests)
        persistedController.close()
    }

    @Test
    fun `clock style changes locally survives restart and preserves timer snapshot`() = runBlocking {
        var networkRequests = 0
        val currentSnapshot = snapshot(1_000L, 7, TimerStatus.RUNNING)
        val store = FakeStore(settings(), currentSnapshot)
        val first = StintController(
            client = client { networkRequests += 1; jsonResponse(currentSnapshot) },
            localStore = store,
            clock = { 1_000L },
            newId = { "id" },
            realtimeEnabled = false,
        )
        withTimeout(2_000) { first.state.first { it.connection == ConnectionStatus.CONNECTED } }
        val before = first.state.value.snapshot
        val requestsBeforePreferenceChange = networkRequests

        first.setClockStyle(TimerClockStyle.HOURGLASS)

        assertEquals(TimerClockStyle.HOURGLASS, first.state.value.clockStyle)
        assertEquals(before, first.state.value.snapshot)
        assertEquals(requestsBeforePreferenceChange, networkRequests)
        first.close()

        val restored = StintController(
            client = client { networkRequests += 1; jsonResponse(currentSnapshot) },
            localStore = store,
            clock = { 1_000L },
            newId = { "id" },
            realtimeEnabled = false,
        )
        withTimeout(2_000) { restored.state.first { it.connection == ConnectionStatus.CONNECTED } }
        assertEquals(TimerClockStyle.HOURGLASS, restored.state.value.clockStyle)
        assertEquals(before, restored.state.value.snapshot)
        assertEquals(requestsBeforePreferenceChange + 1, networkRequests)
        restored.close()
    }

    @Test
    fun `clock style storage failure retains the prior style and reports a local error`() {
        val store = FakeStore(
            ConnectionSettings(deviceId = "device", deviceName = "test"),
            null,
            TimerClockStyle.ORBIT,
        ).apply { failClockSave = true }
        val controller = StintController(
            client = client { error("clock preference must not use network") },
            localStore = store,
            clock = { 1_000L },
            newId = { "id" },
            realtimeEnabled = false,
        )

        controller.setClockStyle(TimerClockStyle.HOURGLASS)

        assertEquals(TimerClockStyle.ORBIT, controller.state.value.clockStyle)
        assertEquals(TimerClockStyle.ORBIT, store.loadClockStyle())
        assertTrue(controller.state.value.message.orEmpty().contains("시계 모양"))
        controller.close()
    }

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
        HttpClient(MockEngine(handler)) {
            expectSuccess = false
            install(ContentNegotiation) { json(json) }
        }

    private fun settings(baseUrl: String = "https://stint.example") = ConnectionSettings(
        baseUrl = baseUrl,
        token = if (baseUrl.contains("old")) "old-token" else "token",
        deviceId = "device",
        deviceName = "test",
    )

    private fun snapshot(
        serverNow: Long,
        revision: Long,
        status: TimerStatus = TimerStatus.IDLE,
    ) = Snapshot(
        serverNowEpochMs = serverNow,
        revision = revision,
        subjects = listOf(Subject("study", "공부")),
        timer = TimerState(subjectId = "study", status = status),
    )

    private inline fun <reified T> MockRequestHandleScope.jsonResponse(
        value: T,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpResponseData = respond(
        content = json.encodeToString(value),
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private class TestTimeout : Exception("timeout")

    private class FakeStore(
        initialSettings: ConnectionSettings,
        initialSnapshot: Snapshot?,
        initialClockStyle: TimerClockStyle = TimerClockStyle.ORBIT,
    ) : LocalStore {
        private var settings = initialSettings
        private var snapshot = initialSnapshot
        private var snapshotIdentity = identity(initialSettings)
        var delayLoads = false
        var beforeSnapshotSave: ((Snapshot) -> Unit)? = null
        private var clockStyle = initialClockStyle
        var failClockSave = false
        val savedRevisions = mutableListOf<Long>()
        val savedIdentityRevisions = mutableListOf<Pair<String, Long>>()

        override fun loadSettings(): ConnectionSettings = settings

        override fun saveSettings(settings: ConnectionSettings) {
            this.settings = settings
        }

        override fun loadSnapshot(): Snapshot? =
            snapshot.takeIf { snapshotIdentity == identity(settings) && !delayLoads }

        override fun saveSnapshot(snapshot: Snapshot) {
            beforeSnapshotSave?.invoke(snapshot)
            this.snapshot = snapshot
            snapshotIdentity = identity(settings)
            savedRevisions += snapshot.revision
            savedIdentityRevisions += identity(settings) to snapshot.revision
        }

        override fun loadClockStyle(): TimerClockStyle = clockStyle

        override fun saveClockStyle(style: TimerClockStyle) {
            check(!failClockSave) { "injected clock style save failure" }
            clockStyle = style
        }

        private fun identity(settings: ConnectionSettings) = "${settings.baseUrl}:${settings.token}"
    }
}
