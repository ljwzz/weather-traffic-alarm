package com.ljwzz.weathertrafficalarm.core.data.repository

import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettings
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import javax.inject.Inject
import javax.inject.Singleton

enum class CommuteSource { GLOBAL, PLAN_OVERRIDE }

data class EffectiveCommute(
    val origin: PlaceRef,
    val destination: PlaceRef,
    val commuteMode: CommuteMode,
    val source: CommuteSource,
)

/** Resolves map-ready coordinates without treating legacy text-only favorites as locations. */
@Singleton
class EffectiveCommuteResolver @Inject constructor(
    private val overrides: PlanCommuteOverrideRepository,
) {
    fun resolveGlobal(settings: LocalSettings): EffectiveCommute? {
        val origin = settings.favorites.firstOrNull { it.id == settings.originId }?.placeRef
        val destination = settings.favorites.firstOrNull { it.id == settings.destinationId }?.placeRef
        if (origin == null || destination == null || origin == destination) return null
        return EffectiveCommute(origin, destination, settings.commuteMode, CommuteSource.GLOBAL)
    }

    suspend fun resolveForPlan(planId: String, settings: LocalSettings): EffectiveCommute? {
        val override = overrides.getByPlanId(planId)
        return override?.let {
            EffectiveCommute(it.origin, it.destination, it.commuteMode, CommuteSource.PLAN_OVERRIDE)
        } ?: resolveGlobal(settings)
    }
}
