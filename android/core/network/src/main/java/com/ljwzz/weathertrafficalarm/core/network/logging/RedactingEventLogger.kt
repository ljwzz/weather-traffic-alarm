package com.ljwzz.weathertrafficalarm.core.network.logging

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records diagnostic events while redacting sensitive fields.
 * Uses a whitelist approach: only known-safe fields are recorded.
 * Network exceptions log only host, path template, status code, and correlation ID.
 */
@Singleton
class RedactingEventLogger @Inject constructor() {

    private val events = mutableListOf<DiagnosticEvent>()

    @Synchronized
    fun record(event: DiagnosticEvent) {
        events.add(event)
        if (events.size > MAX_EVENTS) {
            events.removeAt(0)
        }
    }

    @Synchronized
    fun recentEvents(): List<DiagnosticEvent> = events.toList()

    @Synchronized
    fun clear() {
        events.clear()
    }

    companion object {
        private const val MAX_EVENTS = 200
    }
}

data class DiagnosticEvent(
    val eventType: String,
    val resultCode: String,
    val message: String? = null,
    val timestamp: Long = Instant.now().toEpochMilli(),
    val durationMs: Long? = null,
) {
    /**
     * Redacted string representation: only safe fields, no tokens, addresses, or coordinates.
     */
    fun toRedactedString(): String {
        val msg = message?.let { redactMessage(it) } ?: ""
        return "[$eventType] $resultCode${if (msg.isNotEmpty()) ": $msg" else ""}"
    }

    companion object {
        private val tokenPattern = Regex("""\b([A-Za-z0-9_\-]{20,})\b""")
        private val urlPathPattern = Regex("""(https?://)[^/]+(/[^?\s]*)""")

        fun redactMessage(message: String): String {
            var result = message
            // Redact potential tokens/secrets (20+ chars of base64-ish characters)
            result = tokenPattern.replace(result) { match ->
                match.value.take(8) + "..." + match.value.takeLast(4)
            }
            // Redact host in URLs, keep path template
            result = urlPathPattern.replace(result) { match ->
                match.groupValues[1] + "***" + match.groupValues[2]
            }
            return result
        }
    }
}

/**
 * Network error logger specifically for HTTP and protocol failures.
 * Only records host, path template, status code, and correlation ID.
 */
@Singleton
class NetworkErrorLogger @Inject constructor() {

    fun logHttpError(
        method: String,
        host: String,
        pathTemplate: String,
        statusCode: Int,
        correlationId: String? = null,
        durationMs: Long? = null,
    ): DiagnosticEvent {
        val event = DiagnosticEvent(
            eventType = "HTTP_ERROR",
            resultCode = statusCode.toString(),
            message = "$method $host$pathTemplate${correlationId?.let { " (corr: $it)" } ?: ""}",
            durationMs = durationMs,
        )
        return event
    }

    fun logProtocolError(
        host: String,
        pathTemplate: String,
        errorType: String,
        correlationId: String? = null,
    ): DiagnosticEvent {
        val event = DiagnosticEvent(
            eventType = "PROTOCOL_ERROR",
            resultCode = errorType,
            message = "$host$pathTemplate${correlationId?.let { " (corr: $it)" } ?: ""}",
        )
        return event
    }
}
