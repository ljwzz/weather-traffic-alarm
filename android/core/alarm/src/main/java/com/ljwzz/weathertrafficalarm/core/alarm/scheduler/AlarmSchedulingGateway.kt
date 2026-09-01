package com.ljwzz.weathertrafficalarm.core.alarm.scheduler

import com.ljwzz.weathertrafficalarm.core.model.NextAlarmSnapshot

/** Injectable platform boundary used by the coordinator and its tests. */
interface AlarmSchedulingGateway {
    suspend fun schedule(snapshot: NextAlarmSnapshot): AlarmRegistrationResult
    suspend fun restore(
        snapshot: NextAlarmSnapshot,
        nowMillis: Long = System.currentTimeMillis(),
    ): AlarmRegistrationResult
    suspend fun cancelOccurrence(occurrenceId: String)
    fun canScheduleExactAlarms(): Boolean
}
