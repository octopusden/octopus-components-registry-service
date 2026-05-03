package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.core.exceptions.ComponentNameConflictException
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentCreateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentDetailResponse
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentFilter
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentSummaryResponse
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentUpdateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.FieldOverrideCreateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.FieldOverrideResponse
import org.octopusden.octopus.components.registry.server.dto.v4.FieldOverrideUpdateRequest
import org.octopusden.octopus.components.registry.server.entity.BuildConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionArtifactEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionSecurityGroupEntity
import org.octopusden.octopus.components.registry.server.entity.EscrowConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.FieldOverrideEntity
import org.octopusden.octopus.components.registry.server.entity.JiraComponentConfigEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
import org.octopusden.octopus.components.registry.server.event.AuditEvent
import org.octopusden.octopus.components.registry.server.mapper.toDetailResponse
import org.octopusden.octopus.components.registry.server.mapper.toResponse
import org.octopusden.octopus.components.registry.server.mapper.toSummaryResponse
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.FieldOverrideRepository
import org.octopusden.octopus.components.registry.server.security.CurrentUserResolver
import org.octopusden.octopus.components.registry.server.service.ComponentManagementService
import org.octopusden.octopus.components.registry.server.service.ComponentSourceRegistry
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
@Suppress("TooManyFunctions")
class ComponentManagementServiceImpl(
    private val componentRepository: ComponentRepository,
    private val fieldOverrideRepository: FieldOverrideRepository,
    private val sourceRegistry: ComponentSourceRegistry,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val currentUserResolver: CurrentUserResolver,
    // Risk-1 mitigation: see FieldConfigService class doc. Used by
    // updateComponent to silently strip writes to hidden fields,
    // matching the Portal-side filter so a buggy client can't bypass
    // it. Create path is admin-driven (component import) and reads
    // the full payload — not gated.
    private val fieldConfigService: FieldConfigService,
) : ComponentManagementService {
    @Suppress("CyclomaticComplexMethod")
    override fun createComponent(request: ComponentCreateRequest): ComponentDetailResponse {
        require(!componentRepository.existsByName(request.name)) {
            "Component with name '${request.name}' already exists"
        }

        val parentComponent =
            request.parentComponentName?.let { parentName ->
                componentRepository.findByName(parentName)
                    ?: throw NotFoundException("Parent component '$parentName' not found")
            }

        val entity =
            ComponentEntity(
                name = request.name,
                displayName = request.displayName,
                componentOwner = request.componentOwner,
                productType = request.productType,
                system = request.system.toTypedArray(),
                clientCode = request.clientCode,
                solution = request.solution,
                parentComponent = parentComponent,
                archived = request.archived,
                metadata = request.metadata.toMutableMap(),
                // SYS-039
                groupId = request.groupId,
                releaseManager = request.releaseManager,
                securityChampion = request.securityChampion,
                copyright = request.copyright,
                releasesInDefaultBranch = request.releasesInDefaultBranch,
                labels = request.labels.toTypedArray(),
            )

        val saved = componentRepository.save(entity)

        applicationEventPublisher.publishEvent(
            AuditEvent(
                entityType = "Component",
                entityId = saved.id.toString(),
                action = "CREATE",
                changedBy = currentUserResolver.currentUsername(),
                newValue =
                    mapOf(
                        "name" to saved.name,
                        "displayName" to saved.displayName,
                        "componentOwner" to saved.componentOwner,
                        "productType" to saved.productType,
                        "archived" to saved.archived,
                    ),
            ),
        )

        return saved.toDetailResponse()
    }

    @Transactional(readOnly = true)
    override fun getComponent(id: UUID): ComponentDetailResponse {
        val entity =
            componentRepository.findById(id).orElse(null)
                ?: throw NotFoundException("Component with id '$id' not found")
        return entity.toDetailResponse()
    }

    @Transactional(readOnly = true)
    override fun getComponentByName(name: String): ComponentDetailResponse {
        val entity =
            componentRepository.findByName(name)
                ?: throw NotFoundException("Component with name '$name' not found")
        return entity.toDetailResponse()
    }

    @Suppress("CyclomaticComplexMethod")
    override fun updateComponent(
        id: UUID,
        request: ComponentUpdateRequest,
    ): ComponentDetailResponse {
        val entity =
            componentRepository.findById(id).orElse(null)
                ?: throw NotFoundException("Component with id '$id' not found")

        if (entity.version != request.version) {
            throw jakarta.persistence.OptimisticLockException(
                "Optimistic locking conflict: expected version ${request.version} but found ${entity.version}",
            )
        }

        val oldName = entity.name
        val normalizedName = request.name?.trim()
        if (request.name != null) {
            require(!normalizedName.isNullOrEmpty()) { "name must not be blank" }
            if (normalizedName != oldName && componentRepository.existsByName(normalizedName)) {
                throw ComponentNameConflictException("Component with name '$normalizedName' already exists")
            }
        }
        val isRename = normalizedName != null && normalizedName != oldName

        val oldValue =
            mapOf(
                "name" to oldName,
                "displayName" to entity.displayName,
                "componentOwner" to entity.componentOwner,
                "productType" to entity.productType,
                "system" to entity.system.toList(),
                "clientCode" to entity.clientCode,
                "solution" to entity.solution,
                "parentComponentName" to entity.parentComponent?.name,
                "archived" to entity.archived,
                "metadata" to entity.metadata.toMap(),
                // SYS-039
                "groupId" to entity.groupId,
                "releaseManager" to entity.releaseManager,
                "securityChampion" to entity.securityChampion,
                "copyright" to entity.copyright,
                "releasesInDefaultBranch" to entity.releasesInDefaultBranch,
                "labels" to entity.labels.toList(),
            )

        if (isRename) {
            entity.name = normalizedName!!
        }
        // Risk-1 mitigation: each component-section scalar is gated on
        // FieldConfig visibility. A request that arrives with a value
        // for a field whose admin-configured visibility is "hidden" gets
        // silently stripped — defence-in-depth against buggy / outdated
        // clients. `archived`, `metadata`, `parentComponentName`, and
        // `name` (rename) are NOT FC-controlled and stay un-gated; they
        // have their own permission gates (@PreAuthorize at the
        // controller). `productType` lives on the Component entity but
        // the Portal renders/saves it from EscrowTab, so it's gated
        // under the "escrow.productType" path here to match.
        if (!fieldConfigService.isHidden("component.displayName")) {
            request.displayName?.let { entity.displayName = it }
        }
        if (!fieldConfigService.isHidden("component.componentOwner")) {
            request.componentOwner?.let { entity.componentOwner = it }
        }
        if (!fieldConfigService.isHidden("escrow.productType")) {
            request.productType?.let { entity.productType = it }
        }
        if (!fieldConfigService.isHidden("component.system")) {
            request.system?.let { entity.system = it.toTypedArray() }
        }
        if (!fieldConfigService.isHidden("component.clientCode")) {
            request.clientCode?.let { entity.clientCode = it }
        }
        if (!fieldConfigService.isHidden("component.solution")) {
            request.solution?.let { entity.solution = it }
        }
        request.archived?.let { entity.archived = it }
        request.metadata?.let { entity.metadata = it.toMutableMap() }
        // SYS-039 — same gating
        if (!fieldConfigService.isHidden("component.groupId")) {
            request.groupId?.let { entity.groupId = it }
        }
        if (!fieldConfigService.isHidden("component.releaseManager")) {
            request.releaseManager?.let { entity.releaseManager = it }
        }
        if (!fieldConfigService.isHidden("component.securityChampion")) {
            request.securityChampion?.let { entity.securityChampion = it }
        }
        if (!fieldConfigService.isHidden("component.copyright")) {
            request.copyright?.let { entity.copyright = it }
        }
        if (!fieldConfigService.isHidden("component.releasesInDefaultBranch")) {
            request.releasesInDefaultBranch?.let { entity.releasesInDefaultBranch = it }
        }
        if (!fieldConfigService.isHidden("component.labels")) {
            request.labels?.let { entity.labels = it.toTypedArray() }
        }

        request.parentComponentName?.let { parentName ->
            entity.parentComponent = componentRepository.findByName(parentName)
                ?: throw NotFoundException("Parent component '$parentName' not found")
        }

        // Update nested sub-entities
        request.buildConfiguration?.let { bc ->
            val buildEntity =
                entity.buildConfigurations.firstOrNull()
                    ?: BuildConfigurationEntity(component = entity).also { entity.buildConfigurations.add(it) }
            bc.buildSystem?.let { buildEntity.buildSystem = it }
            bc.buildFilePath?.let { buildEntity.buildFilePath = it }
            bc.javaVersion?.let { buildEntity.javaVersion = it }
            bc.deprecated?.let { buildEntity.deprecated = it }
            bc.metadata?.let { buildEntity.metadata = it.toMutableMap() }
        }

        request.vcsSettings?.let { vcs ->
            val vcsEntity =
                entity.vcsSettings.firstOrNull()
                    ?: VcsSettingsEntity(component = entity).also { entity.vcsSettings.add(it) }
            vcs.vcsType?.let { vcsEntity.vcsType = it }
            vcs.externalRegistry?.let { vcsEntity.externalRegistry = it }
            vcs.entries?.let { requestEntries ->
                vcsEntity.entries.clear()
                requestEntries.forEach { re ->
                    vcsEntity.entries.add(
                        VcsSettingsEntryEntity(
                            vcsSettings = vcsEntity,
                            name = re.name,
                            vcsPath = re.vcsPath ?: "",
                            repositoryType = re.repositoryType ?: "GIT",
                            tag = re.tag,
                            branch = re.branch,
                        ),
                    )
                }
            }
        }

        request.distribution?.let { dist ->
            val distEntity =
                entity.distributions.firstOrNull()
                    ?: DistributionEntity(component = entity).also { entity.distributions.add(it) }
            dist.explicit?.let { distEntity.explicit = it }
            dist.external?.let { distEntity.external = it }
            dist.artifacts?.let { requestArtifacts ->
                distEntity.artifacts.clear()
                requestArtifacts.forEach { ra ->
                    distEntity.artifacts.add(
                        DistributionArtifactEntity(
                            distribution = distEntity,
                            artifactType = ra.artifactType ?: "",
                            groupPattern = ra.groupPattern,
                            artifactPattern = ra.artifactPattern,
                            name = ra.name,
                            tag = ra.tag,
                        ),
                    )
                }
            }
            dist.securityGroups?.let { requestGroups ->
                distEntity.securityGroups.clear()
                requestGroups.forEach { rg ->
                    distEntity.securityGroups.add(
                        DistributionSecurityGroupEntity(
                            distribution = distEntity,
                            groupType = rg.groupType ?: "read",
                            groupName = rg.groupName ?: "",
                        ),
                    )
                }
            }
        }

        request.jiraComponentConfig?.let { jira ->
            val jiraEntity =
                entity.jiraComponentConfigs.firstOrNull()
                    ?: JiraComponentConfigEntity(component = entity).also { entity.jiraComponentConfigs.add(it) }
            jira.projectKey?.let { jiraEntity.projectKey = it }
            jira.displayName?.let { jiraEntity.displayName = it }
            jira.componentVersionFormat?.let { jiraEntity.componentVersionFormat = it }
            jira.technical?.let { jiraEntity.technical = it }
            jira.metadata?.let { jiraEntity.metadata = it.toMutableMap() }
        }

        request.escrowConfiguration?.let { esc ->
            val escrowEntity =
                entity.escrowConfigurations.firstOrNull()
                    ?: EscrowConfigurationEntity(component = entity).also { entity.escrowConfigurations.add(it) }
            esc.buildTask?.let { escrowEntity.buildTask = it }
            esc.providedDependencies?.let { escrowEntity.providedDependencies = it }
            esc.reusable?.let { escrowEntity.reusable = it }
            esc.generation?.let { escrowEntity.generation = it }
            esc.diskSpace?.let { escrowEntity.diskSpace = it }
        }

        // Touch parent entity to ensure @Version increments even when only child entities changed
        entity.updatedAt = java.time.Instant.now()

        val saved = componentRepository.saveAndFlush(entity)

        if (isRename) {
            sourceRegistry.renameComponent(oldName, saved.name)
        }

        val newValue =
            mapOf(
                "name" to saved.name,
                "displayName" to saved.displayName,
                "componentOwner" to saved.componentOwner,
                "productType" to saved.productType,
                "system" to saved.system.toList(),
                "clientCode" to saved.clientCode,
                "solution" to saved.solution,
                "parentComponentName" to saved.parentComponent?.name,
                "archived" to saved.archived,
                "metadata" to saved.metadata.toMap(),
                // SYS-039 — must mirror the oldValue map above; without these
                // keys the audit changeDiff would report a spurious deletion
                // (oldValue has the field, newValue doesn't) on every update
                // that touches a SYS-039 field.
                "groupId" to saved.groupId,
                "releaseManager" to saved.releaseManager,
                "securityChampion" to saved.securityChampion,
                "copyright" to saved.copyright,
                "releasesInDefaultBranch" to saved.releasesInDefaultBranch,
                "labels" to saved.labels.toList(),
            )

        applicationEventPublisher.publishEvent(
            AuditEvent(
                entityType = "Component",
                entityId = saved.id.toString(),
                action = if (isRename) "RENAME" else "UPDATE",
                changedBy = currentUserResolver.currentUsername(),
                oldValue = oldValue,
                newValue = newValue,
            ),
        )

        return saved.toDetailResponse()
    }

    override fun deleteComponent(id: UUID) {
        val entity =
            componentRepository
                .findById(id)
                .orElseThrow { NotFoundException("Component with id '$id' not found") }

        entity.archived = true
        componentRepository.save(entity)

        applicationEventPublisher.publishEvent(
            AuditEvent(
                entityType = "Component",
                entityId = id.toString(),
                action = "DELETE",
                changedBy = currentUserResolver.currentUsername(),
                oldValue = mapOf("name" to entity.name, "archived" to false),
                newValue = mapOf("name" to entity.name, "archived" to true),
            ),
        )
    }

    @Transactional(readOnly = true)
    override fun listComponents(
        filter: ComponentFilter,
        pageable: Pageable,
    ): Page<ComponentSummaryResponse> {
        val spec = buildSpecification(filter)
        return componentRepository.findAll(spec, pageable).map { it.toSummaryResponse() }
    }

    override fun createFieldOverride(
        componentId: UUID,
        request: FieldOverrideCreateRequest,
    ): FieldOverrideResponse {
        val component =
            componentRepository
                .findById(componentId)
                .orElseThrow { NotFoundException("Component with id '$componentId' not found") }

        val entity =
            FieldOverrideEntity(
                component = component,
                fieldPath = request.fieldPath,
                versionRange = request.versionRange,
                value = request.value,
            )

        val saved = fieldOverrideRepository.save(entity)
        return saved.toResponse()
    }

    override fun updateFieldOverride(
        componentId: UUID,
        overrideId: UUID,
        request: FieldOverrideUpdateRequest,
    ): FieldOverrideResponse {
        val entity =
            fieldOverrideRepository
                .findById(overrideId)
                .orElseThrow { NotFoundException("FieldOverride with id '$overrideId' not found") }

        if (entity.component?.id != componentId) {
            throw NotFoundException("FieldOverride '$overrideId' does not belong to component '$componentId'")
        }

        request.versionRange?.let { entity.versionRange = it }
        request.value?.let { entity.value = it }

        val saved = fieldOverrideRepository.save(entity)
        return saved.toResponse()
    }

    override fun deleteFieldOverride(
        componentId: UUID,
        overrideId: UUID,
    ) {
        val entity =
            fieldOverrideRepository
                .findById(overrideId)
                .orElseThrow { NotFoundException("FieldOverride with id '$overrideId' not found") }

        if (entity.component?.id != componentId) {
            throw NotFoundException("FieldOverride '$overrideId' does not belong to component '$componentId'")
        }

        fieldOverrideRepository.delete(entity)
    }

    @Transactional(readOnly = true)
    override fun listFieldOverrides(componentId: UUID): List<FieldOverrideResponse> {
        if (!componentRepository.existsById(componentId)) {
            throw NotFoundException("Component with id '$componentId' not found")
        }
        return fieldOverrideRepository.findByComponentId(componentId).map { it.toResponse() }
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
            // Exact match on `componentOwner` (the entity's column for the user-facing
            // "owner"). Case-sensitive — the values come from `/components/meta/owners`
            // which returns the canonical strings, so the Portal autocomplete picker
            // hands back exactly what is in the column.
            spec = spec.and(Specification { root, _, cb -> cb.equal(root.get<String>("componentOwner"), owner) })
        }

        filter.search?.let { search ->
            val pattern = "%${search.lowercase()}%"
            spec =
                spec.and(
                    Specification { root, _, cb ->
                        cb.or(
                            cb.like(cb.lower(root.get("name")), pattern),
                            cb.like(cb.lower(root.get("displayName")), pattern),
                        )
                    },
                )
        }

        // filter.system is not yet supported against the text[] column. JPA Criteria
        // can't portably express "array contains" across H2 PG-compat and PostgreSQL,
        // and we don't have a native query for it yet. The earlier implementation
        // silently accepted the parameter and returned unfiltered results, which is
        // worse than a clear rejection. Callers that need the filter should either
        // drop the parameter or wait for the native-query follow-up.
        require(filter.system == null) {
            "filter.system is not yet supported; omit the parameter or filter client-side"
        }
        return spec
    }
}
