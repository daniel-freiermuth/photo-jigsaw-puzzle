package dev.daniel.jigsaw.source

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Epoch milliseconds of an ISO-8601 timestamp, with or without zone (zone-less values are taken as
 * UTC, which is what Immich sends); null if absent or unparseable.
 */
internal fun parseIsoTimestamp(text: String?): Long? {
    if (text.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(text).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        try {
            LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
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

/** Epoch milliseconds of a Unix timestamp in seconds given as text; null if absent or not a number. */
internal fun parseEpochSeconds(text: String?): Long? =
    text?.trim()?.toLongOrNull()?.let { Instant.ofEpochSecond(it).toEpochMilli() }
