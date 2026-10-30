package dev.stint.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stint.shared.ConnectionStatus
import dev.stint.shared.TimerStatus
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in production acceptance: starts through the real button and leaves the new timer paused. */
@RunWith(AndroidJUnit4::class)
class LiveTimerStartTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val controller get() = (context.applicationContext as StintApplication).controller

    @Test
    fun startButtonCreatesOneTimerAndPauseButtonStopsItWithoutAddingARecord() {
        assumeTrue(
            "Live timer commands require -e allowLiveTimerCommands true",
            InstrumentationRegistry.getArguments().getString("allowLiveTimerCommands") == "true",
        )
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var startedTimerId: String? = null
        try {
            val initialSnapshot = requireNotNull(connectedIdleState().snapshot)
            val initialSessionIds = initialSnapshot.sessions.map { it.id }
            assumeTrue(
                "Live start acceptance requires an idle production timer; no command was sent",
                initialSnapshot.timer.status == TimerStatus.IDLE,
            )
            assumeTrue(
                "Live acceptance requires focus defaults of at least one minute to pause safely before completion",
                initialSnapshot.subjects.filterNot { it.archived }.all { it.focusSeconds >= 60 },
            )
            awaitOwnActivityWindow(scenario)
            writePrivateWindowScreenshot(scenario)
            writePrivateUiDiagnostics(scenario)
            clickExactOwnAppText("집중 시작")
            val running = runBlocking {
                withTimeout(20_000) {
                    controller.state.first { state ->
                        state.connection == ConnectionStatus.CONNECTED && !state.working &&
                            state.snapshot?.timer?.status == TimerStatus.RUNNING
                    }
                }
            }
            val runningSnapshot = requireNotNull(running.snapshot)
            startedTimerId = requireNotNull(runningSnapshot.timer.id)
            assertNotEquals("Starting must create a fresh timer identity", initialSnapshot.timer.id, startedTimerId)
            val subject = requireNotNull(
                runningSnapshot.subjects.firstOrNull { it.id == runningSnapshot.timer.subjectId },
            )
            assertEquals(subject.focusSeconds, runningSnapshot.timer.targetSeconds)
            assertEquals(initialSessionIds, runningSnapshot.sessions.map { it.id })

            clickExactOwnAppText("일시정지")
            val paused = runBlocking {
                withTimeout(20_000) {
                    controller.state.first { state ->
                        state.connection == ConnectionStatus.CONNECTED && !state.working &&
                            state.snapshot?.timer?.id == startedTimerId &&
                            state.snapshot?.timer?.status == TimerStatus.PAUSED
                    }
                }
            }
            val pausedSnapshot = requireNotNull(paused.snapshot)
            assertEquals(initialSessionIds, pausedSnapshot.sessions.map { it.id })
            assertTrue("The real timer should accrue a small positive elapsed duration",
                pausedSnapshot.timer.accumulatedMillis > 0L)
            writePrivateReport(
                timerId = requireNotNull(startedTimerId),
                revision = pausedSnapshot.revision,
                targetSeconds = pausedSnapshot.timer.targetSeconds,
                accumulatedMillis = pausedSnapshot.timer.accumulatedMillis,
                sessionIds = initialSessionIds,
            )
        } finally {
            val latest = controller.state.value.snapshot?.timer
            if (startedTimerId != null && latest?.id == startedTimerId && latest.status == TimerStatus.RUNNING) {
                controller.pause()
                runBlocking {
                    withTimeout(20_000) {
                        controller.state.first { state ->
                            !state.working && state.snapshot?.timer?.id == startedTimerId &&
                                state.snapshot?.timer?.status == TimerStatus.PAUSED
                        }
                    }
                }
            }
            scenario.close()
        }
    }

    private fun connectedIdleState() = runBlocking {
        withTimeout(20_000) {
            controller.state.first { state ->
                state.connection == ConnectionStatus.CONNECTED && !state.working && state.snapshot != null
            }
        }
    }

    private fun clickExactOwnAppText(text: String) {
        val deadline = SystemClock.elapsedRealtime() + 12_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val target = accessibilityTree(root)
                .firstOrNull { node ->
                    (node.text?.toString() == text || node.contentDescription?.toString() == text) &&
                        node.packageName?.toString() == context.packageName
                }
            val clickable = generateSequence(target) { node -> node.parent }
                .firstOrNull { node ->
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

    private fun awaitOwnActivityWindow(scenario: ActivityScenario<MainActivity>) {
        val deadline = SystemClock.elapsedRealtime() + 12_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            var activityReady = false
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                activityReady = decor.isAttachedToWindow && decor.hasWindowFocus() &&
                    decor.width > 0 && decor.height > 0
            }
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (activityReady && root?.packageName?.toString() == context.packageName) return
            SystemClock.sleep(100L)
        }
        writePrivateUiDiagnostics(scenario)
        throw AssertionError("Stint Activity never became the active, focused accessibility window")
    }

    private fun writePrivateWindowScreenshot(scenario: ActivityScenario<MainActivity>) {
        val completed = CompletableDeferred<Bitmap>()
        scenario.onActivity { activity ->
            val view = activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            fun fallback() {
                view.draw(Canvas(bitmap))
                completed.complete(bitmap)
            }
            try {
                PixelCopy.request(
                    activity.window,
                    bitmap,
                    { result -> if (result == PixelCopy.SUCCESS) completed.complete(bitmap) else fallback() },
                    Handler(Looper.getMainLooper()),
                )
            } catch (_: IllegalArgumentException) {
                fallback()
            }
        }
        val bitmap = runBlocking { withTimeout(10_000L) { completed.await() } }
        FileOutputStream(File(context.filesDir, "live-start-before.png")).use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            stream.fd.sync()
        }
        bitmap.recycle()
    }

    private fun writePrivateUiDiagnostics(scenario: ActivityScenario<MainActivity>) {
        var focused = false
        var width = 0
        var height = 0
        scenario.onActivity { activity ->
            focused = activity.window.decorView.hasWindowFocus()
            width = activity.window.decorView.width
            height = activity.window.decorView.height
        }
        val root = instrumentation.uiAutomation.rootInActiveWindow
        val tree = accessibilityTree(root)
        val knownLabels = listOf("집중 시작", "일시정지", "계속하기", "새로고침", "시간 설정")
        val labels = JSONArray()
        knownLabels.forEach { label ->
            val matches = JSONArray()
            tree.forEach { node ->
                if (node.packageName?.toString() == context.packageName &&
                    (node.text?.toString() == label || node.contentDescription?.toString() == label)
                ) {
                    val clickable = generateSequence(node) { current -> current.parent }
                        .indexOfFirst { current ->
                            current.isClickable || current.actionList.any {
                                it.id == AccessibilityNodeInfo.ACTION_CLICK
                            }
                        }
                    matches.put(JSONObject()
                        .put("enabled", node.isEnabled)
                        .put("clickable", node.isClickable)
                        .put("clickableAncestorDepth", clickable))
                }
            }
            labels.put(JSONObject().put("label", label).put("matches", matches))
        }
        val report = JSONObject()
            .put("rootPackage", root?.packageName?.toString().orEmpty())
            .put("rootClass", root?.className?.toString().orEmpty())
            .put("rootWindowId", root?.windowId ?: -1)
            .put("activityWindowFocused", focused)
            .put("activityWidth", width)
            .put("activityHeight", height)
            .put("knownControls", labels)
        File(context.filesDir, "live-start-ui.json").writeText(report.toString(2))
    }

    /** Compose's provider can leave find-by-text empty, so inspect its bounded semantic tree. */
    private fun accessibilityTree(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        val result = ArrayList<AccessibilityNodeInfo>()
        pending.add(root)
        while (pending.isNotEmpty() && result.size < MAX_ACCESSIBILITY_NODES) {
            val node = pending.removeFirst()
            result.add(node)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(pending::addLast)
            }
        }
        return result
    }

    private fun writePrivateReport(
        timerId: String,
        revision: Long,
        targetSeconds: Int,
        accumulatedMillis: Long,
        sessionIds: List<String>,
    ) {
        val report = JSONObject()
            .put("timerId", timerId)
            .put("status", TimerStatus.PAUSED.name)
            .put("revision", revision)
            .put("targetSeconds", targetSeconds)
            .put("accumulatedMillis", accumulatedMillis)
            .put("preservedSessionIds", JSONArray(sessionIds))
        File(context.filesDir, "live-start-qa.json").writeText(report.toString(2))
    }

    private companion object {
        const val MAX_ACCESSIBILITY_NODES = 4_096
    }
}
