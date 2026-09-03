package com.ljwzz.weathertrafficalarm.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object EvaluationModule {
    @Provides
    @Singleton
    fun provideEvaluationClock(): Clock = Clock.systemUTC()
}
