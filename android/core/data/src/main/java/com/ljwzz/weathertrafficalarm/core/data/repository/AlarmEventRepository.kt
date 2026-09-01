package com.ljwzz.weathertrafficalarm.core.data.repository

import com.ljwzz.weathertrafficalarm.core.data.db.dao.AlarmEventDao
import com.ljwzz.weathertrafficalarm.core.data.mapper.toDomain
import com.ljwzz.weathertrafficalarm.core.data.mapper.toEntity
import com.ljwzz.weathertrafficalarm.core.model.AlarmEvent
import com.ljwzz.weathertrafficalarm.core.model.AlarmEventType
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlarmEventRepository @Inject constructor(
    private val eventDao: AlarmEventDao,
) {
    suspend fun record(
        planId: String,
        occurrenceId: String? = null,
        type: AlarmEventType,
        message: String,
    ): AlarmEvent {
        val event = AlarmEvent(
            id = UUID.randomUUID().toString(),
            planId = planId,
            occurrenceId = occurrenceId,
            type = type,
            message = message,
        )
        eventDao.upsert(event.toEntity())
        return event
    }

    fun observeAll(): Flow<List<AlarmEvent>> =
        eventDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    suspend fun clearOlderThan30Days(nowMillis: Long = System.currentTimeMillis()) {
        eventDao.deleteOlderThan(nowMillis - THIRTY_DAYS_MILLIS)
    }

    private companion object {
        const val THIRTY_DAYS_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}
