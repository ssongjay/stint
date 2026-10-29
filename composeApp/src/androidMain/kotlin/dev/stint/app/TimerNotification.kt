package dev.stint.app

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.stint.shared.ClientController
import dev.stint.shared.Snapshot
import dev.stint.shared.TimerMode
import dev.stint.shared.TimerPhase
import dev.stint.shared.TimerStatus
import dev.stint.shared.elapsedSeconds
import dev.stint.shared.remainingSeconds

class TimerNotification(private val activity: Activity) {
    private val manager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "진행 중인 집중", NotificationManager.IMPORTANCE_LOW).apply {
                description = "현재 집중 또는 휴식의 남은 시간을 표시합니다."
                setShowBadge(false)
            },
        )
    }

    fun requestPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 401)
        }
    }

    fun render(snapshot: Snapshot?, nowEpochMs: Long) {
        val timer = snapshot?.timer
        if (timer == null || timer.status == TimerStatus.IDLE) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val subjectName = snapshot.subjects.firstOrNull { it.id == timer.subjectId }?.name ?: "현재 세션"
        val phaseName = when (timer.phase) {
            TimerPhase.FOCUS -> "집중"
            TimerPhase.SHORT_BREAK -> "짧은 휴식"
            TimerPhase.LONG_BREAK -> "긴 휴식"
        }
        val builder = android.app.Notification.Builder(activity, CHANNEL_ID)
            .setSmallIcon(dev.stint.app.R.drawable.ic_stint_notification)
            .setContentTitle("$phaseName · $subjectName")
            .setCategory(android.app.Notification.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setOngoing(timer.status == TimerStatus.RUNNING && timer.mode != TimerMode.STOPWATCH)
            .setShowWhen(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    activity,
                    0,
                    Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )

        if (timer.status == TimerStatus.PAUSED) {
            builder.setContentText("잠시 멈춤 · ${formatDuration(timer.elapsedSeconds(nowEpochMs))}")
                .setShowWhen(false)
        } else if (timer.mode == TimerMode.COUNTDOWN) {
            val remainingMs = timer.remainingSeconds(nowEpochMs).coerceAtLeast(1) * 1_000L
            builder.setWhen(nowEpochMs + remainingMs)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setTimeoutAfter(remainingMs + 1_000L)
        } else {
            val elapsedMs = timer.elapsedSeconds(nowEpochMs) * 1_000L
            builder.setWhen(nowEpochMs - elapsedMs)
                .setUsesChronometer(true)
                .setChronometerCountDown(false)
                .setTimeoutAfter(STOPWATCH_NOTIFICATION_TIMEOUT_MS)
        }
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun formatDuration(seconds: Int): String = "%02d:%02d".format(seconds / 60, seconds % 60)

    private companion object {
        const val CHANNEL_ID = "active-timer"
        const val NOTIFICATION_ID = 1201
        const val STOPWATCH_NOTIFICATION_TIMEOUT_MS = 12L * 60L * 60L * 1_000L
    }
}

@Composable
fun TimerNotificationEffect(controller: ClientController, notification: TimerNotification) {
    val state by controller.state.collectAsState()
    val snapshot = state.snapshot
    val now = state.serverNowEpochMs
    LaunchedEffect(snapshot?.revision, snapshot?.timer?.status, now / 1_000) {
        notification.render(snapshot, now)
    }
}
