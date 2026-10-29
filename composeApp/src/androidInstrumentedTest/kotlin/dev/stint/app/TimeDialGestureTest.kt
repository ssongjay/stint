package dev.stint.app

import android.Manifest
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stint.app.ui.ClockTimeDial
import dev.stint.app.ui.TimerClockVisual
import dev.stint.app.ui.WorkbenchTheme
import dev.stint.shared.TimerClockStyle
import dev.stint.shared.countdownClockReading
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the rendered dial with native MotionEvents and local callbacks only. */
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
    fun horizontalDragTicksAcrossMinutesAndCommitsExactlyOnce() {
        val harness = renderDial()
        val surface = awaitSurface(harness)

        drag(
            surface = surface,
            fromX = surface.centerX + surface.width * .22f,
            fromY = surface.dialCenterY,
            toX = surface.centerX - surface.width * .22f,
            toY = surface.dialCenterY,
            moveCount = 8,
        )

        waitUntil("horizontal dial commit") { harness.commitAttempts.get() == 1 }
        assertEquals(1, harness.acceptedCommits.get())
        assertTrue("A left drag must advance the target", harness.committedMinutes.get() > 25)
        assertTrue("Crossing multiple minute detents must emit multiple ticks", harness.ticks.get() >= 2)
    }

    @Test
    fun cancelledHorizontalGestureNeverCommits() {
        val harness = renderDial()
        val surface = awaitSurface(harness)

        drag(
            surface = surface,
            fromX = surface.centerX + surface.width * .18f,
            fromY = surface.dialCenterY,
            toX = surface.centerX - surface.width * .18f,
            toY = surface.dialCenterY,
            moveCount = 4,
            finishAction = MotionEvent.ACTION_CANCEL,
        )

        assertStaysZero("cancelled gesture") { harness.commitAttempts.get() }
    }

    @Test
    fun interactionReplacementDuringGestureCancelsTheOldIntent() {
        val harness = renderDial()
        val surface = awaitSurface(harness)
        val downTime = sendDown(surface.centerX + surface.width * .18f, surface.dialCenterY)
        sendMove(downTime, surface.centerX - surface.width * .10f, surface.dialCenterY)

        instrumentation.runOnMainSync { harness.interactionKey.get().value = "timer-b" }
        instrumentation.waitForIdleSync()
        sendUp(downTime, surface.centerX - surface.width * .18f, surface.dialCenterY)

        assertStaysZero("replaced interaction") { harness.commitAttempts.get() }
    }

    @Test
    fun dominantVerticalDragScrollsTheParentWithoutChangingTime() {
        val harness = renderDial(scrollable = true)
        val surface = awaitSurface(harness)

        drag(
            surface = surface,
            fromX = surface.centerX,
            fromY = surface.dialCenterY + surface.dialHeight * .18f,
            toX = surface.centerX,
            toY = surface.dialCenterY - surface.dialHeight * .32f,
            moveCount = 7,
        )

        waitUntil("parent vertical scroll") { harness.scrollPosition.get() > 0 }
        assertEquals(0, harness.commitAttempts.get())
        assertEquals(0, harness.ticks.get())
    }

    @Test
    fun disabledDialIgnoresPointerInput() {
        val harness = renderDial(enabled = false)
        val surface = awaitSurface(harness)

        drag(
            surface = surface,
            fromX = surface.centerX + surface.width * .22f,
            fromY = surface.dialCenterY,
            toX = surface.centerX - surface.width * .22f,
            toY = surface.dialCenterY,
            moveCount = 6,
        )

        assertStaysZero("disabled dial") { harness.commitAttempts.get() }
        assertEquals(0, harness.ticks.get())
        assertEquals(25, harness.authoritativeMinutes.get().value)
    }

    private fun renderDial(enabled: Boolean = true, scrollable: Boolean = false): DialHarness {
        val harness = DialHarness()
        val launched = ActivityScenario.launch(MainActivity::class.java).also { scenario = it }
        launched.onActivity { activity ->
            activity.setContent {
                WorkbenchTheme {
                    val authoritativeMinutes = remember {
                        mutableIntStateOf(25).also(harness.authoritativeMinutes::set)
                    }
                    val interactionKey = remember {
                        mutableStateOf("timer-a").also(harness.interactionKey::set)
                    }
                    val scrollState = rememberScrollState().also { harness.scrollPosition.set(it.value) }
                    val content: @androidx.compose.runtime.Composable () -> Unit = {
                        ClockTimeDial(
                            valueMinutes = authoritativeMinutes.intValue,
                            minimumMinutes = 1,
                            maximumMinutes = 240,
                            enabled = enabled,
                            interactionKey = interactionKey.value,
                            busy = false,
                            feedbackMessage = null,
                            onCommit = { minutes, key ->
                                harness.commitAttempts.incrementAndGet()
                                if (key != interactionKey.value) {
                                    false
                                } else {
                                    harness.committedMinutes.set(minutes)
                                    harness.acceptedCommits.incrementAndGet()
                                    authoritativeMinutes.intValue = minutes
                                    true
                                }
                            },
                            height = DIAL_HEIGHT_DP.dp,
                            modifier = Modifier.fillMaxWidth(),
                            onTick = { harness.ticks.incrementAndGet() },
                        ) { previewMinutes, faceHeight ->
                            val minutes = previewMinutes ?: authoritativeMinutes.intValue
                            TimerClockVisual(
                                style = TimerClockStyle.ORBIT,
                                reading = countdownClockReading(minutes * 60, minutes * 60),
                                running = false,
                                height = faceHeight,
                                sizingReferenceSeconds = 240 * 60,
                                readoutLabel = "전체 목표",
                            )
                        }
                    }
                    if (scrollable) {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(scrollState),
                        ) {
                            content()
                            Spacer(Modifier.height(1_200.dp))
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) { content() }
                    }
                    harness.scrollPosition.set(scrollState.value)
                }
            }
        }
        instrumentation.waitForIdleSync()
        return harness
    }

    private fun awaitSurface(harness: DialHarness): DialSurface {
        val launched = checkNotNull(scenario)
        val reference = AtomicReference<DialSurface?>()
        waitUntil("dial layout") {
            launched.onActivity { activity ->
                val content = activity.findViewById<View>(android.R.id.content)
                if (content.width > 0 && content.height > 0) {
                    val origin = IntArray(2)
                    content.getLocationOnScreen(origin)
                    val density = activity.resources.displayMetrics.density
                    val dialHeight = DIAL_HEIGHT_DP * density
                    reference.set(
                        DialSurface(
                            left = origin[0].toFloat(),
                            top = origin[1].toFloat(),
                            width = content.width.toFloat(),
                            dialHeight = dialHeight,
                        ),
                    )
                }
            }
            harness.authoritativeMinutes.get() != null && reference.get() != null
        }
        return checkNotNull(reference.get())
    }

    private fun drag(
        surface: DialSurface,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        moveCount: Int,
        finishAction: Int = MotionEvent.ACTION_UP,
    ) {
        val downTime = sendDown(fromX, fromY)
        repeat(moveCount) { index ->
            val fraction = (index + 1f) / moveCount
            sendMove(
                downTime,
                fromX + (toX - fromX) * fraction,
                fromY + (toY - fromY) * fraction,
            )
        }
        sendPointer(finishAction, downTime, toX, toY)
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

    private class DialHarness {
        val authoritativeMinutes = AtomicReference<MutableState<Int>>()
        val interactionKey = AtomicReference<MutableState<String>>()
        val committedMinutes = AtomicInteger(25)
        val commitAttempts = AtomicInteger()
        val acceptedCommits = AtomicInteger()
        val ticks = AtomicInteger()
        val scrollPosition = AtomicInteger()
    }

    private data class DialSurface(
        val left: Float,
        val top: Float,
        val width: Float,
        val dialHeight: Float,
    ) {
        val centerX get() = left + width / 2f
        val dialCenterY get() = top + dialHeight / 2f
    }

    private companion object {
        const val DIAL_HEIGHT_DP = 320f
    }
}
