package com.ljwzz.weathertrafficalarm.ui.zhitu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlarmPermissionFlowTest {
    @Test fun continueAcknowledgesTheLatestStateAfterReturningFromSettings() {
        val before = AlarmPermissionSignature(false, false, false)
        val after = before.copy(notification = true)
        val action = AlarmEnableAction.Enable("plan")
        val finished = AlarmPermissionFlow().start(action, before).check().returnFromCheck()
            .continueWith(after).beginExecution().complete(true).finish()
        assertEquals(setOf(after), finished.acknowledged)
        assertEquals(AlarmEnablePhase.Ready, finished.start(action, after).phase)
        assertEquals(AlarmEnablePhase.Guide, finished.start(action, before).phase)
    }

    private val missing = AlarmPermissionSignature(
        notification = false,
        exactAlarm = false,
        fullScreen = false,
    )

    @Test
    fun cancelKeepsTheWriteUnexecutedAndDoesNotAcknowledgeTheMissingState() {
        val action = AlarmEnableAction.Enable("plan-1")

        val cancelled = AlarmPermissionFlow()
            .start(action, missing)
            .check()
            .returnFromCheck()
            .cancel()

        assertEquals(AlarmEnablePhase.Idle, cancelled.phase)
        assertNull(cancelled.pending)
        assertEquals(AlarmEnablePhase.Guide, cancelled.start(action, missing).phase)
    }

    @Test
    fun confirmedGuideAcknowledgesItsStateAndConsumesThePendingActionOnlyOnce() {
        val action = AlarmEnableAction.Enable("plan-1")
        val viewModel = AlarmPermissionViewModel(EditorDraft(soundUri = null))
        viewModel.start(action, missing)
        viewModel.continueWith(missing)

        assertEquals(AlarmEnablePhase.Ready, viewModel.flow.phase)
        assertEquals(action, viewModel.takeAction())
        assertNull(viewModel.takeAction())
        assertEquals(AlarmEnablePhase.Running, viewModel.flow.phase)

        viewModel.complete(success = true)
        viewModel.finish()
        assertEquals(AlarmEnablePhase.Idle, viewModel.flow.phase)
        viewModel.start(action, missing)
        assertEquals(AlarmEnablePhase.Ready, viewModel.flow.phase)
    }

    @Test
    fun repeatedStartKeepsTheOriginalPendingAction() {
        val original = AlarmEnableAction.Enable("plan-original")
        val later = AlarmEnableAction.Enable("plan-later")

        val flow = AlarmPermissionFlow()
            .start(original, missing)
            .start(later, missing)

        assertEquals(AlarmEnablePhase.Guide, flow.phase)
        assertEquals(original, flow.pending)
    }

    @Test
    fun failedExecutionReturnsToIdleWithoutRetainingThePendingWrite() {
        val action = AlarmEnableAction.Enable("plan-1")
        val viewModel = AlarmPermissionViewModel(EditorDraft(soundUri = null))
        viewModel.start(action, missing)
        viewModel.continueWith(missing)
        assertEquals(action, viewModel.takeAction())

        viewModel.complete(success = false)

        assertEquals(AlarmEnablePhase.Idle, viewModel.flow.phase)
        assertNull(viewModel.flow.pending)
        viewModel.start(action, missing)
        assertEquals(AlarmEnablePhase.Ready, viewModel.flow.phase)
    }

    @Test
    fun snapshotSignatureUsesCurrentStandardPermissionsAndOnlyRequiresXiaomiItemsOnXiaomi() {
        val missingNotification = PermissionSnapshot(
            notificationRuntimeGranted = false,
            notificationsAvailable = true,
            alarmChannelAvailable = true,
            exactAlarmAvailable = true,
            fullScreenIntentAvailable = true,
            isXiaomi = false,
            location = LocationPermissionSnapshot(false, false, true),
        )
        val xiaomi = missingNotification.copy(
            notificationRuntimeGranted = true,
            isXiaomi = true,
        )

        val genericSignature = missingNotification.signature(emptySet())
        val xiaomiSignature = xiaomi.signature(emptySet())
        assertEquals(listOf("通知权限"), genericSignature.missing)
        assertNull(genericSignature.xiaomiLockScreen)
        assertNull(genericSignature.xiaomiBackgroundPopup)
        assertEquals(false, xiaomiSignature.xiaomiLockScreen)
        assertEquals(false, xiaomiSignature.xiaomiBackgroundPopup)
        assertEquals(
            listOf("锁屏显示", "后台弹出界面"),
            xiaomiSignature.missing,
        )
        assertEquals(
            emptyList<String>(),
            xiaomi.signature(XiaomiDisplayPermission.entries.toSet()).missing,
        )
    }
}
