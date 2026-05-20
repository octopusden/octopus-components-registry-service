package org.octopusden.octopus.components.registry.server.dto.v4

data class ComponentFilter(
    val system: String? = null,
    val productType: String? = null,
    val archived: Boolean? = null,
    val search: String? = null,
    val owner: String? = null,
    val buildSystem: String? = null,
    /**
     * Multi-value AND filter over the component_labels junction. A component
     * must carry every code in this list to match. Null or empty means "no
     * extra filter applied" (NOT "match components with no labels"). The
     * controller normalises raw query input (split-by-comma, trim,
     * drop-empty, null-if-empty) before populating this field, so the
     * Specification can rely on the list being non-empty and free of
     * blank/whitespace entries.
     */
    val labels: List<String>? = null,
)
