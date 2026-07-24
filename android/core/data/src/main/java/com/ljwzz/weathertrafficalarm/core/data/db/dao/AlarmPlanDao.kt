package com.ljwzz.weathertrafficalarm.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.ljwzz.weathertrafficalarm.core.data.db.entity.AlarmPlanEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmPlanDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(plan: AlarmPlanEntity)

    @Transaction
    suspend fun saveWithRevisionUpdate(plan: AlarmPlanEntity) {
        upsert(plan)
    }

    @Update
    suspend fun update(plan: AlarmPlanEntity)

    @Query("SELECT * FROM alarm_plans WHERE id = :planId")
    suspend fun getById(planId: String): AlarmPlanEntity?

    @Query("SELECT * FROM alarm_plans ORDER BY created_at DESC")
    fun observeAll(): Flow<List<AlarmPlanEntity>>

    @Query("SELECT * FROM alarm_plans ORDER BY created_at DESC")
    suspend fun getAll(): List<AlarmPlanEntity>

    @Query("DELETE FROM alarm_plans WHERE id = :planId")
    suspend fun deleteById(planId: String)

    @Query("DELETE FROM alarm_plans")
    suspend fun deleteAll()
}
