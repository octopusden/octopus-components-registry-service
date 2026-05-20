package org.octopusden.octopus.components.registry.server.dto.v4

data class ComponentFilter(
    /**
     * Multi-value OR filter over the component_systems junction. A component
     * matches when ANY of its system junctions has a systemCode in this
     * list. Unlike labels (also junction-backed but AND across selections),
     * system is OR because the picker semantics is "components belonging to
     * any of these systems". The controller normalises raw query input
     * (split-by-comma, trim, drop-empty, distinct, null-if-empty) before
     * populating this field, so the Specification can rely on the list
     * being non-empty and free of blank/whitespace/duplicate entries.
     */
    val system: List<String>? = null,
    val productType: String? = null,
    val archived: Boolean? = null,
    val search: String? = null,
    val owner: String? = null,
    /**
     * Multi-value OR filter over the BASE configuration row's buildSystem
     * column. A component matches when its BASE buildSystem equals any of
     * the listed values. A component has exactly one BASE buildSystem at
     * a time, so multi-select semantics is OR (NOT AND, which would only
     * ever yield zero or one match). Null or empty means "no extra filter
     * applied". The controller normalises raw query input (split-by-comma,
     * trim, drop-empty, distinct, null-if-empty) before populating this
     * field, so the Specification can rely on the list being non-empty
     * and free of blank/whitespace/duplicate entries.
     */
    val buildSystem: List<String>? = null,
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
