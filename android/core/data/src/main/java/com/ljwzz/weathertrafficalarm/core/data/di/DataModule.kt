package com.ljwzz.weathertrafficalarm.core.data.di

import android.content.Context
import androidx.room3.Room
import com.ljwzz.weathertrafficalarm.core.data.db.AppDatabase
import com.ljwzz.weathertrafficalarm.core.data.db.AppDatabaseMigrations
import com.ljwzz.weathertrafficalarm.core.data.db.dao.AlarmEventDao
import com.ljwzz.weathertrafficalarm.core.data.db.dao.AlarmOccurrenceDao
import com.ljwzz.weathertrafficalarm.core.data.db.dao.AlarmPlanDao
import com.ljwzz.weathertrafficalarm.core.data.db.dao.PlanCommuteOverrideDao
import com.ljwzz.weathertrafficalarm.core.data.db.dao.AlarmDecisionDao
import com.ljwzz.weathertrafficalarm.core.data.db.dao.WorkdayOverrideDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "commute_alarm.db")
            .addMigrations(AppDatabaseMigrations.V1_TO_V2)
            .addMigrations(AppDatabaseMigrations.V2_TO_V3)
            .addMigrations(AppDatabaseMigrations.V3_TO_V4)
            .build()

    @Provides
    fun provideAlarmPlanDao(db: AppDatabase): AlarmPlanDao = db.alarmPlanDao()

    @Provides
    fun providePlanCommuteOverrideDao(db: AppDatabase): PlanCommuteOverrideDao = db.planCommuteOverrideDao()

    @Provides
    fun provideAlarmDecisionDao(db: AppDatabase): AlarmDecisionDao = db.alarmDecisionDao()

    @Provides
    fun provideAlarmOccurrenceDao(db: AppDatabase): AlarmOccurrenceDao = db.alarmOccurrenceDao()

    @Provides
    fun provideWorkdayOverrideDao(db: AppDatabase): WorkdayOverrideDao = db.workdayOverrideDao()

    @Provides
    fun provideAlarmEventDao(db: AppDatabase): AlarmEventDao = db.alarmEventDao()
}
