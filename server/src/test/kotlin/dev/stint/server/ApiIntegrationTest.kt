package dev.stint.server

import dev.stint.shared.MutationContext
import dev.stint.shared.DayPlan
import dev.stint.shared.HourPlan
import dev.stint.shared.PlanCycle
import dev.stint.shared.SaveHourPlanRequest
import dev.stint.shared.SavePlanDefaultRequest
import dev.stint.shared.Snapshot
import dev.stint.shared.TimerAction
import dev.stint.shared.TimerCommand
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
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

    @Test
    fun `plan routes synchronize hour edits defaults and stale device conflicts`() {
        val testConfig = testConfigOrSkip()
        val database = Database(testConfig)
        reset(database)
        val json = Json { ignoreUnknownKeys = true }
        val authenticated: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            header(HttpHeaders.Authorization, "Bearer ${testConfig.authToken}")
            header("X-Device-Id", "plan-device")
            header("X-Device-Name", "Plan%20device")
        }

        testApplication {
            application { stintModule(testConfig, database, Clock { 20_000 }) }

            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/plans/2026-11-26").status)
            val emptyResponse = client.get("/v1/plans/2026-11-26") { authenticated() }
            assertEquals(HttpStatusCode.OK, emptyResponse.status)
            val empty = json.decodeFromString<DayPlan>(emptyResponse.bodyAsText())
            val snapshotResponse = client.get("/v1/snapshot") { authenticated() }
            val snapshot = json.decodeFromString<Snapshot>(snapshotResponse.bodyAsText())

            val save = SaveHourPlanRequest(
                context = MutationContext("api-plan", snapshot.revision, "plan-device", "Plan device"),
                expectedPlanRevision = empty.revision,
                plan = HourPlan(14, "자료 조사", PlanCycle.FIFTEEN_FIVE, listOf(13, 15, 15)),
            )
            val savedResponse = client.put("/v1/plans/2026-11-26/hours/14") {
                authenticated()
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(save))
            }
            assertEquals(HttpStatusCode.OK, savedResponse.status)
            val saved = json.decodeFromString<Snapshot>(savedResponse.bodyAsText())
            assertEquals(1, saved.planRevision)

            val staleResponse = client.put("/v1/plans/2026-11-26/hours/15") {
                authenticated()
                contentType(ContentType.Application.Json)
                setBody(
                    Json.encodeToString(
                        save.copy(
                            context = save.context.copy(commandId = "api-stale"),
                            plan = HourPlan(15, "오래된 변경", PlanCycle.TWENTY_FIVE_FIVE),
                        ),
                    ),
                )
            }
            assertEquals(HttpStatusCode.Conflict, staleResponse.status)

            val defaultResponse = client.put("/v1/plans/default") {
                authenticated()
                contentType(ContentType.Application.Json)
                setBody(
                    Json.encodeToString(
                        SavePlanDefaultRequest(
                            context = save.context.copy(commandId = "api-default"),
                            expectedPlanRevision = 1,
                            cycle = PlanCycle.TWENTY_FIVE_FIVE,
                        ),
                    ),
                )
            }
            assertEquals(HttpStatusCode.OK, defaultResponse.status)
            val actualResponse = client.get("/v1/plans/2026-11-26") { authenticated() }
            val actual = json.decodeFromString<DayPlan>(actualResponse.bodyAsText())
            assertEquals(2, actual.revision)
            assertEquals(PlanCycle.TWENTY_FIVE_FIVE, actual.defaultCycle)
            assertEquals(save.plan, actual.hours.single())
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
                listOf(
                    "processed_commands", "hour_plans", "recent_memos", "focus_sessions", "devices",
                    "timer_state", "subjects", "plan_state", "app_state",
                )
                    .forEach { statement.execute("TRUNCATE TABLE $it") }
                statement.execute("SET FOREIGN_KEY_CHECKS=1")
            }
        }
        database.migrate()
    }
}
