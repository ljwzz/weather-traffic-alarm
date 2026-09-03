package com.ljwzz.weathertrafficalarm.ui.zhitu

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionAccessTest {
    @Test fun directSettingsSuccessDoesNotLaunchFallback() {
        val attempts = mutableListOf<PermissionSetting>()
        val result = launchPermissionSetting(PermissionSetting.FullScreenIntent) { attempts += it; true }
        assertEquals(SettingsLaunchResult.Opened, result)
        assertEquals(listOf(PermissionSetting.FullScreenIntent), attempts)
    }

    @Test fun missingXiaomiEntryFallsBackToApplicationDetails() {
        val attempts = mutableListOf<PermissionSetting>()
        val result = launchPermissionSetting(PermissionSetting.XiaomiDisplayPermissions) { attempts += it; it == PermissionSetting.ApplicationDetails }
        assertEquals(SettingsLaunchResult.FallbackOpened(PermissionSetting.ApplicationDetails), result)
        assertEquals(listOf(PermissionSetting.XiaomiDisplayPermissions, PermissionSetting.ApplicationDetails), attempts)
    }

    @Test fun unavailableApplicationDetailsAreNotRetried() {
        val attempts = mutableListOf<PermissionSetting>()
        val result = launchPermissionSetting(PermissionSetting.ApplicationDetails) { attempts += it; false }
        assertEquals(SettingsLaunchResult.Unavailable(PermissionSetting.ApplicationDetails), result)
        assertEquals(listOf(PermissionSetting.ApplicationDetails), attempts)
    }

    @Test fun volumePanelUsesSoundSettingsBeforeApplicationDetails() {
        val attempts = mutableListOf<PermissionSetting>()
        val result = launchPermissionSetting(PermissionSetting.AlarmVolume) { attempts += it; it == PermissionSetting.SoundSettings }
        assertEquals(SettingsLaunchResult.FallbackOpened(PermissionSetting.SoundSettings), result)
        assertEquals(listOf(PermissionSetting.AlarmVolume, PermissionSetting.SoundSettings), attempts)
    }

    @Test fun failedDedicatedAndFallbackEntriesRemainUnavailable() {
        assertEquals(SettingsLaunchResult.Unavailable(PermissionSetting.ExactAlarm), launchPermissionSetting(PermissionSetting.ExactAlarm) { false })
    }

    @Test
    fun `read delegates complete snapshot without mutating it`() {
        val expected = PermissionSnapshot(
            notificationRuntimeGranted = false,
            notificationsAvailable = true,
            alarmChannelAvailable = false,
            exactAlarmAvailable = true,
            fullScreenIntentAvailable = false,
            isXiaomi = true,
            location = LocationPermissionSnapshot(
                coarseGranted = true,
                fineGranted = false,
                servicesEnabled = true,
            ),
        )
        val access = PermissionAccess.forTesting({ expected }) { SettingsLaunchResult.Opened }

        assertEquals(expected, access.read())
    }

    @Test
    fun `open settings preserves fallback and unavailable outcomes`() {
        val access = PermissionAccess.forTesting(
            snapshotReader = { error("not used") },
            settingsOpener = { setting ->
                when (setting) {
                    PermissionSetting.XiaomiDisplayPermissions ->
                        SettingsLaunchResult.FallbackOpened(PermissionSetting.ApplicationDetails)
                    else -> SettingsLaunchResult.Unavailable(setting)
                }
            },
        )

        assertEquals(
            SettingsLaunchResult.FallbackOpened(PermissionSetting.ApplicationDetails),
            access.openSettings(PermissionSetting.XiaomiDisplayPermissions),
        )
        assertEquals(
            SettingsLaunchResult.Unavailable(PermissionSetting.LocationServices),
            access.openSettings(PermissionSetting.LocationServices),
        )
    }
}
