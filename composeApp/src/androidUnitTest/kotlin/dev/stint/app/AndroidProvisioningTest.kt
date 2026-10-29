package dev.stint.app

import dev.stint.shared.ConnectionSettings
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidProvisioningTest {
    private val current = ConnectionSettings("https://old.example/stint", "old-private-key-value-123456", "existing-device", "My Fold")
    private val token = "new-private-key-value-123456789"
    private fun payload(url: String = "https://new.example/stint/") =
        """{"url":"$url","token":"$token"}"""

    @Test
    fun privateImportPreservesDeviceAndPersistsOnlyTheValidatedConnection() = withFile(payload()) { file ->
        var saved: ConnectionSettings? = null
        val result = consumePrivateDeviceConnection(file, true, current, { saved = it })
        assertEquals(current.copy(baseUrl = "https://new.example/stint", token = token), result)
        assertEquals(result, saved)
        assertFalse(file.exists())
    }

    @Test
    fun releaseBuildDoesNotConsumeOrReadProvisioningFile() = withFile(payload()) { file ->
        val original = file.readBytes()
        val result = consumePrivateDeviceConnection(file, false, current, { error("Release must not persist") })
        assertEquals(current, result)
        assertTrue(file.exists())
        assertTrue(original.contentEquals(file.readBytes()))
    }

    @Test
    fun invalidAddressesAreRejectedBeforePersistence() {
        for (url in listOf("http://new.example", "https://user@new.example", "https://new.example?secret=x", "https://new.example#key", "https:///path", "https://new.example:0")) {
            withFile(payload(url)) { file ->
                var persisted = false
                val errors = mutableListOf<String>()
                val result = consumePrivateDeviceConnection(file, true, current, { persisted = true }, { errors += it })
                assertEquals(current, result)
                assertFalse(persisted)
                assertFalse(file.exists())
                assertEquals(listOf(INVALID_DEVICE_CONNECTION), errors)
            }
        }
    }

    @Test
    fun malformedOrOversizedInputIsDeletedWithoutChangingSettings() {
        for (contents in listOf("not-json", "x".repeat(MAX_DEVICE_CONNECTION_BYTES + 1))) {
            withFile(contents) { file ->
                val result = consumePrivateDeviceConnection(file, true, current, { error("Invalid input must not persist") })
                assertEquals(current, result)
                assertFalse(file.exists())
            }
        }
    }

    @Test
    fun invalidTokensNeverEscapeInErrorMessages() {
        for (bad in listOf("short", "a".repeat(257), "a".repeat(24) + "\\n", "비밀".repeat(20), " ".repeat(24))) {
            val error = assertFailsWith<IllegalArgumentException> {
                parsePrivateDeviceConnection("""{"url":"https://new.example","token":"$bad"}""", current)
            }
            assertEquals(INVALID_DEVICE_CONNECTION, error.message)
        }
    }

    @Test
    fun storageFailureKeepsThePriorConnectionAndDeletesTemporaryPlaintext() = withFile(payload()) { file ->
        val errors = mutableListOf<String>()
        val result = consumePrivateDeviceConnection(file, true, current, { error("Synthetic secure-store failure") }, { errors += it })
        assertEquals(current, result)
        assertFalse(file.exists())
        assertEquals(listOf(INVALID_DEVICE_CONNECTION), errors)
    }

    @Test
    fun symlinkIsNotFollowed() {
        val folder = Files.createTempDirectory("stint-provision-test-").toFile()
        try {
            val target = folder.resolve("other-private-file").apply { writeText(payload()) }
            val file = folder.resolve(DEVICE_CONNECTION_FILE)
            Files.createSymbolicLink(file.toPath(), target.toPath())
            val result = consumePrivateDeviceConnection(file, true, current, { error("Symlink must not persist") })
            assertEquals(current, result)
            assertFalse(file.exists())
            assertEquals(payload(), target.readText())
        } finally {
            folder.deleteRecursively()
        }
    }

    private inline fun withFile(contents: String, block: (java.io.File) -> Unit) {
        val folder = Files.createTempDirectory("stint-provision-test-").toFile()
        try {
            block(folder.resolve(DEVICE_CONNECTION_FILE).apply { writeText(contents) })
        } finally {
            folder.deleteRecursively()
        }
    }
}
