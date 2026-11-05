package dev.stint.server

import dev.stint.shared.MutationContext
import dev.stint.shared.SaveSubjectRequest
import dev.stint.shared.SaveMemoRequest
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
                listOf("processed_commands", "recent_memos", "focus_sessions", "devices", "timer_state", "subjects", "app_state")
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
    fun `recent memos follow actual writes and a duplicate moves to the front once`() {
        val sessionId = completedSession("memo-order")
        var current = repository.snapshot()
        current = saveMemo(sessionId, "첫 회고", "memo-first", current.revision)
        clock.value.incrementAndGet()
        current = saveMemo(sessionId, "둘째 회고", "memo-second", current.revision)
        clock.value.incrementAndGet()
        val repeated = saveMemo(sessionId, "첫 회고", "memo-first-again", current.revision)

        assertEquals(listOf("첫 회고", "둘째 회고"), repeated.recentMemos)
        val replay = repository.saveMemo(
            sessionId,
            SaveMemoRequest(context("memo-first-again", "mac", repeated.revision), "둘째 회고"),
        )
        assertFalse(replay.changed)
        assertEquals(repeated.recentMemos, replay.snapshot.recentMemos)
        assertEquals(repeated.revision, replay.snapshot.revision)
    }

    @Test
    fun `recent memo history keeps five unique writes and ignores a blank clear`() {
        val sessionId = completedSession("memo-cap")
        var current = repository.snapshot()
        (1..6).forEach { index ->
            clock.value.incrementAndGet()
            current = saveMemo(sessionId, " 회고 $index ", "memo-$index", current.revision)
        }
        assertEquals(listOf("회고 6", "회고 5", "회고 4", "회고 3", "회고 2"), current.recentMemos)

        val cleared = saveMemo(sessionId, "   ", "memo-clear", current.revision)
        assertEquals(current.recentMemos, cleared.recentMemos)
        assertEquals("", cleared.sessions.first { it.id == sessionId }.memo)
    }

    @Test
    fun `migration imports five distinct legacy memos once without overwriting new history`() {
        database.connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO focus_sessions(
                  id, subject_id, started_at_ms, ended_at_ms, duration_seconds, mode, memo, device_name, created_at_ms
                ) VALUES (?, 'study', ?, ?, 60, 'COUNTDOWN', ?, 'Legacy', ?)
                """.trimIndent(),
            ).use { statement ->
                listOf(
                    Triple("legacy-1", 1_000L, "첫 기록"),
                    Triple("legacy-2", 2_000L, "중복 기록"),
                    Triple("legacy-3", 3_000L, "중복 기록"),
                    Triple("legacy-4", 4_000L, "넷"),
                    Triple("legacy-5", 5_000L, "다섯"),
                    Triple("legacy-6", 6_000L, "여섯"),
                    Triple("legacy-7", 7_000L, "일곱"),
                ).forEach { (id, endedAt, memo) ->
                    statement.setString(1, id)
                    statement.setLong(2, endedAt - 60_000)
                    statement.setLong(3, endedAt)
                    statement.setString(4, memo)
                    statement.setLong(5, endedAt)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
        database.migrate()
        assertEquals(listOf("일곱", "여섯", "다섯", "넷", "중복 기록"), repository.snapshot().recentMemos)

        val current = repository.snapshot()
        val saved = saveMemo("legacy-1", "새 회고", "new-after-migration", current.revision)
        database.migrate()
        assertEquals(saved.recentMemos, repository.snapshot().recentMemos)
        assertEquals("새 회고", repository.snapshot().recentMemos.first())
    }

    @Test
    fun `pomodoro flow settings persist and an old client edit preserves them`() {
        val original = repository.snapshot().subjects.first { it.id == "study" }
        val saved = repository.saveSubject(
            SaveSubjectRequest(
                context = context("flow-settings", "mac", repository.snapshot().revision),
                subject = original.copy(pomodoroEnabled = false, autoStartBreak = false),
                includesFlowSettings = true,
            ),
        ).snapshot

        val configured = saved.subjects.first { it.id == original.id }
        assertFalse(configured.pomodoroEnabled)
        assertFalse(configured.autoStartBreak)

        val editedByOldClient = repository.saveSubject(
            SaveSubjectRequest(
                context = context("old-client-edit", "android", saved.revision),
                subject = configured.copy(
                    name = "이름만 변경",
                    pomodoroEnabled = true,
                    autoStartBreak = true,
                ),
            ),
        ).snapshot.subjects.first { it.id == original.id }

        assertEquals("이름만 변경", editedByOldClient.name)
        assertFalse(editedByOldClient.pomodoroEnabled)
        assertFalse(editedByOldClient.autoStartBreak)
    }

    @Test
    fun `subject flow column migration is idempotent and defaults preserve current behavior`() {
        database.migrate()
        database.migrate()

        val columns = database.connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'subjects'
                  AND column_name IN ('pomodoro_enabled', 'auto_start_break')
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { result ->
                    buildSet { while (result.next()) add(result.getString(1)) }
                }
            }
        }

        assertEquals(setOf("pomodoro_enabled", "auto_start_break"), columns)
        val seeded = repository.snapshot().subjects.first { it.id == "study" }
        assertTrue(seeded.pomodoroEnabled)
        assertTrue(seeded.autoStartBreak)
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

    private fun completedSession(prefix: String): String {
        val started = repository.execute(start("$prefix-start", "mac", repository.snapshot().revision)).snapshot
        clock.value.addAndGet(2_000)
        return repository.execute(
            TimerCommand(context("$prefix-finish", "mac", started.revision), TimerAction.FINISH),
        ).snapshot.sessions.first().id
    }

    private fun saveMemo(sessionId: String, memo: String, command: String, revision: Long) =
        repository.saveMemo(sessionId, SaveMemoRequest(context(command, "mac", revision), memo)).snapshot

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
