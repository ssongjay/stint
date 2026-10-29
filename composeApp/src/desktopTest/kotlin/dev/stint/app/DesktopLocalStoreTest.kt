package dev.stint.app

import dev.stint.shared.ConnectionSettings
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopLocalStoreTest {
    @Test
    fun `keychain failure leaves the previous server pointer intact`() {
        val node = Preferences.userRoot().node("dev/stint/test/${UUID.randomUUID()}")
        val directory = Files.createTempDirectory("stint-store-test-")
        val secrets = FailingSecretStore("old-token")
        try {
            node.put("baseUrl", "https://old.example")
            node.put("deviceId", "device")
            node.put("deviceName", "Mac")
            node.flush()
            val store = DesktopLocalStore(node, secrets, directory)
            assertEquals("old-token", store.loadSettings().token)

            assertFailsWith<IllegalStateException> {
                store.saveSettings(
                    ConnectionSettings(
                        baseUrl = "https://new.example",
                        token = "new-token",
                        deviceId = "device",
                        deviceName = "Mac",
                    ),
                )
            }

            assertEquals("https://old.example", node.get("baseUrl", ""))
            assertEquals("old-token", store.loadSettings().token)
        } finally {
            node.removeNode()
            Files.deleteIfExists(directory)
        }
    }

    private class FailingSecretStore(private val oldToken: String) : DesktopSecretStore {
        override fun read(account: String): String = oldToken
        override fun write(account: String, token: String) {
            throw IllegalStateException("injected failure")
        }
    }
}
