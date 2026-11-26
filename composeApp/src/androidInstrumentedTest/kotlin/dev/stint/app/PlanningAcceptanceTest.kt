package dev.stint.app

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stint.app.client.StintController
import dev.stint.shared.ConnectionSettings
import dev.stint.shared.ConnectionStatus
import dev.stint.shared.HourPlan
import dev.stint.shared.LocalStore
import dev.stint.shared.PlanCycle
import dev.stint.shared.Snapshot
import dev.stint.shared.TimerClockStyle
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Production-Activity acceptance against the disposable loopback planning server only. */
@RunWith(AndroidJUnit4::class)
class PlanningAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun requireExplicitIsolatedLoopbackOptIn() {
        assumeTrue(
            "Planning acceptance is opt-in because it writes disposable planning data",
            argument(OPT_IN_ARGUMENT) == "true",
        )
        instrumentation.uiAutomation.executeShellCommand("cmd power wakeup").close()
        consumeIsolatedAcceptanceConfiguration()
        val settings = AndroidLocalStore(context).loadSettings()
        assertEquals("Planning acceptance must never target production", ISOLATED_BASE_URL, settings.baseUrl)
        assertTrue("The isolated test connection must have a token", settings.token.isNotBlank())
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
    }

    @After
    fun closeProductionActivity() {
        scenario?.close()
        scenario = null
    }

    @Test
    fun planningTabRendersTheSelectedDayAndBlankHourEditor() {
        val launched = launchConnectedProductionActivity()
        val controller = viewModel(launched).controller

        clickExact("계획")
        val day = awaitLoadedDay(controller)
        val blankHour = firstBlankHour(day.hours.map(HourPlan::hour).toSet())

        assertOwnText("기본 사이클")
        assertHourButton(blankHour, "빈 시간")
        writePrivateWindowScreenshot(launched, "planning-day")

        openBlankHour(blankHour)
        assertOwnAnyText("시간 제목 입력", "시간 제목")
        assertOwnText("공부 15분 · 휴식 5분")
        assertOwnText("공부 25분 · 휴식 5분")
        clickExact("공부 15분 · 휴식 5분")
        assertOwnText("한 시간 계획, ${clock(blankHour, 0)}–${clock(blankHour + 1, 0)}, 빈 시간")

        clickExact("공부 15분")
        assertOwnText("한 시간 계획, ${clock(blankHour, 0)}–${clock(blankHour, 15)}, 15분")
        setEditableValue(description = null, hint = "시간", existingText = "15", value = "12")
        writePrivateWindowScreenshot(launched, "planning-phase")
        clickExact("적용")
        assertOwnText("공부 12분")
        assertOwnText("휴식 8분")
        writePrivateWindowScreenshot(launched, "planning-hour")
    }

    @Test
    fun externalRevisionKeepsItsTitleAcrossTabNavigationAndActivityRecreation() {
        val launched = launchConnectedProductionActivity()
        val controller = viewModel(launched).controller
        var date: String? = null
        var hour: Int? = null
        var external: StintController? = null

        try {
            clickExact("계획")
            val initialDay = awaitLoadedDay(controller)
            val selectedDate = initialDay.date
            val selectedHour = firstBlankHour(initialDay.hours.map(HourPlan::hour).toSet())
            date = selectedDate
            hour = selectedHour
            openBlankHour(selectedHour)

            setTitle(UI_TITLE)
            clickExact("공부 25분 · 휴식 5분")
            clickExact("저장")
            val savedDay = awaitPlan(controller, selectedDate, "UI hour save") { day ->
                day.revision > initialDay.revision && day.hours.any { plan ->
                    plan.hour == selectedHour && plan.title == UI_TITLE &&
                        plan.cycle == PlanCycle.TWENTY_FIVE_FIVE
                }
            }
            clickExact("‹ 일정 목록")
            assertHourButton(selectedHour, UI_TITLE)

            val externalController = externalController(controller.state.value.settings)
            external = externalController
            val externalConnected = runBlocking {
                withTimeout(20_000L) {
                    externalController.state.first { state ->
                        state.connection == ConnectionStatus.CONNECTED && state.snapshot != null
                    }
                }
            }
            assertEquals(ISOLATED_BASE_URL, externalConnected.settings.baseUrl)
            externalController.selectPlanDate(selectedDate)
            val externalDay = awaitPlan(externalController, selectedDate, "external device day load") { true }
            assertTrue("External device must observe the UI save before replacing it",
                externalDay.revision >= savedDay.revision)

            externalController.saveHourPlan(
                selectedDate,
                selectedHour,
                HourPlan(selectedHour, EXTERNAL_TITLE, PlanCycle.TWENTY_FIVE_FIVE),
                externalDay.revision,
            )
            val externallySaved = awaitPlan(externalController, selectedDate, "external device hour save") { day ->
                day.revision > externalDay.revision &&
                    day.hours.any { it.hour == selectedHour && it.title == EXTERNAL_TITLE }
            }
            awaitPlan(controller, selectedDate, "primary device external revision delivery") { day ->
                day.revision >= externallySaved.revision &&
                    day.hours.any { it.hour == selectedHour && it.title == EXTERNAL_TITLE }
            }
            assertHourButton(selectedHour, EXTERNAL_TITLE)

            clickExact("집중")
            clickExact("계획")
            assertHourButton(selectedHour, EXTERNAL_TITLE)

            launched.recreate()
            launched.moveToState(Lifecycle.State.RESUMED)
            awaitOwnActivityWindow(launched)
            assertHourButton(selectedHour, EXTERNAL_TITLE)
            writePrivateWindowScreenshot(launched, "planning-external-revision")
        } finally {
            external?.close()
            date?.let { selectedDate ->
                hour?.let { selectedHour -> cleanupHour(controller, selectedDate, selectedHour) }
            }
        }
    }

    private fun launchConnectedProductionActivity(): ActivityScenario<MainActivity> {
        val launched = ActivityScenario.launch(MainActivity::class.java).also { scenario = it }
        launched.moveToState(Lifecycle.State.RESUMED)
        awaitOwnActivityWindow(launched)
        val controller = viewModel(launched).controller
        assertEquals("The production controller must be isolated before UI interaction",
            ISOLATED_BASE_URL, controller.state.value.settings.baseUrl)
        runBlocking {
            withTimeout(20_000L) {
                controller.state.first { state ->
                    state.settings.baseUrl == ISOLATED_BASE_URL &&
                        state.connection == ConnectionStatus.CONNECTED && state.snapshot != null
                }
            }
        }
        return launched
    }

    private fun viewModel(launched: ActivityScenario<MainActivity>): StintViewModel {
        val reference = AtomicReference<StintViewModel>()
        launched.onActivity { activity ->
            reference.set(ViewModelProvider(activity)[StintViewModel::class.java])
        }
        return checkNotNull(reference.get())
    }

    private fun awaitLoadedDay(controller: dev.stint.shared.ClientController) = runBlocking {
        try {
            withTimeout(20_000L) {
                controller.state.first { state ->
                    state.settings.baseUrl == ISOLATED_BASE_URL &&
                        state.planning.date != null && state.planning.day?.date == state.planning.date &&
                        !state.planning.loading && state.planning.error == null
                }.planning.day!!
            }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError(
                "Timed out waiting for selected planning day; ${planningDiagnostics(controller)}; ${visibleLabels()}",
                timeout,
            )
        }
    }

    private fun awaitPlan(
        controller: dev.stint.shared.ClientController,
        date: String,
        stage: String,
        predicate: (dev.stint.shared.DayPlan) -> Boolean,
    ) = runBlocking {
        try {
            withTimeout(20_000L) {
                controller.state.first { state ->
                    state.settings.baseUrl == ISOLATED_BASE_URL && state.planning.date == date &&
                        !state.planning.loading && !state.planning.saving && state.planning.error == null &&
                        state.planning.day?.let(predicate) == true
                }.planning.day!!
            }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError(
                "Timed out waiting for $stage; ${planningDiagnostics(controller)}; ${visibleLabels()}; " +
                    editableDiagnostics(),
                timeout,
            )
        }
    }

    private fun externalController(settings: ConnectionSettings): StintController {
        assertEquals(ISOLATED_BASE_URL, settings.baseUrl)
        assertTrue(settings.token.isNotBlank())
        val externalSettings = settings.copy(
            deviceId = "planning-external-${UUID.randomUUID()}",
            deviceName = "Planning external revision fixture",
        )
        return StintController(
            client = androidHttpClient(),
            localStore = MemoryStore(externalSettings),
            clock = System::currentTimeMillis,
            newId = { UUID.randomUUID().toString() },
            allowInsecureLoopback = true,
        )
    }

    private fun cleanupHour(controller: dev.stint.shared.ClientController, date: String, hour: Int) {
        controller.selectPlanDate(date)
        val current = awaitPlan(controller, date, "cleanup day load") { true }
        if (current.hours.none { it.hour == hour }) return
        controller.saveHourPlan(date, hour, null, current.revision)
        awaitPlan(controller, date, "cleanup hour deletion") { day ->
            day.revision > current.revision && day.hours.none { it.hour == hour }
        }
    }

    private fun firstBlankHour(occupied: Set<Int>): Int =
        ((6..22) + (0..5) + 23).firstOrNull { it !in occupied }
            ?: throw AssertionError("The isolated planning day must contain at least one blank hour")

    private fun openBlankHour(hour: Int) {
        if (hour >= 12) clickExact("오후")
        clickExact(hourDescription(hour, "빈 시간"))
    }

    private fun assertHourButton(hour: Int, title: String) {
        if (hour >= 12) clickExact("오후")
        val expected = hourDescription(hour, title)
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        var scrollAttempts = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val target = ownAccessibilityTree().firstOrNull { node ->
                node.contentDescription?.toString() == expected
            }
            if (target?.isVisibleToUser == true) return
            if (scrollAttempts < MAX_SCROLL_ATTEMPTS && scrollPlanningDetailsForward(target)) {
                scrollAttempts++
                instrumentation.waitForIdleSync()
                SystemClock.sleep(250L)
            } else {
                SystemClock.sleep(100L)
            }
        }
        throw AssertionError("Timed out waiting for hour $expected; ${visibleLabels()}")
    }

    private fun hourDescription(hour: Int, title: String): String =
        "${hour.toString().padStart(2, '0')}:00–${(hour + 1).toString().padStart(2, '0')}:00 $title"

    private fun setTitle(value: String) {
        setEditableValue(description = "시간 제목 입력", hint = "시간 제목", value = value)
        assertEditableTitle(value)
    }

    private fun setEditableValue(
        description: String?,
        hint: String,
        value: String,
        existingText: String? = null,
    ) {
        val field = awaitEditable(description, hint, existingText)
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        assertTrue("The planning field '$hint' must accept native accessibility text entry",
            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
        instrumentation.waitForIdleSync()
        waitUntil("planning field '$hint' value '$value'") {
            editableNodes().count { node -> node.text?.toString().orEmpty() == value } == 1
        }
    }

    private fun assertEditableTitle(value: String) {
        if (value.isEmpty()) {
            // Compose exposes the label/placeholder as accessibility text even when the
            // backing TextFieldValue is empty. Presence of the specifically described,
            // editable title node is the stable native assertion for a blank draft.
            awaitEditableTitle()
            return
        }
        waitUntil("planning title '$value'") {
            editableNodes().count { node -> node.text?.toString().orEmpty() == value } == 1
        }
    }

    private fun awaitEditableTitle(): AccessibilityNodeInfo {
        return awaitEditable(description = "시간 제목 입력", hint = "시간 제목", existingText = null)
    }

    private fun awaitEditable(
        description: String?,
        hint: String,
        existingText: String?,
    ): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        val settleBeforeScroll = SystemClock.elapsedRealtime() + UI_SETTLE_BEFORE_SCROLL_MILLIS
        var scrollAttempts = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val candidates = editableNodes()
            candidates.firstOrNull { node -> matchesEditableLabel(node, description, hint) }?.let { return it }
            if (existingText != null) {
                candidates.singleOrNull { node -> node.text?.toString().orEmpty() == existingText }
                    ?.let { return it }
            } else {
                candidates.singleOrNull()?.let { return it }
            }
            if (SystemClock.elapsedRealtime() >= settleBeforeScroll &&
                scrollAttempts < MAX_SCROLL_ATTEMPTS && scrollPlanningDetailsForward()
            ) {
                scrollAttempts++
                instrumentation.waitForIdleSync()
                SystemClock.sleep(250L)
            } else {
                SystemClock.sleep(100L)
            }
        }
        throw AssertionError(
            "The planning field '$hint' was not exposed unambiguously; ${editableDiagnostics()}",
        )
    }

    private fun matchesEditableLabel(
        node: AccessibilityNodeInfo,
        description: String?,
        hint: String,
    ): Boolean = (description != null && node.contentDescription?.toString() == description) ||
        node.hintText?.toString() == hint

    private fun editableNodes(): List<AccessibilityNodeInfo> = ownAccessibilityTree().filter { node ->
        node.isEditable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
    }

    private fun editableDiagnostics(): String = editableNodes().joinToString(
        prefix = "editableNodes=[",
        postfix = "]",
    ) { node ->
        "{editable=${node.isEditable},enabled=${node.isEnabled},text=${node.text?.toString().orEmpty().take(40)}," +
            "hint=${node.hintText?.toString().orEmpty().take(40)}," +
            "description=${node.contentDescription?.toString().orEmpty().take(60)}," +
            "actions=${node.actionList.joinToString { it.id.toString() }}}"
    }

    private fun clock(hour: Int, minute: Int): String =
        "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

    private fun clickExact(label: String) {
        val deadline = SystemClock.elapsedRealtime() + 12_000L
        val settleBeforeScroll = SystemClock.elapsedRealtime() + UI_SETTLE_BEFORE_SCROLL_MILLIS
        var scrollAttempts = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val target = ownAccessibilityTree().firstOrNull { node ->
                node.text?.toString() == label || node.contentDescription?.toString() == label
            }
            val clickable = generateSequence(target) { it.parent }.firstOrNull { node ->
                node.isEnabled && (node.isClickable || node.actionList.any {
                    it.id == AccessibilityNodeInfo.ACTION_CLICK
                })
            }
            if (clickable?.isVisibleToUser == true &&
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                instrumentation.waitForIdleSync()
                return
            }
            if (SystemClock.elapsedRealtime() >= settleBeforeScroll &&
                scrollAttempts < MAX_SCROLL_ATTEMPTS && scrollPlanningDetailsForward(target)
            ) {
                scrollAttempts++
                instrumentation.waitForIdleSync()
                SystemClock.sleep(250L)
            } else {
                SystemClock.sleep(100L)
            }
        }
        throw AssertionError("Could not click isolated planning control: $label; ${visibleLabels()}")
    }

    /** Scroll only the app-owned plan details pane, never the clock/fold region or system UI. */
    private fun scrollPlanningDetailsForward(anchor: AccessibilityNodeInfo? = null): Boolean {
        var ancestor = anchor
        while (ancestor != null) {
            if (ancestor.isScrollable && ancestor.packageName?.toString() == context.packageName &&
                ancestor.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            ) return true
            ancestor = ancestor.parent
        }
        val scrollables = ownAccessibilityTree().filter { node ->
            node.packageName?.toString() == context.packageName && node.isScrollable && node.isVisibleToUser &&
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }
        }
        // Book posture has a scrollable details pane to the right of the non-scrollable dial.
        // Choosing the rightmost eligible app node avoids moving the wrong fold region.
        val details = scrollables.maxByOrNull { node ->
            android.graphics.Rect().also(node::getBoundsInScreen).left
        } ?: return false
        return details.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    private fun assertOwnText(text: String) {
        waitUntil("Stint planning text '$text'") {
            ownAccessibilityTree().any { node ->
                node.text?.toString() == text || node.contentDescription?.toString() == text
            }
        }
    }

    private fun assertOwnAnyText(vararg values: String) {
        waitUntil("one of Stint planning labels ${values.toList()}") {
            ownAccessibilityTree().any { node ->
                node.text?.toString() in values || node.contentDescription?.toString() in values
            }
        }
    }

    private fun planningDiagnostics(controller: dev.stint.shared.ClientController): String {
        val state = controller.state.value
        val day = state.planning.day
        val hours = day?.hours?.joinToString { plan -> "${plan.hour}:${plan.title.take(24)}" }
        return "planning={connection=${state.connection},date=${state.planning.date}," +
            "dayDate=${day?.date},revision=${day?.revision},loading=${state.planning.loading}," +
            "saving=${state.planning.saving},error=${state.planning.error}," +
            "hours=$hours}"
    }

    private fun awaitOwnActivityWindow(launched: ActivityScenario<MainActivity>) {
        waitUntil("focused Stint planning window", 12_000L) {
            var ready = false
            launched.onActivity { activity ->
                val decor = activity.window.decorView
                ready = decor.isAttachedToWindow && decor.hasWindowFocus() &&
                    decor.width > 0 && decor.height > 0
            }
            ready && instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == context.packageName
        }
    }

    private fun ownAccessibilityTree(): List<AccessibilityNodeInfo> {
        val root = instrumentation.uiAutomation.rootInActiveWindow
        if (root?.packageName?.toString() != context.packageName) return emptyList()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        val result = ArrayList<AccessibilityNodeInfo>()
        pending.add(root)
        while (pending.isNotEmpty() && result.size < MAX_ACCESSIBILITY_NODES) {
            val node = pending.removeFirst()
            result += node
            repeat(node.childCount) { index -> node.getChild(index)?.let(pending::addLast) }
        }
        return result
    }

    private fun waitUntil(label: String, timeoutMillis: Long = 10_000L, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100L)
        }
        throw AssertionError("Timed out waiting for $label; ${visibleLabels()}; ${editableDiagnostics()}")
    }

    private fun visibleLabels(): String = ownAccessibilityTree().asSequence()
        .filter { it.isVisibleToUser }
        .mapNotNull { node -> node.contentDescription?.toString() ?: node.text?.toString() }
        .filter(String::isNotBlank)
        .take(40)
        .joinToString(prefix = "visible=[", postfix = "]") { it.take(80) }

    private fun writePrivateWindowScreenshot(
        launched: ActivityScenario<MainActivity>,
        name: String,
    ) {
        val completed = kotlinx.coroutines.CompletableDeferred<Bitmap>()
        launched.onActivity { activity ->
            val view = activity.window.decorView
            require(view.width > 0 && view.height > 0)
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
        assertFalse("Planning screenshot must not be empty", bitmap.width == 0 || bitmap.height == 0)
        val folder = File(context.filesDir, "screenshots").apply { mkdirs() }
        FileOutputStream(File(folder, "$name.png")).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.fd.sync()
        }
        bitmap.recycle()
    }

    private fun consumeIsolatedAcceptanceConfiguration() {
        val file = File(context.filesDir, "acceptance-config.json")
        if (!file.isFile) return
        try {
            val value = JSONObject(file.readText())
            val url = value.getString("url").trim().trimEnd('/')
            val token = value.getString("token").trim()
            require(url == ISOLATED_BASE_URL && token.isNotBlank()) {
                "Planning acceptance configuration must target the isolated loopback server"
            }
            val existing = AndroidLocalStore(context).loadSettings()
            AndroidLocalStore(context).saveSettings(
                ConnectionSettings(
                    baseUrl = url,
                    token = token,
                    deviceId = existing.deviceId.ifBlank { "planning-acceptance-emulator" },
                    deviceName = "Planning acceptance emulator",
                ),
            )
        } finally {
            check(file.delete()) { "Planning acceptance configuration could not be removed" }
        }
    }

    private fun argument(name: String): String =
        InstrumentationRegistry.getArguments().getString(name).orEmpty()

    private class MemoryStore(initial: ConnectionSettings) : LocalStore {
        private var settings = initial
        private var snapshot: Snapshot? = null
        private var style = TimerClockStyle.ORBIT

        override fun loadSettings(): ConnectionSettings = settings
        override fun saveSettings(settings: ConnectionSettings) { this.settings = settings }
        override fun loadSnapshot(): Snapshot? = snapshot
        override fun saveSnapshot(snapshot: Snapshot) { this.snapshot = snapshot }
        override fun loadClockStyle(): TimerClockStyle = style
        override fun saveClockStyle(style: TimerClockStyle) { this.style = style }
    }

    private companion object {
        const val ISOLATED_BASE_URL = "http://10.0.2.2:18787"
        const val OPT_IN_ARGUMENT = "runPlanningAcceptance"
        const val UI_TITLE = "안드로이드 계획 계측"
        const val EXTERNAL_TITLE = "외부 동기화 계획"
        const val MAX_ACCESSIBILITY_NODES = 4_096
        const val MAX_SCROLL_ATTEMPTS = 8
        const val UI_SETTLE_BEFORE_SCROLL_MILLIS = 750L
    }
}
