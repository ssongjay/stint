package dev.stint.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection

class Database(private val config: ServerConfig) : AutoCloseable {
    private val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = config.dbUrl
        username = config.dbUser
        password = config.dbPassword
        minimumIdle = 1
        maximumPoolSize = 4
        connectionTimeout = 5_000
        validationTimeout = 2_000
        poolName = "stint-db"
    })

    fun connection(): Connection = dataSource.connection

    fun isReady(): Boolean = try {
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT 1").use { it.next() }
            }
        }
    } catch (_: Exception) {
        false
    }

    /**
     * 반복 실행 가능한 스키마 보강 뒤 초기 데이터를 이관한다.
     * MySQL DDL은 암묵적으로 커밋되므로 아래 rollback이 CREATE/ALTER까지 되돌리지는 않는다.
     */
    fun migrate() {
        connection().use { connection ->
            connection.autoCommit = false
            try {
                schemaStatements.forEach { sql ->
                    connection.createStatement().use { it.execute(sql) }
                }
                ensureSubjectFlowColumns(connection)
                seed(connection)
                seedRecentMemos(connection)
                connection.commit()
            } catch (error: Exception) {
                connection.rollbackAndRethrow(error)
            }
        }
    }

    override fun close() {
        dataSource.close()
    }

    private fun ensureSubjectFlowColumns(connection: Connection) {
        ensureColumn(
            connection,
            column = "pomodoro_enabled",
            definition = "BOOLEAN NOT NULL DEFAULT TRUE",
        )
        ensureColumn(
            connection,
            column = "auto_start_break",
            definition = "BOOLEAN NOT NULL DEFAULT TRUE",
        )
    }

    private fun ensureColumn(connection: Connection, column: String, definition: String) {
        val exists = connection.prepareStatement(
            """
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'subjects' AND column_name = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, column)
            statement.executeQuery().use { it.next() }
        }
        if (!exists) {
            connection.createStatement().use { statement ->
                statement.execute("ALTER TABLE subjects ADD COLUMN $column $definition")
            }
        }
    }

    private fun seed(connection: Connection) {
        connection.prepareStatement("INSERT IGNORE INTO app_state(id, revision) VALUES (1, 0)").use { it.executeUpdate() }
        connection.prepareStatement(
            """
            INSERT IGNORE INTO timer_state(
              id, status, phase, mode, target_seconds, accumulated_millis, completed_focus_count
            ) VALUES (1, 'IDLE', 'FOCUS', 'COUNTDOWN', 1500, 0, 0)
            """.trimIndent(),
        ).use { it.executeUpdate() }

        val seedSql = """
            INSERT IGNORE INTO subjects(
              id, name, color_hex, focus_seconds, short_break_seconds, long_break_seconds,
              long_break_every, archived, created_at_ms
            ) VALUES (?, ?, ?, 1500, 300, 900, 4, FALSE, ?)
        """.trimIndent()
        val now = System.currentTimeMillis()
        listOf(
            Triple("reading", "읽기", "9E5C46"),
            Triple("building", "만들기", "46735D"),
            Triple("study", "공부", "B48A3C"),
        ).forEach { (id, name, color) ->
            connection.prepareStatement(seedSql).use { statement ->
                statement.setString(1, id)
                statement.setString(2, name)
                statement.setString(3, color)
                statement.setLong(4, now)
                statement.executeUpdate()
            }
        }
    }

    /** 최초 한 번만 기존 회고 최대 5개를 가져오며, 이후 실행에서는 실제 사용 순서를 보존한다. */
    private fun seedRecentMemos(connection: Connection) {
        val hasHistory = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT 1 FROM recent_memos LIMIT 1").use { it.next() }
        }
        if (hasHistory) return

        val recent = linkedMapOf<String, Long>()
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT MAX(TRIM(memo)) AS memo, MAX(ended_at_ms) AS last_used_at_ms
                FROM focus_sessions
                WHERE CHAR_LENGTH(TRIM(memo)) > 0
                GROUP BY SHA2(CONVERT(TRIM(memo) USING utf8mb4), 256)
                ORDER BY last_used_at_ms DESC, memo DESC
                LIMIT 5
                """.trimIndent(),
            ).use { result ->
                while (result.next()) {
                    val memo = result.getString("memo")
                    recent[memo] = result.getLong("last_used_at_ms")
                }
            }
        }
        connection.prepareStatement(
            """
            INSERT IGNORE INTO recent_memos(memo_hash, memo, last_used_revision, last_used_at_ms)
            VALUES (?, ?, 0, ?)
            """.trimIndent(),
        ).use { statement ->
            recent.forEach { (memo, lastUsedAt) ->
                statement.setString(1, memoHash(memo))
                statement.setString(2, memo)
                statement.setLong(3, lastUsedAt)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private val schemaStatements = listOf(
        """
        CREATE TABLE IF NOT EXISTS app_state (
          id TINYINT PRIMARY KEY,
          revision BIGINT NOT NULL
        ) ENGINE=InnoDB
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS subjects (
          id VARCHAR(64) PRIMARY KEY,
          name VARCHAR(80) NOT NULL,
          color_hex CHAR(6) NOT NULL,
          focus_seconds INT NOT NULL,
          short_break_seconds INT NOT NULL,
          long_break_seconds INT NOT NULL,
          long_break_every INT NOT NULL,
          archived BOOLEAN NOT NULL DEFAULT FALSE,
          pomodoro_enabled BOOLEAN NOT NULL DEFAULT TRUE,
          auto_start_break BOOLEAN NOT NULL DEFAULT TRUE,
          created_at_ms BIGINT NOT NULL
        ) ENGINE=InnoDB
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS timer_state (
          id TINYINT PRIMARY KEY,
          timer_id VARCHAR(64),
          subject_id VARCHAR(64),
          status VARCHAR(16) NOT NULL,
          phase VARCHAR(16) NOT NULL,
          mode VARCHAR(16) NOT NULL,
          target_seconds INT NOT NULL,
          accumulated_millis BIGINT NOT NULL,
          running_since_ms BIGINT,
          started_at_ms BIGINT,
          completed_focus_count INT NOT NULL,
          controlling_device_id VARCHAR(128),
          controlling_device_name VARCHAR(128),
          CONSTRAINT fk_timer_subject FOREIGN KEY (subject_id) REFERENCES subjects(id)
        ) ENGINE=InnoDB
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS focus_sessions (
          id VARCHAR(64) PRIMARY KEY,
          subject_id VARCHAR(64) NOT NULL,
          started_at_ms BIGINT NOT NULL,
          ended_at_ms BIGINT NOT NULL,
          duration_seconds INT NOT NULL,
          mode VARCHAR(16) NOT NULL,
          memo TEXT NOT NULL,
          device_name VARCHAR(128) NOT NULL,
          created_at_ms BIGINT NOT NULL,
          CONSTRAINT fk_session_subject FOREIGN KEY (subject_id) REFERENCES subjects(id),
          INDEX idx_session_started(started_at_ms),
          INDEX idx_session_subject(subject_id)
        ) ENGINE=InnoDB
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS processed_commands (
          command_id VARCHAR(128) PRIMARY KEY,
          processed_at_ms BIGINT NOT NULL
        ) ENGINE=InnoDB
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS devices (
          id VARCHAR(128) PRIMARY KEY,
          name VARCHAR(128) NOT NULL,
          last_seen_at_ms BIGINT NOT NULL
        ) ENGINE=InnoDB
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS recent_memos (
          memo_hash CHAR(64) PRIMARY KEY,
          memo TEXT NOT NULL,
          last_used_revision BIGINT NOT NULL,
          last_used_at_ms BIGINT NOT NULL,
          INDEX idx_recent_memos_order(last_used_revision, last_used_at_ms)
        ) ENGINE=InnoDB
        """.trimIndent(),
    )
}
