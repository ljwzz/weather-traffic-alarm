package com.ljwzz.weathertrafficalarm.core.data.local

import android.content.Context
import com.ljwzz.weathertrafficalarm.core.model.DayStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private const val CALENDAR_CACHE_DIRECTORY = "holiday-calendar"
private const val CALENDAR_CACHE_SUFFIX = ".json"
private const val OCTOBER = 10
private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 15_000
private const val MAX_CACHE_AGE_MS = 24 * 60 * 60 * 1_000L

internal interface HolidayCalendarClock {
    fun today(): LocalDate
    fun currentTimeMillis(): Long
}

internal object SystemHolidayCalendarClock : HolidayCalendarClock {
    override fun today(): LocalDate = LocalDate.now()
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}

internal fun interface HolidayCalendarTransport {
    fun get(url: String): String
}

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
                throw IllegalStateException("HTTP ${connection.responseCode}")
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
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context = context,
        clock = SystemHolidayCalendarClock,
        transport = UrlHolidayCalendarTransport,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    /** Serializes refresh operations only; it is deliberately never held by [statuses]. */
    private val refreshMutex = Mutex()
    /** Protects short cache reads/writes while a refresh fetches outside this lock. */
    private val cacheMutex = Mutex()
    private val directory = File(context.filesDir, CALENDAR_CACHE_DIRECTORY)
    private val _state = MutableStateFlow(CalendarUiState())
    val state: StateFlow<CalendarUiState> = _state.asStateFlow()

    init {
        scope.launch { publishCachedState() }
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
            val refreshYears = yearsForRefresh()
            val before = cacheMutex.withLock {
                HolidayCalendarCodec.toStatuses(readCachedDocuments(yearsForState()))
            }
            _state.value = _state.value.copy(loaded = true, loading = true, error = null, days = before)

            val errors = mutableListOf<String>()
            var successfulSource: String? = null
            refreshYears.forEach { year ->
                val shouldFetch = cacheMutex.withLock { force || !hasValidFreshCache(year) }
                if (!shouldFetch) return@forEach

                val download = download(year)
                if (download.isFailure) {
                    errors += "$year: ${download.exceptionOrNull()?.message ?: "refresh failed"}"
                    return@forEach
                }
                val (source, payload) = download.getOrThrow()
                cacheMutex.withLock {
                    writeAtomically(cacheFile(year), payload)
                }
                successfulSource = source
            }

            val afterDocuments = cacheMutex.withLock { readCachedDocuments(yearsForState()) }
            val after = HolidayCalendarCodec.toStatuses(afterDocuments)
            _state.value = CalendarUiState(
                loaded = true,
                loading = false,
                fetchedAt = afterDocuments.maxOfOrNull { cacheFile(it.year).lastModified() }?.takeIf { it > 0L },
                sourceUrl = successfulSource ?: _state.value.sourceUrl,
                error = errors.takeIf { it.isNotEmpty() }?.joinToString(separator = "; "),
                days = after,
            )
            after != before
        }
    }

    private suspend fun publishCachedState() = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            val documents = readCachedDocuments(yearsForState())
            removeObsoleteCacheFiles()
            _state.value = CalendarUiState(
                loaded = true,
                fetchedAt = documents.maxOfOrNull { cacheFile(it.year).lastModified() }?.takeIf { it > 0L },
                days = HolidayCalendarCodec.toStatuses(documents),
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

    private fun download(year: Int): Result<Pair<String, String>> {
        var lastFailure: Throwable? = null
        for (url in sourceUrls(year)) {
            try {
                val payload = transport.get(url)
                HolidayCalendarCodec.decodeAndValidate(year, payload)
                return Result.success(url to payload)
            } catch (failure: Throwable) {
                lastFailure = failure
            }
        }
        return Result.failure(lastFailure ?: IllegalStateException("No calendar source available"))
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
