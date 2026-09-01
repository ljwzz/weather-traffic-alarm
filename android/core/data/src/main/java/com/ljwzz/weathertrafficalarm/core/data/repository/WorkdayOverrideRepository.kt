package com.ljwzz.weathertrafficalarm.core.data.repository

import com.ljwzz.weathertrafficalarm.core.data.db.dao.WorkdayOverrideDao
import com.ljwzz.weathertrafficalarm.core.data.mapper.toDomain
import com.ljwzz.weathertrafficalarm.core.data.mapper.toEntity
import com.ljwzz.weathertrafficalarm.core.model.WorkdayOverride
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkdayOverrideRepository @Inject constructor(
    private val overrideDao: WorkdayOverrideDao,
) {
    suspend fun getForPlan(planId: String): List<WorkdayOverride> =
        overrideDao.getByPlanId(planId).map { it.toDomain() }

    fun observeForPlan(planId: String): Flow<List<WorkdayOverride>> =
        overrideDao.observeForPlan(planId).map { entities -> entities.map { it.toDomain() } }

    suspend fun save(override: WorkdayOverride) {
        overrideDao.upsert(override.toEntity())
    }

    suspend fun delete(planId: String, date: String) {
        overrideDao.deleteByPlanIdAndDate(planId, date)
    }
}
