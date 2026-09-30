package com.ljwzz.weathertrafficalarm.core.model

import java.time.LocalDate

/**
 * Typed identity of one evaluation run.
 *
 * The day revision comes from the independent revision table rather than from the override row,
 * so undoing and recreating the same values still produces a different fingerprint. The plan and
 * date identities are explicit so a fingerprint cannot match across plans or dates.
 */
data class DailyEvaluationFingerprint(
    val planId: String,
    val planRevision: Long,
    val date: String,
    val dayRevision: Long,
    val values: List<Any?>,
) {
    fun matches(other: DailyEvaluationFingerprint?): Boolean = this == other

    companion object {
        fun of(
            planId: String,
            planRevision: Long,
            date: LocalDate,
            dayRevision: Long,
            values: List<Any?>,
        ) = DailyEvaluationFingerprint(planId, planRevision, date.toString(), dayRevision, values)
    }
}
