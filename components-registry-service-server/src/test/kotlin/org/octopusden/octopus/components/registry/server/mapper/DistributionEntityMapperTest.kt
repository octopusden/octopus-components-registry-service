package org.octopusden.octopus.components.registry.server.mapper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.entity.DistributionArtifactEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionEntity

/**
 * SYS-030 — `DistributionEntity.toDistribution()` must reconstruct the original GAV
 * string verbatim for every shape the migration mapper writes.
 *
 * The read-back builds the GAV as
 * `"${groupPattern}:${artifactPattern}"` plus optional `:extension` / `:classifier`.
 * Kotlin string templates render `null` as the literal `"null"`, so groupId-only
 * GAVs like `"org.example.teamcity.ee"` round-tripped as
 * `"org.example.teamcity.ee:null"` — which caused the maven-crm-plugin
 * `set-distribution-parameters` mojo to emit `value='...ee:null'` and broke the
 * downstream releng IT (build 8.5138 — SetDistributionParametersTest).
 *
 * These tests drive the fix; the symmetric multi-segment cases are here as
 * regression anchors so the fix does not tighten only the null branch.
 */
class DistributionEntityMapperTest {
    private fun distributionWithGavArtifact(artifact: DistributionArtifactEntity): DistributionEntity {
        val entity = DistributionEntity(explicit = true, external = true)
        artifact.distribution = entity
        entity.artifacts.add(artifact)
        return entity
    }

    @Test
    @DisplayName("SYS-030: groupId-only GAV (e.g. `org.example`) must NOT round-trip to `org.example:null`")
    fun groupIdOnlyGav_roundtrip() {
        val entity =
            distributionWithGavArtifact(
                DistributionArtifactEntity(
                    artifactType = "GAV",
                    groupPattern = "org.example.teamcity.ee",
                    artifactPattern = null,
                    extension = null,
                    classifier = null,
                ),
            )

        val distribution = entity.toDistribution()

        assertEquals("org.example.teamcity.ee", distribution.GAV())
    }

    @Test
    @DisplayName("SYS-030: group:artifact GAV round-trips verbatim")
    fun groupArtifactGav_roundtrip() {
        val entity =
            distributionWithGavArtifact(
                DistributionArtifactEntity(
                    artifactType = "GAV",
                    groupPattern = "org.example",
                    artifactPattern = "service",
                ),
            )

        assertEquals("org.example:service", entity.toDistribution().GAV())
    }

    @Test
    @DisplayName("SYS-030: group:artifact:extension GAV round-trips verbatim")
    fun groupArtifactExtensionGav_roundtrip() {
        val entity =
            distributionWithGavArtifact(
                DistributionArtifactEntity(
                    artifactType = "GAV",
                    groupPattern = "org.example",
                    artifactPattern = "service",
                    extension = "zip",
                ),
            )

        assertEquals("org.example:service:zip", entity.toDistribution().GAV())
    }

    @Test
    @DisplayName("SYS-030: group:artifact:extension:classifier GAV round-trips verbatim")
    fun fullGav_roundtrip() {
        val entity =
            distributionWithGavArtifact(
                DistributionArtifactEntity(
                    artifactType = "GAV",
                    groupPattern = "org.example",
                    artifactPattern = "service",
                    extension = "zip",
                    classifier = "appserv",
                ),
            )

        assertEquals("org.example:service:zip:appserv", entity.toDistribution().GAV())
    }

    @Test
    @DisplayName("SYS-030: multi-GAV stored as raw name round-trips verbatim")
    fun multiGav_roundtrip() {
        val raw = "org.example:service:war,org.example:service:tgz"
        val entity =
            distributionWithGavArtifact(
                DistributionArtifactEntity(
                    artifactType = "GAV",
                    name = raw,
                ),
            )

        assertEquals(raw, entity.toDistribution().GAV())
    }
}
