package dev.stint.app.updates

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidReleasePolicyTest {
    private val digest = "ab".repeat(32)

    @Test
    fun selectsTheHighestValidAndroidReleaseAndIgnoresOtherPlatforms() {
        val result = AndroidReleasePolicy.latestValidRelease(
            releases(
                release(14, "1.10.0"),
                """{"tag_name":"mac-v99","name":"Stint Mac 9.9.9","draft":false,"prerelease":false,"assets":[]}""",
                release(16, "1.12.0"),
                release(15, "1.11.0"),
            ),
        )
        assertEquals(16, result?.versionCode)
        assertEquals("1.12.0", result?.versionName)
        assertEquals(digest, result?.sha256)
    }

    @Test
    fun rejectsDraftPrereleaseDuplicateAndMalformedAssets() {
        val values = listOf(
            release(14, "1.10.0", draft = true),
            release(15, "1.11.0", prerelease = true),
            release(16, "1.12.0", duplicateAsset = true),
            release(17, "1.13.0", url = "https://evil.example/Stint-android.apk"),
            release(18, "1.14.0", digestValue = "sha256:xyz"),
            release(19, "1.15.0", size = AndroidReleasePolicy.APK_LIMIT_BYTES + 1),
        )
        assertNull(AndroidReleasePolicy.latestValidRelease(releases(*values.toTypedArray())))
    }

    @Test
    fun canonicalPolicyRejectsLookalikeTagsNamesAndUrls() {
        val malformed = listOf(
            releaseRaw("android-v0", "Stint Android 1.0.0", 0),
            releaseRaw("android-v014", "Stint Android 1.10.0", 14),
            releaseRaw("android-v14-beta", "Stint Android 1.10.0", 14),
            releaseRaw("android-v14", "Stint Android 01.10.0", 14),
            releaseRaw("android-v14", "Stint Android 1.10", 14),
            release(14, "1.10.0", url = "https://github.com/ssongjay/stint/releases/download/android-v14/Stint-android.apk?x=1"),
        )
        malformed.forEach { assertNull(AndroidReleasePolicy.latestValidRelease(releases(it))) }
        assertTrue(AndroidReleasePolicy.isCanonical(checkNotNull(
            AndroidReleasePolicy.latestValidRelease(releases(release(14, "1.10.0"))),
        )))
    }

    @Test
    fun onlyTheFirstTwentyApiEntriesAreConsidered() {
        val unrelated = (1..20).map { """{"tag_name":"mac-v$it","draft":false,"prerelease":false,"assets":[]}""" }
        assertNull(AndroidReleasePolicy.latestValidRelease(releases(*(unrelated + release(50, "5.0.0")).toTypedArray())))
    }

    @Test
    fun rejectsInvalidOrOversizedMetadata() {
        assertFailsWith<IllegalArgumentException> { AndroidReleasePolicy.latestValidRelease("{}") }
        val oversized = "[\"${"x".repeat(AndroidReleasePolicy.METADATA_LIMIT_BYTES)}\"]"
        assertFailsWith<IllegalArgumentException> { AndroidReleasePolicy.latestValidRelease(oversized) }
    }

    @Test
    fun validOrderingNeverTreatsAnOlderVersionAsNewer() {
        val sameOrOlder = checkNotNull(AndroidReleasePolicy.latestValidRelease(
            releases(release(18, "1.8.0"), release(20, "2.0.0")),
        ))
        val newer = checkNotNull(AndroidReleasePolicy.latestValidRelease(releases(release(21, "2.1.0"))))
        assertNull(AndroidReleasePolicy.availableUpdate(20, sameOrOlder))
        assertEquals(newer, AndroidReleasePolicy.availableUpdate(20, newer))
        assertNull(AndroidReleasePolicy.availableUpdate(0, newer))
    }

    private fun releases(vararg entries: String): String = entries.joinToString(",", "[", "]")

    private fun release(
        code: Long,
        version: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
        duplicateAsset: Boolean = false,
        url: String = AndroidReleasePolicy.expectedDownloadUrl(code),
        digestValue: String = "sha256:$digest",
        size: Long = 20_000_000,
    ): String {
        val asset = """{"name":"${AndroidReleasePolicy.APK_FILE_NAME}","browser_download_url":"$url","size":$size,"digest":"$digestValue"}"""
        return """{"tag_name":"android-v$code","name":"Stint Android $version","draft":$draft,"prerelease":$prerelease,"assets":[$asset${if (duplicateAsset) ",$asset" else ""}]}"""
    }

    private fun releaseRaw(tag: String, name: String, code: Long): String =
        release(code.coerceAtLeast(1), "1.0.0")
            .replace("android-v${code.coerceAtLeast(1)}", tag)
            .replace("Stint Android 1.0.0", name)
}
