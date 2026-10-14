package dev.daniel.jigsaw

import kotlinx.serialization.json.Json

/**
 * The JSON configuration for save files and server responses. Unknown keys are skipped, so saves from
 * newer app versions and answers from newer servers stay readable. Properties at their default value are
 * left out when encoding: mark those that must always be written `@EncodeDefault`.
 */
internal val WireJson = Json { ignoreUnknownKeys = true }
