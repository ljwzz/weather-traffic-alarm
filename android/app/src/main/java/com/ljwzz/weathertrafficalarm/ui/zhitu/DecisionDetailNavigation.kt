package com.ljwzz.weathertrafficalarm.ui.zhitu

import android.content.Intent

/**
 * Explicit post-unlock hand-off from the Direct-Boot ringing surface.
 *
 * Only the occurrence identity crosses this boundary. The normal, unlocked
 * application resolves its persisted occurrence-to-decision association; no
 * decision, route, weather, place, or credential data is copied into an
 * Intent or a device-protected snapshot.
 */
const val ACTION_OPEN_DECISION_DETAIL = "com.ljwzz.weathertrafficalarm.OPEN_DECISION_DETAIL"
const val EXTRA_DECISION_OCCURRENCE_ID = "decision_occurrence_id"

fun Intent.decisionDetailOccurrenceId(): String? =
    takeIf { action == ACTION_OPEN_DECISION_DETAIL }
        ?.getStringExtra(EXTRA_DECISION_OCCURRENCE_ID)
        ?.takeIf(String::isNotBlank)

fun Intent.openDecisionDetailForOccurrence(occurrenceId: String): Intent = apply {
    action = ACTION_OPEN_DECISION_DETAIL
    putExtra(EXTRA_DECISION_OCCURRENCE_ID, occurrenceId)
}

/** Opening or returning from a read-only detail must not trigger alarm recovery. */
fun shouldRecoverAlarmsOnResume(detailReadOnlySession: Boolean): Boolean = !detailReadOnlySession

/** Preserve the no-side-effect detail session across an Activity recreation. */
fun restoredDetailReadOnlySession(savedValue: Boolean?, occurrenceId: String?): Boolean =
    savedValue ?: (occurrenceId != null)
