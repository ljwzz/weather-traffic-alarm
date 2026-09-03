package com.ljwzz.weathertrafficalarm.ui.zhitu

/**
 * Pure state machine for the one-shot foreground location flow. It never
 * reads Android permissions or starts an Activity; the Compose screen maps
 * [LocationPermissionCommand] to those platform operations.
 */
data class LocationFlowSnapshot(
    val amapConsentGranted: Boolean,
    val sdkReady: Boolean,
    val locationServiceEnabled: Boolean,
    val coarseGranted: Boolean,
    val fineGranted: Boolean,
) {
    val hasForegroundLocation: Boolean get() = coarseGranted || fineGranted
}

data class LocationPermissionFlowState(
    val requestInFlight: Boolean = false,
    val locateInFlight: Boolean = false,
    val resumeAfterSettings: Boolean = false,
)

enum class LocationProviderBlockReason {
    CONSENT_REQUIRED,
    SDK_NOT_READY,
}

sealed interface LocationPermissionCommand {
    data object None : LocationPermissionCommand
    data object ShowPurpose : LocationPermissionCommand
    data object RequestSystemPermission : LocationPermissionCommand
    data object LocateOnce : LocationPermissionCommand
    data object ShowLocationServiceRecovery : LocationPermissionCommand
    data object ShowPermissionRecovery : LocationPermissionCommand
    data class ShowProviderBlocked(val reason: LocationProviderBlockReason) : LocationPermissionCommand
}

data class LocationPermissionTransition(
    val state: LocationPermissionFlowState,
    val command: LocationPermissionCommand,
)

fun LocationFlowSnapshot.accessStatusLabel(): String = when {
    !locationServiceEnabled -> "定位服务已关闭"
    fineGranted -> "当前位置权限：已允许精确位置"
    coarseGranted -> "当前位置权限：已允许大致位置"
    else -> "当前位置权限：尚未授予"
}

object LocationPermissionFlow {
    fun onUseCurrentLocation(
        state: LocationPermissionFlowState,
        snapshot: LocationFlowSnapshot,
    ): LocationPermissionTransition {
        if (state.requestInFlight || state.locateInFlight) return LocationPermissionTransition(state, LocationPermissionCommand.None)
        return when (val block = providerBlock(snapshot)) {
            null -> when {
                !snapshot.locationServiceEnabled -> LocationPermissionTransition(state, LocationPermissionCommand.ShowLocationServiceRecovery)
                snapshot.hasForegroundLocation -> LocationPermissionTransition(state.copy(locateInFlight = true), LocationPermissionCommand.LocateOnce)
                else -> LocationPermissionTransition(state, LocationPermissionCommand.ShowPurpose)
            }
            else -> LocationPermissionTransition(state, LocationPermissionCommand.ShowProviderBlocked(block))
        }
    }

    fun onPurposeConfirmed(
        state: LocationPermissionFlowState,
        snapshot: LocationFlowSnapshot,
    ): LocationPermissionTransition {
        if (state.requestInFlight || state.locateInFlight) return LocationPermissionTransition(state, LocationPermissionCommand.None)
        return when (val block = providerBlock(snapshot)) {
            null -> when {
                !snapshot.locationServiceEnabled -> LocationPermissionTransition(state, LocationPermissionCommand.ShowLocationServiceRecovery)
                snapshot.hasForegroundLocation -> LocationPermissionTransition(state.copy(locateInFlight = true), LocationPermissionCommand.LocateOnce)
                else -> LocationPermissionTransition(
                    state.copy(requestInFlight = true),
                    LocationPermissionCommand.RequestSystemPermission,
                )
            }
            else -> LocationPermissionTransition(state, LocationPermissionCommand.ShowProviderBlocked(block))
        }
    }

    fun onPermissionResult(
        state: LocationPermissionFlowState,
        snapshot: LocationFlowSnapshot,
    ): LocationPermissionTransition {
        val cleared = state.copy(requestInFlight = false)
        return when (val block = providerBlock(snapshot)) {
            null -> when {
                !snapshot.locationServiceEnabled -> LocationPermissionTransition(cleared, LocationPermissionCommand.ShowLocationServiceRecovery)
                snapshot.hasForegroundLocation -> LocationPermissionTransition(cleared.copy(locateInFlight = true), LocationPermissionCommand.LocateOnce)
                else -> LocationPermissionTransition(cleared, LocationPermissionCommand.ShowPermissionRecovery)
            }
            else -> LocationPermissionTransition(cleared, LocationPermissionCommand.ShowProviderBlocked(block))
        }
    }

    /** Consumes one pending continuation when the user returns from settings. */
    fun onSettingsReturned(
        state: LocationPermissionFlowState,
        snapshot: LocationFlowSnapshot,
    ): LocationPermissionTransition {
        if (!state.resumeAfterSettings) return LocationPermissionTransition(state, LocationPermissionCommand.None)
        val consumed = state.copy(resumeAfterSettings = false, requestInFlight = false)
        return when (val block = providerBlock(snapshot)) {
            null -> when {
                !snapshot.locationServiceEnabled -> LocationPermissionTransition(
                    consumed,
                    LocationPermissionCommand.ShowLocationServiceRecovery,
                )
                snapshot.hasForegroundLocation -> LocationPermissionTransition(consumed.copy(locateInFlight = true), LocationPermissionCommand.LocateOnce)
                else -> LocationPermissionTransition(consumed, LocationPermissionCommand.ShowPurpose)
            }
            else -> LocationPermissionTransition(consumed, LocationPermissionCommand.ShowProviderBlocked(block))
        }
    }

    fun markSettingsOpened(state: LocationPermissionFlowState): LocationPermissionFlowState =
        state.copy(resumeAfterSettings = true)

    fun cancelPending(state: LocationPermissionFlowState): LocationPermissionFlowState = LocationPermissionFlowState()

    fun onLocationCompleted(state: LocationPermissionFlowState): LocationPermissionFlowState =
        state.copy(locateInFlight = false)

    private fun providerBlock(snapshot: LocationFlowSnapshot): LocationProviderBlockReason? = when {
        !snapshot.amapConsentGranted -> LocationProviderBlockReason.CONSENT_REQUIRED
        !snapshot.sdkReady -> LocationProviderBlockReason.SDK_NOT_READY
        else -> null
    }
}
