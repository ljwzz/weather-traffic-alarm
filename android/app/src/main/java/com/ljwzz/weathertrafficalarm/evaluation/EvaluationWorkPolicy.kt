package com.ljwzz.weathertrafficalarm.evaluation

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Civil-time policy, independent of WorkManager's inexact execution timing. */
object EvaluationWorkPolicy {
    private val start = LocalTime.of(19, 0)
    private val cutoff = LocalTime.of(23, 30)
    private val retryMinutes = listOf(15L, 30L, 60L)

    fun nextNight(now: Instant, zone: ZoneId, jitterMinutes: Int, futureOnly: Boolean = false): Instant {
        require(jitterMinutes in 0..15)
        val local = now.atZone(zone)
        val date = when {
            futureOnly && !local.toLocalTime().isBefore(start) -> local.toLocalDate().plusDays(1)
            !local.toLocalTime().isBefore(cutoff) -> local.toLocalDate().plusDays(1)
            else -> local.toLocalDate()
        }
        return maxOf(now, date.atTime(start).atZone(zone).plusMinutes(jitterMinutes.toLong()).toInstant())
    }

    fun deadline(evaluationDate: LocalDate, zone: ZoneId): Instant =
        evaluationDate.atTime(cutoff).atZone(zone).toInstant()

    fun retryAt(now: Instant, completedAttempt: Int, deadline: Instant, retryAfterSeconds: Long? = null): Instant? {
        val minutes = retryMinutes.getOrNull(completedAttempt) ?: return null
        val delay = maxOf(Duration.ofMinutes(minutes).seconds, (retryAfterSeconds ?: 0).coerceAtLeast(0))
        // Avoid overflow from an invalid or extremely large Retry-After value.
        if (delay >= Duration.between(now, deadline).seconds) return null
        return now.plusSeconds(delay).takeIf { it.isBefore(deadline) }
    }

    fun mayExecute(now: Instant, notBefore: Instant, deadline: Instant): Boolean =
        !now.isBefore(notBefore) && now.isBefore(deadline)
}
