package dev.stint.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stint.app.ui.TimerClockVisual
import dev.stint.app.ui.VerticalTimeWheelReadout
import dev.stint.app.ui.WorkbenchTheme
import dev.stint.shared.TimerClockStyle
import dev.stint.shared.countdownClockReading
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Pixel regression for the read-only → editable clock transition. No controller callback is used. */
@RunWith(AndroidJUnit4::class)
class ClockReadoutLayoutTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun requireAnUnobstructedExistingTestSurface() {
        instrumentation.uiAutomation.executeShellCommand("cmd power wakeup").close()
        assumeTrue(
            "This visual test does not grant or change notification permission",
            Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
    }

    @After
    fun closeTestSurface() {
        scenario?.close()
        scenario = null
    }

    @Test
    fun fourteenMinutesKeepsCompactSpacingWhenTimeBecomesEditable() {
        assertReadoutGeometryStable("14-42", 14 * 60 + 42, widthDp = 320f, heightDp = 260f, export = true)
    }

    @Test
    fun threeDigitMinutesKeepCompactSpacingWhenTimeBecomesEditable() {
        assertReadoutGeometryStable("100-00", 100 * 60, widthDp = 320f, heightDp = 260f)
    }

    @Test
    fun compactClockKeepsTheSameReadoutWhenTimeBecomesEditable() {
        assertReadoutGeometryStable("14-42-compact", 14 * 60 + 42, widthDp = 236f, heightDp = 205f)
    }

    @Test
    fun pausedRemainingRangeKeepsPhaseSizingWhenTimeBecomesEditable() {
        assertReadoutGeometryStable(
            "14-42-paused-range",
            14 * 60 + 42,
            widthDp = 320f,
            heightDp = 260f,
            maximumSeconds = 99 * 60,
        )
    }

    @Test
    fun subMinuteReadoutStaysCenteredWhenTimeBecomesEditable() {
        assertReadoutGeometryStable("00-18", 18, widthDp = 320f, heightDp = 260f)
    }

    @Test
    fun twoDigitMaximumReadoutStaysCenteredWhenTimeBecomesEditable() {
        assertReadoutGeometryStable("99-59", 99 * 60 + 59, widthDp = 320f, heightDp = 260f)
    }

    @Test
    fun threeDigitMaximumReadoutStaysCenteredWhenTimeBecomesEditable() {
        assertReadoutGeometryStable("240-00", 240 * 60, widthDp = 320f, heightDp = 260f)
    }

    private fun assertReadoutGeometryStable(
        name: String,
        seconds: Int,
        widthDp: Float,
        heightDp: Float,
        maximumSeconds: Int = 4 * 60 * 60,
        export: Boolean = false,
    ) {
        val harness = renderClock(seconds, maximumSeconds, widthDp, heightDp)
        waitUntil("read-only clock layout") { harness.clockBounds.get()?.hasArea == true }
        settleFrames()
        val readOnlyWindow = captureWindow()

        instrumentation.runOnMainSync { harness.editable.get().value = true }
        waitUntil("editable readout layout") { harness.readoutBounds.get()?.hasArea == true }
        settleFrames()
        val editableWindow = captureWindow()
        val readoutRect = checkNotNull(harness.readoutBounds.get()).toRect(editableWindow)
        val readOnly = Bitmap.createBitmap(
            readOnlyWindow, readoutRect.left, readoutRect.top, readoutRect.width(), readoutRect.height(),
        )
        val editable = Bitmap.createBitmap(
            editableWindow, readoutRect.left, readoutRect.top, readoutRect.width(), readoutRect.height(),
        )

        assertSameReadoutGeometry("$name read-only to editable", readOnly, editable)
        assertTrue("$name must render a visible numeric readout", inkBounds(readOnly).width() > 30)
        assertReadoutCentered("$name read-only", readOnly)
        assertReadoutCentered("$name editable", editable)
        if (export) {
            writePrivatePng("readout-layout-$name-before.png", readOnly)
            writePrivatePng("readout-layout-$name-editable.png", editable)
        }

        tapMinuteField(checkNotNull(harness.readoutBounds.get()))
        settleFrames()
        assertEquals("A tap without a vertical adjustment must not settle a new value", 0, harness.settles.get())
        val afterTap = crop(captureWindow(), readoutRect)
        assertSameReadoutGeometry("$name after tap", readOnly, afterTap)
        assertReadoutCentered("$name after tap", afterTap)

        instrumentation.runOnMainSync {
            harness.readoutBounds.set(null)
            harness.editable.get().value = false
        }
        waitUntil("read-only clock restored") { harness.readoutBounds.get() == null }
        settleFrames()
        val restored = crop(captureWindow(), readoutRect)
        assertSameReadoutGeometry("$name restored read-only", readOnly, restored)
        assertReadoutCentered("$name restored read-only", restored)

        readOnly.recycle()
        editable.recycle()
        afterTap.recycle()
        restored.recycle()
        readOnlyWindow.recycle()
        editableWindow.recycle()
    }

    private fun renderClock(
        seconds: Int,
        maximumSeconds: Int,
        widthDp: Float,
        heightDp: Float,
    ): LayoutHarness {
        val harness = LayoutHarness()
        val launched = ActivityScenario.launch(MainActivity::class.java).also { scenario = it }
        launched.moveToState(Lifecycle.State.RESUMED)
        waitUntil("fixture activity window") {
            var ready = false
            launched.onActivity { activity ->
                val decor = activity.window.decorView
                ready = decor.isAttachedToWindow && decor.hasWindowFocus() && decor.width > 0 && decor.height > 0
            }
            ready
        }
        launched.onActivity { activity ->
            activity.setContent {
                WorkbenchTheme {
                    val editable = remember { mutableStateOf(false).also(harness.editable::set) }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        TimerClockVisual(
                            style = TimerClockStyle.ORBIT,
                            reading = countdownClockReading(seconds, seconds),
                            running = false,
                            height = heightDp.dp,
                            sizingReferenceSeconds = 4 * 60 * 60,
                            readoutLabel = "남은 시간",
                            modifier = Modifier.width(widthDp.dp).onGloballyPositioned { coordinates ->
                                harness.clockBounds.set(coordinates.boundsInWindow().toNumericBounds())
                            },
                            timeContent = if (editable.value) ({ style ->
                                Box(
                                    Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                                        harness.readoutBounds.set(coordinates.boundsInWindow().toNumericBounds())
                                    },
                                ) {
                                    VerticalTimeWheelReadout(
                                        totalSeconds = seconds,
                                        minimumSeconds = 1,
                                        maximumSeconds = maximumSeconds,
                                        enabled = true,
                                        interactionKey = "layout-$seconds",
                                        textStyle = style,
                                        modifier = Modifier.fillMaxSize(),
                                        onPreview = {},
                                        onSettled = { _, _ -> harness.settles.incrementAndGet() },
                                        onCancel = {},
                                        onGestureActive = {},
                                        onTick = {},
                                    )
                                }
                            }) else null,
                        )
                    }
                }
            }
        }
        instrumentation.waitForIdleSync()
        return harness
    }

    private fun tapMinuteField(bounds: NumericBounds) {
        val launched = checkNotNull(scenario)
        val screenOrigin = IntArray(2)
        launched.onActivity { activity -> activity.window.decorView.getLocationOnScreen(screenOrigin) }
        val x = screenOrigin[0] + bounds.centerX - bounds.width * .19f
        val y = screenOrigin[1] + bounds.centerY
        val downTime = SystemClock.uptimeMillis()
        sendPointer(MotionEvent.ACTION_DOWN, downTime, x, y)
        sendPointer(MotionEvent.ACTION_UP, downTime, x, y)
        instrumentation.waitForIdleSync()
    }

    private fun sendPointer(action: Int, downTime: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            instrumentation.sendPointerSync(event)
        } finally {
            event.recycle()
        }
    }

    private fun captureWindow(): Bitmap {
        val completed = CountDownLatch(1)
        val captured = AtomicReference<Bitmap?>()
        checkNotNull(scenario).onActivity { activity ->
            val decor = activity.window.decorView
            require(decor.width > 0 && decor.height > 0)
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            fun fallback() {
                decor.draw(Canvas(bitmap))
                captured.set(bitmap)
                completed.countDown()
            }
            try {
                PixelCopy.request(activity.window, bitmap, { result ->
                    if (result == PixelCopy.SUCCESS) {
                        captured.set(bitmap)
                        completed.countDown()
                    } else {
                        fallback()
                    }
                }, Handler(Looper.getMainLooper()))
            } catch (_: IllegalArgumentException) {
                fallback()
            }
        }
        check(completed.await(10, TimeUnit.SECONDS)) { "Timed out capturing the clock fixture" }
        return checkNotNull(captured.get())
    }

    private fun assertSameReadoutGeometry(label: String, expected: Bitmap, actual: Bitmap) {
        assertEquals("$label width", expected.width, actual.width)
        assertEquals("$label height", expected.height, actual.height)
        val expectedBounds = inkBounds(expected)
        val actualBounds = inkBounds(actual)
        assertEquals("$label ink left", expectedBounds.left, actualBounds.left)
        assertEquals("$label ink top", expectedBounds.top, actualBounds.top)
        assertEquals("$label ink right", expectedBounds.right, actualBounds.right)
        assertEquals("$label ink bottom", expectedBounds.bottom, actualBounds.bottom)

        val expectedPixels = IntArray(expected.width * expected.height)
        val actualPixels = IntArray(actual.width * actual.height)
        expected.getPixels(expectedPixels, 0, expected.width, 0, 0, expected.width, expected.height)
        actual.getPixels(actualPixels, 0, actual.width, 0, 0, actual.width, actual.height)
        val changed = expectedPixels.indices.count { index -> colorDistance(expectedPixels[index], actualPixels[index]) > 6 }
        val allowance = (expectedPixels.size * .001f).toInt().coerceAtLeast(4)
        assertTrue("$label changed $changed pixels; allowed $allowance", changed <= allowance)
    }

    private fun assertReadoutCentered(label: String, bitmap: Bitmap) {
        val inkCenter = inkBounds(bitmap).exactCenterX()
        val clockCenter = bitmap.width / 2f
        val offset = abs(inkCenter - clockCenter)
        val tolerance = maxOf(3f, bitmap.width * .025f)
        assertTrue(
            "$label ink center $inkCenter is ${offset}px from clock center $clockCenter; allowed $tolerance",
            offset <= tolerance,
        )
    }

    private fun inkBounds(bitmap: Bitmap): Rect {
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val color = bitmap.getPixel(x, y)
                val red = color shr 16 and 0xFF
                val green = color shr 8 and 0xFF
                val blue = color and 0xFF
                if (red < 100 && green < 115 && blue < 110) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        check(right >= left && bottom >= top) { "No ink pixels found in clock readout" }
        return Rect(left, top, right + 1, bottom + 1)
    }

    private fun colorDistance(first: Int, second: Int): Int =
        abs((first shr 16 and 0xFF) - (second shr 16 and 0xFF)) +
            abs((first shr 8 and 0xFF) - (second shr 8 and 0xFF)) +
            abs((first and 0xFF) - (second and 0xFF))

    private fun crop(source: Bitmap, rect: Rect): Bitmap =
        Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height()).also { source.recycle() }

    private fun writePrivatePng(name: String, bitmap: Bitmap) {
        FileOutputStream(File(context.filesDir, name)).use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            stream.fd.sync()
        }
    }

    private fun settleFrames() {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(280)
        instrumentation.waitForIdleSync()
    }

    private fun waitUntil(label: String, timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(20)
        }
        error("Timed out waiting for $label")
    }

    private class LayoutHarness {
        val editable = AtomicReference<MutableState<Boolean>>()
        val clockBounds = AtomicReference<NumericBounds?>()
        val readoutBounds = AtomicReference<NumericBounds?>()
        val settles = AtomicInteger()
    }

    private data class NumericBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val centerX get() = (left + right) / 2f
        val centerY get() = (top + bottom) / 2f
        val hasArea get() = width > 0f && bottom > top

        fun toRect(bitmap: Bitmap): Rect = Rect(
            left.toInt().coerceIn(0, bitmap.width - 1),
            top.toInt().coerceIn(0, bitmap.height - 1),
            right.toInt().coerceIn(1, bitmap.width),
            bottom.toInt().coerceIn(1, bitmap.height),
        )
    }

    private fun androidx.compose.ui.geometry.Rect.toNumericBounds() =
        NumericBounds(left, top, right, bottom)
}
