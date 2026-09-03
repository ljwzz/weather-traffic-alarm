package com.ljwzz.weathertrafficalarm.evaluation

import android.content.Context
import android.os.UserManager
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ljwzz.weathertrafficalarm.core.data.repository.AlarmPlanRepository
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
import java.time.LocalTime
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
                scheduler.enqueueRetry(latest, run, retry)
            }
        }
        decisions.deleteOlderThan(clock.instant().minus(Duration.ofDays(30)).toEpochMilli())
        return Result.success()
    }

    private suspend fun recordExpired(plan: AlarmPlan, run: EvaluationWorkRun) {
        val now = clock.instant().toString()
        val baseline = run.targetDate.atTime(LocalTime.parse(plan.defaultWakeLocalTime)).atZone(plan.zoneIdInstance()).toInstant().toString()
        val key = "expired:${plan.id}:${run.revision}:${run.targetDate}:${run.origin}:${run.attempt}"
        decisions.save(AlarmDecision(
            decisionId = UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString(),
            planId = plan.id, planRevision = run.revision, targetDate = run.targetDate.toString(),
            workdayStatus = null, estimatedDepartureAt = null, commuteSeconds = null,
            weatherSeverity = 0, weatherBufferMinutes = 0, recommendedWakeAt = baseline,
            routeProvider = null, routeProviderReportTime = null, weatherProvider = null,
            weatherProviderReportTime = null, weatherWindowStart = null, weatherWindowEnd = null,
            fallbackReason = FallbackReason.STALE_RESPONSE, insufficientAdvance = false,
            generatedAt = now, expiresAt = run.deadline.toString(), evaluationOutcome = EvaluationOutcome.STALE,
            failureReason = "EVALUATION_WINDOW_EXPIRED", attemptNumber = run.attempt,
            applicationOutcome = "STALE", defaultWakeAt = baseline,
        ))
        decisions.deleteOlderThan(clock.instant().minus(Duration.ofDays(30)).toEpochMilli())
    }
}
