package com.ljwzz.weathertrafficalarm.core.alarm.check

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import com.ljwzz.weathertrafficalarm.core.alarm.R
import com.ljwzz.weathertrafficalarm.core.model.AlarmSound
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Verifies that the configured sound sources can be opened. It deliberately
 * does not create or start a [android.media.Ringtone], so readability is not a
 * playback guarantee.
 */
@Singleton
class AlarmRingtoneChecker private constructor(
    private val candidatesFor: (Uri?) -> List<Uri>,
    private val isReadable: (Uri) -> Boolean,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        candidatesFor = { selected ->
            listOfNotNull(
                selected,
                Settings.System.DEFAULT_ALARM_ALERT_URI,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                Uri.parse("android.resource://${context.packageName}/${R.raw.zhitu_alarm_fallback}"),
            ).distinct()
        },
        isReadable = { uri ->
            runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                    descriptor.fileDescriptor.valid()
                } ?: false
            }.getOrDefault(false)
        },
    )

    /** Runs file/content-provider access away from the main thread. */
    suspend fun check(sounds: List<AlarmSound>): RingtoneReadabilityCheck = withContext(Dispatchers.IO) {
        if (sounds.isEmpty()) return@withContext RingtoneReadabilityCheck(
            result = RingtoneReadabilityResult.NOT_CONFIGURED,
            configuredSoundCount = 0,
        )

        val checks = sounds.map { sound -> checkOne(sound.uri?.let(Uri::parse)) }
        val fallbackCount = checks.count { it == RingtoneReadabilityResult.DEFAULT_FALLBACK }
        val unreadableCount = checks.count { it == RingtoneReadabilityResult.UNREADABLE }
        RingtoneReadabilityCheck(
            result = when {
                unreadableCount > 0 -> RingtoneReadabilityResult.UNREADABLE
                fallbackCount > 0 -> RingtoneReadabilityResult.DEFAULT_FALLBACK
                else -> RingtoneReadabilityResult.READABLE
            },
            configuredSoundCount = sounds.size,
            fallbackCount = fallbackCount,
            unreadableCount = unreadableCount,
        )
    }

    private fun checkOne(selected: Uri?): RingtoneReadabilityResult {
        val candidates = candidatesFor(selected)
        val readableCandidate = candidates.firstOrNull(isReadable)
            ?: return RingtoneReadabilityResult.UNREADABLE
        return if (readableCandidate == candidates.first()) {
            RingtoneReadabilityResult.READABLE
        } else {
            RingtoneReadabilityResult.DEFAULT_FALLBACK
        }
    }

    internal constructor(
        candidatesFor: (Uri?) -> List<Uri>,
        isReadable: (Uri) -> Boolean,
        testOnly: Unit = Unit,
    ) : this(candidatesFor, isReadable)
}

data class RingtoneReadabilityCheck(
    val result: RingtoneReadabilityResult,
    val configuredSoundCount: Int,
    val fallbackCount: Int = 0,
    val unreadableCount: Int = 0,
)

enum class RingtoneReadabilityResult {
    READABLE,
    DEFAULT_FALLBACK,
    UNREADABLE,
    NOT_CONFIGURED,
}
