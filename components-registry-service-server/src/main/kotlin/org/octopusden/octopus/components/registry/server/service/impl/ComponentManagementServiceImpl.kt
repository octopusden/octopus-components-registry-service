package org.octopusden.octopus.components.registry.server.service.impl

import jakarta.persistence.OptimisticLockException
import jakarta.persistence.criteria.JoinType
import org.apache.maven.artifact.versioning.DefaultArtifactVersion
import org.octopusden.octopus.components.registry.core.exceptions.ComponentNameConflictException
import org.octopusden.octopus.components.registry.core.exceptions.CrossComponentConflictException
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.dto.v4.BaseConfigurationRequest
import org.octopusden.octopus.components.registry.server.dto.v4.BuildToolBeanRequest
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentCreateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentDetailResponse
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentEditorsResponse
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentFilter
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentSummaryResponse
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentUpdateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.DockerImageRequest
import org.octopusden.octopus.components.registry.server.dto.v4.FieldOverrideCreateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.FieldOverrideResponse
import org.octopusden.octopus.components.registry.server.dto.v4.SupportedVersionsRequest
import org.octopusden.octopus.components.registry.server.dto.v4.SupportedVersionsResponse
import org.octopusden.octopus.components.registry.server.dto.v4.FieldOverrideUpdateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.FieldOverrideUpsertRequest
import org.octopusden.octopus.components.registry.server.dto.v4.FileUrlArtifactRequest
import org.octopusden.octopus.components.registry.server.dto.v4.MarkerChildrenPayload
import org.octopusden.octopus.components.registry.server.dto.v4.MavenArtifactRequest
import org.octopusden.octopus.components.registry.server.dto.v4.PackageRequest
import org.octopusden.octopus.components.registry.server.dto.v4.VcsEntryRequest
import org.octopusden.octopus.components.registry.server.entity.ArtifactIdMode
import org.octopusden.octopus.components.registry.server.entity.ComponentArtifactMappingEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentArtifactMappingTokenEntity
import org.octopusden.octopus.components.registry.server.util.ArtifactOwnershipModeClassifier
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentDocLinkEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentGroupEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentLabelEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentBuildToolBeanEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentReleaseManagerEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentRequiredToolEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentSecurityChampionEntity
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
import org.octopusden.octopus.components.registry.server.util.VersionRangePartition
import org.octopusden.octopus.components.registry.server.mapper.BEAN_TYPE_NAMES
import org.octopusden.octopus.components.registry.server.mapper.BUILD_SYSTEM_NAMES
import org.octopusden.octopus.components.registry.server.mapper.numericVersionComparator
import org.octopusden.octopus.components.registry.server.mapper.ESCROW_GENERATION_MODE_NAMES
import org.octopusden.octopus.components.registry.server.mapper.MarkerAttributes
import org.octopusden.octopus.components.registry.server.mapper.NOT_AVAILABLE_EXTERNAL_REGISTRY
import org.octopusden.octopus.components.registry.server.mapper.PACKAGE_TYPE_NAMES
import org.octopusden.octopus.components.registry.server.mapper.PRODUCT_TYPE_NAMES
import org.octopusden.octopus.components.registry.server.mapper.REPOSITORY_TYPE_NAMES
import org.octopusden.octopus.components.registry.server.mapper.SCALAR_ATTRIBUTE_PATHS
import org.octopusden.octopus.components.registry.server.mapper.applyScalarValue
import org.octopusden.octopus.components.registry.server.mapper.ARTIFACT_MAPPING_ORDER
import org.octopusden.octopus.components.registry.server.mapper.toDetailResponse
import org.octopusden.octopus.components.registry.server.mapper.toFieldOverrideResponse
import org.octopusden.octopus.components.registry.server.mapper.toSummaryResponse
import org.octopusden.octopus.components.registry.server.repository.ComponentArtifactMappingRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentArtifactMappingTokenRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentBuildToolBeanRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentLabelRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRequiredToolRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentSystemRepository
import org.octopusden.octopus.components.registry.server.repository.DistributionDockerImageRepository
import org.octopusden.octopus.components.registry.server.repository.DistributionMavenArtifactRepository
import org.octopusden.octopus.components.registry.server.repository.LabelRepository
import org.octopusden.octopus.components.registry.server.repository.SystemRepository
import org.octopusden.octopus.components.registry.server.repository.ToolRepository
import org.octopusden.octopus.components.registry.server.security.CurrentUserResolver
import org.octopusden.octopus.components.registry.server.security.PermissionEvaluator
import org.octopusden.octopus.components.registry.server.service.ComponentManagementService
import org.octopusden.octopus.components.registry.server.service.ComponentSourceRegistry
import org.octopusden.octopus.components.registry.server.service.RenderedComponentCode
import org.octopusden.octopus.components.registry.server.teamcity.TeamcityProperties
import org.octopusden.octopus.components.registry.server.util.ComponentCodeRenderer
import org.octopusden.octopus.components.registry.server.util.JiraRowView
import org.octopusden.octopus.components.registry.server.util.MavenGavCollision
import org.octopusden.octopus.components.registry.server.util.computeEffectiveJiraPairs
import org.octopusden.octopus.escrow.config.ConfigHelper
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionRangeFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.env.Environment
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * v4 CRUD against the v2 schema (Model A'). Top-level scalars and per-component
 * child rows map 1:1 with `components` columns + child tables; per-version
 * configuration lives on `component_configurations` rows — base + scalar/marker
 * overrides — and is edited via the field-override sub-resource.
 */
@ConditionalOnDatabaseEnabled
@Service
@Transactional
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass")
class ComponentManagementServiceImpl(
    private val componentRepository: ComponentRepository,
    private val configurationRepository: ComponentConfigurationRepository,
    private val componentLabelRepository: ComponentLabelRepository,
    private val componentSystemRepository: ComponentSystemRepository,
    private val componentRequiredToolRepository: ComponentRequiredToolRepository,
    private val componentBuildToolBeanRepository: ComponentBuildToolBeanRepository,
    private val mavenArtifactRepository: DistributionMavenArtifactRepository,
    private val componentArtifactMappingRepository: ComponentArtifactMappingRepository,
    private val componentArtifactMappingTokenRepository: ComponentArtifactMappingTokenRepository,
    private val dockerImageRepository: DistributionDockerImageRepository,
    private val labelRepository: LabelRepository,
    private val systemRepository: SystemRepository,
    private val toolRepository: ToolRepository,
    private val sourceRegistry: ComponentSourceRegistry,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val currentUserResolver: CurrentUserResolver,
    private val fieldConfigService: FieldConfigService,
    private val permissionEvaluator: PermissionEvaluator,
    private val teamcityProperties: TeamcityProperties,
    private val versionRangeFactory: VersionRangeFactory,
    private val numericVersionFactory: NumericVersionFactory,
    private val environment: Environment,
    private val componentCodeRenderer: ComponentCodeRenderer,
    private val employeeDirectory: EmployeeDirectoryService,
) : ComponentManagementService {
    // ConfigHelper is constructed lazily because it touches the Spring
    // Environment on first access; mirrors the pattern used by
    // CommonControllerV2. The supported group-id
    // list is environment-config-driven (read from
    // `components-registry.supportedGroupIds`), so building it once at
    // first access is sufficient — admins re-config via redeploy, not
    // hot-reload.
    private val configHelper: ConfigHelper by lazy { ConfigHelper(environment) }
    // ============================================================
    // Create
    // ============================================================

    override fun createComponent(request: ComponentCreateRequest): ComponentDetailResponse {
        val normalizedKey = request.name.trim()
        require(normalizedKey.isNotEmpty()) { "name must not be blank" }
        require(!componentRepository.existsByComponentKey(normalizedKey)) {
            // Field-prefixed so the Portal routes the 400 inline onto the Component Key (`name`)
            // field (parseServerFieldErrors keys on the leading `name:`), matching displayName.
            "name: a component with name '$normalizedKey' already exists"
        }
        // displayName is nullable + UNIQUE. Blank/absent → stored as null (this preserves the
        // legacy v1/v2/v3 wire `$.name`: prod 2.0.87 served null for unnamed components, so we do
        // NOT backfill the component key). A non-blank value must not collide with another
        // component's display name (UNIQUE; nullable ⇒ many NULLs allowed). Required-ness for
        // explicit+external components is enforced downstream by validateMalformedFieldRules
        // (mirrors EscrowConfigValidator.validateExplicitExternalComponent). The 400 is
        // colon-prefixed so the Portal routes it inline onto the displayName field.
        val normalizedDisplayName = request.displayName?.trim()?.takeIf { it.isNotEmpty() }
        // Skip the uniqueness probe for a hidden displayName — the value is stripped below
        // (not persisted), so it must not surface a 4xx (hidden = silent, never rejected).
        if (normalizedDisplayName != null && !fieldConfigService.isHidden("component.displayName")) {
            require(!componentRepository.existsByDisplayName(normalizedDisplayName)) {
                "displayName: a component with display name '$normalizedDisplayName' already exists"
            }
        }
        // Strict contract (UI-swift-sloth): a component cannot legitimately exist
        // without a build system on its BASE configuration row. The Portal's Create
        // dialog enforces it at the UX layer; the server is the source of truth and
        // rejects a payload missing it with 400.
        //
        // R1 (aggregator/parentComponent decouple): `group` is NO LONGER required and
        // is NOT assigned on the API path. A ComponentGroup represents DSL aggregator
        // membership (a `components { }` owner + its sub-components) and is established
        // ONLY by the migration/import path. An API-created component is therefore
        // standalone → `componentGroup = null`; any `request.group` is accepted but
        // ignored (creating a component via the API does not make it an aggregator).
        val baseConfigRequest =
            requireNotNull(request.baseConfiguration) {
                "baseConfiguration is required: provide baseConfiguration.build.buildSystem on create"
            }
        val buildAspect =
            requireNotNull(baseConfigRequest.build) {
                "baseConfiguration.build is required: provide baseConfiguration.build.buildSystem on create"
            }
        require(!buildAspect.buildSystem.isNullOrBlank()) {
            "baseConfiguration.build.buildSystem is required and must not be blank"
        }
        // Capture the non-null buildSystem in a local so subsequent calls
        // can pass it directly. The `require(!isNullOrBlank)` above
        // guarantees the value, but Kotlin doesn't smart-cast through a
        // property getter — so we hoist explicitly here rather than
        // sprinkling `!!` or `?.let` at each call site.
        val buildSystemValue: String = buildAspect.buildSystem!!
        // CRS-B: a supplied (non-null) value for a field the caller may not edit is
        // rejected up-front; an absent field lets server defaults apply. Runs BEFORE the
        // value validators below so an INVALID value on a non-editable field surfaces the
        // editability error (403/422), not a value-400 — the gate must win the ordering.
        enforceEditabilityOnCreate(request)
        request.productType?.let { if (!fieldConfigService.isHidden("component.productType")) validateProductType(it) }
        request.clientCode?.let {
            if (!fieldConfigService.isHidden("component.clientCode")) validateClientCode(it)
        }
        request.copyright?.let {
            if (!fieldConfigService.isHidden("component.copyright")) validateCopyright(it)
        }
        baseConfigRequest.versionRange?.let { validateRangeSyntax(it) }
        validateBuildSystem(buildSystemValue)
        // Hidden escrow.generation is stripped below → don't 4xx on its value here.
        baseConfigRequest.escrow?.generation?.let {
            if (!fieldConfigService.isHidden("escrow.generation")) validateEscrowGenerationMode(it)
        }
        // vcsEntries[].repositoryType / packages[].packageType are validated
        // inside `replaceVcsEntries` / `replacePackages` (covers both base-config
        // and field-override marker paths).
        validateLabels(request.labels)
        // Canonicalise once and reuse so the synced junction, the
        // in-memory refresh, and the audit snapshot all see the same
        // trimmed/deduped set — the originally requested set may still
        // contain "A " entries that the storage layer would otherwise
        // reject on read-side filtering.
        val canonicalizedLabels = canonicalizeLabels(request.labels)
        // Multi-value system: trim, drop blanks, validate + canonicalize each
        // code (dedup on the canonical form), or strip entirely when the field
        // is field-config-hidden (CRS-B: hidden fields are silently stripped on
        // create). Each code is validated against the env-config allowlist (or
        // the master `systems` dictionary) and returned in its canonical casing
        // so a caller posting `Classic` lands `CLASSIC` — preventing a duplicate
        // `systems("Classic")` row beside `systems("CLASSIC")`. The junction
        // rows are written by `syncSystems` after the parent flush.
        val canonicalizedSystems =
            if (fieldConfigService.isHidden("component.system")) {
                emptySet()
            } else {
                canonicalizeSystems(request.systems)
            }

        // CRS-B: hidden component-level fields are silently STRIPPED on create (symmetric with
        // the PATCH write-site gates) — a hidden field is never persisted and never 4xx. A hidden
        // parentComponentName is treated as absent (no parent), a hidden canBeParent as its
        // `false` default. Editability (adminOnly/none) is enforced separately by
        // enforceEditabilityOnCreate before this point.
        val effectiveCanBeParent = request.canBeParent && !fieldConfigService.isHidden("component.canBeParent")
        val parent =
            request.parentComponentName
                ?.takeUnless { fieldConfigService.isHidden("component.parentComponentName") }
                ?.let { parentKey ->
                    componentRepository.findByComponentKey(parentKey)
                        ?: throw NotFoundException("Parent component '$parentKey' not found")
                }

        // Parent invariants (create = everything is new): a chosen parent must be
        // marked can-be-parent, and a component that itself can be a parent may
        // not have a parent (a parent cannot have a parent).
        if (parent != null) {
            require(parent.canBeParent) {
                "parentComponentName: parent '${parent.componentKey}' is not marked can-be-parent"
            }
            require(!effectiveCanBeParent) {
                "parentComponentName: a component that can be a parent cannot itself have a parent"
            }
        }

        val entity =
            ComponentEntity(
                componentKey = normalizedKey,
                displayName = stripIfHidden("component.displayName", normalizedDisplayName),
                componentOwner = stripIfHidden("component.componentOwner", request.componentOwner),
                productType = stripIfHidden("component.productType", request.productType),
                clientCode = stripIfHidden("component.clientCode", request.clientCode),
                archived = request.archived,
                solution = stripIfHidden("component.solution", request.solution),
                parentComponent = parent,
                // R1: group = migration-derived aggregator membership only; never assigned via API.
                componentGroup = null,
                canBeParent = effectiveCanBeParent,
                copyright = stripIfHidden("component.copyright", request.copyright),
                releasesInDefaultBranch = stripIfHidden("component.releasesInDefaultBranch", request.releasesInDefaultBranch),
                jiraDisplayName = stripIfHidden("component.jiraDisplayName", request.jiraDisplayName),
                jiraHotfixVersionFormat = stripIfHidden("component.jiraHotfixVersionFormat", request.jiraHotfixVersionFormat),
                // CRS-A: create maps "" → NULL (treated as absent), consistent with the PATCH clear rule.
                // CRS-C: reject the legacy NOT_AVAILABLE sentinel — it must never land in the column.
                vcsExternalRegistry =
                    stripIfHidden(
                        "component.vcsExternalRegistry",
                        request.vcsExternalRegistry?.let(::clearBlankScalar).also(::rejectLegacyExternalRegistrySentinel),
                    ),
                // Non-null Boolean with a false default — hidden strips to the default rather than null.
                skipCommitCheck = if (fieldConfigService.isHidden("component.skipCommitCheck")) false else request.skipCommitCheck,
                distributionExplicit = stripIfHidden("component.distributionExplicit", request.distributionExplicit),
                distributionExternal = stripIfHidden("component.distributionExternal", request.distributionExternal),
            )

        // Per-component child collections (cascade = ALL on these — flushed with the parent)
        // Ordered multi-value people — the accessor canonicalizes (trim → drop
        // blank → keep-first dedupe), so create matches patch/import exactly.
        // CRS-B: hidden people-lists are silently stripped (symmetric with the PATCH gate).
        if (!fieldConfigService.isHidden("component.releaseManager")) entity.replaceReleaseManagerUsernames(request.releaseManager)
        if (!fieldConfigService.isHidden("component.securityChampion")) entity.replaceSecurityChampionUsernames(request.securityChampion)
        addArtifactIds(entity, request.artifactIds)
        addSecurityGroups(entity, request.securityGroups.map { it.groupType to it.groupName })
        addTeamcityProjects(entity, request.teamcityProjects.map { it.projectId })
        addDocLinks(entity, request.docs.map { it.docComponentKey to it.majorVersion })

        // Base configuration row (cascade = ALL — flushed with the parent)
        val baseConfig = ComponentConfigurationEntity(component = entity, versionRange = ALL_VERSIONS, rowType = "BASE")
        applyBaseConfigurationCreate(baseConfig, baseConfigRequest)
        entity.configurations.add(baseConfig)

        // Person fields (componentOwner / releaseManager / securityChampion) FIRST.
        // On create everything is new, so the active-employee check is always
        // triggered (subject to the flag). Person-field errors take precedence over
        // the malformed-input rules below: an explicit+external component with no
        // distribution coordinate AND no releaseManager fails BOTH the person gate
        // (#3/#4) and the ≥1-coordinate rule (#6); the foundation's contract reports
        // the person-field error first, so person validation runs ahead of the
        // malformed-input checks here.
        validatePersonFields(entity, runActiveCheck = true)
        validateRequiredCopyright(entity)
        validateRequiredDisplayName(entity)

        // Malformed-input cross-component / single-field checks (400). These need
        // no DB lookup beyond the soft doc-ref existence probe and run against the
        // final in-memory entity state (everything is freshly assigned on create).
        validateMalformedFieldRules(entity)

        // Q13: reject skipCommitCheck on a WHISKEY component (422). Runs on the final state.
        validateSkipCommitCheckNotWhiskey(entity)

        // Flush so the self-excluding 409 collision queries below see the new
        // component's rows and the entity carries an assigned id.
        val saved = componentRepository.saveAndFlush(entity)

        // Doc-component existence (#20, 400) — post-flush so a self-documenting
        // component (docs[] referencing its own key) is not rejected by ordering.
        validateDocComponentExistence(saved)

        // Cross-component integrity (409): duplicate groupId:artifactId in
        // overlapping ranges, jira (projectKey, versionPrefix) uniqueness among
        // non-archived components, docker image-name global uniqueness. Run AFTER
        // flush so the queries exclude this component by its now-assigned id.
        validateCrossComponentIntegrity(saved)
        validateArtifactOwnershipIfChanged(saved) // self-skips when the create carried no ownership

        // M:N junctions (no cascade — see ComponentEntity kdoc convention) must be
        // persisted via their own repositories AFTER the parent has an assigned id.
        syncLabels(saved.id!!, canonicalizedLabels)
        syncSystems(saved.id!!, canonicalizedSystems)
        val baseRequiredTools = baseConfigRequest.requiredTools
        if (baseRequiredTools != null) syncRequiredTools(baseConfig.id!!, baseRequiredTools)

        // Refresh in-memory junction collections so the response DTO reflects the
        // synced DB state — repo-direct writes bypass the entity's collections.
        refreshComponentLabelsInMemory(saved, canonicalizedLabels)
        refreshComponentSystemsInMemory(saved, canonicalizedSystems)
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
                    overrideLabels = canonicalizedLabels,
                    overrideSystems = canonicalizedSystems,
                ) + sectionAuditMap(saved),
            jiraTaskKey = request.jiraTaskKey,
            changeComment = request.changeComment,
        )

        return toDetail(saved)
    }

    // ============================================================
    // Read
    // ============================================================

    @Transactional(readOnly = true)
    override fun getComponent(id: UUID): ComponentDetailResponse =
        toDetail(findComponentOr404(id))

    @Transactional(readOnly = true)
    override fun getComponentByName(name: String): ComponentDetailResponse =
        toDetail(
            componentRepository.findByComponentKey(name)
                ?: throw NotFoundException("Component with name '$name' not found"),
        )

    // The renderer walks LAZY child collections, so it must run inside this
    // readOnly transaction (the Hibernate session that loaded the entity).
    @Transactional(readOnly = true)
    override fun renderComponentAsCode(idOrName: String): RenderedComponentCode {
        val entity = findByIdOrName(idOrName)
        return RenderedComponentCode(
            entity.componentKey,
            componentCodeRenderer.renderFull(entity, ownershipExportPatterns(entity)),
        )
    }

    @Transactional(readOnly = true)
    override fun renderResolvedComponentAsCode(
        idOrName: String,
        version: String,
    ): RenderedComponentCode {
        val entity = findByIdOrName(idOrName)
        val body =
            componentCodeRenderer.renderResolved(entity, version, ownershipExportPatterns(entity))
                ?: throw NotFoundException(
                    "No configuration resolves for component '${entity.componentKey}' at version '$version'",
                )
        return RenderedComponentCode(entity.componentKey, body)
    }

    // Reads LAZY child collections (releaseManagers / securityChampions), so it must run
    // inside this readOnly transaction (the session that loaded the entity).
    @Transactional(readOnly = true)
    override fun getEditors(idOrName: String): ComponentEditorsResponse {
        val entity = findByIdOrName(idOrName)
        return ComponentEditorsResponse(
            componentOwner = entity.componentOwner,
            releaseManagers = entity.releaseManagerUsernames(),
            securityChampions = entity.securityChampionUsernames(),
        )
    }

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
        // R1 (aggregator/parentComponent decouple): a ComponentGroup is migration-derived
        // aggregator membership (a `components { }` owner + its sub-components), NOT an
        // API-editable field. On PATCH it is never touched — `request.group` is accepted
        // but ignored, and `clearGroup` is an accepted no-op (both kept on the wire for
        // backward compatibility). A `group == null` PATCH always meant "don't touch";
        // now a non-null one means the same.
        //
        // `clearParent` (remove the parent) and `parentComponentName` (set a parent)
        // are mutually exclusive — accepting both would silently drop one. Skip the
        // conflict guard when the field is hidden: both signals are stripped silently
        // downstream (see the parent block below), so a hidden field must never 4xx.
        require(
            fieldConfigService.isHidden("component.parentComponentName") ||
                !(request.clearParent && request.parentComponentName != null),
        ) {
            "parentComponentName: clearParent and parentComponentName are mutually exclusive"
        }

        val oldKey = entity.componentKey
        val normalizedNewKey = request.name?.trim()
        if (request.name != null) {
            require(!normalizedNewKey.isNullOrEmpty()) { "name must not be blank" }
            if (normalizedNewKey != oldKey && componentRepository.existsByComponentKey(normalizedNewKey)) {
                throw ComponentNameConflictException(
                    "uniqueness violation: component name '$normalizedNewKey' is already used by another component",
                )
            }
        }
        val isRename = normalizedNewKey != null && normalizedNewKey != oldKey

        // CRS-B: reject an attempt to CHANGE a field the caller may not edit BEFORE any
        // mutation (change-based; unchanged echo is tolerated). Reads the pristine BASE
        // row for jira/build aspect currents, so it must run ahead of the patch appliers.
        enforceEditabilityOnUpdate(entity, request)

        // Hidden productType is stripped at its write site → don't 4xx on its value here.
        request.productType?.let { if (!fieldConfigService.isHidden("component.productType")) validateProductType(it) }
        // `baseConfiguration.versionRange` is validated downstream inside the
        // base-configuration patch block via `validateRangeSyntax` — no
        // top-level guard needed here.
        request.baseConfiguration?.build?.buildSystem?.let { validateBuildSystem(it) }
        // Hidden escrow.generation is stripped in applyBaseConfigurationPatch → don't 4xx here.
        request.baseConfiguration?.escrow?.generation?.let {
            if (!fieldConfigService.isHidden("escrow.generation")) validateEscrowGenerationMode(it)
        }
        // vcsEntries[].repositoryType / packages[].packageType are validated
        // inside `replaceVcsEntries` / `replacePackages` (see service helpers).
        request.labels?.let { validateLabels(it) }
        // Canonicalise once so the synced junction, in-memory refresh, and
        // audit snapshot all see the same trimmed/deduped set. Null means
        // "don't touch" — the canonical form is computed only when the
        // patch carries a labels payload.
        val canonicalizedLabels = request.labels?.let { canonicalizeLabels(it) }
        // PATCH semantic for the multi-value `system` field: null = don't touch;
        // a non-null set (including the empty set) REPLACES the whole membership
        // (empty clears all systems — mirrors the labels PATCH semantic). Each
        // code is trimmed, blank-dropped, validated + canonicalized (config's
        // casing, not the caller's input) so a PATCH posting `Classic` lands the
        // canonical `CLASSIC` rather than creating a duplicate `systems("Classic")`
        // master row. Field-config-hidden strips the write (silent no-op).
        val canonicalizedSystems =
            request.systems
                ?.takeUnless { fieldConfigService.isHidden("component.system") }
                ?.let { canonicalizeSystems(it) }

        // Capture the pre-update label / system membership so the post-sync audit
        // `newValue` (computed after `syncLabels` / `syncSystems` have bypassed
        // the entity's in-memory junction collections) can compose the effective
        // new set as `request.X ?: original`.
        val originalLabels = entity.labelJunctions.map { it.labelCode }.toSet()
        val originalSystems = entity.systemJunctions.map { it.systemCode }.toSet()
        val oldValue = scalarAuditMap(entity, originalLabels, originalSystems) + sectionAuditMap(entity)
        // Snapshot parent + canBeParent BEFORE applying the patch — the group is
        // rederived only when one of these actually changes (a no-op update
        // preserves the existing group, incl. grandfathered parent-of-parent rows).
        val oldParentKey = entity.parentComponent?.componentKey
        val oldCanBeParent = entity.canBeParent
        // Snapshot the person fields + distribution gate BEFORE the patch so we
        // can decide whether to re-run the active-employee check (building-block
        // #4): it fires when a person field changes OR the gate flips. When
        // neither changes, pre-existing values are grandfathered (not re-checked).
        val oldOwner = entity.componentOwner
        val oldReleaseManagers = entity.releaseManagerUsernames()
        val oldSecurityChampions = entity.securityChampionUsernames()
        val oldExplicit = entity.distributionExplicit
        val oldExternal = entity.distributionExternal
        // CRS-C / Q13: snapshot the flag + effective BASE build system BEFORE the patch so the
        // WHISKEY exclusion is CHANGE-based. A full-form combined Save echoes the whole slice, so a
        // grandfathered skipCommitCheck=true + WHISKEY row (admitted by the import bridge with a
        // warning) must tolerate an unchanged echo; only a real transition (flag false→true while
        // WHISKEY, or buildSystem→WHISKEY while the flag is set) is rejected 422.
        val oldSkipCommitCheck = entity.skipCommitCheck
        val oldBaseBuildSystem = entity.configurations.firstOrNull { it.rowType == "BASE" }?.buildSystem

        if (isRename) entity.componentKey = normalizedNewKey!!

        // FC-gated scalar patches (null = "don't touch"; hidden = silently stripped) ——
        // displayName is nullable + UNIQUE: a blank value clears it (→ null), a non-blank value
        // must not collide with another component (exclude self). Required-ness for
        // explicit+external components is enforced by validateMalformedFieldRules below, so a
        // clear on such a component is rejected there (not here). Colon-prefixed → 400 inline.
        request.displayName?.let {
            if (!fieldConfigService.isHidden("component.displayName")) {
                val newDisplayName = it.trim().takeIf { v -> v.isNotEmpty() }
                if (newDisplayName != null) {
                    require(!componentRepository.existsByDisplayNameAndIdNot(newDisplayName, entity.id!!)) {
                        "displayName: a component with display name '$newDisplayName' already exists"
                    }
                }
                entity.displayName = newDisplayName
            }
        }
        request.componentOwner?.let { if (!fieldConfigService.isHidden("component.componentOwner")) entity.componentOwner = it }
        request.productType?.let { if (!fieldConfigService.isHidden("component.productType")) entity.productType = it }
        request.clientCode?.let {
            if (!fieldConfigService.isHidden("component.clientCode")) {
                validateClientCode(it)
                entity.clientCode = it
            }
        }
        request.solution?.let { if (!fieldConfigService.isHidden("component.solution")) entity.solution = it }
        request.archived?.let { entity.archived = it }
        // canBeParent: editability enforced above; `hidden` silently strips (consistent with
        // the other field-config-gated scalars). Structural invariants are validated below.
        request.canBeParent?.let { if (!fieldConfigService.isHidden("component.canBeParent")) entity.canBeParent = it }
        // Ordered multi-value people. `null` = don't touch; a provided list
        // (including empty = clear) replaces the whole ordered child collection.
        request.releaseManager?.let {
            if (!fieldConfigService.isHidden("component.releaseManager")) entity.replaceReleaseManagerUsernames(it)
        }
        request.securityChampion?.let {
            if (!fieldConfigService.isHidden("component.securityChampion")) entity.replaceSecurityChampionUsernames(it)
        }
        request.copyright?.let {
            if (!fieldConfigService.isHidden("component.copyright")) {
                validateCopyright(it)
                entity.copyright = it
            }
        }
        request.releasesInDefaultBranch?.let {
            if (!fieldConfigService.isHidden("component.releasesInDefaultBranch")) entity.releasesInDefaultBranch = it
        }
        request.jiraDisplayName?.let { if (!fieldConfigService.isHidden("component.jiraDisplayName")) entity.jiraDisplayName = it }
        request.jiraHotfixVersionFormat?.let {
            if (!fieldConfigService.isHidden("component.jiraHotfixVersionFormat")) entity.jiraHotfixVersionFormat = it
        }
        request.vcsExternalRegistry?.let {
            // CRS-A: "" clears the external registry (persist NULL); non-blank sets verbatim.
            // CRS-C: reject the legacy NOT_AVAILABLE sentinel — it must never land in the column.
            val normalized = clearBlankScalar(it)
            rejectLegacyExternalRegistrySentinel(normalized)
            if (!fieldConfigService.isHidden("component.vcsExternalRegistry")) entity.vcsExternalRegistry = normalized
        }
        // PATCH boolean: null = don't touch (handled by ?.let). Editable by any component
        // editor (Q11) — no adminOnly hardcode; the CRS-B gate above already lets field-config
        // narrow it later. Hidden-strip mirrors the peer component scalars.
        request.skipCommitCheck?.let {
            if (!fieldConfigService.isHidden("component.skipCommitCheck")) entity.skipCommitCheck = it
        }
        request.distributionExplicit?.let {
            if (!fieldConfigService.isHidden("component.distributionExplicit")) entity.distributionExplicit = it
        }
        request.distributionExternal?.let {
            if (!fieldConfigService.isHidden("component.distributionExternal")) entity.distributionExternal = it
        }
        // Multi-value system membership is written through `syncSystems` below,
        // after the parent flush — the @OneToMany `systemJunctions` has no
        // cascade (same convention as labels). `canonicalizedSystems` already
        // folded in the field-config-hidden strip (null when hidden or absent),
        // so no extra gate here.

        // Junctions are synced via their repositories below — after the parent save —
        // because @OneToMany on `labelJunctions` / `systemJunctions` has no cascade.

        // Parent. `parentComponentName == null` = don't touch (JSON Merge Patch);
        // `clearParent` is the explicit removal signal (remediates a grandfathered
        // parent-of-parent row by letting the user drop the parent). Editability is
        // enforced above; `hidden` silently strips both the set and the clear.
        if (!fieldConfigService.isHidden("component.parentComponentName")) {
            if (request.clearParent) {
                entity.parentComponent = null
            }
            request.parentComponentName?.let { parentKey ->
                entity.parentComponent =
                    componentRepository.findByComponentKey(parentKey)
                        ?: throw NotFoundException("Parent component '$parentKey' not found")
            }
        }

        // Parent / canBeParent invariants (single-level — matches the DSL validator):
        // a chosen parent must be can-be-parent; a can-be-parent component may not
        // itself have a parent; a parent still referenced by children may not be
        // demoted. Group membership is NOT re-derived here — it is migration-owned
        // aggregator membership and the API leaves it untouched (see the group note at
        // the top of this method).
        val parentChanged = entity.parentComponent?.componentKey != oldParentKey
        val canBeParentChanged = entity.canBeParent != oldCanBeParent
        validateParentInvariants(entity, parentChanged, canBeParentChanged)

        // Person fields. Required/pattern run unconditionally on the FINAL state;
        // the active-employee check fires only when a person field changed OR the
        // distribution gate flipped (so previously-saved values are grandfathered
        // when neither changes — e.g. a label-only PATCH won't re-validate them).
        val personFieldChanged =
            entity.componentOwner != oldOwner ||
                entity.releaseManagerUsernames() != oldReleaseManagers ||
                entity.securityChampionUsernames() != oldSecurityChampions
        val gateFlipped =
            entity.distributionExplicit != oldExplicit ||
                entity.distributionExternal != oldExternal
        validatePersonFields(entity, runActiveCheck = personFieldChanged || gateFlipped)
        validateRequiredCopyright(entity)
        validateRequiredDisplayName(entity)

        // Per-component child REPLACE — present collection wipes and refills (full ownership set)
        request.artifactIds?.let {
            entity.artifactMappings.clear()
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

        // Q13: validate the COMBINED post-patch state (flag + base build system are both applied by
        // now). CHANGE-based, not presence-based: only a real transition is rejected — the flag
        // actually flipping or the BASE build system actually changing. A full-form combined Save
        // that echoes an unchanged skipCommitCheck=true or buildSystem="WHISKEY" on a grandfathered
        // row (import admits WHISKEY+sentinel with a warning) is legal, mirroring the CRS-B
        // change-based write-gate and the "grandfathered on update" convention below.
        val newBaseBuildSystem = entity.configurations.firstOrNull { it.rowType == "BASE" }?.buildSystem
        if (entity.skipCommitCheck != oldSkipCommitCheck || newBaseBuildSystem != oldBaseBuildSystem) {
            validateSkipCommitCheckNotWhiskey(entity)
        }

        // Field overrides (item D): desired-FULL-SET applied in-place on the
        // configuration collection — cascade (orphanRemoval) persists the
        // inserts/deletes on the saveAndFlush below, bumping the aggregate
        // @Version once. null = don't touch. The returned plan carries the
        // post-flush required-tool syncs + per-override audit rows.
        val fieldOverridePlan = request.fieldOverrides?.let { applyFieldOverrideDesiredSet(entity, it) }

        // Cross-component / malformed-input checks are GRANDFATHERED on update:
        // they re-run only when the PATCH actually touches a field they govern
        // (distribution coordinates / jira / docs / the explicit-external-archived
        // gate). A PATCH that leaves all of those untouched (e.g. displayName,
        // people, labels, copyright only) must NOT re-validate the component's
        // pre-existing configuration — mirrors the person-field active-check
        // grandfathering above (a label-only PATCH doesn't re-validate people).
        // This does NOT weaken the rules: every governed field still triggers the
        // check when it changes; only untouched legacy data is left alone. On
        // CREATE everything is new, so they always run (see createComponent).
        val crossComponentRelevantChange =
            request.name != null || // rename can invalidate soft docs[] references to the old key
                request.baseConfiguration != null || // maven / docker / packages / jira live on the base config row
                request.artifactIds != null || // groupId/artifactId ownership mapping (#357 cross-component check)
                request.docs != null ||
                request.distributionExplicit != null ||
                request.distributionExternal != null ||
                request.archived != null ||
                request.fieldOverrides != null // marker overrides can carry colliding maven GAV / docker / jira coordinates

        // Malformed-input single-field checks (400) against the PATCHed final
        // entity state (so a caller need not resend unchanged fields).
        if (crossComponentRelevantChange) validateMalformedFieldRules(entity)

        entity.updatedAt = Instant.now()
        val saved = componentRepository.saveAndFlush(entity)

        if (crossComponentRelevantChange) {
            // Doc-component existence (#20, 400) — post-flush, consistent with create
            // (and so a rename that points docs[] at the new own key is accepted).
            validateDocComponentExistence(saved)

            // Cross-component integrity (409) against the final flushed state — the
            // self-excluding queries skip this component by its id.
            validateCrossComponentIntegrity(saved)
            // Ownership uniqueness only when the PATCH actually replaced the ownership mappings —
            // not on an unrelated rename/baseConfig change (which must not 409 on pre-existing data).
            if (request.artifactIds != null) validateArtifactOwnershipIfChanged(saved)
        }

        // Junctions — synced via their repositories after the parent flush so the
        // assigned ids (for newly-created rows) are visible.
        canonicalizedSystems?.let { syncSystems(saved.id!!, it) }
        canonicalizedLabels?.let { syncLabels(saved.id!!, it) }
        val patchedRequiredTools = request.baseConfiguration?.requiredTools
        if (baseConfigForToolsSync != null && patchedRequiredTools != null) {
            syncRequiredTools(baseConfigForToolsSync.id!!, patchedRequiredTools)
        }

        // Refresh in-memory junction collections so the response DTO reflects the
        // synced DB state — repo-direct writes bypass the entity's collections.
        canonicalizedLabels?.let { refreshComponentLabelsInMemory(saved, it) }
        canonicalizedSystems?.let { refreshComponentSystemsInMemory(saved, it) }
        if (baseConfigForToolsSync != null && patchedRequiredTools != null) {
            refreshConfigRequiredToolsInMemory(baseConfigForToolsSync, patchedRequiredTools)
        }

        // Item D: field-override post-flush work — sync the (non-cascaded)
        // required-tool junctions now that newly-inserted rows have ids, then
        // publish one audit row per override change (same granularity as the
        // standalone field-override CRUD).
        fieldOverridePlan?.let { plan ->
            plan.pendingTools.forEach { (row, tools) ->
                syncRequiredTools(row.id!!, tools)
                refreshConfigRequiredToolsInMemory(row, tools)
            }
            plan.deletedSnapshots.forEach { snapshot ->
                publishAuditEvent(
                    action = "UPDATE",
                    entityId = saved.id.toString(),
                    oldValue = snapshot,
                    newValue = emptyMap(),
                )
            }
            plan.updated.forEach { (row, before) ->
                publishAuditEvent(
                    action = "UPDATE",
                    entityId = saved.id.toString(),
                    oldValue = before,
                    newValue = fieldOverrideAuditSnapshot(row),
                )
            }
            plan.created.forEach { row ->
                publishAuditEvent(
                    action = "UPDATE",
                    entityId = saved.id.toString(),
                    oldValue = emptyMap(),
                    newValue = fieldOverrideAuditSnapshot(row),
                )
            }
        }

        if (isRename) sourceRegistry.renameComponent(oldKey, saved.componentKey)

        publishAuditEvent(
            action = if (isRename) "RENAME" else "UPDATE",
            entityId = saved.id.toString(),
            oldValue = oldValue,
            newValue =
                scalarAuditMap(
                    saved,
                    overrideLabels = canonicalizedLabels ?: originalLabels,
                    overrideSystems = canonicalizedSystems ?: originalSystems,
                ) + sectionAuditMap(saved),
            jiraTaskKey = request.jiraTaskKey,
            changeComment = request.changeComment,
        )

        return toDetail(saved)
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
        // CRS-B: introducing an override IS a value change on the overridden attribute.
        // A hidden attribute is masked as unknown (400, no leak); otherwise gate by the
        // caller's editability. Unknown/marker attributes resolve to `all` (no-op) and
        // still hit their own validation below.
        rejectHiddenOverrideAttribute(request.overriddenAttribute)
        enforceOverrideEditable(request.overriddenAttribute)
        // Whitespace normalisation up-front so the DB UNIQUE (which is exact-
        // string) and the duplicate-row lookup agree with the partial-overlap
        // validator below: `[1.0, 2.0)` stored as `[1.0,2.0)` lets the next
        // POST of `[1.0,2.0)` short-circuit at the UNIQUE check instead of
        // creating a near-duplicate row.
        val canonicalRange = normalizeRange(request.versionRange)
        validateFieldOverrideRange(
            range = canonicalRange,
            component = component,
            attribute = request.overriddenAttribute,
            excludeOverrideId = null,
        )
        require(configurationRepository.findByComponentIdAndVersionRangeAndOverriddenAttribute(
            componentId,
            canonicalRange,
            request.overriddenAttribute,
        ) == null) {
            "Override row for attribute '${request.overriddenAttribute}' and range '$canonicalRange' already exists"
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
                versionRange = canonicalRange,
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
        // ADR-018 redesign: coverage is override-independent — no write-time coverage split. The read
        // path partitions supported by this override's edges at enumerate time.
        bumpParentVersion(component)
        // Review #2: an override row can carry mavenArtifacts/dockerImages/jira
        // coordinates that collide with another component — re-run the same 409 /
        // 400 composite checks the create/update component paths run, now that the
        // override row is flushed.
        validateFieldOverrideCrossComponent(componentId)
        publishAuditEvent(
            action = "UPDATE",
            entityId = component.id.toString(),
            oldValue = emptyMap(),
            newValue = fieldOverrideAuditSnapshot(saved),
        )
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
        requireNotImportManagedMarker(row, "update")
        // CRS-B: a hidden attribute is masked as unknown (400) on the standalone endpoint.
        row.overriddenAttribute?.let { rejectHiddenOverrideAttribute(it) }

        val beforeSnapshot = fieldOverrideAuditSnapshot(row)

        // Gate-before-validation: an INVALID range/value on a non-editable override must
        // surface the editability error (403/422), not a value-400. The successful
        // valid-value path is still gated change-based by the snapshot diff below.
        val pendingTools: List<String>? =
            gatingEditabilityOnInvalidPayload(row.overriddenAttribute!!) {
                request.versionRange?.let {
                    val canonicalRange = normalizeRange(it)
                    validateFieldOverrideRange(
                        range = canonicalRange,
                        component = row.component,
                        attribute = row.overriddenAttribute!!,
                        excludeOverrideId = row.id,
                    )
                    row.versionRange = canonicalRange
                }
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
            }

        // CRS-B: gate any ACTUAL change (scalar value, markerChildren, or range) to an
        // override on a non-editable field. Snapshot-diff keeps it change-based across
        // both scalar and marker attributes — an unchanged echo leaves the snapshot
        // equal and is allowed (the transaction rolls back on the thrown 403/422).
        if (fieldOverrideAuditSnapshot(row) != beforeSnapshot) {
            enforceOverrideEditable(row.overriddenAttribute!!)
        }

        val saved = configurationRepository.save(row)
        if (pendingTools != null) {
            syncRequiredTools(saved.id!!, pendingTools)
            refreshConfigRequiredToolsInMemory(saved, pendingTools)
        }
        // ADR-018 redesign: coverage is override-independent — no write-time coverage split.
        bumpParentVersion(row.component)
        // Review #2: re-run the cross-component composite checks — an UPDATE to a
        // marker override can introduce a colliding GAV / docker image / jira
        // coordinate just as a create can.
        validateFieldOverrideCrossComponent(componentId)
        publishAuditEvent(
            action = "UPDATE",
            entityId = row.component.id.toString(),
            oldValue = beforeSnapshot,
            newValue = fieldOverrideAuditSnapshot(saved),
        )
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
        requireNotImportManagedMarker(row, "delete")
        // CRS-B: a hidden attribute is masked as unknown (400); otherwise removing an override
        // changes the effective value — gate it like the desired-set delete (no direct-DELETE bypass).
        row.overriddenAttribute?.let {
            rejectHiddenOverrideAttribute(it)
            enforceOverrideEditable(it)
        }
        val owningComponent = row.component
        val beforeSnapshot = fieldOverrideAuditSnapshot(row)
        configurationRepository.delete(row)
        bumpParentVersion(owningComponent)
        publishAuditEvent(
            action = "UPDATE",
            entityId = owningComponent.id.toString(),
            oldValue = beforeSnapshot,
            newValue = emptyMap(),
        )
    }

    /**
     * MIG-047: refuse V4 write paths for MARKER rows whose `overriddenAttribute`
     * is NOT in [MarkerAttributes.ALL] — those are import-internal markers
     * (currently only [MarkerAttributes.GROUP_ARTIFACT_PATTERN]) created by
     * `ImportServiceImpl` and recovered on re-import. The V4 admin API surfaces
     * them read-only via `listFieldOverrides` so users can see the configuration;
     * editing or deleting them would diverge the DB from the DSL source of
     * truth until the next import overwrote the change.
     */
    private fun requireNotImportManagedMarker(
        row: ComponentConfigurationEntity,
        operation: String,
    ) {
        if (row.rowType == "MARKER" && row.overriddenAttribute !in MarkerAttributes.ALL) {
            throw IllegalArgumentException(
                "Cannot $operation id ${row.id} via field-override endpoint: " +
                    "marker '${row.overriddenAttribute}' is import-managed and read-only via V4. " +
                    "Edit the source DSL and re-import to change this override.",
            )
        }
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

    /**
     * What [applyFieldOverrideDesiredSet] changed, so [updateComponent] can do the
     * post-flush work that needs assigned ids: sync the non-cascaded required-tool
     * junctions and publish one audit row per override change.
     */
    private data class FieldOverrideApplyPlan(
        val created: List<ComponentConfigurationEntity>,
        val updated: List<Pair<ComponentConfigurationEntity, Map<String, Any?>>>,
        val deletedSnapshots: List<Map<String, Any?>>,
        val pendingTools: Map<ComponentConfigurationEntity, List<String>>,
    )

    /**
     * Item D — apply [desired] as the desired-FULL-SET of V4-editable field
     * overrides for [component], mutating the in-memory configuration collection
     * so the caller's single saveAndFlush cascades inserts + orphan-deletes and
     * bumps the aggregate @Version once. Reuses the same validators/appliers as
     * the standalone CRUD (disjoint-range rule, scalar typing, marker children).
     *
     * Order is DELETE -> UPDATE -> CREATE so the in-memory disjoint-range check
     * (which walks the live collection) never trips on a row that is itself being
     * removed in the same request. Import-managed markers (group-artifact-pattern)
     * are out of scope: never updated or deleted, and a client echo of one (by id)
     * is preserved as a no-op.
     *
     * Known limitation (shared with sequential standalone updates): a single
     * request that SWAPS two existing rows' ranges on the same attribute is
     * rejected, because each update validates against the other row still at its
     * old range. Such a swap must be done in two saves (or delete + create).
     */
    private fun applyFieldOverrideDesiredSet(
        component: ComponentEntity,
        desired: List<FieldOverrideUpsertRequest>,
    ): FieldOverrideApplyPlan {
        fun isImportManaged(row: ComponentConfigurationEntity) =
            row.rowType == "MARKER" && row.overriddenAttribute !in MarkerAttributes.ALL

        val existing = component.configurations.filter { it.rowType in FIELD_OVERRIDE_ROW_TYPES }
        val byId = existing.mapNotNull { row -> row.id?.let { it to row } }.toMap()

        // Referenced ids must exist on this component (clear 400, not a silent create).
        desired.forEach { d ->
            if (d.id != null && d.id !in byId) {
                throw IllegalArgumentException(
                    "fieldOverrides: override id '${d.id}' not found on component '${component.id}'",
                )
            }
        }
        val keepIds = desired.mapNotNull { it.id }.toSet()
        val pendingTools = LinkedHashMap<ComponentConfigurationEntity, List<String>>()

        // 1) DELETE editable rows omitted from the desired set (orphanRemoval on flush).
        // CRS-B: hidden-attribute rows are PRESERVED here (never deleted) — like import-managed
        // markers — so a client that (correctly) omits hidden fields from its slice does not
        // silently drop them.
        val deletedSnapshots = mutableListOf<Map<String, Any?>>()
        existing
            .filter { !isImportManaged(it) && !isHiddenOverrideAttribute(it.overriddenAttribute) && it.id !in keepIds }
            .forEach { row ->
                // Removing an override changes the effective value on that attribute — gate it.
                row.overriddenAttribute?.let { enforceOverrideEditable(it) }
                deletedSnapshots += fieldOverrideAuditSnapshot(row)
                component.configurations.remove(row)
            }

        // 2) UPDATE existing editable rows referenced by id.
        val updated = mutableListOf<Pair<ComponentConfigurationEntity, Map<String, Any?>>>()
        desired.filter { it.id != null }.forEach { d ->
            val row = byId.getValue(d.id!!)
            if (isImportManaged(row)) return@forEach // preserve; ignore client echo
            // CRS-B: hidden-attribute rows are stripped — preserved as-is, incoming change ignored.
            if (isHiddenOverrideAttribute(row.overriddenAttribute)) return@forEach
            require(d.overriddenAttribute == row.overriddenAttribute) {
                "fieldOverrides: cannot change overriddenAttribute of override '${d.id}' " +
                    "(${row.overriddenAttribute} -> ${d.overriddenAttribute})"
            }
            val before = fieldOverrideAuditSnapshot(row)
            // Gate-before-validation: an INVALID range/value on a non-editable override
            // yields the editability error (403/422), not a value-400. A valid change is
            // still gated change-based by the snapshot diff below.
            gatingEditabilityOnInvalidPayload(d.overriddenAttribute) {
                applyOverrideUpsertPayload(component, row, d, excludeOverrideId = row.id)
            }?.let { pendingTools[row] = it }
            // CRS-B: gate any actual change (value / markerChildren / range) to an
            // override on a non-editable field; an unchanged echo (combined Save
            // re-sends the whole set) leaves the snapshot equal and is left alone.
            if (fieldOverrideAuditSnapshot(row) != before) {
                enforceOverrideEditable(d.overriddenAttribute)
            }
            updated += row to before
        }

        // 3) CREATE rows without an id.
        val created = mutableListOf<ComponentConfigurationEntity>()
        desired.filter { it.id == null }.forEach { d ->
            // CRS-B: hidden-attribute create is silently dropped (strip); otherwise a new
            // override is a value change on the overridden attribute — gate it.
            if (isHiddenOverrideAttribute(d.overriddenAttribute)) return@forEach
            enforceOverrideEditable(d.overriddenAttribute)
            val row =
                ComponentConfigurationEntity(
                    component = component,
                    versionRange = "",
                    overriddenAttribute = d.overriddenAttribute,
                    rowType = "",
                )
            applyOverrideUpsertPayload(component, row, d, excludeOverrideId = null)?.let { pendingTools[row] = it }
            component.configurations.add(row)
            // Explicit save (like the single-row create path) so the generated id
            // is assigned to this instance now — the parent saveAndFlush's cascade
            // does not back-populate it onto our reference, which the post-flush
            // required-tool sync + audit snapshot need.
            configurationRepository.save(row)
            created += row
        }
        return FieldOverrideApplyPlan(created, updated, deletedSnapshots, pendingTools)
    }

    /**
     * Set [row]'s range/value/markerChildren to the desired state in [d]
     * (validating the range against siblings, excluding self), classify its
     * rowType, and return the pending required-tool list for build.requiredTools
     * markers (synced post-flush), or null. Shared by the upsert create + update
     * paths; mirrors the single-row create/update validation exactly.
     */
    private fun applyOverrideUpsertPayload(
        component: ComponentEntity,
        row: ComponentConfigurationEntity,
        d: FieldOverrideUpsertRequest,
        excludeOverrideId: UUID?,
    ): List<String>? {
        val canonicalRange = normalizeRange(d.versionRange)
        validateFieldOverrideRange(
            range = canonicalRange,
            component = component,
            attribute = d.overriddenAttribute,
            excludeOverrideId = excludeOverrideId,
        )
        row.versionRange = canonicalRange
        return when {
            d.overriddenAttribute in MarkerAttributes.ALL -> {
                row.rowType = "MARKER"
                require(d.value == null) {
                    "Marker override '${d.overriddenAttribute}' must not carry a scalar value"
                }
                requireNotNull(d.markerChildren) {
                    "Marker override '${d.overriddenAttribute}' requires markerChildren payload"
                }
                applyMarkerChildren(row, d.overriddenAttribute, d.markerChildren)
            }

            d.overriddenAttribute in SCALAR_ATTRIBUTE_PATHS -> {
                row.rowType = "SCALAR_OVERRIDE"
                require(d.markerChildren == null) {
                    "Scalar override '${d.overriddenAttribute}' must not carry markerChildren"
                }
                row.applyScalarValue(d.overriddenAttribute, d.value)
                null
            }

            else -> throw IllegalArgumentException(
                "Unknown overriddenAttribute: '${d.overriddenAttribute}'. " +
                    "Must be a scalar aspect.field path or one of ${MarkerAttributes.ALL}",
            )
        }
    }

    @Transactional(readOnly = true)
    override fun getSupportedVersions(componentId: UUID): SupportedVersionsResponse {
        if (!componentRepository.existsById(componentId)) {
            throw NotFoundException("Component with id '$componentId' not found")
        }
        val ranges =
            configurationRepository
                .findByComponentId(componentId)
                .filter { it.rowType == "RANGE_PRESENCE" }
                .map { it.versionRange }
                .sortedWith(SUPPORTED_RANGE_ORDER)
        // No bounded RANGE_PRESENCE rows ⇒ the ALL_VERSIONS base covers everything (ADR-018).
        return SupportedVersionsResponse(all = ranges.isEmpty(), ranges = ranges)
    }

    @Transactional
    override fun setSupportedVersions(
        componentId: UUID,
        request: SupportedVersionsRequest,
    ): SupportedVersionsResponse {
        val component = findComponentOr404(componentId)
        // supported = ALL when explicitly requested, or when no ranges are supplied.
        val all = request.all == true || request.ranges.isNullOrEmpty()
        // Normalize the requested set to the canonical MERGED union (adjacent/overlapping collapse;
        // gaps preserved) — coverage is override-independent (ADR-018 redesign); the read path
        // partitions supported by value-change edges, so there is no write-time auto-split. The merge
        // also makes any re-PUT a true no-op delta (no insert-before-delete unique-index violation).
        val finalRanges =
            if (all) {
                emptyList()
            } else {
                val normalized = request.ranges!!.map { normalizeRange(it) }
                validateSupportedRanges(normalized)
                // Canonical invariant: supported = ALL ⟺ no RANGE_PRESENCE rows. validateSupportedRanges
                // rejects each INDIVIDUAL all-versions range, but ranges that *tile* all-versions
                // (e.g. ["(,2.0)","[2.0,)"]) collapse to the sentinel under mergeUnion — drop it so we
                // store zero presence rows (= ALL) rather than a bounded-looking all-versions row that
                // reports all=false yet matches every override. Mirrors ImportServiceImpl's coverage filter.
                VersionRangePartition.mergeUnion(normalized, numericVersionComparator(numericVersionFactory))
                    .filterNot { isAllVersionsCoverage(it) }
            }

        // Declarative replace as a DELTA (delete only removed, add only new) so identical rows are
        // kept, never removed-then-re-added.
        val finalSet = finalRanges.toSet()
        val existingPresence = component.configurations.filter { it.rowType == "RANGE_PRESENCE" }
        val existingRanges = existingPresence.map { it.versionRange }.toSet()
        existingPresence
            .filter { it.versionRange !in finalSet }
            .forEach { component.configurations.remove(it) }
        finalRanges
            .filter { it !in existingRanges }
            .forEach {
                component.configurations.add(
                    ComponentConfigurationEntity(
                        component = component,
                        versionRange = it,
                        overriddenAttribute = null,
                        rowType = "RANGE_PRESENCE",
                    ),
                )
            }

        // V1/V5 (warn-and-allow): surface overrides left entirely outside the new supported set — they
        // never resolve (the coverage gate 404s first). Non-blocking; the write still succeeds.
        // `finalRanges` can be empty even when `all=false` (the request's ranges TILED all-versions and
        // collapsed to ALL) — that is effectively supported=ALL, so no override is "outside"; treat it
        // as `all` to avoid spurious warnings.
        val effectiveAll = all || finalRanges.isEmpty()
        val warnings = supportedCoverageWarnings(component, finalRanges, effectiveAll)

        bumpParentVersion(component)
        val resulting =
            component.configurations
                .filter { it.rowType == "RANGE_PRESENCE" }
                .map { it.versionRange }
                .sortedWith(SUPPORTED_RANGE_ORDER)
        publishAuditEvent(
            action = "UPDATE",
            entityId = component.id.toString(),
            oldValue = mapOf("supportedVersions" to existingPresence.map { it.versionRange }.sortedWith(SUPPORTED_RANGE_ORDER)),
            newValue = mapOf("supportedVersions" to resulting.ifEmpty { listOf("ALL") }),
        )
        return SupportedVersionsResponse(all = resulting.isEmpty(), ranges = resulting, warnings = warnings)
    }

    /**
     * Validate a requested supported-versions set (PUT): each range must parse and must NOT be an
     * all-versions sentinel (use `all:true` instead of a bounded-looking RANGE_PRESENCE row that would
     * report `all=false` yet match every override). There is **no disjointness requirement** (ADR-018
     * redesign): overlapping/adjacent requested ranges are normalized into the canonical MERGED union
     * by `VersionRangePartition.mergeUnion` at the call site, and enumeration breakpoints are derived
     * at read time — coverage is a union, not a partition.
     */
    private fun validateSupportedRanges(ranges: List<String>) {
        ranges.forEach { r ->
            validateRangeSyntax(r)
            require(!isAllVersionsCoverage(r)) {
                "Range '$r' denotes all versions — request supported = ALL via {\"all\": true} " +
                    "instead of an all-versions coverage row."
            }
        }
        // No disjointness requirement: overlapping/adjacent requested ranges are normalized into the
        // canonical merged union by VersionRangePartition.mergeUnion (ADR-018 redesign — coverage is a
        // union, not a partition; enumeration breakpoints are derived at read time).
    }

    /** True iff [range] denotes all versions (the ALL_VERSIONS sentinel or the unbounded `(,)`). */
    private fun isAllVersionsCoverage(range: String): Boolean {
        val n = normalizeRange(range)
        return n == ALL_VERSIONS || n == "(,)"
    }

    /** V1/V5 advisories: an override whose range no longer intersects supported is unreachable. */
    private fun supportedCoverageWarnings(
        component: ComponentEntity,
        supported: List<String>,
        all: Boolean,
    ): List<String> {
        if (all) return emptyList()
        val supportedObjs = supported.mapNotNull { runCatching { versionRangeFactory.create(it) }.getOrNull() }
        return component.configurations
            .filter { it.rowType in FIELD_OVERRIDE_ROW_TYPES }
            .filter { ov ->
                // An unparseable override range is itself unreachable — surface it (warn), do NOT
                // silently treat it as covered (that would suppress the very advisory it should raise).
                val ovObj = runCatching { versionRangeFactory.create(ov.versionRange) }.getOrNull() ?: return@filter true
                supportedObjs.none { it.isIntersect(ovObj) }
            }
            .map { ov ->
                "Override '${ov.overriddenAttribute}' on range '${ov.versionRange}' is outside the supported " +
                    "versions and will never resolve (V1/V5) — remove it or extend supported to cover it."
            }
    }

    // ============================================================
    // Helpers — child collection management
    // ============================================================

    private fun addArtifactIds(
        component: ComponentEntity,
        requests: List<org.octopusden.octopus.components.registry.server.dto.v4.ArtifactIdRequest>,
    ) {
        // Canonicalization: one Maven groupId per stored row. A request's comma group-list expands
        // to one mapping per group (sharing mode/tokens/range), so `sort_order` is a RUNNING counter
        // across all requests — NOT the request index, which would collide/reorder once a request
        // yields >1 row (the resolver treats the lowest sort_order as the legacy primary).
        // Validations (all 400 / IllegalArgumentException): group/token allowlist, mode
        // default + token invariants, ALL_EXCEPT single-group, range alignment, and the
        // intra-component "a group token belongs to ≤1 mapping per (component, range)" rule.
        val claimedByRange = mutableSetOf<Pair<String, String>>()
        var sortOrder = 0
        requests.forEach { req ->
            val versionRange = req.versionRange?.takeIf { it.isNotBlank() } ?: ALL_VERSIONS
            val groupRaw = req.groupPattern.trim()
            require(groupRaw.isNotEmpty()) { "artifactIds: groupPattern must not be blank" }
            // Split into per-group rows (canonicalization); fail-loud on an empty comma segment
            // ("a,", "a,,b", ",") rather than silently dropping a group.
            val groupItems =
                try {
                    ArtifactOwnershipModeClassifier.splitGroups(groupRaw)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("artifactIds: ${e.message}", e)
                }
            groupItems.forEach { g ->
                require(ArtifactOwnershipModeClassifier.isLiteralToken(g)) {
                    "artifactIds: invalid group '$g' — letters, digits, . _ - only (no wildcards or regex)"
                }
            }
            val tokens = req.artifactTokens.map { it.trim() }.filter { it.isNotEmpty() }
            val mode = resolveArtifactIdMode(req.mode, tokens)
            when (mode) {
                ArtifactIdMode.EXPLICIT -> {
                    require(tokens.isNotEmpty()) {
                        "artifactIds: 'Specific artifacts' (EXPLICIT) requires at least one artifact token"
                    }
                    tokens.forEach { t ->
                        require(ArtifactOwnershipModeClassifier.isLiteralToken(t)) {
                            "artifactIds: invalid artifact '$t' — literal IDs only (letters, digits, . _ -)"
                        }
                    }
                }
                ArtifactIdMode.ALL, ArtifactIdMode.ALL_EXCEPT_CLAIMED ->
                    require(tokens.isEmpty()) { "artifactIds: ${mode.name} mode must not carry artifact tokens" }
            }
            require(!(mode == ArtifactIdMode.ALL_EXCEPT_CLAIMED && groupItems.size > 1)) {
                "artifactIds: 'All unclaimed' (ALL_EXCEPT_CLAIMED) supports a single group only — " +
                    "split into one mapping per group"
            }
            require(versionRange == ALL_VERSIONS || component.configurations.any { it.versionRange == versionRange }) {
                "artifactIds: versionRange '$versionRange' must equal an existing configuration range or be the base"
            }
            groupItems.forEach { g ->
                require(claimedByRange.add(versionRange to g)) {
                    "artifactIds: group '$g' is claimed by more than one mapping in range '$versionRange'"
                }
            }
            groupItems.forEach { g ->
                val mapping = ComponentArtifactMappingEntity(
                    component = component,
                    versionRange = versionRange,
                    groupPattern = g,
                    artifactIdMode = mode.name,
                    sortOrder = sortOrder++,
                )
                tokens.forEachIndexed { i, t ->
                    mapping.tokens.add(
                        ComponentArtifactMappingTokenEntity(mapping = mapping, artifactPattern = t, sortOrder = i),
                    )
                }
                component.artifactMappings.add(mapping)
            }
        }
        validateNonOverlappingOwnershipRanges(component)
    }

    /**
     * Mode default (review-fix — no silent downgrade): an unspecified mode defaults to ALL only
     * when there are no tokens; tokens present + no mode ⇒ EXPLICIT; an explicit mode is honored.
     */
    private fun resolveArtifactIdMode(mode: String?, tokens: List<String>): ArtifactIdMode =
        when {
            !mode.isNullOrBlank() ->
                runCatching { ArtifactIdMode.valueOf(mode) }.getOrElse {
                    throw IllegalArgumentException("artifactIds: unknown mode '$mode'")
                }
            tokens.isNotEmpty() -> ArtifactIdMode.EXPLICIT
            else -> ArtifactIdMode.ALL
        }

    /** Per-component non-base ownership ranges must be pairwise non-overlapping (most-specific wins). */
    private fun validateNonOverlappingOwnershipRanges(component: ComponentEntity) {
        val nonBaseRanges =
            component.artifactMappings.map { it.versionRange }.filter { it != ALL_VERSIONS }.distinct()
        for (i in nonBaseRanges.indices) {
            for (j in i + 1 until nonBaseRanges.size) {
                val intersects =
                    runCatching {
                        versionRangeFactory.create(nonBaseRanges[i]).isIntersect(versionRangeFactory.create(nonBaseRanges[j]))
                    }.getOrDefault(true)
                require(!intersects) {
                    "artifactIds: per-range ownership ranges must be disjoint — " +
                        "'${nonBaseRanges[i]}' overlaps '${nonBaseRanges[j]}'"
                }
            }
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
     * Canonicalise a raw incoming label set: trim whitespace, drop entries
     * that are empty or whitespace-only, and dedupe. Mirrors the controller
     * read-side normalisation pipeline for `?labels=…` (split → trim →
     * filter empty → distinct → takeIf) so a label code is stored,
     * retrieved, and filtered with the same canonical form regardless of
     * the path that produced it. Without this the controller would trim
     * `?labels=A ` to `A` on read but a write of `labels=["A "]` would
     * persist `"A "` and never be filterable as `A` again — see SYS-040
     * write-side canonicalisation rationale.
     */
    private fun canonicalizeLabels(raw: Set<String>): Set<String> =
        raw.map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /**
     * Reject a write that consists entirely of blank/whitespace label
     * codes. Throws IllegalArgumentException → 400 via the controller
     * exception handler. An entirely-empty desired set is allowed
     * (clearing labels is a legitimate operation); the rejection is for
     * the case where the caller sent non-empty input that canonicalises
     * to nothing — almost certainly a UI bug or a caller mistake.
     */
    private fun validateLabels(raw: Set<String>) {
        if (raw.isEmpty()) return
        require(canonicalizeLabels(raw).isNotEmpty()) {
            "labels: cannot consist entirely of blank entries"
        }
    }

    /**
     * Trim, drop blank entries, and validate + canonicalize every remaining
     * system code (see [validateAndCanonicalizeSystemCode]), deduplicating on
     * the canonical form. An entirely-empty (or entirely-blank) input yields an
     * empty set — a legitimate "no systems" assignment. A non-blank code that is
     * neither config-allowed nor a known master code throws → 400.
     */
    private fun canonicalizeSystems(raw: Set<String>): Set<String> =
        raw.map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { validateAndCanonicalizeSystemCode(it) }
            .toSet()

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
        // Defense-in-depth canonicalisation: every caller is expected to
        // run validateLabels + canonicalizeLabels first, but a stray blank
        // or duplicate getting this far would otherwise land in the
        // junction and surface as an unselectable blank chip / redundant
        // row. Canonicalise here too so the persistence layer is the
        // single source of truth for the canonical form.
        val canonical = canonicalizeLabels(desired)
        val existing = componentLabelRepository.findByComponentId(componentId)
        if (existing.isNotEmpty()) componentLabelRepository.deleteAllInBatch(existing)
        canonical.forEach { code ->
            ensureLabelExists(code)
            componentLabelRepository.save(
                ComponentLabelEntity(componentId = componentId, labelCode = code),
            )
        }
    }

    /**
     * Replace the `component_systems` junction rows for [componentId] with
     * exactly [desired]. Same cascade-free convention as labels — the parent
     * `components` row must be flushed first. Callers pass a set already run
     * through [canonicalizeSystems] (validated + canonical casing); the
     * per-code [ensureSystemExists] here keeps the FK to `systems(code)`
     * satisfied for a config-allowed code introduced for the first time.
     */
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
     * Validate `value` against the env-config allowlist
     * `components-registry.supportedSystems` (primary path) AND return the
     * canonical form. A secondary path
     * also accepts any code that's already in the master `systems`
     * table — this matches the import-side seeding model in
     * `ImportServiceImpl.upsertSystem`, which auto-creates `SystemEntity`
     * rows from DSL discovery without requiring an env-config redeploy.
     * Without this secondary check, a v4 caller could not edit a
     * component whose system code was legitimately introduced by a DSL
     * import.
     *
     * Caller is expected to trim + `takeIf(isNotEmpty)` before calling,
     * so we never see blank input here. A code that matches neither
     * source is rejected with 400 — same error shape as
     * `validateBuildSystem` / `validateProductType`. Unlike groupKey,
     * no prefix logic: `system_code` is a flat enum-like identifier
     * (exact match against a configured / known value).
     *
     * **Returns the canonical form, NOT the caller's input.** Case
     * matching against the config path is case-insensitive (env config
     * typically uses upper-case codes `NONE,CLASSIC,ALFA` but a caller
     * posting `Classic` should not be rejected for stylistic difference), but
     * the form persisted to the `component_systems` junction is the config's
     * canonical casing — NOT the caller's input. This prevents a v4
     * write of `system: "Classic"` from creating a duplicate master
     * `systems("Classic")` row alongside the canonical `systems("CLASSIC")`
     * row (the PK on `systems.code` is case-sensitive), which would
     * make `?system=CLASSIC` filter miss components stored as
     * `"Classic"` and surface duplicate entries on
     * `/meta/systems/dictionary`. The master-table fallback compares
     * case-sensitively (PK match), so codes added by DSL import
     * round-trip verbatim.
     */
    private fun validateAndCanonicalizeSystemCode(value: String): String {
        require(value.isNotBlank()) { "system must not be blank" }
        val allowed = configHelper.supportedSystems().toList()
        val canonicalFromConfig = allowed.firstOrNull { it.equals(value, ignoreCase = true) }
        if (canonicalFromConfig != null) return canonicalFromConfig
        if (systemRepository.findByCode(value) != null) return value
        throw IllegalArgumentException(
            "Invalid system: '$value' is not in the configured supportedSystems " +
                "and is not a known imported system code. Allowed (config): $allowed",
        )
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

    /**
     * Enforce the parent/canBeParent invariants on create/update. Only fires on a
     * genuinely new or changed relationship — a no-op update over a grandfathered
     * parent-of-parent row passes untouched, and clearing the parent is always
     * allowed (remediation path).
     */
    private fun validateParentInvariants(
        entity: ComponentEntity,
        parentChanged: Boolean,
        canBeParentChanged: Boolean,
    ) {
        val parent = entity.parentComponent
        if (parent != null && (parentChanged || canBeParentChanged)) {
            require(parent.canBeParent) {
                "parentComponentName: parent '${parent.componentKey}' is not marked can-be-parent"
            }
            require(!entity.canBeParent) {
                "parentComponentName: a component that can be a parent cannot itself have a parent"
            }
        }
        if (canBeParentChanged && !entity.canBeParent && entity.id != null &&
            componentRepository.existsByParentComponentId(entity.id!!)
        ) {
            throw IllegalArgumentException(
                "canBeParent: cannot disable can-be-parent while other components reference this as their parent",
            )
        }
    }

    // ============================================================
    // Helpers — field-config editability enforcement (CRS-B)
    // ============================================================

    /**
     * CRS-B editability write-gate for a single scalar field, keyed by the
     * section-prefixed field-config path (e.g. `jira.technical`,
     * `component.vcsExternalRegistry`).
     *
     * **Change-based** — the single most important property: a write is rejected
     * only when it would actually CHANGE the field. Echoing the current value (or
     * clearing an already-empty field) is a no-op and is always allowed, because
     * the Portal's combined Save posts whole section slices; a presence-based
     * reject would fail every ordinary editor's save. On CREATE the caller passes
     * `current = null`, so any supplied non-null value counts as a change.
     *
     *  - `editable: adminOnly` without `EDIT_ANY_COMPONENT` → 403 (authorization).
     *  - `editable: none` / `visibility: readonly` (unified) → 422 for everyone.
     *  - `visibility: hidden` is NOT handled here — it keeps its silent-strip
     *    behavior at the individual write sites (`isHidden` short-circuits first).
     *
     * String values are compared with CRS-A "" ≡ null normalization (blank means
     * clear); booleans and lists compare by value equality. Comparison is
     * case-sensitive for strings (a case-only echo of an already-canonicalized
     * value is treated as a change — erring toward reject, the safe direction).
     */
    private fun enforceFieldEditable(
        fieldPath: String,
        incoming: Any?,
        current: Any?,
    ) {
        if (fieldConfigService.isHidden(fieldPath)) return
        if (!isFieldChange(incoming, current)) return
        when (fieldConfigService.editabilityFor(fieldPath)) {
            "adminonly" ->
                if (!permissionEvaluator.canEditAdminOnlyFields()) {
                    throw ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Field '$fieldPath' is editable by administrators only",
                    )
                }
            "none" ->
                throw ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Field '$fieldPath' is not editable",
                )
            else -> Unit // "all"
        }
    }

    /**
     * Reject a create/modify/delete of a per-range override on a field the caller
     * may not edit — the same policy as [enforceFieldEditable], but for the
     * field-override sub-resource where introducing/removing an override IS a
     * value change on the overridden attribute (`overriddenAttribute` doubles as
     * the field-config path, e.g. `jira.technical`). Callers invoke this only for
     * actual changes (an unchanged desired-set echo is left untouched).
     *
     * `hidden` is NOT handled here — the two override entry points treat it differently:
     * the combined-Save desired-set silently strips hidden-attribute records (consistent
     * with the base write), while the standalone CRUD endpoints reject them via
     * [rejectHiddenOverrideAttribute]. Callers apply the hidden rule first.
     */
    private fun enforceOverrideEditable(attribute: String) {
        when (fieldConfigService.editabilityFor(attribute)) {
            "adminonly" ->
                if (!permissionEvaluator.canEditAdminOnlyFields()) {
                    throw ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Field-override on '$attribute' is editable by administrators only",
                    )
                }
            "none" ->
                throw ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Field-override on '$attribute' targets a non-editable field",
                )
            else -> Unit // "all"
        }
    }

    /**
     * Run [applyPayload] — which mutates an override row and may throw a value-400
     * (ConfigurationRowAccessors.requireString / requireBuildSystem / range checks) —
     * but make the editability gate take precedence: if application fails on a
     * non-editable [attribute], the caller sees the 403/422 editability error, NOT the
     * value-400. An editable caller still gets the original 400. The callers keep their
     * own change-based snapshot-diff gate on the SUCCESSFUL, valid-value path (an
     * unchanged echo applies cleanly, diffs equal, and is left untouched).
     */
    private inline fun <T> gatingEditabilityOnInvalidPayload(
        attribute: String,
        applyPayload: () -> T,
    ): T =
        try {
            applyPayload()
        } catch (e: IllegalArgumentException) {
            enforceOverrideEditable(attribute)
            throw e
        }

    /** True when the override's attribute maps to a field-config `visibility: hidden`. */
    private fun isHiddenOverrideAttribute(attribute: String?): Boolean =
        attribute != null && fieldConfigService.isHidden(attribute)

    /**
     * Standalone field-override CRUD rejects a `hidden` attribute with the SAME 400 as a
     * genuinely unknown attribute — deliberately NOT distinguishing "hidden" from "does not
     * exist". Field paths are a public contract, but which fields an installation hides is
     * not: leaking it here would let a caller enumerate hidden config via the override API.
     * (The combined-Save desired-set instead silently strips hidden records — see
     * [applyFieldOverrideDesiredSet].)
     */
    private fun rejectHiddenOverrideAttribute(attribute: String) {
        if (fieldConfigService.isHidden(attribute)) throw unknownOverrideAttribute(attribute)
    }

    /**
     * CRS-B create-side hidden strip: returns null when [fieldPath] is `hidden` (so the
     * value is not persisted), else the value verbatim. Mirrors the component-scalar
     * `if (!isHidden)` gate the PATCH path applies at each write site; used to keep CREATE
     * symmetric with PATCH (a hidden field is silently dropped, never persisted, never 4xx).
     */
    private fun <T> stripIfHidden(
        fieldPath: String,
        value: T?,
    ): T? = if (fieldConfigService.isHidden(fieldPath)) null else value

    /** The canonical 400 for an unsupported override attribute (also reused to mask hidden ones). */
    private fun unknownOverrideAttribute(attribute: String): IllegalArgumentException =
        IllegalArgumentException(
            "Unknown overriddenAttribute: '$attribute'. " +
                "Must be a scalar aspect.field path or one of ${MarkerAttributes.ALL}",
        )

    /**
     * Change detection for the editability gate. For strings it delegates to the CRS-A
     * storage normalizer [clearBlankScalar] so the comparison rule can NEVER drift from what
     * the write path actually persists (`ifBlank { null }`, NO trim: blank clears to null, a
     * non-blank value is kept verbatim — surrounding whitespace is significant for format
     * templates). Trimming here would make a padded echo (`"  x  "` vs stored `"x"`) compare
     * equal while the write would still persist the padded value — a silent change to a
     * non-editable field. Booleans/lists compare by value.
     */
    private fun isFieldChange(
        incoming: Any?,
        current: Any?,
    ): Boolean {
        fun norm(v: Any?): Any? = if (v is String) clearBlankScalar(v) else v
        return norm(incoming) != norm(current)
    }

    /**
     * CRS-B: enforce editability across every scalar the given PATCH touches, BEFORE
     * any mutation, comparing incoming against the entity's current value (its BASE
     * configuration row for jira/build aspect scalars). `null`/absent request fields
     * are skipped (JSON Merge Patch "don't touch"), so only fields actually present
     * in the payload are considered — and even then only a real value change is gated.
     */
    private fun enforceEditabilityOnUpdate(
        entity: ComponentEntity,
        request: ComponentUpdateRequest,
    ) {
        request.displayName?.let { enforceFieldEditable("component.displayName", it, entity.displayName) }
        request.componentOwner?.let { enforceFieldEditable("component.componentOwner", it, entity.componentOwner) }
        request.productType?.let { enforceFieldEditable("component.productType", it, entity.productType) }
        request.clientCode?.let { enforceFieldEditable("component.clientCode", it, entity.clientCode) }
        request.solution?.let { enforceFieldEditable("component.solution", it, entity.solution) }
        request.copyright?.let { enforceFieldEditable("component.copyright", it, entity.copyright) }
        request.releasesInDefaultBranch?.let {
            enforceFieldEditable("component.releasesInDefaultBranch", it, entity.releasesInDefaultBranch)
        }
        request.jiraDisplayName?.let { enforceFieldEditable("component.jiraDisplayName", it, entity.jiraDisplayName) }
        request.jiraHotfixVersionFormat?.let {
            enforceFieldEditable("component.jiraHotfixVersionFormat", it, entity.jiraHotfixVersionFormat)
        }
        request.vcsExternalRegistry?.let {
            enforceFieldEditable("component.vcsExternalRegistry", it, entity.vcsExternalRegistry)
        }
        // Editable by all by default (Q11); gated here only so field-config CAN narrow it later.
        request.skipCommitCheck?.let { enforceFieldEditable("component.skipCommitCheck", it, entity.skipCommitCheck) }
        request.distributionExplicit?.let { enforceFieldEditable("component.distributionExplicit", it, entity.distributionExplicit) }
        request.distributionExternal?.let { enforceFieldEditable("component.distributionExternal", it, entity.distributionExternal) }
        request.systems?.let { enforceFieldEditable("component.system", it, entity.systemJunctions.map { j -> j.systemCode }.toSet()) }
        request.releaseManager?.let { enforceFieldEditable("component.releaseManager", it, entity.releaseManagerUsernames()) }
        request.securityChampion?.let { enforceFieldEditable("component.securityChampion", it, entity.securityChampionUsernames()) }
        request.canBeParent?.let { enforceFieldEditable("component.canBeParent", it, entity.canBeParent) }
        request.parentComponentName?.let {
            enforceFieldEditable("component.parentComponentName", it, entity.parentComponent?.componentKey)
        }
        // clearParent is the explicit "remove parent" signal — an actual change only when a
        // parent currently exists; gate it under the same parentComponentName policy.
        if (request.clearParent && entity.parentComponent != null) {
            enforceFieldEditable("component.parentComponentName", null, entity.parentComponent?.componentKey)
        }

        val base = entity.configurations.firstOrNull { it.rowType == "BASE" }
        request.baseConfiguration?.jira?.let { j ->
            j.projectKey?.let { enforceFieldEditable("jira.projectKey", it, base?.jiraProjectKey) }
            j.technical?.let { enforceFieldEditable("jira.technical", it, base?.jiraTechnical) }
            j.minorVersionFormat?.let { enforceFieldEditable("jira.minorVersionFormat", it, base?.jiraMinorVersionFormat) }
            j.releaseVersionFormat?.let { enforceFieldEditable("jira.releaseVersionFormat", it, base?.jiraReleaseVersionFormat) }
            j.buildVersionFormat?.let { enforceFieldEditable("jira.buildVersionFormat", it, base?.jiraBuildVersionFormat) }
            j.lineVersionFormat?.let { enforceFieldEditable("jira.lineVersionFormat", it, base?.jiraLineVersionFormat) }
            j.versionPrefix?.let { enforceFieldEditable("jira.versionPrefix", it, base?.jiraVersionPrefix) }
            j.versionFormat?.let { enforceFieldEditable("jira.versionFormat", it, base?.jiraVersionFormat) }
        }
        // Full build/escrow aspect-scalar set — must mirror every path applyBaseConfigurationPatch
        // actually writes (see ConfigurationRowAccessors.SCALAR_ATTRIBUTE_PATHS). Booleans gate by value.
        request.baseConfiguration?.build?.let { b ->
            b.buildSystem?.let { enforceFieldEditable("build.buildSystem", it, base?.buildSystem) }
            b.javaVersion?.let { enforceFieldEditable("build.javaVersion", it, base?.javaVersion) }
            b.gradleVersion?.let { enforceFieldEditable("build.gradleVersion", it, base?.gradleVersion) }
            b.mavenVersion?.let { enforceFieldEditable("build.mavenVersion", it, base?.mavenVersion) }
            b.buildFilePath?.let { enforceFieldEditable("build.buildFilePath", it, base?.buildFilePath) }
            b.deprecated?.let { enforceFieldEditable("build.deprecated", it, base?.deprecated) }
            b.requiredProject?.let { enforceFieldEditable("build.requiredProject", it, base?.requiredProject) }
            b.projectVersion?.let { enforceFieldEditable("build.projectVersion", it, base?.projectVersion) }
            b.systemProperties?.let { enforceFieldEditable("build.systemProperties", it, base?.systemProperties) }
            b.buildTasks?.let { enforceFieldEditable("build.buildTasks", it, base?.buildTasks) }
        }
        request.baseConfiguration?.escrow?.let { e ->
            e.providedDependencies?.let { enforceFieldEditable("escrow.providedDependencies", it, base?.escrowProvidedDependencies) }
            e.reusable?.let { enforceFieldEditable("escrow.reusable", it, base?.escrowReusable) }
            e.generation?.let { enforceFieldEditable("escrow.generation", it, base?.escrowGeneration) }
            e.diskSpace?.let { enforceFieldEditable("escrow.diskSpace", it, base?.escrowDiskSpace) }
            e.additionalSources?.let { enforceFieldEditable("escrow.additionalSources", it, base?.escrowAdditionalSources) }
            e.gradleIncludeConfigurations?.let {
                enforceFieldEditable("escrow.gradleIncludeConfigurations", it, base?.escrowGradleIncludeConfigurations)
            }
            e.gradleExcludeConfigurations?.let {
                enforceFieldEditable("escrow.gradleExcludeConfigurations", it, base?.escrowGradleExcludeConfigurations)
            }
            e.gradleIncludeTestConfigurations?.let {
                enforceFieldEditable("escrow.gradleIncludeTestConfigurations", it, base?.escrowGradleIncludeTestConfigurations)
            }
            e.buildTask?.let { enforceFieldEditable("escrow.buildTask", it, base?.escrowBuildTask) }
        }
    }

    /**
     * CRS-B: create-side editability — a supplied (non-null) value for a field the
     * caller may not edit is rejected (`current = null`, so any non-blank value is a
     * change); an absent field lets server defaults apply. Mirrors
     * [enforceEditabilityOnUpdate] for the fields the create payload carries.
     */
    private fun enforceEditabilityOnCreate(request: ComponentCreateRequest) {
        request.displayName?.let { enforceFieldEditable("component.displayName", it, null) }
        request.componentOwner?.let { enforceFieldEditable("component.componentOwner", it, null) }
        request.productType?.let { enforceFieldEditable("component.productType", it, null) }
        request.clientCode?.let { enforceFieldEditable("component.clientCode", it, null) }
        request.solution?.let { enforceFieldEditable("component.solution", it, null) }
        request.copyright?.let { enforceFieldEditable("component.copyright", it, null) }
        request.releasesInDefaultBranch?.let { enforceFieldEditable("component.releasesInDefaultBranch", it, null) }
        request.jiraDisplayName?.let { enforceFieldEditable("component.jiraDisplayName", it, null) }
        request.jiraHotfixVersionFormat?.let { enforceFieldEditable("component.jiraHotfixVersionFormat", it, null) }
        request.vcsExternalRegistry?.let { enforceFieldEditable("component.vcsExternalRegistry", it, null) }
        // skipCommitCheck defaults to false on create; only a supplied `true` is a change to gate.
        request.skipCommitCheck.takeIf { it }?.let { enforceFieldEditable("component.skipCommitCheck", it, null) }
        request.distributionExplicit?.let { enforceFieldEditable("component.distributionExplicit", it, null) }
        request.distributionExternal?.let { enforceFieldEditable("component.distributionExternal", it, null) }
        // Only a supplied NON-EMPTY set is a change on create (empty ≡ absent → server default).
        request.systems.takeIf { it.isNotEmpty() }?.let { enforceFieldEditable("component.system", it, null) }
        request.parentComponentName?.let { enforceFieldEditable("component.parentComponentName", it, null) }
        // canBeParent is a non-null boolean with a `false` default; only a supplied `true`
        // (a non-default value) counts as a supplied change on create.
        if (request.canBeParent) enforceFieldEditable("component.canBeParent", true, null)
        // People lists: only a supplied NON-EMPTY list is a change (empty ≡ absent → server default).
        request.releaseManager?.takeIf { it.isNotEmpty() }?.let { enforceFieldEditable("component.releaseManager", it, null) }
        request.securityChampion?.takeIf { it.isNotEmpty() }?.let { enforceFieldEditable("component.securityChampion", it, null) }

        request.baseConfiguration?.jira?.let { j ->
            j.projectKey?.let { enforceFieldEditable("jira.projectKey", it, null) }
            j.technical?.let { enforceFieldEditable("jira.technical", it, null) }
            j.minorVersionFormat?.let { enforceFieldEditable("jira.minorVersionFormat", it, null) }
            j.releaseVersionFormat?.let { enforceFieldEditable("jira.releaseVersionFormat", it, null) }
            j.buildVersionFormat?.let { enforceFieldEditable("jira.buildVersionFormat", it, null) }
            j.lineVersionFormat?.let { enforceFieldEditable("jira.lineVersionFormat", it, null) }
            j.versionPrefix?.let { enforceFieldEditable("jira.versionPrefix", it, null) }
            j.versionFormat?.let { enforceFieldEditable("jira.versionFormat", it, null) }
        }
        request.baseConfiguration?.build?.let { b ->
            // buildSystem is required on create; gate it too so a non-editable config is
            // enforced symmetrically with the PATCH path (no CREATE-only bypass).
            b.buildSystem?.let { enforceFieldEditable("build.buildSystem", it, null) }
            b.javaVersion?.let { enforceFieldEditable("build.javaVersion", it, null) }
            b.gradleVersion?.let { enforceFieldEditable("build.gradleVersion", it, null) }
            b.mavenVersion?.let { enforceFieldEditable("build.mavenVersion", it, null) }
            b.buildFilePath?.let { enforceFieldEditable("build.buildFilePath", it, null) }
            b.deprecated?.let { enforceFieldEditable("build.deprecated", it, null) }
            b.requiredProject?.let { enforceFieldEditable("build.requiredProject", it, null) }
            b.projectVersion?.let { enforceFieldEditable("build.projectVersion", it, null) }
            b.systemProperties?.let { enforceFieldEditable("build.systemProperties", it, null) }
            b.buildTasks?.let { enforceFieldEditable("build.buildTasks", it, null) }
        }
        request.baseConfiguration?.escrow?.let { e ->
            e.providedDependencies?.let { enforceFieldEditable("escrow.providedDependencies", it, null) }
            e.reusable?.let { enforceFieldEditable("escrow.reusable", it, null) }
            e.generation?.let { enforceFieldEditable("escrow.generation", it, null) }
            e.diskSpace?.let { enforceFieldEditable("escrow.diskSpace", it, null) }
            e.additionalSources?.let { enforceFieldEditable("escrow.additionalSources", it, null) }
            e.gradleIncludeConfigurations?.let { enforceFieldEditable("escrow.gradleIncludeConfigurations", it, null) }
            e.gradleExcludeConfigurations?.let { enforceFieldEditable("escrow.gradleExcludeConfigurations", it, null) }
            e.gradleIncludeTestConfigurations?.let { enforceFieldEditable("escrow.gradleIncludeTestConfigurations", it, null) }
            e.buildTask?.let { enforceFieldEditable("escrow.buildTask", it, null) }
        }
    }

    /**
     * CRS-C / Q13: `skipCommitCheck` is mutually exclusive with a WHISKEY effective BASE
     * build system. A real external registry only ever exists on WHISKEY components, so the
     * flag (a NOT_AVAILABLE stand-in) and WHISKEY can never coexist meaningfully.
     *
     * Validates the COMBINED post-write state (call AFTER the flag and the base build system
     * are both applied), so it catches every transition uniformly: create with both set,
     * PATCH flipping the flag true while WHISKEY, and PATCH switching buildSystem → WHISKEY
     * while the flag is already true. Effective BASE = the BASE configuration row's
     * `build_system` (per-range build overrides do not relax this component-level flag).
     */
    private fun validateSkipCommitCheckNotWhiskey(entity: ComponentEntity) {
        if (!entity.skipCommitCheck) return
        val baseBuildSystem = entity.configurations.firstOrNull { it.rowType == "BASE" }?.buildSystem
        if (baseBuildSystem.equals("WHISKEY", ignoreCase = true)) {
            throw ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "skipCommitCheck is not applicable when the effective BASE build system is WHISKEY",
            )
        }
    }

    /**
     * CRS-C: the legacy `NOT_AVAILABLE` sentinel must never be written to `vcs_external_registry`
     * via v4 — the storage invariant is "real registry names only"; skip-commit intent is expressed
     * through the dedicated `skipCommitCheck` flag. Reject an explicit sentinel (exact match, after
     * the CRS-A blank→null normalization) with 422 rather than silently folding it into the flag —
     * the Portal never sends the sentinel, so this only fires on a misbehaving client.
     */
    private fun rejectLegacyExternalRegistrySentinel(normalizedValue: String?) {
        if (normalizedValue == NOT_AVAILABLE_EXTERNAL_REGISTRY) {
            throw ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "vcsExternalRegistry must not be the legacy \"NOT_AVAILABLE\" sentinel; use skipCommitCheck instead",
            )
        }
    }

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
        // CRS-A: on create an incoming "" maps to NULL (treated as absent), for
        // consistency with the PATCH clear rule. `?.let(::clearBlankScalar)`
        // keeps null as null, maps "" → null, and sets non-blank verbatim.
        // CRS-B: an aspect field whose field-config visibility is `hidden` is SILENTLY
        // STRIPPED (its incoming value is ignored, not persisted and NOT a 4xx) — mirroring
        // the component-scalar isHidden gate. buildSystem is exempt: it is a required enum,
        // hiding it is unsupported. Editability (adminOnly/none) is enforced separately.
        request.build?.let { b ->
            // buildSystem is a required enum (validated) — not clearable, not hidden-stripped.
            // This hidden-strip exemption is enforced structurally at config-sync time:
            // ConfigSyncService.serializeFieldEntry rejects `build.buildSystem: {visibility: hidden}`
            // (ConfigValidationException), so a hidden buildSystem can never reach this writer.
            config.buildSystem = b.buildSystem
            if (!fieldConfigService.isHidden("build.javaVersion")) config.javaVersion = b.javaVersion?.let(::clearBlankScalar)
            if (!fieldConfigService.isHidden("build.mavenVersion")) config.mavenVersion = b.mavenVersion?.let(::clearBlankScalar)
            if (!fieldConfigService.isHidden("build.gradleVersion")) config.gradleVersion = b.gradleVersion?.let(::clearBlankScalar)
            if (!fieldConfigService.isHidden("build.buildFilePath")) config.buildFilePath = b.buildFilePath?.let(::clearBlankScalar)
            if (!fieldConfigService.isHidden("build.deprecated")) config.deprecated = b.deprecated
            if (!fieldConfigService.isHidden("build.requiredProject")) config.requiredProject = b.requiredProject
            if (!fieldConfigService.isHidden("build.projectVersion")) config.projectVersion = b.projectVersion?.let(::clearBlankScalar)
            if (!fieldConfigService.isHidden("build.systemProperties")) config.systemProperties = b.systemProperties?.let(::clearBlankScalar)
            if (!fieldConfigService.isHidden("build.buildTasks")) config.buildTasks = b.buildTasks?.let(::clearBlankScalar)
        }
        request.escrow?.let { e ->
            if (!fieldConfigService.isHidden("escrow.providedDependencies")) {
                config.escrowProvidedDependencies = e.providedDependencies?.let(::clearBlankScalar)
            }
            if (!fieldConfigService.isHidden("escrow.reusable")) config.escrowReusable = e.reusable
            if (!fieldConfigService.isHidden("escrow.generation")) config.escrowGeneration = e.generation
            if (!fieldConfigService.isHidden("escrow.diskSpace")) config.escrowDiskSpace = e.diskSpace?.let(::clearBlankScalar)
            if (!fieldConfigService.isHidden("escrow.additionalSources")) {
                config.escrowAdditionalSources = e.additionalSources?.let(::clearBlankScalar)
            }
            if (!fieldConfigService.isHidden("escrow.gradleIncludeConfigurations")) {
                config.escrowGradleIncludeConfigurations = e.gradleIncludeConfigurations?.let(::clearBlankScalar)
            }
            if (!fieldConfigService.isHidden("escrow.gradleExcludeConfigurations")) {
                config.escrowGradleExcludeConfigurations = e.gradleExcludeConfigurations?.let(::clearBlankScalar)
            }
            if (!fieldConfigService.isHidden("escrow.gradleIncludeTestConfigurations")) {
                config.escrowGradleIncludeTestConfigurations = e.gradleIncludeTestConfigurations
            }
            if (!fieldConfigService.isHidden("escrow.buildTask")) config.escrowBuildTask = e.buildTask?.let(::clearBlankScalar)
        }
        request.jira?.let { j ->
            if (!fieldConfigService.isHidden("jira.projectKey")) config.jiraProjectKey = j.projectKey?.let(::clearBlankScalar)
            if (!fieldConfigService.isHidden("jira.technical")) config.jiraTechnical = j.technical
            if (!fieldConfigService.isHidden("jira.minorVersionFormat")) {
                config.jiraMinorVersionFormat = j.minorVersionFormat?.let(::clearBlankScalar)
            }
            if (!fieldConfigService.isHidden("jira.releaseVersionFormat")) {
                config.jiraReleaseVersionFormat = j.releaseVersionFormat?.let(::clearBlankScalar)
            }
            if (!fieldConfigService.isHidden("jira.buildVersionFormat")) {
                config.jiraBuildVersionFormat = j.buildVersionFormat?.let(::clearBlankScalar)
            }
            if (!fieldConfigService.isHidden("jira.lineVersionFormat")) {
                config.jiraLineVersionFormat = j.lineVersionFormat?.let(::clearBlankScalar)
            }
            if (!fieldConfigService.isHidden("jira.versionPrefix")) config.jiraVersionPrefix = j.versionPrefix?.let(::clearBlankScalar)
            if (!fieldConfigService.isHidden("jira.versionFormat")) config.jiraVersionFormat = j.versionFormat?.let(::clearBlankScalar)
            // jiraHotfixVersionFormat per-range write is intentionally not
            // exposed via V4 (no UI need today); DSL import is the only
            // producer of the per-range column.
        }
        request.vcsEntries?.let { replaceVcsEntries(config, it) }
        request.mavenArtifacts?.let { replaceMavenArtifacts(config, it) }
        request.fileUrlArtifacts?.let { replaceFileUrlArtifacts(config, it) }
        request.dockerImages?.let { replaceDockerImages(config, it) }
        request.packages?.let { replacePackages(config, it) }
        request.buildToolBeans?.let { validateBuildToolBeans(it); replaceBuildToolBeans(config, it) }
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
        // CRS-A tri-state for free-text string scalars: null/absent = no-op (the
        // `?.let` skips it), "" = clear to NULL (clearBlankScalar), non-blank = set
        // verbatim. Booleans keep the plain `?.let` (no clear semantic). The two
        // enum-validated scalars are NOT clearable: `build.buildSystem` is required
        // and `escrow.generation` must be a known mode — both are validated
        // (validateBuildSystem / validateEscrowGenerationMode reject blank), so a
        // "" on those is a 400, not a clear. They keep set-only semantics.
        // CRS-B: hidden aspect fields are silently stripped (see applyBaseConfigurationCreate).
        // buildSystem is exempt (required enum). The `if (!isHidden)` sits inside each `?.let`
        // so an absent field stays a no-op and only a PRESENT value on a visible field is written.
        patch.versionRange?.let { config.versionRange = it }
        patch.build?.let { b ->
            // buildSystem exemption (required enum) is enforced structurally at config-sync time —
            // ConfigSyncService.serializeFieldEntry rejects `build.buildSystem: {visibility: hidden}`,
            // so no hidden-strip guard is needed (or wanted) on this writer.
            b.buildSystem?.let { config.buildSystem = it }
            b.javaVersion?.let { if (!fieldConfigService.isHidden("build.javaVersion")) config.javaVersion = clearBlankScalar(it) }
            b.mavenVersion?.let { if (!fieldConfigService.isHidden("build.mavenVersion")) config.mavenVersion = clearBlankScalar(it) }
            b.gradleVersion?.let { if (!fieldConfigService.isHidden("build.gradleVersion")) config.gradleVersion = clearBlankScalar(it) }
            b.buildFilePath?.let { if (!fieldConfigService.isHidden("build.buildFilePath")) config.buildFilePath = clearBlankScalar(it) }
            b.deprecated?.let { if (!fieldConfigService.isHidden("build.deprecated")) config.deprecated = it }
            b.requiredProject?.let { if (!fieldConfigService.isHidden("build.requiredProject")) config.requiredProject = it }
            b.projectVersion?.let { if (!fieldConfigService.isHidden("build.projectVersion")) config.projectVersion = clearBlankScalar(it) }
            b.systemProperties?.let {
                if (!fieldConfigService.isHidden("build.systemProperties")) config.systemProperties = clearBlankScalar(it)
            }
            b.buildTasks?.let { if (!fieldConfigService.isHidden("build.buildTasks")) config.buildTasks = clearBlankScalar(it) }
        }
        patch.escrow?.let { e ->
            e.providedDependencies?.let {
                if (!fieldConfigService.isHidden("escrow.providedDependencies")) config.escrowProvidedDependencies = clearBlankScalar(it)
            }
            e.reusable?.let { if (!fieldConfigService.isHidden("escrow.reusable")) config.escrowReusable = it }
            e.generation?.let { if (!fieldConfigService.isHidden("escrow.generation")) config.escrowGeneration = it }
            e.diskSpace?.let { if (!fieldConfigService.isHidden("escrow.diskSpace")) config.escrowDiskSpace = clearBlankScalar(it) }
            e.additionalSources?.let {
                if (!fieldConfigService.isHidden("escrow.additionalSources")) config.escrowAdditionalSources = clearBlankScalar(it)
            }
            e.gradleIncludeConfigurations?.let {
                if (!fieldConfigService.isHidden("escrow.gradleIncludeConfigurations")) {
                    config.escrowGradleIncludeConfigurations = clearBlankScalar(it)
                }
            }
            e.gradleExcludeConfigurations?.let {
                if (!fieldConfigService.isHidden("escrow.gradleExcludeConfigurations")) {
                    config.escrowGradleExcludeConfigurations = clearBlankScalar(it)
                }
            }
            e.gradleIncludeTestConfigurations?.let {
                if (!fieldConfigService.isHidden("escrow.gradleIncludeTestConfigurations")) config.escrowGradleIncludeTestConfigurations = it
            }
            e.buildTask?.let { if (!fieldConfigService.isHidden("escrow.buildTask")) config.escrowBuildTask = clearBlankScalar(it) }
        }
        patch.jira?.let { j ->
            j.projectKey?.let { if (!fieldConfigService.isHidden("jira.projectKey")) config.jiraProjectKey = clearBlankScalar(it) }
            j.technical?.let { if (!fieldConfigService.isHidden("jira.technical")) config.jiraTechnical = it }
            j.minorVersionFormat?.let {
                if (!fieldConfigService.isHidden("jira.minorVersionFormat")) config.jiraMinorVersionFormat = clearBlankScalar(it)
            }
            j.releaseVersionFormat?.let {
                if (!fieldConfigService.isHidden("jira.releaseVersionFormat")) config.jiraReleaseVersionFormat = clearBlankScalar(it)
            }
            j.buildVersionFormat?.let {
                if (!fieldConfigService.isHidden("jira.buildVersionFormat")) config.jiraBuildVersionFormat = clearBlankScalar(it)
            }
            j.lineVersionFormat?.let {
                if (!fieldConfigService.isHidden("jira.lineVersionFormat")) config.jiraLineVersionFormat = clearBlankScalar(it)
            }
            j.versionPrefix?.let { if (!fieldConfigService.isHidden("jira.versionPrefix")) config.jiraVersionPrefix = clearBlankScalar(it) }
            j.versionFormat?.let { if (!fieldConfigService.isHidden("jira.versionFormat")) config.jiraVersionFormat = clearBlankScalar(it) }
            // jiraHotfixVersionFormat per-range PATCH is intentionally not
            // exposed via V4; see applyBaseConfigurationCreate above.
        }
        patch.vcsEntries?.let { replaceVcsEntries(config, it) }
        patch.mavenArtifacts?.let { replaceMavenArtifacts(config, it) }
        patch.fileUrlArtifacts?.let { replaceFileUrlArtifacts(config, it) }
        patch.dockerImages?.let { replaceDockerImages(config, it) }
        patch.packages?.let { replacePackages(config, it) }
        patch.buildToolBeans?.let { validateBuildToolBeans(it); replaceBuildToolBeans(config, it) }
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
                    name = req.name ?: "main",
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
        artifacts.forEach { validateMavenArtifactCoordinate(it) }
        config.mavenArtifacts.clear()
        artifacts.forEachIndexed { index, req ->
            config.mavenArtifacts.add(
                DistributionMavenArtifactEntity(
                    componentConfiguration = config,
                    groupPattern = req.groupPattern,
                    artifactPattern = req.artifactPattern,
                    // Normalize blank → null, matching the import path
                    // (parseMavenGavEntry's `takeIf { isNotEmpty }`), so a v4-written
                    // "" and an import-written null compare equal in MavenGavCollision.
                    extension = req.extension?.takeIf { it.isNotBlank() },
                    classifier = req.classifier?.takeIf { it.isNotBlank() },
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

    private fun replaceBuildToolBeans(
        config: ComponentConfigurationEntity,
        beans: List<BuildToolBeanRequest>,
    ) {
        config.buildToolBeans.clear()
        beans.forEachIndexed { index, req ->
            config.buildToolBeans.add(
                ComponentBuildToolBeanEntity(
                    componentConfiguration = config,
                    beanType = req.beanType,
                    toolType = req.toolType,
                    settingsProperty = req.settingsProperty,
                    versionPattern = req.versionPattern,
                    edition = req.edition,
                    sortOrder = index,
                ),
            )
        }
    }

    private fun validateBuildToolBeans(beans: List<BuildToolBeanRequest>) {
        beans.forEach { bean ->
            // Build-tool per-field required (audit #19; old
            // EscrowConfigValidator.validateBuildConfigurationTools required each
            // tool's identifying fields). In v4 a build-tool is a typed bean whose
            // identifying field is `beanType`; require it to be specified. The
            // message is field-name-prefixed so the contract matches the other
            // cheap field checks (→ 400).
            require(bean.beanType.isNotBlank()) { "beanType is not specified for a buildToolBean" }
            require(bean.beanType in BEAN_TYPE_NAMES) {
                "Invalid beanType '${bean.beanType}'; must be one of $BEAN_TYPE_NAMES"
            }
            require(bean.edition == null || bean.beanType == "oracleDatabase") {
                "Field 'edition' is only valid for beanType 'oracleDatabase'; got beanType='${bean.beanType}'"
            }
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
            MarkerAttributes.BUILD_TOOLS -> {
                val beans = requireNotNull(payload.buildToolBeans) { "Marker '$markerName' requires buildToolBeans payload" }
                validateBuildToolBeans(beans)
                replaceBuildToolBeans(row, beans)
                null
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
                if (payload.buildToolBeans != null) add("buildToolBeans")
            }
        val expected =
            when (markerName) {
                MarkerAttributes.VCS_SETTINGS -> "vcsEntries"
                MarkerAttributes.DISTRIBUTION_MAVEN -> "mavenArtifacts"
                MarkerAttributes.DISTRIBUTION_FILE_URL -> "fileUrlArtifacts"
                MarkerAttributes.DISTRIBUTION_DOCKER -> "dockerImages"
                MarkerAttributes.DISTRIBUTION_PACKAGES -> "packages"
                MarkerAttributes.BUILD_REQUIRED_TOOLS -> "requiredTools"
                MarkerAttributes.BUILD_TOOLS -> "buildToolBeans"
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
     * Resolve a component by UUID-or-name, mirroring `ComponentControllerV4.getComponent`:
     * try the UUID path when the token parses as one, fall back to a name lookup
     * (a name that happens to parse as a UUID but has no id match still resolves
     * by name). Infra errors from `findById` propagate; only a missing row falls
     * through. Throws `NotFoundException` when neither matches.
     */
    private fun findByIdOrName(idOrName: String): ComponentEntity {
        val asUuid = runCatching { UUID.fromString(idOrName) }.getOrNull()
        if (asUuid != null) {
            componentRepository.findById(asUuid).orElse(null)?.let { return it }
        }
        return componentRepository.findByComponentKey(idOrName)
            ?: throw NotFoundException("Component '$idOrName' not found")
    }

    /**
     * Parse `range` via the shared `VersionRangeFactory`; throws
     * [IllegalArgumentException] if the syntax is invalid.
     *
     * Used on BASE-row paths where universal `(,0),[0,)` is the sentinel and
     * sibling-overlap detection does not apply. Field-override write paths
     * call [validateFieldOverrideRange] instead, which additionally enforces
     * the partial-overlap and semantic-equality rules of schema-spec §3.5.
     * D5 (closed-range only) is NOT enforced server-side yet — the Portal
     * blocks open-upward input client-side; see [validateFieldOverrideRange]
     * for the full deferred-D5 rationale.
     */
    private fun validateRangeSyntax(range: String) {
        try {
            versionRangeFactory.create(range)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid version range: '$range'", e)
        }
    }

    // ─── Field-override range validation (disjoint-only, matches Portal) ─────────
    //
    // The Portal applies the same disjoint-only rule client-side via
    // `versionRange.ts` (`rangesOverlap` / `classifyRangeConflict`): two
    // overrides on one attribute conflict if they intersect at all — partial
    // overlap, strict containment, or semantic equality. Keeping the two
    // implementations in sync is the explicit goal — a client-side `false`
    // should not surprise the user with a server 400 and vice versa.
    //
    // D5 (closed-range only) is enforced by the Portal at input but is NOT
    // yet mirrored here; see the validateFieldOverrideRange KDoc.

    private val FIELD_OVERRIDE_ROW_TYPES = setOf("SCALAR_OVERRIDE", "MARKER")

    // Anchored regex matches only single-segment ranges (no top-level comma
    // between segments), so composites short-circuit via the regex-mismatch
    // path inside parseSimpleSegment without a separate composite-detector.
    private val SIMPLE_SEGMENT_PATTERN = Regex("^([\\[(])([^,]*),([^,]*)([\\])])$")

    private data class ParsedSimpleRange(
        val lo: String?,
        val loIncl: Boolean,
        val hi: String?,
        val hiIncl: Boolean,
    )

    private fun normalizeRange(range: String): String =
        range.trim().replace(Regex("\\s+"), "")

    private fun parseSimpleSegment(range: String): ParsedSimpleRange? {
        val compact = normalizeRange(range)
        val m = SIMPLE_SEGMENT_PATTERN.matchEntire(compact) ?: return null
        val (open, loStr, hiStr, close) = m.destructured
        if (loStr.any { it in "()[]" } || hiStr.any { it in "()[]" }) return null
        return ParsedSimpleRange(
            lo = loStr.trim().takeIf { it.isNotEmpty() },
            loIncl = open == "[",
            hi = hiStr.trim().takeIf { it.isNotEmpty() },
            hiIncl = close == "]",
        )
    }

    private fun compareMavenVersions(a: String, b: String): Int =
        DefaultArtifactVersion(a).compareTo(DefaultArtifactVersion(b))

    /**
     * Stable display ordering for supported-coverage ranges: by lower bound (open-lower / composite
     * ranges, whose simple floor is null, sort first via the "0" fallback), then by raw string.
     */
    private val SUPPORTED_RANGE_ORDER: Comparator<String> =
        compareBy<String>(
            { parseSimpleSegment(it)?.lo?.let(::DefaultArtifactVersion) ?: DefaultArtifactVersion("0") },
            { it },
        )

    private fun simpleContains(outer: ParsedSimpleRange, inner: ParsedSimpleRange): Boolean {
        val leftOK = when {
            outer.lo == null -> true
            inner.lo == null -> false
            else -> {
                val cmp = compareMavenVersions(outer.lo, inner.lo)
                cmp < 0 || (cmp == 0 && (outer.loIncl || !inner.loIncl))
            }
        }
        if (!leftOK) return false
        return when {
            outer.hi == null -> true
            inner.hi == null -> false
            else -> {
                val cmp = compareMavenVersions(outer.hi, inner.hi)
                cmp > 0 || (cmp == 0 && (outer.hiIncl || !inner.hiIncl))
            }
        }
    }

    /**
     * Enforces the field-override range contract for write paths:
     *
     * 1. Syntax — must parse via [VersionRangeFactory.create].
     * 2. The submitted range must be a single segment. Composite Maven
     *    ranges (e.g. `(,1.0),[2.0,3.0)`) are rejected on POST / PATCH:
     *    composite-vs-anything containment cannot be decided without a
     *    Maven range-algebra primitive the releng library does not expose,
     *    so we'd be forced to either over-reject (false positives on
     *    strict containment) or over-allow (false negatives on partial
     *    overlap). Users compose multiple separate overrides instead.
     *    Existing composite rows (legacy DSL import) are untouched — the
     *    sibling walk below uses `isIntersect` on them but treats them as
     *    opaque when measuring containment.
     * 3. Any intersection with a sibling override on the same attribute is
     *    rejected — field-override ranges must be DISJOINT. Partial overlap
     *    AND strict containment are both conflicts: a version inside the
     *    intersection would match both overrides, so the resolved value would
     *    be ambiguous. (This is stricter than the original schema-spec §3.5,
     *    which allowed strict containment; see also Portal #67.)
     * 4. Semantic equality (each contains the other after Maven-comparator
     *    normalisation) is also a conflict but is reported with a distinct
     *    "duplicate" message, even when the raw strings differ by whitespace
     *    or trailing zeros (`[1.0, 2.0)` vs `[1.0,2.0)` or `[1,2)` vs
     *    `[1.0,2.0)`). The DB UNIQUE constraint catches only exact-string
     *    duplicates.
     *
     * D5 (closed-range only) is NOT enforced here — the Portal blocks
     * open-upward input client-side; server-side D5 is tracked as a follow-up
     * because several existing API tests seed throwaway components with
     * `[X,)` overrides and would break under a strict server rejection.
     *
     * Intersection is detected via [VersionRange.isIntersect], which handles
     * legacy composite SIBLING rows correctly. Because the disjoint-only rule
     * rejects on ANY intersection, we no longer need to classify partial vs
     * containment for composite siblings — so a simple new range intersecting
     * a composite sibling is now rejected too (closing the former composite-
     * vs-simple gap). Composite NEW ranges are still rejected up front
     * (point 2); users compose multiple single-segment overrides instead.
     */
    private fun validateFieldOverrideRange(
        range: String,
        component: ComponentEntity,
        attribute: String,
        excludeOverrideId: UUID?,
    ) {
        validateRangeSyntax(range)
        val parsedNew = parseSimpleSegment(range)
        require(parsedNew != null) {
            "Field-override range '$range' must be a single Maven segment " +
                "(e.g. [1.0,2.0)). Composite ranges are not accepted on POST / " +
                "PATCH — split the override into multiple rows, one per segment."
        }
        val newRangeObj = versionRangeFactory.create(range)
        for (row in component.configurations) {
            if (row.id != null && row.id == excludeOverrideId) continue
            if (row.overriddenAttribute != attribute) continue
            if (row.rowType !in FIELD_OVERRIDE_ROW_TYPES) continue
            val existingRangeObj = try {
                versionRangeFactory.create(row.versionRange)
            } catch (_: Exception) {
                continue
            }
            if (!newRangeObj.isIntersect(existingRangeObj)) continue
            // They intersect → conflict. Field-override ranges on one attribute
            // must be DISJOINT, so partial overlap, strict containment, AND
            // semantic equality are all rejected (a version in the intersection
            // would match both overrides — ambiguous). We only distinguish the
            // semantic-equal case for a clearer "duplicate" message. If the
            // SIBLING is a legacy composite (`parsedExisting` null) we cannot
            // classify it precisely, but `isIntersect` returning true is already
            // sufficient grounds to reject.
            //
            // `parsedNew` is non-null (composites are rejected up-front above).
            val parsedExisting = parseSimpleSegment(row.versionRange)
            if (parsedExisting != null &&
                simpleContains(parsedNew, parsedExisting) &&
                simpleContains(parsedExisting, parsedNew)
            ) {
                throw IllegalArgumentException(
                    "Range '$range' is semantically equal to existing " +
                        "override range '${row.versionRange}' on attribute " +
                        "'$attribute'. Each version range can only have one " +
                        "override per attribute (schema-spec §3.5 / UNIQUE).",
                )
            }
            // V2 (ADR-018 §6): at most one open-upper override per attribute. Two open-upper
            // ranges (`[X,)`) always overlap (both run to +∞), so this is a special case of the
            // disjointness rule below — surfaced with a clearer, actionable message.
            if (parsedExisting != null && parsedNew.hi == null && parsedExisting.hi == null) {
                throw IllegalArgumentException(
                    "Range '$range' and existing override range '${row.versionRange}' on attribute " +
                        "'$attribute' are both open-upper (run to +∞) and therefore always overlap. " +
                        "At most one open-upper override is allowed per attribute (V2) — bound one of " +
                        "them (e.g. close the earlier range at the later range's floor).",
                )
            }
            // Reached for partial overlap, strict containment, and (composite
            // sibling) intersections — for composite siblings the semantic-equal
            // message above is unreachable (parsedExisting is null), and this
            // generic reject is the correct outcome anyway.
            throw IllegalArgumentException(
                "Range '$range' overlaps existing override range " +
                    "'${row.versionRange}' on attribute '$attribute'. " +
                    "Field-override ranges on one attribute must be disjoint — " +
                    "partial overlap and strict containment are both rejected " +
                    "(a version in the intersection would match both overrides).",
            )
        }
    }

    // ─── Cross-component integrity (Stage 4 — restores OLD-VALIDATOR composite
    //     checks as runtime rules). Collisions with OTHER components → 409
    //     (CrossComponentConflictException); malformed-input → 400
    //     (IllegalArgumentException via require). ───────────────────────────────

    /**
     * Malformed-input single-/composite-field rules that reject the SUBMITTED
     * payload itself (not a clash with another component) — all 400.
     *
     *  - **explicit-external ≥1 distribution coordinate** (#6): when
     *    `distributionExplicit && distributionExternal`, at least one of GAV
     *    (maven artifact), docker image, or DEB/RPM package must be defined on
     *    some configuration row.
     *  - **groupId supported prefix** (#10): every maven `groupPattern` element
     *    must start with one of the env-configured `supportedGroupIds`.
     *  - **archived ≠ explicit-external** (#28): an archived component cannot be
     *    explicitly+externally distributed.
     * Note: doc-component existence (#20) is NOT checked here. It is a soft
     * string reference whose target may be the component's OWN key (a component
     * may legitimately document itself), which does not exist in the DB until
     * after the flush on create. It is therefore validated post-flush in
     * [validateDocComponentExistence] alongside the 409 cross-component checks,
     * where the component's own key is already persisted.
     *
     * Runs against the final entity state (post-patch / freshly-built on create).
     */
    private fun validateMalformedFieldRules(entity: ComponentEntity) {
        val explicitExternal =
            (entity.distributionExplicit == true) && (entity.distributionExternal == true)

        // #28 archived ≠ explicit-external — checked before the ≥1-coordinate
        // rule so an archived component is never asked for a coordinate.
        if (explicitExternal) {
            require(!entity.archived) {
                "distribution: an archived component can't be explicitly+externally " +
                    "distributed — set distributionExplicit=false (component '${entity.componentKey}')"
            }
            require(hasAnyDistributionCoordinate(entity)) {
                "distribution: an explicit+external component must define at least one " +
                    "distribution coordinate (maven GAV, docker image, or package) " +
                    "(component '${entity.componentKey}')"
            }
        }

        // #10 groupId supported prefix — applies to every maven artifact regardless
        // of the distribution gate (matches the old per-config validateGroupId).
        validateGroupIdPrefixes(entity)
    }

    /**
     * #20 doc-component existence (soft string ref, no FK) — 400. Runs POST-flush
     * so a component that documents ITSELF (a `docs[]` entry pointing at its own
     * key) passes: on create the component's own row does not exist until the
     * flush, so a pre-flush check would raise a false 400 for the self-reference.
     * The component's own key is excluded explicitly as well, so the order of
     * persistence can never make a self-reference fail.
     */
    private fun validateDocComponentExistence(entity: ComponentEntity) {
        val referencedKeys =
            entity.docLinks
                .map { it.docComponentKey }
                .filterNot { it == entity.componentKey }
                .toSet()
        if (referencedKeys.isEmpty()) return

        val existingKeys =
            componentRepository.findByComponentKeyIn(referencedKeys)
                .mapTo(HashSet()) { it.componentKey }
        val missingKey =
            entity.docLinks
                .asSequence()
                .map { it.docComponentKey }
                .firstOrNull { it != entity.componentKey && it !in existingKeys }
        require(missingKey == null) {
            "docs: referenced doc component '$missingKey' does not exist " +
                "(component '${entity.componentKey}')"
        }
    }

    private fun hasAnyDistributionCoordinate(entity: ComponentEntity): Boolean =
        entity.configurations.any { cfg ->
            cfg.mavenArtifacts.isNotEmpty() ||
                cfg.dockerImages.isNotEmpty() ||
                cfg.packages.isNotEmpty()
        }

    /**
     * #10 — every comma/pipe-separated element of every maven `groupPattern`
     * must start with a configured supported prefix. The supported list is
     * env-config-driven (`components-registry.supportedGroupIds`, read via the
     * shared `ConfigHelper`, the same source `CommonControllerV2` uses). If the
     * list is empty (mis-configured env) the check is skipped with a WARN rather
     * than rejecting every write.
     */
    private fun validateGroupIdPrefixes(entity: ComponentEntity) {
        val supported = runCatching { configHelper.supportedGroupIds() }.getOrDefault(emptyList())
        if (supported.isEmpty()) {
            log.warn(
                "supportedGroupIds is empty/unavailable; skipping groupId-prefix validation for '{}'",
                entity.componentKey,
            )
            return
        }
        entity.configurations.forEach { cfg ->
            cfg.mavenArtifacts.forEach { artifact ->
                artifact.groupPattern.split(GROUP_ID_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
                    .forEach { groupId ->
                        require(supported.any { groupId.startsWith(it) }) {
                            "groupId: '$groupId' does not start with a supported prefix " +
                                "(${supported.joinToString(", ")}) (component '${entity.componentKey}')"
                        }
                    }
            }
        }
    }

    /**
     * Cross-component collisions with OTHER components → 409. All three rules use
     * self-excluding indexed queries against the flushed final state:
     *
     *  - **duplicate maven artifact rows in overlapping ranges** — for each of this
     *    component's maven artifact rows (explicit distribution GAVs AND the
     *    group-artifact-pattern mapping rows), find other components whose rows
     *    share the FULL identity (group, artifact, extension, classifier) under
     *    the union of legacy rule #25 pattern containment and rule #24 exact
     *    token-pair sharing (see `MavenGavCollision`), and reject if any owning
     *    range intersects this component's range (Maven `isIntersect`, decided
     *    in-memory). Differing extension or classifier (`g:a:zip` vs `g:a:apk`)
     *    is NOT a collision; legacy has NO distribution-GAV uniqueness of its own
     *    — the 4-attribute identity is the v3-added strictness on explicit GAVs.
     *  - **#26 jira (projectKey, versionPrefix) uniqueness among non-archived** —
     *    an archived component is exempt (matches the old `!archived` filter).
     *  - **#29 docker image-name global uniqueness** — any other component using
     *    the same image name is a conflict.
     */
    private fun validateCrossComponentIntegrity(entity: ComponentEntity) {
        val componentId = entity.id ?: return
        validateMavenArtifactCollisions(entity, componentId)
        validateJiraProjectKeyVersionPrefixUniqueness(entity)
        validateDockerImageUniqueness(entity, componentId)
    }

    /**
     * Ownership uniqueness runs only where the ownership mappings can CHANGE — the create and
     * update (artifactIds) paths — NOT on the field-override revalidation (a field-override cannot
     * touch ownership, and re-checking there would 409 on pre-existing data the override doesn't own).
     */
    private fun validateArtifactOwnershipIfChanged(entity: ComponentEntity) {
        val componentId = entity.id ?: return
        validateArtifactOwnershipCollisions(entity, componentId)
    }

    /**
     * #357 cross-component groupId/artifactId OWNERSHIP uniqueness (mode-aware matrix over the
     * `component_artifact_mappings` of this component vs every other component). Restores legacy
     * #24/#25 deterministically from stored modes. Throws 409 `CrossComponentConflictException`.
     */
    private fun validateArtifactOwnershipCollisions(entity: ComponentEntity, componentId: UUID) {
        val ownClaims = entity.artifactMappings.map { it.toOwnershipClaim(entity.componentKey) }
        if (ownClaims.isEmpty()) return
        val rivalRows = componentArtifactMappingRepository.findOtherComponents(componentId)
        val explicitTokensByMapping =
            rivalRows.filter { it.artifactIdMode == ArtifactIdMode.EXPLICIT.name }
                .map { it.mappingId }
                .takeIf { it.isNotEmpty() }
                ?.let { ids -> componentArtifactMappingTokenRepository.findTokensByMappingIdIn(ids) }
                ?.groupBy({ it.mappingId }, { it.artifactPattern })
                .orEmpty()
        val rivalClaims =
            rivalRows.map { row ->
                OwnershipClaim(
                    componentKey = row.componentKey,
                    versionRange = row.versionRange,
                    groupTokens = groupTokensOf(row.groupPattern),
                    mode = ArtifactIdMode.valueOf(row.artifactIdMode),
                    tokens = explicitTokensByMapping[row.mappingId]?.toSet().orEmpty(),
                )
            }
        // Each component's own override range strings → a base (ALL_VERSIONS) claim is shadowed
        // (replaced) there and must not conflict. Derived from the already-loaded mappings/rows;
        // no extra query. Applied symmetrically to the edited component and every rival.
        val shadowRanges =
            buildMap<String, Set<String>> {
                put(
                    entity.componentKey,
                    entity.artifactMappings.map { it.versionRange }.filterTo(mutableSetOf()) { it != OWNERSHIP_ALL_VERSIONS },
                )
                rivalRows.groupBy { it.componentKey }.forEach { (key, rows) ->
                    put(key, rows.map { it.versionRange }.filterTo(mutableSetOf()) { it != OWNERSHIP_ALL_VERSIONS })
                }
            }
        val intersect: (String, String) -> Boolean = { a, b ->
            runCatching { versionRangeFactory.create(a).isIntersect(versionRangeFactory.create(b)) }.getOrDefault(true)
        }
        val violations = computeOwnershipCollisions(ownClaims, rivalClaims, intersect, shadowRanges)
        if (violations.isNotEmpty()) throw CrossComponentConflictException(violations.first())
    }

    /** In-memory ownership entity → [OwnershipClaim] (EXPLICIT tokens are already on the loaded entity). */
    private fun ComponentArtifactMappingEntity.toOwnershipClaim(componentKey: String): OwnershipClaim =
        OwnershipClaim(
            componentKey = componentKey,
            versionRange = this.versionRange,
            groupTokens = groupTokensOf(this.groupPattern),
            mode = ArtifactIdMode.valueOf(this.artifactIdMode),
            tokens = this.tokens.map { it.artifactPattern }.toSet(),
        )

    /**
     * DSL/code-export + UI-preview `artifactIdPattern` per ownership mapping id, sibling-aware for
     * ALL_EXCEPT_CLAIMED (a negative-lookahead over the EXPLICIT tokens claimed under the same group
     * in an intersecting range, across ALL components). ALL/EXPLICIT need no siblings and fall back
     * to the wire render at the renderer, so this map carries ONLY the ALL_EXCEPT_CLAIMED entries —
     * and is empty (no cross-component query) when the component has none, keeping the common path free.
     */
    private fun ownershipExportPatterns(entity: ComponentEntity): Map<UUID, String> {
        val allExcept =
            entity.artifactMappings.filter { it.artifactIdMode == ArtifactIdMode.ALL_EXCEPT_CLAIMED.name }
        if (allExcept.isEmpty()) return emptyMap()
        val allRows = componentArtifactMappingRepository.findAllRows()
        val explicitRows = allRows.filter { it.artifactIdMode == ArtifactIdMode.EXPLICIT.name }
        // Each component's own override (non-base) ranges. A rival's BASE EXPLICIT is shadowed (not in
        // force) in a range that rival itself overrides (override REPLACES base), so it must not be
        // excluded by this ALL_EXCEPT there — mirrors the shadow skip in [computeOwnershipCollisions].
        val shadowRangesByComponent =
            allRows
                .groupBy { it.componentKey }
                .mapValues { (_, rows) -> rows.map { it.versionRange }.filterTo(mutableSetOf()) { it != OWNERSHIP_ALL_VERSIONS } }
        val tokensByMapping =
            explicitRows.map { it.mappingId }.takeIf { it.isNotEmpty() }
                ?.let { ids -> componentArtifactMappingTokenRepository.findTokensByMappingIdIn(ids) }
                ?.groupBy({ it.mappingId }, { it.artifactPattern })
                .orEmpty()
        val intersect: (String, String) -> Boolean = { a, b ->
            runCatching { versionRangeFactory.create(a).isIntersect(versionRangeFactory.create(b)) }.getOrDefault(true)
        }
        return allExcept.mapNotNull { m ->
            val id = m.id ?: return@mapNotNull null
            val groups = groupTokensOf(m.groupPattern)
            val siblings =
                explicitRows
                    // OTHER components only: ALL_EXCEPT_CLAIMED yields to OTHER components' EXPLICIT
                    // claims. This component's own EXPLICIT mappings live in disjoint ranges that
                    // REPLACE (not coexist with) this base mapping, so they must not narrow it.
                    .filter { it.componentKey != entity.componentKey }
                    .filter { row -> groupTokensOf(row.groupPattern).any { it in groups } }
                    .filter { intersect(it.versionRange, m.versionRange) }
                    .filterNot { row ->
                        row.versionRange == OWNERSHIP_ALL_VERSIONS &&
                            m.versionRange in shadowRangesByComponent[row.componentKey].orEmpty()
                    }
                    .flatMap { tokensByMapping[it.mappingId].orEmpty() }
                    .distinct()
                    .sorted()
            id to
                org.octopusden.octopus.components.registry.server.util.ArtifactOwnershipRendering
                    .renderExportPattern(ArtifactIdMode.ALL_EXCEPT_CLAIMED, emptyList(), siblings)
        }.toMap()
    }

    /**
     * [toDetailResponse] + sibling-aware `legacyArtifactIdPattern` enrichment for any
     * ALL_EXCEPT_CLAIMED ownership mapping (the wire mapper renders only the plain catch-all; the UI
     * preview needs the negative-lookahead, which depends on OTHER components' claims).
     */
    private fun toDetail(entity: ComponentEntity): ComponentDetailResponse {
        val response = entity.toDetailResponse(teamcityProperties.baseUrl)
        val patterns = ownershipExportPatterns(entity)
        if (patterns.isEmpty()) return response
        return response.copy(
            artifactIds =
                response.artifactIds.map { ai ->
                    patterns[ai.id]?.let { ai.copy(legacyArtifactIdPattern = it) } ?: ai
                },
        )
    }

    /**
     * Review #2: a field-override write can introduce a `mavenArtifacts` /
     * `dockerImages` / jira coordinate on an OVERRIDE row that another component
     * already claims. The create/update component paths run the cross-component
     * checks across ALL configuration rows (base + overrides), but the
     * field-override sub-resource wrote a row WITHOUT re-running them — so a
     * collision could slip in via an override. After the override row is written
     * and flushed (via `bumpParentVersion`), reload the owning component and
     * re-run the SAME composite checks against its full (now-persisted)
     * configuration set:
     *
     *  - [validateMalformedFieldRules] — the #10 groupId-prefix rule applies to a
     *    GAV introduced on an override row too (the explicit+external / archived
     *    rules read component scalars, which an override does not change, so they
     *    are inert here but harmless to re-check).
     *  - [validateCrossComponentIntegrity] — the #24/#25 maven and #29 docker
     *    collisions, plus #26 jira uniqueness for a jira-scalar override.
     *
     * The owning component already carries an id (the override targets an existing
     * component), so the self-excluding queries work unchanged. A conflict throws
     * inside the @Transactional method, rolling back the override write — same
     * shape as the create/update 409 path. Doc-existence (#20) is component-level,
     * not configuration-level, so it is intentionally NOT re-run here.
     */
    private fun validateFieldOverrideCrossComponent(componentId: UUID) {
        val reloaded = findComponentOr404(componentId)
        validateMalformedFieldRules(reloaded)
        validateCrossComponentIntegrity(reloaded)
    }

    private fun validateMavenArtifactCollisions(entity: ComponentEntity, componentId: UUID) {
        val otherArtifacts = mavenArtifactRepository.findOtherComponents(componentId)
        entity.configurations.forEach { cfg ->
            val ownRange = runCatching { versionRangeFactory.create(cfg.versionRange) }.getOrNull()
            cfg.mavenArtifacts.forEach { artifact ->
                otherArtifacts.forEach { other ->
                    // Identity is the FULL coordinate (group, artifact, extension,
                    // classifier) — `g:a:zip` vs `g:a:apk` are distinct artifacts.
                    if (MavenGavCollision.identityCollides(
                            artifact.groupPattern, artifact.artifactPattern,
                            artifact.extension, artifact.classifier,
                            other.groupPattern, other.artifactPattern,
                            other.extension, other.classifier,
                        )
                    ) {
                        val intersects =
                            if (ownRange == null) {
                                true // unparseable own range — be conservative and treat as overlapping
                            } else {
                                runCatching {
                                    ownRange.isIntersect(versionRangeFactory.create(other.versionRange))
                                }.getOrDefault(true)
                            }
                        if (intersects) {
                            val gav = MavenGavCollision.gavLabel(
                                artifact.groupPattern, artifact.artifactPattern,
                                artifact.extension, artifact.classifier,
                            )
                            // Name the REAL source: group-artifact-pattern marker rows come from
                            // the component-level groupId/artifactId mapping, not a distribution{}
                            // block — "distribution GAV" sent operators hunting for a section that
                            // does not exist in the DSL.
                            val ownOrigin = MavenGavCollision.originLabel(cfg.overriddenAttribute)
                            val rivalOrigin = MavenGavCollision.originLabel(other.overriddenAttribute)
                            throw CrossComponentConflictException(
                                "uniqueness violation: $ownOrigin '$gav' of component " +
                                    "'${entity.componentKey}' duplicates the $rivalOrigin of component " +
                                    "'${other.componentKey}' " +
                                    "in intersecting version ranges '${cfg.versionRange}' ∩ '${other.versionRange}'",
                            )
                        }
                    }
                }
            }
        }
    }

    private fun validateJiraProjectKeyVersionPrefixUniqueness(entity: ComponentEntity) {
        // An archived component does not claim a jira (projectKey, versionPrefix)
        // bucket — mirrors the old `!moduleConfiguration.archived` filter.
        if (entity.archived) return
        // EFFECTIVE per-range claims, not raw rows: a projectKey-only override row
        // carries a NULL prefix meaning "inherited from base" — bucketing it as
        // (key, null) falsely conflicted with components that legitimately own the
        // no-prefix bucket (a real prod shape). See computeEffectiveJiraPairs.
        val ownRows =
            entity.configurations.map { cfg ->
                JiraRowView(
                    entity.componentKey, cfg.versionRange, cfg.rowType,
                    cfg.overriddenAttribute, cfg.jiraProjectKey, cfg.jiraVersionPrefix,
                )
            }
        val ownPairs = computeEffectiveJiraPairs(ownRows)[entity.componentKey].orEmpty()
        if (ownPairs.isEmpty()) return
        val rivalRows =
            configurationRepository.findAllNonArchivedJiraRows()
                .filter { it.componentKey != entity.componentKey }
                .map {
                    JiraRowView(
                        it.componentKey, it.versionRange, it.rowType,
                        it.overriddenAttribute, it.projectKey, it.versionPrefix,
                    )
                }
        val rivalsByPair = mutableMapOf<Pair<String, String?>, MutableSet<String>>()
        computeEffectiveJiraPairs(rivalRows).forEach { (rivalKey, rivalPairs) ->
            rivalPairs.forEach { pair -> rivalsByPair.getOrPut(pair) { mutableSetOf() }.add(rivalKey) }
        }
        ownPairs.forEach { pair ->
            val others = rivalsByPair[pair].orEmpty()
            if (others.isNotEmpty()) {
                val (projectKey, versionPrefix) = pair
                val prefixText =
                    if (versionPrefix == null) "no version prefix" else "version prefix '$versionPrefix'"
                throw CrossComponentConflictException(
                    "uniqueness violation: jira project '$projectKey' with $prefixText is already used by " +
                        "non-archived component(s) ${others.sorted().joinToString(", ")} " +
                        "(conflicts with '${entity.componentKey}')",
                )
            }
        }
    }

    private fun validateDockerImageUniqueness(entity: ComponentEntity, componentId: UUID) {
        val imageNames =
            entity.configurations
                .flatMap { it.dockerImages }
                .map { it.imageName }
                .filter { it.isNotBlank() }
                .toSet()
        imageNames.forEach { imageName ->
            val others = dockerImageRepository.findOtherComponentKeysByImageName(imageName, componentId)
            if (others.isNotEmpty()) {
                throw CrossComponentConflictException(
                    "uniqueness violation: docker image name '$imageName' of component " +
                        "'${entity.componentKey}' is already used by component(s) " +
                        "${others.joinToString(", ")} — image names must be globally unique",
                )
            }
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
        require(value in PRODUCT_TYPE_NAMES) {
            "Invalid productType: '$value'. Allowed: $PRODUCT_TYPE_NAMES"
        }
    }

    /**
     * Person-field validation (audit #1/#3/#4/#7), restored on the v4 write
     * path. Delegates the rules to [PersonFieldValidator] so they stay unit-
     * testable in isolation; this method just supplies the FINAL entity state
     * and the active-check trigger.
     *
     * Inputs are read off [entity] AFTER the patch/create has been applied
     * (so PATCH callers needn't resend unchanged fields, matching the old
     * "validate whole config" model). [runActiveCheck] is true when a person
     * field changed OR the distribution gate flipped (per building-block #4):
     * required/pattern run unconditionally; the active-employee call runs only
     * when triggered, the final component is non-archived, AND the
     * employee-service bean is present.
     */
    private fun validatePersonFields(
        entity: ComponentEntity,
        runActiveCheck: Boolean,
    ) {
        PersonFieldValidator.validate(
            owner = entity.componentOwner,
            releaseManagers = entity.releaseManagerUsernames(),
            securityChampions = entity.securityChampionUsernames(),
            explicit = entity.distributionExplicit,
            external = entity.distributionExternal,
            // Active check only meaningful when a client bean is wired; the
            // validator itself also fail-opens on DISABLED, but skipping the
            // per-username calls when the directory is off avoids needless work.
            runActiveCheck = runActiveCheck && !entity.archived && employeeDirectory.isEnabled(),
            isHidden = fieldConfigService::isHidden,
            directory = employeeDirectory,
        )
    }

    private fun validateBuildSystem(value: String) {
        require(value.isNotBlank()) { "build.buildSystem must not be blank" }
        require(value in BUILD_SYSTEM_NAMES) {
            "Invalid build.buildSystem: '$value'. Allowed: $BUILD_SYSTEM_NAMES"
        }
    }

    private fun validateEscrowGenerationMode(value: String) {
        require(value.isNotBlank()) { "escrow.generation must not be blank" }
        require(value in ESCROW_GENERATION_MODE_NAMES) {
            "Invalid escrow.generation: '$value'. Allowed: $ESCROW_GENERATION_MODE_NAMES"
        }
    }

    private fun validateRepositoryType(value: String) {
        require(value.isNotBlank()) { "vcsEntry.repositoryType must not be blank" }
        require(value in REPOSITORY_TYPE_NAMES) {
            "Invalid vcsEntry.repositoryType: '$value'. Allowed: $REPOSITORY_TYPE_NAMES"
        }
    }

    private fun validatePackageType(value: String) {
        require(value.isNotBlank()) { "package.packageType must not be blank" }
        require(value in PACKAGE_TYPE_NAMES) {
            "Invalid package.packageType: '$value'. Allowed: $PACKAGE_TYPE_NAMES"
        }
    }

    /**
     * A distribution Maven coordinate must carry BOTH a groupPattern and an
     * artifactPattern — the structured-DTO equivalent of the import path's
     * "group:artifact" requirement (parseMavenGavEntry rejects < 2 segments).
     * A blank field was previously copied verbatim (201), silently persisting a
     * broken coordinate — symmetric with the import silent-drop. A groupId-only
     * coordinate (blank artifactPattern) is not supported; see TD-011 / #349
     * (if groupId-only support is ever added, relax the artifactPattern check).
     */
    private fun validateMavenArtifactCoordinate(req: MavenArtifactRequest) {
        require(req.groupPattern.isNotBlank()) {
            "distribution.mavenArtifacts: groupPattern must not be blank"
        }
        require(req.artifactPattern.isNotBlank()) {
            "distribution.mavenArtifacts: artifactPattern must not be blank " +
                "(groupId-only coordinate '${req.groupPattern}' is not supported — see TD-011/#349)"
        }
        // The V1-compat read path (EntityMappers.composeGavCsv) rebuilds the GAV
        // string as `group:artifact[:ext[:classifier]]`. A ':' inside any field
        // would silently corrupt that round-trip (re-parsing to a DIFFERENT
        // coordinate) — the exact divergence class this guard closes — and ':'
        // is never valid inside a Maven groupId/artifactId/type/classifier. So
        // reject ':' in every field. NOTE: ',' is NOT rejected — a groupPattern
        // is legitimately a comma-separated CSV of groups (MavenGavCollision
        // splits it), a first-class pattern shape.
        listOf(
            "groupPattern" to req.groupPattern,
            "artifactPattern" to req.artifactPattern,
            "extension" to req.extension,
            "classifier" to req.classifier,
        ).forEach { (field, value) ->
            require(value == null || !value.contains(':')) {
                "distribution.mavenArtifacts: $field must not contain ':' " +
                    "(the coordinate segment separator; value='$value')"
            }
        }
    }

    // ------------------------------------------------------------------
    // Cheap field-format checks (Stage 5 / validation-parity audit #9,#16,#19,#21).
    //
    // Restores the single-field shape/format validations the old
    // EscrowConfigValidator ran at config-load time but that the v4 write
    // path dropped. All four are field-name-prefixed `require(...)` →
    // IllegalArgumentException → 400 (ControllerExceptionHandler).
    // ------------------------------------------------------------------

    /**
     * `clientCode` must match `[A-Z_0-9]+` when present (audit #16; old
     * `EscrowConfigValidator.validateClientCode`). A blank/whitespace value is
     * treated as "no client code" and skipped — only a non-blank value that
     * fails the pattern is rejected.
     */
    private fun validateClientCode(value: String) {
        if (value.isBlank()) return
        require(CLIENT_CODE_PATTERN.matches(value)) {
            "clientCode '$value' does not match the required pattern '${CLIENT_CODE_PATTERN.pattern}'"
        }
    }

    /**
     * `copyright` must name a file in the configured copyright directory (audit
     * #21; old `EscrowConfigValidator.validateCopyright`). The supported list is
     * the same source the old validator used: the regular files under
     * `components-registry.copyright-path`. When that path is not configured (or
     * cannot be listed), the check is a no-op — mirroring the old behaviour of
     * skipping when `copyrightPath == null`. A blank value is skipped.
     */
    private fun validateCopyright(value: String) {
        if (value.isBlank()) return
        val supported = supportedCopyrights() ?: return
        require(value in supported) {
            "copyright '$value' is not supported. Available values are $supported"
        }
    }

    /**
     * Audit #5: when a copyright directory is configured, explicit+external
     * components must select one of its entries. This requiredness check runs
     * against the final entity state on every create/update, like the person
     * requiredness checks, while [validateCopyright] validates a submitted
     * non-blank value against the supported list.
     */
    private fun validateRequiredCopyright(entity: ComponentEntity) {
        if (fieldConfigService.isHidden("component.copyright")) return
        if (entity.distributionExplicit != true || entity.distributionExternal != true) return
        if (configHelper.copyrightPath() == null) return

        require(!entity.copyright.isNullOrBlank()) {
            "copyright must not be blank for an explicit+external component when copyright-path is configured"
        }
    }

    /**
     * #2 componentDisplayName required for explicit+external components — mirrors the
     * pre-existing DSL rule `EscrowConfigValidator.validateExplicitExternalComponent`.
     * displayName itself stays nullable for all other components (preserving the legacy
     * v1/v2/v3 `$.name` wire). Runs against the final entity state on every create/update
     * (like [validateRequiredCopyright]), so it also catches a displayName-only PATCH that
     * clears the value on an explicit+external component. Colon-prefixed → the Portal routes
     * the 400 inline onto the displayName field.
     */
    private fun validateRequiredDisplayName(entity: ComponentEntity) {
        // A hidden displayName is silently stripped (persisted null), so its requiredness
        // must not 4xx — mirrors validateRequiredCopyright's hidden guard above.
        if (fieldConfigService.isHidden("component.displayName")) return
        if (entity.distributionExplicit != true || entity.distributionExternal != true) return
        require(!entity.displayName.isNullOrBlank()) {
            "displayName: componentDisplayName is required for an explicit+external " +
                "component (component '${entity.componentKey}')"
        }
    }

    /**
     * The supported-copyright names, read from the configured copyright
     * directory (regular files only). Returns null when no path is configured or
     * the directory cannot be listed, so the caller can skip the check.
     */
    private fun supportedCopyrights(): List<String>? {
        val path = configHelper.copyrightPath() ?: return null
        return runCatching {
            Files.list(path).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .map { it.fileName.toString() }
                    .sorted()
                    .toList()
            }
        }.onFailure {
            log.warn("Copyright directory '{}' is unreadable; copyright validation skipped", path, it)
        }.getOrNull()
    }

    private fun buildSpecification(filter: ComponentFilter): Specification<ComponentEntity> {
        var spec = Specification.where<ComponentEntity>(null)

        // Always-on exclusion (compat parity). A FAKE aggregator (stub VCS / artifactId that
        // only owns a `components { }` block) keeps its ComponentEntity row so the v1–v3
        // resolver still serves it — but it must NOT appear in the v4 regular-components list
        // (it represents a group, not a shippable component; groups get their own UI later).
        // The importer self-links such a stub to its OWN fake group, so the marker is:
        // componentGroup is fake AND its groupKey equals the row's own componentKey. LEFT join
        // so ordinary group-less components and real-aggregator members are all kept; a
        // many-to-one join does not multiply rows, so no query.distinct(true) is needed.
        spec =
            spec.and(
                Specification { root, _, cb ->
                    val grp = root.join<ComponentEntity, ComponentGroupEntity>("componentGroup", JoinType.LEFT)
                    cb.or(
                        cb.isNull(grp.get<UUID>("id")),
                        cb.not(
                            cb.and(
                                cb.isTrue(grp.get<Boolean>("isFake")),
                                cb.equal(grp.get<String>("groupKey"), root.get<String>("componentKey")),
                            ),
                        ),
                    )
                },
            )

        filter.productType?.let { pt ->
            spec = spec.and(Specification { root, _, cb -> cb.equal(root.get<String>("productType"), pt) })
        }
        filter.archived?.let { archived ->
            spec = spec.and(Specification { root, _, cb -> cb.equal(root.get<Boolean>("archived"), archived) })
        }
        // OR across selected owners — a component matches when its scalar
        // componentOwner column equals any of the listed values. No JOIN
        // (componentOwner is a column on ComponentEntity itself, not a
        // junction), so no query.distinct(true) is needed; the IN-predicate
        // alone is enough. The controller's normalisation guarantees the
        // list, if present, is non-empty, has no blanks, and has no
        // duplicates.
        if (!filter.owner.isNullOrEmpty()) {
            spec =
                spec.and(
                    Specification { root, _, _ ->
                        root.get<String>("componentOwner").`in`(filter.owner)
                    },
                )
        }
        // OR across selected release managers — a component matches when ANY listed
        // username is among its ordered releaseManagers child rows. Unlike owner
        // (a scalar column), this JOINs the child collection, so a single join +
        // username IN(...) gives OR and query.distinct(true) dedupes the row
        // multiplication a multi-RM component would otherwise produce (mirrors the
        // buildSystem single-join-IN shape). The controller's normalisation
        // guarantees a non-empty, blank-free, duplicate-free list.
        if (!filter.releaseManager.isNullOrEmpty()) {
            spec =
                spec.and(
                    Specification { root, query, _ ->
                        val join = root.join<ComponentEntity, ComponentReleaseManagerEntity>("releaseManagers")
                        query?.distinct(true)
                        join.get<String>("username").`in`(filter.releaseManager)
                    },
                )
        }
        // OR across selected security champions — identical shape against the
        // securityChampions child collection.
        if (!filter.securityChampion.isNullOrEmpty()) {
            spec =
                spec.and(
                    Specification { root, query, _ ->
                        val join = root.join<ComponentEntity, ComponentSecurityChampionEntity>("securityChampions")
                        query?.distinct(true)
                        join.get<String>("username").`in`(filter.securityChampion)
                    },
                )
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
        // OR across selected buildSystems — a component has exactly one BASE
        // buildSystem at a time, so multi-select semantics is "any of these"
        // (NOT AND, which would only ever return zero or one match). One
        // JOIN through configurations with rowType=BASE + IN(...) is the
        // correct shape; mirrors the per-value AND pattern used for labels
        // and system but consolidates into a single predicate because the
        // column is scalar on the joined row, not a collection. The
        // controller's normalisation guarantees the list, if present, is
        // non-empty, has no blanks, and has no duplicates.
        if (!filter.buildSystem.isNullOrEmpty()) {
            spec =
                spec.and(
                    Specification { root, query, cb ->
                        val join = root.join<ComponentEntity, ComponentConfigurationEntity>("configurations")
                        query?.distinct(true)
                        cb.and(
                            cb.equal(join.get<String>("rowType"), "BASE"),
                            join.get<String>("buildSystem").`in`(filter.buildSystem),
                        )
                    },
                )
        }
        // OR across selected system codes — a component matches when ANY of its
        // system junctions has a code in the list. Unlike labels (also
        // junction-backed but AND across selections), the picker semantics here
        // is "components belonging to any of these systems". One JOIN through
        // systemJunctions + IN(...) keeps the predicate count bounded regardless
        // of selection size; `distinct(true)` dedupes the row multiplication a
        // multi-system component would otherwise cause. The controller's
        // normalisation guarantees the list, if present, is non-empty, has no
        // blanks, and has no duplicates.
        if (!filter.system.isNullOrEmpty()) {
            spec =
                spec.and(
                    Specification { root, query, _ ->
                        val join = root.join<ComponentEntity, ComponentSystemEntity>("systemJunctions")
                        query?.distinct(true)
                        join.get<String>("systemCode").`in`(filter.system)
                    },
                )
        }
        // AND across selected labels = one join + one predicate per label.
        // A single join + IN(...) would relax to OR (any row whose label is
        // in the set), which is not the semantics the multi-select picker
        // promises ("show me components carrying ALL of these labels").
        // The controller's normalisation guarantees the list, if present,
        // is non-empty, has no blanks, and has no duplicates, so the
        // forEach body is always meaningful and never redundant.
        //
        // One join per selected label, matching the system/buildSystem
        // filter pattern above. Number of labels in a filter is bounded by
        // the picker UI; if it ever grows large enough to be a problem,
        // revisit via EXISTS subqueries or GROUP BY HAVING COUNT(DISTINCT).
        if (!filter.labels.isNullOrEmpty()) {
            filter.labels.forEach { lbl ->
                spec =
                    spec.and(
                        Specification { root, query, cb ->
                            val join = root.join<ComponentEntity, ComponentLabelEntity>("labelJunctions")
                            query?.distinct(true)
                            cb.equal(join.get<String>("labelCode"), lbl)
                        },
                    )
            }
        }
        // Scalar boolean filter on `components.can_be_parent`. No JOIN — plain
        // equality on the column; the partial index serves the `= true` case
        // used by the Portal parent picker.
        filter.canBeParent?.let { cbp ->
            spec =
                spec.and(
                    Specification { root, _, cb ->
                        cb.equal(root.get<Boolean>("canBeParent"), cbp)
                    },
                )
        }
        // ── Extended-search filters ──
        // clientCode / jiraProjectKey / parentComponentName / groupKey are multi-value
        // (exact OR `IN`, SYS-046); solution / jiraTechnical / distributionExplicit /
        // distributionExternal / vcsPath / productionBranch stay single-value.
        // A null/empty list (or null scalar) means "no filter".
        // SYS-046: multi-value exact OR on the scalar `components.client_code`.
        // Scalar column, so no JOIN and no distinct. The controller's
        // normalisation guarantees a non-empty, blank-free, duplicate-free list.
        if (!filter.clientCode.isNullOrEmpty()) {
            spec =
                spec.and(
                    Specification { root, _, _ ->
                        root.get<String>("clientCode").`in`(filter.clientCode)
                    },
                )
        }
        filter.solution?.let { v ->
            spec = spec.and(Specification { root, _, cb -> cb.equal(root.get<Boolean>("solution"), v) })
        }
        // SYS-045: scalar boolean distribution filters, mirroring `solution`. Both columns
        // are nullable, so `=false` matches only rows explicitly set false (NULL rows are
        // excluded). No JOIN, no distinct.
        filter.distributionExplicit?.let { v ->
            spec = spec.and(Specification { root, _, cb -> cb.equal(root.get<Boolean>("distributionExplicit"), v) })
        }
        filter.distributionExternal?.let { v ->
            spec = spec.and(Specification { root, _, cb -> cb.equal(root.get<Boolean>("distributionExternal"), v) })
        }
        // ManyToOne joins — a component has at most one parent / one group, so no
        // row multiplication and no distinct needed.
        // SYS-046: multi-value exact OR on the parent component's `component_key`
        // (children of any of these parents). ManyToOne join → no distinct.
        if (!filter.parentComponentName.isNullOrEmpty()) {
            spec =
                spec.and(
                    Specification { root, _, _ ->
                        val parent = root.join<ComponentEntity, ComponentEntity>("parentComponent")
                        parent.get<String>("componentKey").`in`(filter.parentComponentName)
                    },
                )
        }
        // SYS-046: multi-value exact OR on the owning group's `group_key`.
        // ManyToOne join → no distinct.
        if (!filter.groupKey.isNullOrEmpty()) {
            spec =
                spec.and(
                    Specification { root, _, _ ->
                        val group = root.join<ComponentEntity, ComponentGroupEntity>("componentGroup")
                        group.get<String>("groupKey").`in`(filter.groupKey)
                    },
                )
        }
        // BASE configuration-row filters — one OneToMany JOIN through
        // `configurations` (rowType=BASE), distinct(true) to dedupe (mirrors the
        // buildSystem filter above).
        // SYS-046: multi-value exact OR on the BASE configuration row's
        // `jira_project_key`. One JOIN through `configurations` (rowType=BASE) +
        // distinct(true) so a multi-config/multi-VCS component is counted once.
        if (!filter.jiraProjectKey.isNullOrEmpty()) {
            spec =
                spec.and(
                    Specification { root, query, cb ->
                        val cfg = root.join<ComponentEntity, ComponentConfigurationEntity>("configurations")
                        query?.distinct(true)
                        cb.and(
                            cb.equal(cfg.get<String>("rowType"), "BASE"),
                            cfg.get<String>("jiraProjectKey").`in`(filter.jiraProjectKey),
                        )
                    },
                )
        }
        filter.jiraTechnical?.let { v ->
            spec =
                spec.and(
                    Specification { root, query, cb ->
                        val cfg = root.join<ComponentEntity, ComponentConfigurationEntity>("configurations")
                        query?.distinct(true)
                        cb.and(
                            cb.equal(cfg.get<String>("rowType"), "BASE"),
                            cb.equal(cfg.get<Boolean>("jiraTechnical"), v),
                        )
                    },
                )
        }
        // BASE-row VCS-entry filters — two-level JOIN (configurations(BASE) →
        // vcsEntries), distinct(true) to dedupe multi-entry components.
        filter.vcsPath?.takeIf { it.isNotBlank() }?.let { v ->
            val pattern = "%${v.trim().lowercase()}%"
            spec =
                spec.and(
                    Specification { root, query, cb ->
                        val cfg = root.join<ComponentEntity, ComponentConfigurationEntity>("configurations")
                        val vcs = cfg.join<ComponentConfigurationEntity, VcsSettingsEntryEntity>("vcsEntries")
                        query?.distinct(true)
                        cb.and(
                            cb.equal(cfg.get<String>("rowType"), "BASE"),
                            cb.like(cb.lower(vcs.get("vcsPath")), pattern),
                        )
                    },
                )
        }
        filter.productionBranch?.takeIf { it.isNotBlank() }?.let { v ->
            val pattern = "%${v.trim().lowercase()}%"
            spec =
                spec.and(
                    Specification { root, query, cb ->
                        val cfg = root.join<ComponentEntity, ComponentConfigurationEntity>("configurations")
                        val vcs = cfg.join<ComponentConfigurationEntity, VcsSettingsEntryEntity>("vcsEntries")
                        query?.distinct(true)
                        cb.and(
                            cb.equal(cfg.get<String>("rowType"), "BASE"),
                            cb.like(cb.lower(vcs.get("branch")), pattern),
                        )
                    },
                )
        }
        return spec
    }

    /**
     * Snapshot of the component's scalar fields + label/system memberships for
     * audit-log purposes. `overrideLabels` / `overrideSystems` short-circuit the
     * entity's in-memory `labelJunctions` / `systemJunctions` — needed after the
     * `syncLabels` / `syncSystems` repo-direct writes, since those bypass the
     * entity's collections and the in-memory collections still hold the pre-sync
     * set. When the field was field-config-hidden, callers pass the already-
     * stripped set (empty on create, null → falls back to the captured original
     * on patch), so the audit reflects what actually persisted.
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
            "canBeParent" to entity.canBeParent,
            "groupKey" to entity.componentGroup?.groupKey,
            // Comma-joined with the same empty→null rule as the legacy mapper,
            // so an empty-list clear audits as null (not "").
            "releaseManager" to entity.releaseManagerUsernames().joinToString(",").ifEmpty { null },
            "securityChampion" to entity.securityChampionUsernames().joinToString(",").ifEmpty { null },
            "copyright" to entity.copyright,
            "releasesInDefaultBranch" to entity.releasesInDefaultBranch,
            "jiraDisplayName" to entity.jiraDisplayName,
            "jiraHotfixVersionFormat" to entity.jiraHotfixVersionFormat,
            "vcsExternalRegistry" to entity.vcsExternalRegistry,
            "skipCommitCheck" to entity.skipCommitCheck,
            "distributionExplicit" to entity.distributionExplicit,
            "distributionExternal" to entity.distributionExternal,
            "labels" to (overrideLabels ?: entity.labelJunctions.map { it.labelCode }.toSet()),
            "systems" to (overrideSystems ?: entity.systemJunctions.map { it.systemCode }.toSet()),
        )

    /**
     * Snapshot of the component's section fields for the audit trail: the BASE
     * configuration row's build/escrow/jira scalars + versionRange, its child
     * collections, and the per-component child collections (artifactIds,
     * securityGroups, teamcityProjects, docs). SYS-053: these keys must be part
     * of both audit snapshots — without them a section-only PATCH produces
     * identical old/new maps and the SYS-048 no-op guard drops the row, so the
     * save persists but History stays empty.
     *
     * Collection entries are content-only — no row ids: a PATCH REPLACE
     * recreates the child rows, so an id-bearing snapshot would diff on id
     * churn and turn every re-save of an unchanged form into a fake history
     * entry. Lists are ordered by sortOrder (requiredTools by tool name,
     * securityGroups by content — neither carries a sortOrder) so load order
     * can't leak into the old/new comparison either.
     *
     * Version-ranged rows (SCALAR_OVERRIDE / MARKER) are intentionally NOT
     * captured here — field-override writes publish their own attribute-keyed
     * audit events (SYS-050).
     */
    private fun sectionAuditMap(entity: ComponentEntity): Map<String, Any?> {
        val base = entity.configurations.firstOrNull { it.rowType == "BASE" }
        return baseConfigScalarAuditEntries(base) +
            baseConfigCollectionAuditEntries(base) +
            componentCollectionAuditEntries(entity)
    }

    private fun baseConfigScalarAuditEntries(base: ComponentConfigurationEntity?): Map<String, Any?> =
        mapOf(
            // Namespaced so the BASE row's range can't be misread as the
            // range of a version-ranged override row (those audit under
            // their own fieldOverride[<attr>] key — SYS-050).
            "baseConfiguration.versionRange" to base?.versionRange,
            "build.buildSystem" to base?.buildSystem,
            "build.javaVersion" to base?.javaVersion,
            "build.mavenVersion" to base?.mavenVersion,
            "build.gradleVersion" to base?.gradleVersion,
            "build.buildFilePath" to base?.buildFilePath,
            "build.deprecated" to base?.deprecated,
            "build.requiredProject" to base?.requiredProject,
            "build.projectVersion" to base?.projectVersion,
            "build.systemProperties" to base?.systemProperties,
            "build.buildTasks" to base?.buildTasks,
            "escrow.providedDependencies" to base?.escrowProvidedDependencies,
            "escrow.reusable" to base?.escrowReusable,
            "escrow.generation" to base?.escrowGeneration,
            "escrow.diskSpace" to base?.escrowDiskSpace,
            "escrow.additionalSources" to base?.escrowAdditionalSources,
            "escrow.gradleIncludeConfigurations" to base?.escrowGradleIncludeConfigurations,
            "escrow.gradleExcludeConfigurations" to base?.escrowGradleExcludeConfigurations,
            "escrow.gradleIncludeTestConfigurations" to base?.escrowGradleIncludeTestConfigurations,
            "escrow.buildTask" to base?.escrowBuildTask,
            "jira.projectKey" to base?.jiraProjectKey,
            "jira.technical" to base?.jiraTechnical,
            "jira.minorVersionFormat" to base?.jiraMinorVersionFormat,
            "jira.releaseVersionFormat" to base?.jiraReleaseVersionFormat,
            "jira.buildVersionFormat" to base?.jiraBuildVersionFormat,
            "jira.lineVersionFormat" to base?.jiraLineVersionFormat,
            "jira.versionPrefix" to base?.jiraVersionPrefix,
            "jira.versionFormat" to base?.jiraVersionFormat,
            "jira.hotfixVersionFormat" to base?.jiraHotfixVersionFormat,
        )

    private fun baseConfigCollectionAuditEntries(base: ComponentConfigurationEntity?): Map<String, Any?> =
        mapOf(
            "vcsEntries" to
                base?.vcsEntries.orEmpty().sortedBy { it.sortOrder }.map {
                    mapOf(
                        "name" to it.name,
                        "vcsPath" to it.vcsPath,
                        "branch" to it.branch,
                        "tag" to it.tag,
                        "hotfixBranch" to it.hotfixBranch,
                        "repositoryType" to it.repositoryType,
                    )
                },
            "mavenArtifacts" to
                base?.mavenArtifacts.orEmpty().sortedBy { it.sortOrder }.map {
                    mapOf(
                        "groupPattern" to it.groupPattern,
                        "artifactPattern" to it.artifactPattern,
                        "extension" to it.extension,
                        "classifier" to it.classifier,
                    )
                },
            "fileUrlArtifacts" to
                base?.fileUrlArtifacts.orEmpty().sortedBy { it.sortOrder }.map {
                    mapOf(
                        "url" to it.url,
                        "artifactId" to it.artifactId,
                        "classifier" to it.classifier,
                    )
                },
            "dockerImages" to
                base?.dockerImages.orEmpty().sortedBy { it.sortOrder }.map {
                    mapOf(
                        "imageName" to it.imageName,
                        "flavor" to it.flavor,
                    )
                },
            "packages" to
                base?.packages.orEmpty().sortedBy { it.sortOrder }.map {
                    mapOf(
                        "packageType" to it.packageType,
                        "packageName" to it.packageName,
                    )
                },
            "buildToolBeans" to
                base?.buildToolBeans.orEmpty().sortedBy { it.sortOrder }.map {
                    mapOf(
                        "beanType" to it.beanType,
                        "toolType" to it.toolType,
                        "settingsProperty" to it.settingsProperty,
                        "versionPattern" to it.versionPattern,
                        "edition" to it.edition,
                    )
                },
            "requiredTools" to base?.requiredToolJunctions.orEmpty().map { it.toolName }.sorted(),
        )

    private fun componentCollectionAuditEntries(entity: ComponentEntity): Map<String, Any?> =
        mapOf(
            "artifactIds" to
                entity.artifactMappings.sortedWith(ARTIFACT_MAPPING_ORDER).map {
                    mapOf(
                        "versionRange" to it.versionRange,
                        "groupPattern" to it.groupPattern,
                        "mode" to it.artifactIdMode,
                        "artifactTokens" to it.tokens.sortedBy { t -> t.sortOrder }.map { t -> t.artifactPattern },
                    )
                },
            "securityGroups" to
                entity.securityGroups.sortedWith(compareBy({ it.groupType }, { it.groupName })).map {
                    mapOf(
                        "groupType" to it.groupType,
                        "groupName" to it.groupName,
                    )
                },
            "teamcityProjects" to entity.teamcityProjects.sortedBy { it.sortOrder }.map { it.projectId },
            "docs" to
                entity.docLinks.sortedBy { it.sortOrder }.map {
                    mapOf(
                        "docComponentKey" to it.docComponentKey,
                        "majorVersion" to it.majorVersion,
                    )
                },
        )

    /**
     * Snapshot of a field-override row for the audit trail, keyed by the
     * overridden attribute so the change reads as `fieldOverride[<attr>]` in the
     * diff. Captures the version range and the resolved scalar value / marker
     * children. Used as the old/new payload for the synthetic Component UPDATE
     * events published on field-override writes. SYS-050.
     *
     * Create/delete pass an empty map (not `null`) for the absent side: with an
     * empty map `AuditDiff` computes a non-null diff that records the override as
     * added / removed, whereas `null` would yield a null diff and lose that
     * detail. Both sides are non-null, so the SYS-048 no-op guard still drops a
     * genuine no-op PATCH (before == after).
     */
    private fun fieldOverrideAuditSnapshot(row: ComponentConfigurationEntity): Map<String, Any?> {
        val resp = row.toFieldOverrideResponse()
        return mapOf(
            "fieldOverride[${resp.overriddenAttribute}]" to
                mapOf(
                    "versionRange" to resp.versionRange,
                    "value" to resp.value,
                    "markerChildren" to resp.markerChildren,
                ),
        )
    }

    private fun publishAuditEvent(
        action: String,
        entityId: String,
        oldValue: Map<String, Any?>? = null,
        newValue: Map<String, Any?>? = null,
        jiraTaskKey: String? = null,
        changeComment: String? = null,
    ) {
        applicationEventPublisher.publishEvent(
            AuditEvent(
                entityType = "Component",
                entityId = entityId,
                action = action,
                changedBy = currentUserResolver.currentUsername(),
                oldValue = oldValue,
                newValue = newValue,
                // Normalize blank/whitespace → null so a stray "" from the client
                // is never persisted (the @Pattern accepts blank as "no key").
                jiraTaskKey = jiraTaskKey?.trim()?.ifBlank { null },
                changeComment = changeComment?.trim()?.ifBlank { null },
            ),
        )
    }

    private companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(ComponentManagementServiceImpl::class.java)

        /** Split a multi-valued `groupPattern` on comma or pipe (legacy DSL semantics). */
        private val GROUP_ID_SPLIT = Regex("[,|]")

        // Same shape as the old EscrowConfigValidator.CLIENT_CODE_PATTERN.
        private val CLIENT_CODE_PATTERN = Regex("[A-Z_0-9]+")
    }
}
