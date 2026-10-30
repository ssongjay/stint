package dev.stint.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stint.app.client.TimerCompletionEvent
import dev.stint.shared.TimerPhase
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated system-channel QA. It never reads or mutates the production timer or connection. */
@RunWith(AndroidJUnit4::class)
class CompletionNotificationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @After
    fun removeOnlySyntheticCompletionNotification() {
        manager.cancel(QA_NOTIFICATION_ID)
    }

    @Test
    fun focusAndBreakBuildDistinctAudibleAlarmNotifications() {
        ensureCompletionChannel(manager)
        TimerNotification(context)

        val focus = buildCompletionNotification(context, completionEvent(TimerPhase.FOCUS))
        val shortBreak = buildCompletionNotification(context, completionEvent(TimerPhase.SHORT_BREAK))
        val completionChannel = requireNotNull(manager.getNotificationChannel(COMPLETION_CHANNEL_ID))
        val ongoingChannel = requireNotNull(manager.getNotificationChannel(TimerNotification.CHANNEL_ID))

        assertEquals("집중을 마쳤어요", focus.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("휴식이 끝났어요", shortBreak.extras.getString(Notification.EXTRA_TITLE))
        assertNotEquals(
            focus.extras.getCharSequence(Notification.EXTRA_TEXT),
            shortBreak.extras.getCharSequence(Notification.EXTRA_TEXT),
        )
        listOf(focus, shortBreak).forEach { notification ->
            assertEquals(Notification.CATEGORY_ALARM, notification.category)
            assertEquals(COMPLETION_CHANNEL_ID, notification.channelId)
            assertFalse(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        }
        assertEquals(NotificationManager.IMPORTANCE_HIGH, completionChannel.importance)
        assertNotNull(completionChannel.sound)
        assertTrue(completionChannel.shouldVibrate())
        assertEquals(NotificationManager.IMPORTANCE_LOW, ongoingChannel.importance)
        assertFalse(ongoingChannel.shouldVibrate())
    }

    @Test
    fun physicalSystemPostsOneSyntheticCompletionAndReportsItsChannel() {
        assumeTrue(
            "This test uses the existing notification permission and never grants it",
            Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
        assumeTrue("App notifications must already be enabled", manager.areNotificationsEnabled())
        ensureCompletionChannel(manager)
        val channel = requireNotNull(manager.getNotificationChannel(COMPLETION_CHANNEL_ID))
        assumeTrue("The completion channel must be enabled", channel.importance != NotificationManager.IMPORTANCE_NONE)

        val notification = buildCompletionNotification(context, completionEvent(TimerPhase.FOCUS))
        try {
            manager.notify(QA_NOTIFICATION_ID, notification)
            val posted = awaitPostedNotification()

            assertEquals(COMPLETION_CHANNEL_ID, posted.channelId)
            assertEquals("집중을 마쳤어요", posted.extras.getString(Notification.EXTRA_TITLE))
            assertEquals(Notification.CATEGORY_ALARM, posted.category)
            writePrivateReport(channel)
        } finally {
            manager.cancel(QA_NOTIFICATION_ID)
        }
    }

    private fun awaitPostedNotification(): Notification {
        val deadline = SystemClock.elapsedRealtime() + POST_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            manager.activeNotifications.firstOrNull { it.id == QA_NOTIFICATION_ID }
                ?.let { return it.notification }
            SystemClock.sleep(50L)
        }
        throw AssertionError("Synthetic completion notification was not posted within ${POST_TIMEOUT_MS}ms")
    }

    private fun writePrivateReport(channel: android.app.NotificationChannel) {
        val report = JSONObject()
            .put("sdk", Build.VERSION.SDK_INT)
            .put("importance", channel.importance)
            .put("soundConfigured", channel.sound != null)
            .put("vibrationEnabled", channel.shouldVibrate())
            .put("nativePosted", true)
        File(context.filesDir, REPORT_FILE).writeText(report.toString(2))
    }

    private fun completionEvent(phase: TimerPhase): TimerCompletionEvent = TimerCompletionEvent(
        key = "completion-notification-qa-${phase.name}",
        phase = phase,
        subjectName = "집중하기",
        title = if (phase == TimerPhase.FOCUS) "집중을 마쳤어요" else "휴식이 끝났어요",
        body = if (phase == TimerPhase.FOCUS) {
            "집중하기 · 잠시 쉬어가세요."
        } else {
            "집중하기 · 다음 집중을 시작할 준비가 됐어요."
        },
    )

    private companion object {
        const val QA_NOTIFICATION_ID = 1297
        const val POST_TIMEOUT_MS = 3_000L
        const val REPORT_FILE = "completion-notification-qa.json"
    }
}
