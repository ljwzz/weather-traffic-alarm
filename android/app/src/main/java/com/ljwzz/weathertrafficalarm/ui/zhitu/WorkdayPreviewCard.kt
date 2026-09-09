package com.ljwzz.weathertrafficalarm.ui.zhitu

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ljwzz.weathertrafficalarm.R
import com.ljwzz.weathertrafficalarm.core.data.local.CalendarUiState
import com.ljwzz.weathertrafficalarm.core.model.DayStatus
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

internal enum class WorkdayPreviewKind(val label: String, val status: DayStatus) {
    WORKDAY("工作日", DayStatus.WORKDAY),
    REST_DAY("休息日", DayStatus.HOLIDAY),
    SPECIAL_HOLIDAY("特殊节假日", DayStatus.HOLIDAY),
    SPECIAL_WORKDAY("特殊工作日", DayStatus.WORKDAY),
}

internal data class WorkdayPreviewDay(
    val date: LocalDate,
    val label: String,
    val kind: WorkdayPreviewKind,
    val isToday: Boolean,
    val effectiveStatus: DayStatus,
    val overridden: Boolean,
)

/** Official entries are sparse; their presence distinguishes special days from weekday fallback. */
internal fun workdayPreviewDays(
    today: LocalDate,
    officialDays: Map<String, DayStatus>,
    overrides: Map<String, DayStatus> = emptyMap(),
): List<WorkdayPreviewDay> {
    val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1)
    return List(28) { index ->
        val date = start.plusDays(index.toLong())
        val key = date.toString()
        val kind = when (officialDays[key]) {
            DayStatus.HOLIDAY -> WorkdayPreviewKind.SPECIAL_HOLIDAY
            DayStatus.WORKDAY -> WorkdayPreviewKind.SPECIAL_WORKDAY
            null -> if (date.dayOfWeek.value <= 5) WorkdayPreviewKind.WORKDAY else WorkdayPreviewKind.REST_DAY
        }
        WorkdayPreviewDay(
            date = date,
            label = if (index == 0 || date.dayOfMonth == 1) "${date.monthValue}/${date.dayOfMonth}" else date.dayOfMonth.toString(),
            kind = kind,
            isToday = date == today,
            effectiveStatus = overrides[key] ?: kind.status,
            overridden = key in overrides,
        )
    }
}

@OptIn(ExperimentalTextApi::class)
private val CalendarRoboto = FontFamily(
    Font(
        R.font.roboto_variable,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400), FontVariation.width(100f)),
    ),
)

@Composable
internal fun WorkdayPreviewCard(
    calendarState: CalendarUiState,
    today: LocalDate,
    overrides: Map<String, DayStatus> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    val days = workdayPreviewDays(today, calendarState.days, overrides)
    Column(modifier.fillMaxWidth().testTag("workday_preview")) {
        FormCard {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "工作日预览",
                    color = ZhituColors.Ink,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
                    fontWeight = FontWeight.Bold,
                )
                Column(
                    Modifier.fillMaxWidth().testTag("workday_preview_grid"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { index, label ->
                            Text(
                                label,
                                modifier = Modifier.weight(1f).height(20.dp).testTag("workday_weekday_${index + 1}"),
                                color = ZhituColors.Muted,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                            )
                        }
                    }
                    days.chunked(7).forEachIndexed { week, dates ->
                        Row(
                            Modifier.fillMaxWidth().testTag("workday_week_$week"),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            dates.forEach { day -> WorkdayPreviewCell(day, Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkdayPreviewCell(day: WorkdayPreviewDay, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    val textColor = when (day.kind) {
        WorkdayPreviewKind.WORKDAY -> ZhituColors.CalendarPrimaryText
        WorkdayPreviewKind.REST_DAY, WorkdayPreviewKind.SPECIAL_HOLIDAY -> ZhituColors.CalendarRestText
        WorkdayPreviewKind.SPECIAL_WORKDAY -> ZhituColors.CalendarWorkdayText
    }
    val backgroundColor = when (day.kind) {
        WorkdayPreviewKind.SPECIAL_HOLIDAY -> ZhituColors.CalendarHolidayBackground
        WorkdayPreviewKind.SPECIAL_WORKDAY -> ZhituColors.CalendarWorkdayBackground
        else -> Color.Transparent
    }
    Box(
        modifier.height(36.dp)
            .background(backgroundColor, shape)
            .then(if (day.isToday) Modifier.border(1.dp, ZhituColors.CalendarRestText, shape) else Modifier)
            .testTag("workday_day_${day.date}")
            .clearAndSetSemantics {
                contentDescription = buildString {
                    append("${day.date}，${day.kind.label}")
                    if (day.isToday) append("，今天")
                    if (day.overridden) append(if (day.effectiveStatus == DayStatus.WORKDAY) "，单日覆盖为工作日" else "，单日覆盖为休息日")
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            day.label,
            color = textColor,
            fontFamily = CalendarRoboto,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
        )
    }
}
