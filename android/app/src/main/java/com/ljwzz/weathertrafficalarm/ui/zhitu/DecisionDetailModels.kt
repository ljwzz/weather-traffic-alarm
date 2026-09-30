package com.ljwzz.weathertrafficalarm.ui.zhitu

import com.ljwzz.weathertrafficalarm.core.data.repository.DecisionDetailLookup
import com.ljwzz.weathertrafficalarm.core.data.repository.DecisionOccurrenceAssociation
import com.ljwzz.weathertrafficalarm.core.data.repository.OccurrenceDetailUnavailableReason
import com.ljwzz.weathertrafficalarm.core.model.AlarmDecision
import com.ljwzz.weathertrafficalarm.core.model.AlarmOccurrence
import com.ljwzz.weathertrafficalarm.core.model.EvaluationOutcome
import com.ljwzz.weathertrafficalarm.core.model.FallbackReason
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceState
import com.ljwzz.weathertrafficalarm.core.model.WeatherSeverity
import com.ljwzz.weathertrafficalarm.core.model.WorkdayStatus
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class DecisionDetailTone { POSITIVE, WARNING, NEUTRAL }

enum class DecisionRecoveryAction { CREDENTIALS, ONBOARDING, COMMUTE, DIAGNOSTICS }

/** Pure detail projection shared by home, history, and the full decision screen. */
data class DecisionDetailUi(
    val available: Boolean,
    val unavailableMessage: String? = null,
    val decisionId: String? = null,
    val planName: String = "本次未提供",
    val title: String = "本次未提供",
    val targetDate: String = "本次未提供",
    val evaluatedAt: String = "本次未提供",
    val zoneLabel: String = "本次未提供",
    val evaluationLabel: String = "本次未提供",
    val evaluationTone: DecisionDetailTone = DecisionDetailTone.NEUTRAL,
    val baseWake: String = "本次未提供",
    val recommendedWake: String = "本次未提供",
    val actualWake: String = "本次未提供",
    val departure: String = "本次未提供",
    val commute: String = "本次未提供",
    val preparation: String = "本次未提供",
    /** Effective target arrival of the recorded evaluation; absent before N004. */
    val arrival: String = "本次未提供",
    val weather: String = "本次未提供",
    val weatherBuffer: String = "本次未提供",
    val dayRule: String = "本次未提供",
    val applicationLabel: String = "本次未提供",
    val occurrenceLabel: String = "本次未提供",
    val failureReason: String? = null,
    val fallbackReason: String? = null,
    val attemptLabel: String? = null,
    val nextRetryLabel: String? = null,
    val currentPlanMessage: String? = null,
    val recoveryActions: List<DecisionRecoveryAction> = emptyList(),
    val sourceLines: List<String> = emptyList(),
    val expiredNotice: String? = null,
)

fun DecisionDetailLookup.toDecisionDetailUi(): DecisionDetailUi = when (this) {
    is DecisionDetailLookup.DecisionFound -> decision.toDecisionDetailUi(
        occurrenceLabel = occurrenceAssociation.toOccurrenceLabel(),
        currentPlanMessage = plan.toCurrentPlanMessage(decision),
    )
    is DecisionDetailLookup.OccurrenceFound -> decision.toDecisionDetailUi(
        occurrenceLabel = occurrence.toOccurrenceLabel(rootAdvance),
        currentPlanMessage = plan.toCurrentPlanMessage(decision),
    )
    is DecisionDetailLookup.DecisionMissing -> DecisionDetailUi(
        available = false,
        unavailableMessage = "本次决策记录已不存在或已清理。",
    )
    is DecisionDetailLookup.OccurrenceUnavailable -> DecisionDetailUi(
        available = false,
        unavailableMessage = reason.toUnavailableMessage(),
    )
}

fun AlarmDecision.toDecisionDetailUi(
    occurrenceLabel: String = "本次未提供实例记录",
    currentPlanMessage: String? = null,
): DecisionDetailUi {
    val zone = zoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() }
    val application = applicationOutcome.toApplicationLabel()
    return DecisionDetailUi(
        available = true,
        decisionId = decisionId,
        planName = planName?.ifBlank { "闹钟" } ?: "本次未提供",
        title = toDecisionTitle(),
        targetDate = targetDate.ifBlank { "本次未提供" },
        evaluatedAt = generatedAt.formatDecisionTime(zone),
        zoneLabel = zoneId ?: "本次未提供",
        evaluationLabel = evaluationOutcome.toEvaluationLabel(insufficientAdvance),
        evaluationTone = toDecisionTone(),
        baseWake = defaultWakeAt.formatDecisionTime(zone),
        recommendedWake = if (applicationOutcome == "NOT_APPLIED") "本次未生成建议" else recommendedWakeAt.formatDecisionTime(zone),
        actualWake = actualWakeAt.formatDecisionTime(zone),
        departure = estimatedDepartureAt.formatDecisionTime(zone),
        commute = commuteSeconds.toDurationLabel(),
        preparation = if (planName == null && defaultWakeAt == null) "本次未提供" else "$preparationMinutes 分钟",
        arrival = arrivalLocalTime?.formatLocalTime() ?: "本次未提供",
        weather = weatherSeverity.toWeatherLabel(weatherProvider != null || weatherDataSource != null),
        weatherBuffer = if (weatherProvider != null || weatherDataSource != null) "$weatherBufferMinutes 分钟" else "本次未提供",
        dayRule = workdayStatus.toDayRuleLabel(),
        applicationLabel = application,
        occurrenceLabel = occurrenceLabel,
        failureReason = failureReason?.toFailureLabel() ?: if (applicationOutcome == "FAILED") "本次未提供注册失败的详细原因" else null,
        fallbackReason = fallbackReason.takeUnless { it == FallbackReason.NONE }?.toFallbackLabel(),
        attemptLabel = attemptNumber.takeIf { it > 0 }?.let { "第 $it 次重试" },
        recoveryActions = recoveryActions(),
        currentPlanMessage = currentPlanMessage,
        sourceLines = buildList {
            calendarSource?.let { add("日期规则来源：${it.toCalendarSourceLabel()}") }
            if (dayRevision > 0) add("单日覆盖修订：第 $dayRevision 次")
            routeProvider?.let { add("路线来源：${if (it in setOf("AMAP", "AMAP_WEB")) "高德地图" else it}${routeProviderReportTime.formatSourceTime(zone)}") }
            weatherProvider?.let { add("天气来源：${if (it in setOf("CAIYUN", "CAIYUN_V2_6")) "彩云天气" else it}${weatherProviderReportTime.formatSourceTime(zone)}") }
            weatherDataSource?.let { add("天气数据：${it.toWeatherSourceLabel()}") }
            weatherWindowStart?.formatDecisionTime(zone)?.takeUnless { it == "本次未提供" }?.let { start ->
                weatherWindowEnd?.formatDecisionTime(zone)?.takeUnless { it == "本次未提供" }?.let { end ->
                    add("天气适用时段：$start 至 $end")
                }
            }
            expiresAt.formatDecisionTime(zone).takeUnless { it == "本次未提供" }?.let { add("数据有效至：$it") }
        },
        expiredNotice = expiresAt.isExpired()
            .takeIf { it }
            ?.let { "本次评估数据已过有效期；历史评估与调度结果保持不变。" },
    )
}

private fun AlarmDecision.toDecisionTitle(): String = when (evaluationOutcome) {
    EvaluationOutcome.SUCCESS -> when {
        insufficientAdvance -> "提前额度不足"
        applicationOutcome == "APPLIED" -> "已应用提前提醒"
        applicationOutcome == "CANCELLED" -> "本次无需提前"
        applicationOutcome == "FAILED" -> "提前提醒注册失败"
        else -> "评估完成"
    }
    EvaluationOutcome.FAILED -> if (applicationOutcome == "FAILED") "提前提醒注册失败" else "评估失败"
    EvaluationOutcome.STALE -> "评估已过期"
    EvaluationOutcome.SKIPPED -> "本次跳过评估"
}

private fun AlarmDecision.toDecisionTone(): DecisionDetailTone = when {
    applicationOutcome == "FAILED" -> DecisionDetailTone.WARNING
    evaluationOutcome == EvaluationOutcome.SUCCESS -> DecisionDetailTone.POSITIVE
    evaluationOutcome == EvaluationOutcome.FAILED || evaluationOutcome == EvaluationOutcome.STALE -> DecisionDetailTone.WARNING
    else -> DecisionDetailTone.NEUTRAL
}

private fun com.ljwzz.weathertrafficalarm.core.model.AlarmPlan?.toCurrentPlanMessage(decision: AlarmDecision): String? = when {
    this == null -> "当前计划已删除；以下为本次历史记录。"
    !enabled -> "当前计划已停用；以下为本次历史记录。"
    revision != decision.planRevision -> "当前计划已更新；以下为评估时快照。"
    else -> null
}

private fun AlarmDecision.recoveryActions(): List<DecisionRecoveryAction> = buildSet {
    val providerCategory = failureReason?.substringAfter('_', missingDelimiterValue = "")
    when (failureReason) {
        "COMMUTE_NOT_CONFIGURED", "ROUTE_ROUTE_NOT_FOUND" -> add(DecisionRecoveryAction.COMMUTE)
        "WEATHER_WEATHER_PROVIDER_AUTH", "MISSING_CAIYUN_CREDENTIALS" -> add(DecisionRecoveryAction.CREDENTIALS)
        "ROUTE_CONSENT_REQUIRED" -> add(DecisionRecoveryAction.ONBOARDING)
        "INVALID_TIME_WINDOW" -> add(DecisionRecoveryAction.COMMUTE)
    }
    when (providerCategory) {
        "MISSING_KEY", "INVALID_KEY" -> add(DecisionRecoveryAction.CREDENTIALS)
        "CONSENT_REQUIRED" -> add(DecisionRecoveryAction.ONBOARDING)
    }
    when (applicationOutcome) {
        "FAILED" -> add(DecisionRecoveryAction.DIAGNOSTICS)
    }
}.toList()

private fun DecisionOccurrenceAssociation.toOccurrenceLabel(): String = when (this) {
    is DecisionOccurrenceAssociation.Exact -> occurrence.toOccurrenceLabel(occurrence)
    DecisionOccurrenceAssociation.Missing -> "本次未提供实例记录"
    is DecisionOccurrenceAssociation.Ambiguous -> "关联实例不唯一，无法确认本次状态"
}

private fun AlarmOccurrence.toOccurrenceLabel(rootAdvance: AlarmOccurrence): String {
    val rootPrefix = if (occurrenceId == rootAdvance.occurrenceId) "提前实例" else "本次贪睡实例"
    return "$rootPrefix：${state.toOccurrenceStateLabel()}"
}

private fun OccurrenceDetailUnavailableReason.toUnavailableMessage(): String = when (this) {
    OccurrenceDetailUnavailableReason.OCCURRENCE_MISSING -> "本次响铃实例已不存在或已清理。"
    OccurrenceDetailUnavailableReason.UNRELATED_INSTANCE -> "该实例不是可追溯的提前提醒。"
    OccurrenceDetailUnavailableReason.DECISION_LINK_MISSING -> "本次实例缺少决策关联。"
    OccurrenceDetailUnavailableReason.DECISION_LINK_MISMATCH -> "本次实例的决策关联不一致。"
    OccurrenceDetailUnavailableReason.DECISION_MISSING -> "本次提前决策记录已不存在或已清理。"
    OccurrenceDetailUnavailableReason.ROOT_IDENTITY_MISMATCH -> "本次实例与决策记录不匹配。"
}

private fun EvaluationOutcome.toEvaluationLabel(insufficientAdvance: Boolean): String = when (this) {
    EvaluationOutcome.SUCCESS -> if (insufficientAdvance) "评估完成，提前额度不足" else "评估完成"
    EvaluationOutcome.FAILED -> "评估失败"
    EvaluationOutcome.STALE -> "评估已过期"
    EvaluationOutcome.SKIPPED -> "本次跳过评估"
}

private fun String?.toApplicationLabel(): String = when (this) {
    "APPLIED" -> "已应用：已注册本次提前提醒"
    "NOT_APPLIED" -> "本次未新增或调整提醒"
    "UNCHANGED" -> "未调整：保留已有提醒（非本次决策实例）"
    "CANCELLED" -> "无需提前：本次未新增提前提醒"
    "FAILED" -> "注册失败：已有提醒可能仍保留"
    "STALE" -> "未应用：评估记录已过期"
    null -> "本次未提供"
    else -> "本次未提供"
}

private fun OccurrenceState.toOccurrenceStateLabel(): String = when (this) {
    OccurrenceState.REGISTERING -> "注册中"
    OccurrenceState.SCHEDULED -> "已注册"
    OccurrenceState.FAILED -> "注册失败"
    OccurrenceState.DEFAULT_REGISTERED -> "基础提醒已注册"
    OccurrenceState.ADVANCED -> "提前提醒已注册"
    OccurrenceState.FIRING -> "响铃中"
    OccurrenceState.SNOOZED -> "贪睡中"
    OccurrenceState.DISMISSED -> "已停止"
    OccurrenceState.MISSED -> "已错过"
    OccurrenceState.CANCELLED -> "已取消"
}

private fun Int.toWeatherLabel(hasRecordedWeather: Boolean): String = when {
    !hasRecordedWeather -> "本次未提供"
    else -> when (this) {
        WeatherSeverity.FINE.level -> "晴好"
        WeatherSeverity.LIGHT.level -> "轻度影响"
        WeatherSeverity.MODERATE.level -> "中度影响"
        WeatherSeverity.SEVERE.level -> "严重影响"
        else -> "本次未提供"
    }
}

private fun WorkdayStatus?.toDayRuleLabel(): String = when (this) {
    WorkdayStatus.WORKDAY -> "工作日"
    WorkdayStatus.HOLIDAY -> "休息日"
    null -> "本次未提供"
}

private fun String.toFailureLabel(): String = when {
    startsWith("ROUTE_") -> substringAfter("ROUTE_").toRouteFailureLabel()
    startsWith("WEATHER_") -> substringAfter("WEATHER_").toWeatherFailureLabel()
    else -> when (this) {
    "COMMUTE_NOT_CONFIGURED" -> "未配置通勤地点或方式"
    "MISSING_CAIYUN_CREDENTIALS" -> "未完成天气服务凭据验证"
    "INVALID_TIME_WINDOW" -> "评估时间范围无效"
    "DATE_NOT_APPLICABLE" -> "该日期不适用当前计划规则"
    "EVALUATION_WINDOW_EXPIRED" -> "评估窗口已结束"
    "EVALUATION_RESULT_EXPIRED" -> "评估结果已过有效期"
    "EVALUATION_INPUTS_CHANGED" -> "评估期间计划配置已变化"
    else -> "本次原因无法识别"
    }
}

private fun String.toRouteFailureLabel(): String = when (this) {
    "CONSENT_REQUIRED" -> "尚未完成地图授权"
    "MISSING_KEY" -> "未配置路线服务凭据"
    "INVALID_KEY" -> "路线服务凭据无效或未授权"
    "INVALID_REQUEST" -> "路线请求无效"
    "QUOTA_EXCEEDED", "RATE_LIMITED" -> "路线服务额度或频率受限"
    "ROUTE_NOT_FOUND" -> "未找到可用通勤路线"
    "NETWORK" -> "路线网络不可用"
    "TIMEOUT" -> "路线服务响应超时"
    "MALFORMED_RESPONSE", "PROVIDER_FAILURE", "UNEXPECTED" -> "路线服务返回不可用结果"
    else -> "本次原因无法识别"
}

private fun String.toWeatherFailureLabel(): String = when (this) {
    "CONSENT_REQUIRED" -> "天气服务授权不可用"
    "MISSING_KEY" -> "未配置天气服务凭据"
    "INVALID_KEY" -> "天气服务凭据无效或未授权"
    "INVALID_REQUEST" -> "天气请求无效"
    "QUOTA_EXCEEDED", "RATE_LIMITED" -> "天气服务额度或频率受限"
    "ROUTE_NOT_FOUND" -> "天气服务未提供可用结果"
    "NETWORK" -> "天气网络不可用"
    "TIMEOUT" -> "天气服务响应超时"
    "WEATHER_PROVIDER_AUTH" -> "天气服务授权不可用"
    "WEATHER_HORIZON_UNAVAILABLE" -> "目标时段超出天气数据范围"
    "WEATHER_UNKNOWN_CODE" -> "天气数据包含未识别状态"
    "STALE_RESPONSE" -> "天气响应已过有效期"
    "WEATHER_PROVIDER_TIMEOUT" -> "天气服务响应超时"
    "WEATHER_PROVIDER_QUOTA" -> "天气服务额度不足"
    "MALFORMED_RESPONSE", "PROVIDER_FAILURE", "UNEXPECTED" -> "天气服务返回不可用结果"
    else -> "本次原因无法识别"
}

private fun FallbackReason.toFallbackLabel(): String = when (this) {
    FallbackReason.CURRENT_TRAFFIC_FALLBACK -> "使用当前路况估算"
    FallbackReason.FUTURE_ROUTE_NOT_ENTITLED -> "路线服务不支持未来时段"
    FallbackReason.ROUTE_HORIZON_UNAVAILABLE -> "路线预测时段不可用"
    FallbackReason.ROUTE_PROVIDER_TIMEOUT -> "路线服务响应超时"
    FallbackReason.ROUTE_PROVIDER_QUOTA -> "路线服务额度不足"
    FallbackReason.ROUTE_NOT_FOUND -> "未找到可用通勤路线"
    FallbackReason.WEATHER_HORIZON_UNAVAILABLE -> "天气预测时段不可用"
    FallbackReason.WEATHER_PROVIDER_TIMEOUT -> "天气服务响应超时"
    FallbackReason.WEATHER_PROVIDER_AUTH -> "天气服务授权不可用"
    FallbackReason.WEATHER_PROVIDER_QUOTA -> "天气服务额度不足"
    FallbackReason.WEATHER_UNKNOWN_CODE -> "天气数据包含未识别状态"
    FallbackReason.CALENDAR_FALLBACK -> "日期规则使用星期兜底"
    FallbackReason.STALE_RESPONSE -> "响应超过评估有效期"
    FallbackReason.NONE -> "本次未提供"
}

private fun String.toCalendarSourceLabel(): String = when (this) {
    "PLAN_OVERRIDE" -> "计划日期覆盖"
    "HOLIDAY_CN" -> "本地工作日日历"
    "WEEKDAY_FALLBACK" -> "星期规则兜底"
    else -> "本次未提供"
}

private fun String.toWeatherSourceLabel(): String = when (this) {
    "NETWORK" -> "实时数据"
    "CACHE" -> "本地缓存"
    "MIXED" -> "混合数据"
    else -> "本次未提供"
}

private fun Long?.toDurationLabel(): String = this?.takeIf { it >= 0 }?.let { seconds ->
    val minutes = (seconds + 59) / 60
    "${minutes / 60}小时${minutes % 60}分钟"
} ?: "本次未提供"

private fun String?.formatDecisionTime(zone: ZoneId?): String {
    val value = this ?: return "本次未提供"
    val instant = value.toEpochMillisOrNull()?.let(Instant::ofEpochMilli)
    if (instant != null) return instant.atZone(zone ?: ZoneOffset.UTC)
        .format(DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA)) + if (zone == null) " UTC" else ""
    return runCatching {
        LocalDateTime.parse(value).format(DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA))
    }.getOrDefault("本次未提供")
}

private fun String?.formatSourceTime(zone: ZoneId?): String = " · 更新于 ${formatDecisionTime(zone)}"

/** Effective arrival is stored as a local wall-clock time, which needs no zone conversion. */
private fun String.formatLocalTime(): String =
    runCatching { LocalTime.parse(this).format(DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)) }
        .getOrDefault("本次未提供")

private fun String?.isExpired(): Boolean = this.toEpochMillisOrNull()?.let { it < System.currentTimeMillis() } == true

private fun String?.toEpochMillisOrNull(): Long? = this?.toLongOrNull()
    ?: this?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
