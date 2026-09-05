package com.ljwzz.weathertrafficalarm.core.data.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
enum class CalendarRefreshOutcome {
    SUCCESS,
    FAILED,
}

@Serializable
enum class CalendarRefreshFailure {
    NETWORK,
    TIMEOUT,
    HTTP,
    VALIDATION,
    STORAGE,
    RATE_LIMITED,
    CANCELLED,
    UNKNOWN,
}

@Serializable
enum class CalendarSourceOutcome {
    SUCCESS,
    FAILED,
    SKIPPED_LIMIT,
}

/** One fetch decision for a source. Only the host and fixed failure category are retained. */
@Serializable
data class CalendarSourceAttempt(
    val year: Int,
    val sourceHost: String,
    val outcome: CalendarSourceOutcome,
    val failure: CalendarRefreshFailure? = null,
    val durationMillis: Long,
    val consecutiveFailures: Int,
    val dailyFailures: Int,
)

/** A refresh summary safe for local diagnostics. It never contains response bodies or exception text. */
@Serializable
data class CalendarRefreshDiagnostic(
    val startedAt: Long,
    val durationMillis: Long,
    val outcome: CalendarRefreshOutcome,
    val failure: CalendarRefreshFailure? = null,
    val consecutiveFailures: Int,
    val cacheHitYears: List<Int> = emptyList(),
    val refreshedYears: List<Int> = emptyList(),
    val attempts: List<CalendarSourceAttempt> = emptyList(),
)

@Serializable
internal data class CalendarSourceFailures(
    val day: String,
    val dailyFailures: Int = 0,
    val consecutiveFailures: Int = 0,
)

@Serializable
internal data class CalendarRefreshHistory(
    val sources: Map<String, CalendarSourceFailures> = emptyMap(),
    val consecutiveFailures: Int = 0,
    val diagnostics: List<CalendarRefreshDiagnostic> = emptyList(),
)

/**
 * Atomic local persistence for calendar refresh diagnostics. Callers serialize read-modify-write
 * access so rate-limit counters and refresh summaries cannot overwrite each other.
 */
internal class HolidayCalendarRefreshStore(private val directory: File) {
    private val file = File(directory, DIAGNOSTICS_FILE_NAME)
    private val temporary = File(directory, "$DIAGNOSTICS_FILE_NAME.tmp")

    fun read(): CalendarRefreshHistory {
        if (!file.isFile) return CalendarRefreshHistory()

        return runCatching {
            file.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                json.decodeFromString<CalendarRefreshHistory>(reader.readText())
            }
        }.getOrDefault(CalendarRefreshHistory())
    }

    /** Writes only the most recent [MAX_DIAGNOSTICS] records from an oldest-to-newest input. */
    fun write(history: CalendarRefreshHistory) {
        check(directory.exists() || directory.mkdirs()) { "Cannot create calendar diagnostics directory" }
        val contents = json.encodeToString(
            history.copy(diagnostics = history.diagnostics.takeLast(MAX_DIAGNOSTICS)),
        ).toByteArray(StandardCharsets.UTF_8)
        try {
            temporary.outputStream().use { output ->
                output.write(contents)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private companion object {
        const val DIAGNOSTICS_FILE_NAME = "refresh-diagnostics.json"
        const val MAX_DIAGNOSTICS = 100
        val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}
