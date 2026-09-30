package com.ljwzz.weathertrafficalarm.core.data.repository

import com.ljwzz.weathertrafficalarm.core.data.db.dao.WorkdayOverrideDao
import com.ljwzz.weathertrafficalarm.core.data.db.dao.WorkdayOverrideWriteDao
import com.ljwzz.weathertrafficalarm.core.data.mapper.toDomain
import com.ljwzz.weathertrafficalarm.core.data.mapper.toEntity
import com.ljwzz.weathertrafficalarm.core.model.SingleDayOverride
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and writes single-day overrides for one plan and date. Saving and undoing are
 * scoped to the target row and return the persisted day revision, or `null` when the
 * date now inherits everything again.
 */
@Singleton
class WorkdayOverrideRepository @Inject constructor(
    private val overrideDao: WorkdayOverrideDao,
    private val writeDao: WorkdayOverrideWriteDao,
) {
    suspend fun getForPlan(planId: String): List<SingleDayOverride> =
        overrideDao.getByPlanId(planId).map { it.toDomain() }

    fun observeForPlan(planId: String): Flow<List<SingleDayOverride>> =
        overrideDao.observeForPlan(planId).map { entities -> entities.map { it.toDomain() } }

    suspend fun getForPlanDate(planId: String, date: String): SingleDayOverride? =
        overrideDao.getByPlanIdAndDate(planId, date)?.toDomain()

    /**
     * Persists the day override and returns the row with its incremented day revision.
     * Absent optional fields are taken from the row already stored for the same plan and
     * date, so saving one field keeps the other day-level values the user set earlier.
     */
    suspend fun save(override: SingleDayOverride): SingleDayOverride {
        val existing = getForPlanDate(override.planId, override.date)
        val replacesCommute = override.origin != null || override.destination != null || override.commuteMode != null
        val merged = override.copy(
            status = override.status ?: existing?.status,
            wakeLocalTime = override.wakeLocalTime ?: existing?.wakeLocalTime,
            arrivalLocalTime = override.arrivalLocalTime ?: existing?.arrivalLocalTime,
            preparationMinutes = override.preparationMinutes ?: existing?.preparationMinutes,
            weatherProfile = override.weatherProfile ?: existing?.weatherProfile,
            // The commute combination is one unit: a new combination replaces it whole,
            // otherwise the stored combination is retained unchanged.
            origin = if (replacesCommute) override.origin else existing?.origin,
            destination = if (replacesCommute) override.destination else existing?.destination,
            commuteMode = if (replacesCommute) override.commuteMode else existing?.commuteMode,
        )
        return writeDao.saveIncrementingRevision(merged.toEntity()).toDomain()
    }

    /**
     * Removes the day override. Returns the removed day revision, or `null` when the
     * date already inherited everything, so repeated undo stays idempotent.
     */
    suspend fun delete(planId: String, date: String): Long? =
        writeDao.deleteRowReportingRevision(planId, date)

    /** Writes the row verbatim, including its day revision; used to keep a failed edit recoverable. */
    suspend fun restore(override: SingleDayOverride) {
        writeDao.restore(override.toEntity())
    }
}
