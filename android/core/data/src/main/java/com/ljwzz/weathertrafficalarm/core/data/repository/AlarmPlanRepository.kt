package com.ljwzz.weathertrafficalarm.core.data.repository

import com.ljwzz.weathertrafficalarm.core.data.db.dao.AlarmPlanDao
import com.ljwzz.weathertrafficalarm.core.data.db.dao.PlanCommuteWriteDao
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
    private val planCommuteWriteDao: PlanCommuteWriteDao? = null,
) {

    fun observeAll(): Flow<List<AlarmPlan>> =
        planDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    suspend fun getById(planId: String): AlarmPlan? =
        planDao.getById(planId)?.toDomain()

    suspend fun save(
        plan: AlarmPlan,
        commuteOverrideMutation: CommuteOverrideMutation? = null,
    ): AlarmPlan {
        val withRevision = plan.withRevisionIncremented()
        persistSave(withRevision, commuteOverrideMutation)
        return withRevision
    }

    suspend fun update(
        plan: AlarmPlan,
        commuteOverrideMutation: CommuteOverrideMutation? = null,
    ) {
        persistUpdate(plan, commuteOverrideMutation)
    }

    suspend fun deleteById(planId: String) {
        planDao.deleteById(planId)
    }

    suspend fun enable(planId: String) {
        val plan = planDao.getById(planId) ?: return
        planDao.upsert(plan.copy(enabled = true, updatedAt = System.currentTimeMillis()))
    }

    suspend fun disable(planId: String) {
        val plan = planDao.getById(planId) ?: return
        planDao.upsert(plan.copy(enabled = false, updatedAt = System.currentTimeMillis()))
    }

    private suspend fun persistSave(plan: AlarmPlan, mutation: CommuteOverrideMutation?) {
        if (mutation == null) {
            planDao.upsert(plan.toEntity())
            return
        }
        val dao = requireNotNull(planCommuteWriteDao) {
            "PlanCommuteWriteDao is required when saving a commute override mutation"
        }
        when (mutation) {
            CommuteOverrideMutation.Reset -> dao.upsertPlanAndDeleteOverride(plan.toEntity())
            is CommuteOverrideMutation.Replace -> {
                require(mutation.override.planId == plan.id) { "commute override must belong to the plan being saved" }
                dao.upsertPlanAndOverride(plan.toEntity(), mutation.override.toEntity())
            }
        }
    }

    private suspend fun persistUpdate(plan: AlarmPlan, mutation: CommuteOverrideMutation?) {
        if (mutation == null) {
            planDao.update(plan.toEntity())
            return
        }
        val dao = requireNotNull(planCommuteWriteDao) {
            "PlanCommuteWriteDao is required when saving a commute override mutation"
        }
        when (mutation) {
            CommuteOverrideMutation.Reset -> dao.updatePlanAndDeleteOverride(plan.toEntity())
            is CommuteOverrideMutation.Replace -> {
                require(mutation.override.planId == plan.id) { "commute override must belong to the plan being saved" }
                dao.updatePlanAndOverride(plan.toEntity(), mutation.override.toEntity())
            }
        }
    }
}
