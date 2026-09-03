package com.ljwzz.weathertrafficalarm.ui.zhitu

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.UserManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.ljwzz.weathertrafficalarm.MainActivity
import com.ljwzz.weathertrafficalarm.core.alarm.AlarmReceiver
import com.ljwzz.weathertrafficalarm.core.alarm.AlarmRingingService
import com.ljwzz.weathertrafficalarm.core.alarm.pendingintent.AlarmAction
import com.ljwzz.weathertrafficalarm.core.alarm.pendingintent.PendingIntentFactory
import com.ljwzz.weathertrafficalarm.core.alarm.store.NextAlarmSnapshotStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch

/** Uses only device-protected snapshots; the receiver owns durable alarm actions. */
class AlarmRingingActivity : ComponentActivity() {
    private var occurrenceId by mutableStateOf<String?>(null)
    private var navigationError by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        occurrenceId = readOccurrence(intent)
        val store = NextAlarmSnapshotStore(applicationContext)
        setContent {
            val snapshots by remember(store) {
                store.observeAll().catch { emit(emptyList()) }
            }.collectAsState(initial = null)
            val active by AlarmRingingService.activeAlarms.collectAsState()
            val id = occurrenceId
            val snapshot = snapshots?.firstOrNull { it.occurrenceId == id }
            var pendingRevision by rememberSaveable(id) { mutableStateOf<Long?>(null) }
            var actionError by rememberSaveable(id) { mutableStateOf<String?>(null) }
            val activeIds = active.map { it.occurrenceId }.toSet()
            val projected = ringingUiState(id, snapshots, activeIds)

            LaunchedEffect(id, snapshots, activeIds) {
                if (snapshot?.occurrenceState == AlarmReceiver.STATE_SNOOZED) {
                    val firingChild = snapshots?.firstOrNull {
                        it.parentOccurrenceId == id && it.planId == snapshot.planId &&
                            it.planRevision == snapshot.planRevision && it.occurrenceKind == "SNOOZE" &&
                            it.occurrenceState == AlarmReceiver.STATE_FIRING && it.occurrenceId in activeIds
                    }
                    if (firingChild != null) {
                        setIntent(PendingIntentFactory(this@AlarmRingingActivity).createShowAlarmIntent(firingChild.occurrenceId))
                        occurrenceId = firingChild.occurrenceId
                    }
                }
            }

            LaunchedEffect(id, snapshot?.actionRevision, snapshot?.occurrenceState, activeIds, projected.phase) {
                val revision = pendingRevision
                if (revision != null && (
                        (snapshot != null && snapshot.actionRevision > revision && snapshot.actionError != null) ||
                            projected.phase in setOf(RingingPhase.STOPPED, RingingPhase.SNOOZED, RingingPhase.UNAVAILABLE)
                        )) {
                    pendingRevision = null
                    actionError = snapshot?.actionError
                }
            }
            LaunchedEffect(id, pendingRevision) {
                if (pendingRevision != null) {
                    delay(ACTION_CONFIRMATION_TIMEOUT_MILLIS)
                    pendingRevision = null
                    actionError = "操作尚未确认，请重试或从通知操作。"
                }
            }

            fun requestAction(dismiss: Boolean) {
                if (id == null || snapshot?.occurrenceState != AlarmReceiver.STATE_FIRING ||
                    id !in activeIds || pendingRevision != null) return
                navigationError = null
                actionError = null
                pendingRevision = snapshot.actionRevision
                runCatching {
                    val factory = PendingIntentFactory(this@AlarmRingingActivity)
                    val pending = if (dismiss) factory.dismissPendingIntent(id, AlarmReceiver::class.java)
                    else factory.snoozePendingIntent(id, AlarmReceiver::class.java)
                    pending.send()
                }.onFailure {
                    pendingRevision = null
                    actionError = "操作未能发送，请重试。"
                }
            }

            ZhituTheme {
                RingingScreen(
                    state = ringingUiState(
                        id, snapshots, activeIds,
                        pendingAction = pendingRevision != null,
                        errorMessage = navigationError ?: if (projected.phase == RingingPhase.RINGING) {
                            actionError ?: snapshot?.actionError.takeIf { pendingRevision == null }
                        } else null,
                    ),
                    onDismiss = { requestAction(dismiss = true) },
                    onSnooze = { requestAction(dismiss = false) },
                    onOpenPlans = ::openPlans,
                    onClose = ::finish,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        navigationError = null
        occurrenceId = readOccurrence(intent)
    }

    private fun openPlans() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked) {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = openUnlockedPlans()
                override fun onDismissCancelled() { navigationError = "解锁后可查看闹钟。" }
                override fun onDismissError() { navigationError = "请解锁后查看闹钟。" }
            })
        } else openUnlockedPlans()
    }

    private fun openUnlockedPlans() {
        if (!getSystemService(UserManager::class.java).isUserUnlocked) {
            navigationError = "解锁后可查看闹钟。"
            return
        }
        startActivity(Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_ALARM_PLANS
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    private fun readOccurrence(intent: Intent): String? {
        val uri = intent.data ?: return null
        if (intent.action != PendingIntentFactory.ACTION_SHOW_ALARM || uri.scheme != "alarm" ||
            uri.host != "occurrences" || uri.pathSegments.size != 1 ||
            uri.getQueryParameter("action") != AlarmAction.SHOW_ALARM.path) return null
        val id = uri.pathSegments.single().takeIf(String::isNotBlank) ?: return null
        return id.takeIf { it == intent.getStringExtra(PendingIntentFactory.EXTRA_OCCURRENCE_ID) }
    }

    companion object {
        private const val ACTION_CONFIRMATION_TIMEOUT_MILLIS = 8_000L
    }
}
