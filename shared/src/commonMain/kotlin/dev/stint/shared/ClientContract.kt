package dev.stint.shared

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class ConnectionSettings(
    val baseUrl: String = "",
    @Transient val token: String = "",
    val deviceId: String = "",
    val deviceName: String = "",
)

enum class ConnectionStatus { SETUP, CONNECTING, CONNECTED, OFFLINE }

data class ClientState(
    val settings: ConnectionSettings,
    val snapshot: Snapshot? = null,
    val serverNowEpochMs: Long = 0,
    val connection: ConnectionStatus = ConnectionStatus.SETUP,
    val working: Boolean = false,
    val message: String? = null,
    val clockStyle: TimerClockStyle = TimerClockStyle.ORBIT,
)

interface ClientController {
    val state: StateFlow<ClientState>
    fun configure(baseUrl: String, token: String)
    fun start(subjectId: String, mode: TimerMode = TimerMode.COUNTDOWN, focusSeconds: Int? = null)
    fun pause()
    fun resume()
    fun finish()
    fun skipBreak()
    fun setTimerDuration(targetSeconds: Int, expectedRevision: Long? = null)
    fun setSubjectFocusDuration(subjectId: String, targetSeconds: Int, expectedRevision: Long)
    fun saveSubject(subject: Subject)
    fun saveMemo(sessionId: String, memo: String)
    fun refresh()
    fun dismissMessage()
    fun setClockStyle(style: TimerClockStyle)
    fun close()
}

interface LocalStore {
    fun loadSettings(): ConnectionSettings
    fun saveSettings(settings: ConnectionSettings)
    fun loadSnapshot(): Snapshot?
    fun saveSnapshot(snapshot: Snapshot)
    fun loadClockStyle(): TimerClockStyle
    fun saveClockStyle(style: TimerClockStyle)
}
