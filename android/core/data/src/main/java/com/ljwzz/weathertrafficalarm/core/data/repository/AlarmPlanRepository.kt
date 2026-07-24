package com.ljwzz.weathertrafficalarm.core.data.repository

import com.ljwzz.weathertrafficalarm.core.data.db.dao.AlarmPlanDao
import com.ljwzz.weathertrafficalarm.core.data.mapper.toDomain
import com.ljwzz.weathertrafficalarm.core.data.mapper.toEntity
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlarmPlanRepository @Inject constructor(
    private val planDao: AlarmPlanDao,
) {

    fun observeAll(): Flow<List<AlarmPlan>> =
        planDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    suspend fun getById(planId: String): AlarmPlan? =
        planDao.getById(planId)?.toDomain()

    suspend fun save(plan: AlarmPlan) {
        val withRevision = plan.withRevisionIncremented()
        planDao.upsert(withRevision.toEntity())
    }

    suspend fun update(plan: AlarmPlan) {
        planDao.update(plan.toEntity())
    }

    suspend fun deleteById(planId: String) {
        planDao.deleteById(planId)
    }

    suspend fun enable(planId: String) {
        val plan = planDao.getById(planId) ?: return
        planDao.upsert(plan.copy(enabled = true))
    }

    suspend fun disable(planId: String) {
        val plan = planDao.getById(planId) ?: return
        planDao.upsert(plan.copy(enabled = false))
    }
}
