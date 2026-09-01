package com.ljwzz.weathertrafficalarm.core.data.local

import com.ljwzz.weathertrafficalarm.core.model.DayStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HolidayCalendarCodecTest {

    @Test
    fun validatesSourceYearAndMapsOfficialWorkdayStatus() {
        val document = HolidayCalendarCodec.decodeAndValidate(2026, document(year = 2026))

        assertEquals(
            mapOf("2026-01-01" to DayStatus.HOLIDAY, "2026-01-04" to DayStatus.WORKDAY),
            HolidayCalendarCodec.toStatuses(listOf(document)),
        )
    }

    @Test
    fun rejectsWrongYearAndDuplicateDates() {
        assertThrows(IllegalArgumentException::class.java) {
            HolidayCalendarCodec.decodeAndValidate(2026, document(year = 2025))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HolidayCalendarCodec.decodeAndValidate(2026, document(year = 2026, duplicate = true))
        }
    }

    @Test
    fun laterNoticeYearOverridesDecemberDateFromEarlierDocument() {
        val first = HolidayCalendarCodec.decodeAndValidate(2026, document(year = 2026, firstIsOffDay = true))
        val later = HolidayCalendarCodec.decodeAndValidate(2027, document(year = 2027, date = "2026-01-01", firstIsOffDay = false))

        assertEquals(DayStatus.WORKDAY, HolidayCalendarCodec.toStatuses(listOf(first, later))["2026-01-01"])
    }

    private fun document(
        year: Int,
        date: String = "$year-01-01",
        firstIsOffDay: Boolean = true,
        duplicate: Boolean = false,
    ): String {
        val first = """{"name":"元旦","date":"$date","isOffDay":$firstIsOffDay}"""
        val second = if (duplicate) first else """{"name":"调休","date":"$year-01-04","isOffDay":false}"""
        return """{"year":$year,"papers":["https://www.gov.cn/notice"],"days":[$first,$second]}"""
    }
}
