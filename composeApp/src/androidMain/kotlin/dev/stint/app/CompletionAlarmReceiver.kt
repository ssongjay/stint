package dev.stint.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.stint.app.client.TimerCompletionTracker
import dev.stint.shared.ConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class CompletionAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != COMPLETION_ALARM_ACTION) return
        val requestedKey = intent.getStringExtra(COMPLETION_KEY_EXTRA) ?: return
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                handleAlarm(appContext, requestedKey)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handleAlarm(context: Context, requestedKey: String) {
        val store = CompletionStore(context)
        val arm = store.currentArm()?.takeIf { it.key == requestedKey } ?: return
        val application = context as? StintApplication ?: return
        val controller = application.controller
        val settings = controller.state.value.settings
        if (settings.completionIdentityHash() != arm.identityHash) {
            store.cancelAlarm(context)
            return
        }

        val tracker = TimerCompletionTracker(store.deliveredKeys(arm.identityHash))
        tracker.update(arm.baselineState(settings))
        val before = controller.state.value.snapshot
        controller.refresh()
        val fresh = withTimeoutOrNull(9_000L) {
            controller.state.first { state ->
                val snapshot = state.snapshot
                state.connection == ConnectionStatus.CONNECTED && snapshot != null &&
                    (snapshot.serverNowEpochMs > (before?.serverNowEpochMs ?: Long.MIN_VALUE) ||
                        snapshot.revision != before?.revision)
            }
        }
        if (fresh == null) {
            store.clearArmIfMatches(arm)
            return
        }

        // The foreground observer can replace or cancel this arm while the refresh is in flight.
        if (store.currentArm()?.hasSameSchedule(arm) != true) return
        tracker.update(fresh)?.let { event ->
            if (store.claimIfArmMatches(arm, event.key)) postCompletion(context, event)
        }
        store.clearArmIfMatches(arm)
    }
}
