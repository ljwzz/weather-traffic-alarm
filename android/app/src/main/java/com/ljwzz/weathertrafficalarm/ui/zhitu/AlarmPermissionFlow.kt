package com.ljwzz.weathertrafficalarm.ui.zhitu

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

enum class XiaomiDisplayPermission { LockScreen, BackgroundPopup }

/** User acknowledgements are separate from capabilities read from Android. */
data class AlarmPermissionSignature(
    val notification: Boolean,
    val exactAlarm: Boolean,
    val fullScreen: Boolean,
    val xiaomiLockScreen: Boolean? = null,
    val xiaomiBackgroundPopup: Boolean? = null,
) {
    val missing: List<String>
        get() = buildList {
            if (!notification) add("通知权限")
            if (!exactAlarm) add("精确闹钟")
            if (!fullScreen) add("全屏提醒")
            if (xiaomiLockScreen == false) add("锁屏显示")
            if (xiaomiBackgroundPopup == false) add("后台弹出界面")
        }
}

sealed interface AlarmEnableAction {
    data class Save(val draft: EditorDraft) : AlarmEnableAction
    data class Enable(val planId: String) : AlarmEnableAction
}

enum class AlarmEnablePhase { Idle, Guide, Checking, Ready, Running, Finished }

/** Session-only state; cancel never consumes consent and a pending write is taken once. */
data class AlarmPermissionFlow(
    val phase: AlarmEnablePhase = AlarmEnablePhase.Idle,
    val pending: AlarmEnableAction? = null,
    val acknowledged: Set<AlarmPermissionSignature> = emptySet(),
) {
    fun start(action: AlarmEnableAction, signature: AlarmPermissionSignature): AlarmPermissionFlow {
        if (phase != AlarmEnablePhase.Idle) return this
        return copy(
            pending = action,
            phase = if (signature.missing.isNotEmpty() && signature !in acknowledged) AlarmEnablePhase.Guide else AlarmEnablePhase.Ready,
        )
    }

    fun check() = if (phase == AlarmEnablePhase.Guide) copy(phase = AlarmEnablePhase.Checking) else this
    fun returnFromCheck() = if (phase == AlarmEnablePhase.Checking) copy(phase = AlarmEnablePhase.Guide) else this
    fun continueWith(signature: AlarmPermissionSignature) = if (phase == AlarmEnablePhase.Guide) {
        copy(phase = AlarmEnablePhase.Ready, acknowledged = acknowledged + signature)
    } else this
    fun beginExecution() = if (phase == AlarmEnablePhase.Ready) copy(phase = AlarmEnablePhase.Running) else this
    fun complete(success: Boolean) = if (phase == AlarmEnablePhase.Running) {
        if (success) copy(phase = AlarmEnablePhase.Finished) else copy(phase = AlarmEnablePhase.Idle, pending = null)
    } else this
    fun cancel() = if (phase in setOf(AlarmEnablePhase.Guide, AlarmEnablePhase.Checking)) {
        copy(phase = AlarmEnablePhase.Idle, pending = null)
    } else this
    fun finish() = if (phase == AlarmEnablePhase.Finished) copy(phase = AlarmEnablePhase.Idle, pending = null) else this
}

/** Survives activity recreation, but never persists an automatic write across process death. */
class AlarmPermissionViewModel(initialDraft: EditorDraft = EditorDraft()) : ViewModel() {
    var flow by mutableStateOf(AlarmPermissionFlow())
        private set
    var confirmations by mutableStateOf(emptySet<XiaomiDisplayPermission>())
        private set
    var editorDraft by mutableStateOf(initialDraft)
    var destination by mutableStateOf(ZhituDestination.HOME)
    var navigationInitialized = false
    var entryOccurrenceId: String? = null
    var entryDestination: ZhituDestination? = null
    var initialized by mutableStateOf(false)

    fun start(action: AlarmEnableAction, signature: AlarmPermissionSignature) { flow = flow.start(action, signature) }
    fun check() { flow = flow.check() }
    fun returnFromCheck() { flow = flow.returnFromCheck() }
    fun continueWith(signature: AlarmPermissionSignature) { flow = flow.continueWith(signature) }
    fun cancel() { flow = flow.cancel() }
    fun confirm(permission: XiaomiDisplayPermission) { confirmations = confirmations + permission }
    fun clearConfirmation(permission: XiaomiDisplayPermission) { confirmations = confirmations - permission }
    fun takeAction(): AlarmEnableAction? {
        if (flow.phase != AlarmEnablePhase.Ready) return null
        val action = flow.pending
        flow = flow.beginExecution()
        return action
    }
    fun complete(success: Boolean) { flow = flow.complete(success) }
    fun finish() { flow = flow.finish() }
}
