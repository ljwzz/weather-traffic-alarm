package com.ljwzz.weathertrafficalarm.core.data.repository

import com.ljwzz.weathertrafficalarm.core.data.db.dao.PlanCommuteOverrideDao
import com.ljwzz.weathertrafficalarm.core.data.db.entity.PlanCommuteOverrideEntity
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class PlanCommuteOverride(
    val planId: String,
    val origin: PlaceRef,
    val destination: PlaceRef,
    val commuteMode: CommuteMode,
    val updatedAt: Long,
) {
    init {
        require(origin != destination) { "origin and destination must differ" }
    }
}

@Singleton
class PlanCommuteOverrideRepository @Inject constructor(
    private val overrideDao: PlanCommuteOverrideDao,
) {
    suspend fun getByPlanId(planId: String): PlanCommuteOverride? =
        overrideDao.getByPlanId(planId)?.toDomain()

    fun observeByPlanId(planId: String): Flow<PlanCommuteOverride?> =
        overrideDao.observeByPlanId(planId).map { it?.toDomain() }

    suspend fun save(override: PlanCommuteOverride) {
        overrideDao.upsert(override.toEntity())
    }

    suspend fun deleteByPlanId(planId: String) {
        overrideDao.deleteByPlanId(planId)
    }
}

private fun PlanCommuteOverrideEntity.toDomain() = PlanCommuteOverride(
    planId = planId,
    origin = origin,
    destination = destination,
    commuteMode = commuteMode,
    updatedAt = updatedAt,
)

private fun PlanCommuteOverride.toEntity() = PlanCommuteOverrideEntity(
    planId = planId,
    origin = origin,
    destination = destination,
    commuteMode = commuteMode,
    updatedAt = updatedAt,
)
