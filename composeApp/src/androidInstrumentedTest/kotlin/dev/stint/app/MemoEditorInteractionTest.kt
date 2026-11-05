package dev.stint.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stint.app.ui.MemoEditor
import dev.stint.app.ui.Paper
import dev.stint.app.ui.WorkbenchTheme
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Native keyboard and touch acceptance for the isolated memo draft editor. */
@RunWith(AndroidJUnit4::class)
class MemoEditorInteractionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun requireAnUnobstructedExistingSurface() {
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
    fun tabAcceptsTheGhostAndOnlyExplicitSavePersistsIt() {
        val harness = launchFixture("tab")
        assertOwnAppText(LATEST_MEMO)
        assertEditableValue("")
        writePrivateWindowScreenshot("memo-ghost.png")

        sendKey(KeyEvent.KEYCODE_TAB)

        assertEditableValue(LATEST_MEMO)
        assertEquals(0, harness.saveCount.get())
        clickExactOwnAppText("저장")
        waitUntil("memo save callback") { harness.saveCount.get() == 1 }
        assertEquals(LATEST_MEMO, harness.savedMemo.get())
    }

    @Test
    fun arrowsChooseFromFiveUniqueRecentMemosAndEscapeClosesWithoutChangingTheDraft() {
        val harness = launchFixture("keyboard")

        sendKey(KeyEvent.KEYCODE_DPAD_DOWN)
        assertSuggestionRows(5)
        assertHighlightedSuggestion(1, LATEST_MEMO)
        writePrivateWindowScreenshot("memo-suggestions.png")

        sendKey(KeyEvent.KEYCODE_DPAD_DOWN)
        assertHighlightedSuggestion(2, SECOND_MEMO)
        sendKey(KeyEvent.KEYCODE_ENTER)

        assertEditableValue(SECOND_MEMO)
        assertEquals(0, harness.saveCount.get())
        assertSuggestionRows(0)

        sendKey(KeyEvent.KEYCODE_DPAD_DOWN)
        assertSuggestionRows(5)
        sendKey(KeyEvent.KEYCODE_ESCAPE)

        assertSuggestionRows(0)
        assertEditableValue(SECOND_MEMO)
        assertEquals(0, harness.saveCount.get())
    }

    @Test
    fun theTouchShortcutFillsTheLatestMemoWithoutSaving() {
        val harness = launchFixture("touch")

        clickExactOwnAppText("직전 회고 사용")

        assertEditableValue(LATEST_MEMO)
        assertEquals(0, harness.saveCount.get())
        assertFalse(harness.cancelled.get())
    }

    private fun launchFixture(id: String): MemoHarness {
        val harness = MemoHarness()
        val launched = ActivityScenario.launch(MainActivity::class.java).also { scenario = it }
        launched.moveToState(Lifecycle.State.RESUMED)
        launched.onActivity { activity ->
            activity.setContent {
                WorkbenchTheme {
                    Surface(Modifier.fillMaxSize(), color = Paper) {
                        MemoEditor(
                            sessionId = "memo-$id",
                            initialMemo = "",
                            recentMemos = RECENT_MEMOS,
                            enabled = true,
                            onSave = { memo ->
                                harness.savedMemo.set(memo)
                                harness.saveCount.incrementAndGet()
                            },
                            onCancel = { harness.cancelled.set(true) },
                        )
                    }
                }
            }
        }
        awaitOwnActivityWindow()
        waitUntil("memo editor autofocus") { ownAccessibilityTree().any { it.isEditable && it.isFocused } }
        return harness
    }

    private fun sendKey(keyCode: Int) {
        instrumentation.sendKeyDownUpSync(keyCode)
        instrumentation.waitForIdleSync()
    }

    private fun assertEditableValue(expected: String) {
        waitUntil("editable memo value '$expected'") {
            ownAccessibilityTree().any { node -> node.isEditable && node.text?.toString().orEmpty() == expected }
        }
    }

    private fun assertSuggestionRows(expected: Int) {
        waitUntil("$expected recent memo rows") {
            ownAccessibilityTree().count { node ->
                node.contentDescription?.toString()?.matches(Regex("최근 회고 [1-5]: .*")) == true
            } == expected
        }
    }

    private fun assertHighlightedSuggestion(index: Int, memo: String) {
        val expected = "최근 회고 $index: $memo"
        waitUntil("selected row '$expected'") {
            ownAccessibilityTree().any { node ->
                node.contentDescription?.toString() == expected && (node.isSelected || node.isChecked)
            }
        }
    }

    private fun clickExactOwnAppText(text: String) {
        val deadline = SystemClock.elapsedRealtime() + 8_000L
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
        throw AssertionError("Could not click Stint control with exact text: $text")
    }

    private fun assertOwnAppText(text: String) {
        waitUntil("Stint text '$text'") {
            ownAccessibilityTree().any { node ->
                node.text?.toString() == text || node.contentDescription?.toString() == text
            }
        }
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
        val deadline = SystemClock.elapsedRealtime() + 12_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val launched = scenario ?: break
            var ready = false
            launched.onActivity { activity ->
                val decor = activity.window.decorView
                ready = decor.isAttachedToWindow && decor.hasWindowFocus() && decor.width > 0 && decor.height > 0
            }
            if (ready && instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == context.packageName) return
            SystemClock.sleep(100L)
        }
        throw AssertionError("Memo fixture never became the active Stint window")
    }

    private fun waitUntil(label: String, timeoutMillis: Long = 8_000L, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100L)
        }
        throw AssertionError("Timed out waiting for $label; ${memoNodeDiagnostics()}")
    }

    /** Failure output is deliberately limited to this fake editor's field and suggestion rows. */
    private fun memoNodeDiagnostics(): String = ownAccessibilityTree().asSequence()
        .filter { node ->
            node.isEditable || node.contentDescription?.toString()?.matches(Regex("최근 회고 [1-5]: .*")) == true
        }
        .joinToString(prefix = "memoNodes=[", postfix = "]") { node ->
            "{editable=${node.isEditable},focused=${node.isFocused},selected=${node.isSelected}," +
                "checkable=${node.isCheckable},checked=${node.isChecked}," +
                "text=${node.text?.toString().orEmpty().take(80)}," +
                "description=${node.contentDescription?.toString().orEmpty().take(100)}}"
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
        assertTrue("Memo screenshot timed out", completed.await(5, TimeUnit.SECONDS))
        val output = requireNotNull(bitmap.get())
        FileOutputStream(File(context.filesDir, name)).use { stream ->
            assertTrue("PNG writer failed", output.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        output.recycle()
    }

    private class MemoHarness {
        val saveCount = AtomicInteger()
        val savedMemo = AtomicReference<String>()
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    private companion object {
        const val LATEST_MEMO = "최근 회고"
        const val SECOND_MEMO = "두 번째"
        val RECENT_MEMOS = listOf(
            LATEST_MEMO,
            SECOND_MEMO,
            "  $LATEST_MEMO  ",
            "세 번째",
            "네 번째",
            "다섯 번째",
            "여섯 번째",
        )
    }
}
