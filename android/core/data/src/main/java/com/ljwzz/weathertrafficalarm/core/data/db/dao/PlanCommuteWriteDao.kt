package com.ljwzz.weathertrafficalarm.core.data.db.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import androidx.room3.Upsert
import com.ljwzz.weathertrafficalarm.core.data.db.entity.AlarmPlanEntity
import com.ljwzz.weathertrafficalarm.core.data.db.entity.PlanCommuteOverrideEntity

/** Writes a plan and its commute mutation in one Room write transaction. */
@Dao
abstract class PlanCommuteWriteDao {
    @Upsert
    protected abstract suspend fun upsertPlan(plan: AlarmPlanEntity)

    @Update
    protected abstract suspend fun updatePlan(plan: AlarmPlanEntity)

    @Upsert
    protected abstract suspend fun upsertOverride(override: PlanCommuteOverrideEntity)

    @Query("DELETE FROM plan_commute_overrides WHERE plan_id = :planId")
    protected abstract suspend fun deleteOverride(planId: String)

    @Transaction
    open suspend fun upsertPlanAndDeleteOverride(plan: AlarmPlanEntity) {
        upsertPlan(plan)
        deleteOverride(plan.id)
    }

    @Transaction
    open suspend fun upsertPlanAndOverride(plan: AlarmPlanEntity, override: PlanCommuteOverrideEntity) {
        upsertPlan(plan)
        upsertOverride(override)
    }

    @Transaction
    open suspend fun updatePlanAndDeleteOverride(plan: AlarmPlanEntity) {
        updatePlan(plan)
        deleteOverride(plan.id)
    }

    @Transaction
    open suspend fun updatePlanAndOverride(plan: AlarmPlanEntity, override: PlanCommuteOverrideEntity) {
        updatePlan(plan)
        upsertOverride(override)
    }
}
