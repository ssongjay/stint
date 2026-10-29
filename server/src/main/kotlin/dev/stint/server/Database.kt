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

    fun isReady(): Boolean = runCatching {
        connection().use { connection ->
            connection.createStatement().use { it.executeQuery("SELECT 1").next() }
        }
    }.getOrDefault(false)

    fun migrate() {
        connection().use { connection ->
            connection.autoCommit = false
            try {
                schemaStatements.forEach { sql ->
                    connection.createStatement().use { it.execute(sql) }
                }
                seed(connection)
                connection.commit()
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            }
        }
    }

    override fun close() {
        dataSource.close()
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
    )
}
