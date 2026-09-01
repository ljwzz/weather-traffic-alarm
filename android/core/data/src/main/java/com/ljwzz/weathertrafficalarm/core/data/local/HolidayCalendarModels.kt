package com.ljwzz.weathertrafficalarm.core.data.local

import com.ljwzz.weathertrafficalarm.core.model.DayStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

/** State exposed to the calendar feature. Dates use ISO-8601 LocalDate text. */
data class CalendarUiState(
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val fetchedAt: Long? = null,
    val sourceUrl: String? = null,
    val error: String? = null,
    val days: Map<String, DayStatus> = emptyMap(),
)

@Serializable
internal data class HolidayYearDocument(
    val year: Int,
    val papers: List<String>,
    val days: List<HolidayDocumentDay>,
)

@Serializable
internal data class HolidayDocumentDay(
    val name: String,
    val date: String,
    val isOffDay: Boolean,
)

/**
 * Validates exactly the fields used from holiday-cn before a response reaches disk.
 * A document may contain the following December because the source indexes documents
 * by notice year rather than calendar-date year.
 */
internal object HolidayCalendarCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun decodeAndValidate(expectedYear: Int, payload: String): HolidayYearDocument {
        val document = json.decodeFromString<HolidayYearDocument>(payload)
        require(document.year == expectedYear) { "Calendar year does not match requested year" }
        require(document.papers.isNotEmpty() && document.papers.all { it.startsWith("https://") }) {
            "Calendar papers are missing or invalid"
        }
        require(document.days.isNotEmpty()) { "Calendar days are empty" }

        val dates = HashSet<String>(document.days.size)
        document.days.forEach { day ->
            require(day.name.isNotBlank()) { "Calendar day name is blank" }
            val parsed = runCatching { LocalDate.parse(day.date) }.getOrElse {
                throw IllegalArgumentException("Calendar day date is invalid", it)
            }
            require(parsed.year in (expectedYear - 1)..(expectedYear + 1)) {
                "Calendar day is outside the source document range"
            }
            require(dates.add(day.date)) { "Calendar day is duplicated" }
        }
        return document
    }

    fun toStatuses(documents: Iterable<HolidayYearDocument>): Map<String, DayStatus> = buildMap {
        documents.sortedBy { it.year }.forEach { document ->
            document.days.forEach { day ->
                put(day.date, if (day.isOffDay) DayStatus.HOLIDAY else DayStatus.WORKDAY)
            }
        }
    }
}
