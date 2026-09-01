package com.ljwzz.weathertrafficalarm.core.data.db.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.ljwzz.weathertrafficalarm.core.data.db.entity.PlanCommuteOverrideEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlanCommuteOverrideDao {
    @Upsert
    suspend fun upsert(override: PlanCommuteOverrideEntity)

    @Query("SELECT * FROM plan_commute_overrides WHERE plan_id = :planId")
    suspend fun getByPlanId(planId: String): PlanCommuteOverrideEntity?

    @Query("SELECT * FROM plan_commute_overrides WHERE plan_id = :planId")
    fun observeByPlanId(planId: String): Flow<PlanCommuteOverrideEntity?>

    @Query("DELETE FROM plan_commute_overrides WHERE plan_id = :planId")
    suspend fun deleteByPlanId(planId: String)
}
