package com.ljwzz.weathertrafficalarm

import android.Manifest
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljwzz.weathertrafficalarm.core.alarm.AlarmRingingService
import com.ljwzz.weathertrafficalarm.core.alarm.LocalAlarmCoordinator
import com.ljwzz.weathertrafficalarm.core.data.local.CredentialInput
import com.ljwzz.weathertrafficalarm.core.data.local.CredentialStore
import com.ljwzz.weathertrafficalarm.core.data.repository.AlarmEventRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.AlarmPlanRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.OccurrenceRepository
import com.ljwzz.weathertrafficalarm.core.model.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import android.os.ParcelFileDescriptor
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Runs through the actual device AlarmManager, receivers, service and encrypted storage. */
@RunWith(AndroidJUnit4::class)
class LocalAlarmDeviceTest {
    private lateinit var context: Context
    private lateinit var deps: DeviceTestDependencies
    private lateinit var activity: ActivityScenario<MainActivity>
    private val ownedPlans = mutableListOf<String>()

    @Before fun prepare() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        deps = EntryPointAccessors.fromApplication(context, DeviceTestDependencies::class.java)
        shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        activity = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun cleanup() = runBlocking {
        shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        ownedPlans.forEach { deps.coordinator().delete(it) }
        activity.close()
    }

    @Test fun actualAlarmRingsThenDismissesAndRecordsHistory() = runBlocking {
        val plan = futurePlan()
        val saved = deps.coordinator().save(plan)
        assertEquals(AlarmArmedState.SCHEDULED, saved.armedState)
        val occurrence = regularFor(plan.id)
        await { deps.occurrences().getById(occurrence.occurrenceId)?.state == OccurrenceState.FIRING }
        await { AlarmRingingService.activeAlarms.value.any { it.occurrenceId == occurrence.occurrenceId } }
        await {
            context.getSystemService(AudioManager::class.java).activePlaybackConfigurations
                .any { it.audioAttributes.usage == AudioAttributes.USAGE_ALARM }
        }
        deps.coordinator().dismiss(occurrence.occurrenceId)
        deps.coordinator().dismiss(occurrence.occurrenceId)
        await { AlarmRingingService.activeAlarms.value.none { it.occurrenceId == occurrence.occurrenceId } }
        assertEquals(OccurrenceState.DISMISSED, deps.occurrences().getById(occurrence.occurrenceId)?.state)
        assertFalse(requireNotNull(deps.plans().getById(plan.id)).enabled)
        val events = deps.events().observeAll().first().filter { it.occurrenceId == occurrence.occurrenceId }
        assertTrue(events.any { it.type == AlarmEventType.REGISTERED })
        assertTrue(events.any { it.type == AlarmEventType.TRIGGERED })
        assertEquals(1, events.count { it.type == AlarmEventType.DISMISSED })
    }

    @Test fun simultaneousAlarmsRemainIndependent() = runBlocking<Unit> {
        val at = Instant.now().plusSeconds(10)
        val first = futurePlan(at)
        val second = futurePlan(at)
        deps.coordinator().save(first)
        deps.coordinator().save(second)
        val firstOccurrence = regularFor(first.id)
        val secondOccurrence = regularFor(second.id)
        await { AlarmRingingService.activeAlarms.value.map { it.occurrenceId }.containsAll(listOf(firstOccurrence.occurrenceId, secondOccurrence.occurrenceId)) }
        deps.coordinator().dismiss(firstOccurrence.occurrenceId)
        await { AlarmRingingService.activeAlarms.value.none { it.occurrenceId == firstOccurrence.occurrenceId } }
        assertTrue(AlarmRingingService.activeAlarms.value.any { it.occurrenceId == secondOccurrence.occurrenceId })
        assertEquals(OccurrenceState.FIRING, deps.occurrences().getById(secondOccurrence.occurrenceId)?.state)
        deps.coordinator().dismiss(secondOccurrence.occurrenceId)
    }

    @Test fun snoozeTriggersAgainWithoutReplacingTheNextRepeat() = runBlocking {
        val plan = futurePlan().copy(schedule = AlarmSchedule.Weekly((1..7).toSet()), snoozeMinutes = 1)
        deps.coordinator().save(plan)
        val initial = regularFor(plan.id)
        await { deps.occurrences().getById(initial.occurrenceId)?.state == OccurrenceState.FIRING }
        deps.coordinator().snooze(initial.occurrenceId)
        deps.coordinator().snooze(initial.occurrenceId)
        val all = deps.occurrences().getByPlanId(plan.id)
        val snoozed = all.filter { it.kind == OccurrenceKind.SNOOZE }
        assertEquals(1, snoozed.size)
        assertEquals(initial.occurrenceId, snoozed.single().parentOccurrenceId)
        assertTrue(all.any { it.kind == OccurrenceKind.REGULAR && it.state == OccurrenceState.SCHEDULED && it.occurrenceId != initial.occurrenceId })
        await(80_000) { deps.occurrences().getById(snoozed.single().occurrenceId)?.state == OccurrenceState.FIRING }
        deps.coordinator().dismiss(snoozed.single().occurrenceId)
        assertTrue(requireNotNull(deps.plans().getById(plan.id)).enabled)
    }

    @Test fun editingAndDisablingCancelOnlyTheSelectedAlarm() = runBlocking {
        val first = deps.coordinator().save(futurePlan(Instant.now().plusSeconds(300)))
        val second = deps.coordinator().save(futurePlan(Instant.now().plusSeconds(400)))
        val old = regularFor(first.id)
        val updatedTime = Instant.now().plusSeconds(500).atZone(ZoneId.of(first.zoneId))
        deps.coordinator().save(first.copy(defaultWakeLocalTime = updatedTime.toLocalTime().withNano(0).toString(), schedule = AlarmSchedule.Once(updatedTime.toLocalDate().toString())))
        assertEquals(OccurrenceState.CANCELLED, deps.occurrences().getById(old.occurrenceId)?.state)
        assertEquals(1, deps.occurrences().getByPlanId(first.id).count { it.state == OccurrenceState.SCHEDULED })
        deps.coordinator().setEnabled(first.id, false)
        assertFalse(requireNotNull(deps.plans().getById(first.id)).enabled)
        assertTrue(deps.occurrences().getByPlanId(first.id).none { it.state == OccurrenceState.SCHEDULED })
        assertEquals(OccurrenceState.SCHEDULED, regularFor(second.id).state)
    }

    @Test fun encryptedCredentialsPersistMergeAndClear() = runBlocking {
        val store = deps.credentials()
        store.clear()
        val first = "test-only-${UUID.randomUUID()}"
        val second = "test-only-${UUID.randomUUID()}"
        try {
            store.save(CredentialInput(amapWebKey = first))
            store.save(CredentialInput(caiyunAppKey = second))
            val reopened = CredentialStore(context)
            assertTrue(reopened.maskedValues().hasAmapWebKey)
            assertTrue(reopened.maskedValues().hasCaiyunAppKey)
            val plaintext = reopened.credentialsForServiceUse()
            assertEquals(first, plaintext?.amapWebKey)
            assertEquals(second, plaintext?.caiyunAppKey)
            context.noBackupFilesDir.walkTopDown().filter { it.isFile }.forEach {
                val bytes = it.readBytes().toString(Charsets.ISO_8859_1)
                assertFalse(bytes.contains(first))
                assertFalse(bytes.contains(second))
            }
            store.clear()
            assertFalse(store.maskedValues().hasAmapWebKey)
            assertNull(store.credentialsForServiceUse())
        } finally { store.clear() }
    }

    private fun futurePlan(at: Instant = Instant.now().plusSeconds(8)): AlarmPlan {
        val local = at.atZone(ZoneId.systemDefault())
        val id = "device-test-${UUID.randomUUID()}"
        ownedPlans += id
        return AlarmPlan(id = id, revision = 0, name = "设备验证", enabled = true,
            zoneId = local.zone.id, defaultWakeLocalTime = local.toLocalTime().withNano(0).toString(),
            arrivalLocalTime = "09:00", preparationMinutes = 30, maxAdvanceMinutes = 60,
            commuteMode = CommuteMode.DRIVING,
            schedule = AlarmSchedule.Once(local.toLocalDate().toString()))
    }

    private suspend fun regularFor(id: String): AlarmOccurrence = deps.occurrences().getByPlanId(id)
        .first { it.kind == OccurrenceKind.REGULAR && it.state == OccurrenceState.SCHEDULED }

    private suspend fun await(timeout: Long = 30_000, condition: suspend () -> Boolean) {
        withTimeout(timeout) { while (!condition()) delay(100) }
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
        ).use { it.readBytes() }
    }
}
