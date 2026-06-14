package org.octopusden.octopus.components.registry.cli.model

import kotlinx.serialization.Serializable

/**
 * Mirror of v4.json `SortObject`.
 */
@Serializable
data class SortObject(
    val empty: Boolean? = null,
    val sorted: Boolean? = null,
    val unsorted: Boolean? = null,
)

/**
 * Mirror of v4.json `PageableObject` (the resolved pageable echoed back in page responses).
 */
@Serializable
data class PageableObject(
    val offset: Long? = null,
    val pageNumber: Int? = null,
    val pageSize: Int? = null,
    val paged: Boolean? = null,
    val sort: SortObject? = null,
    val unpaged: Boolean? = null,
)

/**
 * Mirror of v4.json `PageComponentSummaryResponse` — the page envelope returned by
 * GET /rest/api/4/components.
 *
 * The spec marks no property as required, so every field is nullable.
 */
@Serializable
data class PageComponentSummaryResponse(
    val content: List<ComponentSummaryResponse>? = null,
    val empty: Boolean? = null,
    val first: Boolean? = null,
    val last: Boolean? = null,
    val number: Int? = null,
    val numberOfElements: Int? = null,
    val pageable: PageableObject? = null,
    val size: Int? = null,
    val sort: SortObject? = null,
    val totalElements: Long? = null,
    val totalPages: Int? = null,
)

/**
 * Mirror of v4.json `PageAuditLogResponse` — the page envelope returned by the audit endpoints.
 */
@Serializable
data class PageAuditLogResponse(
    val content: List<AuditLogResponse>? = null,
    val empty: Boolean? = null,
    val first: Boolean? = null,
    val last: Boolean? = null,
    val number: Int? = null,
    val numberOfElements: Int? = null,
    val pageable: PageableObject? = null,
    val size: Int? = null,
    val sort: SortObject? = null,
    val totalElements: Long? = null,
    val totalPages: Int? = null,
)
