package com.ljwzz.weathertrafficalarm.core.data.diagnostics

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val MAX_EVENTS = 200
private const val DIAGNOSTICS_DIRECTORY = "diagnostics"
private const val DIAGNOSTICS_FILE = "events.json"
private const val LOCK_FILE = "events.lock"

@Serializable
enum class DiagnosticEventType {
    CALENDAR_REFRESH,
    EVALUATION,
    ALARM_REGISTRATION,
    ALARM_TRIGGER,
    ALARM_DISMISS,
    ALARM_SNOOZE,
    ALARM_MISSED,
    ALARM_CANCEL,
    ALARM_RECOVERY,
    ALARM_PLAYBACK,
    RINGTONE_CHECK,
}

@Serializable
enum class DiagnosticResultCode {
    SUCCESS,
    FAILED,
    CANCELLED,
    SKIPPED,
    CACHE_HIT,
    NETWORK,
    TIMEOUT,
    HTTP,
    VALIDATION,
    STORAGE,
    RATE_LIMITED,
    UNKNOWN,
    STALE,
    NEEDS_PERMISSION,
    NOT_FOUND,
    UNREADABLE,
    DEFAULT_FALLBACK,
    MISSED,
}

/**
 * A locally persisted diagnostic summary. Raw plan and occurrence identifiers are never stored.
 */
@Serializable
data class DiagnosticEvent(
    val eventType: DiagnosticEventType,
    val resultCode: DiagnosticResultCode,
    val appVersion: String,
    val sdkInt: Int,
    val planIdHash: String? = null,
    val occurrenceIdHash: String? = null,
    val durationMs: Long? = null,
    val timestamp: Long,
)

/**
 * Bounded, direct-boot-safe diagnostic event storage.
 *
 * [record] synchronously updates the shared in-process view and atomically persists a bounded
 * device-protected file. A storage failure is deliberately ignored so alarm handling remains
 * unaffected.
 */
@Singleton
class RedactingEventLogger @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val store = DiagnosticEventStore(context.createDeviceProtectedStorageContext())
    private val shared = sharedStates.computeIfAbsent(store.key) { SharedDiagnosticsState(store.read()) }
    val appVersion: String = context.packageManager.runCatching {
        getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "unknown"
    val sdkInt: Int = Build.VERSION.SDK_INT

    val events: StateFlow<List<DiagnosticEvent>> = shared.events.asStateFlow()

    /** Records only fixed categories and hashes identifiers before they leave the caller. */
    fun record(
        eventType: DiagnosticEventType,
        resultCode: DiagnosticResultCode,
        planId: String? = null,
        occurrenceId: String? = null,
        durationMs: Long? = null,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        runCatching {
            val event = DiagnosticEvent(
                eventType = eventType,
                resultCode = resultCode,
                appVersion = appVersion,
                sdkInt = sdkInt,
                planIdHash = planId?.sha256(),
                occurrenceIdHash = occurrenceId?.sha256(),
                durationMs = durationMs?.coerceAtLeast(0L),
                timestamp = timestamp,
            )
            synchronized(shared.lock) {
                val persisted = store.append(event)
                shared.events.value = persisted ?: (shared.events.value + event).takeLast(MAX_EVENTS)
            }
        }
    }

    /** Returns events from oldest to newest. */
    fun recentEvents(): List<DiagnosticEvent> = shared.events.value

    fun clear() {
        runCatching {
            synchronized(shared.lock) {
                if (store.clear()) shared.events.value = emptyList()
            }
        }
    }

    internal companion object {
        private val sharedStates = ConcurrentHashMap<String, SharedDiagnosticsState>()

        internal fun resetSharedStateForTests(context: Context) {
            val key = DiagnosticEventStore(context.createDeviceProtectedStorageContext()).key
            sharedStates.remove(key)
        }
    }
}

private class SharedDiagnosticsState(initialEvents: List<DiagnosticEvent>) {
    val lock = Any()
    val events = MutableStateFlow(initialEvents.takeLast(MAX_EVENTS))
}

internal class DiagnosticEventStore(deviceContext: Context) {
    private val directory = File(deviceContext.noBackupFilesDir, DIAGNOSTICS_DIRECTORY)
    private val file = File(directory, DIAGNOSTICS_FILE)
    private val temporary = File(directory, "$DIAGNOSTICS_FILE.tmp")
    private val lockFile = File(directory, LOCK_FILE)
    val key: String get() = file.absolutePath

    fun read(): List<DiagnosticEvent> = withFileLock {
        readUnlocked()
    } ?: emptyList()

    fun append(event: DiagnosticEvent): List<DiagnosticEvent>? = withFileLock {
        (readUnlocked() + event).takeLast(MAX_EVENTS).also(::writeUnlocked)
    }

    fun clear(): Boolean = withFileLock { writeUnlocked(emptyList()); true } ?: false

    private fun readUnlocked(): List<DiagnosticEvent> {
        if (!file.isFile) return emptyList()
        return runCatching {
            json.decodeFromString<List<DiagnosticEvent>>(file.readText(StandardCharsets.UTF_8)).takeLast(MAX_EVENTS)
        }.getOrDefault(emptyList())
    }

    private fun writeUnlocked(events: List<DiagnosticEvent>) {
        check(directory.exists() || directory.mkdirs()) { "Cannot create diagnostics directory" }
        val bytes = json.encodeToString(events.takeLast(MAX_EVENTS)).toByteArray(StandardCharsets.UTF_8)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun <T> withFileLock(block: () -> T): T? = runCatching {
        check(directory.exists() || directory.mkdirs()) { "Cannot create diagnostics directory" }
        val key = lockFile.absolutePath
        synchronized(processLocks.computeIfAbsent(key) { Any() }) {
            java.nio.channels.FileChannel.open(
                lockFile.toPath(),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE,
            ).use { channel ->
                channel.lock().use { block() }
            }
        }
    }.getOrNull()

    private companion object {
        val processLocks = ConcurrentHashMap<String, Any>()
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    }
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(StandardCharsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
