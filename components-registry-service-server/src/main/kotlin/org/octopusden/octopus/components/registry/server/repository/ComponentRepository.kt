package org.octopusden.octopus.components.registry.server.repository

import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ComponentRepository :
    JpaRepository<ComponentEntity, UUID>,
    JpaSpecificationExecutor<ComponentEntity> {
    fun findByComponentKey(componentKey: String): ComponentEntity?

    fun findByComponentKeyIn(componentKeys: Collection<String>): List<ComponentEntity>

    fun findByComponentKeyAndArchivedFalse(componentKey: String): ComponentEntity?

    fun findByArchivedFalse(): List<ComponentEntity>

    fun existsByComponentKey(componentKey: String): Boolean

    // display_name is nullable + UNIQUE; create/update validate uniqueness of a non-null value before write.
    fun existsByDisplayName(displayName: String): Boolean

    fun existsByDisplayNameAndIdNot(displayName: String, id: UUID): Boolean

    /**
     * Every (componentKey, displayName) pair with a non-null display name.
     * Used by the migration uniqueness pre-pass to check incoming DSL display
     * names against already-persisted components (DB enforces UNIQUE; the
     * pre-pass reports the offenders up front instead of aborting mid-import
     * on the constraint).
     */
    @Query(
        "SELECT c.componentKey AS componentKey, c.displayName AS displayName " +
            "FROM ComponentEntity c WHERE c.displayName IS NOT NULL",
    )
    fun findAllDisplayNamePairs(): List<DisplayNamePairRow>

    @Query(
        "SELECT DISTINCT c.componentOwner FROM ComponentEntity c " +
            "WHERE c.componentOwner IS NOT NULL AND c.componentOwner <> '' " +
            "ORDER BY c.componentOwner",
    )
    fun findDistinctOwners(): List<String>

    /**
     * Distinct client codes currently in use on at least one component. Source for
     * the `/meta/client-codes` dropdown (SYS-046). Scalar `components.client_code`
     * column; same IS NOT NULL + non-blank defence as [findDistinctOwners].
     */
    @Query(
        "SELECT DISTINCT c.clientCode FROM ComponentEntity c " +
            "WHERE c.clientCode IS NOT NULL AND TRIM(c.clientCode) <> '' " +
            "ORDER BY c.clientCode",
    )
    fun findDistinctClientCodes(): List<String>

    /**
     * Distinct BASE-configuration-row jira project keys currently in use. Source for
     * the `/meta/jira-project-keys` dropdown (SYS-046). Restricted to `rowType=BASE`
     * to match the `?jiraProjectKey=` filter (which only inspects the BASE row), so
     * the dropdown never advertises an override-only value the filter can't match.
     */
    @Query(
        "SELECT DISTINCT cfg.jiraProjectKey FROM ComponentEntity c JOIN c.configurations cfg " +
            "WHERE cfg.rowType = 'BASE' AND cfg.jiraProjectKey IS NOT NULL AND TRIM(cfg.jiraProjectKey) <> '' " +
            "ORDER BY cfg.jiraProjectKey",
    )
    fun findDistinctJiraProjectKeys(): List<String>

    /**
     * Distinct component keys actually referenced as some component's parent. Source
     * for the `/meta/parent-component-names` FILTER dropdown (SYS-046) — the set of
     * real parent refs in use, NOT the can-be-parent candidate set the editor's
     * parent picker uses (that comes from `?canBeParent=true`). Do not conflate.
     */
    @Query(
        "SELECT DISTINCT c.parentComponent.componentKey FROM ComponentEntity c " +
            "WHERE c.parentComponent IS NOT NULL AND TRIM(c.parentComponent.componentKey) <> '' " +
            "ORDER BY c.parentComponent.componentKey",
    )
    fun findDistinctParentComponentNames(): List<String>

    /**
     * True when at least one component references [parentId] as its
     * `parentComponent`. Used by the service layer to reject disabling
     * `canBeParent` on a component that still has children.
     */
    @Query(
        "SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END " +
            "FROM ComponentEntity c WHERE c.parentComponent.id = :parentId",
    )
    fun existsByParentComponentId(parentId: UUID): Boolean

    /**
     * All components whose `componentGroup` is [groupId]. Used by migration cleanup
     * (§6.3) to unlink the members of a group that is no longer a true aggregator
     * before deleting the now-orphaned `component_groups` row.
     */
    fun findByComponentGroupId(groupId: UUID): List<ComponentEntity>

    // --- Edit-ownership projections (ADR-004 Phase 2) ---
    // These power `PermissionEvaluator.canEditComponent`, which runs inside a
    // Spring Security @PreAuthorize interceptor — i.e. OUTSIDE any open Hibernate
    // session/transaction. Walking the LAZY `releaseManagers` / `securityChampions`
    // collections there (via `ComponentEntity.releaseManagerUsernames()` etc.) would
    // throw LazyInitializationException. These queries return bare username scalars
    // — no entity hydration, no LAZY exposure — so the gate is safe to call there.

    /** The single `component_owner` username (or null) for one component. */
    @Query("SELECT c.componentOwner FROM ComponentEntity c WHERE c.id = :id")
    fun findComponentOwnerById(id: UUID): String?

    /** Ordered release-manager usernames for one component (first = primary). */
    @Query(
        "SELECT rm.username FROM ComponentEntity c JOIN c.releaseManagers rm " +
            "WHERE c.id = :id ORDER BY rm.sortOrder",
    )
    fun findReleaseManagerUsernames(id: UUID): List<String>

    /** Ordered security-champion usernames for one component (first = primary). */
    @Query(
        "SELECT sc.username FROM ComponentEntity c JOIN c.securityChampions sc " +
            "WHERE c.id = :id ORDER BY sc.sortOrder",
    )
    fun findSecurityChampionUsernames(id: UUID): List<String>

    // --- Health statistics aggregations (SYS-057) ---
    // Counts for the admin Registry-Health page, computed in SQL (COUNT / GROUP BY) so the
    // 685+ components are never loaded into memory. All exclude the FAKE-aggregator stub rows
    // (componentGroup is fake AND its groupKey == the row's own componentKey), matching the
    // always-on exclusion in `ComponentManagementServiceImpl.buildSpecification`, so the totals
    // line up with the v4 component list.
    //
    // The group condition uses an explicit LEFT JOIN: referencing `c.componentGroup.isFake`
    // inline would force an INNER join and silently drop every group-less component (the common
    // API-created case) from the count. With `LEFT JOIN c.componentGroup g`, `g IS NULL` keeps
    // group-less components and real-aggregator members; only the self-linked fake aggregator
    // stub is excluded.

    /** Count of regular (non-FAKE-aggregator) components. */
    @Query(
        "SELECT COUNT(c) FROM ComponentEntity c LEFT JOIN c.componentGroup g " +
            "WHERE (g IS NULL OR NOT (g.isFake = true AND g.groupKey = c.componentKey))",
    )
    fun countRegularComponents(): Long

    /** Count of regular components by archived flag. */
    @Query(
        "SELECT COUNT(c) FROM ComponentEntity c LEFT JOIN c.componentGroup g " +
            "WHERE c.archived = :archived " +
            "AND (g IS NULL OR NOT (g.isFake = true AND g.groupKey = c.componentKey))",
    )
    fun countRegularComponentsByArchived(archived: Boolean): Long

    /**
     * Per-owner ACTIVE-component counts (regular, non-archived components only; null/blank
     * owners excluded). One row per distinct `component_owner`. Archived components are
     * excluded so a person's breakdown reflects their live workload, consistent with
     * `activeComponents`; `totalComponents` keeps the full count separately.
     */
    @Query(
        "SELECT c.componentOwner AS name, COUNT(c) AS count FROM ComponentEntity c LEFT JOIN c.componentGroup g " +
            "WHERE c.archived = false " +
            "AND c.componentOwner IS NOT NULL AND TRIM(c.componentOwner) <> '' " +
            "AND (g IS NULL OR NOT (g.isFake = true AND g.groupKey = c.componentKey)) " +
            "GROUP BY c.componentOwner",
    )
    fun countComponentsByOwner(): List<NameCountRow>

    /**
     * Per-release-manager ACTIVE-component counts (GROUP BY over the
     * component_release_managers child table; regular, non-archived components only). A user
     * on N active components yields count N; a user whose only components are archived does
     * not appear in the map at all.
     */
    @Query(
        "SELECT rm.username AS name, COUNT(c) AS count FROM ComponentEntity c " +
            "JOIN c.releaseManagers rm LEFT JOIN c.componentGroup g " +
            "WHERE c.archived = false " +
            "AND (g IS NULL OR NOT (g.isFake = true AND g.groupKey = c.componentKey)) " +
            "GROUP BY rm.username",
    )
    fun countComponentsByReleaseManager(): List<NameCountRow>

    /** Per-security-champion ACTIVE-component counts (GROUP BY over component_security_champions; archived excluded). */
    @Query(
        "SELECT sc.username AS name, COUNT(c) AS count FROM ComponentEntity c " +
            "JOIN c.securityChampions sc LEFT JOIN c.componentGroup g " +
            "WHERE c.archived = false " +
            "AND (g IS NULL OR NOT (g.isFake = true AND g.groupKey = c.componentKey)) " +
            "GROUP BY sc.username",
    )
    fun countComponentsBySecurityChampion(): List<NameCountRow>
}

/** Projection for the health-statistics GROUP BY queries: a name (owner / RM / SC username) and its count. */
interface NameCountRow {
    val name: String
    val count: Long
}

/** Projection for the migration displayName-uniqueness pre-pass. */
interface DisplayNamePairRow {
    val componentKey: String
    val displayName: String
}
