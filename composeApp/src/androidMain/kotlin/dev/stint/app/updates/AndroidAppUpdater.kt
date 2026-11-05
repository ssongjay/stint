package dev.stint.app.updates

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class AndroidAppUpdater(
    private val currentVersionCode: Long,
    currentVersionName: String,
) {
    private val operationMutex = Mutex()
    private val _state = MutableStateFlow<AndroidUpdateState>(AndroidUpdateState.Idle)
    val state: StateFlow<AndroidUpdateState> = _state.asStateFlow()
    private var availableRelease: AndroidRelease? = null

    init {
        require(currentVersionCode > 0L)
        require(currentVersionName.isNotBlank())
    }

    suspend fun check() = operationMutex.withLock {
        _state.value = AndroidUpdateState.Checking
        try {
            val release = withTimeout(15_000) { fetchLatestRelease() }
            if (release == null) {
                availableRelease = null
                _state.value = AndroidUpdateState.Error("배포된 Android 업데이트를 찾지 못했어요.")
                return@withLock
            }
            availableRelease = AndroidReleasePolicy.availableUpdate(currentVersionCode, release)
            _state.value = availableRelease?.let { AndroidUpdateState.Available(it) }
                ?: AndroidUpdateState.UpToDate
        } catch (_: TimeoutCancellationException) {
            _state.value = AndroidUpdateState.Error("업데이트 확인 시간이 초과됐어요.")
        } catch (cancelled: CancellationException) {
            _state.value = AndroidUpdateState.Idle
            throw cancelled
        } catch (_: IOException) {
            _state.value = AndroidUpdateState.Error("업데이트 서버에 연결하지 못했어요.")
        } catch (_: IllegalArgumentException) {
            _state.value = AndroidUpdateState.Error("업데이트 정보를 확인하지 못했어요.")
        } catch (_: RuntimeException) {
            _state.value = AndroidUpdateState.Error("업데이트 확인 중 문제가 생겼어요.")
        }
    }

    fun openDownload(context: Context) {
        val release = availableRelease
        if (release == null || release.versionCode <= currentVersionCode ||
            !AndroidReleasePolicy.isCanonical(release)
        ) {
            _state.value = AndroidUpdateState.Error("받을 업데이트를 다시 확인해 주세요.")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(release.downloadUrl)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.applicationContext.startActivity(intent)
            _state.value = AndroidUpdateState.Available(release)
        } catch (_: ActivityNotFoundException) {
            _state.value = AndroidUpdateState.Error("업데이트를 받을 브라우저를 열지 못했어요.")
        } catch (_: SecurityException) {
            _state.value = AndroidUpdateState.Error("브라우저에서 업데이트 주소를 열 수 없어요.")
        } catch (_: RuntimeException) {
            _state.value = AndroidUpdateState.Error("업데이트 주소를 열지 못했어요.")
        }
    }

    private suspend fun fetchLatestRelease(): AndroidRelease? = coroutineScope {
        val activeConnection = AtomicReference<HttpURLConnection?>(null)
        suspendCancellableCoroutine { continuation ->
            lateinit var requestJob: Job
            requestJob = launch(Dispatchers.IO) {
                try {
                    continuation.resume(fetchLatestReleaseBlocking(activeConnection))
                } catch (cancelled: CancellationException) {
                    if (continuation.isActive) continuation.cancel(cancelled)
                } catch (failure: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
            continuation.invokeOnCancellation {
                activeConnection.getAndSet(null)?.disconnect()
                requestJob.cancel()
            }
        }
    }

    private suspend fun fetchLatestReleaseBlocking(
        activeConnection: AtomicReference<HttpURLConnection?>,
    ): AndroidRelease? {
        val connection = (URL(AndroidReleasePolicy.RELEASES_API_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7_000
            readTimeout = 7_000
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Stint-Android-Updater")
        }
        activeConnection.set(connection)
        try {
            coroutineContext.ensureActive()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Unexpected update response")
            }
            val declaredLength = connection.contentLengthLong
            if (declaredLength > AndroidReleasePolicy.METADATA_LIMIT_BYTES) {
                throw IllegalArgumentException("Metadata is too large")
            }
            val metadata = connection.inputStream.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    coroutineContext.ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > AndroidReleasePolicy.METADATA_LIMIT_BYTES) {
                        throw IllegalArgumentException("Metadata is too large")
                    }
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
            coroutineContext.ensureActive()
            return AndroidReleasePolicy.latestValidRelease(metadata)
        } catch (failure: IOException) {
            coroutineContext.ensureActive()
            throw failure
        } finally {
            activeConnection.compareAndSet(connection, null)
            connection.disconnect()
        }
    }
}

