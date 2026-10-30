package dev.stint.app

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stint.app.ui.ClockTimeDial
import dev.stint.app.ui.TimerClockVisual
import dev.stint.app.ui.WorkbenchTheme
import dev.stint.shared.TimerClockStyle
import dev.stint.shared.countdownClockReading
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the minute and second digit wheels with native MotionEvents and local callbacks only. */
@RunWith(AndroidJUnit4::class)
class TimeDialGestureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun keepTheTestSurfaceUnobstructed() {
        instrumentation.uiAutomation.executeShellCommand("cmd power wakeup").close()
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
    }

    @After
    fun closeTestSurface() {
        scenario?.close()
        scenario = null
    }

    @Test
    fun minuteWheelMovesBothDirectionsWithoutChangingSeconds() {
        val harness = renderWheels()
        val surface = awaitSurface(harness)

        verticalDrag(surface.minuteX, surface.lowerY, surface.farUpperY, moveCount = 12)
        waitUntil("minute wheel up commit") { harness.commitAttempts.get() == 1 }
        val increased = harness.committedSeconds.get()
        assertTrue("Dragging minutes up must increase total minutes", increased / 60 > 25)
        assertEquals("Minute changes must preserve seconds", 17, increased % 60)
        assertTrue("Crossing minute detents must tick", harness.ticks.get() >= 2)
        writePrivateWindowScreenshot("vertical-wheel-fixture")

        waitUntil("minute wheel ready after acknowledged commit") {
            !harness.adjusting.get() && harness.renderedPreviewSeconds.get() == null
        }
        val ticksAfterUp = harness.ticks.get()
        verticalDrag(surface.minuteX, surface.upperY, surface.farLowerY, moveCount = 12)
        waitUntil("minute wheel down commit") { harness.commitAttempts.get() == 2 }
        val decreased = harness.committedSeconds.get()
        assertTrue("Dragging minutes down must decrease total minutes", decreased / 60 < increased / 60)
        assertEquals("Minute changes must keep seconds", 17, decreased % 60)
        assertTrue("The reverse minute gesture must tick", harness.ticks.get() > ticksAfterUp)
        assertEquals(2, harness.acceptedCommits.get())
    }

    @Test
    fun secondWheelMovesBothDirectionsWithoutChangingMinutes() {
        val harness = renderWheels()
        val surface = awaitSurface(harness)

        verticalDrag(surface.secondX, surface.lowerY, surface.upperY)
        waitUntil("second wheel up commit") { harness.commitAttempts.get() == 1 }
        val increased = harness.committedSeconds.get()
        assertEquals("Second changes must preserve minutes", 25, increased / 60)
        assertTrue("Dragging seconds up must increase seconds", increased % 60 > 17)

        waitUntil("second wheel ready after acknowledged commit") {
            !harness.adjusting.get() && harness.renderedPreviewSeconds.get() == null
        }
        verticalDrag(surface.secondX, surface.upperY, surface.lowerY)
        waitUntil("second wheel down commit") { harness.commitAttempts.get() == 2 }
        val decreased = harness.committedSeconds.get()
        assertEquals("Second changes must keep minutes", 25, decreased / 60)
        assertTrue("Dragging seconds down must decrease seconds", decreased % 60 < increased % 60)
        assertEquals(2, harness.acceptedCommits.get())
        assertTrue("Second detents must tick", harness.ticks.get() >= 2)
    }

    @Test
    fun subMinuteValueAdjustsSecondsWithoutInventingAMinute() {
        val harness = renderWheels(initialSeconds = 30)
        val surface = awaitSurface(harness)

        verticalDrag(surface.secondX, surface.upperY, surface.lowerY)
        waitUntil("sub-minute second commit") { harness.commitAttempts.get() == 1 }

        val committed = harness.committedSeconds.get()
        assertEquals(0, committed / 60)
        assertTrue("Dragging down from 00:30 must reduce seconds", committed in 1..29)
    }

    @Test
    fun cancelledVerticalGestureNeverCommits() {
        val harness = renderWheels()
        val surface = awaitSurface(harness)

        verticalDrag(surface.minuteX, surface.lowerY, surface.upperY,
            finishAction = MotionEvent.ACTION_CANCEL)

        assertStaysZero("cancelled gesture") { harness.commitAttempts.get() }
        waitUntil("cancelled gesture state reset") {
            !harness.adjusting.get() && harness.renderedPreviewSeconds.get() == null
        }
        assertEquals(INITIAL_SECONDS, harness.authoritativeSeconds.get().intValue)
    }

    @Test
    fun interactionReplacementDuringGestureCancelsTheOldIntent() {
        val harness = renderWheels()
        val surface = awaitSurface(harness)
        val downTime = sendDown(surface.secondX, surface.lowerY)
        sendMove(downTime, surface.secondX, surface.wheelCenterY)

        instrumentation.runOnMainSync { harness.interactionKey.get().value = "timer-b" }
        instrumentation.waitForIdleSync()
        sendUp(downTime, surface.secondX, surface.upperY)

        assertStaysZero("replaced interaction") { harness.commitAttempts.get() }
        assertEquals(INITIAL_SECONDS, harness.authoritativeSeconds.get().intValue)
    }

    @Test
    fun verticalDragOutsideDigitsScrollsParentWithoutChangingTime() {
        val harness = renderWheels(scrollable = true)
        val surface = awaitSurface(harness)

        verticalDrag(surface.outsideX, surface.lowerY, surface.top + surface.wheelHeight * .05f)

        waitUntil("parent vertical scroll") { harness.scrollPosition.get() > 0 }
        assertEquals(0, harness.commitAttempts.get())
        assertEquals(0, harness.ticks.get())
        assertEquals(INITIAL_SECONDS, harness.authoritativeSeconds.get().intValue)
    }

    @Test
    fun disabledWheelsIgnorePointerInput() {
        val harness = renderWheels(enabled = false)
        val surface = awaitSurface(harness)

        verticalDrag(surface.minuteX, surface.lowerY, surface.upperY)
        verticalDrag(surface.secondX, surface.lowerY, surface.upperY)

        assertStaysZero("disabled wheels") { harness.commitAttempts.get() }
        assertEquals(0, harness.ticks.get())
        assertEquals(INITIAL_SECONDS, harness.authoritativeSeconds.get().intValue)
    }

    private fun renderWheels(
        enabled: Boolean = true,
        scrollable: Boolean = false,
        initialSeconds: Int = INITIAL_SECONDS,
    ): WheelHarness {
        val harness = WheelHarness(initialSeconds)
        val launched = ActivityScenario.launch(MainActivity::class.java).also { scenario = it }
        launched.onActivity { activity ->
            activity.setContent {
                WorkbenchTheme {
                    val authoritativeSeconds = remember {
                        mutableIntStateOf(initialSeconds).also(harness.authoritativeSeconds::set)
                    }
                    val interactionKey = remember {
                        mutableStateOf("timer-a").also(harness.interactionKey::set)
                    }
                    val scrollState = rememberScrollState()
                    harness.scrollPosition.set(scrollState.value)
                    val content: @androidx.compose.runtime.Composable () -> Unit = {
                        Box(
                            Modifier.fillMaxWidth().height(WHEEL_HEIGHT_DP.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            ClockTimeDial(
                                valueSeconds = authoritativeSeconds.intValue,
                                minimumSeconds = 1,
                                maximumSeconds = 4 * 60 * 60,
                                enabled = enabled,
                                interactionKey = interactionKey.value,
                                busy = false,
                                feedbackMessage = null,
                                onCommit = { seconds, key ->
                                    harness.commitAttempts.incrementAndGet()
                                    if (key != interactionKey.value) {
                                        false
                                    } else {
                                        harness.committedSeconds.set(seconds)
                                        harness.acceptedCommits.incrementAndGet()
                                        authoritativeSeconds.intValue = seconds
                                        true
                                    }
                                },
                                height = WHEEL_HEIGHT_DP.dp,
                                modifier = Modifier.width(WHEEL_WIDTH_DP.dp),
                                onTick = { harness.ticks.incrementAndGet() },
                                onAdjustingChange = harness.adjusting::set,
                            ) { previewSeconds, faceHeight, timeContent ->
                                harness.renderedPreviewSeconds.set(previewSeconds)
                                val seconds = previewSeconds ?: authoritativeSeconds.intValue
                                TimerClockVisual(
                                    style = TimerClockStyle.ORBIT,
                                    reading = countdownClockReading(seconds, seconds),
                                    running = false,
                                    height = faceHeight,
                                    sizingReferenceSeconds = 4 * 60 * 60,
                                    readoutLabel = "전체 목표",
                                    timeContent = { style ->
                                        Box(
                                            Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                                                val bounds = coordinates.boundsInWindow()
                                                harness.readoutBoundsInWindow.set(
                                                    NumericBounds(bounds.left, bounds.top,
                                                        bounds.right, bounds.bottom),
                                                )
                                            },
                                        ) {
                                            harness.timeContentMounted.set(true)
                                            timeContent?.invoke(this, style) ?: Text(
                                                text = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}",
                                                modifier = Modifier.fillMaxWidth(),
                                                style = style,
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                    if (scrollable) {
                        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
                            Spacer(Modifier.height(TOP_SPACER_DP.dp))
                            content()
                            Spacer(Modifier.height(1_200.dp))
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            Spacer(Modifier.height(TOP_SPACER_DP.dp))
                            content()
                        }
                    }
                    harness.scrollPosition.set(scrollState.value)
                }
            }
        }
        instrumentation.waitForIdleSync()
        return harness
    }

    private fun awaitSurface(harness: WheelHarness): WheelSurface {
        val launched = checkNotNull(scenario)
        val reference = AtomicReference<WheelSurface?>()
        waitUntil("time wheel layout") {
            launched.onActivity { activity ->
                val content = activity.findViewById<View>(android.R.id.content)
                val bounds = harness.readoutBoundsInWindow.get()
                if (content.width > 0 && content.height > 0 && bounds != null &&
                    bounds.right > bounds.left && bounds.bottom > bounds.top
                ) {
                    val windowOrigin = IntArray(2)
                    activity.window.decorView.getLocationOnScreen(windowOrigin)
                    val contentOrigin = IntArray(2)
                    content.getLocationOnScreen(contentOrigin)
                    reference.set(
                        WheelSurface(
                            left = windowOrigin[0] + bounds.left,
                            top = windowOrigin[1] + bounds.top,
                            width = bounds.right - bounds.left,
                            wheelHeight = bounds.bottom - bounds.top,
                            contentLeft = contentOrigin[0].toFloat(),
                        ),
                    )
                }
            }
            harness.authoritativeSeconds.get() != null && harness.timeContentMounted.get() &&
                reference.get() != null
        }
        return checkNotNull(reference.get())
    }

    private fun writePrivateWindowScreenshot(name: String) {
        val launched = checkNotNull(scenario)
        val completed = CountDownLatch(1)
        val captured = AtomicReference<Bitmap?>()
        launched.onActivity { activity ->
            val width = activity.window.decorView.width
            val height = activity.window.decorView.height
            require(width > 0 && height > 0) { "Test Activity window has no drawable area" }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            fun drawFallback() {
                activity.window.decorView.draw(Canvas(bitmap))
                captured.set(bitmap)
                completed.countDown()
            }
            try {
                PixelCopy.request(
                    activity.window,
                    bitmap,
                    { result ->
                        if (result == PixelCopy.SUCCESS) {
                            captured.set(bitmap)
                            completed.countDown()
                        } else {
                            drawFallback()
                        }
                    },
                    Handler(Looper.getMainLooper()),
                )
            } catch (_: IllegalArgumentException) {
                drawFallback()
            }
        }
        check(completed.await(10, TimeUnit.SECONDS)) { "Timed out capturing the vertical wheel fixture" }
        val bitmap = checkNotNull(captured.get())
        val folder = File(context.filesDir, "screenshots").apply { mkdirs() }
        FileOutputStream(File(folder, "$name.png")).use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            stream.fd.sync()
        }
    }

    private fun verticalDrag(
        x: Float,
        fromY: Float,
        toY: Float,
        moveCount: Int = 8,
        finishAction: Int = MotionEvent.ACTION_UP,
    ) {
        val downTime = sendDown(x, fromY)
        repeat(moveCount) { index ->
            val fraction = (index + 1f) / moveCount
            sendMove(downTime, x, fromY + (toY - fromY) * fraction)
        }
        sendPointer(finishAction, downTime, x, toY)
        instrumentation.waitForIdleSync()
    }

    private fun sendDown(x: Float, y: Float): Long {
        val downTime = SystemClock.uptimeMillis()
        sendPointer(MotionEvent.ACTION_DOWN, downTime, x, y)
        return downTime
    }

    private fun sendMove(downTime: Long, x: Float, y: Float) {
        SystemClock.sleep(18)
        sendPointer(MotionEvent.ACTION_MOVE, downTime, x, y)
    }

    private fun sendUp(downTime: Long, x: Float, y: Float) {
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

    private fun waitUntil(label: String, timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(20)
        }
        error("Timed out waiting for $label")
    }

    private fun assertStaysZero(label: String, durationMillis: Long = 700, value: () -> Int) {
        val deadline = SystemClock.uptimeMillis() + durationMillis
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            assertEquals("$label must not commit", 0, value())
            SystemClock.sleep(25)
        }
    }

    private class WheelHarness(initialSeconds: Int) {
        val authoritativeSeconds = AtomicReference<MutableIntState>()
        val interactionKey = AtomicReference<MutableState<String>>()
        val committedSeconds = AtomicInteger(initialSeconds)
        val commitAttempts = AtomicInteger()
        val acceptedCommits = AtomicInteger()
        val ticks = AtomicInteger()
        val adjusting = AtomicBoolean()
        val renderedPreviewSeconds = AtomicReference<Int?>()
        val readoutBoundsInWindow = AtomicReference<NumericBounds?>()
        val timeContentMounted = AtomicBoolean()
        val scrollPosition = AtomicInteger()
    }

    private data class NumericBounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    private data class WheelSurface(
        val left: Float,
        val top: Float,
        val width: Float,
        val wheelHeight: Float,
        val contentLeft: Float,
    ) {
        val minuteX get() = left + width * .24f
        val secondX get() = left + width * .76f
        val outsideX get() = contentLeft + 20f
        val wheelCenterY get() = top + wheelHeight / 2f
        val upperY get() = wheelCenterY - wheelHeight * .35f
        val lowerY get() = wheelCenterY + wheelHeight * .35f
        val farUpperY get() = wheelCenterY - wheelHeight * 1.8f
        val farLowerY get() = wheelCenterY + wheelHeight * 1.8f
    }

    private companion object {
        const val INITIAL_SECONDS = 25 * 60 + 17
        const val WHEEL_WIDTH_DP = 320f
        const val WHEEL_HEIGHT_DP = 220f
        const val TOP_SPACER_DP = 120f
    }
}
