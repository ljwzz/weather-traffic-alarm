package com.ljwzz.weathertrafficalarm.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ljwzz.weathertrafficalarm.core.data.db.entity.AlarmDecisionEntity

@Dao
interface AlarmDecisionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(decision: AlarmDecisionEntity)

    @Query("SELECT * FROM alarm_decisions WHERE plan_id = :planId ORDER BY generated_at DESC")
    suspend fun getByPlanId(planId: String): List<AlarmDecisionEntity>

    @Query("SELECT * FROM alarm_decisions WHERE plan_id = :planId AND target_date = :targetDate LIMIT 1")
    suspend fun getByPlanIdAndDate(planId: String, targetDate: String): AlarmDecisionEntity?

    @Query("DELETE FROM alarm_decisions WHERE generated_at < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long)

    @Query("DELETE FROM alarm_decisions WHERE plan_id = :planId")
    suspend fun deleteByPlanId(planId: String)
}
