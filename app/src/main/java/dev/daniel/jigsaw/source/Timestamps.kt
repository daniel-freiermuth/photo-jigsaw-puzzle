package dev.daniel.jigsaw.source

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
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
