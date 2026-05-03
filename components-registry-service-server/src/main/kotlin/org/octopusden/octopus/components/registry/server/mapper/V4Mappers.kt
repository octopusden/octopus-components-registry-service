@file:Suppress("TooManyFunctions")

package org.octopusden.octopus.components.registry.server.mapper

import org.octopusden.octopus.components.registry.server.dto.v4.AuditLogResponse
import org.octopusden.octopus.components.registry.server.dto.v4.BuildConfigurationResponse
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentDetailResponse
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentSummaryResponse
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentVersionResponse
import org.octopusden.octopus.components.registry.server.dto.v4.DistributionArtifactResponse
import org.octopusden.octopus.components.registry.server.dto.v4.DistributionResponse
import org.octopusden.octopus.components.registry.server.dto.v4.DistributionSecurityGroupResponse
import org.octopusden.octopus.components.registry.server.dto.v4.EscrowConfigurationResponse
import org.octopusden.octopus.components.registry.server.dto.v4.FieldOverrideResponse
import org.octopusden.octopus.components.registry.server.dto.v4.JiraComponentConfigResponse
import org.octopusden.octopus.components.registry.server.dto.v4.VcsSettingsEntryResponse
import org.octopusden.octopus.components.registry.server.dto.v4.VcsSettingsResponse
import org.octopusden.octopus.components.registry.server.entity.AuditLogEntity
import org.octopusden.octopus.components.registry.server.entity.BuildConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentVersionEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionArtifactEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionSecurityGroupEntity
import org.octopusden.octopus.components.registry.server.entity.EscrowConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.FieldOverrideEntity
import org.octopusden.octopus.components.registry.server.entity.JiraComponentConfigEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity

fun ComponentEntity.toDetailResponse(): ComponentDetailResponse =
    ComponentDetailResponse(
        id = this.id!!,
        name = this.name,
        displayName = this.displayName,
        componentOwner = this.componentOwner,
        productType = this.productType,
        system = this.system.toSet(),
        clientCode = this.clientCode,
        archived = this.archived,
        solution = this.solution,
        parentComponentName = this.parentComponent?.name,
        metadata = this.metadata.toMap(),
        version = this.version,
        createdAt = this.createdAt,
        updatedAt = this.updatedAt,
        buildConfigurations = this.buildConfigurations.map { it.toResponse() },
        vcsSettings = this.vcsSettings.map { it.toResponse() },
        distributions = this.distributions.map { it.toResponse() },
        jiraComponentConfigs = this.jiraComponentConfigs.map { it.toResponse() },
        escrowConfigurations = this.escrowConfigurations.map { it.toResponse() },
        versions = this.versions.map { it.toResponse() },
    )

fun ComponentEntity.toSummaryResponse(): ComponentSummaryResponse =
    ComponentSummaryResponse(
        id = this.id!!,
        name = this.name,
        displayName = this.displayName,
        componentOwner = this.componentOwner,
        system = this.system.toSet(),
        productType = this.productType,
        archived = this.archived,
        updatedAt = this.updatedAt,
        // SYS-040: list-view extras. firstOrNull mirrors V4Mappers convention
        // for nested-collection access; in practice the same row is
        // returned across queries (heap order on H2; physical-storage order
        // on Postgres until VACUUM FULL reshuffles), and the V2/V3 code
        // path already relies on this implicit first-pick. We considered
        // @OrderBy("id ASC") for explicit determinism but reverted: it
        // changed which row was "first" and broke V2/V3 expected-data
        // fixtures (RES-001 in BaseComponentsRegistryServiceTest). The
        // long-term fix is a created_at column on the child tables.
        // Blank strings are normalized to null so the Portal can treat
        // absence and empty as the same case.
        buildSystem =
            this.buildConfigurations
                .firstOrNull()
                ?.buildSystem
                ?.takeIf { it.isNotBlank() },
        jiraProjectKey =
            this.jiraComponentConfigs
                .firstOrNull()
                ?.projectKey
                ?.takeIf { it.isNotBlank() },
        vcsPath =
            this.vcsSettings
                .firstOrNull()
                ?.entries
                ?.firstOrNull()
                ?.vcsPath
                ?.takeIf { it.isNotBlank() },
    )

fun BuildConfigurationEntity.toResponse(): BuildConfigurationResponse =
    BuildConfigurationResponse(
        id = this.id,
        buildSystem = this.buildSystem,
        buildFilePath = this.buildFilePath,
        javaVersion = this.javaVersion,
        deprecated = this.deprecated,
        metadata = this.metadata.toMap(),
    )

fun VcsSettingsEntity.toResponse(): VcsSettingsResponse =
    VcsSettingsResponse(
        id = this.id,
        vcsType = this.vcsType,
        externalRegistry = this.externalRegistry,
        entries = this.entries.map { it.toResponse() },
    )

fun VcsSettingsEntryEntity.toResponse(): VcsSettingsEntryResponse =
    VcsSettingsEntryResponse(
        id = this.id,
        name = this.name,
        vcsPath = this.vcsPath,
        repositoryType = this.repositoryType,
        tag = this.tag,
        branch = this.branch,
    )

fun DistributionEntity.toResponse(): DistributionResponse =
    DistributionResponse(
        id = this.id,
        explicit = this.explicit,
        external = this.external,
        artifacts = this.artifacts.map { it.toResponse() },
        securityGroups = this.securityGroups.map { it.toResponse() },
    )

fun DistributionArtifactEntity.toResponse(): DistributionArtifactResponse =
    DistributionArtifactResponse(
        id = this.id,
        artifactType = this.artifactType,
        groupPattern = this.groupPattern,
        artifactPattern = this.artifactPattern,
        name = this.name,
        tag = this.tag,
    )

fun DistributionSecurityGroupEntity.toResponse(): DistributionSecurityGroupResponse =
    DistributionSecurityGroupResponse(
        id = this.id,
        groupType = this.groupType,
        groupName = this.groupName,
    )

fun JiraComponentConfigEntity.toResponse(): JiraComponentConfigResponse =
    JiraComponentConfigResponse(
        id = this.id,
        projectKey = this.projectKey,
        displayName = this.displayName,
        componentVersionFormat = this.componentVersionFormat,
        technical = this.technical,
        metadata = this.metadata.toMap(),
    )

fun EscrowConfigurationEntity.toResponse(): EscrowConfigurationResponse =
    EscrowConfigurationResponse(
        id = this.id,
        buildTask = this.buildTask,
        providedDependencies = this.providedDependencies,
        reusable = this.reusable,
        generation = this.generation,
        diskSpace = this.diskSpace,
    )

fun ComponentVersionEntity.toResponse(): ComponentVersionResponse =
    ComponentVersionResponse(
        id = this.id,
        versionRange = this.versionRange,
    )

fun FieldOverrideEntity.toResponse(): FieldOverrideResponse =
    FieldOverrideResponse(
        id = this.id!!,
        fieldPath = this.fieldPath,
        versionRange = this.versionRange,
        value = this.value,
        createdAt = this.createdAt,
        updatedAt = this.updatedAt,
    )

fun AuditLogEntity.toResponse(): AuditLogResponse =
    AuditLogResponse(
        id = this.id!!,
        entityType = this.entityType,
        entityId = this.entityId,
        action = this.action,
        changedBy = this.changedBy,
        changedAt = this.changedAt,
        oldValue = this.oldValue,
        newValue = this.newValue,
        changeDiff = this.changeDiff,
        correlationId = this.correlationId,
        source = this.source,
    )
