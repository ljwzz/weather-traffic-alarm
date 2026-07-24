package com.ljwzz.weathertrafficalarm.core.data.repository

import com.ljwzz.weathertrafficalarm.core.data.db.dao.AlarmOccurrenceDao
import com.ljwzz.weathertrafficalarm.core.data.mapper.toDomain
import com.ljwzz.weathertrafficalarm.core.data.mapper.toEntity
import com.ljwzz.weathertrafficalarm.core.model.AlarmOccurrence
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OccurrenceRepository @Inject constructor(
    private val occurrenceDao: AlarmOccurrenceDao,
) {

    suspend fun save(occurrence: AlarmOccurrence) {
        occurrenceDao.createOccurrence(occurrence.toEntity())
    }

    suspend fun getById(occurrenceId: String): AlarmOccurrence? =
        occurrenceDao.getById(occurrenceId)?.toDomain()

    suspend fun getByPlanIdAndDate(planId: String, targetDate: String): AlarmOccurrence? =
        occurrenceDao.getByPlanIdAndDate(planId, targetDate)?.toDomain()

    suspend fun getByPlanId(planId: String): List<AlarmOccurrence> =
        occurrenceDao.getByPlanId(planId).map { it.toDomain() }

    suspend fun updateState(occurrenceId: String, state: String, updatedAt: Long) {
        occurrenceDao.updateState(occurrenceId, state, updatedAt)
    }

    suspend fun deleteByPlanId(planId: String) {
        occurrenceDao.deleteByPlanId(planId)
    }
}
