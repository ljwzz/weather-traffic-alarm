package com.ljwzz.weathertrafficalarm.ui.zhitu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationPermissionFlowTest {
    @Test
    fun useCurrentLocationIsBlockedUntilConsentAndSdkAreReady() {
        val consentBlocked = LocationPermissionFlow.onUseCurrentLocation(
            LocationPermissionFlowState(),
            snapshot(amapConsentGranted = false),
        )
        val sdkBlocked = LocationPermissionFlow.onUseCurrentLocation(
            LocationPermissionFlowState(),
            snapshot(sdkReady = false),
        )

        assertEquals(
            LocationPermissionCommand.ShowProviderBlocked(LocationProviderBlockReason.CONSENT_REQUIRED),
            consentBlocked.command,
        )
        assertEquals(
            LocationPermissionCommand.ShowProviderBlocked(LocationProviderBlockReason.SDK_NOT_READY),
            sdkBlocked.command,
        )
    }

    @Test
    fun purposeConfirmationRequestsCoarseAndFineOnlyWhenNeitherIsGranted() {
        val requested = LocationPermissionFlow.onPurposeConfirmed(
            LocationPermissionFlowState(),
            snapshot(),
        )
        val coarseAlreadyGranted = LocationPermissionFlow.onPurposeConfirmed(
            LocationPermissionFlowState(),
            snapshot(coarseGranted = true),
        )

        assertEquals(LocationPermissionCommand.RequestSystemPermission, requested.command)
        assertEquals(LocationPermissionCommand.LocateOnce, coarseAlreadyGranted.command)
    }

    @Test
    fun permissionResultAcceptsApproximateLocationAndClearsInFlightRequest() {
        val transition = LocationPermissionFlow.onPermissionResult(
            LocationPermissionFlowState(requestInFlight = true),
            snapshot(coarseGranted = true),
        )

        assertEquals(LocationPermissionCommand.LocateOnce, transition.command)
        assertFalse(transition.state.requestInFlight)
        assertTrue(transition.state.locateInFlight)
    }

    @Test
    fun alreadyGrantedLocationRunsOnceUntilTheLocationOperationCompletes() {
        val started = LocationPermissionFlow.onUseCurrentLocation(
            LocationPermissionFlowState(),
            snapshot(coarseGranted = true),
        )
        val duplicate = LocationPermissionFlow.onUseCurrentLocation(started.state, snapshot(coarseGranted = true))
        val completed = LocationPermissionFlow.onLocationCompleted(started.state)

        assertEquals(LocationPermissionCommand.LocateOnce, started.command)
        assertEquals(LocationPermissionCommand.None, duplicate.command)
        assertFalse(completed.locateInFlight)
    }

    @Test
    fun deniedResultsExposeSettingsRecoveryWithoutStartingLocation() {
        val denied = LocationPermissionFlow.onPermissionResult(
            LocationPermissionFlowState(requestInFlight = true),
            snapshot(),
        )
        val noRationale = LocationPermissionFlow.onPermissionResult(
            LocationPermissionFlowState(requestInFlight = true),
            snapshot(),
        )

        assertEquals(LocationPermissionCommand.ShowPermissionRecovery, denied.command)
        assertEquals(LocationPermissionCommand.ShowPermissionRecovery, noRationale.command)
    }

    @Test
    fun settingsReturnConsumesOnlyOnePendingLocationContinuation() {
        val pending = LocationPermissionFlow.markSettingsOpened(LocationPermissionFlowState())
        val resumed = LocationPermissionFlow.onSettingsReturned(pending, snapshot(fineGranted = true))
        val repeatedResume = LocationPermissionFlow.onSettingsReturned(resumed.state, snapshot(fineGranted = true))

        assertEquals(LocationPermissionCommand.LocateOnce, resumed.command)
        assertFalse(resumed.state.resumeAfterSettings)
        assertEquals(LocationPermissionCommand.None, repeatedResume.command)
    }

    @Test
    fun leavingThePickerCancelsAnySettingsContinuation() {
        val cancelled = LocationPermissionFlow.cancelPending(
            LocationPermissionFlowState(requestInFlight = true, resumeAfterSettings = true),
        )
        val returnAfterExit = LocationPermissionFlow.onSettingsReturned(cancelled, snapshot(fineGranted = true))

        assertEquals(LocationPermissionCommand.None, returnAfterExit.command)
    }

    @Test
    fun disabledLocationServiceGuidesToSettingsAndDoesNotResumeUntilReturn() {
        val transition = LocationPermissionFlow.onUseCurrentLocation(
            LocationPermissionFlowState(),
            snapshot(locationServiceEnabled = false),
        )

        assertEquals(LocationPermissionCommand.ShowLocationServiceRecovery, transition.command)
        assertFalse(transition.state.resumeAfterSettings)
        assertEquals(LocationPermissionCommand.ShowLocationServiceRecovery, LocationPermissionFlow.onSettingsReturned(
            LocationPermissionFlow.markSettingsOpened(transition.state),
            snapshot(locationServiceEnabled = false),
        ).command)
    }

    @Test
    fun permissionResultRechecksProviderAndLocationServiceBeforeLocating() {
        val serviceDisabled = LocationPermissionFlow.onPermissionResult(
            LocationPermissionFlowState(requestInFlight = true),
            snapshot(coarseGranted = true, locationServiceEnabled = false),
        )
        val providerBlocked = LocationPermissionFlow.onPermissionResult(
            LocationPermissionFlowState(requestInFlight = true),
            snapshot(coarseGranted = true, amapConsentGranted = false),
        )

        assertEquals(LocationPermissionCommand.ShowLocationServiceRecovery, serviceDisabled.command)
        assertEquals(
            LocationPermissionCommand.ShowProviderBlocked(LocationProviderBlockReason.CONSENT_REQUIRED),
            providerBlocked.command,
        )
    }

    @Test
    fun accessStatusDistinguishesCoarseFineAndDisabledServices() {
        assertEquals("当前位置权限：已允许大致位置", snapshot(coarseGranted = true).accessStatusLabel())
        assertEquals("当前位置权限：已允许精确位置", snapshot(fineGranted = true).accessStatusLabel())
        assertEquals("定位服务已关闭", snapshot(fineGranted = true, locationServiceEnabled = false).accessStatusLabel())
    }

    private fun snapshot(
        amapConsentGranted: Boolean = true,
        sdkReady: Boolean = true,
        locationServiceEnabled: Boolean = true,
        coarseGranted: Boolean = false,
        fineGranted: Boolean = false,
    ) = LocationFlowSnapshot(
        amapConsentGranted = amapConsentGranted,
        sdkReady = sdkReady,
        locationServiceEnabled = locationServiceEnabled,
        coarseGranted = coarseGranted,
        fineGranted = fineGranted,
    )
}
