package com.ljwzz.weathertrafficalarm.core.model

/**
 * One complete single-day write for a plan and date.
 *
 * The replacement is a full snapshot: every `null` field means "restore inheritance for this
 * field" instead of "keep the stored value". A `null` replacement, or one whose every field is
 * empty, removes the override row and restores the calendar classification.
 */
data class DayOverrideChange(
    val planId: String,
    val date: String,
    /** Revision the editor loaded; a mismatch with the committed revision is a conflict. */
    val expectedDayRevision: Long,
    val replacement: SingleDayOverride?,
) {
    companion object {
        /** Convenience for callers that already hold the committed revision. */
        fun replacing(planId: String, date: String, expectedDayRevision: Long, replacement: SingleDayOverride?) =
            DayOverrideChange(planId, date, expectedDayRevision, replacement)
    }
}

/** Outcome of a single-day write as the page sees it, including the real registration state. */
sealed interface DayOverrideSaveResult {
    data class Success(
        val stored: SingleDayOverride?,
        val dayRevision: Long,
        val registration: DayRegistrationState,
    ) : DayOverrideSaveResult

    data class Failure(
        val code: DayOverrideFailureCode,
        val message: String,
    ) : DayOverrideSaveResult
}

/** Why a single-day write did not commit. */
enum class DayOverrideFailureCode {
    /** The editor held an outdated day revision; nothing was written. */
    CONFLICT,

    /** The target plan no longer exists. */
    PLAN_NOT_FOUND,

    /** The draft itself is invalid; the page validates before calling the coordinator. */
    INVALID_DRAFT,

    /** The platform rejected the candidate registration; stored values are unchanged. */
    REGISTRATION_FAILED,

    /** A storage or commit step failed; stored values are unchanged. */
    STORAGE_FAILED,
}

/** What happened to the local instance while the day override committed. */
enum class DayRegistrationState {
    /** A new instance was registered for the changed date. */
    SCHEDULED,

    /** The plan is disabled or has no next instance, so nothing was registered. */
    NOT_ARMED,

    /** The date change did not require a new registration. */
    UNCHANGED,
}

/** Stored override plus the independently persisted revision of that plan and date. */
data class DayOverrideState(
    val override: SingleDayOverride?,
    val committedRevision: Long,
    /** Target date in ISO form; present even when the override row was removed. */
    val date: String,
)

/** Committed day revision of one plan and date; the queue and evaluation read this value. */
data class DayRevision(
    val planId: String,
    val date: String,
    val committedRevision: Long,
)
