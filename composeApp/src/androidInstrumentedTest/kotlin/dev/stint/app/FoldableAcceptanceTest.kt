package dev.stint.app

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowMetricsCalculator
import dev.stint.shared.ConnectionSettings
import dev.stint.shared.ConnectionStatus
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FoldableAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun prepareEncryptedConnectionAndNotificationPermission() {
        instrumentation.uiAutomation.executeShellCommand("cmd power wakeup").close()
        consumePrivateAcceptanceConfiguration()
        val saved = AndroidLocalStore(context).loadSettings()
        require(saved.baseUrl.startsWith("https://") && saved.token.isNotBlank()) {
            "A private HTTPS acceptance connection must be provisioned before this test"
        }
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
    }

    @After
    fun closeActivityAndRestoreOpenedPosture() {
        scenario?.close()
        scenario = null
        instrumentation.uiAutomation.executeShellCommand("cmd device_state state 2").close()
    }

    @Test
    fun rendersConnectedProductionActivityInThePhysicalFoldPosture() {
        val expectedLayout = argument("expectedLayout", "ordinary")
        val shotName = safeFileName(argument("screenshotName", expectedLayout))
        val launched = ActivityScenario.launch(MainActivity::class.java).also { scenario = it }

        val viewModel = viewModel(launched)
        val connected = runBlocking {
            withTimeout(20_000) {
                viewModel.controller.state.first { it.connection == ConnectionStatus.CONNECTED }
            }
        }
        assertTrue("The production snapshot must be available", connected.snapshot != null)
        val sessions = connected.snapshot!!.sessions
        assertEquals("Session identities must be unique", sessions.size, sessions.map { it.id }.toSet().size)
        assertPhysicalLayout(launched, expectedLayout)
        writePrivateWindowScreenshot(launched, shotName)
    }

    @Test
    fun controllerAndConnectedSnapshotSurvivePhysicalFoldTransition() {
        org.junit.Assume.assumeTrue(
            "Opt-in diagnostic: API 36 Pixel Fold emulator removes the task during live display migration",
            argument("runLiveMigration", "false") == "true",
        )
        val launched = ActivityScenario.launch(MainActivity::class.java).also { scenario = it }
        val before = viewModel(launched)
        val beforeState = runBlocking {
            withTimeout(20_000) {
                before.controller.state.first { it.connection == ConnectionStatus.CONNECTED }
            }
        }

        instrumentation.uiAutomation.executeShellCommand("cmd device_state state 1").close()
        instrumentation.uiAutomation.executeShellCommand("cmd power wakeup").close()
        waitUntilResumed(launched)
        instrumentation.uiAutomation.executeShellCommand("cmd device_state state 2").close()
        instrumentation.uiAutomation.executeShellCommand("cmd power wakeup").close()
        waitUntilResumed(launched)

        val after = viewModel(launched)
        assertSame("MainActivity must retain its controller across fold/configuration recreation", before, after)
        assertTrue("A live server update may advance but never rewind the revision",
            after.controller.state.value.snapshot!!.revision >= beforeState.snapshot!!.revision)
        assertEquals(ConnectionStatus.CONNECTED, after.controller.state.value.connection)
    }

    @Test
    fun controllerAndConnectedSnapshotSurviveActivityRecreationOnUnfoldedDisplay() {
        instrumentation.uiAutomation.executeShellCommand("cmd device_state state 2").close()
        instrumentation.uiAutomation.executeShellCommand("cmd power wakeup").close()
        val initial = instrumentation.startActivitySync(
            Intent.makeMainActivity(ComponentName(context, MainActivity::class.java))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        val before = ViewModelProvider(initial)[StintViewModel::class.java]
        val beforeState = runBlocking {
            withTimeout(20_000) {
                before.controller.state.first { it.connection == ConnectionStatus.CONNECTED }
            }
        }
        val recreated = CompletableDeferred<MainActivity>()
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: android.os.Bundle?) {
                if (activity is MainActivity && activity !== initial) recreated.complete(activity)
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        initial.application.registerActivityLifecycleCallbacks(callbacks)
        try {
            instrumentation.runOnMainSync { initial.recreate() }
            val next = runBlocking { withTimeout(20_000) { recreated.await() } }
            val after = ViewModelProvider(next)[StintViewModel::class.java]

            assertSame("Configuration recreation must retain the Stint ViewModel", before, after)
            assertTrue("Configuration recreation must not rewind the snapshot",
                after.controller.state.value.snapshot!!.revision >= beforeState.snapshot!!.revision)
            assertEquals(ConnectionStatus.CONNECTED, after.controller.state.value.connection)
            instrumentation.runOnMainSync { next.finish() }
        } finally {
            initial.application.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }

    private fun waitUntilResumed(activityScenario: ActivityScenario<MainActivity>) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (activityScenario.state == androidx.lifecycle.Lifecycle.State.RESUMED) return
            SystemClock.sleep(100)
        }
        error("MainActivity did not resume after the physical fold transition")
    }

    private fun viewModel(activityScenario: ActivityScenario<MainActivity>): StintViewModel {
        val reference = AtomicReference<StintViewModel>()
        activityScenario.onActivity { activity ->
            reference.set(ViewModelProvider(activity)[StintViewModel::class.java])
        }
        return reference.get() ?: error("Stint ViewModel was unavailable")
    }

    private fun assertPhysicalLayout(activityScenario: ActivityScenario<MainActivity>, expected: String) {
        val observation = CompletableDeferred<ObservedLayout>()
        activityScenario.onActivity { activity ->
            activity.lifecycleScope.launch {
                val feature = withTimeout(8_000) {
                    WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).first { info ->
                        val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                        when (expected) {
                            "book" -> true
                            "tabletop" -> fold?.orientation == FoldingFeature.Orientation.HORIZONTAL &&
                                fold.state == FoldingFeature.State.HALF_OPENED
                            else -> true
                        }
                    }.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                }
                val bounds = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity).bounds
                val density = activity.resources.displayMetrics.density
                observation.complete(ObservedLayout(bounds.width() / density, feature))
            }
        }
        val observed = runBlocking { withTimeout(10_000) { observation.await() } }
        when (expected) {
            "folded" -> {
                assertTrue("Folded layout should fit a narrow one-hand viewport", observed.widthDp < 600f)
                assertFalse("Folded layout should not expose a separating hinge", observed.feature?.isSeparating == true)
            }
            // Fold8 uses a continuous inner display, so an unfolded device can be wide
            // without reporting an occluding/separating hinge. The app's width fallback
            // intentionally uses the same two-pane workspace in that case.
            "book" -> assertTrue("Unfolded layout should expose a wide workspace", observed.widthDp >= 600f)
            "tabletop" -> assertTrue(
                "Tabletop requires a horizontal half-opened hinge",
                observed.feature?.orientation == FoldingFeature.Orientation.HORIZONTAL &&
                    observed.feature?.state == FoldingFeature.State.HALF_OPENED,
            )
            "ordinary" -> Unit
            else -> error("Unsupported expectedLayout: $expected")
        }
    }

    private fun writePrivateWindowScreenshot(activityScenario: ActivityScenario<MainActivity>, name: String) {
        // A cached network snapshot can arrive before the first Android layout pass.
        // Wait for an actual measured window rather than treating that race as a UI failure.
        val layoutDeadline = SystemClock.uptimeMillis() + 10_000
        var measured = false
        while (!measured && SystemClock.uptimeMillis() < layoutDeadline) {
            activityScenario.onActivity { activity ->
                measured = activity.window.decorView.width > 0 && activity.window.decorView.height > 0
            }
            if (!measured) SystemClock.sleep(50)
        }
        check(measured) { "Production Activity did not receive a drawable layout" }
        val completed = CompletableDeferred<Bitmap>()
        activityScenario.onActivity { activity ->
            val width = activity.window.decorView.width
            val height = activity.window.decorView.height
            require(width > 0 && height > 0) { "Activity window has no drawable area" }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            fun drawViewFallback() {
                activity.window.decorView.draw(Canvas(bitmap))
                completed.complete(bitmap)
            }
            try {
                PixelCopy.request(
                    activity.window,
                    bitmap,
                    { result ->
                        if (result == PixelCopy.SUCCESS) completed.complete(bitmap)
                        else drawViewFallback()
                    },
                    Handler(Looper.getMainLooper()),
                )
            } catch (_: IllegalArgumentException) {
                // The Android Emulator can report a resumed window without a backing
                // Surface during a fold transition. View.draw still captures only the
                // production Activity window and never uses an adb/system screenshot.
                drawViewFallback()
            }
        }
        val bitmap = runBlocking { withTimeout(10_000) { completed.await() } }
        assertFalse("Rendered window must not be blank", bitmapHasOneFlatColor(bitmap))
        val folder = File(context.filesDir, "screenshots").apply { mkdirs() }
        FileOutputStream(File(folder, "$name.png")).use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            stream.fd.sync()
        }
    }

    private fun bitmapHasOneFlatColor(bitmap: Bitmap): Boolean {
        val first = bitmap.getPixel(0, 0)
        val stepX = (bitmap.width / 12).coerceAtLeast(1)
        val stepY = (bitmap.height / 12).coerceAtLeast(1)
        for (x in 0 until bitmap.width step stepX) {
            for (y in 0 until bitmap.height step stepY) {
                if (Color.red(bitmap.getPixel(x, y)) != Color.red(first) ||
                    Color.green(bitmap.getPixel(x, y)) != Color.green(first) ||
                    Color.blue(bitmap.getPixel(x, y)) != Color.blue(first)
                ) return false
            }
        }
        return true
    }

    private fun consumePrivateAcceptanceConfiguration() {
        val file = File(context.filesDir, "acceptance-config.json")
        if (!file.isFile) return
        try {
            val value = JSONObject(file.readText())
            val url = value.getString("url").trim().trimEnd('/')
            val token = value.getString("token").trim()
            require(url.startsWith("https://") && token.isNotBlank())
            val existing = AndroidLocalStore(context).loadSettings()
            AndroidLocalStore(context).saveSettings(
                ConnectionSettings(
                    baseUrl = url,
                    token = token,
                    deviceId = existing.deviceId.ifBlank { "acceptance-emulator" },
                    deviceName = "Foldable acceptance emulator",
                ),
            )
        } finally {
            check(file.delete()) { "Private acceptance configuration could not be removed" }
        }
    }

    private fun argument(name: String, default: String): String =
        InstrumentationRegistry.getArguments().getString(name).orEmpty().ifBlank { default }

    private fun safeFileName(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9_-]{1,48}"))) { "Invalid screenshot name" }
        return value
    }

    private data class ObservedLayout(val widthDp: Float, val feature: FoldingFeature?)
}
