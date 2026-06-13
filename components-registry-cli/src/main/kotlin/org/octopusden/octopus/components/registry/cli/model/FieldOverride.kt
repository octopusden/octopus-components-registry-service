package org.octopusden.octopus.components.registry.cli.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Mirror of v4.json `MarkerChildrenPayload`.
 *
 * In the canon the nested item types are the *Request* shapes (the payload is symmetric for
 * read/write), so this mirror reuses [BuildAspectResponse]-style request fields directly where they
 * are structurally identical. Items below mirror the request item schemas faithfully. All optional.
 */
@Serializable
data class MarkerChildrenPayload(
    val buildToolBeans: List<BuildToolBeanItem>? = null,
    val dockerImages: List<DockerImageItem>? = null,
    val fileUrlArtifacts: List<FileUrlArtifactItem>? = null,
    val mavenArtifacts: List<MavenArtifactItem>? = null,
    val packages: List<PackageItem>? = null,
    val requiredTools: List<String>? = null,
    val vcsEntries: List<VcsEntryItem>? = null,
)

/**
 * Mirror of v4.json `BuildToolBeanRequest` (used inside [MarkerChildrenPayload]). `beanType` required.
 */
@Serializable
data class BuildToolBeanItem(
    val beanType: String,
    val edition: String? = null,
    val settingsProperty: String? = null,
    val toolType: String? = null,
    val versionPattern: String? = null,
)

/**
 * Mirror of v4.json `DockerImageRequest` (used inside [MarkerChildrenPayload]). `imageName` required.
 */
@Serializable
data class DockerImageItem(
    val imageName: String,
    val flavor: String? = null,
)

/**
 * Mirror of v4.json `FileUrlArtifactRequest` (used inside [MarkerChildrenPayload]). `url` required.
 */
@Serializable
data class FileUrlArtifactItem(
    val url: String,
    val artifactId: String? = null,
    val classifier: String? = null,
)

/**
 * Mirror of v4.json `MavenArtifactRequest` (used inside [MarkerChildrenPayload]).
 */
@Serializable
data class MavenArtifactItem(
    val artifactPattern: String,
    val groupPattern: String,
    val classifier: String? = null,
    val extension: String? = null,
)

/**
 * Mirror of v4.json `PackageRequest` (used inside [MarkerChildrenPayload]).
 */
@Serializable
data class PackageItem(
    val packageName: String,
    val packageType: String,
)

/**
 * Mirror of v4.json `VcsEntryRequest` (used inside [MarkerChildrenPayload]). No required fields per
 * the request schema; modelled all-nullable.
 */
@Serializable
data class VcsEntryItem(
    val name: String? = null,
    val vcsPath: String? = null,
    val repositoryType: String? = null,
    val branch: String? = null,
    val tag: String? = null,
    val hotfixBranch: String? = null,
)

/**
 * Mirror of v4.json `FieldOverrideResponse` — a single element of the array returned by
 * GET /rest/api/4/components/{id}/field-overrides.
 *
 * `value` is a free-form spec `object`, modelled as a nullable [JsonElement]. `createdAt`/`updatedAt`
 * are ISO-8601 date-time strings.
 *
 * Required per spec: id, overriddenAttribute, rowType, versionRange.
 */
@Serializable
data class FieldOverrideResponse(
    val id: String,
    val overriddenAttribute: String,
    val rowType: String,
    val versionRange: String,
    val value: JsonElement? = null,
    val markerChildren: MarkerChildrenPayload? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)
