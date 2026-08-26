package com.ljwzz.weathertrafficalarm.core.data.db.converter

import androidx.room3.ColumnTypeConverter
import com.ljwzz.weathertrafficalarm.core.model.AlarmSound
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.DayStatus
import com.ljwzz.weathertrafficalarm.core.model.FallbackReason
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceState
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import com.ljwzz.weathertrafficalarm.core.model.RoutePolicy
import com.ljwzz.weathertrafficalarm.core.model.VibrationPattern
import com.ljwzz.weathertrafficalarm.core.model.WeatherSeverity
import com.ljwzz.weathertrafficalarm.core.model.WorkdayStatus
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class Converters {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // --- PlaceRef ---

    @ColumnTypeConverter
    fun fromPlaceRef(value: PlaceRef): String = json.encodeToString(PlaceRef.serializer(), value)

    @ColumnTypeConverter
    fun toPlaceRef(value: String): PlaceRef = json.decodeFromString(PlaceRef.serializer(), value)

    // --- List<PlaceRef> ---

    @ColumnTypeConverter
    fun fromPlaceRefList(value: List<PlaceRef>): String =
        json.encodeToString(ListSerializer(PlaceRef.serializer()), value)

    @ColumnTypeConverter
    fun toPlaceRefList(value: String): List<PlaceRef> =
        json.decodeFromString(ListSerializer(PlaceRef.serializer()), value)

    // --- AlarmSound ---

    @ColumnTypeConverter
    fun fromAlarmSound(value: AlarmSound): String = json.encodeToString(AlarmSound.serializer(), value)

    @ColumnTypeConverter
    fun toAlarmSound(value: String): AlarmSound = json.decodeFromString(AlarmSound.serializer(), value)

    // --- VibrationPattern ---

    @ColumnTypeConverter
    fun fromVibrationPattern(value: VibrationPattern): String =
        json.encodeToString(VibrationPattern.serializer(), value)

    @ColumnTypeConverter
    fun toVibrationPattern(value: String): VibrationPattern =
        json.decodeFromString(VibrationPattern.serializer(), value)

    // --- Enums stored as strings ---

    @ColumnTypeConverter
    fun fromCommuteMode(value: CommuteMode): String = value.name

    @ColumnTypeConverter
    fun toCommuteMode(value: String): CommuteMode = CommuteMode.valueOf(value)

    @ColumnTypeConverter
    fun fromRoutePolicy(value: RoutePolicy): String = value.name

    @ColumnTypeConverter
    fun toRoutePolicy(value: String): RoutePolicy = RoutePolicy.valueOf(value)

    @ColumnTypeConverter
    fun fromWorkdayStatus(value: WorkdayStatus?): String? = value?.name

    @ColumnTypeConverter
    fun toWorkdayStatus(value: String?): WorkdayStatus? = value?.let { WorkdayStatus.valueOf(it) }

    @ColumnTypeConverter
    fun fromFallbackReason(value: FallbackReason): String = value.name

    @ColumnTypeConverter
    fun toFallbackReason(value: String): FallbackReason = FallbackReason.valueOf(value)

    @ColumnTypeConverter
    fun fromOccurrenceState(value: OccurrenceState): String = value.name

    @ColumnTypeConverter
    fun toOccurrenceState(value: String): OccurrenceState = OccurrenceState.valueOf(value)

    @ColumnTypeConverter
    fun fromDayStatus(value: DayStatus): String = value.name

    @ColumnTypeConverter
    fun toDayStatus(value: String): DayStatus = DayStatus.valueOf(value)
}
