package com.ljwzz.weathertrafficalarm.core.data.di

import android.content.Context
import androidx.room3.Room
import com.ljwzz.weathertrafficalarm.core.data.db.AppDatabase
import com.ljwzz.weathertrafficalarm.core.data.db.dao.AlarmPlanDao
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
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideAlarmPlanDao(db: AppDatabase): AlarmPlanDao = db.alarmPlanDao()
}
