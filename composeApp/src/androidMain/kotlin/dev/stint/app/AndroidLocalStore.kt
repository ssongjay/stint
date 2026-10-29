package dev.stint.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import dev.stint.shared.ConnectionSettings
import dev.stint.shared.LocalStore
import dev.stint.shared.Snapshot
import dev.stint.shared.TimerClockStyle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidLocalStore(context: Context) : LocalStore {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences("stint-settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheFile = File(applicationContext.filesDir, "snapshot.json")
    private var currentSettings: ConnectionSettings? = null

    override fun loadSettings(): ConnectionSettings {
        val settings = ConnectionSettings(
            baseUrl = preferences.getString(KEY_BASE_URL, "").orEmpty(),
            token = decryptToken(preferences.getString(KEY_TOKEN, null)),
            deviceId = preferences.getString(KEY_DEVICE_ID, "").orEmpty().ifBlank { UUID.randomUUID().toString() },
            deviceName = preferences.getString(KEY_DEVICE_NAME, "").orEmpty().ifBlank {
                Build.MODEL.takeIf { it.isNotBlank() } ?: "Android"
            },
        )
        currentSettings = settings
        return consumePrivateDeviceConnection(
            file = File(applicationContext.filesDir, DEVICE_CONNECTION_FILE),
            debuggable = applicationContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            current = settings,
            persist = ::saveSettings,
            reportError = { Log.w("StintConnection", it) },
        ).also { currentSettings = it }
    }

    @SuppressLint("UseKtx")
    override fun saveSettings(settings: ConnectionSettings) {
        val editor = preferences.edit()
            .putString(KEY_BASE_URL, settings.baseUrl)
            .putString(KEY_DEVICE_ID, settings.deviceId)
            .putString(KEY_DEVICE_NAME, settings.deviceName)
        if (settings.token.isBlank()) editor.remove(KEY_TOKEN)
        else if (settings.token != currentSettings?.token) editor.putString(KEY_TOKEN, encryptToken(settings.token))
        // KTX edit(commit = true) discards the boolean result; this must detect persistence failure.
        check(editor.commit()) { "Unable to save local settings" }
        currentSettings = settings
    }

    override fun loadSnapshot(): Snapshot? {
        val settings = currentSettings ?: loadSettings()
        if (!cacheFile.isFile) return null
        return runCatching {
            val cached = json.decodeFromString<CachedSnapshot>(cacheFile.readText())
            cached.snapshot.takeIf { cached.identity == settings.identityHash() }
        }.getOrNull()
    }

    override fun saveSnapshot(snapshot: Snapshot) {
        val settings = currentSettings ?: return
        if (settings.baseUrl.isBlank() || settings.token.isBlank()) return
        val temporary = File.createTempFile("snapshot-", ".tmp", cacheFile.parentFile)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(json.encodeToString(CachedSnapshot(settings.identityHash(), snapshot)).toByteArray())
                stream.fd.sync()
            }
            runCatching {
                Files.move(
                    temporary.toPath(),
                    cacheFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(temporary.toPath(), cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    override fun loadClockStyle(): TimerClockStyle =
        TimerClockStyle.fromStored(preferences.getString(KEY_CLOCK_STYLE, null))

    @SuppressLint("UseKtx")
    override fun saveClockStyle(style: TimerClockStyle) {
        check(preferences.edit().putString(KEY_CLOCK_STYLE, style.name).commit()) {
            "Unable to save clock style"
        }
    }

    private fun encryptToken(token: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(1 + cipher.iv.size + encrypted.size)
        payload[0] = cipher.iv.size.toByte()
        cipher.iv.copyInto(payload, 1)
        encrypted.copyInto(payload, 1 + cipher.iv.size)
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decryptToken(encoded: String?): String {
        if (encoded.isNullOrBlank()) return ""
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            val ivSize = payload.first().toInt() and 0xff
            require(ivSize in 12..16 && payload.size > ivSize + 1)
            val iv = payload.copyOfRange(1, 1 + ivSize)
            val encrypted = payload.copyOfRange(1 + ivSize, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun ConnectionSettings.identityHash(): String = MessageDigest.getInstance("SHA-256")
        .digest("$baseUrl\u0000$token".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    @Serializable
    private data class CachedSnapshot(val identity: String, val snapshot: Snapshot)

    private companion object {
        const val KEY_BASE_URL = "baseUrl"
        const val KEY_TOKEN = "encryptedToken"
        const val KEY_DEVICE_ID = "deviceId"
        const val KEY_DEVICE_NAME = "deviceName"
        const val KEY_CLOCK_STYLE = "clockStyle"
        const val KEY_ALIAS = "dev.stint.app.connection-key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
