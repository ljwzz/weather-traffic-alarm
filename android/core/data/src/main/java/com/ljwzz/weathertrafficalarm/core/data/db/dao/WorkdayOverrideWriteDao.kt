package com.ljwzz.weathertrafficalarm.core.data.db.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import com.ljwzz.weathertrafficalarm.core.data.db.entity.WorkdayOverrideEntity

/**
 * Atomic single-day writes for `workday_overrides`. Each save reads the current row and
 * writes the next day revision inside one transaction, so concurrent saves of the same
 * day cannot lose an increment and other dates are never touched.
 */
@Dao
abstract class WorkdayOverrideWriteDao {

    @Query("SELECT * FROM workday_overrides WHERE plan_id = :planId AND date = :date LIMIT 1")
    protected abstract suspend fun currentRow(planId: String, date: String): WorkdayOverrideEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertEntity(override: WorkdayOverrideEntity)

    @Query("DELETE FROM workday_overrides WHERE plan_id = :planId AND date = :date")
    protected abstract suspend fun deleteRow(planId: String, date: String)

    /** Persists [override] and returns the stored row with its incremented day revision. */
    @Transaction
    open suspend fun saveIncrementingRevision(override: WorkdayOverrideEntity): WorkdayOverrideEntity {
        val next = override.copy(dayRevision = (currentRow(override.planId, override.date)?.dayRevision ?: 0L) + 1L)
        upsertEntity(next)
        return next
    }

    /** Deletes the row and reports the day revision that was removed, if any. */
    @Transaction
    open suspend fun deleteRowReportingRevision(planId: String, date: String): Long? {
        val existing = currentRow(planId, date) ?: return null
        deleteRow(planId, date)
        return existing.dayRevision
    }

    /** Restores a previously captured row verbatim, including its day revision. */
    @Transaction
    open suspend fun restore(override: WorkdayOverrideEntity) {
        upsertEntity(override)
    }
}
