package dev.stint.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.stint.shared.ConnectionStatus
import dev.stint.shared.TimerPhase
import dev.stint.shared.TimerStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Keeps the user-visible timer connected while its activity is in the background. */
class TimerStatusService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller get() = (application as StintApplication).controller
    private lateinit var notification: TimerNotification

    override fun onCreate() {
        super.onCreate()
        running = true
        notification = TimerNotification(this)
        startForeground(TimerNotification.NOTIFICATION_ID, notification.connectingNotification())
        scope.launch {
            var previous: List<Any?>? = null
            controller.state.collect { state ->
                if (state.connection == ConnectionStatus.SETUP ||
                    (state.connection == ConnectionStatus.CONNECTED && state.snapshot?.timer?.status == TimerStatus.IDLE)) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }
                // Ticks are 250ms in the app; the system chronometer animates independently.
                val ticking = state.connection == ConnectionStatus.CONNECTED &&
                    state.snapshot?.timer?.status == TimerStatus.RUNNING
                val identity = listOf(state.snapshot?.revision, state.connection, state.working,
                    if (ticking) state.serverNowEpochMs / 1_000 else 0L)
                if (identity != previous) {
                    notification.render(state)
                    previous = identity
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action != null) {
            val guard = parseGuard(intent)
            scope.launch {
                val fresh = withTimeoutOrNull(12_000) {
                    controller.state.first { it.connection == ConnectionStatus.CONNECTED }
                }
                if (fresh != null && notificationCommandMatches(action, guard, fresh)) {
                    when (action) {
                        TimerNotificationPolicy.ACTION_PAUSE -> controller.pause()
                        TimerNotificationPolicy.ACTION_RESUME -> controller.resume()
                        TimerNotificationPolicy.ACTION_FINISH -> controller.finish()
                        TimerNotificationPolicy.ACTION_SKIP_BREAK -> controller.skipBreak()
                    }
                }
            }
        }
        // Force-stop and system termination remain respected; opening the app reconnects.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun parseGuard(intent: Intent): NotificationCommandGuard? = runCatching {
        NotificationCommandGuard(
            timerId = requireNotNull(intent.getStringExtra(TimerNotificationPolicy.EXTRA_TIMER_ID)),
            revision = intent.getLongExtra(TimerNotificationPolicy.EXTRA_REVISION, -1),
            phase = TimerPhase.valueOf(requireNotNull(intent.getStringExtra(TimerNotificationPolicy.EXTRA_PHASE))),
            status = TimerStatus.valueOf(requireNotNull(intent.getStringExtra(TimerNotificationPolicy.EXTRA_STATUS))),
        )
    }.getOrNull()

    companion object {
        @Volatile internal var running = false
            private set
    }
}
