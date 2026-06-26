package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.entity.ArtifactIdMode
import org.octopusden.octopus.components.registry.server.entity.ComponentArtifactMappingEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentArtifactMappingTokenEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity

/** Catch-all artifact pattern forms that map to mode ALL (the inherited ANY_ARTIFACT default). */
private val CATCH_ALL_FORMS = setOf("*", ".*", "[\\w-\\.]+", "[\\w-]+", "\\w+")

/**
 * Test helper: add a base (all-versions) ownership mapping built from a legacy `(groupPattern,
 * artifactPattern)` pair — catch-all form ⇒ mode ALL, otherwise EXPLICIT with the comma/pipe-split
 * literal tokens. Mirrors what `ImportServiceImpl` writes, so fixtures match production shape.
 */
fun ComponentEntity.addOwnershipMapping(
    groupPattern: String,
    artifactPattern: String,
    versionRange: String = "(,0),[0,)",
) {
    val catchAll = artifactPattern in CATCH_ALL_FORMS
    val mapping =
        ComponentArtifactMappingEntity(
            component = this,
            versionRange = versionRange,
            groupPattern = groupPattern,
            artifactIdMode = if (catchAll) ArtifactIdMode.ALL.name else ArtifactIdMode.EXPLICIT.name,
            sortOrder = this.artifactMappings.size,
        )
    if (!catchAll) {
        artifactPattern.split(',', '|').map { it.trim() }.filter { it.isNotEmpty() }
            .forEachIndexed { i, token ->
                mapping.tokens.add(ComponentArtifactMappingTokenEntity(mapping = mapping, artifactPattern = token, sortOrder = i))
            }
    }
    this.artifactMappings.add(mapping)
}
