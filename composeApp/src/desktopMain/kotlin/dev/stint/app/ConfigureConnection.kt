package dev.stint.app

import io.ktor.http.URLProtocol
import io.ktor.http.Url

/** Stores a connection in the macOS Keychain without opening the UI. */
fun main() {
    val baseUrl = System.getenv("STINT_SERVER_URL").orEmpty().trim().trimEnd('/')
    val token = System.getenv("STINT_AUTH_TOKEN").orEmpty().trim()
    val allowLocal = System.getenv("STINT_ALLOW_INSECURE_LOCALHOST") == "true"
    require(baseUrl.isNotBlank() && token.isNotBlank()) {
        "STINT_SERVER_URL and STINT_AUTH_TOKEN are required"
    }
    require('\n' !in token && '\r' !in token) { "Connection key cannot contain a newline" }
    val parsed = Url(baseUrl)
    val localHost = parsed.host == "localhost" || parsed.host == "127.0.0.1" || parsed.host == "::1"
    require(parsed.protocol == URLProtocol.HTTPS ||
        (allowLocal && parsed.protocol == URLProtocol.HTTP && localHost)
    ) { "HTTPS is required unless explicit localhost development mode is enabled" }

    val store = DesktopLocalStore()
    val current = store.loadSettings()
    store.saveSettings(current.copy(baseUrl = baseUrl, token = token))
    val verified = store.loadSettings()
    check(verified.baseUrl == baseUrl && verified.token == token) { "Connection could not be verified" }
    println("Stint connection saved securely for ${verified.deviceName}.")
}
