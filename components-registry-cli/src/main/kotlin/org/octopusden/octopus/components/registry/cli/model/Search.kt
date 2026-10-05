package org.octopusden.octopus.components.registry.cli.model

import kotlinx.serialization.Serializable

/**
 * Mirror of v4.json `AsCodeSearchResponse` — GET /rest/api/4/components/as-code/search (SYS-098).
 */
@Serializable
data class AsCodeSearchResponse(
    val query: String,
    val regex: Boolean,
    val totalComponents: Int,
    val truncated: Boolean,
    val results: List<AsCodeSearchHit> = emptyList(),
)

/** Mirror of v4.json `AsCodeSearchHit` — one matching component. */
@Serializable
data class AsCodeSearchHit(
    val componentKey: String,
    val archived: Boolean,
    val matchCount: Int,
    val matches: List<AsCodeSearchLine> = emptyList(),
)

/** Mirror of v4.json `AsCodeSearchLine` — one matching line of the component's as-code view. */
@Serializable
data class AsCodeSearchLine(
    val line: Int,
    val text: String,
    val path: List<String> = emptyList(),
)
