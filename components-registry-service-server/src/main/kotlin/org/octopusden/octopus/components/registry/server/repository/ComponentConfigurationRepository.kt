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
     * Single BASE row for a component. Uniqueness is enforced by the partial
     * unique index `uq_component_configurations_one_base WHERE row_type = 'BASE'`.
     * Non-BASE row types may match multiple rows per component — callers that
     * need to enumerate them should use `findByComponentId` and filter, not a
     * derived single-result finder.
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT c FROM ComponentConfigurationEntity c " +
            "WHERE c.component.id = :componentId AND c.rowType = 'BASE'",
    )
    fun findBaseByComponentId(componentId: UUID): ComponentConfigurationEntity?

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
