package org.octopusden.octopus.components.registry.server.controller

import org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentCreateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentDetailResponse
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentFilter
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentSummaryResponse
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentUpdateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.FieldOverrideCreateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.FieldOverrideResponse
import org.octopusden.octopus.components.registry.server.dto.v4.FieldOverrideUpdateRequest
import org.octopusden.octopus.components.registry.server.repository.ComponentLabelRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.LabelRepository
import org.octopusden.octopus.components.registry.server.repository.SystemRepository
import org.octopusden.octopus.components.registry.server.service.ComponentManagementService
import org.octopusden.octopus.escrow.BuildSystem
import org.octopusden.octopus.escrow.RepositoryType
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * `@PreAuthorize` is applied method-by-method rather than at class level because
 * Spring Security 6's method-level annotation **replaces** a class-level one instead
 * of AND-ing with it. A class-level `ACCESS_COMPONENTS` + method-level
 * `EDIT_COMPONENTS` would silently let a user with only `EDIT_COMPONENTS` bypass the
 * read gate, which is the opposite of what "class-level default" suggests. Every
 * endpoint now declares the full set of permissions it requires.
 */
@RestController
@RequestMapping("rest/api/4/components")
@Suppress("TooManyFunctions")
class ComponentControllerV4(
    private val componentManagementService: ComponentManagementService,
    private val componentRepository: ComponentRepository,
    private val componentLabelRepository: ComponentLabelRepository,
    private val labelRepository: LabelRepository,
    private val systemRepository: SystemRepository,
) {
    private val log = LoggerFactory.getLogger(ComponentControllerV4::class.java)

    @GetMapping("/meta/owners")
    @PreAuthorize("@permissionEvaluator.hasPermission('ACCESS_COMPONENTS')")
    fun getDistinctOwners(): List<String> = componentRepository.findDistinctOwners()

    // Distinct label codes currently in use on at least one component, sorted
    // ascending. Sourced from the component_labels junction, NOT from the
    // master LabelEntity table — see ComponentLabelRepository.findDistinctLabelCodes
    // for rationale. Mirrors /meta/owners in shape and intent.
    @GetMapping("/meta/labels")
    @PreAuthorize("@permissionEvaluator.hasPermission('ACCESS_COMPONENTS')")
    fun getDistinctLabels(): List<String> = componentLabelRepository.findDistinctLabelCodes()

    // Distinct system codes currently assigned to at least one component,
    // sorted ascending. Sourced from the scalar `components.system_code`
    // column (M:N junction was collapsed to a 1:0..1 reference in this
    // iteration), NOT from the master SystemEntity table — same rationale
    // as /meta/labels: the picker should advertise only codes actually
    // attached to a component. Mirrors /meta/owners and /meta/labels in
    // shape and intent.
    @GetMapping("/meta/systems")
    @PreAuthorize("@permissionEvaluator.hasPermission('ACCESS_COMPONENTS')")
    fun getDistinctSystems(): List<String> = componentRepository.findDistinctSystemCodes()

    // Full master-table dictionary variants of /meta/labels and /meta/systems.
    // The legacy `/meta/labels` and `/meta/systems` endpoints are sourced from
    // the M:N junctions and advertise only codes currently attached to at
    // least one component — correct for filter-bar pickers that filter
    // existing data. The Portal's editor multi-select needs the FULL master
    // dictionary so admin-seeded codes that no component carries yet are
    // still selectable. These endpoints expose every row in the `labels` /
    // `systems` master table, sorted ascending. Same `ACCESS_COMPONENTS`
    // permission as the junction-sourced variants.
    @GetMapping("/meta/labels/dictionary")
    @PreAuthorize("@permissionEvaluator.hasPermission('ACCESS_COMPONENTS')")
    fun getAllLabels(): List<String> = labelRepository.findAllCodesSorted()

    @GetMapping("/meta/systems/dictionary")
    @PreAuthorize("@permissionEvaluator.hasPermission('ACCESS_COMPONENTS')")
    fun getAllSystems(): List<String> = systemRepository.findAllCodesSorted()

    // Domain-named option lists for the three free-form aspect string fields
    // (buildSystem, repositoryType, generation). The portal's EnumSelect uses
    // these to populate dropdowns when the admin field-config registry has no
    // explicit options[] seeded. Endpoint names are domain-named, not
    // implementation-named (no `/meta/enums`), so the wire surface survives
    // a future move of the option source from a Kotlin enum to a config
    // table or admin-editable registry.
    //
    // For buildSystem and repositoryType the *persistence-layer* enums
    // (`org.octopusden.octopus.escrow.BuildSystem` / `RepositoryType`) are
    // sourced rather than the `core.dto.*` mirrors. The DTO variant of
    // `BuildSystem` carries `NOT_SUPPORTED` while
    // `EntityMappers.safeParseBuildSystem` calls `BuildSystem.valueOf` on
    // the escrow variant (which has `ESCROW_NOT_SUPPORTED`); advertising
    // the DTO token would silently drop that value on save. For generation
    // there is only one canonical source in `components-registry-api`'s
    // `EscrowGenerationMode` (the `core.dto.EscrowGenerationMode` mirror
    // has the same token set; we use the API enum because `Mappers.toDTO`
    // reads it directly off the escrow model).
    @GetMapping("/meta/build-systems")
    @PreAuthorize("@permissionEvaluator.hasPermission('ACCESS_COMPONENTS')")
    fun getBuildSystems(): List<String> = BuildSystem.values().map { it.name }

    @GetMapping("/meta/repository-types")
    @PreAuthorize("@permissionEvaluator.hasPermission('ACCESS_COMPONENTS')")
    fun getRepositoryTypes(): List<String> = RepositoryType.values().map { it.name }

    @GetMapping("/meta/escrow-generations")
    @PreAuthorize("@permissionEvaluator.hasPermission('ACCESS_COMPONENTS')")
    fun getEscrowGenerations(): List<String> = EscrowGenerationMode.values().map { it.name }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(
        "@permissionEvaluator.hasPermission('ACCESS_COMPONENTS') " +
            "and @permissionEvaluator.hasPermission('EDIT_COMPONENTS')",
    )
    fun createComponent(
        @RequestBody request: ComponentCreateRequest,
    ): ComponentDetailResponse = componentManagementService.createComponent(request)

    @GetMapping
    @PreAuthorize("@permissionEvaluator.hasPermission('ACCESS_COMPONENTS')")
    fun listComponents(
        @RequestParam(required = false) system: List<String>?,
        @RequestParam(required = false) productType: String?,
        @RequestParam(required = false) archived: Boolean?,
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) owner: List<String>?,
        @RequestParam(required = false) buildSystem: List<String>?,
        @RequestParam(required = false) labels: List<String>?,
        @RequestParam(required = false) canBeParent: Boolean?,
        @RequestParam(required = false) clientCode: String?,
        @RequestParam(required = false) solution: Boolean?,
        @RequestParam(required = false) jiraProjectKey: String?,
        @RequestParam(required = false) jiraTechnical: Boolean?,
        @RequestParam(required = false) vcsPath: String?,
        @RequestParam(required = false) productionBranch: String?,
        @RequestParam(required = false) parentComponentName: String?,
        @RequestParam(required = false) groupKey: String?,
        @RequestParam(required = false) distributionExplicit: Boolean?,
        @RequestParam(required = false) distributionExternal: Boolean?,
        pageable: Pageable,
    ): Page<ComponentSummaryResponse> {
        // Each multi-value list filter parameter (system, owner, buildSystem,
        // labels) is normalised through `normalizeCsvParam` — see that
        // helper for the wire-shape contract. The four downstream
        // Specifications can rely on receiving a non-null, non-blank,
        // duplicate-free list (or null = "no filter").
        val filter =
            ComponentFilter(
                system = normalizeCsvParam(system),
                productType = productType,
                archived = archived,
                search = search,
                owner = normalizeCsvParam(owner),
                buildSystem = normalizeCsvParam(buildSystem),
                labels = normalizeCsvParam(labels),
                canBeParent = canBeParent,
                clientCode = clientCode,
                solution = solution,
                jiraProjectKey = jiraProjectKey,
                jiraTechnical = jiraTechnical,
                vcsPath = vcsPath,
                productionBranch = productionBranch,
                parentComponentName = parentComponentName,
                groupKey = groupKey,
                distributionExplicit = distributionExplicit,
                distributionExternal = distributionExternal,
            )
        return componentManagementService.listComponents(filter, pageable)
    }

    /**
     * Normalise a multi-value `@RequestParam List<String>?` into the
     * canonical shape consumed by `ComponentFilter` / the Specification
     * branches.
     *
     * Spring's binder accepts both repeatable params (`?x=A&x=B`) and CSV
     * inside a single value (`?x=A,B`). `flatMap { it.split(",") }`
     * normalises both into one shape; trim then drop-empty defends against
     * blank entries (`?x=`, `?x=,,`, `?x=,A,,B,`) that would otherwise
     * yield empty-string predicates that silently match nothing or break
     * equality checks downstream. `distinct()` collapses repeated codes
     * (`?x=A,A → ?x=A`) so the Specification doesn't issue a redundant
     * extra JOIN per duplicate. The final `takeIf { it.isNotEmpty() }`
     * collapses an all-blank input back to null so the Specification's
     * `isNullOrEmpty` branch skips the filter entirely.
     *
     * Downstream semantics differ per filter — buildSystem is OR (single
     * column IN list), system is OR (junction + IN), owner is OR (scalar
     * IN), labels is AND (one join per code) — but they all consume the
     * same normalised non-empty list shape.
     */
    private fun normalizeCsvParam(raw: List<String>?): List<String>? =
        raw
            ?.flatMap { it.split(",") }
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }

    @GetMapping("/{idOrName}")
    @PreAuthorize("@permissionEvaluator.hasPermission('ACCESS_COMPONENTS')")
    fun getComponent(
        @PathVariable idOrName: String,
    ): ComponentDetailResponse {
        // Prefer UUID lookup when the path parses as one, but fall through to the
        // name lookup ONLY for NotFoundException — the sentinel for "no row with
        // this id". Infra / server-side errors (DB down, NPE, etc.) must surface
        // to the caller instead of silently being re-routed to a name lookup
        // that would mask the real failure. Ensures a component whose `name`
        // happens to parse as a UUID still resolves by name.
        val asUuid = runCatching { UUID.fromString(idOrName) }.getOrNull()
        if (asUuid != null) {
            try {
                return componentManagementService.getComponent(asUuid)
            } catch (e: NotFoundException) {
                // Id not found — continue to the name lookup below. Log at debug
                // so unrelated callers aren't noisy in production; the name
                // lookup will either succeed or raise its own NotFoundException
                // which the handler maps to the 404 the caller sees.
                log.debug("id lookup missed for '{}', falling back to name lookup: {}", idOrName, e.message)
            }
        }
        return componentManagementService.getComponentByName(idOrName)
    }

    // Field-level gating: a plain edit passes on EDIT_COMPONENTS alone, but switching
    // `archived` additionally requires ARCHIVE_COMPONENTS, and changing `name`
    // (rename) additionally requires RENAME_COMPONENTS. These latter two permissions
    // are currently granted only to ROLE_ADMIN — EDITOR can't archive or rename via
    // this endpoint. When we split archive/rename into dedicated endpoints, this SpEL
    // collapses back to the simple edit guard.
    @PatchMapping("/{id}")
    @PreAuthorize(
        "@permissionEvaluator.hasPermission('ACCESS_COMPONENTS') " +
            "and @permissionEvaluator.canEditComponent(#id.toString()) " +
            "and (#request.archived == null or @permissionEvaluator.canArchiveComponent(#id.toString())) " +
            "and (#request.name == null or @permissionEvaluator.canRenameComponent(#id.toString()))",
    )
    fun updateComponent(
        @PathVariable id: UUID,
        @RequestBody request: ComponentUpdateRequest,
    ): ComponentDetailResponse = componentManagementService.updateComponent(id, request)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(
        "@permissionEvaluator.hasPermission('ACCESS_COMPONENTS') " +
            "and @permissionEvaluator.canDeleteComponent(#id.toString())",
    )
    fun deleteComponent(
        @PathVariable id: UUID,
    ) {
        componentManagementService.deleteComponent(id)
    }

    @PostMapping("/{id}/field-overrides")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(
        "@permissionEvaluator.hasPermission('ACCESS_COMPONENTS') " +
            "and @permissionEvaluator.hasPermission('EDIT_COMPONENTS')",
    )
    fun createFieldOverride(
        @PathVariable id: UUID,
        @RequestBody request: FieldOverrideCreateRequest,
    ): FieldOverrideResponse = componentManagementService.createFieldOverride(id, request)

    @PatchMapping("/{id}/field-overrides/{overrideId}")
    @PreAuthorize(
        "@permissionEvaluator.hasPermission('ACCESS_COMPONENTS') " +
            "and @permissionEvaluator.hasPermission('EDIT_COMPONENTS')",
    )
    fun updateFieldOverride(
        @PathVariable id: UUID,
        @PathVariable overrideId: UUID,
        @RequestBody request: FieldOverrideUpdateRequest,
    ): FieldOverrideResponse = componentManagementService.updateFieldOverride(id, overrideId, request)

    @DeleteMapping("/{id}/field-overrides/{overrideId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(
        "@permissionEvaluator.hasPermission('ACCESS_COMPONENTS') " +
            "and @permissionEvaluator.hasPermission('EDIT_COMPONENTS')",
    )
    fun deleteFieldOverride(
        @PathVariable id: UUID,
        @PathVariable overrideId: UUID,
    ) {
        componentManagementService.deleteFieldOverride(id, overrideId)
    }

    @GetMapping("/{id}/field-overrides")
    @PreAuthorize("@permissionEvaluator.hasPermission('ACCESS_COMPONENTS')")
    fun listFieldOverrides(
        @PathVariable id: UUID,
    ): List<FieldOverrideResponse> = componentManagementService.listFieldOverrides(id)
}
