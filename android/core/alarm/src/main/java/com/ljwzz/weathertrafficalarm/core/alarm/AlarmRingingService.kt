package com.ljwzz.weathertrafficalarm.core.alarm

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.UserManager
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import dagger.hilt.android.EntryPointAccessors
import com.ljwzz.weathertrafficalarm.core.alarm.pendingintent.PendingIntentFactory
import com.ljwzz.weathertrafficalarm.core.alarm.store.NextAlarmSnapshotStore
import com.ljwzz.weathertrafficalarm.core.model.NextAlarmSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Foreground service that owns local audio, vibration and the ten-minute timeout. */
class AlarmRingingService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private val active = linkedMapOf<String, NextAlarmSnapshot>()
    private val timeouts = mutableMapOf<String, Runnable>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val snapshot = intent?.toSnapshot() ?: return START_NOT_STICKY
        when (intent.action) {
            ACTION_DISMISS, ACTION_SNOOZE -> {
                stopOccurrence(snapshot.occurrenceId)
            }
            ACTION_RING -> startRinging(snapshot)
        }
        return START_NOT_STICKY
    }

    private fun startRinging(snapshot: NextAlarmSnapshot) {
        if (snapshot.occurrenceId in active) return
        val wasEmpty = active.isEmpty()
        active[snapshot.occurrenceId] = snapshot
        publishActive()
        AlarmNotificationChannel.ensureCreated(this)
        val notification = buildNotification(snapshot)
        if (wasEmpty) {
            startForeground(
                notificationId(snapshot.occurrenceId),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
            )
            if (!startAudio(snapshot)) {
                markAudioFailureAndStop(snapshot)
                return
            }
            startVibration(snapshot)
        } else {
            getSystemService(NotificationManager::class.java).notify(notificationId(snapshot.occurrenceId), notification)
        }
        timeouts.remove(snapshot.occurrenceId)?.let(mainHandler::removeCallbacks)
        val timeout = Runnable {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                val userManager = getSystemService(UserManager::class.java)
                if (userManager.isUserUnlocked) {
                    EntryPointAccessors
                        .fromApplication(applicationContext, AlarmCoordinatorEntryPoint::class.java)
                        .coordinator()
                        .dismiss(snapshot.occurrenceId)
                } else {
                    NextAlarmSnapshotStore(applicationContext).getByOccurrenceId(snapshot.occurrenceId)?.let { stored ->
                        NextAlarmSnapshotStore(applicationContext).save(
                            stored.copy(occurrenceState = AlarmReceiver.STATE_DISMISSED),
                        )
                    }
                }
            }
            stopOccurrence(snapshot.occurrenceId)
        }
        timeouts[snapshot.occurrenceId] = timeout
        mainHandler.postDelayed(timeout, RING_TIMEOUT_MILLIS)
    }

    private fun stopOccurrence(occurrenceId: String) {
        if (active.remove(occurrenceId) == null) {
            if (active.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }
        timeouts.remove(occurrenceId)?.let(mainHandler::removeCallbacks)
        getSystemService(NotificationManager::class.java).cancel(notificationId(occurrenceId))
        publishActive()
        if (active.isEmpty()) {
            stopAudio()
            getSystemService(VibratorManager::class.java).defaultVibrator.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            val foreground = active.values.last()
            startForeground(
                notificationId(foreground.occurrenceId),
                buildNotification(foreground),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
            )
            stopAudio()
            getSystemService(VibratorManager::class.java).defaultVibrator.cancel()
            if (startAudio(foreground)) {
                startVibration(foreground)
            } else {
                markAudioFailureAndStop(foreground)
            }
        }
    }

    private fun buildNotification(snapshot: NextAlarmSnapshot): Notification {
        val factory = PendingIntentFactory(this)
        val dismiss = factory.dismissPendingIntent(snapshot.occurrenceId, AlarmReceiver::class.java)
        val snooze = factory.snoozePendingIntent(snapshot.occurrenceId, AlarmReceiver::class.java)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(snapshot.alarmLabel)
            .setContentText("闹钟正在响铃")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(factory.showAlarmPendingIntent(snapshot.occurrenceId))
            .addAction(android.R.drawable.ic_media_pause, "停止", dismiss)
            .addAction(android.R.drawable.ic_media_play, "稍后 ${snapshot.snoozeMinutes} 分钟", snooze)
            .also { builder ->
                val manager = getSystemService(NotificationManager::class.java)
                if (manager.canUseFullScreenIntent()) {
                    builder.setFullScreenIntent(factory.showAlarmPendingIntent(snapshot.occurrenceId), true)
                }
            }
            .build()
    }

    private fun startAudio(snapshot: NextAlarmSnapshot): Boolean {
        stopAudio()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val ringtone = sequenceOf(
            snapshot.soundUri?.let(Uri::parse),
            Settings.System.DEFAULT_ALARM_ALERT_URI,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            fallbackAlarmUri(),
        ).filterNotNull().firstNotNullOfOrNull { candidate -> playRingtone(candidate, attributes) }
        this.ringtone = ringtone
        return ringtone != null
    }

    private fun playRingtone(uri: Uri, attributes: AudioAttributes): Ringtone? = runCatching {
        val candidate = RingtoneManager.getRingtone(this, uri) ?: return@runCatching null
        candidate.audioAttributes = attributes
        candidate.isLooping = true
        candidate.play()
        candidate.takeIf { it.isPlaying }
            ?: run {
                candidate.stop()
                null
            }
    }.getOrNull()

    private fun fallbackAlarmUri(): Uri =
        Uri.parse("android.resource://$packageName/${R.raw.zhitu_alarm_fallback}")

    private fun markAudioFailureAndStop(snapshot: NextAlarmSnapshot) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            NextAlarmSnapshotStore(applicationContext).getByOccurrenceId(snapshot.occurrenceId)?.let { stored ->
                NextAlarmSnapshotStore(applicationContext).save(
                    stored.copy(occurrenceState = AlarmReceiver.STATE_DISMISSED),
                )
            }
        }
        stopOccurrence(snapshot.occurrenceId)
    }

    private fun startVibration(snapshot: NextAlarmSnapshot) {
        if (!snapshot.vibrationEnabled) return
        val vibrator = getSystemService(VibratorManager::class.java).defaultVibrator
        val pattern = snapshot.vibrationPatternMillis.toLongArray()
        if (pattern.isNotEmpty() && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
        }
    }

    private fun stopAudio() {
        runCatching { ringtone?.stop() }
        ringtone = null
    }

    override fun onDestroy() {
        timeouts.values.forEach(mainHandler::removeCallbacks)
        timeouts.clear()
        active.clear()
        publishActive()
        stopAudio()
        getSystemService(VibratorManager::class.java).defaultVibrator.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_RING = "com.ljwzz.weathertrafficalarm.action.RING"
        const val ACTION_DISMISS = "com.ljwzz.weathertrafficalarm.action.DISMISS"
        const val ACTION_SNOOZE = "com.ljwzz.weathertrafficalarm.action.SNOOZE"

        private const val CHANNEL_ID = AlarmNotificationChannel.ID
        private const val RING_TIMEOUT_MILLIS = 10 * 60_000L

        private val _activeAlarms = MutableStateFlow<List<NextAlarmSnapshot>>(emptyList())
        val activeAlarms: StateFlow<List<NextAlarmSnapshot>> = _activeAlarms.asStateFlow()

        private const val EXTRA_PLAN_ID = "alarm_plan_id"
        private const val EXTRA_PLAN_REVISION = "alarm_plan_revision"
        private const val EXTRA_TRIGGER_AT = "alarm_trigger_at"
        private const val EXTRA_SOUND_URI = "alarm_sound_uri"
        private const val EXTRA_VIBRATION = "alarm_vibration"
        private const val EXTRA_VIBRATION_PATTERN = "alarm_vibration_pattern"
        private const val EXTRA_SNOOZE = "alarm_snooze"
        private const val EXTRA_LABEL = "alarm_label"
        private const val EXTRA_KIND = "alarm_kind"
        private const val EXTRA_PARENT = "alarm_parent"
        private const val EXTRA_SNOOZE_COUNT = "alarm_snooze_count"

        fun intent(context: Context, action: String, snapshot: NextAlarmSnapshot): Intent =
            Intent(context, AlarmRingingService::class.java).apply {
                this.action = action
                putExtra(PendingIntentFactory.EXTRA_OCCURRENCE_ID, snapshot.occurrenceId)
                putExtra(EXTRA_PLAN_ID, snapshot.planId)
                putExtra(EXTRA_PLAN_REVISION, snapshot.planRevision)
                putExtra(EXTRA_TRIGGER_AT, snapshot.triggerAtMillis)
                putExtra(EXTRA_SOUND_URI, snapshot.soundUri)
                putExtra(EXTRA_VIBRATION, snapshot.vibrationEnabled)
                putExtra(EXTRA_VIBRATION_PATTERN, snapshot.vibrationPatternMillis.toLongArray())
                putExtra(EXTRA_SNOOZE, snapshot.snoozeMinutes)
                putExtra(EXTRA_LABEL, snapshot.alarmLabel)
                putExtra(EXTRA_KIND, snapshot.occurrenceKind)
                putExtra(EXTRA_PARENT, snapshot.parentOccurrenceId)
                putExtra(EXTRA_SNOOZE_COUNT, snapshot.snoozeCount)
            }

        private fun Intent.toSnapshot(): NextAlarmSnapshot? {
            val occurrenceId = getStringExtra(PendingIntentFactory.EXTRA_OCCURRENCE_ID) ?: return null
            val planId = getStringExtra(EXTRA_PLAN_ID) ?: return null
            return NextAlarmSnapshot(
                occurrenceId = occurrenceId,
                planId = planId,
                planRevision = getLongExtra(EXTRA_PLAN_REVISION, -1L),
                triggerAtMillis = getLongExtra(EXTRA_TRIGGER_AT, -1L),
                soundUri = getStringExtra(EXTRA_SOUND_URI),
                vibrationEnabled = getBooleanExtra(EXTRA_VIBRATION, true),
                vibrationPatternMillis = getLongArrayExtra(EXTRA_VIBRATION_PATTERN)?.toList()
                    ?: listOf(0, 500, 500, 500),
                snoozeMinutes = getIntExtra(EXTRA_SNOOZE, 10),
                alarmLabel = getStringExtra(EXTRA_LABEL) ?: "闹钟",
                occurrenceKind = getStringExtra(EXTRA_KIND) ?: "REGULAR",
                parentOccurrenceId = getStringExtra(EXTRA_PARENT),
                snoozeCount = getIntExtra(EXTRA_SNOOZE_COUNT, 0),
            )
        }

        private fun notificationId(occurrenceId: String): Int =
            10_000 + (occurrenceId.hashCode() and 0x0FFF_FFFF)

    }

    private fun publishActive() {
        _activeAlarms.value = active.values.toList()
    }
}
