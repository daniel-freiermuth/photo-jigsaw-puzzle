package dev.daniel.jigsaw.source

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Epoch milliseconds of an ISO-8601 timestamp, with or without zone (zone-less values are taken as
 * UTC, which is what Immich sends); null if absent, unparseable or out of range.
 */
internal fun parseIsoTimestamp(text: String?): Long? {
    if (text.isNullOrBlank()) return null
    return try {
        val instant = try {
            OffsetDateTime.parse(text).toInstant()
        } catch (_: DateTimeParseException) {
            LocalDateTime.parse(text).toInstant(ZoneOffset.UTC)
        }
        instant.toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    } catch (_: ArithmeticException) {
        null
    }
}

/** Epoch milliseconds of an HTTP date (`Tue, 13 Oct 2026 19:39:24 GMT`); null if absent or unparseable. */
internal fun parseHttpDate(text: String?): Long? {
    if (text.isNullOrBlank()) return null
    return try {
        ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
}

/** Epoch milliseconds of a Unix timestamp in seconds given as text; null if absent, not a number or out of range. */
internal fun parseEpochSeconds(text: String?): Long? {
    val seconds = text?.trim()?.toLongOrNull() ?: return null
    return try {
        Instant.ofEpochSecond(seconds).toEpochMilli()
    } catch (_: DateTimeException) {
        null
    } catch (_: ArithmeticException) {
        null
    }
}
