package org.octopusden.octopus.components.registry.server.mapper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.dto.v4.ConfigurationRowType
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionDockerImageEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionFileUrlArtifactEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionMavenArtifactEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionPackageEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
import java.util.UUID

/**
 * Phase 6 — distribution child-entity mapping rewritten for schema v2.
 *
 * `DistributionEntity` (schema v1) was retired. Per-family child entities
 * (`DistributionMavenArtifactEntity`, `DistributionFileUrlArtifactEntity`,
 * `DistributionDockerImageEntity`, `DistributionPackageEntity`) are now
 * FK-children of `ComponentConfigurationEntity`, and their `.toResponse()`
 * converters in V4Mappers are private.
 *
 * Tests exercise them indirectly via the public
 * `ComponentConfigurationEntity.toConfigurationResponse()`, covering:
 *
 *  - BASE row: all four distribution families surfaced in response
 *  - MARKER rows: only the matching family is surfaced; others are empty
 *  - sortOrder preservation (entities added in reverse order → response sorted)
 *  - Blank / null optional fields (extension, classifier, flavor, artifactId)
 *  - MARKER isolation: a VCS-settings marker suppresses all distribution families
 */
class DistributionEntityMapperTest {

    // -----------------------------------------------------------------------
    // Fixture builders
    // -----------------------------------------------------------------------

    private fun minimalComponent(): ComponentEntity =
        ComponentEntity(
            id = UUID.randomUUID(),
            componentKey = "test-component",
        )

    /** BASE row — all child families allowed. */
    private fun baseConfig(): ComponentConfigurationEntity =
        ComponentConfigurationEntity(
            id = UUID.randomUUID(),
            component = minimalComponent(),
            versionRange = "(,0),[0,)",
            overriddenAttribute = null,
            rowType = "BASE",
        )

    /** MARKER row — only the child family matching `markerName` is surfaced. */
    private fun markerConfig(markerName: String): ComponentConfigurationEntity =
        ComponentConfigurationEntity(
            id = UUID.randomUUID(),
            component = minimalComponent(),
            versionRange = "[2,3)",
            overriddenAttribute = markerName,
            rowType = "MARKER",
        )

    // -----------------------------------------------------------------------
    // Maven artifacts
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("BASE row: maven artifact fields round-trip to MavenArtifactResponse")
    fun baseRow_mavenArtifact_fieldsRoundTrip() {
        val cfg = baseConfig()
        val maven = DistributionMavenArtifactEntity(
            id = UUID.randomUUID(),
            componentConfiguration = cfg,
            groupPattern = "org.example",
            artifactPattern = "service",
            extension = "zip",
            classifier = "appserv",
            sortOrder = 0,
        )
        cfg.mavenArtifacts.add(maven)

        val cr = cfg.toConfigurationResponse()
        assertEquals(ConfigurationRowType.BASE, cr.rowType)
        assertEquals(1, cr.mavenArtifacts.size)
        val m = cr.mavenArtifacts[0]
        assertEquals(maven.id, m.id)
        assertEquals("org.example", m.groupPattern)
        assertEquals("service", m.artifactPattern)
        assertEquals("zip", m.extension)
        assertEquals("appserv", m.classifier)
        assertEquals(0, m.sortOrder)
    }

    @Test
    @DisplayName("BASE row: maven artifact without extension/classifier → null in response")
    fun baseRow_mavenArtifact_optionalFieldsNull() {
        val cfg = baseConfig()
        cfg.mavenArtifacts.add(
            DistributionMavenArtifactEntity(
                id = UUID.randomUUID(),
                componentConfiguration = cfg,
                groupPattern = "org.example",
                artifactPattern = "svc",
                sortOrder = 0,
            ),
        )

        val m = cfg.toConfigurationResponse().mavenArtifacts[0]
        assertNull(m.extension)
        assertNull(m.classifier)
    }

    @Test
    @DisplayName("MARKER distribution.maven: mavenArtifacts surfaced; fileUrl, docker, packages empty")
    fun markerRow_distributionMaven_onlyMavenSurfaced() {
        val cfg = markerConfig(MarkerAttributes.DISTRIBUTION_MAVEN)
        cfg.mavenArtifacts.add(
            DistributionMavenArtifactEntity(
                id = UUID.randomUUID(),
                componentConfiguration = cfg,
                groupPattern = "org.example",
                artifactPattern = "svc",
                sortOrder = 0,
            ),
        )

        val cr = cfg.toConfigurationResponse()
        assertEquals(ConfigurationRowType.MARKER, cr.rowType)
        assertEquals(1, cr.mavenArtifacts.size)
        assertTrue(cr.fileUrlArtifacts.isEmpty())
        assertTrue(cr.dockerImages.isEmpty())
        assertTrue(cr.packages.isEmpty())
        assertTrue(cr.vcsEntries.isEmpty())
    }

    @Test
    @DisplayName("maven artifacts sortOrder preserved (sorted ascending)")
    fun mavenArtifacts_sortOrderPreserved() {
        val cfg = baseConfig()
        cfg.mavenArtifacts.add(
            DistributionMavenArtifactEntity(
                id = UUID.randomUUID(), componentConfiguration = cfg,
                groupPattern = "org.b", artifactPattern = "b", sortOrder = 2,
            ),
        )
        cfg.mavenArtifacts.add(
            DistributionMavenArtifactEntity(
                id = UUID.randomUUID(), componentConfiguration = cfg,
                groupPattern = "org.a", artifactPattern = "a", sortOrder = 1,
            ),
        )

        val artifacts = cfg.toConfigurationResponse().mavenArtifacts
        assertEquals(listOf("org.a", "org.b"), artifacts.map { it.groupPattern })
        assertEquals(listOf(1, 2), artifacts.map { it.sortOrder })
    }

    // -----------------------------------------------------------------------
    // File URL artifacts
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("BASE row: file URL artifact fields round-trip to FileUrlArtifactResponse")
    fun baseRow_fileUrlArtifact_fieldsRoundTrip() {
        val cfg = baseConfig()
        val fileUrl = DistributionFileUrlArtifactEntity(
            id = UUID.randomUUID(),
            componentConfiguration = cfg,
            url = "https://releases.example.com/svc-1.0.zip",
            artifactId = "svc",
            classifier = "dist",
            sortOrder = 0,
        )
        cfg.fileUrlArtifacts.add(fileUrl)

        val cr = cfg.toConfigurationResponse()
        assertEquals(1, cr.fileUrlArtifacts.size)
        val f = cr.fileUrlArtifacts[0]
        assertEquals(fileUrl.id, f.id)
        assertEquals("https://releases.example.com/svc-1.0.zip", f.url)
        assertEquals("svc", f.artifactId)
        assertEquals("dist", f.classifier)
        assertEquals(0, f.sortOrder)
    }

    @Test
    @DisplayName("BASE row: file URL artifact without artifactId/classifier → null in response")
    fun baseRow_fileUrlArtifact_optionalFieldsNull() {
        val cfg = baseConfig()
        cfg.fileUrlArtifacts.add(
            DistributionFileUrlArtifactEntity(
                id = UUID.randomUUID(),
                componentConfiguration = cfg,
                url = "https://releases.example.com/svc-1.0.zip",
                sortOrder = 0,
            ),
        )

        val f = cfg.toConfigurationResponse().fileUrlArtifacts[0]
        assertNull(f.artifactId)
        assertNull(f.classifier)
    }

    @Test
    @DisplayName("MARKER distribution.fileUrl: fileUrlArtifacts surfaced; maven, docker, packages empty")
    fun markerRow_distributionFileUrl_onlyFileUrlSurfaced() {
        val cfg = markerConfig(MarkerAttributes.DISTRIBUTION_FILE_URL)
        cfg.fileUrlArtifacts.add(
            DistributionFileUrlArtifactEntity(
                id = UUID.randomUUID(),
                componentConfiguration = cfg,
                url = "https://example.com/file.zip",
                sortOrder = 0,
            ),
        )

        val cr = cfg.toConfigurationResponse()
        assertEquals(ConfigurationRowType.MARKER, cr.rowType)
        assertEquals(1, cr.fileUrlArtifacts.size)
        assertTrue(cr.mavenArtifacts.isEmpty())
        assertTrue(cr.dockerImages.isEmpty())
        assertTrue(cr.packages.isEmpty())
    }

    // -----------------------------------------------------------------------
    // Docker images
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("BASE row: docker image fields round-trip to DockerImageResponse")
    fun baseRow_dockerImage_fieldsRoundTrip() {
        val cfg = baseConfig()
        val docker = DistributionDockerImageEntity(
            id = UUID.randomUUID(),
            componentConfiguration = cfg,
            imageName = "docker.example.com/myapp",
            flavor = "amazon",
            sortOrder = 0,
        )
        cfg.dockerImages.add(docker)

        val cr = cfg.toConfigurationResponse()
        assertEquals(1, cr.dockerImages.size)
        val d = cr.dockerImages[0]
        assertEquals(docker.id, d.id)
        assertEquals("docker.example.com/myapp", d.imageName)
        assertEquals("amazon", d.flavor)
        assertEquals(0, d.sortOrder)
    }

    @Test
    @DisplayName("docker image with null flavor → flavor null in response")
    fun dockerImage_nullFlavor_flavorNullInResponse() {
        val cfg = baseConfig()
        cfg.dockerImages.add(
            DistributionDockerImageEntity(
                id = UUID.randomUUID(),
                componentConfiguration = cfg,
                imageName = "docker.example.com/myapp",
                flavor = null,
                sortOrder = 0,
            ),
        )

        val d = cfg.toConfigurationResponse().dockerImages[0]
        assertNull(d.flavor)
    }

    @Test
    @DisplayName("MARKER distribution.docker: dockerImages surfaced; maven, fileUrl, packages empty")
    fun markerRow_distributionDocker_onlyDockerSurfaced() {
        val cfg = markerConfig(MarkerAttributes.DISTRIBUTION_DOCKER)
        cfg.dockerImages.add(
            DistributionDockerImageEntity(
                id = UUID.randomUUID(),
                componentConfiguration = cfg,
                imageName = "myapp",
                sortOrder = 0,
            ),
        )

        val cr = cfg.toConfigurationResponse()
        assertEquals(1, cr.dockerImages.size)
        assertTrue(cr.mavenArtifacts.isEmpty())
        assertTrue(cr.fileUrlArtifacts.isEmpty())
        assertTrue(cr.packages.isEmpty())
    }

    @Test
    @DisplayName("docker images sortOrder preserved (sorted ascending)")
    fun dockerImages_sortOrderPreserved() {
        val cfg = baseConfig()
        cfg.dockerImages.add(
            DistributionDockerImageEntity(
                id = UUID.randomUUID(), componentConfiguration = cfg, imageName = "image-b", sortOrder = 2,
            ),
        )
        cfg.dockerImages.add(
            DistributionDockerImageEntity(
                id = UUID.randomUUID(), componentConfiguration = cfg, imageName = "image-a", sortOrder = 1,
            ),
        )

        val images = cfg.toConfigurationResponse().dockerImages
        assertEquals(listOf("image-a", "image-b"), images.map { it.imageName })
    }

    // -----------------------------------------------------------------------
    // Packages (DEB / RPM)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("BASE row: package fields round-trip to PackageResponse")
    fun baseRow_package_fieldsRoundTrip() {
        val cfg = baseConfig()
        val pkg = DistributionPackageEntity(
            id = UUID.randomUUID(),
            componentConfiguration = cfg,
            packageType = "DEB",
            packageName = "myapp",
            sortOrder = 0,
        )
        cfg.packages.add(pkg)

        val cr = cfg.toConfigurationResponse()
        assertEquals(1, cr.packages.size)
        val p = cr.packages[0]
        assertEquals(pkg.id, p.id)
        assertEquals("DEB", p.packageType)
        assertEquals("myapp", p.packageName)
        assertEquals(0, p.sortOrder)
    }

    @Test
    @DisplayName("MARKER distribution.packages: packages surfaced; maven, fileUrl, docker empty")
    fun markerRow_distributionPackages_onlyPackagesSurfaced() {
        val cfg = markerConfig(MarkerAttributes.DISTRIBUTION_PACKAGES)
        cfg.packages.add(
            DistributionPackageEntity(
                id = UUID.randomUUID(),
                componentConfiguration = cfg,
                packageType = "RPM",
                packageName = "myapp-rpm",
                sortOrder = 0,
            ),
        )

        val cr = cfg.toConfigurationResponse()
        assertEquals(1, cr.packages.size)
        assertEquals("RPM", cr.packages[0].packageType)
        assertTrue(cr.mavenArtifacts.isEmpty())
        assertTrue(cr.dockerImages.isEmpty())
        assertTrue(cr.fileUrlArtifacts.isEmpty())
    }

    @Test
    @DisplayName("packages sortOrder preserved (sorted ascending)")
    fun packages_sortOrderPreserved() {
        val cfg = baseConfig()
        cfg.packages.add(
            DistributionPackageEntity(
                id = UUID.randomUUID(), componentConfiguration = cfg,
                packageType = "DEB", packageName = "pkg-b", sortOrder = 2,
            ),
        )
        cfg.packages.add(
            DistributionPackageEntity(
                id = UUID.randomUUID(), componentConfiguration = cfg,
                packageType = "DEB", packageName = "pkg-a", sortOrder = 1,
            ),
        )

        val packages = cfg.toConfigurationResponse().packages
        assertEquals(listOf("pkg-a", "pkg-b"), packages.map { it.packageName })
    }

    // -----------------------------------------------------------------------
    // MARKER isolation: wrong-family MARKER suppresses all other child families
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("MARKER vcs.settings: all distribution families empty even if entity has children")
    fun markerRow_vcsSettings_allDistributionFamiliesEmpty() {
        val cfg = markerConfig(MarkerAttributes.VCS_SETTINGS)
        cfg.vcsEntries.add(
            VcsSettingsEntryEntity(
                id = UUID.randomUUID(),
                componentConfiguration = cfg,
                name = "main",
                vcsPath = "org/repo",
                sortOrder = 0,
            ),
        )
        // Add maven artifact directly on the entity — must NOT appear in response
        // because the marker key is VCS_SETTINGS, not DISTRIBUTION_MAVEN.
        cfg.mavenArtifacts.add(
            DistributionMavenArtifactEntity(
                id = UUID.randomUUID(),
                componentConfiguration = cfg,
                groupPattern = "org.x",
                artifactPattern = "y",
                sortOrder = 0,
            ),
        )

        val cr = cfg.toConfigurationResponse()
        assertEquals(1, cr.vcsEntries.size)
        assertTrue(cr.mavenArtifacts.isEmpty())
        assertTrue(cr.fileUrlArtifacts.isEmpty())
        assertTrue(cr.dockerImages.isEmpty())
        assertTrue(cr.packages.isEmpty())
    }
}
