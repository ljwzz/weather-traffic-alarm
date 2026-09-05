package com.ljwzz.weathertrafficalarm.core.alarm

import com.ljwzz.weathertrafficalarm.core.data.diagnostics.RedactingEventLogger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Accesses device-protected diagnostics without resolving credential-protected repositories. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DiagnosticLoggerEntryPoint {
    fun logger(): RedactingEventLogger
}
