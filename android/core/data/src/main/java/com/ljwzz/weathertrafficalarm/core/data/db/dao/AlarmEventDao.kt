package com.ljwzz.weathertrafficalarm.core.data.db.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.ljwzz.weathertrafficalarm.core.data.db.entity.AlarmEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(event: AlarmEventEntity)

    @Query("SELECT * FROM alarm_events ORDER BY created_at DESC")
    fun observeAll(): Flow<List<AlarmEventEntity>>

    @Query("DELETE FROM alarm_events WHERE created_at < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long)
}
