package com.ljwzz.weathertrafficalarm.core.network.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactingEventLoggerTest {

    @Test
    fun recordAndRetrieveEvents() {
        val logger = RedactingEventLogger()
        val event = DiagnosticEvent(
            eventType = "TEST",
            resultCode = "OK",
            message = "test event",
        )
        logger.record(event)
        val events = logger.recentEvents()
        assertEquals(1, events.size)
        assertEquals("TEST", events[0].eventType)
    }

    @Test
    fun clearRemovesEvents() {
        val logger = RedactingEventLogger()
        logger.record(DiagnosticEvent(eventType = "A", resultCode = "1"))
        logger.clear()
        assertTrue(logger.recentEvents().isEmpty())
    }

    @Test
    fun redactMessageReplacesLongTokens() {
        val longToken = "abcdefghijklmnopqrstuvwxyz012345"
        val result = DiagnosticEvent.redactMessage("token=$longToken")
        assertFalse("Full token should not be visible", result.contains(longToken))
    }

    @Test
    fun redactMessageReplacesHostInUrl() {
        val result = DiagnosticEvent.redactMessage("https://amap.com/api/route")
        assertTrue(result.contains("https://***"))
        assertTrue(result.contains("/api/route"))
    }

    @Test
    fun shortStringIsNotRedacted() {
        val short = "shortstring"
        val result = DiagnosticEvent.redactMessage("key=$short")
        assertEquals("key=$short", result)
    }

    @Test
    fun networkErrorLoggerDoesNotContainToken() {
        val logger = NetworkErrorLogger()
        val event = logger.logHttpError(
            method = "POST",
            host = "api.example.com",
            pathTemplate = "/v1/alarm-evaluations",
            statusCode = 401,
            correlationId = "corr-001",
        )
        val redacted = event.toRedactedString()
        assertTrue(redacted.contains("401"))
        assertTrue(redacted.contains("POST"))
        assertTrue(redacted.contains("corr-001"))
        assertFalse(redacted.contains("secret"))
    }

    @Test
    fun protocolErrorDoesNotContainCoordinates() {
        val logger = NetworkErrorLogger()
        val event = logger.logProtocolError(
            host = "api.caiyunapp.com",
            pathTemplate = "/v2.6/***/hourly",
            errorType = "AUTH_FAILED",
        )
        val redacted = event.toRedactedString()
        assertTrue(redacted.contains("AUTH_FAILED"))
        assertFalse(redacted.contains("116.397428"))
    }

    @Test
    fun maximumEventLimit() {
        val logger = RedactingEventLogger()
        repeat(250) { i ->
            logger.record(DiagnosticEvent(eventType = "EVT", resultCode = i.toString()))
        }
        assertEquals(200, logger.recentEvents().size)
    }
}
