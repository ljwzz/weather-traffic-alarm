package com.ljwzz.weathertrafficalarm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.ljwzz.weathertrafficalarm.core.data.repository.PlanCommuteOverride
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.AlarmSchedule
import com.ljwzz.weathertrafficalarm.core.model.CommuteMode
import com.ljwzz.weathertrafficalarm.core.model.EvaluationOutcome
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceKind
import com.ljwzz.weathertrafficalarm.core.model.OccurrenceState
import com.ljwzz.weathertrafficalarm.core.model.PlaceRef
import com.ljwzz.weathertrafficalarm.evaluation.EvaluationWorkScheduler
import dagger.hilt.android.EntryPointAccessors
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the installed Hilt Worker. AMap consent is disabled only for this
 * test so evaluation fails before any provider request; no real route or
 * weather request is made.
 */
@RunWith(AndroidJUnit4::class)
class EvaluationDeviceTest {
    @Test
    fun manualEvaluationRecordsFailureWithoutReplacingTheBaselineOccurrence() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deps = EntryPointAccessors.fromApplication(context, DeviceTestDependencies::class.java)
        val originalSettings = deps.settings().loadInitial()
        val planId = "evaluation-device-${UUID.randomUUID()}"

        try {
            deps.settings().update { originalSettings.copy(amapConsentGranted = false) }
            await { !deps.settings().settings.value.amapConsentGranted }
            val plan = futurePlan(planId)
            deps.coordinator().save(plan)
            val baseline = deps.occurrences().getByPlanId(planId).single {
                it.kind == OccurrenceKind.REGULAR && it.state == OccurrenceState.SCHEDULED
            }
            deps.commuteOverrides().save(
                PlanCommuteOverride(
                    planId = planId,
                    origin = place("测试起点", 116.397, 39.908),
                    destination = place("测试终点", 116.407, 39.918),
                    commuteMode = CommuteMode.DRIVING,
                    updatedAt = System.currentTimeMillis(),
                ),
            )

            deps.evaluationScheduler().evaluateNow(planId)
            await {
                deps.decisions().getByPlanId(planId).any { it.evaluationOutcome == EvaluationOutcome.FAILED }
            }

            val after = requireNotNull(deps.occurrences().getById(baseline.occurrenceId))
            assertEquals(baseline.scheduledWakeAt, after.scheduledWakeAt)
            assertEquals(OccurrenceState.SCHEDULED, after.state)
            assertTrue(deps.decisions().getByPlanId(planId).any { it.evaluationOutcome == EvaluationOutcome.FAILED })
        } finally {
            runCatching {
                WorkManager.getInstance(context)
                .cancelAllWorkByTag(EvaluationWorkScheduler.planTag(planId))
                .result
                .get()
            }
            runCatching { deps.commuteOverrides().deleteByPlanId(planId) }
            runCatching { deps.coordinator().delete(planId) }
            deps.settings().update { originalSettings }
        }
    }

    private fun futurePlan(planId: String): AlarmPlan {
        val wake = Instant.now().plusSeconds(20 * 60).atZone(ZoneId.systemDefault())
        return AlarmPlan(
            id = planId,
            revision = 0,
            name = "评估设备验证-$planId",
            enabled = true,
            zoneId = wake.zone.id,
            defaultWakeLocalTime = wake.toLocalTime().withNano(0).toString(),
            arrivalLocalTime = wake.toLocalTime().plusHours(1).withNano(0).toString(),
            preparationMinutes = 20,
            maxAdvanceMinutes = 45,
            commuteMode = CommuteMode.DRIVING,
            schedule = AlarmSchedule.Once(wake.toLocalDate().toString()),
        )
    }

    private fun place(name: String, longitude: Double, latitude: Double) = PlaceRef(
        name = name,
        displayAddress = name,
        longitudeGcj02 = longitude,
        latitudeGcj02 = latitude,
        adcode = "110000",
        citycode = "010",
    )

    private suspend fun await(condition: suspend () -> Boolean) {
        withTimeout(45_000) {
            while (!condition()) delay(150)
        }
    }
}
