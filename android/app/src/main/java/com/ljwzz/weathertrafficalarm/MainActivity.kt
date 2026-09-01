package com.ljwzz.weathertrafficalarm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.ljwzz.weathertrafficalarm.core.alarm.LocalAlarmCoordinator
import com.ljwzz.weathertrafficalarm.core.alarm.pendingintent.PendingIntentFactory
import com.ljwzz.weathertrafficalarm.ui.zhitu.ZhituApp
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Normal entry point and the AlarmClockInfo display target. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var coordinator: LocalAlarmCoordinator
    private var occurrenceId: String? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        occurrenceId = intent.showAlarmOccurrenceId()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { ZhituApp(ringingOccurrenceId = occurrenceId) }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { runCatching { coordinator.recover() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        occurrenceId = intent.showAlarmOccurrenceId()
        if (occurrenceId != null) recreate()
    }

    private fun Intent.showAlarmOccurrenceId(): String? =
        if (action == PendingIntentFactory.ACTION_SHOW_ALARM) {
            getStringExtra(PendingIntentFactory.EXTRA_OCCURRENCE_ID)
        } else {
            null
        }
}
