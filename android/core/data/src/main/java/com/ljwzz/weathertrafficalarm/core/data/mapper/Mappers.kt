package com.ljwzz.weathertrafficalarm.core.data.mapper

import com.ljwzz.weathertrafficalarm.core.data.db.entity.AlarmDecisionEntity
import com.ljwzz.weathertrafficalarm.core.data.db.entity.AlarmEventEntity
import com.ljwzz.weathertrafficalarm.core.data.db.entity.AlarmOccurrenceEntity
import com.ljwzz.weathertrafficalarm.core.data.db.entity.AlarmPlanEntity
import com.ljwzz.weathertrafficalarm.core.data.db.entity.WorkdayOverrideEntity
import com.ljwzz.weathertrafficalarm.core.model.AlarmDecision
import com.ljwzz.weathertrafficalarm.core.model.AlarmEvent
import com.ljwzz.weathertrafficalarm.core.model.AlarmOccurrence
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.SingleDayOverride
import com.ljwzz.weathertrafficalarm.core.model.WeatherBufferProfile
import java.time.Instant

fun AlarmPlanEntity.toDomain(): AlarmPlan = AlarmPlan(
    id = id,
    revision = revision,
    name = name,
    enabled = enabled,
    zoneId = zoneId,
    defaultWakeLocalTime = defaultWakeLocalTime,
    arrivalLocalTime = arrivalLocalTime,
    preparationMinutes = preparationMinutes,
    maxAdvanceMinutes = maxAdvanceMinutes,
    commuteMode = commuteMode,
    origin = origin,
    destination = destination,
    waypoints = waypoints,
    routePolicy = routePolicy,
    weatherRuleVersion = weatherRuleVersion,
    sound = sound,
    vibration = vibration,
    snoozeMinutes = snoozeMinutes,
    schedule = schedule,
    armedState = armedState,
    scheduleError = scheduleError,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun AlarmPlan.toEntity(): AlarmPlanEntity = AlarmPlanEntity(
    id = id,
    revision = revision,
    name = name,
    enabled = enabled,
    zoneId = zoneId,
    defaultWakeLocalTime = defaultWakeLocalTime,
    arrivalLocalTime = arrivalLocalTime,
    preparationMinutes = preparationMinutes,
    maxAdvanceMinutes = maxAdvanceMinutes,
    commuteMode = commuteMode,
    origin = origin,
    destination = destination,
    waypoints = waypoints,
    routePolicy = routePolicy,
    weatherRuleVersion = weatherRuleVersion,
    sound = sound,
    vibration = vibration,
    snoozeMinutes = snoozeMinutes,
    schedule = schedule,
    armedState = armedState,
    scheduleError = scheduleError,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun AlarmOccurrenceEntity.toDomain(): AlarmOccurrence = AlarmOccurrence(
    occurrenceId = occurrenceId,
    planId = planId,
    planRevision = planRevision,
    targetDate = targetDate,
    scheduledWakeAt = scheduledWakeAt,
    state = state,
    decisionId = decisionId,
    kind = kind,
    parentOccurrenceId = parentOccurrenceId,
    updatedAt = updatedAt,
    dayRevision = dayRevision,
)

fun AlarmOccurrence.toEntity(): AlarmOccurrenceEntity = AlarmOccurrenceEntity(
    occurrenceId = occurrenceId,
    planId = planId,
    planRevision = planRevision,
    targetDate = targetDate,
    scheduledWakeAt = scheduledWakeAt,
    state = state,
    decisionId = decisionId,
    kind = kind,
    parentOccurrenceId = parentOccurrenceId,
    updatedAt = updatedAt,
    dayRevision = dayRevision,
)

fun AlarmDecisionEntity.toDomain(): AlarmDecision = AlarmDecision(
    decisionId = decisionId,
    planId = planId,
    planRevision = planRevision,
    targetDate = targetDate,
    workdayStatus = workdayStatus,
    estimatedDepartureAt = estimatedDepartureAt,
    commuteSeconds = commuteSeconds,
    weatherSeverity = weatherSeverity,
    weatherBufferMinutes = weatherBufferMinutes,
    recommendedWakeAt = recommendedWakeAt,
    routeProvider = routeProvider,
    routeProviderReportTime = routeProviderReportTime,
    weatherProvider = weatherProvider,
    weatherProviderReportTime = weatherProviderReportTime,
    weatherWindowStart = weatherWindowStart,
    weatherWindowEnd = weatherWindowEnd,
    fallbackReason = fallbackReason,
    insufficientAdvance = insufficientAdvance,
    generatedAt = Instant.ofEpochMilli(generatedAt).toString(),
    expiresAt = Instant.ofEpochMilli(expiresAt).toString(),
    evaluationOutcome = evaluationOutcome,
    failureReason = failureReason,
    attemptNumber = attemptNumber,
    applicationOutcome = applicationOutcome,
    preparationMinutes = preparationMinutes,
    defaultWakeAt = defaultWakeAt,
    actualWakeAt = actualWakeAt,
    calendarSource = calendarSource,
    weatherDataSource = weatherDataSource,
    arrivalLocalTime = arrivalLocalTime,
    dayRevision = dayRevision,
    planName = planName,
    zoneId = zoneId,
)

fun AlarmDecision.toEntity(): AlarmDecisionEntity = AlarmDecisionEntity(
    decisionId = decisionId,
    planId = planId,
    planRevision = planRevision,
    targetDate = targetDate,
    workdayStatus = workdayStatus,
    estimatedDepartureAt = estimatedDepartureAt,
    commuteSeconds = commuteSeconds,
    weatherSeverity = weatherSeverity,
    weatherBufferMinutes = weatherBufferMinutes,
    recommendedWakeAt = recommendedWakeAt,
    routeProvider = routeProvider,
    routeProviderReportTime = routeProviderReportTime,
    weatherProvider = weatherProvider,
    weatherProviderReportTime = weatherProviderReportTime,
    weatherWindowStart = weatherWindowStart,
    weatherWindowEnd = weatherWindowEnd,
    fallbackReason = fallbackReason,
    insufficientAdvance = insufficientAdvance,
    generatedAt = generatedAt.toEpochMillis("generatedAt"),
    expiresAt = expiresAt.toEpochMillis("expiresAt"),
    evaluationOutcome = evaluationOutcome,
    failureReason = failureReason,
    attemptNumber = attemptNumber,
    applicationOutcome = applicationOutcome,
    preparationMinutes = preparationMinutes,
    defaultWakeAt = defaultWakeAt,
    actualWakeAt = actualWakeAt,
    calendarSource = calendarSource,
    weatherDataSource = weatherDataSource,
    arrivalLocalTime = arrivalLocalTime,
    dayRevision = dayRevision,
    planName = planName,
    zoneId = zoneId,
)

private fun String.toEpochMillis(field: String): Long =
    toLongOrNull() ?: runCatching { Instant.parse(this).toEpochMilli() }
        .getOrElse { throw IllegalArgumentException("$field must be an epoch millisecond or ISO-8601 instant", it) }

fun WorkdayOverrideEntity.toDomain(): SingleDayOverride = SingleDayOverride(
    planId = planId,
    date = date,
    status = status,
    wakeLocalTime = wakeLocalTime,
    arrivalLocalTime = arrivalLocalTime,
    preparationMinutes = preparationMinutes,
    weatherProfile = weatherProfileOrNull(),
    origin = origin,
    destination = destination,
    commuteMode = commuteMode,
    dayRevision = dayRevision,
)

fun SingleDayOverride.toEntity(): WorkdayOverrideEntity = WorkdayOverrideEntity(
    planId = planId,
    date = date,
    status = status,
    wakeLocalTime = wakeLocalTime,
    arrivalLocalTime = arrivalLocalTime,
    preparationMinutes = preparationMinutes,
    weatherSeverity1Minutes = weatherProfile?.lightMinutes,
    weatherSeverity2Minutes = weatherProfile?.moderateMinutes,
    weatherSeverity3Minutes = weatherProfile?.severeMinutes,
    origin = origin,
    destination = destination,
    commuteMode = commuteMode,
    dayRevision = dayRevision,
)

/**
 * A weather profile is one unit. A partially persisted triple (only possible for
 * externally written rows) falls back to inheritance instead of inventing values.
 */
private fun WorkdayOverrideEntity.weatherProfileOrNull(): WeatherBufferProfile? {
    val light = weatherSeverity1Minutes ?: return null
    val moderate = weatherSeverity2Minutes ?: return null
    val severe = weatherSeverity3Minutes ?: return null
    if (light !in 0..60 || moderate !in 0..60 || severe !in 0..60) return null
    return WeatherBufferProfile(lightMinutes = light, moderateMinutes = moderate, severeMinutes = severe)
}

fun AlarmEventEntity.toDomain(): AlarmEvent = AlarmEvent(
    id = id,
    planId = planId,
    occurrenceId = occurrenceId,
    type = type,
    message = message,
    createdAt = createdAt,
)

fun AlarmEvent.toEntity(): AlarmEventEntity = AlarmEventEntity(
    id = id,
    planId = planId,
    occurrenceId = occurrenceId,
    type = type,
    message = message,
    createdAt = createdAt,
)
