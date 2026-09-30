package com.ljwzz.weathertrafficalarm.core.data.db.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.ljwzz.weathertrafficalarm.core.data.db.entity.DayOverrideCommitEntity
import com.ljwzz.weathertrafficalarm.core.data.db.entity.WorkdayDayRevisionEntity
import com.ljwzz.weathertrafficalarm.core.data.db.entity.WorkdayOverrideEntity
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceState
import kotlinx.coroutines.flow.Flow

/** Override row plus the independently persisted revision, read from one transaction snapshot. */
data class DayOverrideCommitState(
    val override: WorkdayOverrideEntity?,
    val committedRevision: Long,
    val lastIssuedRevision: Long,
    val date: String,
)

/** One allocated candidate revision with its recoverable change record. */
data class DayOverrideCandidateRow(
    val changeId: String,
    val candidateRevision: Long,
    val previousRevision: Long,
)

/**
 * Atomic single-day writes. Allocation, the committed revision, the override row and the
 * occurrence states of one change are written in one transaction, so a failure never leaves a
 * half-applied day. Reads of the override row and its revision use the same transaction.
 */
@Dao
abstract class DayOverrideCommitDao {

    @Query("SELECT * FROM workday_overrides WHERE plan_id = :planId AND date = :date LIMIT 1")
    protected abstract suspend fun overrideRow(planId: String, date: String): WorkdayOverrideEntity?

    @Query("SELECT * FROM workday_day_revisions WHERE plan_id = :planId AND date = :date LIMIT 1")
    protected abstract suspend fun revisionRow(planId: String, date: String): WorkdayDayRevisionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertOverride(override: WorkdayOverrideEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertRevision(revision: WorkdayDayRevisionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertCommit(commit: DayOverrideCommitEntity)

    @Query("DELETE FROM workday_overrides WHERE plan_id = :planId AND date = :date")
    protected abstract suspend fun deleteOverride(planId: String, date: String)

    @Query("DELETE FROM day_override_commits WHERE change_id = :changeId")
    protected abstract suspend fun deleteCommit(changeId: String)

    @Query("SELECT * FROM day_override_commits WHERE change_id = :changeId LIMIT 1")
    protected abstract suspend fun commitRow(changeId: String): DayOverrideCommitEntity?

    @Query("UPDATE day_override_commits SET published = 1 WHERE change_id = :changeId")
    protected abstract suspend fun markPublished(changeId: String)

    @Query("UPDATE alarm_occurrences SET state = :state, updated_at = :now WHERE occurrence_id = :occurrenceId")
    protected abstract suspend fun updateOccurrenceState(
        occurrenceId: String,
        state: OccurrenceState,
        now: Long,
    )

    @Query(
        "UPDATE alarm_occurrences SET day_revision = :dayRevision, updated_at = :now " +
            "WHERE occurrence_id = :occurrenceId",
    )
    protected abstract suspend fun updateOccurrenceDayRevision(
        occurrenceId: String,
        dayRevision: Long,
        now: Long,
    )

    /** Every recoverable change, oldest first; recovery replays them in allocation order. */
    @Query("SELECT * FROM day_override_commits ORDER BY created_at ASC")
    abstract suspend fun pendingCommits(): List<DayOverrideCommitEntity>

    /** Committed day revisions of one plan; the scheduler observes this to detect date changes. */
    @Query("SELECT * FROM workday_day_revisions WHERE plan_id = :planId")
    abstract fun observeRevisions(planId: String): Flow<List<WorkdayDayRevisionEntity>>

    @Query("SELECT * FROM workday_day_revisions WHERE plan_id = :planId")
    abstract suspend fun revisions(planId: String): List<WorkdayDayRevisionEntity>

    /** Detailed read used by the editor, evaluation and the scheduler. */
    @Transaction
    open suspend fun readState(planId: String, date: String): DayOverrideCommitState {
        val revision = revisionRow(planId, date)
        val override = overrideRow(planId, date)
        return DayOverrideCommitState(
            override = override,
            committedRevision = revision?.committedRevision ?: override?.dayRevision ?: 0L,
            lastIssuedRevision = maxOf(revision?.lastIssuedRevision ?: 0L, override?.dayRevision ?: 0L),
            date = date,
        )
    }

    /**
     * Reserves the next revision for [planId] and [date] and stores the recoverable change.
     * The committed revision is untouched until [commitCandidate] runs, and a discarded
     * candidate leaves its number unused (a hole) instead of handing it out twice.
     */
    @Transaction
    open suspend fun allocateCandidate(
        changeId: String,
        planId: String,
        date: String,
        override: WorkdayOverrideEntity?,
        candidateOccurrenceId: String?,
        cancelledOccurrenceIds: List<String>,
        now: Long,
    ): DayOverrideCandidateRow {
        val state = readState(planId, date)
        val candidate = maxOf(state.committedRevision, state.lastIssuedRevision) + 1L
        upsertRevision(
            WorkdayDayRevisionEntity(
                planId = planId,
                date = date,
                committedRevision = state.committedRevision,
                lastIssuedRevision = candidate,
            ),
        )
        upsertCommit(
            DayOverrideCommitEntity.of(
                changeId = changeId,
                planId = planId,
                date = date,
                candidateRevision = candidate,
                previousRevision = state.committedRevision,
                candidateOccurrenceId = candidateOccurrenceId,
                cancelledOccurrenceIds = cancelledOccurrenceIds,
                published = false,
                createdAt = now,
                override = override,
            ),
        )
        return DayOverrideCandidateRow(changeId, candidate, state.committedRevision)
    }

    /** Records that the device-protected snapshot and commit credential were published. */
    @Transaction
    open suspend fun markCandidatePublished(changeId: String) {
        markPublished(changeId)
    }

    /**
     * Publishes the candidate as the committed revision and applies the occurrence transition
     * of the same change in one transaction. Returns the committed state, or `null` when the
     * change was already committed (idempotent replay after a crash).
     */
    @Transaction
    open suspend fun commitCandidate(
        changeId: String,
        revisedOccurrenceIds: List<String>,
        now: Long,
    ): DayOverrideCommitState? {
        val commit = commitRow(changeId) ?: return null
        if (commit.isDeletion) {
            deleteOverride(commit.planId, commit.date)
        } else {
            upsertOverride(commit.toOverrideEntity())
        }
        val previous = revisionRow(commit.planId, commit.date)
        upsertRevision(
            WorkdayDayRevisionEntity(
                planId = commit.planId,
                date = commit.date,
                committedRevision = commit.candidateRevision,
                lastIssuedRevision = maxOf(
                    previous?.lastIssuedRevision ?: 0L,
                    commit.candidateRevision,
                    previous?.committedRevision ?: 0L,
                ),
            ),
        )
        commit.cancelledIds.forEach { updateOccurrenceState(it, OccurrenceState.CANCELLED, now) }
        revisedOccurrenceIds.forEach { updateOccurrenceDayRevision(it, commit.candidateRevision, now) }
        commit.candidateOccurrenceId?.let { updateOccurrenceState(it, OccurrenceState.SCHEDULED, now) }
        deleteCommit(changeId)
        return readState(commit.planId, commit.date)
    }

    /**
     * Drops an unpublished or compensated candidate. Its allocated number stays unused, and the
     * candidate occurrence is failed so it can never become an effective instance.
     */
    @Transaction
    open suspend fun discardCandidate(changeId: String, failedOccurrenceIds: List<String>, now: Long) {
        failedOccurrenceIds.forEach { updateOccurrenceState(it, OccurrenceState.FAILED, now) }
        deleteCommit(changeId)
    }
}
