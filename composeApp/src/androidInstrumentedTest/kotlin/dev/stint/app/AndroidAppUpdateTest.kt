package dev.stint.app

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stint.app.ui.Paper
import dev.stint.app.ui.WorkbenchTheme
import dev.stint.app.updates.AndroidAppUpdater
import dev.stint.app.updates.AndroidReleasePolicy
import dev.stint.app.updates.AndroidUpdateSection
import dev.stint.app.updates.AndroidUpdateState
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in acceptance for public release metadata and the browser-only download boundary. */
@RunWith(AndroidJUnit4::class)
class AndroidAppUpdateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun requireOptInAndAnUnobstructedExistingSurface() {
        assumeTrue(
            "Public update acceptance requires -e allowPublicUpdateCheck true",
            InstrumentationRegistry.getArguments().getString("allowPublicUpdateCheck") == "true",
        )
        instrumentation.uiAutomation.executeShellCommand("cmd power wakeup").close()
        assumeTrue(
            "This test does not grant or change notification permission",
            Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
    }

    @After
    fun closeFixture() {
        scenario?.close()
        scenario = null
    }

    @Test
    fun publicReleaseOffersTheOldBuildAndHandsOnlyTheCanonicalApkToTheBrowser() {
        val updater = AndroidAppUpdater(currentVersionCode = 13, currentVersionName = "1.9.0")
        val browser = RecordingBrowserContext(context)
        launchFixture(updater, "1.9.0", browser)

        clickExactOwnAppText("업데이트 확인")
        val available = awaitTerminalState(updater) as? AndroidUpdateState.Available
            ?: throw AssertionError("A published Android update must be available to versionCode 13: ${updater.state.value}")
        assertTrue(available.release.versionCode > 13L)
        assertTrue(available.release.versionName.isNotBlank())
        assertEquals(
            AndroidReleasePolicy.expectedDownloadUrl(available.release.versionCode),
            available.release.downloadUrl,
        )
        assertOwnAppText("APK 받기")
        writePrivateWindowScreenshot("updates-available.png")

        clickExactOwnAppText("APK 받기")
        val intent = browser.awaitIntent()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertTrue("The browser handoff must remain browsable", intent.categories.orEmpty().contains(Intent.CATEGORY_BROWSABLE))
        assertEquals(available.release.downloadUrl, intent.dataString)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(1, browser.startedIntentCount())
    }

    @Test
    fun installedBuildReportsCurrentOrANewerCanonicalReleaseWithoutFalseErrors() {
        val updater = AndroidAppUpdater(
            currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
            currentVersionName = BuildConfig.VERSION_NAME,
        )
        launchFixture(updater, BuildConfig.VERSION_NAME, RecordingBrowserContext(context))

        clickExactOwnAppText("업데이트 확인")
        when (val terminal = awaitTerminalState(updater)) {
            AndroidUpdateState.UpToDate -> assertOwnAppText("현재 최신 버전을 사용하고 있어요.")
            is AndroidUpdateState.Available -> {
                assertTrue(terminal.release.versionCode > BuildConfig.VERSION_CODE)
                assertEquals(
                    AndroidReleasePolicy.expectedDownloadUrl(terminal.release.versionCode),
                    terminal.release.downloadUrl,
                )
                assertOwnAppText("APK 받기")
            }
            is AndroidUpdateState.Error -> throw AssertionError("A valid public Android release must not be an error: ${terminal.message}")
            AndroidUpdateState.Checking,
            AndroidUpdateState.Idle -> throw AssertionError("Update check did not reach a terminal state")
        }
        writePrivateWindowScreenshot("updates-current.png")
    }

    private fun launchFixture(
        updater: AndroidAppUpdater,
        versionName: String,
        browserContext: Context,
    ) {
        val launched = ActivityScenario.launch(MainActivity::class.java).also { scenario = it }
        launched.moveToState(Lifecycle.State.RESUMED)
        awaitOwnActivityWindow()
        launched.onActivity { activity ->
            activity.setContent {
                CompositionLocalProvider(LocalContext provides browserContext) {
                    WorkbenchTheme {
                        Surface(Modifier.fillMaxSize(), color = Paper) {
                            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp)) {
                                AndroidUpdateSection(updater, versionName)
                            }
                        }
                    }
                }
            }
        }
        instrumentation.waitForIdleSync()
        assertOwnAppText("업데이트 확인")
    }

    private fun awaitTerminalState(updater: AndroidAppUpdater): AndroidUpdateState = runBlocking {
        withTimeout(20_000) {
            updater.state.first { it != AndroidUpdateState.Idle && it != AndroidUpdateState.Checking }
        }
    }

    private fun clickExactOwnAppText(text: String) {
        val deadline = SystemClock.elapsedRealtime() + 12_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val target = ownAccessibilityTree().firstOrNull { node ->
                node.text?.toString() == text || node.contentDescription?.toString() == text
            }
            val clickable = generateSequence(target) { it.parent }.firstOrNull { node ->
                node.isEnabled && (node.isClickable || node.actionList.any {
                    it.id == AccessibilityNodeInfo.ACTION_CLICK
                })
            }
            if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                instrumentation.waitForIdleSync()
                return
            }
            SystemClock.sleep(100L)
        }
        throw AssertionError("Could not click enabled Stint control with exact text: $text")
    }

    private fun assertOwnAppText(text: String) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ownAccessibilityTree().any { node ->
                    node.text?.toString() == text || node.contentDescription?.toString() == text
                }
            ) return
            SystemClock.sleep(100L)
        }
        throw AssertionError("Stint did not display expected text: $text")
    }

    private fun ownAccessibilityTree(): List<AccessibilityNodeInfo> {
        val root = instrumentation.uiAutomation.rootInActiveWindow
        if (root?.packageName?.toString() != context.packageName) return emptyList()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        val result = ArrayList<AccessibilityNodeInfo>()
        pending.add(root)
        while (pending.isNotEmpty() && result.size < 4_096) {
            val node = pending.removeFirst()
            result += node
            repeat(node.childCount) { index -> node.getChild(index)?.let(pending::addLast) }
        }
        return result
    }

    private fun awaitOwnActivityWindow() {
        val launched = checkNotNull(scenario)
        val deadline = SystemClock.elapsedRealtime() + 12_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            launched.onActivity { activity ->
                val decor = activity.window.decorView
                ready = decor.isAttachedToWindow && decor.hasWindowFocus() && decor.width > 0 && decor.height > 0
            }
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (ready && root?.packageName?.toString() == context.packageName) return
            SystemClock.sleep(100L)
        }
        throw AssertionError("Stint update fixture never became the active accessibility window")
    }

    private fun writePrivateWindowScreenshot(name: String) {
        val launched = checkNotNull(scenario)
        val bitmap = AtomicReference<Bitmap>()
        val completed = CountDownLatch(1)
        launched.onActivity { activity ->
            val view = activity.window.decorView
            val output = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            fun fallback() {
                view.draw(Canvas(output))
                bitmap.set(output)
                completed.countDown()
            }
            try {
                PixelCopy.request(activity.window, output, { result ->
                    if (result == PixelCopy.SUCCESS) {
                        bitmap.set(output)
                        completed.countDown()
                    } else fallback()
                }, Handler(Looper.getMainLooper()))
            } catch (_: IllegalArgumentException) {
                fallback()
            }
        }
        assertTrue("Window screenshot timed out", completed.await(5, TimeUnit.SECONDS))
        val output = requireNotNull(bitmap.get())
        val file = File(context.filesDir, name)
        FileOutputStream(file).use { stream ->
            assertTrue("PNG writer failed", output.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        output.recycle()
    }

    private class RecordingBrowserContext(base: Context) : ContextWrapper(base) {
        private val intents = ArrayList<Intent>()
        private val delivered = CountDownLatch(1)

        override fun getApplicationContext(): Context = this

        override fun startActivity(intent: Intent) {
            synchronized(intents) { intents += Intent(intent) }
            delivered.countDown()
        }

        fun awaitIntent(): Intent {
            assertTrue("Browser handoff was not emitted", delivered.await(5, TimeUnit.SECONDS))
            return synchronized(intents) { Intent(intents.single()) }
        }

        fun startedIntentCount(): Int = synchronized(intents) { intents.size }
    }
}
