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

/** 플랫폼 UI가 서버 동기화 구현을 직접 알지 않도록 사용자 명령과 관찰 상태만 노출하는 경계다. */
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

/** 서버 스냅샷과 기기 전용 설정의 저장 위치를 Android와 macOS가 각자 구현하는 경계다. */
interface LocalStore {
    fun loadSettings(): ConnectionSettings
    fun saveSettings(settings: ConnectionSettings)
    fun loadSnapshot(): Snapshot?
    fun saveSnapshot(snapshot: Snapshot)
    fun loadClockStyle(): TimerClockStyle
    fun saveClockStyle(style: TimerClockStyle)
}
