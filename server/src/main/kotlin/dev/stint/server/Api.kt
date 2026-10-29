package dev.stint.server

import dev.stint.shared.ApiError
import dev.stint.shared.SaveMemoRequest
import dev.stint.shared.SaveSubjectRequest
import dev.stint.shared.Snapshot
import dev.stint.shared.TimerCommand
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.net.URLDecoder
import java.security.MessageDigest

@Serializable
private data class HealthResponse(val ok: Boolean)

internal class EventHub(initial: Snapshot) {
    private val snapshots = MutableStateFlow(initial)

    internal val current: Snapshot
        get() = snapshots.value

    fun publish(snapshot: Snapshot) {
        snapshots.update { current ->
            if (snapshot.revision >= current.revision) snapshot else current
        }
    }

    suspend fun collect(send: suspend (Snapshot) -> Unit) {
        snapshots.collectLatest(send)
    }
}

fun Application.stintModule(
    config: ServerConfig,
    database: Database = Database(config),
    clock: Clock = SystemClock,
) {
    val wireJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    database.migrate()
    val repository = StintRepository(database, clock)
    repository.settleExpired()
    val events = EventHub(repository.snapshot())
    val appLog = environment.log

    install(ContentNegotiation) { json(wireJson) }
    install(WebSockets)
    install(StatusPages) {
        exception<RevisionConflictException> { call, error ->
            if (error.stateChanged) events.publish(error.latest)
            call.respond(
                HttpStatusCode.Conflict,
                ApiError("revision_conflict", error.message ?: "State changed", error.latest),
            )
        }
        exception<ValidationException> { call, error ->
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request", error.message ?: "Invalid request"))
        }
        exception<NotFoundException> { call, error ->
            call.respond(HttpStatusCode.NotFound, ApiError("not_found", error.message ?: "Not found"))
        }
        exception<BadRequestException> { call, error ->
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request", error.message ?: "Invalid request"))
        }
        exception<CancellationException> { _, error -> throw error }
        exception<Throwable> { call, error ->
            appLog.error("Request failed", error)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error", "The server could not complete the request"))
        }
    }

    routing {
        get("/health") {
            val ready = withContext(Dispatchers.IO) { database.isReady() }
            call.respond(if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, HealthResponse(ready))
        }

        route("/v1") {
            get("/snapshot") {
                if (!call.hasValidToken(config.authToken)) return@get
                val device = call.requireDevice() ?: return@get
                val settled = withContext(Dispatchers.IO) { repository.settleExpired() }
                settled?.let { events.publish(it.snapshot) }
                val snapshot = withContext(Dispatchers.IO) {
                    repository.touchDevice(device.first, device.second)
                    repository.snapshot()
                }
                call.respond(snapshot)
            }

            post("/timer/commands") {
                if (!call.hasValidToken(config.authToken)) return@post
                val request = call.receive<TimerCommand>()
                call.requireMatchingDevice(request.context.deviceId, request.context.deviceName) ?: return@post
                val settled = withContext(Dispatchers.IO) { repository.settleExpired() }
                settled?.let { events.publish(it.snapshot) }
                val result = withContext(Dispatchers.IO) { repository.execute(request) }
                if (result.changed) events.publish(result.snapshot)
                call.respond(result.snapshot)
            }

            post("/subjects") {
                if (!call.hasValidToken(config.authToken)) return@post
                val request = call.receive<SaveSubjectRequest>()
                call.requireMatchingDevice(request.context.deviceId, request.context.deviceName) ?: return@post
                val result = withContext(Dispatchers.IO) { repository.saveSubject(request) }
                if (result.changed) events.publish(result.snapshot)
                call.respond(result.snapshot)
            }

            put("/sessions/{id}/memo") {
                if (!call.hasValidToken(config.authToken)) return@put
                val id = call.parameters["id"] ?: throw ValidationException("Session id is required")
                val request = call.receive<SaveMemoRequest>()
                call.requireMatchingDevice(request.context.deviceId, request.context.deviceName) ?: return@put
                val result = withContext(Dispatchers.IO) { repository.saveMemo(id, request) }
                if (result.changed) events.publish(result.snapshot)
                call.respond(result.snapshot)
            }

            webSocket("/events") {
                if (!call.hasValidToken(config.authToken, respond = false)) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                    return@webSocket
                }
                val deviceId = call.request.headers["X-Device-Id"]
                val encodedDeviceName = call.request.headers["X-Device-Name"]
                if (deviceId.isNullOrBlank() || encodedDeviceName.isNullOrBlank()) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Device headers are required"))
                    return@webSocket
                }
                val deviceName = decodeDeviceName(encodedDeviceName)
                if (deviceName == null) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid device name encoding"))
                    return@webSocket
                }
                try {
                    withContext(Dispatchers.IO) { repository.touchDevice(deviceId, deviceName) }
                } catch (_: ValidationException) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid device headers"))
                    return@webSocket
                }
                events.publish(withContext(Dispatchers.IO) { repository.snapshot() })
                events.collect { snapshot -> send(Frame.Text(wireJson.encodeToString(Snapshot.serializer(), snapshot))) }
            }
        }
    }

    val ticker: Job = launch {
        while (isActive) {
            delay(1_000)
            runCatching { withContext(Dispatchers.IO) { repository.settleExpired() } }
                .onSuccess { result -> result?.let { events.publish(it.snapshot) } }
                .onFailure { error -> appLog.error("Timer settlement failed", error) }
        }
    }
    monitor.subscribe(ApplicationStopped) {
        ticker.cancel()
        database.close()
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.hasValidToken(
    expectedToken: String,
    respond: Boolean = true,
): Boolean {
    val supplied = request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substringAfter(' ')
        .orEmpty()
    val valid = MessageDigest.isEqual(
        supplied.toByteArray(StandardCharsets.UTF_8),
        expectedToken.toByteArray(StandardCharsets.UTF_8),
    )
    if (!valid && respond) {
        respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "A valid connection key is required"))
    }
    return valid
}

private suspend fun io.ktor.server.application.ApplicationCall.requireDevice(): Pair<String, String>? {
    val id = request.headers["X-Device-Id"]
    val encodedName = request.headers["X-Device-Name"]
    if (id.isNullOrBlank() || encodedName.isNullOrBlank()) {
        respond(HttpStatusCode.BadRequest, ApiError("invalid_device", "Device headers are required"))
        return null
    }
    val name = decodeDeviceName(encodedName)
    if (name == null) {
        respond(HttpStatusCode.BadRequest, ApiError("invalid_device", "Device name encoding is invalid"))
        return null
    }
    if (id.length > 128 || name.length > 128) {
        respond(HttpStatusCode.BadRequest, ApiError("invalid_device", "Device headers are too long"))
        return null
    }
    return id to name
}

private fun decodeDeviceName(encoded: String): String? = try {
    URLDecoder.decode(encoded, StandardCharsets.UTF_8).takeIf { it.isNotBlank() }
} catch (_: IllegalArgumentException) {
    null
}

private suspend fun io.ktor.server.application.ApplicationCall.requireMatchingDevice(
    expectedId: String,
    expectedName: String,
): Pair<String, String>? {
    val device = requireDevice() ?: return null
    if (device.first != expectedId || device.second != expectedName) {
        respond(HttpStatusCode.BadRequest, ApiError("invalid_device", "Device headers do not match the command"))
        return null
    }
    return device
}
