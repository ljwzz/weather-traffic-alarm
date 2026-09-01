package com.ljwzz.weathertrafficalarm.ui.zhitu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.ljwzz.weathertrafficalarm.core.alarm.AlarmReceiver
import com.ljwzz.weathertrafficalarm.core.alarm.pendingintent.PendingIntentFactory
import com.ljwzz.weathertrafficalarm.core.alarm.store.NextAlarmSnapshotStore

/**
 * Direct-Boot-safe full-screen alarm surface. It never opens Room or Hilt;
 * receiver/service own the durable state and validate every action.
 */
class AlarmRingingActivity : ComponentActivity() {
    private var occurrenceId: String? = null
    private fun readOccurrence(intent: android.content.Intent): String? {
        return if (intent.action == PendingIntentFactory.ACTION_SHOW_ALARM) {
            intent.getStringExtra(PendingIntentFactory.EXTRA_OCCURRENCE_ID)
        } else null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        occurrenceId = readOccurrence(intent)
        render()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        occurrenceId = readOccurrence(intent)
        render()
    }

    private fun render() {
        val snapshotStore = NextAlarmSnapshotStore(this)
        setContent {
            val snapshots by snapshotStore.observeAll().collectAsState(initial = emptyList())
            val snapshot = snapshots.firstOrNull { it.occurrenceId == occurrenceId }
            ZhituTheme {
                RingingScreen(
                    occurrenceId = occurrenceId,
                    alarmName = snapshot?.alarmLabel ?: "本地闹钟",
                    alarmTime = snapshot?.triggerAtMillis?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) } ?: "--:--",
                    snoozeMinutes = snapshot?.snoozeMinutes ?: 10,
                    onDismiss = { sendAction(dismiss = true) },
                    onSnooze = { sendAction(dismiss = false) },
                )
            }
        }
    }

    private fun sendAction(dismiss: Boolean) {
        val id = occurrenceId ?: run { finish(); return }
        runCatching {
            val intents = PendingIntentFactory(this)
            val pending = if (dismiss) {
                intents.dismissPendingIntent(id, AlarmReceiver::class.java)
            } else {
                intents.snoozePendingIntent(id, AlarmReceiver::class.java)
            }
            pending.send()
        }
        finish()
    }
}
