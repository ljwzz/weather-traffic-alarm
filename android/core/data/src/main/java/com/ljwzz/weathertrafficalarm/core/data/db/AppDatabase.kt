package com.ljwzz.weathertrafficalarm.core.data.db

import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.ColumnTypeConverters
import com.ljwzz.weathertrafficalarm.core.data.db.converter.Converters
import com.ljwzz.weathertrafficalarm.core.data.db.dao.AlarmDecisionDao
import com.ljwzz.weathertrafficalarm.core.data.db.dao.AlarmEventDao
import com.ljwzz.weathertrafficalarm.core.data.db.dao.AlarmOccurrenceDao
import com.ljwzz.weathertrafficalarm.core.data.db.dao.AlarmPlanDao
import com.ljwzz.weathertrafficalarm.core.data.db.dao.PlanCommuteOverrideDao
import com.ljwzz.weathertrafficalarm.core.data.db.dao.WorkdayOverrideDao
import com.ljwzz.weathertrafficalarm.core.data.db.entity.AlarmDecisionEntity
import com.ljwzz.weathertrafficalarm.core.data.db.entity.AlarmEventEntity
import com.ljwzz.weathertrafficalarm.core.data.db.entity.AlarmOccurrenceEntity
import com.ljwzz.weathertrafficalarm.core.data.db.entity.AlarmPlanEntity
import com.ljwzz.weathertrafficalarm.core.data.db.entity.PlanCommuteOverrideEntity
import com.ljwzz.weathertrafficalarm.core.data.db.entity.WorkdayOverrideEntity

@Database(
    entities = [
        AlarmPlanEntity::class,
        PlanCommuteOverrideEntity::class,
        AlarmDecisionEntity::class,
        AlarmEventEntity::class,
        AlarmOccurrenceEntity::class,
        WorkdayOverrideEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@ColumnTypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun alarmPlanDao(): AlarmPlanDao
    abstract fun planCommuteOverrideDao(): PlanCommuteOverrideDao
    abstract fun alarmDecisionDao(): AlarmDecisionDao
    abstract fun alarmEventDao(): AlarmEventDao
    abstract fun alarmOccurrenceDao(): AlarmOccurrenceDao
    abstract fun workdayOverrideDao(): WorkdayOverrideDao
}
