package dev.stint.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import dev.stint.app.client.TimerCompletionEvent
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
import java.security.MessageDigest
import java.nio.charset.StandardCharsets

/** Reconciles synchronized countdown state with one audible, guarded system alarm. */
internal class AndroidCompletionNotifications(context: Context) {
    private val appContext = context.applicationContext
    private val store = CompletionStore(appContext)
    private var identityHash: String? = null
    private var tracker = TimerCompletionTracker()

    init {
        ensureCompletionChannel(
            appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager,
        )
    }

    fun update(state: ClientState) {
        val nextIdentity = state.settings.completionIdentityHash()
        if (identityHash != nextIdentity) {
            identityHash = nextIdentity
            tracker = TimerCompletionTracker(store.deliveredKeys(nextIdentity))
            store.cancelAlarm(appContext)
        }

        tracker.update(state)?.let { event ->
            if (store.claim(nextIdentity, event.key)) postCompletion(appContext, event)
        }
        reconcileAlarm(state, nextIdentity)
    }

    private fun reconcileAlarm(state: ClientState, identity: String) {
        val arm = completionAlarmArm(
            state,
            identity,
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
            wallClockEpochMs = System.currentTimeMillis(),
        )
        if (arm == null) {
            store.cancelAlarm(appContext)
            return
        }
        val current = store.currentArm()
        if (current?.hasSameSchedule(arm) == true) {
            if (current.revision != arm.revision) {
                store.saveArm(
                    arm.copy(
                        triggerElapsedRealtimeMs = current.triggerElapsedRealtimeMs,
                        triggerWallClockEpochMs = current.triggerWallClockEpochMs,
                    ),
                )
            }
            return
        }
        store.cancelAlarm(appContext)
        store.saveArm(arm)
        scheduleAlarm(appContext, arm)
    }
}

internal data class CompletionAlarmArm(
    val identityHash: String,
    val key: String,
    val timerId: String,
    val subjectId: String,
    val subjectName: String,
    val phase: TimerPhase,
    val startedAtEpochMs: Long,
    val runningSinceEpochMs: Long,
    val accumulatedMillis: Long,
    val targetSeconds: Int,
    val deadlineEpochMs: Long,
    val triggerElapsedRealtimeMs: Long,
    val triggerWallClockEpochMs: Long,
    val revision: Long,
)

internal fun CompletionAlarmArm.hasSameSchedule(other: CompletionAlarmArm): Boolean =
    identityHash == other.identityHash && key == other.key && deadlineEpochMs == other.deadlineEpochMs

internal fun completionAlarmArm(
    state: ClientState,
    identityHash: String,
    elapsedRealtimeMs: Long,
    wallClockEpochMs: Long = System.currentTimeMillis(),
): CompletionAlarmArm? {
    if (state.connection != ConnectionStatus.CONNECTED || state.working) return null
    val snapshot = state.snapshot ?: return null
    val timer = snapshot.timer
    if (timer.status != TimerStatus.RUNNING || timer.mode != TimerMode.COUNTDOWN) return null
    val timerId = timer.id ?: return null
    val subjectId = timer.subjectId ?: return null
    val startedAt = timer.startedAtEpochMs ?: return null
    val runningSince = timer.runningSinceEpochMs ?: return null
    val targetMillis = timer.targetSeconds.coerceAtLeast(0).toLong() * 1_000L
    val remainingAtResume = (targetMillis - timer.accumulatedMillis.coerceAtLeast(0L)).coerceAtLeast(0L)
    val deadline = addSaturated(runningSince, remainingAtResume)
    val remaining = (deadline - state.serverNowEpochMs).coerceAtLeast(0L)
    if (remaining == 0L) return null
    val subjectName = snapshot.subjects.firstOrNull { it.id == subjectId }?.name.orEmpty()
    return CompletionAlarmArm(
        identityHash = identityHash,
        key = "$timerId|${timer.phase.name}|$startedAt",
        timerId = timerId,
        subjectId = subjectId,
        subjectName = subjectName,
        phase = timer.phase,
        startedAtEpochMs = startedAt,
        runningSinceEpochMs = runningSince,
        accumulatedMillis = timer.accumulatedMillis,
        targetSeconds = timer.targetSeconds,
        deadlineEpochMs = deadline,
        triggerElapsedRealtimeMs = addSaturated(elapsedRealtimeMs, remaining),
        triggerWallClockEpochMs = addSaturated(wallClockEpochMs, remaining),
        revision = snapshot.revision,
    )
}

internal fun postCompletion(context: Context, event: TimerCompletionEvent) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    ensureCompletionChannel(manager)
    manager.notify(COMPLETION_NOTIFICATION_ID, buildCompletionNotification(context, event))
}

internal fun buildCompletionNotification(context: Context, event: TimerCompletionEvent): Notification {
    val icon = if (event.phase == TimerPhase.FOCUS) {
        R.drawable.ic_stint_notification
    } else {
        R.drawable.ic_stint_notification_break
    }
    val openApp = completionOpenPendingIntent(context)
    return Notification.Builder(context, COMPLETION_CHANNEL_ID)
        .setSmallIcon(icon)
        .setContentTitle(event.title)
        .setContentText(event.body)
        .setStyle(Notification.BigTextStyle().bigText(event.body))
        .setCategory(Notification.CATEGORY_ALARM)
        .setAutoCancel(true)
        .setShowWhen(true)
        .setContentIntent(openApp)
        .build()
}

internal fun ensureCompletionChannel(manager: NotificationManager) {
    manager.createNotificationChannel(
        NotificationChannel(
            COMPLETION_CHANNEL_ID,
            "타이머 종료",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "집중과 휴식이 끝났을 때 알림음과 진동으로 알려줍니다."
            enableVibration(true)
            setShowBadge(true)
        },
    )
}

private fun scheduleAlarm(context: Context, arm: CompletionAlarmArm) {
    val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val operation = completionAlarmPendingIntent(context, arm.key)
    if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) {
        runCatching {
            manager.setAlarmClock(
                AlarmManager.AlarmClockInfo(
                    arm.triggerWallClockEpochMs,
                    completionOpenPendingIntent(context),
                ),
                operation,
            )
        }.getOrElse {
            manager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                arm.triggerElapsedRealtimeMs,
                operation,
            )
        }
    } else {
        manager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            arm.triggerElapsedRealtimeMs,
            operation,
        )
    }
}

private fun completionAlarmPendingIntent(context: Context, key: String): PendingIntent =
    PendingIntent.getBroadcast(
        context,
        COMPLETION_ALARM_REQUEST_CODE,
        Intent(context, CompletionAlarmReceiver::class.java)
            .setAction(COMPLETION_ALARM_ACTION)
            .setData(Uri.parse("stint://completion/${Uri.encode(key)}"))
            .putExtra(COMPLETION_KEY_EXTRA, key),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

private fun completionOpenPendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
    context,
    COMPLETION_OPEN_REQUEST_CODE,
    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)

internal class CompletionStore(context: Context) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(COMPLETION_PREFERENCES, Context.MODE_PRIVATE)

    fun currentArm(): CompletionAlarmArm? = synchronized(STORE_LOCK) { readArm() }

    private fun readArm(): CompletionAlarmArm? {
        val key = preferences.getString(ARM_KEY, null) ?: return null
        return CompletionAlarmArm(
            identityHash = preferences.getString(ARM_IDENTITY, "").orEmpty(),
            key = key,
            timerId = preferences.getString(ARM_TIMER_ID, "").orEmpty(),
            subjectId = preferences.getString(ARM_SUBJECT_ID, "").orEmpty(),
            subjectName = preferences.getString(ARM_SUBJECT_NAME, "").orEmpty(),
            phase = runCatching {
                TimerPhase.valueOf(preferences.getString(ARM_PHASE, "").orEmpty())
            }.getOrNull() ?: return null,
            startedAtEpochMs = preferences.getLong(ARM_STARTED_AT, 0L),
            runningSinceEpochMs = preferences.getLong(ARM_RUNNING_SINCE, 0L),
            accumulatedMillis = preferences.getLong(ARM_ACCUMULATED, 0L),
            targetSeconds = preferences.getInt(ARM_TARGET_SECONDS, 0),
            deadlineEpochMs = preferences.getLong(ARM_DEADLINE, 0L),
            triggerElapsedRealtimeMs = preferences.getLong(ARM_TRIGGER, 0L),
            triggerWallClockEpochMs = preferences.getLong(ARM_WALL_TRIGGER, 0L),
            revision = preferences.getLong(ARM_REVISION, -1L),
        )
    }

    fun saveArm(arm: CompletionAlarmArm) = synchronized(STORE_LOCK) {
        preferences.edit()
            .putString(ARM_IDENTITY, arm.identityHash)
            .putString(ARM_KEY, arm.key)
            .putString(ARM_TIMER_ID, arm.timerId)
            .putString(ARM_SUBJECT_ID, arm.subjectId)
            .putString(ARM_SUBJECT_NAME, arm.subjectName)
            .putString(ARM_PHASE, arm.phase.name)
            .putLong(ARM_STARTED_AT, arm.startedAtEpochMs)
            .putLong(ARM_RUNNING_SINCE, arm.runningSinceEpochMs)
            .putLong(ARM_ACCUMULATED, arm.accumulatedMillis)
            .putInt(ARM_TARGET_SECONDS, arm.targetSeconds)
            .putLong(ARM_DEADLINE, arm.deadlineEpochMs)
            .putLong(ARM_TRIGGER, arm.triggerElapsedRealtimeMs)
            .putLong(ARM_WALL_TRIGGER, arm.triggerWallClockEpochMs)
            .putLong(ARM_REVISION, arm.revision)
            .commit()
        Unit
    }

    fun claim(identity: String, key: String): Boolean {
        synchronized(STORE_LOCK) {
            val deliveredKey = deliveredPreferenceKey(identity)
            val delivered = preferences.getStringSet(deliveredKey, emptySet()).orEmpty().toMutableSet()
            if (!delivered.add(key)) return false
            preferences.edit().putStringSet(deliveredKey, delivered.toList().takeLast(64).toSet()).commit()
            return true
        }
    }

    fun deliveredKeys(identity: String): Set<String> =
        synchronized(STORE_LOCK) {
            preferences.getStringSet(deliveredPreferenceKey(identity), emptySet()).orEmpty().toSet()
        }

    fun claimIfArmMatches(expected: CompletionAlarmArm, key: String): Boolean = synchronized(STORE_LOCK) {
        if (readArm()?.hasSameSchedule(expected) != true) return@synchronized false
        val deliveredKey = deliveredPreferenceKey(expected.identityHash)
        val delivered = preferences.getStringSet(deliveredKey, emptySet()).orEmpty().toMutableSet()
        if (!delivered.add(key)) return@synchronized false
        preferences.edit().putStringSet(deliveredKey, delivered.toList().takeLast(64).toSet()).commit()
    }

    fun cancelAlarm(context: Context) = synchronized(STORE_LOCK) {
        val key = preferences.getString(ARM_KEY, null)
        if (key != null) {
            val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            manager.cancel(completionAlarmPendingIntent(context, key))
        }
        clearArmLocked()
    }

    fun clearArm() = synchronized(STORE_LOCK) { clearArmLocked() }

    fun clearArmIfMatches(expected: CompletionAlarmArm): Boolean = synchronized(STORE_LOCK) {
        if (readArm()?.hasSameSchedule(expected) != true) return@synchronized false
        clearArmLocked()
        true
    }

    private fun clearArmLocked() {
        preferences.edit().remove(ARM_IDENTITY).remove(ARM_KEY).remove(ARM_TIMER_ID)
            .remove(ARM_SUBJECT_ID).remove(ARM_SUBJECT_NAME).remove(ARM_PHASE)
            .remove(ARM_STARTED_AT).remove(ARM_RUNNING_SINCE).remove(ARM_ACCUMULATED)
            .remove(ARM_TARGET_SECONDS).remove(ARM_DEADLINE).remove(ARM_TRIGGER)
            .remove(ARM_WALL_TRIGGER)
            .remove(ARM_REVISION).commit()
    }

    private companion object {
        val STORE_LOCK = Any()
    }
}

internal fun CompletionAlarmArm.baselineState(settings: ConnectionSettings): ClientState {
    val timer = TimerState(
        id = timerId,
        subjectId = subjectId,
        status = TimerStatus.RUNNING,
        phase = phase,
        mode = TimerMode.COUNTDOWN,
        targetSeconds = targetSeconds,
        accumulatedMillis = accumulatedMillis,
        runningSinceEpochMs = runningSinceEpochMs,
        startedAtEpochMs = startedAtEpochMs,
    )
    return ClientState(
        settings = settings,
        snapshot = Snapshot(
            serverNowEpochMs = (deadlineEpochMs - 1L).coerceAtLeast(0L),
            revision = revision,
            subjects = listOf(Subject(id = subjectId, name = subjectName)),
            timer = timer,
        ),
        serverNowEpochMs = (deadlineEpochMs - 1L).coerceAtLeast(0L),
        connection = ConnectionStatus.CONNECTED,
    )
}

internal fun ConnectionSettings.completionIdentityHash(): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$baseUrl\u0000$token".toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

private fun deliveredPreferenceKey(identity: String): String = "delivered-$identity"

private fun addSaturated(left: Long, right: Long): Long =
    if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

internal const val COMPLETION_CHANNEL_ID = "timer-completion-v1"
internal const val COMPLETION_NOTIFICATION_ID = 1202
internal const val COMPLETION_ALARM_ACTION = "dev.stint.app.TIMER_COMPLETION"
internal const val COMPLETION_KEY_EXTRA = "dev.stint.app.extra.COMPLETION_KEY"
private const val COMPLETION_ALARM_REQUEST_CODE = 12020
private const val COMPLETION_OPEN_REQUEST_CODE = 12021
private const val COMPLETION_PREFERENCES = "stint-completion-notifications"

private const val ARM_IDENTITY = "arm-identity"
private const val ARM_KEY = "arm-key"
private const val ARM_TIMER_ID = "arm-timer-id"
private const val ARM_SUBJECT_ID = "arm-subject-id"
private const val ARM_SUBJECT_NAME = "arm-subject-name"
private const val ARM_PHASE = "arm-phase"
private const val ARM_STARTED_AT = "arm-started-at"
private const val ARM_RUNNING_SINCE = "arm-running-since"
private const val ARM_ACCUMULATED = "arm-accumulated"
private const val ARM_TARGET_SECONDS = "arm-target-seconds"
private const val ARM_DEADLINE = "arm-deadline"
private const val ARM_TRIGGER = "arm-trigger"
private const val ARM_WALL_TRIGGER = "arm-wall-trigger"
private const val ARM_REVISION = "arm-revision"
