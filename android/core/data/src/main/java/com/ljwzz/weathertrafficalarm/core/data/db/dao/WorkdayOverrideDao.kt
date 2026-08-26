package com.ljwzz.weathertrafficalarm.core.data.db.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.ljwzz.weathertrafficalarm.core.data.db.entity.WorkdayOverrideEntity

@Dao
interface WorkdayOverrideDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(override: WorkdayOverrideEntity)

    @Query("SELECT * FROM workday_overrides WHERE plan_id = :planId")
    suspend fun getByPlanId(planId: String): List<WorkdayOverrideEntity>

    @Query("SELECT * FROM workday_overrides WHERE plan_id = :planId AND date = :date LIMIT 1")
    suspend fun getByPlanIdAndDate(planId: String, date: String): WorkdayOverrideEntity?

    @Query("DELETE FROM workday_overrides WHERE plan_id = :planId AND date = :date")
    suspend fun deleteByPlanIdAndDate(planId: String, date: String)

    @Query("DELETE FROM workday_overrides WHERE plan_id = :planId")
    suspend fun deleteByPlanId(planId: String)
}
