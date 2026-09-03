package com.ljwzz.weathertrafficalarm.ui.zhitu

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.ljwzz.weathertrafficalarm.core.alarm.AlarmNotificationChannel

/** Read-only platform permission state and best-effort user-setting navigation. */
data class PermissionSnapshot(
    val notificationRuntimeGranted: Boolean,
    val notificationsAvailable: Boolean,
    val alarmChannelAvailable: Boolean,
    val exactAlarmAvailable: Boolean,
    val fullScreenIntentAvailable: Boolean,
    val isXiaomi: Boolean,
    val location: LocationPermissionSnapshot,
)

data class LocationPermissionSnapshot(
    val coarseGranted: Boolean,
    val fineGranted: Boolean,
    val servicesEnabled: Boolean,
)

enum class PermissionSetting {
    Notifications,
    ExactAlarm,
    FullScreenIntent,
    XiaomiDisplayPermissions,
    ApplicationDetails,
    LocationServices,
    AlarmVolume,
    SoundSettings,
}

sealed interface SettingsLaunchResult {
    data object Opened : SettingsLaunchResult
    data class FallbackOpened(val target: PermissionSetting) : SettingsLaunchResult
    data class Unavailable(val target: PermissionSetting) : SettingsLaunchResult
}

class PermissionAccess private constructor(
    private val snapshotReader: () -> PermissionSnapshot,
    private val settingsOpener: (PermissionSetting) -> SettingsLaunchResult,
) {
    constructor(context: Context) : this(
        snapshotReader = { context.readPermissionSnapshot() },
        settingsOpener = { setting -> context.openPermissionSetting(setting) },
    )

    fun read(): PermissionSnapshot = snapshotReader()

    fun openSettings(setting: PermissionSetting): SettingsLaunchResult = settingsOpener(setting)

    companion object {
        internal fun forTesting(
            snapshotReader: () -> PermissionSnapshot,
            settingsOpener: (PermissionSetting) -> SettingsLaunchResult,
        ): PermissionAccess = PermissionAccess(snapshotReader, settingsOpener)
    }
}

private fun Context.readPermissionSnapshot(): PermissionSnapshot {
    val notificationManager = getSystemService(NotificationManager::class.java)
    val alarmManager = getSystemService(AlarmManager::class.java)
    val locationManager = getSystemService(LocationManager::class.java)
    val notificationRuntimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val alarmChannel = notificationManager?.getNotificationChannel(AlarmNotificationChannel.ID)
    return PermissionSnapshot(
        notificationRuntimeGranted = notificationRuntimeGranted,
        notificationsAvailable = notificationManager?.areNotificationsEnabled() == true,
        alarmChannelAvailable = alarmChannel != null &&
            alarmChannel.importance >= NotificationManager.IMPORTANCE_HIGH &&
            (alarmChannel.group?.let { groupId -> notificationManager.getNotificationChannelGroup(groupId)?.isBlocked } != true),
        exactAlarmAvailable = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager?.canScheduleExactAlarms() == true,
        fullScreenIntentAvailable = Build.VERSION.SDK_INT < 34 || notificationManager?.canUseFullScreenIntent() == true,
        isXiaomi = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true),
        location = LocationPermissionSnapshot(
            coarseGranted = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED,
            fineGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
            servicesEnabled = locationManager?.isLocationEnabled == true,
        ),
    )
}

private fun Context.openPermissionSetting(setting: PermissionSetting): SettingsLaunchResult =
    launchPermissionSetting(setting) { target -> tryStart(target.intentFor(packageName)) }

internal fun launchPermissionSetting(setting: PermissionSetting, launch: (PermissionSetting) -> Boolean): SettingsLaunchResult {
    if (launch(setting)) return SettingsLaunchResult.Opened

    if (setting == PermissionSetting.AlarmVolume && launch(PermissionSetting.SoundSettings)) {
        return SettingsLaunchResult.FallbackOpened(PermissionSetting.SoundSettings)
    }

    val fallback = PermissionSetting.ApplicationDetails
    if (setting != fallback && launch(fallback)) {
        return SettingsLaunchResult.FallbackOpened(fallback)
    }
    return SettingsLaunchResult.Unavailable(setting)
}

private fun PermissionSetting.intentFor(packageName: String): Intent = when (this) {
    PermissionSetting.Notifications -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    PermissionSetting.ExactAlarm -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
        .setData(Uri.parse("package:$packageName"))
    PermissionSetting.FullScreenIntent -> Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
        .setData(Uri.parse("package:$packageName"))
    PermissionSetting.XiaomiDisplayPermissions -> Intent(MIUI_APP_PERMISSION_EDITOR)
        .addCategory(Intent.CATEGORY_DEFAULT)
        .putExtra(MIUI_EXTRA_PACKAGE_NAME, packageName)
    PermissionSetting.ApplicationDetails -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.parse("package:$packageName"))
    PermissionSetting.LocationServices -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
    PermissionSetting.AlarmVolume -> Intent(Settings.Panel.ACTION_VOLUME)
    PermissionSetting.SoundSettings -> Intent(Settings.ACTION_SOUND_SETTINGS)
}

private fun Context.tryStart(intent: Intent): Boolean {
    return runCatching {
        if (intent.resolveActivity(packageManager) == null) return false
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}

private const val MIUI_APP_PERMISSION_EDITOR = "miui.intent.action.APP_PERM_EDITOR"
private const val MIUI_EXTRA_PACKAGE_NAME = "extra_pkgname"
