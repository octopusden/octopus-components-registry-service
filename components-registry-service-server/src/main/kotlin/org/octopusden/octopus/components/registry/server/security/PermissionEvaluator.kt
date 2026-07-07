package org.octopusden.octopus.components.registry.server.security

import org.octopusden.cloud.commons.security.BasePermissionEvaluator
import org.octopusden.cloud.commons.security.SecurityService
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.service.impl.EmployeeDirectoryService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class PermissionEvaluator(
    securityService: SecurityService,
    // Optional on purpose: in the `no-db` boot mode (SYS-047) there is no JPA
    // ComponentRepository bean, yet PermissionEvaluator must still construct because the
    // git read controllers reference `@permissionEvaluator.hasPermission(...)`. No-db mode
    // also exposes no write endpoints (ComponentControllerV4 is @ConditionalOnDatabaseEnabled),
    // so `canEditComponent` is never actually invoked there — but if it were, a null repo
    // denies (fail-closed).
    componentRepositoryProvider: ObjectProvider<ComponentRepository>,
    private val employeeDirectory: EmployeeDirectoryService,
) : BasePermissionEvaluator(securityService) {
    private val log = LoggerFactory.getLogger(PermissionEvaluator::class.java)
    private val componentRepository: ComponentRepository? by lazy { componentRepositoryProvider.getIfAvailable() }
    /**
     * Per-component edit gate (ADR-004, Phase 2). A user may edit a component only
     * when they are listed on it as its `componentOwner`, a `releaseManager`, or a
     * `securityChampion` — admins (holding [EDIT_ANY_COMPONENT]) bypass that check.
     *
     * [componentIdOrName] is the path variable verbatim: v4 controllers pass a UUID
     * via `#id.toString()`, but the contract also accepts a component key (name), so
     * a future name-based caller resolves correctly instead of getting a silent 403.
     *
     * Resolution order:
     *  1. no `ACCESS_COMPONENTS` permission         → deny (also protects future
     *     call sites that might forget the explicit read gate).
     *  2. `EDIT_ANY_COMPONENT` (admin)              → allow.
     *  3. blank / anonymous username                → deny.
     *  4. unresolvable id-or-key, or a component with
     *     no owner AND no RM AND no SC               → deny (admin-only; a missing id
     *     therefore yields 403, not 404 — @PreAuthorize runs before the controller).
     *  5. current username matches owner / RM / SC  → allow. The comparison is trimmed
     *     and case-insensitive: Keycloak `preferred_username` and the stored usernames
     *     may differ in case (the entity only trims/dedupes, it does not lowercase).
     *
     * Owner/RM/SC are read via scalar projection queries on [ComponentRepository] —
     * never via the entity's LAZY collections — because this runs outside a Hibernate
     * session (see the repository's edit-ownership projections).
     */
    fun canEditComponent(componentIdOrName: String): Boolean {
        if (!hasPermission(ACCESS_COMPONENTS)) return false
        if (hasPermission(EDIT_ANY_COMPONENT)) return true

        val username = securityService.getCurrentUser().username.trim()
        if (username.isEmpty() || username == ANONYMOUS_USER) return false

        return try {
            val repository = componentRepository ?: return false // no-db mode: no editable components
            val id = resolveComponentId(repository, componentIdOrName) ?: return false
            // Short-circuit cheapest-first: an owner match skips the RM and SC queries, an RM
            // match skips the SC query. Any no-match — including an owner-less component (no
            // owner, empty RM, empty SC) — falls through to deny (owner-less is then admin-only,
            // reachable only via the EDIT_ANY_COMPONENT bypass handled above).
            val owner = repository.findComponentOwnerById(id)
            if (matches(username, owner)) return true
            if (repository.findReleaseManagerUsernames(id).any { matches(username, it) }) return true
            if (repository.findSecurityChampionUsernames(id).any { matches(username, it) }) return true
            // OCTOPUS-2191: the manager of the componentOwner may also edit.
            val ownerTrimmed = owner?.trim()
            if (!ownerTrimmed.isNullOrEmpty() && matches(username, employeeDirectory.getManager(ownerTrimmed))) return true
            false
        } catch (e: RuntimeException) {
            // Fail closed: a lookup failure (e.g. DB unavailable) denies the edit (→ 403)
            // instead of letting the exception escape the @PreAuthorize interceptor as a 500.
            // Mirrors the defensive try/catch in octopus-dms-service's PermissionEvaluator.
            log.warn("canEditComponent: ownership lookup failed for '{}'; denying edit", componentIdOrName, e)
            false
        }
    }

    /** UUID first (the live v4 call site), then component-key fallback (documented contract). */
    private fun resolveComponentId(
        repository: ComponentRepository,
        componentIdOrName: String,
    ): UUID? =
        runCatching { UUID.fromString(componentIdOrName) }.getOrNull()
            ?: repository.findByComponentKey(componentIdOrName)?.id

    private fun matches(
        username: String,
        candidate: String?,
    ): Boolean = candidate != null && username.equals(candidate.trim(), ignoreCase = true)

    @Suppress("UnusedParameter")
    fun canDeleteComponent(componentIdOrName: String): Boolean = hasPermission(DELETE_COMPONENTS)

    @Suppress("UnusedParameter")
    fun canArchiveComponent(componentIdOrName: String): Boolean = hasPermission(ARCHIVE_COMPONENTS)

    @Suppress("UnusedParameter")
    fun canRenameComponent(componentIdOrName: String): Boolean = hasPermission(RENAME_COMPONENTS)

    fun canImport(): Boolean = hasPermission(IMPORT_DATA)

    /**
     * CRS-B field-config `editable: adminOnly` gate. A caller may write a field marked
     * `adminOnly` only when they hold [EDIT_ANY_COMPONENT] — the same permission that
     * bypasses the per-component ownership check in [canEditComponent]. Read server-side
     * from the requester's permissions; the field-config read blob stays user-agnostic.
     */
    fun canEditAdminOnlyFields(): Boolean = hasPermission(EDIT_ANY_COMPONENT)

    /**
     * Edit component-configuration metadata — gates the raw Field-Overrides edit
     * surface (add/edit/delete, incl. marker editing); a power-user / escape-hatch
     * capability. Distinct from CREATE_COMPONENTS (new component creation) and
     * IMPORT_DATA (bulk import). Mapped to ROLE_ADMIN in octopus-security.roles today.
     */
    fun canEditMetadata(): Boolean = hasPermission(EDIT_METADATA)

    companion object {
        const val ACCESS_COMPONENTS = "ACCESS_COMPONENTS"
        const val CREATE_COMPONENTS = "CREATE_COMPONENTS"

        /**
         * Bypass for the per-component ownership check in [canEditComponent]:
         * together with ACCESS_COMPONENTS, a holder may edit ANY component regardless
         * of owner/RM/SC. Mapped to ROLE_ADMIN in octopus-security.roles (so admins can
         * e.g. reassign a departed owner).
         */
        const val EDIT_ANY_COMPONENT = "EDIT_ANY_COMPONENT"
        const val ARCHIVE_COMPONENTS = "ARCHIVE_COMPONENTS"
        const val RENAME_COMPONENTS = "RENAME_COMPONENTS"
        const val DELETE_COMPONENTS = "DELETE_COMPONENTS"
        const val IMPORT_DATA = "IMPORT_DATA"
        const val ACCESS_AUDIT = "ACCESS_AUDIT"
        const val EDIT_METADATA = "EDIT_METADATA"

        /** `SecurityService.getCurrentUser()` username when there is no authentication. */
        private const val ANONYMOUS_USER = "anonymous"
    }
}
