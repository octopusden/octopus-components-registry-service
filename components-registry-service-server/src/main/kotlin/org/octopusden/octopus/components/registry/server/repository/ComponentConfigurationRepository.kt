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
     * Distinct keys of OTHER, non-archived components (excluding
     * [excludeComponentId]) that own a configuration row with the same jira
     * `(projectKey, versionPrefix)` pair. Restores the old
     * `validateJiraProjectKeyAndVersionPrefixIntersections` rule: each
     * (projectKey, versionPrefix) maps to at most one non-archived component.
     *
     * `versionPrefix` is nullable — the null-safe comparison treats a NULL
     * prefix on both sides as the same bucket (matching the old
     * `Tuple2(projectKey, versionPrefix)` map key where versionPrefix could be
     * null). A non-empty result is a cross-component conflict.
     */
    @Query(
        "SELECT DISTINCT comp.componentKey FROM ComponentConfigurationEntity cfg " +
            "JOIN cfg.component comp " +
            "WHERE comp.id <> :excludeComponentId " +
            "AND comp.archived = false " +
            "AND cfg.jiraProjectKey = :projectKey " +
            "AND ((:versionPrefix IS NULL AND cfg.jiraVersionPrefix IS NULL) " +
            "  OR cfg.jiraVersionPrefix = :versionPrefix)",
    )
    fun findOtherNonArchivedComponentKeysByJiraProjectKeyAndVersionPrefix(
        @Param("projectKey") projectKey: String,
        @Param("versionPrefix") versionPrefix: String?,
        @Param("excludeComponentId") excludeComponentId: UUID,
    ): List<String>

    /**
     * Every distinct jira `(projectKey, versionPrefix)` pair of every
     * non-archived component, with the owning component key. Used by the
     * migration uniqueness pre-pass to check incoming DSL pairs against the
     * already-persisted state (same invariant as
     * [findOtherNonArchivedComponentKeysByJiraProjectKeyAndVersionPrefix],
     * fetched wholesale because the pre-pass compares many candidates at once).
     */
    @Query(
        "SELECT DISTINCT comp.componentKey AS componentKey, " +
            "cfg.jiraProjectKey AS projectKey, cfg.jiraVersionPrefix AS versionPrefix " +
            "FROM ComponentConfigurationEntity cfg " +
            "JOIN cfg.component comp " +
            "WHERE comp.archived = false AND cfg.jiraProjectKey IS NOT NULL",
    )
    fun findAllNonArchivedJiraPairs(): List<JiraPairRow>

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

/** Projection for the migration jira-uniqueness pre-pass. */
interface JiraPairRow {
    val componentKey: String
    val projectKey: String
    val versionPrefix: String?
}
