package com.ljwzz.weathertrafficalarm.core.data.db.converter

import androidx.room.TypeConverter
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

    @TypeConverter
    fun fromPlaceRef(value: PlaceRef): String = json.encodeToString(PlaceRef.serializer(), value)

    @TypeConverter
    fun toPlaceRef(value: String): PlaceRef = json.decodeFromString(PlaceRef.serializer(), value)

    // --- List<PlaceRef> ---

    @TypeConverter
    fun fromPlaceRefList(value: List<PlaceRef>): String =
        json.encodeToString(ListSerializer(PlaceRef.serializer()), value)

    @TypeConverter
    fun toPlaceRefList(value: String): List<PlaceRef> =
        json.decodeFromString(ListSerializer(PlaceRef.serializer()), value)

    // --- AlarmSound ---

    @TypeConverter
    fun fromAlarmSound(value: AlarmSound): String = json.encodeToString(AlarmSound.serializer(), value)

    @TypeConverter
    fun toAlarmSound(value: String): AlarmSound = json.decodeFromString(AlarmSound.serializer(), value)

    // --- VibrationPattern ---

    @TypeConverter
    fun fromVibrationPattern(value: VibrationPattern): String =
        json.encodeToString(VibrationPattern.serializer(), value)

    @TypeConverter
    fun toVibrationPattern(value: String): VibrationPattern =
        json.decodeFromString(VibrationPattern.serializer(), value)

    // --- Enums stored as strings ---

    @TypeConverter
    fun fromCommuteMode(value: CommuteMode): String = value.name

    @TypeConverter
    fun toCommuteMode(value: String): CommuteMode = CommuteMode.valueOf(value)

    @TypeConverter
    fun fromRoutePolicy(value: RoutePolicy): String = value.name

    @TypeConverter
    fun toRoutePolicy(value: String): RoutePolicy = RoutePolicy.valueOf(value)

    @TypeConverter
    fun fromWorkdayStatus(value: WorkdayStatus?): String? = value?.name

    @TypeConverter
    fun toWorkdayStatus(value: String?): WorkdayStatus? = value?.let { WorkdayStatus.valueOf(it) }

    @TypeConverter
    fun fromFallbackReason(value: FallbackReason): String = value.name

    @TypeConverter
    fun toFallbackReason(value: String): FallbackReason = FallbackReason.valueOf(value)

    @TypeConverter
    fun fromOccurrenceState(value: OccurrenceState): String = value.name

    @TypeConverter
    fun toOccurrenceState(value: String): OccurrenceState = OccurrenceState.valueOf(value)

    @TypeConverter
    fun fromDayStatus(value: DayStatus): String = value.name

    @TypeConverter
    fun toDayStatus(value: String): DayStatus = DayStatus.valueOf(value)
}
