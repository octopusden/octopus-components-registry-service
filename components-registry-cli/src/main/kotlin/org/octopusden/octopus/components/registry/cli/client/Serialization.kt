package org.octopusden.octopus.components.registry.cli.client

import kotlinx.serialization.json.Json

/**
 * The single kotlinx Json instance owned by the core layer.
 *
 * - `ignoreUnknownKeys = true`: the DTO mirror in `model/` intentionally does not enumerate every
 *   server field, so unknown keys must be tolerated rather than fail deserialization.
 * - `encodeDefaults = false`: keeps rendered output compact — properties left at their default
 *   (notably the many nullable `= null` fields) are omitted.
 * - `isLenient = false`: the server emits strict RFC-8259 JSON; we reject anything that is not.
 */
val Json: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    isLenient = false
}

/**
 * A separate pretty-printing instance for human-facing STDOUT rendering. Shares the lenient/unknown
 * settings of [Json] but turns on indentation. `encodeDefaults` stays false so null fields are
 * omitted from rendered output.
 */
val PrettyJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    isLenient = false
    prettyPrint = true
}
