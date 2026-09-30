package com.ljwzz.weathertrafficalarm.core.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.ColumnTypeConverters
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.ljwzz.weathertrafficalarm.core.data.db.converter.Converters
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.DayStatus
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef

/**
 * Recoverable single-day change that has been allocated a candidate revision.
 *
 * The row carries the complete replacement snapshot and survives a process kill: [published]
 * records whether the device-protected snapshot and commit credential were already written, so
 * recovery either finishes this commit or discards an unpublished candidate. All payload columns
 * being null means the committed change deletes the override row.
 */
@Entity(
    tableName = "day_override_commits",
    foreignKeys = [
        ForeignKey(
            entity = AlarmPlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["plan_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("plan_id"),
    ],
)
@ColumnTypeConverters(Converters::class)
data class DayOverrideCommitEntity(
    @PrimaryKey @ColumnInfo(name = "change_id") val changeId: String,
    @ColumnInfo(name = "plan_id") val planId: String,
    @ColumnInfo(name = "date") val date: String,
    @ColumnInfo(name = "candidate_revision") val candidateRevision: Long,
    @ColumnInfo(name = "previous_revision") val previousRevision: Long,
    @ColumnInfo(name = "candidate_occurrence_id") val candidateOccurrenceId: String? = null,
    /** Comma-separated occurrence ids this change invalidates; replayed by recovery. */
    @ColumnInfo(name = "cancelled_occurrence_ids", defaultValue = "") val cancelledOccurrenceIds: String = "",
    @ColumnInfo(name = "published", defaultValue = "0") val published: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "status") val status: DayStatus? = null,
    @ColumnInfo(name = "wake_local_time") val wakeLocalTime: String? = null,
    @ColumnInfo(name = "arrival_local_time") val arrivalLocalTime: String? = null,
    @ColumnInfo(name = "preparation_minutes") val preparationMinutes: Int? = null,
    @ColumnInfo(name = "weather_severity1_minutes") val weatherSeverity1Minutes: Int? = null,
    @ColumnInfo(name = "weather_severity2_minutes") val weatherSeverity2Minutes: Int? = null,
    @ColumnInfo(name = "weather_severity3_minutes") val weatherSeverity3Minutes: Int? = null,
    @ColumnInfo(name = "origin") val origin: PlaceRef? = null,
    @ColumnInfo(name = "destination") val destination: PlaceRef? = null,
    @ColumnInfo(name = "commute_mode") val commuteMode: CommuteMode? = null,
) {
    /** Occurrence ids that must end up cancelled when this change commits. */
    val cancelledIds: List<String>
        get() = cancelledOccurrenceIds.split(',').map(String::trim).filter(String::isNotEmpty)

    /** True when committing this change removes the override row instead of writing one. */
    val isDeletion: Boolean
        get() = status == null &&
            wakeLocalTime == null &&
            arrivalLocalTime == null &&
            preparationMinutes == null &&
            weatherSeverity1Minutes == null &&
            weatherSeverity2Minutes == null &&
            weatherSeverity3Minutes == null &&
            origin == null &&
            destination == null &&
            commuteMode == null

    /** The override row this change commits, carrying the allocated candidate revision. */
    fun toOverrideEntity(): WorkdayOverrideEntity = WorkdayOverrideEntity(
        planId = planId,
        date = date,
        status = status,
        wakeLocalTime = wakeLocalTime,
        arrivalLocalTime = arrivalLocalTime,
        preparationMinutes = preparationMinutes,
        weatherSeverity1Minutes = weatherSeverity1Minutes,
        weatherSeverity2Minutes = weatherSeverity2Minutes,
        weatherSeverity3Minutes = weatherSeverity3Minutes,
        origin = origin,
        destination = destination,
        commuteMode = commuteMode,
        dayRevision = candidateRevision,
    )

    companion object {
        /** Full snapshot of [override]; null or an empty snapshot becomes a deletion. */
        fun of(
            changeId: String,
            planId: String,
            date: String,
            candidateRevision: Long,
            previousRevision: Long,
            candidateOccurrenceId: String?,
            cancelledOccurrenceIds: List<String>,
            published: Boolean,
            createdAt: Long,
            override: WorkdayOverrideEntity?,
        ): DayOverrideCommitEntity {
            val payload = override?.takeUnless { it.isEmptySnapshot() }
            return DayOverrideCommitEntity(
                changeId = changeId,
                planId = planId,
                date = date,
                candidateRevision = candidateRevision,
                previousRevision = previousRevision,
                candidateOccurrenceId = candidateOccurrenceId,
                cancelledOccurrenceIds = cancelledOccurrenceIds.joinToString(","),
                published = published,
                createdAt = createdAt,
                status = payload?.status,
                wakeLocalTime = payload?.wakeLocalTime,
                arrivalLocalTime = payload?.arrivalLocalTime,
                preparationMinutes = payload?.preparationMinutes,
                weatherSeverity1Minutes = payload?.weatherSeverity1Minutes,
                weatherSeverity2Minutes = payload?.weatherSeverity2Minutes,
                weatherSeverity3Minutes = payload?.weatherSeverity3Minutes,
                origin = payload?.origin,
                destination = payload?.destination,
                commuteMode = payload?.commuteMode,
            )
        }

        private fun WorkdayOverrideEntity.isEmptySnapshot(): Boolean =
            status == null &&
                wakeLocalTime == null &&
                arrivalLocalTime == null &&
                preparationMinutes == null &&
                weatherSeverity1Minutes == null &&
                weatherSeverity2Minutes == null &&
                weatherSeverity3Minutes == null &&
                origin == null &&
                destination == null &&
                commuteMode == null
    }
}
