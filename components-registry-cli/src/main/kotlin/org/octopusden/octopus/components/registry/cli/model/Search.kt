package org.octopusden.octopus.components.registry.cli.model

import kotlinx.serialization.Serializable

/**
 * Mirror of v4.json `AsCodeSearchResponse` — GET /rest/api/4/components/as-code/search (SYS-100).
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
    val id: String? = null,
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
    val ranges: List<AsCodeMatchRange> = emptyList(),
)

/** Mirror of v4.json `AsCodeMatchRange` — a matched span of the line text (`end` exclusive). */
@Serializable
data class AsCodeMatchRange(
    val start: Int,
    val end: Int,
)
