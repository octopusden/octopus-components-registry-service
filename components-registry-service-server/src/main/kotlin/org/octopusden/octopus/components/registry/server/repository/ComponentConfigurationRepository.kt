package org.octopusden.octopus.components.registry.server.repository
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ComponentConfigurationRepository : JpaRepository<ComponentConfigurationEntity, UUID> {
    /** All configuration rows (base + overrides) for a component, in arbitrary order. */
    fun findByComponentId(componentId: UUID): List<ComponentConfigurationEntity>

    /**
     * Single row for a component with the given `row_type`. Pass `"BASE"` to
     * locate the base row (enforced unique by `uq_component_configurations_one_base`
     * partial index). Other row types may match multiple rows; callers that
     * pass `"RANGE_PRESENCE"`/`"SCALAR_OVERRIDE"`/`"MARKER"` should expect
     * `null` when no such row exists and must handle the not-unique case for
     * those non-BASE shapes via a different finder if they need to enumerate.
     */
    fun findByComponentIdAndRowType(componentId: UUID, rowType: String): ComponentConfigurationEntity?

    /** Exact match used by service-layer overlap validation. */
    fun findByComponentIdAndVersionRangeAndOverriddenAttribute(
        componentId: UUID,
        versionRange: String,
        overriddenAttribute: String?,
    ): ComponentConfigurationEntity?

    /**
     * Locate a row by (component, version_range, row_type). Used for RANGE_PRESENCE
     * idempotency on re-imports since presence rows have a NULL
     * `overridden_attribute` and cannot be looked up by the
     * `findByComponentIdAndVersionRangeAndOverriddenAttribute` overload above.
     */
    fun findByComponentIdAndVersionRangeAndRowType(
        componentId: UUID,
        versionRange: String,
        rowType: String,
    ): ComponentConfigurationEntity?
}
