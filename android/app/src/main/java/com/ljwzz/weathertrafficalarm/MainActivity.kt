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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Normal entry point and the AlarmClockInfo display target. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var coordinator: LocalAlarmCoordinator
    private var occurrenceId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        occurrenceId = intent.showAlarmOccurrenceId()
        setContent {
            ZhituApp(
                initialDestination = if (intent.action == ACTION_OPEN_ALARM_PLANS) ZhituDestination.PLANS else ZhituDestination.HOME,
                ringingOccurrenceId = occurrenceId,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { runCatching { coordinator.recover() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        occurrenceId = intent.showAlarmOccurrenceId()
        if (occurrenceId != null || intent.action == ACTION_OPEN_ALARM_PLANS) recreate()
    }

    private fun Intent.showAlarmOccurrenceId(): String? =
        if (action == PendingIntentFactory.ACTION_SHOW_ALARM) {
            getStringExtra(PendingIntentFactory.EXTRA_OCCURRENCE_ID)
        } else {
            null
        }

    companion object {
        const val ACTION_OPEN_ALARM_PLANS = "com.ljwzz.weathertrafficalarm.OPEN_ALARM_PLANS"
    }
}
