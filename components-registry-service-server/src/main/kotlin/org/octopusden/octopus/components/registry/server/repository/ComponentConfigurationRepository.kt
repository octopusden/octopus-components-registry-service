package org.octopusden.octopus.components.registry.server.repository
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ComponentConfigurationRepository : JpaRepository<ComponentConfigurationEntity, UUID> {
    /** All configuration rows (base + overrides) for a component, in arbitrary order. */
    fun findByComponentId(componentId: UUID): List<ComponentConfigurationEntity>

    /** Single base row for a component (NULL `overridden_attribute`); enforced by partial UNIQUE index. */
    fun findByComponentIdAndOverriddenAttributeIsNull(componentId: UUID): ComponentConfigurationEntity?

    /** Exact match used by service-layer overlap validation. */
    fun findByComponentIdAndVersionRangeAndOverriddenAttribute(
        componentId: UUID,
        versionRange: String,
        overriddenAttribute: String?,
    ): ComponentConfigurationEntity?
}
