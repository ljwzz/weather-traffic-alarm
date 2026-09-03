package com.ljwzz.weathertrafficalarm.evaluation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** Credential-protected evaluation work starts only after unlock. */
class EvaluationRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return
        val pending = goAsync()
        try {
            EntryPointAccessors.fromApplication(context.applicationContext, EvaluationEntryPoint::class.java)
                .scheduler().recover().invokeOnCompletion { pending.finish() }
        } catch (_: Exception) {
            pending.finish()
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface EvaluationEntryPoint {
    fun scheduler(): EvaluationWorkScheduler
}
