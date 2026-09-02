package com.ljwzz.weathertrafficalarm

import com.ljwzz.weathertrafficalarm.core.alarm.LocalAlarmCoordinator
import com.ljwzz.weathertrafficalarm.core.data.local.CredentialStore
import com.ljwzz.weathertrafficalarm.core.data.local.WorkdayCalendarRepository
import com.ljwzz.weathertrafficalarm.core.data.preferences.LocalSettingsStore
import com.ljwzz.weathertrafficalarm.core.data.repository.AlarmEventRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.AlarmPlanRepository
import com.ljwzz.weathertrafficalarm.core.data.repository.OccurrenceRepository
import com.ljwzz.weathertrafficalarm.core.model.WeatherProvider
import com.ljwzz.weathertrafficalarm.core.network.caiyun.CaiyunWeatherProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Debug-only access to the real application graph for device integration tests. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DeviceTestDependencies {
    fun coordinator(): LocalAlarmCoordinator
    fun plans(): AlarmPlanRepository
    fun occurrences(): OccurrenceRepository
    fun events(): AlarmEventRepository
    fun credentials(): CredentialStore
    fun settings(): LocalSettingsStore
    fun calendar(): WorkdayCalendarRepository
    fun caiyunWeatherProvider(): CaiyunWeatherProvider
    fun weatherProvider(): WeatherProvider
}
