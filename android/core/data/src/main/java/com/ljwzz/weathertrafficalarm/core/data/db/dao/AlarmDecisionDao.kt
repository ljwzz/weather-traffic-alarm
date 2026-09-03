package com.ljwzz.weathertrafficalarm.core.data.db.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.ljwzz.weathertrafficalarm.core.data.db.entity.AlarmDecisionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDecisionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(decision: AlarmDecisionEntity)

    @Transaction
    suspend fun saveIfPlanExists(decision: AlarmDecisionEntity): Boolean {
        if (!planExists(decision.planId)) return false
        upsert(decision)
        return true
    }

    @Query("SELECT EXISTS(SELECT 1 FROM alarm_plans WHERE id = :planId)")
    suspend fun planExists(planId: String): Boolean

    @Query("SELECT * FROM alarm_decisions ORDER BY target_date DESC, generated_at DESC")
    fun observeAll(): Flow<List<AlarmDecisionEntity>>

    @Query("SELECT * FROM alarm_decisions WHERE decision_id = :decisionId")
    suspend fun getById(decisionId: String): AlarmDecisionEntity?

    @Query("SELECT * FROM alarm_decisions WHERE plan_id = :planId ORDER BY target_date DESC, generated_at DESC")
    suspend fun getByPlanId(planId: String): List<AlarmDecisionEntity>

    @Query("SELECT * FROM alarm_decisions WHERE plan_id = :planId AND target_date = :targetDate ORDER BY generated_at DESC LIMIT 1")
    suspend fun getByPlanIdAndDate(planId: String, targetDate: String): AlarmDecisionEntity?

    @Query("DELETE FROM alarm_decisions WHERE generated_at < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long)

    @Query("DELETE FROM alarm_decisions WHERE plan_id = :planId")
    suspend fun deleteByPlanId(planId: String)
}
