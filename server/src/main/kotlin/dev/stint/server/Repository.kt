package dev.stint.server

import dev.stint.shared.DeviceInfo
import dev.stint.shared.FocusSession
import dev.stint.shared.MutationContext
import dev.stint.shared.SaveMemoRequest
import dev.stint.shared.SaveSubjectRequest
import dev.stint.shared.Snapshot
import dev.stint.shared.Subject
import dev.stint.shared.TimerAction
import dev.stint.shared.TimerCommand
import dev.stint.shared.TimerMode
import dev.stint.shared.TimerPhase
import dev.stint.shared.TimerState
import dev.stint.shared.TimerStatus
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

class ValidationException(message: String) : RuntimeException(message)
class NotFoundException(message: String) : RuntimeException(message)
class RevisionConflictException(
    val latest: Snapshot,
    val stateChanged: Boolean = false,
) : RuntimeException("The state changed on another device")

data class MutationResult(val snapshot: Snapshot, val changed: Boolean)

class StintRepository(
    private val database: Database,
    private val clock: Clock = SystemClock,
) {
    fun snapshot(): Snapshot = database.connection().use { connection ->
        connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
        connection.isReadOnly = true
        connection.autoCommit = false
        try {
            val result = snapshot(connection, clock.nowEpochMs())
            connection.commit()
            result
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        }
    }

    fun touchDevice(id: String, name: String) {
        validateDevice(id, name)
        database.connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO devices(id, name, last_seen_at_ms) VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE name = VALUES(name), last_seen_at_ms = VALUES(last_seen_at_ms)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(2, name)
                statement.setLong(3, clock.nowEpochMs())
                statement.executeUpdate()
            }
        }
    }

    fun execute(command: TimerCommand): MutationResult = mutate(command.context) { connection, now ->
        val timer = readTimer(connection, forUpdate = true)
        val subject = timer.subjectId?.let { readSubject(connection, it) }
        val next = when (command.action) {
            TimerAction.START -> {
                if (timer.status != TimerStatus.IDLE) throw ValidationException("Finish the current timer before starting another")
                val requestedSubjectId = command.subjectId ?: timer.subjectId
                    ?: throw ValidationException("subjectId is required")
                val requestedSubject = readSubject(connection, requestedSubjectId)
                    ?: throw NotFoundException("Subject was not found")
                if (requestedSubject.archived) throw ValidationException("An archived subject cannot be started")
                val target = command.focusSeconds ?: requestedSubject.focusSeconds
                validateDuration(target, "focusSeconds")
                timer.copy(
                    id = UUID.randomUUID().toString(),
                    subjectId = requestedSubject.id,
                    status = TimerStatus.RUNNING,
                    phase = TimerPhase.FOCUS,
                    mode = command.mode,
                    targetSeconds = target,
                    accumulatedMillis = 0,
                    runningSinceEpochMs = now,
                    startedAtEpochMs = now,
                    controllingDeviceId = command.context.deviceId,
                    controllingDeviceName = command.context.deviceName,
                )
            }
            TimerAction.PAUSE -> TimerEngine.pause(timer, now)
            TimerAction.RESUME -> TimerEngine.resume(
                timer,
                now,
                command.context.deviceId,
                command.context.deviceName,
            )
            TimerAction.FINISH -> {
                if (timer.status == TimerStatus.IDLE) throw ValidationException("There is no active timer")
                val settlement = TimerEngine.finish(timer, subject, now, command.context.deviceName)
                settlement.session?.let { insertSession(connection, it, now) }
                settlement.timer
            }
            TimerAction.SKIP_BREAK -> {
                if (timer.phase == TimerPhase.FOCUS || timer.status == TimerStatus.IDLE) {
                    throw ValidationException("There is no break to skip")
                }
                TimerEngine.idleFocus(timer, subject)
            }
        }
        writeTimer(connection, next)
    }

    fun saveSubject(request: SaveSubjectRequest): MutationResult = mutate(request.context) { connection, now ->
        validateSubject(request.subject)
        connection.prepareStatement(
            """
            INSERT INTO subjects(
              id, name, color_hex, focus_seconds, short_break_seconds, long_break_seconds,
              long_break_every, archived, created_at_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              name = VALUES(name), color_hex = VALUES(color_hex), focus_seconds = VALUES(focus_seconds),
              short_break_seconds = VALUES(short_break_seconds), long_break_seconds = VALUES(long_break_seconds),
              long_break_every = VALUES(long_break_every), archived = VALUES(archived)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, request.subject.id)
            statement.setString(2, request.subject.name.trim())
            statement.setString(3, request.subject.colorHex.uppercase())
            statement.setInt(4, request.subject.focusSeconds)
            statement.setInt(5, request.subject.shortBreakSeconds)
            statement.setInt(6, request.subject.longBreakSeconds)
            statement.setInt(7, request.subject.longBreakEvery)
            statement.setBoolean(8, request.subject.archived)
            statement.setLong(9, now)
            statement.executeUpdate()
        }
    }

    fun saveMemo(sessionId: String, request: SaveMemoRequest): MutationResult = mutate(request.context) { connection, _ ->
        if (sessionId.isBlank() || sessionId.length > 64) throw ValidationException("Invalid session id")
        if (request.memo.length > 4_000) throw ValidationException("Memo must be at most 4000 characters")
        val updated = connection.prepareStatement("UPDATE focus_sessions SET memo = ? WHERE id = ?").use { statement ->
            statement.setString(1, request.memo.trim())
            statement.setString(2, sessionId)
            statement.executeUpdate()
        }
        if (updated == 0) throw NotFoundException("Session was not found")
    }

    fun settleExpired(): MutationResult? = database.connection().use { connection ->
        connection.autoCommit = false
        try {
            val revision = lockRevision(connection)
            val settlement = settleLocked(connection, clock.nowEpochMs())
            if (settlement == null) {
                connection.rollback()
                return@use null
            }
            val now = clock.nowEpochMs()
            setRevision(connection, revision + 1)
            val result = snapshot(connection, now)
            connection.commit()
            MutationResult(result, changed = true)
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        }
    }

    private fun mutate(context: MutationContext, change: (Connection, Long) -> Unit): MutationResult {
        validateContext(context)
        return database.connection().use { connection ->
            connection.autoCommit = false
            try {
                val currentRevision = lockRevision(connection)
                if (isProcessed(connection, context.commandId)) {
                    val current = snapshot(connection, clock.nowEpochMs())
                    connection.commit()
                    return@use MutationResult(current, changed = false)
                }
                val now = clock.nowEpochMs()
                val settlement = settleLocked(connection, now)
                val effectiveRevision = currentRevision + if (settlement == null) 0 else 1
                if (settlement != null) setRevision(connection, effectiveRevision)
                if (context.expectedRevision != effectiveRevision) {
                    val latest = snapshot(connection, clock.nowEpochMs())
                    if (settlement == null) connection.rollback() else connection.commit()
                    throw RevisionConflictException(latest, stateChanged = settlement != null)
                }
                change(connection, now)
                touchDevice(connection, context.deviceId, context.deviceName, now)
                connection.prepareStatement(
                    "INSERT INTO processed_commands(command_id, processed_at_ms) VALUES (?, ?)",
                ).use { statement ->
                    statement.setString(1, context.commandId)
                    statement.setLong(2, now)
                    statement.executeUpdate()
                }
                setRevision(connection, effectiveRevision + 1)
                val result = snapshot(connection, now)
                connection.commit()
                MutationResult(result, changed = true)
            } catch (error: Throwable) {
                if (error !is RevisionConflictException || !error.stateChanged) {
                    runCatching { connection.rollback() }
                }
                throw error
            }
        }
    }

    private fun settleLocked(connection: Connection, now: Long): Settlement? {
        val timer = readTimer(connection, forUpdate = true)
        val subject = timer.subjectId?.let { readSubject(connection, it) } ?: return null
        val settlement = TimerEngine.settleExpired(timer, subject, now) ?: return null
        settlement.session?.let { insertSession(connection, it, now) }
        writeTimer(connection, settlement.timer)
        return settlement
    }

    private fun snapshot(connection: Connection, now: Long): Snapshot = Snapshot(
        serverNowEpochMs = now,
        revision = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT revision FROM app_state WHERE id = 1").use { result ->
                check(result.next()) { "app_state is missing" }
                result.getLong(1)
            }
        },
        subjects = readSubjects(connection),
        timer = readTimer(connection),
        sessions = readSessions(connection),
        devices = readDevices(connection, now),
    )

    private fun readSubjects(connection: Connection): List<Subject> = connection.createStatement().use { statement ->
        statement.executeQuery(
            """
            SELECT id, name, color_hex, focus_seconds, short_break_seconds, long_break_seconds,
                   long_break_every, archived
            FROM subjects ORDER BY archived, created_at_ms, id
            """.trimIndent(),
        ).use { result -> buildList { while (result.next()) add(result.subject()) } }
    }

    private fun readSubject(connection: Connection, id: String): Subject? = connection.prepareStatement(
        """
        SELECT id, name, color_hex, focus_seconds, short_break_seconds, long_break_seconds,
               long_break_every, archived
        FROM subjects WHERE id = ?
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, id)
        statement.executeQuery().use { result -> if (result.next()) result.subject() else null }
    }

    private fun ResultSet.subject(): Subject = Subject(
        id = getString("id"),
        name = getString("name"),
        colorHex = getString("color_hex"),
        focusSeconds = getInt("focus_seconds"),
        shortBreakSeconds = getInt("short_break_seconds"),
        longBreakSeconds = getInt("long_break_seconds"),
        longBreakEvery = getInt("long_break_every"),
        archived = getBoolean("archived"),
    )

    private fun readTimer(connection: Connection, forUpdate: Boolean = false): TimerState {
        val suffix = if (forUpdate) " FOR UPDATE" else ""
        return connection.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM timer_state WHERE id = 1$suffix").use { result ->
                check(result.next()) { "timer_state is missing" }
                TimerState(
                    id = result.getString("timer_id"),
                    subjectId = result.getString("subject_id"),
                    status = TimerStatus.valueOf(result.getString("status")),
                    phase = TimerPhase.valueOf(result.getString("phase")),
                    mode = TimerMode.valueOf(result.getString("mode")),
                    targetSeconds = result.getInt("target_seconds"),
                    accumulatedMillis = result.getLong("accumulated_millis"),
                    runningSinceEpochMs = result.nullableLong("running_since_ms"),
                    startedAtEpochMs = result.nullableLong("started_at_ms"),
                    completedFocusCount = result.getInt("completed_focus_count"),
                    controllingDeviceId = result.getString("controlling_device_id"),
                    controllingDeviceName = result.getString("controlling_device_name"),
                )
            }
        }
    }

    private fun writeTimer(connection: Connection, timer: TimerState) {
        connection.prepareStatement(
            """
            UPDATE timer_state SET timer_id=?, subject_id=?, status=?, phase=?, mode=?, target_seconds=?,
              accumulated_millis=?, running_since_ms=?, started_at_ms=?, completed_focus_count=?,
              controlling_device_id=?, controlling_device_name=? WHERE id=1
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, timer.id)
            statement.setString(2, timer.subjectId)
            statement.setString(3, timer.status.name)
            statement.setString(4, timer.phase.name)
            statement.setString(5, timer.mode.name)
            statement.setInt(6, timer.targetSeconds)
            statement.setLong(7, timer.accumulatedMillis)
            statement.setNullableLong(8, timer.runningSinceEpochMs)
            statement.setNullableLong(9, timer.startedAtEpochMs)
            statement.setInt(10, timer.completedFocusCount)
            statement.setString(11, timer.controllingDeviceId)
            statement.setString(12, timer.controllingDeviceName)
            statement.executeUpdate()
        }
    }

    private fun readSessions(connection: Connection): List<FocusSession> = connection.createStatement().use { statement ->
        statement.executeQuery(
            """
            SELECT id, subject_id, started_at_ms, ended_at_ms, duration_seconds, mode, memo, device_name
            FROM focus_sessions ORDER BY started_at_ms DESC LIMIT 200
            """.trimIndent(),
        ).use { result ->
            buildList {
                while (result.next()) add(
                    FocusSession(
                        id = result.getString("id"),
                        subjectId = result.getString("subject_id"),
                        startedAtEpochMs = result.getLong("started_at_ms"),
                        endedAtEpochMs = result.getLong("ended_at_ms"),
                        durationSeconds = result.getInt("duration_seconds"),
                        mode = TimerMode.valueOf(result.getString("mode")),
                        memo = result.getString("memo"),
                        deviceName = result.getString("device_name"),
                    ),
                )
            }
        }
    }

    private fun insertSession(connection: Connection, session: FocusSession, now: Long) {
        connection.prepareStatement(
            """
            INSERT INTO focus_sessions(
              id, subject_id, started_at_ms, ended_at_ms, duration_seconds, mode, memo, device_name, created_at_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, session.id)
            statement.setString(2, session.subjectId)
            statement.setLong(3, session.startedAtEpochMs)
            statement.setLong(4, session.endedAtEpochMs)
            statement.setInt(5, session.durationSeconds)
            statement.setString(6, session.mode.name)
            statement.setString(7, session.memo)
            statement.setString(8, session.deviceName.take(128))
            statement.setLong(9, now)
            statement.executeUpdate()
        }
    }

    private fun readDevices(connection: Connection, now: Long): List<DeviceInfo> = connection.prepareStatement(
        "SELECT id, name, last_seen_at_ms FROM devices WHERE last_seen_at_ms >= ? ORDER BY last_seen_at_ms DESC",
    ).use { statement ->
        statement.setLong(1, now - 30L * 24 * 60 * 60 * 1000)
        statement.executeQuery().use { result ->
            buildList {
                while (result.next()) add(
                    DeviceInfo(result.getString("id"), result.getString("name"), result.getLong("last_seen_at_ms")),
                )
            }
        }
    }

    private fun lockRevision(connection: Connection): Long = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT revision FROM app_state WHERE id=1 FOR UPDATE").use { result ->
            check(result.next()) { "app_state is missing" }
            result.getLong(1)
        }
    }

    private fun setRevision(connection: Connection, revision: Long) {
        connection.prepareStatement("UPDATE app_state SET revision=? WHERE id=1").use { statement ->
            statement.setLong(1, revision)
            statement.executeUpdate()
        }
    }

    private fun isProcessed(connection: Connection, commandId: String): Boolean = connection.prepareStatement(
        "SELECT 1 FROM processed_commands WHERE command_id=?",
    ).use { statement ->
        statement.setString(1, commandId)
        statement.executeQuery().use { it.next() }
    }

    private fun touchDevice(connection: Connection, id: String, name: String, now: Long) {
        connection.prepareStatement(
            """
            INSERT INTO devices(id, name, last_seen_at_ms) VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE name=VALUES(name), last_seen_at_ms=VALUES(last_seen_at_ms)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, id)
            statement.setString(2, name)
            statement.setLong(3, now)
            statement.executeUpdate()
        }
    }

    private fun validateContext(context: MutationContext) {
        if (context.commandId.isBlank() || context.commandId.length > 128) throw ValidationException("Invalid commandId")
        if (context.expectedRevision < 0) throw ValidationException("Invalid expectedRevision")
        validateDevice(context.deviceId, context.deviceName)
    }

    private fun validateDevice(id: String, name: String) {
        if (id.isBlank() || id.length > 128) throw ValidationException("Invalid device id")
        if (name.isBlank() || name.length > 128) throw ValidationException("Invalid device name")
    }

    private fun validateSubject(subject: Subject) {
        if (!subject.id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) throw ValidationException("Invalid subject id")
        if (subject.name.isBlank() || subject.name.length > 80) throw ValidationException("Subject name must be 1 to 80 characters")
        if (!subject.colorHex.matches(Regex("[0-9A-Fa-f]{6}"))) throw ValidationException("colorHex must contain six hex digits")
        validateDuration(subject.focusSeconds, "focusSeconds")
        validateDuration(subject.shortBreakSeconds, "shortBreakSeconds")
        validateDuration(subject.longBreakSeconds, "longBreakSeconds")
        if (subject.longBreakEvery !in 1..100) throw ValidationException("longBreakEvery must be between 1 and 100")
    }

    private fun validateDuration(seconds: Int, name: String) {
        if (seconds !in 1..14_400) throw ValidationException("$name must be between 1 and 14400")
    }

    private fun ResultSet.nullableLong(column: String): Long? {
        val value = getLong(column)
        return if (wasNull()) null else value
    }

    private fun java.sql.PreparedStatement.setNullableLong(index: Int, value: Long?) {
        if (value == null) setNull(index, java.sql.Types.BIGINT) else setLong(index, value)
    }
}
