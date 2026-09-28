package com.ljwzz.weathertrafficalarm.core.data.local

import android.content.Context
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.DiagnosticEventType
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.DiagnosticResultCode
import com.ljwzz.weathertrafficalarm.core.data.diagnostics.RedactingEventLogger
import com.ljwzz.weathertrafficalarm.core.model.DayStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private const val CALENDAR_CACHE_DIRECTORY = "holiday-calendar"
private const val CALENDAR_CACHE_SUFFIX = ".json"
private const val OCTOBER = 10
private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 15_000
private const val MAX_CACHE_AGE_MS = 24 * 60 * 60 * 1_000L
// FR-015: stop a source for the day once its failures exceed three.
private const val DAILY_SOURCE_FAILURE_THRESHOLD = 3
private const val MAX_REFRESH_DIAGNOSTICS = 100

internal interface HolidayCalendarClock {
    fun today(): LocalDate
    fun currentTimeMillis(): Long
    fun elapsedRealtimeMillis(): Long = System.nanoTime() / 1_000_000
}

internal object SystemHolidayCalendarClock : HolidayCalendarClock {
    override fun today(): LocalDate = LocalDate.now()
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}

internal fun interface HolidayCalendarTransport {
    fun get(url: String): String
}

internal class HolidayCalendarHttpException(val statusCode: Int) : IOException()

internal object UrlHolidayCalendarTransport : HolidayCalendarTransport {
    override fun get(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            useCaches = false
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw HolidayCalendarHttpException(connection.responseCode)
            }
            return connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader -> reader.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Offline-first holiday-cn cache. Per-plan overrides are intentionally not stored here;
 * [WorkdayOverrideRepository] remains their source of truth.
 */
@Singleton
class WorkdayCalendarRepository internal constructor(
    private val context: Context,
    private val clock: HolidayCalendarClock,
    private val transport: HolidayCalendarTransport,
    private val scope: CoroutineScope,
    private val diagnosticLogger: RedactingEventLogger? = null,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        diagnosticLogger: RedactingEventLogger,
    ) : this(
        context = context,
        clock = SystemHolidayCalendarClock,
        transport = UrlHolidayCalendarTransport,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        diagnosticLogger = diagnosticLogger,
    )

    /** Compatibility constructor for direct callers that do not create the Hilt graph. */
    constructor(context: Context, clock: Clock = Clock.systemDefaultZone()) : this(
        context = context,
        clock = object : HolidayCalendarClock {
            override fun today(): LocalDate = LocalDate.now(clock)
            override fun currentTimeMillis(): Long = clock.millis()
        },
        transport = UrlHolidayCalendarTransport,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    /** Serializes refresh operations only; it is deliberately never held by [statuses]. */
    private val refreshMutex = Mutex()
    /** Protects short cache reads/writes while a refresh fetches outside this lock. */
    private val cacheMutex = Mutex()
    private val directory = File(context.filesDir, CALENDAR_CACHE_DIRECTORY)
    private val refreshStore = HolidayCalendarRefreshStore(directory)
    private var refreshHistory: CalendarRefreshHistory? = null
    private var diagnosticStorageFailed = false
    private val _state = MutableStateFlow(CalendarUiState())
    val state: StateFlow<CalendarUiState> = _state.asStateFlow()

    init {
        scope.launch { refreshMutex.withLock { publishCachedState() } }
    }

    /** Returns validated cached official days. This never performs network I/O. */
    suspend fun statuses(): Map<String, DayStatus> = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            readCachedDocuments(yearsForState()).let(HolidayCalendarCodec::toStatuses)
        }
    }

    /**
     * Refreshes missing/stale target years, retaining a previous valid cache on every
     * request failure. Returns true only when the effective status map changed.
     */
    suspend fun refresh(force: Boolean = false): Boolean = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            val startedAt = clock.currentTimeMillis()
            val startedElapsed = clock.elapsedRealtimeMillis()
            diagnosticStorageFailed = false
            val refreshYears = yearsForRefresh()
            var before = _state.value.days
            val errors = mutableListOf<Pair<Int?, CalendarRefreshFailure>>()
            val attempts = mutableListOf<CalendarSourceAttempt>()
            val cacheHitYears = mutableListOf<Int>()
            val refreshedYears = mutableListOf<Int>()
            var successfulSource: String? = null
            var changed = false
            try {
                history()
                before = cacheMutex.withLock {
                    removeObsoleteCacheFiles()
                    HolidayCalendarCodec.toStatuses(readCachedDocuments(yearsForState()))
                }
                currentCoroutineContext().ensureActive()
                _state.value = _state.value.copy(loaded = true, loading = true, error = null, days = before)
                refreshYears.forEach { year ->
                    currentCoroutineContext().ensureActive()
                    val shouldFetch = cacheMutex.withLock { force || !hasValidFreshCache(year) }
                    if (!shouldFetch) {
                        cacheHitYears += year
                        return@forEach
                    }

                    val download = download(year, attempts)
                    if (download.failure != null) {
                        errors += year to download.failure
                        return@forEach
                    }
                    currentCoroutineContext().ensureActive()
                    try {
                        cacheMutex.withLock { writeAtomically(cacheFile(year), requireNotNull(download.payload)) }
                        refreshedYears += year
                        successfulSource = download.sourceUrl
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        errors += year to CalendarRefreshFailure.STORAGE
                    }
                }
            } catch (cancelled: CancellationException) {
                errors += null to CalendarRefreshFailure.CANCELLED
                throw cancelled
            } catch (_: Exception) {
                errors += null to CalendarRefreshFailure.UNKNOWN
            } finally {
                withContext(NonCancellable) {
                    if (diagnosticStorageFailed) errors += null to CalendarRefreshFailure.STORAGE
                    val previous = history()
                    val diagnostic = CalendarRefreshDiagnostic(
                        startedAt = startedAt,
                        durationMillis = elapsedSince(startedElapsed),
                        outcome = if (errors.isEmpty()) CalendarRefreshOutcome.SUCCESS else CalendarRefreshOutcome.FAILED,
                        failure = errors.firstOrNull()?.second,
                        consecutiveFailures = if (errors.isEmpty()) 0 else increment(previous.consecutiveFailures),
                        cacheHitYears = cacheHitYears,
                        refreshedYears = refreshedYears,
                        attempts = attempts,
                    )
                    val updated = previous.copy(
                        consecutiveFailures = diagnostic.consecutiveFailures,
                        diagnostics = (previous.diagnostics + diagnostic).takeLast(MAX_REFRESH_DIAGNOSTICS),
                    )
                    if (!saveHistory(updated)) {
                        errors += null to CalendarRefreshFailure.STORAGE
                        val failedDiagnostic = diagnostic.copy(
                            outcome = CalendarRefreshOutcome.FAILED,
                            failure = diagnostic.failure ?: CalendarRefreshFailure.STORAGE,
                            consecutiveFailures = increment(previous.consecutiveFailures),
                        )
                        refreshHistory = updated.copy(
                            consecutiveFailures = failedDiagnostic.consecutiveFailures,
                            diagnostics = updated.diagnostics.dropLast(1) + failedDiagnostic,
                        )
                    }
                    val afterDocuments = cacheMutex.withLock { readCachedDocuments(yearsForState()) }
                    val after = HolidayCalendarCodec.toStatuses(afterDocuments)
                    _state.value = CalendarUiState(
                        loaded = true,
                        loading = false,
                        fetchedAt = afterDocuments.maxOfOrNull { cacheFile(it.year).lastModified() }?.takeIf { it > 0L },
                        sourceUrl = successfulSource ?: _state.value.sourceUrl,
                        error = errors.distinct().takeIf { it.isNotEmpty() }?.joinToString("; ") { (year, failure) ->
                            listOfNotNull(year?.toString(), failureMessage(failure)).joinToString(": ")
                        },
                        days = after,
                        diagnostics = history().diagnostics,
                    )
                    diagnosticLogger?.record(
                        eventType = DiagnosticEventType.CALENDAR_REFRESH,
                        resultCode = calendarDiagnosticResult(errors, cacheHitYears, refreshedYears),
                        durationMs = elapsedSince(startedElapsed),
                        timestamp = startedAt,
                    )
                    changed = after != before
                }
            }
            changed
        }
    }

    private suspend fun publishCachedState() = withContext(Dispatchers.IO) {
        if (_state.value.loaded) return@withContext
        cacheMutex.withLock {
            val documents = readCachedDocuments(yearsForState())
            removeObsoleteCacheFiles()
            _state.value = CalendarUiState(
                loaded = true,
                fetchedAt = documents.maxOfOrNull { cacheFile(it.year).lastModified() }?.takeIf { it > 0L },
                days = HolidayCalendarCodec.toStatuses(documents),
                diagnostics = history().diagnostics,
            )
        }
    }

    /** Years eligible for a network fetch. */
    private fun yearsForRefresh(today: LocalDate = clock.today()): Set<Int> = buildSet {
        add(today.year)
        // The next notice may affect December; begin seeking it once October starts.
        if (today.monthValue >= OCTOBER) add(today.year + 1)
    }

    /**
     * State also retains the preceding notice year when cached. It supports a calendar
     * displaying the preceding December, while a December refresh merges the following
     * notice year so its updated dates take precedence.
     */
    private fun yearsForState(today: LocalDate = clock.today()): Set<Int> = buildSet {
        add(today.year - 1)
        addAll(yearsForRefresh(today))
    }

    private fun readCachedDocuments(years: Set<Int>): List<HolidayYearDocument> = years.mapNotNull { year ->
        val file = cacheFile(year)
        if (!file.isFile) return@mapNotNull null
        runCatching {
            HolidayCalendarCodec.decodeAndValidate(year, file.readText(StandardCharsets.UTF_8))
        }.getOrNull()
    }

    private data class Download(
        val sourceUrl: String? = null,
        val payload: String? = null,
        val failure: CalendarRefreshFailure? = null,
    )

    private suspend fun download(year: Int, attempts: MutableList<CalendarSourceAttempt>): Download {
        var lastFailure = CalendarRefreshFailure.RATE_LIMITED
        for (url in sourceUrls(year)) {
            currentCoroutineContext().ensureActive()
            val host = URL(url).host
            val today = clock.today().toString()
            val stored = history().sources[host]
            val failures = if (stored?.day == today) stored else CalendarSourceFailures(
                day = today,
                consecutiveFailures = stored?.consecutiveFailures ?: 0,
            )
            if (failures.dailyFailures > DAILY_SOURCE_FAILURE_THRESHOLD) {
                attempts += CalendarSourceAttempt(
                    year, host, CalendarSourceOutcome.SKIPPED_LIMIT, CalendarRefreshFailure.RATE_LIMITED,
                    durationMillis = 0, consecutiveFailures = failures.consecutiveFailures, dailyFailures = failures.dailyFailures,
                )
                continue
            }
            val started = clock.elapsedRealtimeMillis()
            var payload: String? = null
            val failure: CalendarRefreshFailure? = try {
                payload = transport.get(url)
                currentCoroutineContext().ensureActive()
                try {
                    HolidayCalendarCodec.decodeAndValidate(year, payload)
                    null
                } catch (_: Exception) {
                    CalendarRefreshFailure.VALIDATION
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: HolidayCalendarHttpException) {
                CalendarRefreshFailure.HTTP
            } catch (_: SocketTimeoutException) {
                CalendarRefreshFailure.TIMEOUT
            } catch (_: IOException) {
                CalendarRefreshFailure.NETWORK
            } catch (_: Exception) {
                CalendarRefreshFailure.UNKNOWN
            }
            val next = failures.copy(
                dailyFailures = if (failure == null) failures.dailyFailures else increment(failures.dailyFailures),
                consecutiveFailures = if (failure == null) 0 else increment(failures.consecutiveFailures),
            )
            saveHistory(history().copy(sources = history().sources + (host to next)))
            attempts += CalendarSourceAttempt(
                year, host, if (failure == null) CalendarSourceOutcome.SUCCESS else CalendarSourceOutcome.FAILED, failure,
                durationMillis = elapsedSince(started), consecutiveFailures = next.consecutiveFailures, dailyFailures = next.dailyFailures,
            )
            if (failure == null) return Download(sourceUrl = url, payload = payload)
            lastFailure = failure
        }
        return Download(failure = lastFailure)
    }

    private fun history(): CalendarRefreshHistory = refreshHistory ?: refreshStore.read().also { refreshHistory = it }

    private fun saveHistory(updated: CalendarRefreshHistory): Boolean {
        refreshHistory = updated
        return try {
            refreshStore.write(updated)
            true
        } catch (_: Exception) {
            diagnosticStorageFailed = true
            false
        }
    }

    private fun elapsedSince(started: Long): Long = (clock.elapsedRealtimeMillis() - started).coerceAtLeast(0L)
    private fun increment(value: Int): Int = if (value == Int.MAX_VALUE) value else value + 1

    private fun failureMessage(failure: CalendarRefreshFailure): String = when (failure) {
        CalendarRefreshFailure.NETWORK -> "日历网络连接失败"
        CalendarRefreshFailure.TIMEOUT -> "日历请求超时"
        CalendarRefreshFailure.HTTP -> "日历服务响应错误"
        CalendarRefreshFailure.VALIDATION -> "日历数据校验失败"
        CalendarRefreshFailure.STORAGE -> "日历本地保存失败"
        CalendarRefreshFailure.RATE_LIMITED -> "日历源已达当日失败限制，次日重试"
        CalendarRefreshFailure.CANCELLED -> "日历刷新已取消"
        CalendarRefreshFailure.UNKNOWN -> "日历刷新失败"
    }

    private fun calendarDiagnosticResult(
        errors: List<Pair<Int?, CalendarRefreshFailure>>,
        cacheHitYears: List<Int>,
        refreshedYears: List<Int>,
    ): DiagnosticResultCode {
        if (errors.isEmpty()) {
            return if (cacheHitYears.isNotEmpty() && refreshedYears.isEmpty()) {
                DiagnosticResultCode.CACHE_HIT
            } else {
                DiagnosticResultCode.SUCCESS
            }
        }
        return when (errors.first().second) {
            CalendarRefreshFailure.NETWORK -> DiagnosticResultCode.NETWORK
            CalendarRefreshFailure.TIMEOUT -> DiagnosticResultCode.TIMEOUT
            CalendarRefreshFailure.HTTP -> DiagnosticResultCode.HTTP
            CalendarRefreshFailure.VALIDATION -> DiagnosticResultCode.VALIDATION
            CalendarRefreshFailure.STORAGE -> DiagnosticResultCode.STORAGE
            CalendarRefreshFailure.RATE_LIMITED -> DiagnosticResultCode.RATE_LIMITED
            CalendarRefreshFailure.CANCELLED -> DiagnosticResultCode.CANCELLED
            CalendarRefreshFailure.UNKNOWN -> DiagnosticResultCode.UNKNOWN
        }
    }

    private fun writeAtomically(destination: File, payload: String) {
        check(directory.exists() || directory.mkdirs()) { "Cannot create calendar cache directory" }
        val temporary = File(directory, "${destination.name}.tmp")
        temporary.outputStream().use { stream ->
            stream.write(payload.toByteArray(StandardCharsets.UTF_8))
            stream.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun removeObsoleteCacheFiles() {
        val minRetainedYear = clock.today().year - 1
        directory.listFiles()?.forEach { file ->
            val year = file.name.removeSuffix(CALENDAR_CACHE_SUFFIX).toIntOrNull()
            if (year != null && file.name.endsWith(CALENDAR_CACHE_SUFFIX) && year < minRetainedYear) file.delete()
        }
    }

    private fun cacheFile(year: Int): File = File(directory, "$year$CALENDAR_CACHE_SUFFIX")
    private fun hasValidFreshCache(year: Int): Boolean {
        val file = cacheFile(year)
        return file.isFile &&
            clock.currentTimeMillis() - file.lastModified() < MAX_CACHE_AGE_MS &&
            runCatching { HolidayCalendarCodec.decodeAndValidate(year, file.readText(StandardCharsets.UTF_8)) }.isSuccess
    }

    private fun sourceUrls(year: Int): List<String> = listOf(
        "https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/$year.json",
        "https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/$year.json",
        "https://fastly.jsdelivr.net/gh/NateScarlet/holiday-cn@master/$year.json",
    )
}
