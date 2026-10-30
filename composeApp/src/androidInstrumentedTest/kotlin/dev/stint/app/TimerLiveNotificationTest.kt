package dev.stint.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stint.shared.ClientState
import dev.stint.shared.ConnectionSettings
import dev.stint.shared.ConnectionStatus
import dev.stint.shared.Snapshot
import dev.stint.shared.Subject
import dev.stint.shared.TimerMode
import dev.stint.shared.TimerPhase
import dev.stint.shared.TimerState
import dev.stint.shared.TimerStatus
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimerLiveNotificationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val timerNotification = TimerNotification(context)

    @Test
    fun activeSessionServiceSurvivesActivityLeavingForegroundWithoutTimerCommands() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val controller = (context.applicationContext as StintApplication).controller
            val state = runBlocking {
                withTimeout(20_000) { controller.state.first { it.connection == ConnectionStatus.CONNECTED } }
            }
            assumeTrue("Read-only service acceptance needs an existing active session",
                state.snapshot?.timer?.status?.let { it != TimerStatus.IDLE } == true)
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (!TimerStatusService.running && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            assertTrue(TimerStatusService.running)
            scenario.moveToState(Lifecycle.State.CREATED)
            SystemClock.sleep(350)
            assertTrue("Background service must outlive the activity", TimerStatusService.running)
            val active = manager.activeNotifications.first { it.id == TimerNotification.NOTIFICATION_ID }.notification
            assertTrue(active.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
            assertEquals(state.snapshot?.timer?.id, controller.state.value.snapshot?.timer?.id)
        } finally {
            scenario.close()
        }
    }

    @After
    fun removeOnlySyntheticNotification() {
        manager.cancel(QA_NOTIFICATION_ID)
    }

    @Test
    fun builderUsesLocalClockForLiveChronometerAndImmutableGuardedActions() {
        val localNow = 1_700_000_000_000L
        val serverNow = 9_000_000L
        val state = state(
            serverNow = serverNow,
            timer = timer(
                status = TimerStatus.RUNNING,
                mode = TimerMode.COUNTDOWN,
                targetSeconds = 1_500,
                accumulatedMillis = 1_234L,
                runningSinceEpochMs = serverNow - 2_000L,
            ),
        )

        writeIconPreview()
        val built = requireNotNull(timerNotification.build(state, localNow))
        val remainingMillis = 1_500_000L - 3_234L

        assertEquals(localNow + remainingMillis, built.`when`)
        assertTrue(built.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(built.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        assertTrue(built.extras.getBoolean(TimerNotificationPolicy.EXTRA_REQUEST_PROMOTED_ONGOING))
        assertTrue(built.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(R.drawable.ic_stint_notification, built.smallIcon.resId)
        assertNull(built.contentView)
        assertNull(built.bigContentView)
        assertNull(built.headsUpContentView)
        assertNull(built.extras.getString(Notification.EXTRA_TEMPLATE))
        assertEquals(2, built.actions.size)
        if (Build.VERSION.SDK_INT >= 31) {
            built.actions.forEach { action -> assertTrue(action.actionIntent.isImmutable) }
        }
    }

    @Test
    fun pausedAndOfflinePresentHonestNonLiveStates() {
        val paused = state(
            serverNow = 20_000L,
            timer = timer(
                status = TimerStatus.PAUSED,
                targetSeconds = 1_500,
                accumulatedMillis = 61_001L,
            ),
        )
        val pausedNotification = requireNotNull(timerNotification.build(paused, 1_700_000_000_000L))

        assertEquals("일시정지 · 23:59 남음", pausedNotification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertFalse(pausedNotification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertFalse(pausedNotification.extras.getBoolean(TimerNotificationPolicy.EXTRA_REQUEST_PROMOTED_ONGOING))
        assertEquals(2, pausedNotification.actions.size)
        assertEquals(R.drawable.ic_stint_notification_paused, pausedNotification.smallIcon.resId)

        val breakNotification = requireNotNull(
            timerNotification.build(
                state(
                    serverNow = 20_000L,
                    timer = timer(
                        status = TimerStatus.RUNNING,
                        phase = TimerPhase.SHORT_BREAK,
                        targetSeconds = 300,
                        runningSinceEpochMs = 20_000L,
                    ),
                ),
                1_700_000_000_000L,
            ),
        )
        assertEquals(R.drawable.ic_stint_notification_break, breakNotification.smallIcon.resId)

        val offline = paused.copy(connection = ConnectionStatus.OFFLINE)
        val offlineNotification = requireNotNull(timerNotification.build(offline, 1_700_000_000_000L))

        assertEquals(
            "오프라인 · 앱을 열어 연결을 확인해 주세요",
            offlineNotification.extras.getCharSequence(Notification.EXTRA_TEXT),
        )
        assertFalse(offlineNotification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertFalse(offlineNotification.extras.getBoolean(TimerNotificationPolicy.EXTRA_REQUEST_PROMOTED_ONGOING))
        assertTrue(offlineNotification.actions.isNullOrEmpty())
    }

    @Test
    fun physicalSystemReportsPromotionDecisionForSyntheticNotification() {
        File(context.filesDir, REPORT_FILE).delete()
        assumeTrue(
            "Notification permission is required; this test never grants or changes it",
            Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
        assumeTrue("App notifications must already be enabled", manager.areNotificationsEnabled())
        val channel = requireNotNull(manager.getNotificationChannel(TimerNotification.CHANNEL_ID))
        assumeTrue("The active timer channel must already be enabled", channel.importance != NotificationManager.IMPORTANCE_NONE)

        val notification = requireNotNull(
            timerNotification.build(
                state(
                    serverNow = 500_000L,
                    timer = timer(
                        status = TimerStatus.RUNNING,
                        targetSeconds = 1_500,
                        runningSinceEpochMs = 500_000L,
                    ),
                ),
                System.currentTimeMillis(),
            ),
        )
        try {
            manager.notify(QA_NOTIFICATION_ID, notification)
            val posted = awaitPostedNotification()
            val requested = posted.extras.getBoolean(TimerNotificationPolicy.EXTRA_REQUEST_PROMOTED_ONGOING)
            val promoted = Build.VERSION.SDK_INT >= 36 &&
                posted.flags and Notification.FLAG_PROMOTED_ONGOING != 0
            val canPostPromoted = if (Build.VERSION.SDK_INT >= 36) {
                manager.canPostPromotedNotifications()
            } else {
                false
            }

            assertTrue(requested)
            assertTrue(posted.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertNull(posted.contentView)
            assertNull(posted.bigContentView)
            assertNull(posted.headsUpContentView)
            assertNull(posted.extras.getString(Notification.EXTRA_TEMPLATE))
            if (Build.VERSION.SDK_INT >= 36) assertTrue(posted.hasPromotableCharacteristics())

            writeReport(
                requested = requested,
                flags = posted.flags,
                promoted = promoted,
                channelImportance = channel.importance,
                canPostPromoted = canPostPromoted,
            )
        } finally {
            manager.cancel(QA_NOTIFICATION_ID)
        }
    }

    private fun writeIconPreview() {
        val bitmap = android.graphics.Bitmap.createBitmap(480, 200, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(android.graphics.Color.rgb(245, 240, 230))
        paint.color = android.graphics.Color.rgb(27, 40, 34)
        canvas.drawRect(0f, 100f, 480f, 200f, paint)
        listOf(R.drawable.ic_stint_notification, R.drawable.ic_stint_notification_paused,
            R.drawable.ic_stint_notification_break).forEachIndexed { index, id ->
            listOf(0, 100).forEach { y ->
                val icon = requireNotNull(context.getDrawable(id)).mutate()
                icon.setTint(if (y == 0) android.graphics.Color.rgb(27, 40, 34) else android.graphics.Color.rgb(245, 240, 230))
                icon.setBounds(index * 160 + 40, y + 14, index * 160 + 112, y + 86)
                icon.draw(canvas)
            }
        }
        File(context.filesDir, "chip-icons-preview.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun awaitPostedNotification(): Notification {
        val deadline = SystemClock.elapsedRealtime() + POST_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            manager.activeNotifications.firstOrNull { it.id == QA_NOTIFICATION_ID }?.let { return it.notification }
            SystemClock.sleep(50L)
        }
        throw AssertionError("Synthetic live notification was not posted within ${POST_TIMEOUT_MS}ms")
    }

    private fun writeReport(
        requested: Boolean,
        flags: Int,
        promoted: Boolean,
        channelImportance: Int,
        canPostPromoted: Boolean,
    ) {
        val report = JSONObject()
            .put("sdk", Build.VERSION.SDK_INT)
            .put("deviceModel", Build.MODEL)
            .put("requestedExtra", requested)
            .put("flags", flags)
            .put("promotedFlag", promoted)
            .put("channelImportance", channelImportance)
            .put("canPostPromoted", canPostPromoted)
        File(context.filesDir, REPORT_FILE).writeText(report.toString(2))
    }

    private fun state(serverNow: Long, timer: TimerState): ClientState = ClientState(
        settings = ConnectionSettings(deviceName = "Live notification QA"),
        snapshot = Snapshot(
            serverNowEpochMs = serverNow,
            revision = 42L,
            subjects = listOf(Subject(id = SUBJECT_ID, name = "집중하기")),
            timer = timer,
        ),
        serverNowEpochMs = serverNow,
        connection = ConnectionStatus.CONNECTED,
    )

    private fun timer(
        status: TimerStatus,
        phase: TimerPhase = TimerPhase.FOCUS,
        mode: TimerMode = TimerMode.COUNTDOWN,
        targetSeconds: Int = 1_500,
        accumulatedMillis: Long = 0L,
        runningSinceEpochMs: Long? = null,
    ): TimerState = TimerState(
        id = TIMER_ID,
        subjectId = SUBJECT_ID,
        status = status,
        phase = phase,
        mode = mode,
        targetSeconds = targetSeconds,
        accumulatedMillis = accumulatedMillis,
        runningSinceEpochMs = runningSinceEpochMs,
    )

    private companion object {
        const val QA_NOTIFICATION_ID = 1299
        const val POST_TIMEOUT_MS = 3_000L
        const val REPORT_FILE = "live-notification-qa.json"
        const val TIMER_ID = "live-notification-qa-timer"
        const val SUBJECT_ID = "live-notification-qa-subject"
    }
}
