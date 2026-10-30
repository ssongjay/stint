package dev.stint.app

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stint.app.client.TimerCompletionTracker
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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs real short AlarmManager deadlines without reading the synchronized timer. */
@RunWith(AndroidJUnit4::class)
class CompletionCountdownDeliveryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Test
    fun focusAndBreakExpireThroughSystemAlarmAndPostOneCompletionEach() {
        assumeTrue(
            "This test uses the existing notification permission and never changes it",
            Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
        assertTrue("App notifications must already be enabled", notifications.areNotificationsEnabled())
        if (Build.VERSION.SDK_INT >= 31) {
            assertTrue("An exact-alarm capable timer install is required", alarms.canScheduleExactAlarms())
        }
        ensureCompletionChannel(notifications)
        File(context.filesDir, REPORT_FILE).delete()
        verifyCountdown(TimerPhase.FOCUS)
        verifyCountdown(TimerPhase.SHORT_BREAK)
    }

    private fun verifyCountdown(phase: TimerPhase) {
        val serverNow = 1_000_000L
        val baseElapsed = SystemClock.elapsedRealtime()
        val timer = TimerState(
            id = "completion-countdown-qa", subjectId = "qa-subject",
            status = TimerStatus.RUNNING, phase = phase, mode = TimerMode.COUNTDOWN,
            targetSeconds = COUNTDOWN_SECONDS, runningSinceEpochMs = serverNow,
            startedAtEpochMs = serverNow,
        )
        val baseline = state(serverNow, timer)
        val arm = requireNotNull(completionAlarmArm(
            baseline, "completion-countdown-qa", baseElapsed, System.currentTimeMillis(),
        ))
        val tracker = TimerCompletionTracker().also { assertEquals(null, it.update(baseline)) }
        val action = "${context.packageName}.qa.${phase.name}.${UUID.randomUUID()}"
        val callbacks = AtomicInteger()
        val callbackElapsed = AtomicLong(-1L)
        val deliveryElapsed = AtomicLong(-1L)
        val failure = AtomicReference<Throwable?>()
        val completed = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiveContext: Context, intent: Intent) {
                callbacks.incrementAndGet()
                callbackElapsed.set(SystemClock.elapsedRealtime() - baseElapsed)
                fun deliverAtDeadline() {
                    try {
                        val elapsed = SystemClock.elapsedRealtime() - baseElapsed
                        val expired = state(serverNow + elapsed, timer)
                        val event = requireNotNull(tracker.update(expired))
                        deliveryElapsed.set(elapsed)
                        notifications.notify(QA_NOTIFICATION_ID, buildCompletionNotification(context, event))
                        assertEquals(null, tracker.update(expired.copy(
                            serverNowEpochMs = expired.serverNowEpochMs + 1,
                        )))
                    } catch (error: Throwable) {
                        failure.set(error)
                    } finally {
                        completed.countDown()
                    }
                }
                val remaining = COUNTDOWN_MILLIS - callbackElapsed.get()
                if (remaining > 0L) Handler(Looper.getMainLooper()).postDelayed(
                    ::deliverAtDeadline, remaining + 5L,
                ) else deliverAtDeadline()
            }
        }
        register(receiver, action)
        val operation = PendingIntent.getBroadcast(
            context, ALARM_REQUEST_CODE + phase.ordinal,
            Intent(action).setPackage(context.packageName)
                .setData(Uri.parse("stint://completion-qa/${phase.name.lowercase()}")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val showIntent = PendingIntent.getActivity(
            context, SHOW_REQUEST_CODE + phase.ordinal,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(arm.triggerWallClockEpochMs, showIntent), operation)
            assertTrue("The system alarm did not deliver", completed.await(COUNTDOWN_SECONDS + 5L, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Completion delivery failed", it) }
            assertTrue("Alarm fired too early at ${callbackElapsed.get()}ms", callbackElapsed.get() >= COUNTDOWN_MILLIS - 100L)
            assertTrue(deliveryElapsed.get() >= COUNTDOWN_MILLIS)
            assertEquals(1, callbacks.get())

            val posted = awaitPostedNotification()
            val title = if (phase == TimerPhase.FOCUS) "집중을 마쳤어요" else "휴식이 끝났어요"
            assertEquals(title, posted.extras.getString(Notification.EXTRA_TITLE))
            assertEquals(Notification.CATEGORY_ALARM, posted.category)
            assertEquals(COMPLETION_CHANNEL_ID, posted.channelId)
            writeReport(phase, title, callbackElapsed.get(), deliveryElapsed.get())
            SystemClock.sleep(VISIBLE_MILLIS)
            assertEquals("The countdown must not deliver a second callback", 1, callbacks.get())
        } finally {
            alarms.cancel(operation)
            operation.cancel()
            context.unregisterReceiver(receiver)
            notifications.cancel(QA_NOTIFICATION_ID)
        }
    }

    private fun register(receiver: BroadcastReceiver, action: String) {
        val filter = IntentFilter(action).apply { addDataScheme("stint") }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
    }

    private fun awaitPostedNotification(): Notification {
        val deadline = SystemClock.elapsedRealtime() + 2_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            notifications.activeNotifications.firstOrNull { it.id == QA_NOTIFICATION_ID }
                ?.let { return it.notification }
            SystemClock.sleep(50L)
        }
        throw AssertionError("Completion notification was not posted")
    }

    private fun state(now: Long, timer: TimerState) = ClientState(
        settings = ConnectionSettings("https://stint.example", "qa-token", "qa-device", "알림 확인"),
        snapshot = Snapshot(now, 1, listOf(Subject("qa-subject", "알림 확인")), timer),
        serverNowEpochMs = now, connection = ConnectionStatus.CONNECTED,
    )

    private fun writeReport(phase: TimerPhase, title: String, callbackMs: Long, deliveryMs: Long) {
        val file = File(context.filesDir, REPORT_FILE)
        val report = if (file.isFile) runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
            else JSONObject()
        report.put(phase.name, JSONObject()
            .put("phase", phase.name).put("callbackElapsedMs", callbackMs)
            .put("deliveryElapsedMs", deliveryMs).put("callbackCount", 1)
            .put("titlePosted", title).put("categoryAlarm", true)
            .put("channelId", COMPLETION_CHANNEL_ID))
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        report.put("notificationVolume", audio.getStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION))
            .put("ringerMode", audio.ringerMode)
            .put("interruptionFilter", notifications.currentInterruptionFilter)
        file.writeText(report.toString(2))
    }

    private companion object {
        const val COUNTDOWN_SECONDS = 6
        const val COUNTDOWN_MILLIS = COUNTDOWN_SECONDS * 1_000L
        const val VISIBLE_MILLIS = 2_000L
        const val QA_NOTIFICATION_ID = 1296
        const val ALARM_REQUEST_CODE = 12960
        const val SHOW_REQUEST_CODE = 12970
        const val REPORT_FILE = "completion-countdown-qa.json"
    }
}
