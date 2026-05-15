package org.octopusden.octopus.components.registry.server.service.impl

import jakarta.persistence.OptimisticLockException
import org.octopusden.octopus.components.registry.core.exceptions.ComponentNameConflictException
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.components.registry.server.dto.v4.BaseConfigurationRequest
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentCreateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentDetailResponse
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentFilter
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentGroupRequest
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentSummaryResponse
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentUpdateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.DockerImageRequest
import org.octopusden.octopus.components.registry.server.dto.v4.FieldOverrideCreateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.FieldOverrideResponse
import org.octopusden.octopus.components.registry.server.dto.v4.FieldOverrideUpdateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.FileUrlArtifactRequest
import org.octopusden.octopus.components.registry.server.dto.v4.MarkerChildrenPayload
import org.octopusden.octopus.components.registry.server.dto.v4.MavenArtifactRequest
import org.octopusden.octopus.components.registry.server.dto.v4.PackageRequest
import org.octopusden.octopus.components.registry.server.dto.v4.VcsEntryRequest
import org.octopusden.octopus.components.registry.server.entity.ComponentArtifactIdEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentDocLinkEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentGroupEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentLabelEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentRequiredToolEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentSystemEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentTeamcityProjectEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionDockerImageEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionFileUrlArtifactEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionMavenArtifactEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionPackageEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionSecurityGroupEntity
import org.octopusden.octopus.components.registry.server.entity.LabelEntity
import org.octopusden.octopus.components.registry.server.entity.SystemEntity
import org.octopusden.octopus.components.registry.server.entity.ToolEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
import org.octopusden.octopus.components.registry.server.event.AuditEvent
import org.octopusden.octopus.components.registry.server.mapper.ALL_VERSIONS
import org.octopusden.octopus.components.registry.server.mapper.MarkerAttributes
import org.octopusden.octopus.components.registry.server.mapper.SCALAR_ATTRIBUTE_PATHS
import org.octopusden.octopus.components.registry.server.mapper.applyScalarValue
import org.octopusden.octopus.components.registry.server.mapper.toDetailResponse
import org.octopusden.octopus.components.registry.server.mapper.toFieldOverrideResponse
import org.octopusden.octopus.components.registry.server.mapper.toSummaryResponse
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentGroupRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentLabelRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRequiredToolRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentSystemRepository
import org.octopusden.octopus.components.registry.server.repository.LabelRepository
import org.octopusden.octopus.components.registry.server.repository.SystemRepository
import org.octopusden.octopus.components.registry.server.repository.ToolRepository
import org.octopusden.octopus.components.registry.server.security.CurrentUserResolver
import org.octopusden.octopus.components.registry.server.service.ComponentManagementService
import org.octopusden.octopus.components.registry.server.service.ComponentSourceRegistry
import org.octopusden.octopus.components.registry.server.teamcity.TeamcityProperties
import org.octopusden.releng.versions.VersionRangeFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * v4 CRUD against the v2 schema (Model A'). Top-level scalars and per-component
 * child rows map 1:1 with `components` columns + child tables; per-version
 * configuration lives on `component_configurations` rows — base + scalar/marker
 * overrides — and is edited via the field-override sub-resource.
 */
@Service
@Transactional
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass")
class ComponentManagementServiceImpl(
    private val componentRepository: ComponentRepository,
    private val configurationRepository: ComponentConfigurationRepository,
    private val componentGroupRepository: ComponentGroupRepository,
    private val componentLabelRepository: ComponentLabelRepository,
    private val componentSystemRepository: ComponentSystemRepository,
    private val componentRequiredToolRepository: ComponentRequiredToolRepository,
    private val labelRepository: LabelRepository,
    private val systemRepository: SystemRepository,
    private val toolRepository: ToolRepository,
    private val sourceRegistry: ComponentSourceRegistry,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val currentUserResolver: CurrentUserResolver,
    private val fieldConfigService: FieldConfigService,
    private val teamcityProperties: TeamcityProperties,
    private val versionRangeFactory: VersionRangeFactory,
) : ComponentManagementService {
    // ============================================================
    // Create
    // ============================================================

    override fun createComponent(request: ComponentCreateRequest): ComponentDetailResponse {
        val normalizedKey = request.name.trim()
        require(normalizedKey.isNotEmpty()) { "name must not be blank" }
        require(!componentRepository.existsByComponentKey(normalizedKey)) {
            "Component with name '$normalizedKey' already exists"
        }
        request.productType?.let { validateProductType(it) }
        request.baseConfiguration?.versionRange?.let { validateRangeSyntax(it) }
        request.baseConfiguration?.build?.buildSystem?.let { validateBuildSystem(it) }
        // vcsEntries[].repositoryType / packages[].packageType are validated
        // inside `replaceVcsEntries` / `replacePackages` (covers both base-config
        // and field-override marker paths).

        val parent =
            request.parentComponentName?.let { parentKey ->
                componentRepository.findByComponentKey(parentKey)
                    ?: throw NotFoundException("Parent component '$parentKey' not found")
            }

        val group = request.group?.let { upsertGroup(it) }

        val entity =
            ComponentEntity(
                componentKey = normalizedKey,
                displayName = request.displayName,
                componentOwner = request.componentOwner,
                productType = request.productType,
                clientCode = request.clientCode,
                archived = request.archived,
                solution = request.solution,
                parentComponent = parent,
                componentGroup = group,
                releaseManager = request.releaseManager,
                securityChampion = request.securityChampion,
                copyright = request.copyright,
                releasesInDefaultBranch = request.releasesInDefaultBranch,
                jiraDisplayName = request.jiraDisplayName,
                jiraHotfixVersionFormat = request.jiraHotfixVersionFormat,
                vcsExternalRegistry = request.vcsExternalRegistry,
                distributionExplicit = request.distributionExplicit,
                distributionExternal = request.distributionExternal,
            )

        // Per-component child collections (cascade = ALL on these — flushed with the parent)
        addArtifactIds(entity, request.artifactIds)
        addSecurityGroups(entity, request.securityGroups.map { it.groupType to it.groupName })
        addTeamcityProjects(entity, request.teamcityProjects.map { it.projectId })
        addDocLinks(entity, request.docs.map { it.docComponentKey to it.majorVersion })

        // Base configuration row (cascade = ALL — flushed with the parent)
        val baseConfig = ComponentConfigurationEntity(component = entity, versionRange = ALL_VERSIONS, rowType = "BASE")
        applyBaseConfigurationCreate(baseConfig, request.baseConfiguration)
        entity.configurations.add(baseConfig)

        val saved = componentRepository.save(entity)

        // M:N junctions (no cascade — see ComponentEntity kdoc convention) must be
        // persisted via their own repositories AFTER the parent has an assigned id.
        syncLabels(saved.id!!, request.labels)
        syncSystems(saved.id!!, request.systems)
        val baseRequiredTools = request.baseConfiguration?.requiredTools
        if (baseRequiredTools != null) syncRequiredTools(baseConfig.id!!, baseRequiredTools)

        // Refresh in-memory junction collections so the response DTO reflects the
        // synced DB state — repo-direct writes bypass the entity's collections.
        refreshComponentLabelsInMemory(saved, request.labels)
        refreshComponentSystemsInMemory(saved, request.systems)
        if (baseRequiredTools != null) refreshConfigRequiredToolsInMemory(baseConfig, baseRequiredTools)

        // Mark this component as DB-sourced so the v1–v3 routing path lands on
        // `DatabaseComponentRegistryResolver` even when the env-wide default is `git`.
        sourceRegistry.setComponentSource(saved.componentKey, "db")

        publishAuditEvent(
            action = "CREATE",
            entityId = saved.id.toString(),
            newValue =
                scalarAuditMap(
                    saved,
                    overrideLabels = request.labels,
                    overrideSystems = request.systems,
                ),
        )

        return saved.toDetailResponse(teamcityProperties.baseUrl)
    }

    // ============================================================
    // Read
    // ============================================================

    @Transactional(readOnly = true)
    override fun getComponent(id: UUID): ComponentDetailResponse =
        findComponentOr404(id).toDetailResponse(teamcityProperties.baseUrl)

    @Transactional(readOnly = true)
    override fun getComponentByName(name: String): ComponentDetailResponse =
        (
            componentRepository.findByComponentKey(name)
                ?: throw NotFoundException("Component with name '$name' not found")
        ).toDetailResponse(teamcityProperties.baseUrl)

    // ============================================================
    // Update
    // ============================================================

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    override fun updateComponent(
        id: UUID,
        request: ComponentUpdateRequest,
    ): ComponentDetailResponse {
        val entity = findComponentOr404(id)

        if (entity.version != request.version) {
            throw OptimisticLockException(
                "Optimistic locking conflict: expected version ${request.version} but found ${entity.version}",
            )
        }

        val oldKey = entity.componentKey
        val normalizedNewKey = request.name?.trim()
        if (request.name != null) {
            require(!normalizedNewKey.isNullOrEmpty()) { "name must not be blank" }
            if (normalizedNewKey != oldKey && componentRepository.existsByComponentKey(normalizedNewKey)) {
                throw ComponentNameConflictException("Component with name '$normalizedNewKey' already exists")
            }
        }
        val isRename = normalizedNewKey != null && normalizedNewKey != oldKey

        request.productType?.let { validateProductType(it) }
        // `baseConfiguration.versionRange` is also validated by the downstream
        // base-configuration patch block via `validateRangeSyntax`. Keep this
        // top-level guard as an explicit fail-fast (and to match the create
        // path's ordering); the duplicate cost is one parse on PATCH only.
        request.baseConfiguration?.build?.buildSystem?.let { validateBuildSystem(it) }
        // vcsEntries[].repositoryType / packages[].packageType are validated
        // inside `replaceVcsEntries` / `replacePackages` (see service helpers).

        // Capture the pre-update label / system membership so the post-sync audit
        // `newValue` (which is computed after `syncLabels` / `syncSystems` have
        // bypassed the entity's in-memory junction collections) can compose the
        // effective new set as `request.X ?: original`.
        val originalLabels = entity.labelJunctions.map { it.labelCode }.toSet()
        val originalSystems = entity.systemJunctions.map { it.systemCode }.toSet()
        val oldValue = scalarAuditMap(entity, originalLabels, originalSystems)

        if (isRename) entity.componentKey = normalizedNewKey!!

        // FC-gated scalar patches (null = "don't touch"; hidden = silently stripped) ——
        request.displayName?.let { if (!fieldConfigService.isHidden("component.displayName")) entity.displayName = it }
        request.componentOwner?.let { if (!fieldConfigService.isHidden("component.componentOwner")) entity.componentOwner = it }
        request.productType?.let { if (!fieldConfigService.isHidden("component.productType")) entity.productType = it }
        request.clientCode?.let { if (!fieldConfigService.isHidden("component.clientCode")) entity.clientCode = it }
        request.solution?.let { if (!fieldConfigService.isHidden("component.solution")) entity.solution = it }
        request.archived?.let { entity.archived = it }
        request.releaseManager?.let { if (!fieldConfigService.isHidden("component.releaseManager")) entity.releaseManager = it }
        request.securityChampion?.let { if (!fieldConfigService.isHidden("component.securityChampion")) entity.securityChampion = it }
        request.copyright?.let { if (!fieldConfigService.isHidden("component.copyright")) entity.copyright = it }
        request.releasesInDefaultBranch?.let {
            if (!fieldConfigService.isHidden("component.releasesInDefaultBranch")) entity.releasesInDefaultBranch = it
        }
        request.jiraDisplayName?.let { if (!fieldConfigService.isHidden("component.jiraDisplayName")) entity.jiraDisplayName = it }
        request.jiraHotfixVersionFormat?.let {
            if (!fieldConfigService.isHidden("component.jiraHotfixVersionFormat")) entity.jiraHotfixVersionFormat = it
        }
        request.vcsExternalRegistry?.let {
            if (!fieldConfigService.isHidden("component.vcsExternalRegistry")) entity.vcsExternalRegistry = it
        }
        request.distributionExplicit?.let {
            if (!fieldConfigService.isHidden("component.distributionExplicit")) entity.distributionExplicit = it
        }
        request.distributionExternal?.let {
            if (!fieldConfigService.isHidden("component.distributionExternal")) entity.distributionExternal = it
        }

        // Junctions are synced via their repositories below — after the parent save —
        // because @OneToMany on `labelJunctions` / `systemJunctions` has no cascade.

        // Parent (rename to null is not expressible by JSON Merge Patch; null parent → use clearGroup style if needed later)
        request.parentComponentName?.let { parentKey ->
            entity.parentComponent =
                componentRepository.findByComponentKey(parentKey)
                    ?: throw NotFoundException("Parent component '$parentKey' not found")
        }

        // Group: clearGroup flag wins over group payload when both present
        when {
            request.clearGroup -> entity.componentGroup = null
            request.group != null -> entity.componentGroup = upsertGroup(request.group)
            else -> Unit
        }

        // Per-component child REPLACE — present collection wipes and refills
        request.artifactIds?.let {
            entity.artifactIds.clear()
            addArtifactIds(entity, it)
        }
        request.securityGroups?.let {
            entity.securityGroups.clear()
            addSecurityGroups(entity, it.map { req -> req.groupType to req.groupName })
        }
        request.teamcityProjects?.let {
            entity.teamcityProjects.clear()
            addTeamcityProjects(entity, it.map { req -> req.projectId })
        }
        request.docs?.let {
            entity.docLinks.clear()
            addDocLinks(entity, it.map { req -> req.docComponentKey to req.majorVersion })
        }

        // Base configuration patch (scalars + cascaded child collections only)
        request.baseConfiguration?.versionRange?.let { validateRangeSyntax(it) }
        val baseConfigForToolsSync =
            request.baseConfiguration?.let { patch ->
                val base =
                    entity.configurations.firstOrNull { it.rowType == "BASE" }
                        ?: ComponentConfigurationEntity(component = entity, versionRange = ALL_VERSIONS, rowType = "BASE").also {
                            entity.configurations.add(it)
                        }
                applyBaseConfigurationPatch(base, patch)
                base.takeIf { patch.requiredTools != null }
            }

        entity.updatedAt = Instant.now()
        val saved = componentRepository.saveAndFlush(entity)

        // Junctions — synced via their repositories after the parent flush so the
        // assigned ids (for newly-created rows) are visible.
        request.systems?.let { syncSystems(saved.id!!, it) }
        request.labels?.let { syncLabels(saved.id!!, it) }
        val patchedRequiredTools = request.baseConfiguration?.requiredTools
        if (baseConfigForToolsSync != null && patchedRequiredTools != null) {
            syncRequiredTools(baseConfigForToolsSync.id!!, patchedRequiredTools)
        }

        // Refresh in-memory junction collections so the response DTO reflects the
        // synced DB state — repo-direct writes bypass the entity's collections.
        request.labels?.let { refreshComponentLabelsInMemory(saved, it) }
        request.systems?.let { refreshComponentSystemsInMemory(saved, it) }
        if (baseConfigForToolsSync != null && patchedRequiredTools != null) {
            refreshConfigRequiredToolsInMemory(baseConfigForToolsSync, patchedRequiredTools)
        }

        if (isRename) sourceRegistry.renameComponent(oldKey, saved.componentKey)

        publishAuditEvent(
            action = if (isRename) "RENAME" else "UPDATE",
            entityId = saved.id.toString(),
            oldValue = oldValue,
            newValue =
                scalarAuditMap(
                    saved,
                    overrideLabels = request.labels ?: originalLabels,
                    overrideSystems = request.systems ?: originalSystems,
                ),
        )

        return saved.toDetailResponse(teamcityProperties.baseUrl)
    }

    // ============================================================
    // Delete (soft)
    // ============================================================

    override fun deleteComponent(id: UUID) {
        val entity = findComponentOr404(id)
        val wasArchived = entity.archived
        entity.archived = true
        componentRepository.save(entity)

        publishAuditEvent(
            action = "DELETE",
            entityId = id.toString(),
            oldValue = mapOf("name" to entity.componentKey, "archived" to wasArchived),
            newValue = mapOf("name" to entity.componentKey, "archived" to true),
        )
    }

    // ============================================================
    // List
    // ============================================================

    @Transactional(readOnly = true)
    override fun listComponents(
        filter: ComponentFilter,
        pageable: Pageable,
    ): Page<ComponentSummaryResponse> =
        componentRepository
            .findAll(buildSpecification(filter), translateSort(pageable))
            .map { it.toSummaryResponse(teamcityProperties.baseUrl) }

    /**
     * Translate API-facing sort field names to `ComponentEntity` property names.
     * The v4 `ComponentSummaryResponse` exposes `name` (mapped from `componentKey`),
     * so a natural `?sort=name,asc` from the Portal would otherwise raise
     * `PropertyReferenceException` ("no property 'name' on ComponentEntity") and
     * surface as a 500. Other summary fields (`id`, `displayName`, `componentOwner`,
     * `productType`, `archived`, `updatedAt`) already match entity properties
     * 1:1 and need no translation. Derived/joined fields on the summary
     * (`systems`, `labels`, `buildSystem`, etc.) intentionally have NO translation
     * — sorting on them would still raise `PropertyReferenceException` and end
     * up as a clean 400, which is the right answer until and unless someone
     * adds a dedicated query for those. Unknown fields fall through to Spring
     * Data and end up as a clean 400 via `ControllerExceptionHandler`.
     */
    private fun translateSort(pageable: Pageable): Pageable {
        if (pageable.sort.isUnsorted) return pageable
        val translated =
            Sort.by(
                pageable.sort.map { order ->
                    when (order.property) {
                        "name" ->
                            // 4-arg ctor — preserves `ignoreCase` alongside direction
                            // and null-handling. The 3-arg variant defaults
                            // ignoreCase to false and would silently drop the flag.
                            Sort.Order(order.direction, "componentKey", order.isIgnoreCase, order.nullHandling)
                        else -> order
                    }
                }.toList(),
            )
        return PageRequest.of(pageable.pageNumber, pageable.pageSize, translated)
    }

    // ============================================================
    // Field overrides — backed by `component_configurations`
    // ============================================================

    override fun createFieldOverride(
        componentId: UUID,
        request: FieldOverrideCreateRequest,
    ): FieldOverrideResponse {
        val component = findComponentOr404(componentId)
        validateRangeSyntax(request.versionRange)
        require(configurationRepository.findByComponentIdAndVersionRangeAndOverriddenAttribute(
            componentId,
            request.versionRange,
            request.overriddenAttribute,
        ) == null) {
            "Override row for attribute '${request.overriddenAttribute}' and range '${request.versionRange}' already exists"
        }

        val rowType =
            when {
                request.overriddenAttribute in MarkerAttributes.ALL -> "MARKER"
                request.overriddenAttribute in SCALAR_ATTRIBUTE_PATHS -> "SCALAR_OVERRIDE"
                else -> throw IllegalArgumentException(
                    "Unknown overriddenAttribute: '${request.overriddenAttribute}'. " +
                        "Must be a scalar aspect.field path or one of ${MarkerAttributes.ALL}",
                )
            }
        val row =
            ComponentConfigurationEntity(
                component = component,
                versionRange = request.versionRange,
                overriddenAttribute = request.overriddenAttribute,
                rowType = rowType,
            )

        val pendingTools: List<String>? =
            when {
                request.overriddenAttribute in MarkerAttributes.ALL -> {
                    require(request.value == null) {
                        "Marker override '${request.overriddenAttribute}' must not carry a scalar value"
                    }
                    requireNotNull(request.markerChildren) {
                        "Marker override '${request.overriddenAttribute}' requires markerChildren payload"
                    }
                    applyMarkerChildren(row, request.overriddenAttribute, request.markerChildren)
                }

                request.overriddenAttribute in SCALAR_ATTRIBUTE_PATHS -> {
                    require(request.markerChildren == null) {
                        "Scalar override '${request.overriddenAttribute}' must not carry markerChildren"
                    }
                    row.applyScalarValue(request.overriddenAttribute, request.value)
                    null
                }

                else -> throw IllegalArgumentException(
                    "Unknown overriddenAttribute: '${request.overriddenAttribute}'. " +
                        "Must be a scalar aspect.field path or one of ${MarkerAttributes.ALL}",
                )
            }

        component.configurations.add(row)
        val saved = configurationRepository.save(row)
        if (pendingTools != null) {
            syncRequiredTools(saved.id!!, pendingTools)
            refreshConfigRequiredToolsInMemory(saved, pendingTools)
        }
        bumpParentVersion(component)
        return saved.toFieldOverrideResponse()
    }

    override fun updateFieldOverride(
        componentId: UUID,
        overrideId: UUID,
        request: FieldOverrideUpdateRequest,
    ): FieldOverrideResponse {
        val row =
            configurationRepository
                .findById(overrideId)
                .orElseThrow { NotFoundException("FieldOverride with id '$overrideId' not found") }

        if (row.component.id != componentId) {
            throw NotFoundException("FieldOverride '$overrideId' does not belong to component '$componentId'")
        }
        require(row.rowType != "BASE" && row.rowType != "RANGE_PRESENCE") {
            "Cannot update id $overrideId via field-override endpoint (row_type=${row.rowType})"
        }

        request.versionRange?.let {
            validateRangeSyntax(it)
            row.versionRange = it
        }

        val pendingTools: List<String>? =
            when {
                row.overriddenAttribute in MarkerAttributes.ALL -> {
                    require(request.value == null) { "Marker override does not accept a scalar value" }
                    request.markerChildren?.let { applyMarkerChildren(row, row.overriddenAttribute!!, it) }
                }

                else -> {
                    require(request.markerChildren == null) { "Scalar override does not accept markerChildren" }
                    request.value?.let { row.applyScalarValue(row.overriddenAttribute!!, it) }
                    null
                }
            }

        val saved = configurationRepository.save(row)
        if (pendingTools != null) {
            syncRequiredTools(saved.id!!, pendingTools)
            refreshConfigRequiredToolsInMemory(saved, pendingTools)
        }
        bumpParentVersion(row.component)
        return saved.toFieldOverrideResponse()
    }

    override fun deleteFieldOverride(
        componentId: UUID,
        overrideId: UUID,
    ) {
        val row =
            configurationRepository
                .findById(overrideId)
                .orElseThrow { NotFoundException("FieldOverride with id '$overrideId' not found") }
        if (row.component.id != componentId) {
            throw NotFoundException("FieldOverride '$overrideId' does not belong to component '$componentId'")
        }
        require(row.rowType != "BASE" && row.rowType != "RANGE_PRESENCE") {
            "Cannot delete id $overrideId via field-override endpoint (row_type=${row.rowType})"
        }
        val owningComponent = row.component
        configurationRepository.delete(row)
        bumpParentVersion(owningComponent)
    }

    @Transactional(readOnly = true)
    override fun listFieldOverrides(componentId: UUID): List<FieldOverrideResponse> {
        if (!componentRepository.existsById(componentId)) {
            throw NotFoundException("Component with id '$componentId' not found")
        }
        return configurationRepository
            .findByComponentId(componentId)
            .filter { it.rowType == "SCALAR_OVERRIDE" || it.rowType == "MARKER" }
            .map { it.toFieldOverrideResponse() }
    }

    // ============================================================
    // Helpers — child collection management
    // ============================================================

    private fun addArtifactIds(
        component: ComponentEntity,
        requests: List<org.octopusden.octopus.components.registry.server.dto.v4.ArtifactIdRequest>,
    ) {
        requests.forEach { req ->
            component.artifactIds.add(
                ComponentArtifactIdEntity(
                    component = component,
                    groupPattern = req.groupPattern,
                    artifactPattern = req.artifactPattern,
                ),
            )
        }
    }

    private fun addSecurityGroups(
        component: ComponentEntity,
        groups: List<Pair<String, String>>,
    ) {
        groups.forEach { (type, name) ->
            component.securityGroups.add(
                DistributionSecurityGroupEntity(
                    component = component,
                    groupType = type,
                    groupName = name,
                ),
            )
        }
    }

    private fun addTeamcityProjects(
        component: ComponentEntity,
        projectIds: List<String>,
    ) {
        projectIds.forEachIndexed { index, projectId ->
            component.teamcityProjects.add(
                ComponentTeamcityProjectEntity(
                    component = component,
                    projectId = projectId,
                    sortOrder = index,
                ),
            )
        }
    }

    private fun addDocLinks(
        component: ComponentEntity,
        docs: List<Pair<String, String?>>,
    ) {
        docs.forEachIndexed { index, (docKey, majorVersion) ->
            component.docLinks.add(
                ComponentDocLinkEntity(
                    component = component,
                    docComponentKey = docKey,
                    majorVersion = majorVersion,
                    sortOrder = index,
                ),
            )
        }
    }

    /**
     * Replace the `component_labels` rows for [componentId] with exactly the
     * labels in [desired]. Junctions have no JPA cascade — they must be written
     * through their own repository (see `ComponentEntity` kdoc convention).
     * Caller must invoke this AFTER `componentRepository.save(...)` has
     * assigned an id; passing a transient component leads to an FK violation.
     */
    private fun syncLabels(
        componentId: UUID,
        desired: Set<String>,
    ) {
        val existing = componentLabelRepository.findByComponentId(componentId)
        if (existing.isNotEmpty()) componentLabelRepository.deleteAllInBatch(existing)
        desired.forEach { code ->
            ensureLabelExists(code)
            componentLabelRepository.save(
                ComponentLabelEntity(componentId = componentId, labelCode = code),
            )
        }
    }

    /** See [syncLabels]; same cascade-free convention applies. */
    private fun syncSystems(
        componentId: UUID,
        desired: Set<String>,
    ) {
        val existing = componentSystemRepository.findByComponentId(componentId)
        if (existing.isNotEmpty()) componentSystemRepository.deleteAllInBatch(existing)
        desired.forEach { code ->
            ensureSystemExists(code)
            componentSystemRepository.save(
                ComponentSystemEntity(componentId = componentId, systemCode = code),
            )
        }
    }

    /**
     * Replace the `component_required_tools` rows for [configId] with exactly
     * [desired]. Same cascade-free convention as labels/systems — the parent
     * `component_configurations` row must be flushed first.
     */
    private fun syncRequiredTools(
        configId: UUID,
        desired: List<String>,
    ) {
        val existing = componentRequiredToolRepository.findByComponentConfigurationId(configId)
        if (existing.isNotEmpty()) componentRequiredToolRepository.deleteAllInBatch(existing)
        desired.distinct().forEach { tool ->
            ensureToolExists(tool)
            componentRequiredToolRepository.save(
                ComponentRequiredToolEntity(componentConfigurationId = configId, toolName = tool),
            )
        }
    }

    private fun ensureLabelExists(code: String) {
        if (labelRepository.findByCode(code) == null) {
            labelRepository.save(LabelEntity(code = code))
        }
    }

    private fun ensureSystemExists(code: String) {
        if (systemRepository.findByCode(code) == null) {
            systemRepository.save(SystemEntity(code = code))
        }
    }

    /**
     * Auto-create a dictionary row for `name` if missing so the FK from
     * `component_required_tools.tool_name → tools.name` doesn't reject a v4
     * write referencing a tool that the operator hasn't seeded through DSL
     * import. Only the PK column is populated; the env-specific metadata
     * (`escrow_env_variable`, `target_location`, etc.) stays NULL and is
     * filled in later either by the import pipeline or by a future tools
     * admin endpoint.
     */
    private fun ensureToolExists(name: String) {
        if (toolRepository.findByName(name) == null) {
            toolRepository.save(ToolEntity(name = name))
        }
    }

    /**
     * Refresh the entity's in-memory `labelJunctions` so the response DTO
     * reflects the synced DB state. `syncLabels` writes through the
     * repository and bypasses the entity's collection — without this
     * refresh, `entity.toDetailResponse()` would surface the pre-sync
     * (stale) labels and the API caller would see an empty / wrong list
     * until a subsequent GET.
     */
    private fun refreshComponentLabelsInMemory(
        component: ComponentEntity,
        desired: Set<String>,
    ) {
        component.labelJunctions.clear()
        desired.distinct().forEach { code ->
            component.labelJunctions.add(
                ComponentLabelEntity(componentId = component.id!!, labelCode = code),
            )
        }
    }

    /** See [refreshComponentLabelsInMemory]; same pattern for `systemJunctions`. */
    private fun refreshComponentSystemsInMemory(
        component: ComponentEntity,
        desired: Set<String>,
    ) {
        component.systemJunctions.clear()
        desired.distinct().forEach { code ->
            component.systemJunctions.add(
                ComponentSystemEntity(componentId = component.id!!, systemCode = code),
            )
        }
    }

    /** See [refreshComponentLabelsInMemory]; same pattern for `requiredToolJunctions` on a configuration row. */
    private fun refreshConfigRequiredToolsInMemory(
        config: ComponentConfigurationEntity,
        desired: List<String>,
    ) {
        config.requiredToolJunctions.clear()
        desired.distinct().forEach { tool ->
            config.requiredToolJunctions.add(
                ComponentRequiredToolEntity(componentConfigurationId = config.id!!, toolName = tool),
            )
        }
    }

    /**
     * Touch the owning `ComponentEntity` so that override-row CRUD bumps the
     * aggregate root's `@Version` + `updatedAt`. Without this, clients can
     * keep using a stale component `version` after override changes —
     * undermining optimistic locking for the detail view.
     */
    private fun bumpParentVersion(component: ComponentEntity) {
        component.updatedAt = Instant.now()
        componentRepository.saveAndFlush(component)
    }

    private fun upsertGroup(request: ComponentGroupRequest): ComponentGroupEntity =
        componentGroupRepository.findByGroupKey(request.groupKey)
            ?: componentGroupRepository.save(
                ComponentGroupEntity(
                    groupKey = request.groupKey,
                    isFake = request.isFake,
                ),
            )

    // ============================================================
    // Helpers — base configuration apply/patch
    // ============================================================

    @Suppress("CyclomaticComplexMethod")
    private fun applyBaseConfigurationCreate(
        config: ComponentConfigurationEntity,
        request: BaseConfigurationRequest?,
    ) {
        if (request == null) return
        request.versionRange?.let { config.versionRange = it }
        request.build?.let { b ->
            config.buildSystem = b.buildSystem
            config.buildSystemVersion = b.buildSystemVersion
            config.javaVersion = b.javaVersion
            config.mavenVersion = b.mavenVersion
            config.gradleVersion = b.gradleVersion
            config.buildFilePath = b.buildFilePath
            config.deprecated = b.deprecated
            config.requiredProject = b.requiredProject
            config.projectVersion = b.projectVersion
            config.systemProperties = b.systemProperties
            config.buildTasks = b.buildTasks
        }
        request.escrow?.let { e ->
            config.escrowProvidedDependencies = e.providedDependencies
            config.escrowReusable = e.reusable
            config.escrowGeneration = e.generation
            config.escrowDiskSpace = e.diskSpace
            config.escrowAdditionalSources = e.additionalSources
            config.escrowGradleIncludeConfigurations = e.gradleIncludeConfigurations
            config.escrowGradleExcludeConfigurations = e.gradleExcludeConfigurations
            config.escrowGradleIncludeTestConfigurations = e.gradleIncludeTestConfigurations
            config.escrowBuildTask = e.buildTask
        }
        request.jira?.let { j ->
            config.jiraProjectKey = j.projectKey
            config.jiraTechnical = j.technical
            config.jiraMajorVersionFormat = j.majorVersionFormat
            config.jiraReleaseVersionFormat = j.releaseVersionFormat
            config.jiraBuildVersionFormat = j.buildVersionFormat
            config.jiraLineVersionFormat = j.lineVersionFormat
            config.jiraVersionPrefix = j.versionPrefix
            config.jiraVersionFormat = j.versionFormat
        }
        request.vcsEntries?.let { replaceVcsEntries(config, it) }
        request.mavenArtifacts?.let { replaceMavenArtifacts(config, it) }
        request.fileUrlArtifacts?.let { replaceFileUrlArtifacts(config, it) }
        request.dockerImages?.let { replaceDockerImages(config, it) }
        request.packages?.let { replacePackages(config, it) }
        // requiredTools is a non-cascaded M:N junction — caller syncs via syncRequiredTools
        // after the configuration row's id has been assigned (post parent flush).
    }

    /**
     * PATCH variant: null scalar fields preserve the existing column; present
     * scalars overwrite; present collections REPLACE.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun applyBaseConfigurationPatch(
        config: ComponentConfigurationEntity,
        patch: BaseConfigurationRequest,
    ) {
        patch.versionRange?.let { config.versionRange = it }
        patch.build?.let { b ->
            b.buildSystem?.let { config.buildSystem = it }
            b.buildSystemVersion?.let { config.buildSystemVersion = it }
            b.javaVersion?.let { config.javaVersion = it }
            b.mavenVersion?.let { config.mavenVersion = it }
            b.gradleVersion?.let { config.gradleVersion = it }
            b.buildFilePath?.let { config.buildFilePath = it }
            b.deprecated?.let { config.deprecated = it }
            b.requiredProject?.let { config.requiredProject = it }
            b.projectVersion?.let { config.projectVersion = it }
            b.systemProperties?.let { config.systemProperties = it }
            b.buildTasks?.let { config.buildTasks = it }
        }
        patch.escrow?.let { e ->
            e.providedDependencies?.let { config.escrowProvidedDependencies = it }
            e.reusable?.let { config.escrowReusable = it }
            e.generation?.let { config.escrowGeneration = it }
            e.diskSpace?.let { config.escrowDiskSpace = it }
            e.additionalSources?.let { config.escrowAdditionalSources = it }
            e.gradleIncludeConfigurations?.let { config.escrowGradleIncludeConfigurations = it }
            e.gradleExcludeConfigurations?.let { config.escrowGradleExcludeConfigurations = it }
            e.gradleIncludeTestConfigurations?.let { config.escrowGradleIncludeTestConfigurations = it }
            e.buildTask?.let { config.escrowBuildTask = it }
        }
        patch.jira?.let { j ->
            j.projectKey?.let { config.jiraProjectKey = it }
            j.technical?.let { config.jiraTechnical = it }
            j.majorVersionFormat?.let { config.jiraMajorVersionFormat = it }
            j.releaseVersionFormat?.let { config.jiraReleaseVersionFormat = it }
            j.buildVersionFormat?.let { config.jiraBuildVersionFormat = it }
            j.lineVersionFormat?.let { config.jiraLineVersionFormat = it }
            j.versionPrefix?.let { config.jiraVersionPrefix = it }
            j.versionFormat?.let { config.jiraVersionFormat = it }
        }
        patch.vcsEntries?.let { replaceVcsEntries(config, it) }
        patch.mavenArtifacts?.let { replaceMavenArtifacts(config, it) }
        patch.fileUrlArtifacts?.let { replaceFileUrlArtifacts(config, it) }
        patch.dockerImages?.let { replaceDockerImages(config, it) }
        patch.packages?.let { replacePackages(config, it) }
        // requiredTools: caller syncs via syncRequiredTools after flush — see updateComponent.
    }

    // ============================================================
    // Helpers — child-row family replacements (used by base config + markers)
    // ============================================================

    private fun replaceVcsEntries(
        config: ComponentConfigurationEntity,
        entries: List<VcsEntryRequest>,
    ) {
        entries.forEach { req -> req.repositoryType?.let { validateRepositoryType(it) } }
        config.vcsEntries.clear()
        entries.forEachIndexed { index, req ->
            config.vcsEntries.add(
                VcsSettingsEntryEntity(
                    componentConfiguration = config,
                    name = req.name,
                    vcsPath = req.vcsPath,
                    branch = req.branch,
                    tag = req.tag,
                    hotfixBranch = req.hotfixBranch,
                    repositoryType = req.repositoryType,
                    sortOrder = index,
                ),
            )
        }
    }

    private fun replaceMavenArtifacts(
        config: ComponentConfigurationEntity,
        artifacts: List<MavenArtifactRequest>,
    ) {
        config.mavenArtifacts.clear()
        artifacts.forEachIndexed { index, req ->
            config.mavenArtifacts.add(
                DistributionMavenArtifactEntity(
                    componentConfiguration = config,
                    groupPattern = req.groupPattern,
                    artifactPattern = req.artifactPattern,
                    extension = req.extension,
                    classifier = req.classifier,
                    sortOrder = index,
                ),
            )
        }
    }

    private fun replaceFileUrlArtifacts(
        config: ComponentConfigurationEntity,
        artifacts: List<FileUrlArtifactRequest>,
    ) {
        config.fileUrlArtifacts.clear()
        artifacts.forEachIndexed { index, req ->
            config.fileUrlArtifacts.add(
                DistributionFileUrlArtifactEntity(
                    componentConfiguration = config,
                    url = req.url,
                    artifactId = req.artifactId,
                    classifier = req.classifier,
                    sortOrder = index,
                ),
            )
        }
    }

    private fun replaceDockerImages(
        config: ComponentConfigurationEntity,
        images: List<DockerImageRequest>,
    ) {
        config.dockerImages.clear()
        images.forEachIndexed { index, req ->
            config.dockerImages.add(
                DistributionDockerImageEntity(
                    componentConfiguration = config,
                    imageName = req.imageName,
                    flavor = req.flavor,
                    sortOrder = index,
                ),
            )
        }
    }

    private fun replacePackages(
        config: ComponentConfigurationEntity,
        packages: List<PackageRequest>,
    ) {
        packages.forEach { validatePackageType(it.packageType) }
        config.packages.clear()
        packages.forEachIndexed { index, req ->
            config.packages.add(
                DistributionPackageEntity(
                    componentConfiguration = config,
                    packageType = req.packageType,
                    packageName = req.packageName,
                    sortOrder = index,
                ),
            )
        }
    }

    /**
     * Apply a marker children payload to a marker row. Validates that the
     * payload's populated list matches the marker name on the row AND that no
     * other child-family list is also populated — a strict check so a
     * malformed request (e.g., `vcs.settings` marker with both `vcsEntries`
     * and `mavenArtifacts` set) fails fast instead of silently dropping the
     * extra fields.
     *
     * Returns the desired `requiredTools` list when the marker is
     * `build.requiredTools` (caller syncs the junction via
     * [syncRequiredTools] after the row's id is assigned), else null.
     * Cascaded child families (vcs, maven, fileUrl, docker, packages) are
     * mutated on `row` in place and flushed with the row.
     */
    private fun applyMarkerChildren(
        row: ComponentConfigurationEntity,
        markerName: String,
        payload: MarkerChildrenPayload,
    ): List<String>? {
        rejectExtraneousMarkerFields(markerName, payload)
        return when (markerName) {
            MarkerAttributes.VCS_SETTINGS -> {
                requireNotNull(payload.vcsEntries) { "Marker '$markerName' requires vcsEntries payload" }
                replaceVcsEntries(row, payload.vcsEntries)
                null
            }
            MarkerAttributes.DISTRIBUTION_MAVEN -> {
                requireNotNull(payload.mavenArtifacts) { "Marker '$markerName' requires mavenArtifacts payload" }
                replaceMavenArtifacts(row, payload.mavenArtifacts)
                null
            }
            MarkerAttributes.DISTRIBUTION_FILE_URL -> {
                requireNotNull(payload.fileUrlArtifacts) { "Marker '$markerName' requires fileUrlArtifacts payload" }
                replaceFileUrlArtifacts(row, payload.fileUrlArtifacts)
                null
            }
            MarkerAttributes.DISTRIBUTION_DOCKER -> {
                requireNotNull(payload.dockerImages) { "Marker '$markerName' requires dockerImages payload" }
                replaceDockerImages(row, payload.dockerImages)
                null
            }
            MarkerAttributes.DISTRIBUTION_PACKAGES -> {
                requireNotNull(payload.packages) { "Marker '$markerName' requires packages payload" }
                replacePackages(row, payload.packages)
                null
            }
            MarkerAttributes.BUILD_REQUIRED_TOOLS -> {
                requireNotNull(payload.requiredTools) { "Marker '$markerName' requires requiredTools payload" }
                payload.requiredTools
            }
            else -> error("Unknown marker '$markerName' — caller did not validate")
        }
    }

    /**
     * Strict check: a marker payload must populate exactly the one child-family
     * list that corresponds to the marker name and leave the others null.
     * Silently dropping unrelated lists masks malformed requests; reject them
     * with a clear 400.
     */
    private fun rejectExtraneousMarkerFields(
        markerName: String,
        payload: MarkerChildrenPayload,
    ) {
        val populated =
            buildList {
                if (payload.vcsEntries != null) add("vcsEntries")
                if (payload.mavenArtifacts != null) add("mavenArtifacts")
                if (payload.fileUrlArtifacts != null) add("fileUrlArtifacts")
                if (payload.dockerImages != null) add("dockerImages")
                if (payload.packages != null) add("packages")
                if (payload.requiredTools != null) add("requiredTools")
            }
        val expected =
            when (markerName) {
                MarkerAttributes.VCS_SETTINGS -> "vcsEntries"
                MarkerAttributes.DISTRIBUTION_MAVEN -> "mavenArtifacts"
                MarkerAttributes.DISTRIBUTION_FILE_URL -> "fileUrlArtifacts"
                MarkerAttributes.DISTRIBUTION_DOCKER -> "dockerImages"
                MarkerAttributes.DISTRIBUTION_PACKAGES -> "packages"
                MarkerAttributes.BUILD_REQUIRED_TOOLS -> "requiredTools"
                else -> error("Unknown marker '$markerName' — caller did not validate")
            }
        val extras = populated.filter { it != expected }
        require(extras.isEmpty()) {
            "Marker '$markerName' accepts only the '$expected' payload list; " +
                "request also populated: ${extras.joinToString(", ")}"
        }
    }

    // ============================================================
    // Helpers — query specification, validation, audit
    // ============================================================

    private fun findComponentOr404(id: UUID): ComponentEntity =
        componentRepository.findById(id).orElseThrow {
            NotFoundException("Component with id '$id' not found")
        }

    /**
     * Parse `range` via the shared `VersionRangeFactory`; throws
     * [IllegalArgumentException] if the syntax is invalid. Partial-overlap
     * detection across other override rows is intentionally NOT enforced here —
     * the DB UNIQUE on (component_id, version_range, overridden_attribute)
     * blocks equal ranges; strict containment and disjoint ranges are allowed
     * per schema-spec.md §7 (transitional). See [`todo.md`](../../../../../../../docs/db-migration/todo.md)
     * entry "field-override partial-range-overlap rejection" for the follow-up.
     */
    private fun validateRangeSyntax(range: String) {
        try {
            versionRangeFactory.create(range)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid version range: '$range'", e)
        }
    }

    /**
     * Reject write-time enum-typed values that the resolver would later parse
     * with `Enum.valueOf` and silently drop on mismatch. Without these checks
     * the v4 API would accept the bad value with 201 / 200, then v1-v3
     * resolver calls for the same component would see `null` and either 500
     * or silently emit incomplete data — see PR #192 review thread.
     */
    private fun validateProductType(value: String) {
        require(value.isNotBlank()) { "productType must not be blank" }
        require(value in PRODUCT_TYPES) {
            "Invalid productType: '$value'. Allowed: $PRODUCT_TYPES"
        }
    }

    private fun validateBuildSystem(value: String) {
        require(value.isNotBlank()) { "build.buildSystem must not be blank" }
        require(value in BUILD_SYSTEMS) {
            "Invalid build.buildSystem: '$value'. Allowed: $BUILD_SYSTEMS"
        }
    }

    private fun validateRepositoryType(value: String) {
        require(value.isNotBlank()) { "vcsEntry.repositoryType must not be blank" }
        require(value in REPOSITORY_TYPES) {
            "Invalid vcsEntry.repositoryType: '$value'. Allowed: $REPOSITORY_TYPES"
        }
    }

    private fun validatePackageType(value: String) {
        require(value.isNotBlank()) { "package.packageType must not be blank" }
        require(value in PACKAGE_TYPES) {
            "Invalid package.packageType: '$value'. Allowed: $PACKAGE_TYPES"
        }
    }

    private companion object {
        private val PRODUCT_TYPES: Set<String> =
            org.octopusden.octopus.components.registry.api.enums.ProductTypes
                .values()
                .map { it.name }
                .toSet()
        private val BUILD_SYSTEMS: Set<String> =
            org.octopusden.octopus.components.registry.core.dto.BuildSystem
                .values()
                .map { it.name }
                .toSet()
        private val REPOSITORY_TYPES: Set<String> =
            org.octopusden.octopus.escrow.RepositoryType
                .values()
                .map { it.name }
                .toSet()
        // `Distribution.DEB` / `Distribution.RPM` are the only types emitted by
        // the resolver. There is no enum class for them at the API layer, so
        // hand-list the allowed values.
        private val PACKAGE_TYPES: Set<String> = setOf("DEB", "RPM")
    }

    private fun buildSpecification(filter: ComponentFilter): Specification<ComponentEntity> {
        var spec = Specification.where<ComponentEntity>(null)

        filter.productType?.let { pt ->
            spec = spec.and(Specification { root, _, cb -> cb.equal(root.get<String>("productType"), pt) })
        }
        filter.archived?.let { archived ->
            spec = spec.and(Specification { root, _, cb -> cb.equal(root.get<Boolean>("archived"), archived) })
        }
        filter.owner?.let { owner ->
            spec = spec.and(Specification { root, _, cb -> cb.equal(root.get<String>("componentOwner"), owner) })
        }
        filter.search?.let { search ->
            val pattern = "%${search.lowercase()}%"
            spec =
                spec.and(
                    Specification { root, _, cb ->
                        cb.or(
                            cb.like(cb.lower(root.get("componentKey")), pattern),
                            cb.like(cb.lower(root.get("displayName")), pattern),
                        )
                    },
                )
        }
        filter.buildSystem?.let { bs ->
            spec =
                spec.and(
                    Specification { root, query, cb ->
                        val join = root.join<ComponentEntity, ComponentConfigurationEntity>("configurations")
                        query?.distinct(true)
                        cb.and(
                            cb.equal(join.get<String>("rowType"), "BASE"),
                            cb.equal(join.get<String>("buildSystem"), bs),
                        )
                    },
                )
        }
        filter.system?.let { systemCode ->
            spec =
                spec.and(
                    Specification { root, query, cb ->
                        val join = root.join<ComponentEntity, ComponentSystemEntity>("systemJunctions")
                        query?.distinct(true)
                        cb.equal(join.get<String>("systemCode"), systemCode)
                    },
                )
        }
        return spec
    }

    /**
     * Snapshot of the component's scalar fields + label/system memberships for
     * audit-log purposes. `overrideLabels` and `overrideSystems` short-circuit
     * the entity's in-memory junction collections — needed after the
     * `syncLabels` / `syncSystems` repo-direct writes, since those bypass
     * the entity's `labelJunctions` / `systemJunctions` and the in-memory
     * collections still hold the pre-sync set.
     */
    private fun scalarAuditMap(
        entity: ComponentEntity,
        overrideLabels: Set<String>? = null,
        overrideSystems: Set<String>? = null,
    ): Map<String, Any?> =
        mapOf(
            "name" to entity.componentKey,
            "displayName" to entity.displayName,
            "componentOwner" to entity.componentOwner,
            "productType" to entity.productType,
            "clientCode" to entity.clientCode,
            "archived" to entity.archived,
            "solution" to entity.solution,
            "parentComponentName" to entity.parentComponent?.componentKey,
            "groupKey" to entity.componentGroup?.groupKey,
            "releaseManager" to entity.releaseManager,
            "securityChampion" to entity.securityChampion,
            "copyright" to entity.copyright,
            "releasesInDefaultBranch" to entity.releasesInDefaultBranch,
            "jiraDisplayName" to entity.jiraDisplayName,
            "jiraHotfixVersionFormat" to entity.jiraHotfixVersionFormat,
            "vcsExternalRegistry" to entity.vcsExternalRegistry,
            "distributionExplicit" to entity.distributionExplicit,
            "distributionExternal" to entity.distributionExternal,
            "labels" to (overrideLabels ?: entity.labelJunctions.map { it.labelCode }.toSet()),
            "systems" to (overrideSystems ?: entity.systemJunctions.map { it.systemCode }.toSet()),
        )

    private fun publishAuditEvent(
        action: String,
        entityId: String,
        oldValue: Map<String, Any?>? = null,
        newValue: Map<String, Any?>? = null,
    ) {
        applicationEventPublisher.publishEvent(
            AuditEvent(
                entityType = "Component",
                entityId = entityId,
                action = action,
                changedBy = currentUserResolver.currentUsername(),
                oldValue = oldValue,
                newValue = newValue,
            ),
        )
    }
}
