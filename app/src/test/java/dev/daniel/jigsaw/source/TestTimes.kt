package dev.daniel.jigsaw.source

import java.time.Instant

/** Epoch millis of an ISO-8601 instant, for stating expected capture times. */
internal fun millis(iso: String) = Instant.parse(iso).toEpochMilli()
