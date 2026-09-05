package com.ljwzz.weathertrafficalarm.ui.zhitu

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljwzz.weathertrafficalarm.core.alarm.check.AlarmRingtoneChecker
import com.ljwzz.weathertrafficalarm.core.alarm.check.RingtoneReadabilityCheck
import com.ljwzz.weathertrafficalarm.core.alarm.check.RingtoneReadabilityResult
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.DiagnosticEvent
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.DiagnosticEventType
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.DiagnosticResultCode
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.RedactingEventLogger
import com.ljwzz.weathertrafficalarm.core.model.AlarmSound
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val ringtoneChecker: AlarmRingtoneChecker,
    private val diagnostics: RedactingEventLogger,
) : ViewModel() {
    val events: StateFlow<List<DiagnosticEvent>> = diagnostics.events
    val appVersion: String = diagnostics.appVersion
    val sdkInt: Int = diagnostics.sdkInt

    private val _ringtoneReadability = MutableStateFlow<RingtoneReadabilityCheck?>(null)
    val ringtoneReadability: StateFlow<RingtoneReadabilityCheck?> = _ringtoneReadability.asStateFlow()
    private val _checkingRingtone = MutableStateFlow(false)
    val checkingRingtone: StateFlow<Boolean> = _checkingRingtone.asStateFlow()
    private var ringtoneCheckJob: Job? = null

    /** Reads configured sound sources only; it never changes a plan or starts playback. */
    fun refreshRingtoneReadability(sounds: List<AlarmSound>) {
        ringtoneCheckJob?.cancel()
        ringtoneCheckJob = viewModelScope.launch {
            _checkingRingtone.value = true
            val startedAt = SystemClock.elapsedRealtime()
            val result = ringtoneChecker.check(sounds)
            _ringtoneReadability.value = result
            _checkingRingtone.value = false
            diagnostics.record(
                eventType = DiagnosticEventType.RINGTONE_CHECK,
                resultCode = result.result.toDiagnosticResultCode(),
                durationMs = SystemClock.elapsedRealtime() - startedAt,
            )
        }
    }
}

private fun RingtoneReadabilityResult.toDiagnosticResultCode(): DiagnosticResultCode = when (this) {
    RingtoneReadabilityResult.READABLE -> DiagnosticResultCode.SUCCESS
    RingtoneReadabilityResult.DEFAULT_FALLBACK -> DiagnosticResultCode.DEFAULT_FALLBACK
    RingtoneReadabilityResult.UNREADABLE -> DiagnosticResultCode.UNREADABLE
    RingtoneReadabilityResult.NOT_CONFIGURED -> DiagnosticResultCode.SKIPPED
}
