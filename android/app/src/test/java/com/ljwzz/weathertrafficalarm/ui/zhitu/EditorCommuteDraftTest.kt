package com.ljwzz.weathertrafficalarm.ui.zhitu

import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import org.junit.Assert.*
import org.junit.Test

class EditorCommuteDraftTest {
    private val origin = PlaceRef(name = "家", displayAddress = "北京市", longitudeGcj02 = 116.4, latitudeGcj02 = 39.9, adcode = "110000", citycode = "010")
    private val destination = PlaceRef(name = "公司", displayAddress = "北京市", longitudeGcj02 = 116.5, latitudeGcj02 = 39.8, adcode = "110000", citycode = "010")

    @Test fun secondPlanCommuteUpdatesOnlyTheMatchingWholeDraft() {
        val draft = EditorDraft(id = "second", name = "晚班", preparationMinutes = 42, soundUri = null)
        val editor = PlanCommuteEditorState("second", origin, destination, CommuteMode.WALKING, false)
        val updated = draft.withCommute(editor)
        assertEquals("second", updated.commute!!.toOverride(updated.planId)!!.planId)
        assertEquals("晚班", updated.name)
        assertEquals(42, updated.preparationMinutes)
        assertNull(draft.commute)
        assertEquals(CommuteMode.WALKING, updated.commute!!.mode)
    }

    @Test fun mismatchedPlanAndIncompleteOrLoadingCommuteCannotEnterDraft() {
        val draft = EditorDraft(id = "second", soundUri = null)
        assertThrows(IllegalArgumentException::class.java) { draft.withCommute(PlanCommuteEditorState(planId = "first")) }
        assertThrows(IllegalArgumentException::class.java) { draft.withCommute(PlanCommuteEditorState(planId = "second", loading = true)) }
        assertThrows(IllegalArgumentException::class.java) { draft.withCommute(PlanCommuteEditorState(planId = "second", useGlobal = false)) }
        assertThrows(IllegalArgumentException::class.java) { draft.withCommute(PlanCommuteEditorState("second", origin, origin, useGlobal = false)) }
    }

    @Test fun newDraftKeepsStableIdentityThroughChildAndPermissionRoundTrips() {
        val draft = EditorDraft(name = "新计划", soundUri = null)
        val updated = draft.withCommute(PlanCommuteEditorState(draft.planId, origin, destination, useGlobal = false))
        assertNull(updated.id)
        assertEquals(draft.planId, updated.copy(name = "新名字").planId)
        val permission = AlarmPermissionViewModel(updated)
        permission.start(AlarmEnableAction.Save(updated), AlarmPermissionSignature(false, true, true))
        permission.check()
        permission.returnFromCheck()
        permission.cancel()
        assertEquals(updated, permission.editorDraft)
        assertNull(permission.flow.pending)
        assertEquals(updated.planId, updated.commute!!.toOverride(updated.planId)!!.planId)
    }

    @Test fun untouchedOverrideAndExplicitGlobalAreDifferentDraftStates() {
        val draft = EditorDraft(id = "existing", soundUri = null)
        assertNull(draft.commute)
        val global = draft.withCommute(PlanCommuteEditorState(planId = draft.planId))
        assertNotNull(global.commute)
        assertTrue(global.commute!!.useGlobal)
        assertNull(global.commute!!.toOverride(global.planId))
    }
}
