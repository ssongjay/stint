package dev.stint.app

import dev.stint.shared.ConnectionSettings
import dev.stint.shared.LocalStore
import dev.stint.shared.Snapshot
import dev.stint.shared.TimerClockStyle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.prefs.Preferences

class DesktopLocalStore internal constructor(
    private val preferences: Preferences = Preferences.userRoot().node("dev/stint/app"),
    private val secrets: DesktopSecretStore = SecurityCliSecretStore,
    private val appDirectory: java.nio.file.Path =
        java.io.File(System.getProperty("user.home"), "Library/Application Support/Stint").toPath(),
) : LocalStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheFile = appDirectory.resolve("snapshot.json")
    private var currentSettings: ConnectionSettings? = null

    override fun loadSettings(): ConnectionSettings {
        val baseUrl = preferences.get(KEY_BASE_URL, "")
        val settings = ConnectionSettings(
            baseUrl = baseUrl,
            token = secrets.read(keychainAccount(baseUrl)),
            deviceId = preferences.get(KEY_DEVICE_ID, "").ifBlank { UUID.randomUUID().toString() },
            deviceName = preferences.get(KEY_DEVICE_NAME, "").ifBlank {
                "${System.getProperty("user.name", "나")}의 Mac"
            },
        )
        currentSettings = settings
        return settings
    }

    override fun saveSettings(settings: ConnectionSettings) {
        if (settings.token.isNotBlank() &&
            (settings.token != currentSettings?.token || settings.baseUrl != currentSettings?.baseUrl)
        ) {
            secrets.write(keychainAccount(settings.baseUrl), settings.token)
        }
        preferences.put(KEY_BASE_URL, settings.baseUrl)
        preferences.put(KEY_DEVICE_ID, settings.deviceId)
        preferences.put(KEY_DEVICE_NAME, settings.deviceName)
        preferences.flush()
        currentSettings = settings
    }

    override fun loadSnapshot(): Snapshot? {
        val settings = currentSettings ?: loadSettings()
        if (!Files.isRegularFile(cacheFile)) return null
        return runCatching {
            val cached = json.decodeFromString<CachedSnapshot>(Files.readString(cacheFile))
            cached.snapshot.takeIf { cached.identity == settings.identityHash() }
        }.getOrNull()
    }

    override fun saveSnapshot(snapshot: Snapshot) {
        val settings = currentSettings ?: return
        if (settings.baseUrl.isBlank() || settings.token.isBlank()) return
        Files.createDirectories(appDirectory)
        val temporary = Files.createTempFile(appDirectory, "snapshot-", ".tmp")
        try {
            Files.writeString(
                temporary,
                json.encodeToString(CachedSnapshot(settings.identityHash(), snapshot)),
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            runCatching {
                Files.move(
                    temporary,
                    cacheFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun loadClockStyle(): TimerClockStyle =
        TimerClockStyle.fromStored(preferences.get(KEY_CLOCK_STYLE, null))

    override fun saveClockStyle(style: TimerClockStyle) {
        preferences.put(KEY_CLOCK_STYLE, style.name)
        preferences.flush()
    }

    private fun keychainAccount(baseUrl: String): String =
        if (baseUrl.isBlank()) "" else "connection-${baseUrl.sha256().take(24)}"

    private fun ConnectionSettings.identityHash(): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$baseUrl\u0000$token".toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    @Serializable
    private data class CachedSnapshot(val identity: String, val snapshot: Snapshot)

    private companion object {
        const val KEY_BASE_URL = "baseUrl"
        const val KEY_DEVICE_ID = "deviceId"
        const val KEY_DEVICE_NAME = "deviceName"
        const val KEY_CLOCK_STYLE = "clockStyle"
    }
}

internal interface DesktopSecretStore {
    fun read(account: String): String
    fun write(account: String, token: String)
}

private object SecurityCliSecretStore : DesktopSecretStore {
    private const val SERVICE = "dev.stint.app.sync"

    override fun read(account: String): String {
        if (account.isBlank()) return ""
        return runCatching {
        val process = ProcessBuilder(
            "/usr/bin/security",
            "find-generic-password",
            "-w",
            "-s", SERVICE,
            "-a", account,
        ).redirectErrorStream(false).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (process.waitFor() == 0) output else ""
        }.getOrDefault("")
    }

    override fun write(account: String, token: String) {
        require('\n' !in token && '\r' !in token)
        val process = ProcessBuilder("/usr/bin/security", "-i").start()
        process.outputStream.bufferedWriter().use {
            it.write(
                "add-generic-password -U -s \"${SERVICE.securityQuote()}\" " +
                    "-a \"${account.securityQuote()}\" -w \"${token.securityQuote()}\"",
            )
            it.newLine()
        }
        val error = process.errorStream.bufferedReader().use { it.readText().trim() }
        check(process.waitFor() == 0) { error.ifBlank { "Keychain rejected the connection key" } }
    }

    private fun String.securityQuote(): String = replace("\\", "\\\\").replace("\"", "\\\"")
}
