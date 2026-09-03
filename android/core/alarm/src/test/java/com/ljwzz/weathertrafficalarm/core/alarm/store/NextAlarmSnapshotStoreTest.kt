package com.ljwzz.weathertrafficalarm.core.alarm.store

import com.ljwzz.weathertrafficalarm.core.model.NextAlarmSnapshot
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextAlarmSnapshotStoreTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun snapshotSerializationRoundTrip() {
        val snapshot = NextAlarmSnapshot(
            occurrenceId = "occ-1",
            planId = "plan-1",
            planRevision = 1,
            triggerAtMillis = 1690264200000L,
            soundUri = "content://media/ringtone",
            vibrationEnabled = true,
            snoozeMinutes = 10,
        )
        val serialized = json.encodeToString(snapshot)
        assertTrue(serialized.contains("occ-1"))
        assertTrue(serialized.contains("plan-1"))

        val deserialized = json.decodeFromString<NextAlarmSnapshot>(serialized)
        assertEquals("occ-1", deserialized.occurrenceId)
        assertEquals(1690264200000L, deserialized.triggerAtMillis)
        assertTrue(deserialized.vibrationEnabled)
    }

    @Test
    fun legacySnapshotJsonUsesDefaultActionReceiptFields() {
        val legacy = """
            {"occurrenceId":"occ-1","planId":"plan-1","planRevision":1,
             "triggerAtMillis":1000,"soundUri":null,"vibrationEnabled":true,
             "snoozeMinutes":10}
        """.trimIndent()

        val restored = json.decodeFromString<NextAlarmSnapshot>(legacy)

        assertEquals(0L, restored.actionRevision)
        assertEquals(null, restored.actionError)
    }

    @Test
    fun snapshotToStringDoesNotContainCoordinates() {
        val snapshot = NextAlarmSnapshot(
            occurrenceId = "occ-1",
            planId = "plan-1",
            planRevision = 1,
            triggerAtMillis = 1000L,
            soundUri = null,
            vibrationEnabled = true,
            snoozeMinutes = 10,
        )
        val str = snapshot.toString()
        assertTrue(str.contains("plan-1"))
        // No coordinates or addresses
        assertFalse(str.contains("longitude"))
        assertFalse(str.contains("latitude"))
    }

    @Test
    fun snapshotValuesAreCorrect() {
        val snapshot = NextAlarmSnapshot(
            occurrenceId = "occ-1",
            planId = "plan-1",
            planRevision = 2,
            triggerAtMillis = 1000L,
            soundUri = "uri",
            vibrationEnabled = false,
            snoozeMinutes = 5,
        )
        assertEquals("occ-1", snapshot.occurrenceId)
        assertEquals(2L, snapshot.planRevision)
        assertFalse(snapshot.vibrationEnabled)
        assertEquals(5, snapshot.snoozeMinutes)
    }

    @Test
    fun directBootFieldsPreserveOnlyCurrentOccurrenceData() {
        val snapshot = NextAlarmSnapshot(
            occurrenceId = "snooze-2",
            planId = "plan-1",
            planRevision = 4,
            triggerAtMillis = 2_000L,
            soundUri = null,
            vibrationEnabled = true,
            snoozeMinutes = 10,
            occurrenceKind = "SNOOZE",
            parentOccurrenceId = "regular-1",
            occurrenceState = "SCHEDULED",
            snoozeCount = 2,
        )

        val restored = json.decodeFromString<NextAlarmSnapshot>(json.encodeToString(snapshot))
        assertEquals("SNOOZE", restored.occurrenceKind)
        assertEquals("regular-1", restored.parentOccurrenceId)
        assertEquals(2, restored.snoozeCount)
        assertEquals("SCHEDULED", restored.occurrenceState)
    }

    @Test
    fun actionReceiptFieldsRoundTripWithoutSensitiveDetails() {
        val snapshot = NextAlarmSnapshot(
            occurrenceId = "occ-1",
            planId = "plan-1",
            planRevision = 1,
            triggerAtMillis = 1_000L,
            soundUri = null,
            vibrationEnabled = true,
            snoozeMinutes = 10,
            occurrenceState = "FIRING",
            actionRevision = 3,
            actionError = "贪睡未能注册，请重试或停止闹钟",
        )

        val restored = json.decodeFromString<NextAlarmSnapshot>(json.encodeToString(snapshot))

        assertEquals(3L, restored.actionRevision)
        assertEquals("贪睡未能注册，请重试或停止闹钟", restored.actionError)
    }
}
