package com.ljwzz.weathertrafficalarm

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.ljwzz.weathertrafficalarm.core.alarm.LocalAlarmCoordinator
import com.ljwzz.weathertrafficalarm.core.alarm.pendingintent.PendingIntentFactory
import com.ljwzz.weathertrafficalarm.ui.zhitu.ZhituApp
import com.ljwzz.weathertrafficalarm.ui.zhitu.ZhituDestination
import com.ljwzz.weathertrafficalarm.ui.zhitu.ACTION_OPEN_DECISION_DETAIL
import com.ljwzz.weathertrafficalarm.ui.zhitu.decisionDetailOccurrenceId
import com.ljwzz.weathertrafficalarm.ui.zhitu.restoredDetailReadOnlySession
import com.ljwzz.weathertrafficalarm.ui.zhitu.shouldRecoverAlarmsOnResume
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Normal entry point and the AlarmClockInfo display target. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var coordinator: LocalAlarmCoordinator
    private var occurrenceId: String? = null
    private var decisionOccurrenceId: String? = null
    private var detailReadOnlySession = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        occurrenceId = intent.showAlarmOccurrenceId()
        decisionOccurrenceId = intent.decisionDetailOccurrenceId()
        val decisionDetailRequest = intent.action == ACTION_OPEN_DECISION_DETAIL
        detailReadOnlySession = if (decisionDetailRequest) true else restoredDetailReadOnlySession(
            savedInstanceState?.getBoolean(STATE_DETAIL_READ_ONLY), decisionOccurrenceId,
        )
        setContent {
            ZhituApp(
                initialDestination = when (intent.action) {
                    ACTION_OPEN_ALARM_PLANS -> ZhituDestination.PLANS
                    ACTION_OPEN_DECISION_DETAIL -> ZhituDestination.DECISION_DETAIL
                    else -> ZhituDestination.HOME
                },
                ringingOccurrenceId = occurrenceId,
                initialDecisionOccurrenceId = decisionOccurrenceId,
                onDecisionDetailVisibilityChanged = { detailReadOnlySession = it },
                onExternalDecisionBack = ::finish,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (shouldRecoverAlarmsOnResume(detailReadOnlySession)) {
            lifecycleScope.launch { runCatching { coordinator.recover() } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        occurrenceId = intent.showAlarmOccurrenceId()
        decisionOccurrenceId = intent.decisionDetailOccurrenceId()
        val decisionDetailRequest = intent.action == ACTION_OPEN_DECISION_DETAIL
        detailReadOnlySession = decisionDetailRequest
        if (occurrenceId != null || decisionDetailRequest || intent.action == ACTION_OPEN_ALARM_PLANS) recreate()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_DETAIL_READ_ONLY, detailReadOnlySession)
        super.onSaveInstanceState(outState)
    }

    private fun Intent.showAlarmOccurrenceId(): String? =
        if (action == PendingIntentFactory.ACTION_SHOW_ALARM) {
            getStringExtra(PendingIntentFactory.EXTRA_OCCURRENCE_ID)
        } else {
            null
        }

    companion object {
        const val ACTION_OPEN_ALARM_PLANS = "com.ljwzz.weathertrafficalarm.OPEN_ALARM_PLANS"
        private const val STATE_DETAIL_READ_ONLY = "detail_read_only"
    }
}
