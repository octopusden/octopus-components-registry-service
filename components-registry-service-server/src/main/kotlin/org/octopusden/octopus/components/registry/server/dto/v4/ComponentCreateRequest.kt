package org.octopusden.octopus.components.registry.server.dto.v4

data class ComponentCreateRequest(
    val name: String,
    val displayName: String? = null,
    val componentOwner: String? = null,
    val productType: String? = null,
    val system: Set<String> = emptySet(),
    val clientCode: String? = null,
    val solution: Boolean? = null,
    val parentComponentName: String? = null,
    val archived: Boolean = false,
    val metadata: Map<String, Any?> = emptyMap(),
    // SYS-039: §7.0 Wave 2 PR-G fields. Optional on create with null /
    // empty defaults so legacy callers don't have to set them.
    val groupId: String? = null,
    val releaseManager: String? = null,
    val securityChampion: String? = null,
    val copyright: String? = null,
    val releasesInDefaultBranch: Boolean? = null,
    val labels: Set<String> = emptySet(),
)
