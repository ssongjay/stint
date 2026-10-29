package dev.stint.app

import dev.stint.shared.ConnectionSettings
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal const val DEVICE_CONNECTION_FILE = "device-connection.json"
internal const val MAX_DEVICE_CONNECTION_BYTES = 8 * 1024
internal const val INVALID_DEVICE_CONNECTION = "USB 연결 정보를 적용하지 못했어요. 앱에서 연결 설정을 확인해 주세요."
internal const val DEVICE_CONNECTION_CLEANUP_FAILED = "USB 연결 임시 파일을 삭제하지 못했어요."

/** A fixed private file is consumed only by debuggable, personally installed builds. */
internal fun consumePrivateDeviceConnection(
    file: File,
    debuggable: Boolean,
    current: ConnectionSettings,
    persist: (ConnectionSettings) -> Unit,
    reportError: (String) -> Unit = {},
): ConnectionSettings {
    // Release builds must not inspect, parse, delete, or apply a provisioning file.
    if (!debuggable || !file.exists()) return current
    return try {
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        val bytes = file.inputStream().use { stream ->
            val buffer = ByteArray(MAX_DEVICE_CONNECTION_BYTES + 1)
            var count = 0
            while (count < buffer.size) {
                val read = stream.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            require(count in 1..MAX_DEVICE_CONNECTION_BYTES)
            buffer.copyOf(count)
        }
        val next = parsePrivateDeviceConnection(bytes.toString(Charsets.UTF_8), current)
        persist(next)
        next
    } catch (_: Exception) {
        // Neither the parser exception nor the file contents may reach logs.
        reportError(INVALID_DEVICE_CONNECTION)
        current
    } finally {
        if (!file.delete()) reportError(DEVICE_CONNECTION_CLEANUP_FAILED)
    }
}

internal fun parsePrivateDeviceConnection(text: String, current: ConnectionSettings): ConnectionSettings {
    return try {
        require(text.toByteArray(Charsets.UTF_8).size in 1..MAX_DEVICE_CONNECTION_BYTES)
        val value = Json.parseToJsonElement(text).jsonObject
        val url = value["url"]?.jsonPrimitive?.contentOrNull?.trim()?.trimEnd('/') ?: error("missing")
        val token = value["token"]?.jsonPrimitive?.contentOrNull ?: error("missing")
        val uri = URI(url)
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.isOpaque && !uri.host.isNullOrBlank())
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(uri.port == -1 || uri.port in 1..65535)
        require(token.length in 24..256 && token.all { it.code in 33..126 })
        current.copy(baseUrl = "https" + url.substring(uri.scheme.length), token = token)
    } catch (_: Exception) {
        throw IllegalArgumentException(INVALID_DEVICE_CONNECTION)
    }
}
