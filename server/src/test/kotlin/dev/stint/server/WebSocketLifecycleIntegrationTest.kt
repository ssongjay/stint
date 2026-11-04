package dev.stint.server

import dev.stint.shared.Snapshot
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real Netty coverage for a lifecycle that the in-memory test engine cannot reproduce. */
class WebSocketLifecycleIntegrationTest {
    private var database: Database? = null
    private val client = HttpClient.newHttpClient()

    @AfterTest
    fun closeDatabase() {
        database?.close()
        database = null
    }

    @Test
    fun `graceful and aborted clients release subscriptions while an active peer receives updates`() = runBlocking {
        val config = testConfigOrSkip()
        val database = Database(config).also { this@WebSocketLifecycleIntegrationTest.database = it }
        database.migrate()
        val events = EventHub(Snapshot(serverNowEpochMs = 1, revision = 1))
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            stintModule(config.copy(port = 0), database, Clock { 10_000 }, events)
        }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            val active = connect(port, config, "active")
            awaitSubscriptions(events, 1)

            repeat(lifecycleIterations()) { index ->
                val graceful = connect(port, config, "graceful-$index")
                awaitSubscriptions(events, 2)
                graceful.socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
                awaitSubscriptions(events, 1)

                val aborted = connect(port, config, "aborted-$index")
                awaitSubscriptions(events, 2)
                aborted.socket.abort()
                awaitSubscriptions(events, 1)
            }

            val published = events.current.copy(
                serverNowEpochMs = events.current.serverNowEpochMs + 1,
                revision = events.current.revision + 1,
            )
            events.publish(published)
            assertTrue(
                active.listener.awaitTextContaining("\"revision\":${published.revision}"),
                "The remaining peer missed an update",
            )
            active.socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
            awaitSubscriptions(events, 0)
        } finally {
            server.stop(500, 2_000)
        }
    }

    private suspend fun awaitSubscriptions(events: EventHub, expected: Int) {
        withTimeout(5_000) {
            while (events.subscriptionCount != expected) delay(20)
        }
        assertEquals(expected, events.subscriptionCount)
    }

    private fun connect(port: Int, config: ServerConfig, id: String): ConnectedClient {
        val listener = RecordingListener()
        val socket = client.newWebSocketBuilder()
            .header("Authorization", "Bearer ${config.authToken}")
            .header("X-Device-Id", id)
            .header("X-Device-Name", id)
            .buildAsync(URI("ws://127.0.0.1:$port/v1/events"), listener)
            .get(5, TimeUnit.SECONDS)
        listener.opened.get(5, TimeUnit.SECONDS)
        return ConnectedClient(socket, listener)
    }

    private fun testConfigOrSkip(): ServerConfig {
        val url = System.getenv("STINT_TEST_DB_URL")
        val user = System.getenv("STINT_TEST_DB_USER")
        val password = System.getenv("STINT_TEST_DB_PASSWORD")
        assumeTrue(!url.isNullOrBlank() && !user.isNullOrBlank() && password != null, "MySQL test environment is not configured")
        return ServerConfig(url!!, user!!, password!!, "test-token-that-is-long-enough", "127.0.0.1", 0)
    }

    private fun lifecycleIterations(): Int =
        (System.getenv("STINT_WEBSOCKET_LIFECYCLE_ITERATIONS")
            ?: System.getProperty("stint.websocketLifecycleIterations"))
            ?.toIntOrNull()
            ?.coerceIn(1, 2_000)
            ?: 12

    private data class ConnectedClient(val socket: WebSocket, val listener: RecordingListener)

    private class RecordingListener : WebSocket.Listener {
        val opened = CompletableFuture<Unit>()
        private val texts = CopyOnWriteArrayList<String>()

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
            opened.complete(Unit)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
            texts += data.toString()
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        fun awaitTextContaining(fragment: String): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                if (texts.any { fragment in it }) return true
                Thread.sleep(20)
            }
            return false
        }
    }
}
