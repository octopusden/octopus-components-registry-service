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
    /**
     * Multi-value OR filter over the scalar `components.componentOwner`
     * column. A component matches when its componentOwner equals any of
     * the listed values. Picker semantics is "components owned by any of
     * these people". Scalar column on ComponentEntity, so no JOIN and no
     * `query.distinct(true)` needed — the IN-predicate alone is enough.
     * Same controller normalisation as the other multi-value filters.
     */
    val owner: List<String>? = null,
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
