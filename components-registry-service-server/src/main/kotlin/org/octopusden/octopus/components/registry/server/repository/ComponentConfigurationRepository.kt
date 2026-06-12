package org.octopusden.octopus.components.registry.server.repository
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ComponentConfigurationRepository : JpaRepository<ComponentConfigurationEntity, UUID> {
    /**
     * Jira-relevant rows of every non-archived component: every BASE row (it
     * carries the defaults the per-range overrides layer over) plus every
     * SCALAR_OVERRIDE row for `jira.projectKey` / `jira.versionPrefix`. The
     * uniqueness invariant compares EFFECTIVE per-range claims — see
     * `computeEffectiveJiraPairs` — not raw rows: a projectKey-only override
     * row carries a NULL prefix that means "inherited from base", not
     * "no prefix". Used by both the API-side cross-component check and the
     * migration uniqueness pre-pass.
     */
    @Query(
        "SELECT comp.componentKey AS componentKey, cfg.versionRange AS versionRange, " +
            "cfg.rowType AS rowType, cfg.overriddenAttribute AS overriddenAttribute, " +
            "cfg.jiraProjectKey AS projectKey, cfg.jiraVersionPrefix AS versionPrefix " +
            "FROM ComponentConfigurationEntity cfg " +
            "JOIN cfg.component comp " +
            "WHERE comp.archived = false " +
            "AND (cfg.rowType = 'BASE' " +
            "  OR cfg.overriddenAttribute IN ('jira.projectKey', 'jira.versionPrefix'))",
    )
    fun findAllNonArchivedJiraRows(): List<JiraRowProjection>

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

/** Row projection for effective jira-pair computation (see `computeEffectiveJiraPairs`). */
interface JiraRowProjection {
    val componentKey: String
    val versionRange: String
    val rowType: String
    val overriddenAttribute: String?
    val projectKey: String?
    val versionPrefix: String?
}
