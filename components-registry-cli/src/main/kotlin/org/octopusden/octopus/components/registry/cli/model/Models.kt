// Mirror of v4.json — verify/regenerate against the canon on any contract change.
//
// All @Serializable data classes in this package are a faithful, hand-written mirror of the
// schemas declared in:
//   components-registry-service-server/src/main/resources/openapi/v4.json
//
// Conventions:
//   - Required spec fields are non-nullable Kotlin properties.
//   - Optional spec fields are nullable with a `= null` default.
//   - @SerialName is applied only where the JSON key differs from the idiomatic Kotlin name.
//   - Free-form JSON `object` values are modelled as kotlinx JsonElement / Map<String, JsonElement>.
//   - The core layer owns the Json instance and MUST set ignoreUnknownKeys = true; these classes
//     intentionally do not enumerate every server field (e.g. request-only schemas are omitted).
package org.octopusden.octopus.components.registry.cli.model
