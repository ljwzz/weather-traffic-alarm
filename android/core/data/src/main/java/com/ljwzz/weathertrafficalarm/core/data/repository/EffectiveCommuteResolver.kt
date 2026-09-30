package com.ljwzz.weathertrafficalarm.core.data.repository

import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettings
import com.ljwzz.weathertrafficalarm.core.model.CommuteResolution
import com.ljwzz.weathertrafficalarm.core.model.CommuteSource
import com.ljwzz.weathertrafficalarm.core.model.SingleDayOverride
import javax.inject.Inject
import javax.inject.Singleton

/** Effective commute for the pre-N004 callers that only know a plan. */
typealias EffectiveCommute = CommuteResolution

/**
 * Resolves map-ready coordinates without treating legacy text-only favorites as locations.
 * The single-day override wins over the plan override, which wins over the global pair.
 */
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

    /**
     * Resolves the target date for one plan. A day-level combination replaces the plan and
     * global pair as a whole; an incomplete combination falls through to the next tier
     * instead of mixing origins from different tiers.
     */
    suspend fun resolveForPlanDate(
        planId: String,
        date: String,
        settings: LocalSettings,
        dayOverride: SingleDayOverride? = null,
    ): EffectiveCommute? {
        if (dayOverride != null &&
            dayOverride.planId == planId &&
            dayOverride.date == date &&
            dayOverride.hasCompleteCommute
        ) {
            return EffectiveCommute(
                origin = requireNotNull(dayOverride.origin),
                destination = requireNotNull(dayOverride.destination),
                commuteMode = requireNotNull(dayOverride.commuteMode),
                source = CommuteSource.DAY_OVERRIDE,
            )
        }
        return resolveForPlan(planId, settings)
    }
}
