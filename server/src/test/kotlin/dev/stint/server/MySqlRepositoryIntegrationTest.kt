package dev.stint.server

import dev.stint.shared.MutationContext
import dev.stint.shared.SaveSubjectRequest
import dev.stint.shared.TimerAction
import dev.stint.shared.TimerCommand
import dev.stint.shared.TimerPhase
import dev.stint.shared.TimerStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class MySqlRepositoryIntegrationTest {
    private lateinit var database: Database
    private lateinit var clock: MutableClock
    private lateinit var repository: StintRepository

    @BeforeTest
    fun prepareDatabase() {
        val url = System.getenv("STINT_TEST_DB_URL")
        val user = System.getenv("STINT_TEST_DB_USER")
        val password = System.getenv("STINT_TEST_DB_PASSWORD")
        assumeTrue(!url.isNullOrBlank() && !user.isNullOrBlank() && password != null, "MySQL test environment is not configured")
        val config = ServerConfig(url!!, user!!, password!!, "test-token-that-is-long-enough", "127.0.0.1", 0)
        database = Database(config)
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
        clock = MutableClock(10_000)
        repository = StintRepository(database, clock)
    }

    @AfterTest
    fun closeDatabase() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun `concurrent commands with one revision allow exactly one winner`() {
        val revision = repository.snapshot().revision
        val gate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val calls = listOf("mac-command" to "mac", "android-command" to "android").map { (command, device) ->
            executor.submit<Result<MutationResult>> {
                gate.await()
                runCatching { repository.execute(start(command, device, revision)) }
            }
        }

        gate.countDown()
        val results = calls.map { it.get() }
        executor.shutdown()

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.exceptionOrNull() is RevisionConflictException })
        assertEquals(TimerStatus.RUNNING, repository.snapshot().timer.status)
    }

    @Test
    fun `repeating a command id returns current state without a duplicate record`() {
        val started = repository.execute(start("start", "mac", repository.snapshot().revision)).snapshot
        clock.value.set(12_500)
        val finish = TimerCommand(
            context = context("finish", "mac", started.revision),
            action = TimerAction.FINISH,
        )

        val first = repository.execute(finish)
        val second = repository.execute(finish)

        assertTrue(first.changed)
        assertFalse(second.changed)
        assertEquals(first.snapshot.revision, second.snapshot.revision)
        assertEquals(1, repository.snapshot().sessions.size)
    }

    @Test
    fun `a new repository settles a timer that elapsed while the server was down`() {
        repository.execute(
            TimerCommand(
                context = context("start-short", "mac", repository.snapshot().revision),
                action = TimerAction.START,
                subjectId = "study",
                focusSeconds = 1,
            ),
        )
        clock.value.set(20_000)

        val afterRestart = StintRepository(database, clock)
        val settlement = afterRestart.settleExpired()

        assertEquals(1, settlement?.snapshot?.sessions?.size)
        assertEquals(TimerStatus.RUNNING, settlement?.snapshot?.timer?.status)
        assertEquals(TimerPhase.SHORT_BREAK, settlement?.snapshot?.timer?.phase)
        assertEquals(1, afterRestart.snapshot().sessions.size)
    }

    @Test
    fun `an expired timer is settled in the same locked transaction before a stale command conflicts`() {
        val started = repository.execute(
            TimerCommand(
                context = context("start-expiring", "mac", repository.snapshot().revision),
                action = TimerAction.START,
                subjectId = "study",
                focusSeconds = 1,
            ),
        ).snapshot
        clock.value.set(20_000)

        val conflict = assertFailsWith<RevisionConflictException> {
            repository.execute(
                TimerCommand(
                    context = context("pause-too-late", "android", started.revision),
                    action = TimerAction.PAUSE,
                ),
            )
        }

        assertTrue(conflict.stateChanged)
        assertEquals(1, conflict.latest.sessions.size)
        assertEquals(conflict.latest.revision, repository.snapshot().revision)
        assertEquals(1, repository.snapshot().sessions.size)
    }

    @Test
    fun `invalid duration is rejected without consuming the revision`() {
        val revision = repository.snapshot().revision
        assertFailsWith<ValidationException> {
            repository.execute(
                TimerCommand(
                    context = context("invalid", "mac", revision),
                    action = TimerAction.START,
                    subjectId = "study",
                    focusSeconds = 0,
                ),
            )
        }
        assertEquals(revision, repository.snapshot().revision)
    }

    @Test
    fun `paused countdown duration changes once without resetting elapsed progress`() {
        val paused = pauseStartedTimer("duration")
        val command = TimerCommand(
            context = context("set-duration", "android", paused.revision),
            action = TimerAction.SET_DURATION,
            targetSeconds = 120,
        )

        val first = repository.execute(command)
        val repeated = repository.execute(command)

        assertTrue(first.changed)
        assertFalse(repeated.changed)
        assertEquals(first.snapshot.revision, repeated.snapshot.revision)
        assertEquals(2_500, first.snapshot.timer.accumulatedMillis)
        assertEquals(120, first.snapshot.timer.targetSeconds)
        assertEquals(paused.timer.id, first.snapshot.timer.id)
        assertEquals(paused.timer.startedAtEpochMs, first.snapshot.timer.startedAtEpochMs)
        assertEquals(TimerStatus.PAUSED, first.snapshot.timer.status)
    }

    @Test
    fun `duration below exact elapsed time rolls back without consuming revision`() {
        val paused = pauseStartedTimer("invalid-duration")

        assertFailsWith<ValidationException> {
            repository.execute(
                TimerCommand(
                    context = context("too-short", "mac", paused.revision),
                    action = TimerAction.SET_DURATION,
                    targetSeconds = 2,
                ),
            )
        }

        val current = repository.snapshot()
        assertEquals(paused.revision, current.revision)
        assertEquals(paused.timer, current.timer)
    }

    @Test
    fun `two duration changes at one revision allow one winner`() {
        val paused = pauseStartedTimer("competing-duration")
        val gate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val calls = listOf(120, 180).mapIndexed { index, target ->
            executor.submit<Result<MutationResult>> {
                gate.await()
                runCatching {
                    repository.execute(
                        TimerCommand(
                            context = context("duration-$index", "device-$index", paused.revision),
                            action = TimerAction.SET_DURATION,
                            targetSeconds = target,
                        ),
                    )
                }
            }
        }

        gate.countDown()
        val results = calls.map { it.get() }
        executor.shutdown()

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.exceptionOrNull() is RevisionConflictException })
        assertTrue(repository.snapshot().timer.targetSeconds in setOf(120, 180))
    }

    @Test
    fun `snapshot reads one repeatable revision while writes commit concurrently`() {
        val reading = repository.snapshot().subjects.first { it.id == "reading" }
        val running = AtomicBoolean(true)
        val executor = Executors.newFixedThreadPool(2)
        val writer = executor.submit {
            var current = repository.snapshot()
            repeat(80) { index ->
                val nextRevision = current.revision + 1
                current = repository.saveSubject(
                    SaveSubjectRequest(
                        context = context("subject-$index", "mac", current.revision),
                        subject = reading.copy(name = "revision-$nextRevision"),
                    ),
                ).snapshot
            }
            running.set(false)
        }
        val reader = executor.submit {
            while (running.get()) {
                val snapshot = repository.snapshot()
                val name = snapshot.subjects.first { it.id == "reading" }.name
                if (name.startsWith("revision-")) {
                    assertEquals(snapshot.revision, name.substringAfter("revision-").toLong())
                } else {
                    assertEquals(0, snapshot.revision)
                }
            }
        }

        writer.get()
        reader.get()
        executor.shutdown()
    }

    private fun start(command: String, device: String, revision: Long) = TimerCommand(
        context = context(command, device, revision),
        action = TimerAction.START,
        subjectId = "study",
    )

    private fun pauseStartedTimer(prefix: String): dev.stint.shared.Snapshot {
        val started = repository.execute(start("$prefix-start", "mac", repository.snapshot().revision)).snapshot
        clock.value.set(12_500)
        return repository.execute(
            TimerCommand(
                context = context("$prefix-pause", "mac", started.revision),
                action = TimerAction.PAUSE,
            ),
        ).snapshot
    }

    private fun context(command: String, device: String, revision: Long) = MutationContext(
        commandId = command,
        expectedRevision = revision,
        deviceId = device,
        deviceName = device.replaceFirstChar { it.uppercase() },
    )

    private class MutableClock(initial: Long) : Clock {
        val value = AtomicLong(initial)
        override fun nowEpochMs(): Long = value.get()
    }
}
