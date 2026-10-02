package com.ljwzz.weathertrafficalarm.core.data.repository

import com.ljwzz.weathertrafficalarm.core.data.db.dao.DayOverrideCommitDao
import com.ljwzz.weathertrafficalarm.core.data.db.dao.WorkdayOverrideDao
import com.ljwzz.weathertrafficalarm.core.data.mapper.toDomain
import com.ljwzz.weathertrafficalarm.core.data.mapper.toEntity
import com.ljwzz.weathertrafficalarm.core.model.DayOverrideChange
import com.ljwzz.weathertrafficalarm.core.model.DayOverrideState
import com.ljwzz.weathertrafficalarm.core.model.DayRevision
import com.ljwzz.weathertrafficalarm.core.model.AlarmArmedState
import com.ljwzz.weathertrafficalarm.core.model.SingleDayOverride
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of reserving the next day revision for a change. */
sealed interface DayOverrideAllocation {
    data class Allocated(
        val changeId: String,
        val candidateRevision: Long,
        val previousRevision: Long,
    ) : DayOverrideAllocation

    /** The editor held an outdated revision; nothing was written. */
    data class Conflict(val committedRevision: Long) : DayOverrideAllocation
}

/** Recoverable change waiting for its aggregated commit. */
data class PendingDayOverrideChange(
    val changeId: String,
    val planId: String,
    val date: String,
    val candidateRevision: Long,
    val previousRevision: Long,
    val candidateOccurrenceId: String?,
    /** Occurrence ids this change invalidates; recovery replays the platform cleanup. */
    val cancelledOccurrenceIds: List<String>,
    val published: Boolean,
    /** Null means the committed change removes the override row. */
    val replacement: SingleDayOverride?,
)

/**
 * Reads and writes single-day overrides for one plan and date.
 *
 * Every write is a complete replacement snapshot ([SingleDayOverride]) that is committed through
 * an allocated candidate revision, so a failed or interrupted change never rewrites the stored
 * values. The revision lives in its own table and survives deleting the override row.
 */
@Singleton
class WorkdayOverrideRepository @Inject constructor(
    private val overrideDao: WorkdayOverrideDao,
    private val commitDao: DayOverrideCommitDao,
) {
    suspend fun getForPlan(planId: String): List<SingleDayOverride> =
        overrideDao.getByPlanId(planId).map { it.toDomain() }

    fun observeForPlan(planId: String): Flow<List<SingleDayOverride>> =
        overrideDao.observeForPlan(planId).map { entities -> entities.map { it.toDomain() } }

    suspend fun getForPlanDate(planId: String, date: String): SingleDayOverride? =
        overrideDao.getByPlanIdAndDate(planId, date)?.toDomain()

    /** Override row and committed revision from one transaction snapshot. */
    suspend fun getState(planId: String, date: String): DayOverrideState {
        val state = commitDao.readState(planId, date)
        return DayOverrideState(state.override?.toDomain(), state.committedRevision, date)
    }

    suspend fun committedRevision(planId: String, date: String): Long =
        commitDao.readState(planId, date).committedRevision

    fun observeRevisions(planId: String): Flow<List<DayRevision>> =
        commitDao.observeRevisions(planId).map { rows ->
            rows.map { DayRevision(it.planId, it.date, it.committedRevision) }
        }

    /**
     * Override plus committed revision for every date of [planId]. Dates whose override was
     * removed keep their revision, so observers still see the invalidation.
     */
    fun observeDayStates(planId: String): Flow<List<DayOverrideState>> =
        combine(overrideDao.observeForPlan(planId), commitDao.observeRevisions(planId)) { overrides, revisions ->
            val byDate = overrides.associateBy { it.date }
            val dates = byDate.keys + revisions.map { it.date }
            dates.sorted().map { date ->
                DayOverrideState(
                    override = byDate[date]?.toDomain(),
                    committedRevision = revisions.firstOrNull { it.date == date }?.committedRevision
                        ?: byDate[date]?.dayRevision
                        ?: 0L,
                    date = date,
                )
            }
        }

    /**
     * Reserves the next revision for the target date and stores the recoverable change. The
     * committed revision and the override row stay untouched until [commitCandidate] runs.
     */
    suspend fun allocateCandidate(
        changeId: String,
        planId: String,
        date: String,
        replacement: SingleDayOverride?,
        expectedDayRevision: Long,
        candidateOccurrenceId: String?,
        cancelledOccurrenceIds: List<String>,
        now: Long,
    ): DayOverrideAllocation {
        val state = commitDao.readState(planId, date)
        if (state.committedRevision != expectedDayRevision) {
            return DayOverrideAllocation.Conflict(state.committedRevision)
        }
        val row = commitDao.allocateCandidate(
            changeId = changeId,
            planId = planId,
            date = date,
            override = replacement?.takeUnless { it.isInheritingEverything }?.toEntity(),
            candidateOccurrenceId = candidateOccurrenceId,
            cancelledOccurrenceIds = cancelledOccurrenceIds,
            now = now,
        )
        return DayOverrideAllocation.Allocated(row.changeId, row.candidateRevision, row.previousRevision)
    }

    /** Records that the device-protected snapshot and commit credential are published. */
    suspend fun markCandidatePublished(changeId: String) = commitDao.markCandidatePublished(changeId)

    suspend fun abandonRegistration(changeId: String, occurrenceId: String, now: Long) =
        commitDao.abandonRegistration(changeId, occurrenceId, now)

    /**
     * Publishes the candidate as the committed revision and applies the occurrence transitions of
     * the same change in one transaction. Returns `null` when the change was already committed.
     */
    suspend fun commitCandidate(
        changeId: String,
        revisedOccurrenceIds: List<String> = emptyList(),
        now: Long,
        armedState: AlarmArmedState? = null,
        scheduleError: String? = null,
        candidateDayRevision: Long? = null,
    ): DayOverrideState? = commitDao.commitCandidate(
        changeId = changeId,
        revisedOccurrenceIds = revisedOccurrenceIds,
        now = now,
        armedState = armedState,
        scheduleError = scheduleError,
        candidateDayRevision = candidateDayRevision,
    )?.let { DayOverrideState(it.override?.toDomain(), it.committedRevision, it.date) }

    /** Drops a candidate without changing the committed values; its number is not reused. */
    suspend fun discardCandidate(changeId: String, failedOccurrenceIds: List<String>, now: Long) =
        commitDao.discardCandidate(changeId, failedOccurrenceIds, now)

    suspend fun pendingCandidates(): List<PendingDayOverrideChange> =
        commitDao.pendingCommits().map { row ->
            PendingDayOverrideChange(
                changeId = row.changeId,
                planId = row.planId,
                date = row.date,
                candidateRevision = row.candidateRevision,
                previousRevision = row.previousRevision,
                candidateOccurrenceId = row.candidateOccurrenceId,
                cancelledOccurrenceIds = row.cancelledIds,
                published = row.published,
                replacement = if (row.isDeletion) null else row.toOverrideEntity().toDomain(),
            )
        }

    suspend fun pendingCandidate(changeId: String): PendingDayOverrideChange? =
        pendingCandidates().firstOrNull { it.changeId == changeId }

    /**
     * Reserves and commits [change] in place, without registering an instance. The coordinator
     * uses it when the target date needs no new local instance, and fixtures use it to seed
     * storage. A stale [DayOverrideChange.expectedDayRevision] never writes.
     */
    suspend fun commitNow(change: DayOverrideChange, now: Long): DayOverrideCommit {
        val allocation = allocateCandidate(
            changeId = UUID.randomUUID().toString(),
            planId = change.planId,
            date = change.date,
            replacement = change.replacement,
            expectedDayRevision = change.expectedDayRevision,
            candidateOccurrenceId = null,
            cancelledOccurrenceIds = emptyList(),
            now = now,
        )
        return when (allocation) {
            is DayOverrideAllocation.Conflict -> DayOverrideCommit.Conflict(allocation.committedRevision)
            is DayOverrideAllocation.Allocated -> {
                val state = commitCandidate(allocation.changeId, now = now)
                    ?: return DayOverrideCommit.Conflict(committedRevision(change.planId, change.date))
                DayOverrideCommit.Committed(state)
            }
        }
    }
    /**
     * Commits [replacement] for the target date using the revision that is committed right now.
     * Fixtures, device tests and migration checks seed storage this way; the editor always passes
     * the revision it loaded so a stale draft cannot overwrite a newer one.
     */
    suspend fun commitCurrent(
        planId: String,
        date: String,
        replacement: SingleDayOverride?,
        now: Long,
    ): DayOverrideState? {
        val expected = committedRevision(planId, date)
        return when (val result = commitNow(DayOverrideChange(planId, date, expected, replacement), now)) {
            is DayOverrideCommit.Committed -> result.state
            is DayOverrideCommit.Conflict -> null
        }
    }
}

/** Result of an immediate single-day commit with no instance registration. */
sealed interface DayOverrideCommit {
    data class Committed(val state: DayOverrideState) : DayOverrideCommit

    data class Conflict(val committedRevision: Long) : DayOverrideCommit
}
