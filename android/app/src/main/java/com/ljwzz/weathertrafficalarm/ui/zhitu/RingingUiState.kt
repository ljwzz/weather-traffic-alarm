package com.ljwzz.weathertrafficalarm.ui.zhitu

import com.ljwzz.weathertrafficalarm.core.model.NextAlarmSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class RingingPhase { LOADING, SCHEDULED, RINGING, STOPPED, SNOOZED, UNAVAILABLE }

data class RingingUiState(
    val phase: RingingPhase = RingingPhase.LOADING,
    val alarmTime: String = "--:--",
    val dateLabel: String = "",
    val header: String = "知途 · 基础闹钟",
    val badge: String = "正在读取闹钟",
    val reasonTitle: String = "本地闹钟",
    val reason: String = "正在确认本次实例状态。",
    val footer: String = "仅操作本次闹钟，不改变其他计划。",
    val snoozeMinutes: Int = 10,
    val busy: Boolean = false,
    val errorMessage: String? = null,
)

/** Pure projection of device-protected, authoritative state; never creates an alarm. */
fun ringingUiState(
    occurrenceId: String?,
    snapshots: List<NextAlarmSnapshot>?,
    activeOccurrenceIds: Set<String>,
    pendingAction: Boolean = false,
    errorMessage: String? = null,
    zoneId: ZoneId = ZoneId.systemDefault(),
): RingingUiState {
    if (snapshots == null) return RingingUiState(errorMessage = errorMessage)
    val snapshot = snapshots.firstOrNull { it.occurrenceId == occurrenceId }
        ?: return RingingUiState(
            phase = RingingPhase.UNAVAILABLE,
            badge = "该闹钟已结束或失效",
            reasonTitle = "无法操作此实例",
            reason = "请返回闹钟页查看当前计划。",
            errorMessage = errorMessage,
        )
    val isSnooze = snapshot.occurrenceKind == "SNOOZE"
    val isAdvance = snapshot.occurrenceKind == "ADVANCE"
    val advanceMinutes = snapshot.defaultWakeAtMillis?.let {
        ((it - snapshot.triggerAtMillis) / 60_000L).toInt().takeIf { minutes -> minutes > 0 }
    }
    val child = snapshots.filter {
        it.parentOccurrenceId == snapshot.occurrenceId &&
            it.planId == snapshot.planId && it.planRevision == snapshot.planRevision &&
            it.occurrenceKind == "SNOOZE" && it.occurrenceState in setOf("SCHEDULED", "FIRING")
    }.maxByOrNull { it.triggerAtMillis }
    val active = snapshot.occurrenceId in activeOccurrenceIds
    val phase = when (snapshot.occurrenceState) {
        "FIRING" -> if (active) RingingPhase.RINGING else RingingPhase.UNAVAILABLE
        "SCHEDULED", "REGISTERING", "DEFAULT_REGISTERED" -> RingingPhase.SCHEDULED
        "DISMISSED" -> if (active) RingingPhase.RINGING else RingingPhase.STOPPED
        "SNOOZED" -> if (active) RingingPhase.RINGING else if (child != null) RingingPhase.SNOOZED else RingingPhase.UNAVAILABLE
        else -> RingingPhase.UNAVAILABLE
    }
    val display = if (phase == RingingPhase.SNOOZED) requireNotNull(child) else snapshot
    val local = Instant.ofEpochMilli(display.triggerAtMillis).atZone(zoneId)
    val time = local.format(DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA))
    val date = local.format(DateTimeFormatter.ofPattern("M月d日  EEEE", Locale.CHINA))
    val waitingForService = active && snapshot.occurrenceState in setOf("DISMISSED", "SNOOZED")
    val snoozeMinutes = snapshot.snoozeMinutes.coerceIn(1, 30)
    return RingingUiState(
        phase = phase,
        alarmTime = time,
        dateLabel = date,
        header = if (isAdvance) "知途 · 提前提醒" else "知途 · 基础闹钟",
        badge = when (phase) {
            RingingPhase.RINGING -> when {
                isSnooze -> "贪睡后再次响铃"
                isAdvance -> advanceMinutes?.let { "提前 $it 分钟提醒" } ?: "通勤提前提醒"
                else -> "按设定时间提醒"
            }
            RingingPhase.SCHEDULED -> "尚未到响铃时间"
            RingingPhase.STOPPED -> "本次响铃已停止"
            RingingPhase.SNOOZED -> "已贪睡 $snoozeMinutes 分钟"
            else -> "该闹钟已结束或失效"
        },
        reasonTitle = when (phase) {
            RingingPhase.STOPPED -> "本次闹钟已结束"
            RingingPhase.SNOOZED -> "下次响铃"
            RingingPhase.UNAVAILABLE -> "无法操作此实例"
            else -> if (isSnooze) "贪睡提醒" else snapshot.alarmLabel
        },
        reason = when (phase) {
            RingingPhase.RINGING -> when {
                isSnooze -> "本次为贪睡后的提醒。\n不改变后续重复闹钟。"
                isAdvance -> "根据路线、天气和日期规则提前提醒。\n基础闹钟仍按原定时间响铃。"
                else -> "按计划 $time 提醒；\n不依赖天气或路线。"
            }
            RingingPhase.SCHEDULED -> "已安排在 $date $time 提醒。\n到点后才可停止或贪睡。"
            RingingPhase.STOPPED -> "仅结束当前实例；\n其他闹钟和后续重复安排不受影响。"
            RingingPhase.SNOOZED -> "将在 $date $time 再次提醒。\n贪睡不改变后续重复闹钟。"
            else -> "请返回闹钟页查看当前计划。"
        },
        footer = "由本 App 负责响铃、停止与贪睡。\n仅操作本次闹钟，不改变其他计划。",
        snoozeMinutes = snoozeMinutes,
        busy = pendingAction || waitingForService,
        errorMessage = errorMessage,
    )
}
