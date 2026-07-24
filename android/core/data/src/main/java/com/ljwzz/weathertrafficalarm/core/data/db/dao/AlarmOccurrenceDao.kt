package com.ljwzz.weathertrafficalarm.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.ljwzz.weathertrafficalarm.core.data.db.entity.AlarmOccurrenceEntity

@Dao
interface AlarmOccurrenceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(occurrence: AlarmOccurrenceEntity)

    @Transaction
    suspend fun createOccurrence(occurrence: AlarmOccurrenceEntity) {
        upsert(occurrence)
    }

    @Update
    suspend fun update(occurrence: AlarmOccurrenceEntity)

    @Query("SELECT * FROM alarm_occurrences WHERE occurrence_id = :occurrenceId")
    suspend fun getById(occurrenceId: String): AlarmOccurrenceEntity?

    @Query("SELECT * FROM alarm_occurrences WHERE plan_id = :planId AND target_date = :targetDate LIMIT 1")
    suspend fun getByPlanIdAndDate(planId: String, targetDate: String): AlarmOccurrenceEntity?

    @Query("SELECT * FROM alarm_occurrences WHERE plan_id = :planId ORDER BY target_date DESC")
    suspend fun getByPlanId(planId: String): List<AlarmOccurrenceEntity>

    @Query("UPDATE alarm_occurrences SET state = :state, updated_at = :updatedAt WHERE occurrence_id = :occurrenceId")
    suspend fun updateState(occurrenceId: String, state: String, updatedAt: Long)

    @Query("DELETE FROM alarm_occurrences WHERE plan_id = :planId")
    suspend fun deleteByPlanId(planId: String)
}
