package com.ljwzz.weathertrafficalarm.core.data.repository

import com.ljwzz.weathertrafficalarm.core.data.local.WorkdayCalendarRepository
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettings
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettingsStore
import com.ljwzz.weathertrafficalarm.core.data.preferences.WeatherBuffers
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.CommuteResolution
import com.ljwzz.weathertrafficalarm.core.model.DailyEvaluationFingerprint
import com.ljwzz.weathertrafficalarm.core.model.DailySettingsResolver
import com.ljwzz.weathertrafficalarm.core.model.DayStatus
import com.ljwzz.weathertrafficalarm.core.model.EffectiveDailySettings
import com.ljwzz.weathertrafficalarm.core.model.SingleDayOverride
import com.ljwzz.weathertrafficalarm.core.model.WeatherBufferProfile
import com.ljwzz.weathertrafficalarm.core.model.WeatherBufferProfiles
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Global weather profiles as the resolver consumes them. */
fun LocalSettings.weatherBufferProfiles(): WeatherBufferProfiles = WeatherBufferProfiles(
    workday = workdayWeatherBuffers.toProfile(),
    weekend = weekendWeatherBuffers.toProfile(),
    statutoryRest = holidayWeatherBuffers.toProfile(),
)

/** Buffers as the resolver consumes them. */
fun WeatherBuffers.toProfile(): WeatherBufferProfile = WeatherBufferProfile(lightMinutes, moderateMinutes, severeMinutes)

/**
 * Everything one evaluation, one queue decision and one page summary needs for a plan and date.
 *
 * [inherited] ignores the day override so the editor can show the real tier a value falls back
 * to; [effective] applies it. Both share the same calendar classification and committed day
 * revision, and [fingerprint] is the identity a submitted evaluation must still match when the
 * alarm coordinator commits it.
 */
data class DailyEvaluationInputs(
    val plan: AlarmPlan,
    val date: LocalDate,
    val settings: LocalSettings,
    val override: SingleDayOverride?,
    val committedRevision: Long,
    val calendarDays: Map<String, DayStatus>,
    val inherited: EffectiveDailySettings,
    val effective: EffectiveDailySettings,
    val inheritedCommute: CommuteResolution?,
    val effectiveCommute: CommuteResolution?,
) {
    val dayStatus: DayStatus get() = effective.classification.effectiveStatus

    val calendarSource: String
        get() = when {
            override?.status != null -> "PLAN_OVERRIDE"
            calendarDays.containsKey(date.toString()) -> "HOLIDAY_CN"
            else -> "WEEKDAY_FALLBACK"
        }

    val fingerprint: DailyEvaluationFingerprint
        get() = DailyEvaluationFingerprint.of(
            planId = plan.id,
            planRevision = plan.revision,
            date = date,
            dayRevision = committedRevision,
            values = fingerprintValues(),
        )

    /**
     * Complete input identity. Every value the evaluation actually uses is included, so a save,
     * undo, undo-then-recreate or unrelated configuration change invalidates an in-flight run.
     */
    private fun fingerprintValues(): List<Any?> = listOf(
        plan.revision, plan.enabled, plan.zoneId, plan.defaultWakeLocalTime, plan.arrivalLocalTime,
        plan.preparationMinutes, plan.maxAdvanceMinutes, plan.schedule, plan.routePolicy,
        plan.weatherRuleVersion, plan.commuteMode,
        effectiveCommute, inheritedCommute, override,
        effective.defaultWakeLocalTime, effective.arrivalLocalTime, effective.preparationMinutes,
        effective.weatherProfile, effective.classification.baseDayKind, effective.classification.source,
        settings.originId, settings.destinationId, settings.commuteMode,
        settings.workdayWeatherBuffers, settings.weekendWeatherBuffers, settings.holidayWeatherBuffers,
        settings.amapConsentGranted, settings.amapConsentPromptedVersion,
        calendarDays[date.toString()],
    )
}

/**
 * Sole composition point for per-date evaluation inputs. The page, the evaluation coordinator,
 * the alarm coordinator and the work scheduler all resolve through this class so they cannot
 * disagree about the effective values or the revision an input belongs to.
 */
@Singleton
class DailyEvaluationInputResolver @Inject constructor(
    private val plans: AlarmPlanRepository,
    private val settings: LocalSettingsStore,
    private val commutes: EffectiveCommuteResolver,
    private val overrides: WorkdayOverrideRepository,
    private val calendar: WorkdayCalendarRepository,
) {
    /** Resolves [date] for an already loaded plan. */
    suspend fun resolve(plan: AlarmPlan, date: LocalDate): DailyEvaluationInputs {
        val persisted = settings.loadInitial()
        val state = overrides.getState(plan.id, date.toString())
        return resolve(plan, date, persisted, state.override, state.committedRevision, calendar.statuses())
    }

    /** Re-reads the plan and resolves [date]; null when the plan no longer exists. */
    suspend fun resolveCurrent(planId: String, date: LocalDate): DailyEvaluationInputs? {
        val plan = plans.getById(planId) ?: return null
        return resolve(plan, date)
    }

    /** Resolves [date] with an explicit day override, used to project a not yet committed change. */
    suspend fun resolveWith(
        plan: AlarmPlan,
        date: LocalDate,
        override: SingleDayOverride?,
        committedRevision: Long,
    ): DailyEvaluationInputs {
        val persisted = settings.loadInitial()
        return resolve(plan, date, persisted, override, committedRevision, calendar.statuses())
    }

    private suspend fun resolve(
        plan: AlarmPlan,
        date: LocalDate,
        persisted: LocalSettings,
        override: SingleDayOverride?,
        committedRevision: Long,
        calendarDays: Map<String, DayStatus>,
    ): DailyEvaluationInputs {
        val profiles = persisted.weatherBufferProfiles()
        val inheritedCommute = commutes.resolveForPlan(plan.id, persisted)
        val effectiveCommute = commutes.resolveForPlanDate(plan.id, date.toString(), persisted, override)
        val inherited = DailySettingsResolver.resolve(
            plan = plan,
            date = date,
            override = null,
            officialDays = calendarDays,
            profiles = profiles,
            commute = inheritedCommute,
        ).copy(dayRevision = committedRevision)
        val effective = DailySettingsResolver.resolve(
            plan = plan,
            date = date,
            override = override,
            officialDays = calendarDays,
            profiles = profiles,
            commute = effectiveCommute,
        ).copy(dayRevision = committedRevision)
        return DailyEvaluationInputs(
            plan = plan,
            date = date,
            settings = persisted,
            override = override,
            committedRevision = committedRevision,
            calendarDays = calendarDays,
            inherited = inherited,
            effective = effective,
            inheritedCommute = inheritedCommute,
            effectiveCommute = effectiveCommute,
        )
    }
}
