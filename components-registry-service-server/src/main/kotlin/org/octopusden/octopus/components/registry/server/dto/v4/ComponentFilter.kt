package org.octopusden.octopus.components.registry.server.dto.v4

data class ComponentFilter(
    /**
     * Multi-value OR filter over the scalar `components.system_code` column
     * (a component belongs to at most one system — the M:N junction was
     * collapsed to a 1:0..1 reference in this iteration). A component
     * matches when its `system_code` is in this list. Picker semantics is
     * "components belonging to any of these systems"; since each component
     * has exactly zero-or-one system, multi-select reduces to a plain
     * `IN(...)` predicate against the scalar column — no JOIN and no
     * `query.distinct(true)` needed. Components with `system_code IS NULL`
     * never match a non-empty filter. The controller normalises raw query
     * input (split-by-comma, trim, drop-empty, distinct, null-if-empty)
     * before populating this field.
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
     * drop-empty, distinct, null-if-empty) before populating this field,
     * so the Specification can rely on the list being non-empty and free
     * of blank/whitespace/duplicate entries.
     */
    val labels: List<String>? = null,
    /**
     * Optional filter on the scalar `components.can_be_parent` flag. When set,
     * returns only components whose `canBeParent` equals this value. The Portal
     * parent picker passes `canBeParent=true` to list eligible parents. Null =
     * "no extra filter applied". Scalar column, so no JOIN / distinct needed.
     */
    val canBeParent: Boolean? = null,
)
