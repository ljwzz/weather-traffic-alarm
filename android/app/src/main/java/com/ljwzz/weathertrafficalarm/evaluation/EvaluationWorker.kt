package com.ljwzz.weathertrafficalarm.evaluation

import android.content.Context
import android.os.UserManager
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ljwzz.weathertrafficalarm.core.data.repository.AlarmPlanRepository
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.DiagnosticEventType
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.DiagnosticResultCode
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.RedactingEventLogger
import com.ljwzz.weathertrafficalarm.core.data.repository.DecisionRepository
import com.ljwzz.weathertrafficalarm.core.model.AlarmDecision
import com.ljwzz.weathertrafficalarm.core.model.AlarmPlan
import com.ljwzz.weathertrafficalarm.core.model.EvaluationOutcome
import com.ljwzz.weathertrafficalarm.core.model.FallbackReason
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Duration
import java.util.UUID

@HiltWorker
class EvaluationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val plans: AlarmPlanRepository,
    private val coordinator: EvaluationCoordinator,
    private val scheduler: EvaluationWorkScheduler,
    private val decisions: DecisionRepository,
    private val clock: Clock,
    private val diagnostics: RedactingEventLogger,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!applicationContext.getSystemService(UserManager::class.java).isUserUnlocked) return Result.retry()
        val planId = inputData.getString(EvaluationWorkScheduler.PLAN_ID) ?: return Result.failure()
        val run = EvaluationWorkRun.fromTags(tags) ?: return Result.failure()
        val plan = plans.getById(planId)?.takeIf { it.enabled } ?: return Result.success()
        // Arrange future work before any potentially failing network request.
        scheduler.ensureNightly(plan)
        val now = clock.instant()
        if (plan.revision != run.revision || plan.zoneId != run.zoneId ||
            !EvaluationWorkPolicy.mayExecute(now, run.notBefore, run.deadline)) {
            recordExpired(plan, run)
            return Result.success()
        }
        val result = try {
            coordinator.evaluate(planId, attemptNumber = run.attempt, targetDate = run.targetDate,
                deadline = run.deadline, evaluationId = id.toString())
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
        // A fresh repository read also prevents disabled or edited plans from creating retries.
        val latest = plans.getById(planId)
        if (result.retryable && latest?.enabled == true && latest.revision == run.revision) {
            val retryDeadline = minOf(run.deadline,
                EvaluationWorkPolicy.deadline(clock.instant().atZone(plan.zoneIdInstance()).toLocalDate(), plan.zoneIdInstance()))
            EvaluationWorkPolicy.retryAt(clock.instant(), run.attempt, retryDeadline, result.retryAfterSeconds)?.let { retry ->
                result.decision?.decisionId?.let { decisionId -> scheduler.enqueueRetry(latest, run, retry, decisionId) }
            }
        }
        decisions.deleteOlderThan(clock.instant().minus(Duration.ofDays(30)).toEpochMilli())
        return Result.success()
    }

    private suspend fun recordExpired(plan: AlarmPlan, run: EvaluationWorkRun) {
        decisions.save(expiredDecision(plan, run, id.toString(), clock.instant()))
        diagnostics.record(DiagnosticEventType.EVALUATION, DiagnosticResultCode.STALE,
            planId = plan.id, timestamp = clock.millis())
        decisions.deleteOlderThan(clock.instant().minus(Duration.ofDays(30)).toEpochMilli())
    }
}

/**
 * Produces a record for this exact Worker attempt. A current plan can only supply historical
 * display fields when it is the same evaluated revision and civil-time zone as the run.
 */
internal fun expiredDecision(
    plan: AlarmPlan,
    run: EvaluationWorkRun,
    workId: String,
    now: java.time.Instant,
): AlarmDecision {
    val inputsUnchanged = plan.revision == run.revision && plan.zoneId == run.zoneId
    val baseline = if (inputsUnchanged) {
        runCatching {
            run.targetDate.atTime(java.time.LocalTime.parse(plan.defaultWakeLocalTime))
                .atZone(java.time.ZoneId.of(run.zoneId)).toInstant().toString()
        }.getOrNull()
    } else null
    return AlarmDecision(
        decisionId = UUID.nameUUIDFromBytes("expired-worker:$workId".toByteArray(Charsets.UTF_8)).toString(),
        planId = plan.id, planRevision = run.revision, targetDate = run.targetDate.toString(),
        workdayStatus = null, estimatedDepartureAt = null, commuteSeconds = null,
        weatherSeverity = 0, weatherBufferMinutes = 0, recommendedWakeAt = baseline.orEmpty(),
        routeProvider = null, routeProviderReportTime = null, weatherProvider = null,
        weatherProviderReportTime = null, weatherWindowStart = null, weatherWindowEnd = null,
        fallbackReason = FallbackReason.STALE_RESPONSE, insufficientAdvance = false,
        generatedAt = now.toString(), expiresAt = run.deadline.toString(), evaluationOutcome = EvaluationOutcome.STALE,
        failureReason = if (inputsUnchanged) "EVALUATION_WINDOW_EXPIRED" else "EVALUATION_INPUTS_CHANGED",
        attemptNumber = run.attempt, applicationOutcome = "NOT_APPLIED",
        preparationMinutes = if (inputsUnchanged) plan.preparationMinutes else 0,
        defaultWakeAt = baseline, planName = plan.name.takeIf { inputsUnchanged }, zoneId = run.zoneId,
    )
}
