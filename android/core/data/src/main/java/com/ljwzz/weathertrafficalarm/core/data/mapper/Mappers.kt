package com.ljwzz.weathertrafficalarm.core.data.mapper

import com.ljwzz.weathertrafficalarm.core.data.db.entity.AlarmDecisionEntity
import com.ljwzz.weathertrafficalarm.core.data.db.entity.AlarmOccurrenceEntity
import com.ljwzz.weathertrafficalarm.core.data.db.entity.AlarmPlanEntity
import com.ljwzz.weathertrafficalarm.core.data.db.entity.WorkdayOverrideEntity
import com.ljwzz.weathertrafficalarm.core.model.AlarmDecision
import com.ljwzz.weathertrafficalarm.core.model.AlarmOccurrence
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.WorkdayOverride

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
    updatedAt = updatedAt,
)

fun AlarmOccurrence.toEntity(): AlarmOccurrenceEntity = AlarmOccurrenceEntity(
    occurrenceId = occurrenceId,
    planId = planId,
    planRevision = planRevision,
    targetDate = targetDate,
    scheduledWakeAt = scheduledWakeAt,
    state = state,
    decisionId = decisionId,
    updatedAt = updatedAt,
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
    generatedAt = generatedAt.toString(),
    expiresAt = expiresAt.toString(),
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
    generatedAt = generatedAt.toLongOrNull() ?: 0L,
    expiresAt = expiresAt.toLongOrNull() ?: 0L,
)

fun WorkdayOverrideEntity.toDomain(): WorkdayOverride = WorkdayOverride(
    planId = planId,
    date = date,
    status = status,
)

fun WorkdayOverride.toEntity(): WorkdayOverrideEntity = WorkdayOverrideEntity(
    planId = planId,
    date = date,
    status = status,
)
