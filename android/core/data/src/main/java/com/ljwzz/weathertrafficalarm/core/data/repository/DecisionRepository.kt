package com.ljwzz.weathertrafficalarm.core.data.repository

import com.ljwzz.weathertrafficalarm.core.data.db.dao.AlarmDecisionDao
import com.ljwzz.weathertrafficalarm.core.data.mapper.toDomain
import com.ljwzz.weathertrafficalarm.core.data.mapper.toEntity
import com.ljwzz.weathertrafficalarm.core.model.AlarmDecision
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DecisionRepository @Inject constructor(
    private val decisionDao: AlarmDecisionDao,
) {

    fun observeAll(): Flow<List<AlarmDecision>> =
        decisionDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    suspend fun getById(decisionId: String): AlarmDecision? =
        decisionDao.getById(decisionId)?.toDomain()

    suspend fun save(decision: AlarmDecision): Boolean =
        decisionDao.saveIfPlanExists(decision.toEntity())

    suspend fun getByPlanId(planId: String): List<AlarmDecision> =
        decisionDao.getByPlanId(planId).map { it.toDomain() }

    suspend fun getByPlanIdAndDate(planId: String, targetDate: String): AlarmDecision? =
        decisionDao.getByPlanIdAndDate(planId, targetDate)?.toDomain()

    suspend fun deleteOlderThan(cutoffMillis: Long) {
        decisionDao.deleteOlderThan(cutoffMillis)
    }

    suspend fun deleteByPlanId(planId: String) {
        decisionDao.deleteByPlanId(planId)
    }
}
