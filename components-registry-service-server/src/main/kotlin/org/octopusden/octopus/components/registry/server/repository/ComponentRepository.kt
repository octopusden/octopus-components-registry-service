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

    @Query(
        "SELECT DISTINCT c.componentOwner FROM ComponentEntity c " +
            "WHERE c.componentOwner IS NOT NULL AND c.componentOwner <> '' " +
            "ORDER BY c.componentOwner",
    )
    fun findDistinctOwners(): List<String>

    /**
     * Distinct system codes currently assigned to at least one component.
     *
     * Sourced from the scalar `components.system_code` column (the M:N
     * `component_systems` junction was collapsed to a 1:0..1 reference in
     * this iteration). NOT sourced from the master `SystemEntity` /
     * `systems` table — that's the `/meta/systems/dictionary` endpoint;
     * this endpoint advertises only codes actually in use, parity with
     * `/meta/owners` and `/meta/labels`.
     *
     * Defensively filters null and blank/whitespace-only system_code
     * values, mirroring the IS-NOT-NULL + non-empty guard on
     * `findDistinctOwners`. A stray "" or "   " in the column (from
     * direct DB write) would otherwise surface as an unselectable blank
     * chip in the Portal picker.
     */
    @Query(
        "SELECT DISTINCT c.systemCode FROM ComponentEntity c " +
            "WHERE c.systemCode IS NOT NULL AND TRIM(c.systemCode) <> '' " +
            "ORDER BY c.systemCode",
    )
    fun findDistinctSystemCodes(): List<String>

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
}
