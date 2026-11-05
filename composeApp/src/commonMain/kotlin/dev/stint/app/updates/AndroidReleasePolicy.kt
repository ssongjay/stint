package dev.stint.app.updates

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal data class AndroidRelease(
    val versionCode: Long,
    val versionName: String,
    val downloadUrl: String,
    val sha256: String,
)

internal sealed interface AndroidUpdateState {
    data object Idle : AndroidUpdateState
    data object Checking : AndroidUpdateState
    data object UpToDate : AndroidUpdateState
    data class Available(val release: AndroidRelease) : AndroidUpdateState
    data class Error(val message: String) : AndroidUpdateState
}

/** Strict public-release contract. An unrelated or malformed release is never installable. */
internal object AndroidReleasePolicy {
    const val RELEASES_API_URL = "https://api.github.com/repos/ssongjay/stint/releases?per_page=20"
    const val APK_FILE_NAME = "Stint-android.apk"
    const val METADATA_LIMIT_BYTES = 1 * 1024 * 1024
    const val APK_LIMIT_BYTES = 100L * 1024L * 1024L
    const val RELEASE_LIMIT = 20

    private const val RELEASE_DOWNLOAD_BASE_URL =
        "https://github.com/ssongjay/stint/releases/download"
    private val androidTag = Regex("android-v([1-9][0-9]*)")
    private val releaseName = Regex(
        """Stint Android ((?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*))""",
    )
    private val sha256Digest = Regex("sha256:([0-9a-fA-F]{64})")

    fun latestValidRelease(metadata: String): AndroidRelease? {
        require(metadata.encodeToByteArray().size <= METADATA_LIMIT_BYTES) { "업데이트 정보가 너무 커요." }
        val releases = runCatching { Json.parseToJsonElement(metadata).jsonArray }.getOrNull()
            ?: throw IllegalArgumentException("업데이트 정보 형식이 올바르지 않아요.")
        return releases.take(RELEASE_LIMIT)
            .mapNotNull { element -> runCatching { parseRelease(element.jsonObject) }.getOrNull() }
            .maxByOrNull(AndroidRelease::versionCode)
    }

    fun isCanonical(release: AndroidRelease): Boolean =
        release.versionCode > 0L &&
            release.downloadUrl == expectedDownloadUrl(release.versionCode) &&
            release.sha256.length == 64 && release.sha256.all { it in '0'..'9' || it in 'a'..'f' }

    fun availableUpdate(currentVersionCode: Long, release: AndroidRelease?): AndroidRelease? =
        release?.takeIf { currentVersionCode > 0L && it.versionCode > currentVersionCode && isCanonical(it) }

    fun expectedDownloadUrl(versionCode: Long): String =
        "$RELEASE_DOWNLOAD_BASE_URL/android-v$versionCode/$APK_FILE_NAME"

    private fun parseRelease(value: kotlinx.serialization.json.JsonObject): AndroidRelease? {
        if (value["draft"]?.jsonPrimitive?.booleanOrNull != false ||
            value["prerelease"]?.jsonPrimitive?.booleanOrNull != false
        ) return null

        val tag = value["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
        val versionCode = androidTag.matchEntire(tag)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        if (versionCode <= 0L) return null
        val versionName = value["name"]?.jsonPrimitive?.contentOrNull
            ?.let { releaseName.matchEntire(it)?.groupValues?.get(1) }
            ?: return null
        val matchingAssets = value["assets"]?.jsonArray.orEmpty().mapNotNull { asset ->
            runCatching { asset.jsonObject }.getOrNull()
                ?.takeIf { it["name"]?.jsonPrimitive?.contentOrNull == APK_FILE_NAME }
        }
        if (matchingAssets.size != 1) return null
        val asset = matchingAssets.single()
        val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null
        val size = asset["size"]?.jsonPrimitive?.longOrNull ?: return null
        val digest = asset["digest"]?.jsonPrimitive?.contentOrNull
            ?.let { sha256Digest.matchEntire(it)?.groupValues?.get(1)?.lowercase() }
            ?: return null
        val release = AndroidRelease(versionCode, versionName, url, digest)
        return release.takeIf { size in 1L..APK_LIMIT_BYTES && isCanonical(it) }
    }
}

