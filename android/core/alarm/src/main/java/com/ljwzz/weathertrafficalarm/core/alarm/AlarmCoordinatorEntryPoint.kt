package com.ljwzz.weathertrafficalarm.core.alarm

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Lazily accessed only after credential-encrypted storage is unlocked. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AlarmCoordinatorEntryPoint {
    fun coordinator(): LocalAlarmCoordinator
}
