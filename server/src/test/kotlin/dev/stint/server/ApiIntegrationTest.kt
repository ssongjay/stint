package dev.stint.server

import dev.stint.shared.MutationContext
import dev.stint.shared.TimerAction
import dev.stint.shared.TimerCommand
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiIntegrationTest {
    @Test
    fun `health is public while snapshots require auth and bounded device headers`() {
        val testConfig = testConfigOrSkip()
        val database = Database(testConfig)
        reset(database)

        testApplication {
            application { stintModule(testConfig, database, Clock { 10_000 }) }

            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/snapshot").status)
            assertEquals(
                HttpStatusCode.BadRequest,
                client.get("/v1/snapshot") {
                    header(HttpHeaders.Authorization, "Bearer ${testConfig.authToken}")
                }.status,
            )
            assertEquals(
                HttpStatusCode.OK,
                client.get("/v1/snapshot") {
                    header(HttpHeaders.Authorization, "Bearer ${testConfig.authToken}")
                    header("X-Device-Id", "integration-test")
                    header("X-Device-Name", "%EB%A7%A5%EB%B6%81")
                }.status,
            )
            val koreanDeviceCommand = TimerCommand(
                context = MutationContext("korean-device", 0, "integration-test", "맥북"),
                action = TimerAction.START,
                subjectId = "study",
            )
            assertEquals(
                HttpStatusCode.OK,
                client.post("/v1/timer/commands") {
                    header(HttpHeaders.Authorization, "Bearer ${testConfig.authToken}")
                    header("X-Device-Id", "integration-test")
                    header("X-Device-Name", "%EB%A7%A5%EB%B6%81")
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(koreanDeviceCommand))
                }.status,
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                client.post("/v1/timer/commands") {
                    header(HttpHeaders.Authorization, "Bearer ${testConfig.authToken}")
                    header("X-Device-Id", "integration-test")
                    header("X-Device-Name", "%EB%A7%A5%EB%B6%81")
                    contentType(ContentType.Application.Json)
                    setBody(
                        Json.encodeToString(
                            koreanDeviceCommand.copy(
                                context = koreanDeviceCommand.context.copy(
                                    commandId = "wrong-name",
                                    expectedRevision = 1,
                                    deviceName = "다른 기기",
                                ),
                            ),
                        ),
                    )
                }.status,
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                client.get("/v1/snapshot") {
                    header(HttpHeaders.Authorization, "Bearer ${testConfig.authToken}")
                    header("X-Device-Id", "integration-test")
                    header("X-Device-Name", "%ZZ")
                }.status,
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                client.post("/v1/timer/commands") {
                    header(HttpHeaders.Authorization, "Bearer ${testConfig.authToken}")
                    header("X-Device-Id", "integration-test")
                    header("X-Device-Name", "Integration test")
                    contentType(ContentType.Application.Json)
                    setBody("{not-json")
                }.status,
            )
        }
    }

    private fun testConfigOrSkip(): ServerConfig {
        val url = System.getenv("STINT_TEST_DB_URL")
        val user = System.getenv("STINT_TEST_DB_USER")
        val password = System.getenv("STINT_TEST_DB_PASSWORD")
        assumeTrue(!url.isNullOrBlank() && !user.isNullOrBlank() && password != null, "MySQL test environment is not configured")
        return ServerConfig(url!!, user!!, password!!, "test-token-that-is-long-enough", "127.0.0.1", 0)
    }

    private fun reset(database: Database) {
        database.migrate()
        database.connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("SET FOREIGN_KEY_CHECKS=0")
                listOf("processed_commands", "focus_sessions", "devices", "timer_state", "subjects", "app_state")
                    .forEach { statement.execute("TRUNCATE TABLE $it") }
                statement.execute("SET FOREIGN_KEY_CHECKS=1")
            }
        }
        database.migrate()
    }
}
