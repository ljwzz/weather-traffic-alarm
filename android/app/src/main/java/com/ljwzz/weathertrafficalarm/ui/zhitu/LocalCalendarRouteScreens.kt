package com.ljwzz.weathertrafficalarm.ui.zhitu

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.painterResource
import com.ljwzz.weathertrafficalarm.R
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ljwzz.weathertrafficalarm.core.data.local.CalendarUiState
import com.ljwzz.weathertrafficalarm.core.data.repository.EffectiveCommute
import com.ljwzz.weathertrafficalarm.core.data.preferences.FavoritePlace
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettings
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.CommuteSource
import com.ljwzz.weathertrafficalarm.core.model.DailySettingSource
import com.ljwzz.weathertrafficalarm.core.model.DayClassification
import com.ljwzz.weathertrafficalarm.core.model.DayKind
import com.ljwzz.weathertrafficalarm.core.model.DayOverrideChange
import com.ljwzz.weathertrafficalarm.core.model.DayOverrideFailureCode
import com.ljwzz.weathertrafficalarm.core.model.DayOverrideSaveResult
import com.ljwzz.weathertrafficalarm.core.model.DayStatus
import com.ljwzz.weathertrafficalarm.core.model.EffectiveDailySettings
import com.ljwzz.weathertrafficalarm.core.model.GeoPoint
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import com.ljwzz.weathertrafficalarm.core.model.RouteAlternative
import com.ljwzz.weathertrafficalarm.core.model.SingleDayOverride
import com.ljwzz.weathertrafficalarm.core.model.WeatherBufferProfile
import com.ljwzz.weathertrafficalarm.core.map.AmapMap
import com.ljwzz.weathertrafficalarm.core.map.AmapMapUiState
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Per-plan calendar editor. Every field is held in Compose state until Save writes the
 * single-day override through [onSave]; leaving the page without saving writes nothing.
 * A null field keeps inheriting from the plan or the global defaults.
 */
@Composable
fun LocalCalendarScreen(
    plans: List<AlarmPlan>,
    overrides: List<SingleDayOverride>,
    calendarState: CalendarUiState,
    loadEditorInputs: suspend (planId: String, date: String) -> DayEditorInputs?,
    onSave: (DayOverrideChange, onComplete: (DayOverrideSaveResult) -> Unit) -> Unit,
    onRefresh: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var selectedPlanId by remember { mutableStateOf(plans.firstOrNull()?.id) }
    var inputs by remember { mutableStateOf<DayEditorInputs?>(null) }
    var draft by remember { mutableStateOf(DayOverrideDraft()) }
    var timeField by remember { mutableStateOf<DayTimeField?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var fieldErrors by remember { mutableStateOf(DayDraftErrors()) }
    var failureCode by remember { mutableStateOf<DayOverrideFailureCode?>(null) }
    var saving by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableStateOf(0) }
    var persisted by remember { mutableStateOf(false) }
    val activePlan = plans.firstOrNull { it.id == selectedPlanId } ?: plans.firstOrNull()
    val activePlanId = activePlan?.id
    val existing = overrides.firstOrNull { it.planId == activePlanId && it.date == selectedDate.toString() }

    LaunchedEffect(Unit) { onRefresh(false) }
    val storedForDate = overrides.firstOrNull { it.planId == activePlanId && it.date == selectedDate.toString() }
    // Selecting another plan or date starts a fresh draft.
    LaunchedEffect(activePlanId, selectedDate) {
        feedback = null
        fieldErrors = DayDraftErrors()
        failureCode = null
        persisted = false
        reloadToken += 1
    }
    // Loading inputs is bound to the selection and to an explicit reload. Another date's save, a
    // calendar refresh or a rejected save never overwrite the draft the user is editing; a change
    // to this very date surfaces on the next save as a conflict with a reload action.
    LaunchedEffect(activePlanId, selectedDate, reloadToken) {
        val planId = activePlanId
        if (planId == null) {
            inputs = null
            draft = DayOverrideDraft()
            return@LaunchedEffect
        }
        val loaded = loadEditorInputs(planId, selectedDate.toString())
        inputs = loaded
        draft = loaded?.let { DayOverrideDraft.from(it) } ?: DayOverrideDraft()
    }

    Scaffold(
        containerColor = ZhituColors.Background,
        topBar = { ZhituTopBar("工作日日历", "识别日期类型，只改动指定的一天", onBack, navigationTag = "day_top_back") },
        bottomBar = {
            Button(
                onClick = {
                    val current = inputs ?: return@Button
                    if (saving) return@Button
                    val errors = draft.validate(current)
                    fieldErrors = errors
                    if (errors.hasErrors) {
                        // Invalid drafts never reach the save callback and never write to storage.
                        failureCode = null
                        feedback = errors.firstMessage
                        return@Button
                    }
                    if (draft.isEmptySnapshot()) {
                        if (storedForDate == null) {
                            // Nothing stored and nothing drafted: the date already inherits everything.
                            persisted = true
                            feedback = null
                            failureCode = null
                            return@Button
                        }
                    }
                    saving = true
                    feedback = null
                    failureCode = null
                    onSave(draft.toChange(current)) { result ->
                        saving = false
                        when (result) {
                            is DayOverrideSaveResult.Success -> {
                                persisted = true
                                feedback = null
                                failureCode = null
                                fieldErrors = DayDraftErrors()
                                // Reload the committed revision so the next save is not a conflict.
                                reloadToken += 1
                            }
                            // A rejected save keeps the user's draft and the stored values.
                            is DayOverrideSaveResult.Failure -> {
                                persisted = false
                                failureCode = result.code
                                feedback = result.message
                            }
                        }
                    }
                },
                enabled = activePlan != null && inputs != null && !saving,
                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("day_override_save"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ZhituColors.Brand),
            ) { Text(if (saving) "保存中…" else if (storedForDate == null && draft.isInheriting) "保持自动规则" else "保存当前日期") }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("day_screen_list"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { CalendarSourceCard(calendarState, onRefresh) }
            item {
                if (plans.isEmpty()) LocalInfoCard("尚未创建闹钟", "先创建闹钟后，才能设置某个计划在指定日期的覆盖规则。")
                else {
                    Text("选择闹钟", color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(plans, key = AlarmPlan::id) { plan ->
                            FilterChip(
                                selected = plan.id == activePlanId,
                                onClick = { selectedPlanId = plan.id },
                                modifier = Modifier.testTag("day_plan_${plan.id}"),
                                label = { Text(plan.name.ifBlank { "闹钟" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
            }
            item {
                LocalMonthGrid(
                    month = month,
                    selected = selectedDate,
                    officialDays = calendarState.days,
                    overrides = overrides.filter { it.planId == activePlanId },
                    onPrevious = { month = month.minusMonths(1) },
                    onNext = { month = month.plusMonths(1) },
                    onDate = { date -> selectedDate = date },
                )
            }
            if (activePlan != null && inputs != null) {
                val current = requireNotNull(inputs)
                item {
                    DayStatusCard(
                        planName = activePlan.name.ifBlank { "闹钟" },
                        date = selectedDate,
                        classification = current.effective.classification,
                        calendarSource = if (calendarState.days.containsKey(selectedDate.toString())) "年度日历" else "星期回退",
                        draftStatus = draft.status,
                        onStatus = { draft = draft.copy(status = it) },
                    )
                }
                item {
                    DayTimeCard(
                        draft = draft,
                        effective = current.effective,
                        onPick = { field -> timeField = field },
                        onClear = { field ->
                            draft = when (field) {
                                DayTimeField.WAKE -> draft.copy(wake = null)
                                DayTimeField.ARRIVAL -> draft.copy(arrival = null)
                            }
                        },
                    )
                }
                item {
                    DayPreparationCard(
                        draft = draft,
                        effective = current.effective,
                        planPreparation = current.plan.preparationMinutes,
                        onPreparation = { draft = draft.copy(preparationMinutes = it) },
                    )
                }
                item {
                    DayWeatherCard(
                        draft = draft,
                        effective = current.effective,
                        inherited = current.inherited,
                        onProfile = { draft = draft.copy(weatherProfile = it) },
                    )
                }
                item {
                    DayCommuteCard(
                        draft = draft,
                        planCommute = current.planCommute,
                        favorites = current.settings.favorites,
                        onChange = { draft = it },
                    )
                }
                item {
                    DayInheritanceSummary(
                        effective = current.effective,
                        inherited = current.inherited,
                        draft = draft,
                        persisted = persisted,
                    )
                }
            }
            feedback?.let { message ->
                item {
                    LocalInfoCard("无法保存", message, ZhituColors.AmberBackground, ZhituColors.Amber) {
                        if (failureCode == DayOverrideFailureCode.CONFLICT) {
                            TextButton(
                                onClick = { reloadToken += 1 },
                                modifier = Modifier.testTag("day_override_reload"),
                            ) { Text("重新载入本日设置") }
                        }
                    }
                }
            }
        }
    }
    val field = timeField
    if (field != null && activePlan != null) {
        LocalTimePicker(
            initial = when (field) {
                DayTimeField.WAKE -> draft.wake ?: activePlan.defaultWakeLocalTime
                DayTimeField.ARRIVAL -> draft.arrival?.takeIf { it != activePlan.arrivalLocalTime } ?: activePlan.arrivalLocalTime
            },
            tag = when (field) {
                DayTimeField.WAKE -> "day_wake_picker"
                DayTimeField.ARRIVAL -> "day_arrival_picker"
            },
            onSave = {
                draft = when (field) {
                    DayTimeField.WAKE -> draft.copy(wake = it)
                    DayTimeField.ARRIVAL -> draft.copy(arrival = it.takeIf { value -> value != activePlan.arrivalLocalTime })
                }
                timeField = null
            },
            onDismiss = { timeField = null },
        )
    }
}

internal enum class DayTimeField { WAKE, ARRIVAL }

/**
 * Weather step baseline: the draft's own three values once it has one, otherwise the profile the
 * target date really inherits from the plan, the global configuration and the raw date category.
 */
internal fun bufferBaseline(draft: DayOverrideDraft, inherited: EffectiveDailySettings): WeatherBufferProfile =
    draft.weatherProfile ?: inherited.weatherProfile

/** Field-level validation result of one day draft; nothing is written while any entry is set. */
internal data class DayDraftErrors(
    val wake: String? = null,
    val arrival: String? = null,
    val preparation: String? = null,
    val weather: String? = null,
    val commute: String? = null,
) {
    val hasErrors: Boolean
        get() = listOfNotNull(wake, arrival, preparation, weather, commute).isNotEmpty()

    val firstMessage: String?
        get() = listOfNotNull(commute, wake, arrival, preparation, weather).firstOrNull()
}

/**
 * Editable single-day draft. Null means "inherit"; the stored row also keeps its
 * persisted revision so a save only touches this plan and date.
 */
internal data class DayOverrideDraft(
    val status: DayStatus? = null,
    val wake: String? = null,
    val arrival: String? = null,
    val preparationMinutes: Int? = null,
    /** Null inherits the global profile matching the raw date category. */
    val weatherProfile: WeatherBufferProfile? = null,
    /** True once the user touched any of the three buffer values. */
    val weatherEdited: Boolean = false,
    val origin: PlaceRef? = null,
    val destination: PlaceRef? = null,
    /** Favorite identifiers behind [origin] and [destination]; null means "no place selected yet". */
    val originFavoriteId: String? = null,
    val destinationFavoriteId: String? = null,
    val commuteMode: CommuteMode? = null,
    /** True once the user selected a day-level commute combination. */
    val commuteEdited: Boolean = false,
) {
    val isInheriting: Boolean
        get() = status == null && wake == null && arrival == null && preparationMinutes == null &&
            weatherProfile == null && !weatherEdited && !commuteEdited &&
            origin == null && destination == null && commuteMode == null

    /** True when the draft carries no user-visible change and would remove the stored row. */
    fun isEmptySnapshot(): Boolean = isInheriting

    /**
     * Validates the draft against the contract before any domain object is constructed. An
     * incomplete or contradictory commute, an unparseable time and an out-of-range number are
     * reported as field errors instead of escaping as an exception.
     */
    fun validate(inputs: DayEditorInputs): DayDraftErrors {
        val plan = inputs.plan
        val errors = DayDraftErrors(
            wake = wake?.takeIf { SingleDayOverride.parseLocalTime(it) == null }?.let { "响铃时间无效" },
            arrival = arrival?.takeIf { SingleDayOverride.parseLocalTime(it) == null }?.let { "到岗时间无效" },
            preparation = preparationMinutes?.takeIf { it !in 0..240 }?.let { "准备时长需在 0–240 分钟" },
            weather = weatherProfile?.takeIf {
                it.lightMinutes !in 0..60 || it.moderateMinutes !in 0..60 || it.severeMinutes !in 0..60
            }?.let { "天气缓冲每档需在 0–60 分钟" },
            commute = when {
                !commuteEdited && origin == null && destination == null && commuteMode == null -> null
                origin == null || destination == null || commuteMode == null -> "本日通勤需要同时选择起点和终点"
                origin == destination -> "本日通勤的起点和终点不能相同"
                else -> null
            },
        )
        return errors.copy(wake = errors.wake.takeIf { SingleDayOverride.parseLocalTime(plan.defaultWakeLocalTime) == null || it != null })
    }

    /** Snapshot for a valid draft; null when validation fails, so nothing is written. */
    fun toChangeOrNull(inputs: DayEditorInputs): DayOverrideChange? =
        if (validate(inputs).hasErrors) null else toChange(inputs)

    /**
     * Builds the complete replacement snapshot for this date. Every null field means "restore
     * inheritance", so a cleared field really clears the stored value.
     */
    fun toChange(inputs: DayEditorInputs): DayOverrideChange = DayOverrideChange(
        planId = inputs.plan.id,
        date = inputs.effective.date.toString(),
        expectedDayRevision = inputs.dayRevision,
        replacement = SingleDayOverride(
            planId = inputs.plan.id,
            date = inputs.effective.date.toString(),
            status = status,
            wakeLocalTime = wake,
            arrivalLocalTime = arrival,
            preparationMinutes = preparationMinutes,
            weatherProfile = weatherProfile,
            origin = if (commuteEdited) origin else null,
            destination = if (commuteEdited) destination else null,
            commuteMode = if (commuteEdited) commuteMode else null,
        ),
    )

    companion object {
        fun from(inputs: DayEditorInputs): DayOverrideDraft {
            val stored = inputs.dayOverride
            val favorites = inputs.settings.favorites
            return DayOverrideDraft(
                status = stored?.status,
                wake = stored?.wakeLocalTime,
                arrival = stored?.arrivalLocalTime,
                preparationMinutes = stored?.preparationMinutes,
                weatherProfile = stored?.weatherProfile,
                weatherEdited = stored?.weatherProfile != null,
                origin = stored?.origin,
                destination = stored?.destination,
                originFavoriteId = favorites.firstOrNull { it.placeRef == stored?.origin }?.id,
                destinationFavoriteId = favorites.firstOrNull { it.placeRef == stored?.destination }?.id,
                commuteMode = stored?.commuteMode,
                commuteEdited = stored?.hasCompleteCommute == true,
            )
        }
    }
}

@Composable
private fun DayStatusCard(
    planName: String,
    date: LocalDate,
    classification: DayClassification,
    calendarSource: String,
    draftStatus: DayStatus?,
    onStatus: (DayStatus?) -> Unit,
) {
    LocalCard {
        Text("$planName · ${date.format(DateTimeFormatter.ofPattern("M月d日"))}", fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
        Spacer(Modifier.height(4.dp))
        Text(
            "日历判定：$calendarSource · ${baseKindLabel(classification.baseDayKind)}",
            color = ZhituColors.Muted,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                draftStatus == null -> "本日状态：自动（${statusLabel(classification.effectiveStatus)}）"
                else -> "本日状态：手动 ${statusLabel(draftStatus)}"
            },
            color = ZhituColors.Muted,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverrideChoice("自动", draftStatus == null, "day_status_auto") { onStatus(null) }
            OverrideChoice("工作", draftStatus == DayStatus.WORKDAY, "day_status_work") { onStatus(DayStatus.WORKDAY) }
            OverrideChoice("休息", draftStatus == DayStatus.HOLIDAY, "day_status_rest") { onStatus(DayStatus.HOLIDAY) }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "本日上班不会把原始${baseKindLabel(classification.baseDayKind)}改成普通工作日，天气缓冲仍按原始类别选择。",
            color = ZhituColors.Muted,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun DayTimeCard(
    draft: DayOverrideDraft,
    effective: EffectiveDailySettings,
    onPick: (DayTimeField) -> Unit,
    onClear: (DayTimeField) -> Unit,
) {
    LocalCard {
        Text("时间", fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
        Spacer(Modifier.height(8.dp))
        DayValueRow(
            label = "本日响铃时间",
            value = draft.wake,
            inherited = effective.defaultWakeLocalTime,
            source = effective.wakeSource,
            testTag = "day_wake_row",
            onPick = { onPick(DayTimeField.WAKE) },
            onClear = { onClear(DayTimeField.WAKE) },
        )
        Spacer(Modifier.height(8.dp))
        DayValueRow(
            label = "本日到岗时间",
            value = draft.arrival,
            inherited = effective.arrivalLocalTime,
            source = effective.arrivalSource,
            testTag = "day_arrival_row",
            onPick = { onPick(DayTimeField.ARRIVAL) },
            onClear = { onClear(DayTimeField.ARRIVAL) },
        )
    }
}

@Composable
private fun DayValueRow(
    label: String,
    value: String?,
    inherited: String,
    source: DailySettingSource,
    testTag: String,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = ZhituColors.Ink, modifier = Modifier.weight(1f))
        Text(
            text = value ?: "$inherited（${sourceLabel(source)}）",
            color = if (value == null) ZhituColors.Muted else ZhituColors.Ink,
            modifier = Modifier.testTag("${testTag}_value").clickable { onPick() },
        )
        if (value != null) {
            TextButton(onClick = onClear, modifier = Modifier.testTag("${testTag}_clear")) { Text("恢复继承") }
        } else {
            Text(" ›", color = ZhituColors.Subtle)
        }
    }
}

@Composable
private fun DayPreparationCard(
    draft: DayOverrideDraft,
    effective: EffectiveDailySettings,
    planPreparation: Int,
    onPreparation: (Int?) -> Unit,
) {
    LocalCard {
        Text("准备时长", fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
        Spacer(Modifier.height(8.dp))
        Text(
            if (draft.preparationMinutes == null) {
                "沿用 ${planPreparation} 分钟（${sourceLabel(effective.preparationSource)}）"
            } else {
                "本日 ${draft.preparationMinutes} 分钟"
            },
            color = ZhituColors.Muted,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 15, 30, 45, 60, 90).forEach { minutes ->
                OverrideChoice("$minutes", draft.preparationMinutes == minutes, "day_preparation_$minutes") { onPreparation(minutes) }
            }
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = { onPreparation(null) }, modifier = Modifier.testTag("day_preparation_clear")) { Text("恢复继承") }
    }
}

@Composable
private fun DayWeatherCard(
    draft: DayOverrideDraft,
    effective: EffectiveDailySettings,
    inherited: EffectiveDailySettings,
    onProfile: (WeatherBufferProfile?) -> Unit,
) {
    // The step baseline is the real inherited tier, not the factory constant.
    val inherited = bufferBaseline(draft, inherited)
    val profile = draft.weatherProfile ?: inherited
    LocalCard {
        Text("本日天气缓冲", fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
        Spacer(Modifier.height(4.dp))
        Text(
            if (draft.weatherProfile == null) {
                "沿用${weatherKindLabel(effective)}全局档（${sourceLabel(effective.weatherProfileSource)}）：${bufferText(inherited)}"
            } else {
                "本日三档：${bufferText(profile)}"
            },
            color = ZhituColors.Muted,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))
        WeatherBufferStepper("轻微", profile.lightMinutes, "day_buffer_light") { value ->
            onProfile(profile.copy(lightMinutes = value))
        }
        WeatherBufferStepper("中等", profile.moderateMinutes, "day_buffer_moderate") { value ->
            onProfile(profile.copy(moderateMinutes = value))
        }
        WeatherBufferStepper("严重", profile.severeMinutes, "day_buffer_severe") { value ->
            onProfile(profile.copy(severeMinutes = value))
        }
        TextButton(onClick = { onProfile(null) }, modifier = Modifier.testTag("day_buffer_clear")) { Text("恢复继承") }
    }
}

@Composable
private fun WeatherBufferStepper(label: String, value: Int, testTag: String, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = ZhituColors.Ink, modifier = Modifier.weight(1f))
        TextButton(onClick = { onChange((value - 5).coerceAtLeast(0)) }, modifier = Modifier.testTag("${testTag}_minus")) { Text("−5") }
        Text("$value 分钟", color = ZhituColors.Ink, modifier = Modifier.testTag(testTag))
        TextButton(onClick = { onChange((value + 5).coerceAtMost(60)) }, modifier = Modifier.testTag("${testTag}_plus")) { Text("+5") }
    }
}

@Composable
private fun DayCommuteCard(
    draft: DayOverrideDraft,
    planCommute: EffectiveCommute?,
    favorites: List<FavoritePlace>,
    onChange: (DayOverrideDraft) -> Unit,
) {
    LocalCard {
        Text("本日通勤", fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
        Spacer(Modifier.height(4.dp))
        Text(
            if (!draft.commuteEdited) {
                planCommute?.let { "沿用现有通勤：${it.origin.name} → ${it.destination.name}（${sourceLabel(it.source)}）" }
                    ?: "尚未配置可用通勤；本日覆盖需要起点和终点。"
            } else {
                "本日起点、终点和方式整体替换，不与其他层级混用。"
            },
            color = ZhituColors.Muted,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))
        FavoriteSelector("本日起点", draft.originFavoriteId, favorites, tag = "day_origin") { id ->
            onChange(draft.copy(originFavoriteId = id, origin = favorites.firstOrNull { it.id == id }?.placeRef, commuteEdited = true))
        }
        Spacer(Modifier.height(8.dp))
        FavoriteSelector("本日终点", draft.destinationFavoriteId, favorites, tag = "day_destination") { id ->
            onChange(draft.copy(destinationFavoriteId = id, destination = favorites.firstOrNull { it.id == id }?.placeRef, commuteEdited = true))
        }
        Spacer(Modifier.height(10.dp))
        Text("本日出行方式", color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                OverrideChoice("沿用", !draft.commuteEdited, "day_commute_inherit") { onChange(draft.copy(origin = null, destination = null, originFavoriteId = null, destinationFavoriteId = null, commuteMode = null, commuteEdited = false)) }
            }
            items(CommuteMode.entries.toList(), key = CommuteMode::name) { mode ->
                OverrideChoice(mode.label(), draft.commuteMode == mode, "day_commute_mode_${mode.name}") {
                    onChange(draft.copy(commuteMode = mode, commuteEdited = true))
                }
            }
        }
    }
}

@Composable
private fun DayInheritanceSummary(
    effective: EffectiveDailySettings,
    inherited: EffectiveDailySettings,
    draft: DayOverrideDraft,
    persisted: Boolean,
) {
    LocalCard(background = ZhituColors.Mint, modifier = Modifier.testTag("day_summary")) {
        Text(if (persisted) "本日覆盖已保存" else "本日差异预览", fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
        Spacer(Modifier.height(6.dp))
        val rows = buildList {
            add("状态" to (draft.status?.let(::statusLabel) ?: "自动（${statusLabel(effective.classification.effectiveStatus)}）"))
            add("响铃" to (draft.wake ?: effective.defaultWakeLocalTime))
            add("到岗" to (draft.arrival ?: effective.arrivalLocalTime))
            add("准备" to "${draft.preparationMinutes ?: effective.preparationMinutes} 分钟")
            add("天气缓冲" to bufferText(draft.weatherProfile ?: inherited.weatherProfile))
            add(
                "通勤" to when {
                    draft.commuteEdited && draft.origin != null && draft.destination != null && draft.commuteMode != null ->
                        "${draft.origin.name} → ${draft.destination.name} · ${draft.commuteMode.label()}"
                    else -> "沿用现有通勤"
                },
            )
        }
        rows.forEach { (label, value) ->
            Text("$label：$value", color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "只影响当前计划和所选日期；返回不保存，恢复继承会删除本日记录并重算。",
            color = ZhituColors.Muted,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        )
    }
}

internal fun baseKindLabel(kind: DayKind): String = when (kind) {
    DayKind.WORKDAY -> "工作日"
    DayKind.WEEKEND_REST -> "普通周末"
    DayKind.STATUTORY_REST -> "法定休息日"
}

private fun weatherKindLabel(effective: EffectiveDailySettings): String = when (effective.classification.baseDayKind) {
    DayKind.WORKDAY -> "工作日"
    DayKind.WEEKEND_REST -> "普通周末"
    DayKind.STATUTORY_REST -> "法定休息日"
}

private fun bufferText(profile: WeatherBufferProfile): String =
    "${profile.lightMinutes}/${profile.moderateMinutes}/${profile.severeMinutes} 分钟"

internal fun sourceLabel(source: DailySettingSource): String = when (source) {
    DailySettingSource.DAY_OVERRIDE -> "本日覆盖"
    DailySettingSource.PLAN -> "计划配置"
    DailySettingSource.GLOBAL -> "全局默认"
}

internal fun sourceLabel(source: CommuteSource): String = when (source) {
    CommuteSource.DAY_OVERRIDE -> "本日覆盖"
    CommuteSource.PLAN_OVERRIDE -> "本计划通勤"
    CommuteSource.GLOBAL -> "全局通勤"
}

private fun CommuteMode.label(): String = when (this) {
    CommuteMode.DRIVING -> "驾车"
    CommuteMode.TRANSIT -> "公交"
    CommuteMode.WALKING -> "步行"
    CommuteMode.BICYCLING -> "骑行"
    CommuteMode.ELECTRIC_BICYCLE -> "电动车"
}

/**
 * Global commute configuration. Network and map effects deliberately remain in
 * the owner so this composable can keep rendering while a provider is loading
 * or unavailable.
 */
@Composable
fun LocalRouteScreen(
    settings: LocalSettings,
    routeState: RouteUiState,
    mapStatus: MapStatus,
    onSave: (LocalSettings, onComplete: (String?) -> Unit) -> Unit,
    onBack: () -> Unit,
    onModeChange: (CommuteMode) -> Unit,
    onRefresh: () -> Unit,
    onSelectRoute: (String) -> Unit,
    onTrafficChange: (Boolean) -> Unit,
    onPickPlace: ((PlaceSelectionTarget) -> Unit)? = null,
) {
    var favorites by remember(settings) { mutableStateOf(settings.favorites) }
    var originId by remember(settings) { mutableStateOf(settings.originId) }
    var destinationId by remember(settings) { mutableStateOf(settings.destinationId) }
    var mode by remember(settings) { mutableStateOf(settings.commuteMode) }
    var feedback by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = ZhituColors.Background,
        topBar = { ZhituTopBar("通勤路线", "高德地图与实时路况", onBack) },
        bottomBar = {
            Button(
                onClick = {
                    val updated = settings.copy(favorites = favorites, originId = originId, destinationId = destinationId, commuteMode = mode)
                    onSave(updated) { failure -> if (failure == null) onBack() else feedback = failure }
                },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ZhituColors.Brand),
            ) { Text("保存地点与方式") }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                LocalCard(border = BorderStroke(1.dp, ZhituColors.Brand)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("全局通勤", fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
                            Text("计划可单独覆盖", color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = onRefresh) { Text("刷新") }
                    }
                    PlaceSummaryRow("起点", favorites.firstOrNull { it.id == originId }?.name ?: "请选择", onClick = { onPickPlace?.invoke(PlaceSelectionTarget.ORIGIN) })
                    PlaceSummaryRow("终点", favorites.firstOrNull { it.id == destinationId }?.name ?: "请选择", onClick = { onPickPlace?.invoke(PlaceSelectionTarget.DESTINATION) })
                    Text("出行方式", fontWeight = FontWeight.Medium, color = ZhituColors.Ink)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        commuteModes.forEach { item ->
                            FilterChip(selected = mode == item.first, onClick = { mode = item.first; onModeChange(item.first) }, label = { Text(item.second) })
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    RouteMapPanel(routeMapState(settings, routeState), mapStatus, onSelectRoute)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("路线方案", fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
                            Text("最多 3 条", color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                        }
                        FilterChip(selected = routeState.trafficEnabled, onClick = { onTrafficChange(!routeState.trafficEnabled) }, label = { Text("实时路况") })
                    }
                    when {
                        routeState.loading -> Text("正在查询路线…", color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                        routeState.alternatives.isNotEmpty() -> routeState.alternatives.forEachIndexed { index, alternative ->
                            RouteAlternativeRow(alternative, index, routeState.selectedRouteId == alternative.id) { onSelectRoute(alternative.id) }
                        }
                        else -> RouteEmptyState(routeState.message ?: "选择起点和终点后显示最多三条路线、距离和预计时间。")
                    }
                    routeState.refreshError?.let { message ->
                        Text(
                            "更新失败，保留上次结果：$message",
                            color = ZhituColors.Amber,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            feedback?.let { message -> item { LocalInfoCard("无法保存", message, ZhituColors.AmberBackground, ZhituColors.Amber) } }
        }
    }
}

/** Which value receives a confirmed place from [PlacePickerScreen]. */
enum class PlaceSelectionTarget { ORIGIN, DESTINATION, FAVORITE, PLAN_ORIGIN, PLAN_DESTINATION }

/** UI representation intentionally keeps provider DTOs out of Compose state. */
data class PlaceCandidateUi(
    val id: String,
    val name: String,
    val address: String,
    val subtitle: String = "",
    val placeRef: PlaceRef? = null,
)

/**
 * Shared place chooser for the global route and per-plan route editor. The
 * owner debounces [onQueryChanged], maps provider results, and handles map
 * clicks/location permission; this screen only exposes the resulting states.
 */
@Composable
fun PlacePickerScreen(
    target: PlaceSelectionTarget,
    query: String,
    candidates: List<PlaceCandidateUi>,
    loading: Boolean,
    message: String?,
    mapStatus: MapStatus,
    mapState: AmapMapUiState,
    onQueryChanged: (String) -> Unit,
    onUseCurrentLocation: (onComplete: () -> Unit) -> Unit,
    onLocationPermissionDenied: () -> Unit,
    onMapClick: (GeoPoint) -> Unit,
    onConfirm: (PlaceCandidateUi) -> Unit,
    onBack: () -> Unit,
    favorites: List<FavoritePlace> = emptyList(),
    onDeleteFavorite: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestMapStatus by rememberUpdatedState(mapStatus)
    val latestUseCurrentLocation by rememberUpdatedState(onUseCurrentLocation)
    val latestLocationPermissionDenied by rememberUpdatedState(onLocationPermissionDenied)
    var selected by remember(candidates) { mutableStateOf<PlaceCandidateUi?>(candidates.firstOrNull()) }
    var permissionFlowState by remember { mutableStateOf(LocationPermissionFlowState()) }
    var permissionDialog by remember { mutableStateOf<LocationPermissionDialog?>(null) }
    var activeLocationRequestToken by remember { mutableStateOf(0L) }

    fun snapshot() = LocationFlowSnapshot(
        amapConsentGranted = latestMapStatus !is MapStatus.ConsentRequired,
        sdkReady = latestMapStatus is MapStatus.Ready || latestMapStatus is MapStatus.RendererUnavailable,
        locationServiceEnabled = context.locationServiceEnabled(),
        coarseGranted = context.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION),
        fineGranted = context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION),
    )
    val locationAccessStatus = snapshot().accessStatusLabel()

    fun showCommand(command: LocationPermissionCommand) {
        permissionDialog = when (command) {
            LocationPermissionCommand.None,
            LocationPermissionCommand.RequestSystemPermission,
            LocationPermissionCommand.LocateOnce -> null
            LocationPermissionCommand.ShowPurpose -> LocationPermissionDialog.Purpose
            LocationPermissionCommand.ShowLocationServiceRecovery -> LocationPermissionDialog.LocationServiceRecovery
            LocationPermissionCommand.ShowPermissionRecovery -> LocationPermissionDialog.PermissionRecovery
            is LocationPermissionCommand.ShowProviderBlocked -> LocationPermissionDialog.ProviderBlocked(command.reason)
        }
    }

    fun startLocationOnce() {
        permissionDialog = null
        val requestToken = activeLocationRequestToken + 1
        activeLocationRequestToken = requestToken
        latestUseCurrentLocation {
            if (activeLocationRequestToken == requestToken) {
                permissionFlowState = LocationPermissionFlow.onLocationCompleted(permissionFlowState)
            }
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val resultSnapshot = snapshot().copy(
            coarseGranted = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true || context.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION),
            fineGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION),
        )
        val transition = LocationPermissionFlow.onPermissionResult(permissionFlowState, resultSnapshot)
        permissionFlowState = transition.state
        if (transition.command == LocationPermissionCommand.LocateOnce) startLocationOnce()
        else {
            if (transition.command == LocationPermissionCommand.ShowPermissionRecovery) latestLocationPermissionDenied()
            showCommand(transition.command)
        }
    }

    fun applyTransition(transition: LocationPermissionTransition) {
        permissionFlowState = transition.state
        when (transition.command) {
            LocationPermissionCommand.None -> Unit
            LocationPermissionCommand.RequestSystemPermission -> {
                permissionDialog = null
                runCatching {
                    locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }.onFailure {
                    permissionFlowState = LocationPermissionFlow.cancelPending(permissionFlowState)
                    permissionDialog = LocationPermissionDialog.SettingsUnavailable
                }
            }
            LocationPermissionCommand.LocateOnce -> startLocationOnce()
            else -> showCommand(transition.command)
        }
    }

    fun requestCurrentLocation() {
        applyTransition(LocationPermissionFlow.onUseCurrentLocation(permissionFlowState, snapshot()))
    }

    fun cancelLocationFlow() {
        activeLocationRequestToken += 1
        permissionFlowState = LocationPermissionFlow.cancelPending(permissionFlowState)
        permissionDialog = null
    }

    fun openSettings(open: Context.() -> Boolean) {
        permissionFlowState = LocationPermissionFlow.markSettingsOpened(permissionFlowState)
        if (!context.open()) {
            permissionFlowState = LocationPermissionFlow.cancelPending(permissionFlowState)
            permissionDialog = LocationPermissionDialog.SettingsUnavailable
        } else {
            permissionDialog = null
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && permissionFlowState.resumeAfterSettings) {
                applyTransition(LocationPermissionFlow.onSettingsReturned(permissionFlowState, snapshot()))
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            cancelLocationFlow()
        }
    }
    val title = when (target) {
        PlaceSelectionTarget.ORIGIN -> "选择起点"
        PlaceSelectionTarget.DESTINATION -> "选择终点"
        PlaceSelectionTarget.FAVORITE -> "添加常用地点"
        PlaceSelectionTarget.PLAN_ORIGIN -> "选择计划起点"
        PlaceSelectionTarget.PLAN_DESTINATION -> "选择计划终点"
    }
    fun leavePicker() {
        cancelLocationFlow()
        onBack()
    }
    Scaffold(
        containerColor = ZhituColors.Background,
        topBar = { ZhituTopBar(title, "搜索、定位或地图点选", ::leavePicker) },
        bottomBar = {
            Button(
                onClick = {
                    selected?.let { candidate ->
                        cancelLocationFlow()
                        onConfirm(candidate)
                    }
                },
                enabled = selected != null,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ZhituColors.Brand),
            ) { Text("确认地点") }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索地点") },
                    singleLine = true,
                    trailingIcon = {
                        if (loading && query.isNotBlank()) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp).testTag("place_search_loading"),
                                strokeWidth = 2.dp,
                            )
                        }
                    },
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(
                        selected = false,
                        onClick = ::requestCurrentLocation,
                        colors = FilterChipDefaults.filterChipColors(containerColor = ZhituColors.Mint, labelColor = ZhituColors.Brand),
                        border = null,
                        modifier = Modifier.height(48.dp),
                        leadingIcon = { Icon(painterResource(R.drawable.ic_location), contentDescription = null, modifier = Modifier.size(18.dp)) },
                        enabled = !permissionFlowState.locateInFlight,
                        label = { Text(if (permissionFlowState.locateInFlight) "正在获取当前位置" else "使用当前位置") },
                    )
                    favorites.forEach { favorite ->
                        Row(
                            Modifier.clip(RoundedCornerShape(8.dp)).background(ZhituColors.Mint),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = {
                                if (favorite.placeRef == null) onQueryChanged(favorite.name)
                                else selected = PlaceCandidateUi(favorite.id, favorite.name, favorite.address, placeRef = favorite.placeRef)
                            }) { Text(favorite.name, color = ZhituColors.Brand) }
                            Spacer(Modifier.width(1.dp).height(18.dp).background(ZhituColors.Brand.copy(alpha = 0.25f)))
                            IconButton(onClick = {
                                if (selected?.id == favorite.id) selected = null
                                onDeleteFavorite(favorite.id)
                            }) { Icon(painterResource(R.drawable.ic_delete), contentDescription = "删除${favorite.name}", tint = ZhituColors.Brand, modifier = Modifier.size(18.dp)) }
                        }
                    }
                }
            }
            item {
                Text(
                    locationAccessStatus,
                    color = if (locationAccessStatus == "定位服务已关闭") ZhituColors.Amber else ZhituColors.Muted,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    modifier = Modifier.testTag("location_access_status"),
                )
            }
            item {
                Box(Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(16.dp))) {
                    if (mapStatus == MapStatus.Ready) AmapMap(state = mapState, modifier = Modifier.fillMaxSize(), onMapClick = onMapClick)
                    else RouteMapPanel(mapState, mapStatus)
                }
            }
            item { Text("在地图上点击即可选点并进行逆地理编码。", color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.labelSmall) }
            message?.let { item { LocalInfoCard("地点服务", it, ZhituColors.AmberBackground, ZhituColors.Amber) } }
            when {
                loading -> item { LocalInfoCard("正在搜索", "正在获取地点建议。") }
                candidates.isEmpty() && query.isNotBlank() -> item { LocalInfoCard("未找到地点", "请修改关键词，或在地图上点选位置。") }
                candidates.isEmpty() -> item { LocalInfoCard("输入地点名称", "可显示输入提示和 POI 搜索结果。") }
                else -> items(candidates, key = PlaceCandidateUi::id) { candidate ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { selected = candidate },
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = if (selected?.id == candidate.id) ZhituColors.Mint else ZhituColors.Surface),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(candidate.name, color = ZhituColors.Ink, fontWeight = FontWeight.Medium)
                            Text(candidate.address, color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                            if (candidate.subtitle.isNotBlank()) Text(candidate.subtitle, color = ZhituColors.Subtle, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
    permissionDialog?.let { dialog ->
        LocationPermissionDialog(
            dialog = dialog,
            onDismiss = {
                cancelLocationFlow()
            },
            onConfirmPurpose = { applyTransition(LocationPermissionFlow.onPurposeConfirmed(permissionFlowState, snapshot())) },
            onOpenLocationSettings = { openSettings(Context::openLocationServiceSettings) },
            onOpenPermissionSettings = { openSettings(Context::openApplicationPermissionSettings) },
        )
    }
}

private sealed interface LocationPermissionDialog {
    data object Purpose : LocationPermissionDialog
    data object LocationServiceRecovery : LocationPermissionDialog
    data object PermissionRecovery : LocationPermissionDialog
    data class ProviderBlocked(val reason: LocationProviderBlockReason) : LocationPermissionDialog
    data object SettingsUnavailable : LocationPermissionDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationPermissionDialog(
    dialog: LocationPermissionDialog,
    onDismiss: () -> Unit,
    onConfirmPurpose: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenPermissionSettings: () -> Unit,
) {
    val title = when (dialog) {
        LocationPermissionDialog.Purpose -> "使用当前位置"
        LocationPermissionDialog.LocationServiceRecovery -> "定位服务已关闭"
        LocationPermissionDialog.PermissionRecovery -> "请在设置中开启位置权限"
        is LocationPermissionDialog.ProviderBlocked -> "当前位置暂不可用"
        LocationPermissionDialog.SettingsUnavailable -> "无法打开系统设置"
    }
    val description = when (dialog) {
        LocationPermissionDialog.Purpose -> "仅在本次点击后获取前台位置，用于将当前位置填入地点选择。不会后台持续定位；系统可能仅授予大致位置，仍可继续使用。"
        LocationPermissionDialog.LocationServiceRecovery -> "请在系统设置中打开定位服务后返回。也可继续搜索或在地图上点选地点。"
        LocationPermissionDialog.PermissionRecovery -> "请在应用权限设置中恢复位置权限；返回后将仅继续本次定位。仍可继续搜索或地图选点。"
        is LocationPermissionDialog.ProviderBlocked -> when (dialog.reason) {
            LocationProviderBlockReason.CONSENT_REQUIRED -> "请先完成高德地图专项授权，再使用当前位置。"
            LocationProviderBlockReason.SDK_NOT_READY -> "请先配置高德 Android SDK Key 并完成初始化，再使用当前位置。"
        }
        LocationPermissionDialog.SettingsUnavailable -> "请在设备设置中手动恢复定位服务或应用位置权限；仍可继续搜索或地图选点。"
    }
    val confirmLabel = when (dialog) {
        LocationPermissionDialog.Purpose -> "继续"
        LocationPermissionDialog.LocationServiceRecovery -> "前往系统设置"
        LocationPermissionDialog.PermissionRecovery -> "打开应用设置"
        else -> "知道了"
    }
    val confirm = when (dialog) {
        LocationPermissionDialog.Purpose -> onConfirmPurpose
        LocationPermissionDialog.LocationServiceRecovery -> onOpenLocationSettings
        LocationPermissionDialog.PermissionRecovery -> onOpenPermissionSettings
        else -> onDismiss
    }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White, dragHandle = null,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).testTag("location_permission_sheet"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, fontWeight = FontWeight.Bold, color = ZhituColors.Ink, style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            Text(description, color = ZhituColors.Muted)
            Button(onClick = confirm, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = ZhituColors.Brand)) { Text(confirmLabel) }
            if (dialog == LocationPermissionDialog.Purpose || dialog == LocationPermissionDialog.LocationServiceRecovery || dialog == LocationPermissionDialog.PermissionRecovery) {
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(if (dialog == LocationPermissionDialog.Purpose) "取消" else "继续搜索或地图选点")
                }
            }
        }
    }
}

private fun Context.hasPermission(permission: String): Boolean =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

private fun Context.locationServiceEnabled(): Boolean =
    getSystemService(LocationManager::class.java)?.isLocationEnabled == true

private fun Context.openLocationServiceSettings(): Boolean = openSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))

private fun Context.openApplicationPermissionSettings(): Boolean = openSettings(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
)

private fun Context.openSettings(intent: Intent): Boolean =
    if (intent.resolveActivity(packageManager) == null) false else runCatching { startActivity(intent) }.isSuccess

/** A plan-specific draft; it never writes global preferences. */
@Composable
fun PlanCommuteScreen(
    planName: String,
    editor: PlanCommuteEditorState,
    mapStatus: MapStatus,
    onBack: () -> Unit,
    onModeChange: (CommuteMode) -> Unit,
    onPickPlace: (PlaceSelectionTarget) -> Unit,
    onRefresh: () -> Unit,
    onSelectRoute: (String) -> Unit,
    onTrafficChange: (Boolean) -> Unit,
    onSave: () -> Unit,
) {
    val mapState = AmapMapUiState(
        markers = listOfNotNull(editor.origin?.toMarker("plan-origin", "计划起点"), editor.destination?.toMarker("plan-destination", "计划终点")),
        routes = editor.route.alternatives,
        selectedRouteId = editor.route.selectedRouteId,
        trafficEnabled = editor.route.trafficEnabled,
    )
    Scaffold(
        containerColor = ZhituColors.Background,
        topBar = { ZhituTopBar("计划通勤", planName, onBack) },
        bottomBar = {
            Button(onClick = onSave, enabled = editor.planId != null && !editor.loading && editor.loadError == null, modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = ZhituColors.Brand)) { Text("完成通勤配置") }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (editor.loading) item { Text("正在读取通勤配置…") }
            else if (editor.loadError != null) item { LocalInfoCard("读取失败", editor.loadError) }
            else {
                item {
                    LocalCard {
                        Column(Modifier.testTag("plan_commute_${editor.planId}")) {
                            Text(planName, fontWeight = FontWeight.Bold, color = ZhituColors.Ink, modifier = Modifier.testTag("plan_commute_selected_plan"))
                        }

                    }
                }
                if (!editor.useGlobal) {
                    item {
                        LocalCard {
                            Text("专属起点与终点", fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
                            PlaceSummaryRow("起点", editor.origin?.name ?: "请选择", onClick = { onPickPlace(PlaceSelectionTarget.PLAN_ORIGIN) })
                            PlaceSummaryRow("终点", editor.destination?.name ?: "请选择", onClick = { onPickPlace(PlaceSelectionTarget.PLAN_DESTINATION) })
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                commuteModes.forEach { (mode, label) -> FilterChip(selected = editor.mode == mode, onClick = { onModeChange(mode) }, label = { Text(label) }) }
                            }
                        }
                    }
                }
                item { RouteMapPanel(mapState, mapStatus, onSelectRoute) }
                item {
                    LocalCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (editor.useGlobal) "全局有效路线" else "专属路线预览", fontWeight = FontWeight.Bold, color = ZhituColors.Ink, modifier = Modifier.weight(1f))
                            TextButton(onClick = onRefresh) { Text("刷新") }
                        }
                        FilterChip(selected = editor.route.trafficEnabled, onClick = { onTrafficChange(!editor.route.trafficEnabled) }, label = { Text("实时路况") })
                        when {
                            editor.route.loading -> Text("正在查询路线…", color = ZhituColors.Muted)
                            editor.route.alternatives.isNotEmpty() -> editor.route.alternatives.forEachIndexed { index, route ->
                                RouteAlternativeRow(route, index, editor.route.selectedRouteId == route.id) { onSelectRoute(route.id) }
                            }
                            else -> RouteEmptyState(editor.route.message ?: "请选择地点后查询路线。")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceSummaryRow(label: String, value: String, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().height(52.dp).clickable(onClick = onClick),
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(label, color = ZhituColors.Ink, modifier = Modifier.width(52.dp))
    Text(value, color = ZhituColors.Muted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text("›", color = ZhituColors.Subtle, style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
}

@Composable
private fun RouteMapPanel(
    state: AmapMapUiState,
    mapStatus: MapStatus,
    onRouteClick: ((String) -> Unit)? = null,
) = Box(
    Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(16.dp)).background(ZhituColors.Mint),
    contentAlignment = Alignment.Center,
) {
    if (mapStatus == MapStatus.Ready) AmapMap(state, Modifier.fillMaxSize(), onRouteClick = onRouteClick)
    else Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
        Text("地图未就绪", fontWeight = FontWeight.Bold, color = ZhituColors.Ink)
        Spacer(Modifier.height(4.dp))
        Text(mapStatus.label(), color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
    }
}

@Composable
private fun RouteEmptyState(message: String) = Card(
    colors = CardDefaults.cardColors(containerColor = ZhituColors.Sky),
    shape = RoundedCornerShape(16.dp),
) { Text(message, Modifier.padding(12.dp), color = ZhituColors.Blue, style = androidx.compose.material3.MaterialTheme.typography.bodySmall) }

private fun routeMapState(settings: LocalSettings, routeState: RouteUiState): AmapMapUiState {
    val origin = settings.favorites.firstOrNull { it.id == settings.originId }?.placeRef
    val destination = settings.favorites.firstOrNull { it.id == settings.destinationId }?.placeRef
    return AmapMapUiState(
        markers = listOfNotNull(origin?.toMarker("origin", "起点"), destination?.toMarker("destination", "终点")),
        routes = routeState.alternatives,
        selectedRouteId = routeState.selectedRouteId,
        trafficEnabled = routeState.trafficEnabled,
    )
}

private fun PlaceRef.toMarker(id: String, title: String) = com.ljwzz.weathertrafficalarm.core.map.MapMarker(id, GeoPoint(longitudeGcj02, latitudeGcj02), title)
private fun MapStatus.label(): String = when (this) {
    MapStatus.NotInitialized -> "正在初始化高德地图。"
    MapStatus.ConsentRequired -> "请先完成高德地图专项授权。"
    MapStatus.MissingAndroidKey -> "请在凭据页配置高德 Android Key。"
    MapStatus.RendererUnavailable -> "当前模拟器图形环境不兼容高德原生地图；路线结果仍可使用，请在真机验收地图。"
    MapStatus.Failed -> "地图初始化失败，请检查 Key 与网络。"
    MapStatus.Ready -> ""
}

@Composable
private fun RouteAlternativeRow(
    route: RouteAlternative,
    index: Int,
    selected: Boolean,
    onClick: () -> Unit,
) = Card(
    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable(onClick = onClick),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = if (selected) ZhituColors.Mint else ZhituColors.Surface),
    border = BorderStroke(1.dp, if (selected) ZhituColors.Brand else ZhituColors.Line),
) {
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(routeOptionName(index), color = ZhituColors.Ink, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("${route.durationMinutes()} 分钟", color = ZhituColors.Ink, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatDistance(route.distanceMeters), color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            if (selected) Text("当前选择", color = ZhituColors.Brand, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
        }
    }
}

private fun routeOptionName(index: Int): String = if (index == 0) "推荐路线" else "备选 $index"
private fun RouteAlternative.durationMinutes(): Long = ((durationSeconds + 59L) / 60L).coerceAtLeast(1L)
private fun formatDistance(meters: Long): String = if (meters >= 1_000) "%.1f km".format(meters / 1_000.0) else "$meters m"

private val commuteModes = listOf(
    CommuteMode.DRIVING to "驾车", CommuteMode.TRANSIT to "公交", CommuteMode.BICYCLING to "骑行",
    CommuteMode.ELECTRIC_BICYCLE to "电动车", CommuteMode.WALKING to "步行",
)

@Composable private fun CalendarSourceCard(state: CalendarUiState, refresh: (Boolean) -> Unit) = LocalCard(background = if (state.error == null) ZhituColors.Mint else ZhituColors.AmberBackground) {
    Text(if (state.days.isEmpty()) "日历来源 · 星期回退" else "日历来源 · 本地缓存", color = if (state.error == null) ZhituColors.Brand else ZhituColors.Amber, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(4.dp))
    Text(state.error ?: state.fetchedAt?.let { "已加载年度日历" } ?: "首次进入正在检查本地日历。", color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
    state.diagnostics.lastOrNull()?.let { diagnostic ->
        Spacer(Modifier.height(8.dp))
        CalendarRefreshDiagnosticDetails(diagnostic, includeTitle = false)
    }
    TextButton({ refresh(true) }, enabled = !state.loading) { Text(if (state.loading) "正在刷新" else "刷新日历") }
}

@Composable private fun LocalMonthGrid(month: YearMonth, selected: LocalDate, officialDays: Map<String, DayStatus>, overrides: List<SingleDayOverride>, onPrevious: () -> Unit, onNext: () -> Unit, onDate: (LocalDate) -> Unit) = LocalCard {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) { TextButton(onPrevious) { Text("‹") }; Text(month.format(DateTimeFormatter.ofPattern("yyyy年 M月")), modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, color = ZhituColors.Ink); TextButton(onNext) { Text("›") } }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { listOf("一","二","三","四","五","六","日").forEach { Text(it, modifier = Modifier.width(40.dp), textAlign = TextAlign.Center, color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.labelSmall) } }
    val first = month.atDay(1); val start = first.minusDays((first.dayOfWeek.value - 1).toLong()); val overridesByDate = overrides.associateBy(SingleDayOverride::date)
    repeat(6) { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { repeat(7) { column -> val date = start.plusDays((row * 7 + column).toLong()); val inMonth = date.month == month.month; val override = overridesByDate[date.toString()]; val status = override?.status ?: officialDays[date.toString()] ?: fallbackStatus(date); val selectedDay = date == selected; val background = when { selectedDay -> ZhituColors.Brand; override != null -> ZhituColors.Mint; status == DayStatus.HOLIDAY -> ZhituColors.Sky; else -> Color.Transparent }; Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(background).then(if (inMonth) Modifier.clickable { onDate(date) } else Modifier), contentAlignment = Alignment.Center) { Text(date.dayOfMonth.toString(), color = if (selectedDay) Color.White else if (inMonth) ZhituColors.Ink else ZhituColors.Subtle); if (override != null) Text("•", color = ZhituColors.Brand, modifier = Modifier.align(Alignment.BottomCenter), style = androidx.compose.material3.MaterialTheme.typography.labelSmall) } } } }
}

@Composable private fun FavoriteSelector(label: String, selectedId: String?, favorites: List<FavoritePlace>, tag: String? = null, onSelected: (String?) -> Unit) { Column { Text(label, color = ZhituColors.Ink); LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) { item { FilterChip(selected = selectedId == null, onClick = { onSelected(null) }, label = { Text("未选择") }) }; items(favorites, key = FavoritePlace::id) { place -> FilterChip(selected = selectedId == place.id, onClick = { onSelected(place.id) }, modifier = if (tag == null) Modifier else Modifier.testTag("${tag}_${place.id}"), label = { Text(place.name) }) } } } }
@Composable private fun OverrideChoice(label: String, selected: Boolean, tag: String? = null, onClick: () -> Unit) = FilterChip(selected = selected, onClick = onClick, modifier = if (tag == null) Modifier else Modifier.testTag(tag), label = { Text(label) })
@Composable private fun LocalMapPlaceholder() = Box(Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(16.dp)).background(ZhituColors.Mint), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("地图暂未接入", fontWeight = FontWeight.Bold, color = ZhituColors.Ink); Text("可保存地点文字；不会请求定位、路线或距离。", color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.labelSmall) } }
@Composable private fun LocalCard(
    background: Color = ZhituColors.Surface,
    border: BorderStroke? = null,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) = Card(
    modifier = modifier,
    shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(containerColor = background),
    border = border,
) { Column(Modifier.fillMaxWidth().padding(16.dp), content = content) }
@Composable private fun LocalInfoCard(title: String, body: String, background: Color = ZhituColors.Surface, color: Color = ZhituColors.Ink, action: (@Composable () -> Unit)? = null) = LocalCard(background) { Text(title, fontWeight = FontWeight.Bold, color = color); Spacer(Modifier.height(6.dp)); Text(body, color = ZhituColors.Muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall); action?.invoke() }
private fun fallbackStatus(date: LocalDate) = if (date.dayOfWeek.value <= 5) DayStatus.WORKDAY else DayStatus.HOLIDAY
private fun statusLabel(status: DayStatus) = if (status == DayStatus.WORKDAY) "工作日" else "休息日"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalTimePicker(
    initial: String,
    tag: String = "local_time_picker",
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val pieces = initial.split(":")
    val state = rememberTimePickerState(
        initialHour = pieces.getOrNull(0)?.toIntOrNull() ?: 6,
        initialMinute = pieces.getOrNull(1)?.toIntOrNull() ?: 0,
        is24Hour = true,
    )
    TimePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onSave("%02d:%02d".format(state.hour, state.minute)) },
                modifier = Modifier.testTag("${tag}_confirm"),
            ) {
                Text("确定")
            }
        },
        title = { Text("选择时间") },
    ) {
        TimePicker(state)
    }
}
