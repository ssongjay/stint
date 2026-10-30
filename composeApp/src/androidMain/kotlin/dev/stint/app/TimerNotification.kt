package dev.stint.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import dev.stint.shared.ClientState
import dev.stint.shared.TimerPhase
import dev.stint.shared.TimerStatus

class TimerNotification(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "진행 중인 집중", NotificationManager.IMPORTANCE_LOW).apply {
                description = "현재 집중 또는 휴식의 남은 시간을 표시합니다."
                setShowBadge(false)
            },
        )
    }

    fun build(state: ClientState, localNowEpochMs: Long = System.currentTimeMillis()): Notification? {
        val snapshot = state.snapshot ?: return null
        val timer = snapshot.timer
        val presentation = timerNotificationPresentation(state, localNowEpochMs) ?: return null
        val subjectName = snapshot.subjects.firstOrNull { it.id == timer.subjectId }?.name ?: "현재 세션"
        val phaseName = when (timer.phase) {
            TimerPhase.FOCUS -> "집중"
            TimerPhase.SHORT_BREAK -> "짧은 휴식"
            TimerPhase.LONG_BREAK -> "긴 휴식"
        }
        val builder = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(timerStatusIcon(timer.status, timer.phase))
            .setContentTitle("$phaseName · $subjectName")
            .setContentText(presentation.contentText)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setOngoing(presentation.liveUpdateRequested)
            .setShowWhen(presentation.usesChronometer)
            .setContentIntent(openAppIntent())

        if (presentation.usesChronometer) {
            builder.setWhen(presentation.whenEpochMs)
                .setUsesChronometer(true)
                .setChronometerCountDown(presentation.chronometerCountDown)
        }
        presentation.timeoutAfterMs?.let(builder::setTimeoutAfter)
        if (presentation.progressMax > 0) {
            builder.setProgress(presentation.progressMax, presentation.progress, false)
        }
        if (presentation.liveUpdateRequested) {
            builder.addExtras(Bundle().apply {
                putBoolean(TimerNotificationPolicy.EXTRA_REQUEST_PROMOTED_ONGOING, true)
            })
        }
        if (presentation.actionsEnabled) addTimerActions(builder, state)
        return builder.build()
    }

    fun render(state: ClientState) {
        val notification = build(state)
        if (notification == null) {
            cancel()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun cancel() {
        manager.cancel(NOTIFICATION_ID)
    }

    /** Used while the foreground action service validates and refreshes the synchronized state. */
    fun connectingNotification(): Notification = Notification.Builder(appContext, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stint_notification)
        .setContentTitle("Stint · 동기화 중")
        .setContentText("최신 타이머 상태를 확인하고 있어요")
        .setCategory(Notification.CATEGORY_SERVICE)
        .setOnlyAlertOnce(true)
        .setLocalOnly(true)
        .setOngoing(true)
        .setShowWhen(false)
        .setContentIntent(openAppIntent())
        .build()

    private fun addTimerActions(builder: Notification.Builder, state: ClientState) {
        val timer = state.snapshot?.timer ?: return
        val toggleAction = when (timer.status) {
            TimerStatus.RUNNING -> TimerNotificationPolicy.ACTION_PAUSE to "일시정지"
            TimerStatus.PAUSED -> TimerNotificationPolicy.ACTION_RESUME to "계속하기"
            TimerStatus.IDLE -> null
        }
        toggleAction?.let { (action, label) ->
            actionIntent(action, state)?.let { pendingIntent ->
                builder.addAction(Notification.Action.Builder(null as Icon?, label, pendingIntent).build())
            }
        }
        val endAction = if (timer.phase == TimerPhase.FOCUS) {
            TimerNotificationPolicy.ACTION_FINISH to "집중 마치기"
        } else {
            TimerNotificationPolicy.ACTION_SKIP_BREAK to "휴식 건너뛰기"
        }
        actionIntent(endAction.first, state)?.let { pendingIntent ->
            builder.addAction(
                Notification.Action.Builder(null as Icon?, endAction.second, pendingIntent).build(),
            )
        }
    }

    private fun timerStatusIcon(status: TimerStatus, phase: TimerPhase): Int = when {
        status == TimerStatus.PAUSED -> R.drawable.ic_stint_notification_paused
        phase == TimerPhase.FOCUS -> R.drawable.ic_stint_notification
        else -> R.drawable.ic_stint_notification_break
    }

    private fun actionIntent(action: String, state: ClientState): PendingIntent? {
        val guard = notificationCommandGuard(state) ?: return null
        val data = Uri.Builder()
            .scheme("stint")
            .authority("timer-action")
            .appendPath(action.substringAfterLast('.').lowercase())
            .appendQueryParameter("timer", guard.timerId)
            .appendQueryParameter("revision", guard.revision.toString())
            .build()
        val intent = Intent(appContext, TimerStatusService::class.java)
            .setAction(action)
            .setData(data)
            .putExtra(TimerNotificationPolicy.EXTRA_TIMER_ID, guard.timerId)
            .putExtra(TimerNotificationPolicy.EXTRA_REVISION, guard.revision)
            .putExtra(TimerNotificationPolicy.EXTRA_PHASE, guard.phase.name)
            .putExtra(TimerNotificationPolicy.EXTRA_STATUS, guard.status.name)
        return PendingIntent.getForegroundService(
            appContext,
            actionRequestCode(action),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        appContext,
        OPEN_APP_REQUEST_CODE,
        Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun actionRequestCode(action: String): Int = when (action) {
        TimerNotificationPolicy.ACTION_PAUSE -> PAUSE_REQUEST_CODE
        TimerNotificationPolicy.ACTION_RESUME -> RESUME_REQUEST_CODE
        TimerNotificationPolicy.ACTION_FINISH -> FINISH_REQUEST_CODE
        TimerNotificationPolicy.ACTION_SKIP_BREAK -> SKIP_BREAK_REQUEST_CODE
        else -> error("Unknown timer notification action: $action")
    }

    companion object {
        const val CHANNEL_ID = "active-timer"
        const val NOTIFICATION_ID = 1201
        private const val OPEN_APP_REQUEST_CODE = 12010
        private const val PAUSE_REQUEST_CODE = 12011
        private const val RESUME_REQUEST_CODE = 12012
        private const val FINISH_REQUEST_CODE = 12013
        private const val SKIP_BREAK_REQUEST_CODE = 12014
    }
}
