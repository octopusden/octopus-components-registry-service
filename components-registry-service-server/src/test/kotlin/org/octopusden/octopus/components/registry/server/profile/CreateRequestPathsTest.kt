package org.octopusden.octopus.components.registry.server.profile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.octopusden.octopus.components.registry.server.dto.v4.ArtifactIdRequest
import org.octopusden.octopus.components.registry.server.dto.v4.BaseConfigurationRequest
import org.octopusden.octopus.components.registry.server.dto.v4.BuildAspectRequest
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentCreateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.DockerImageRequest
import org.octopusden.octopus.components.registry.server.dto.v4.JiraAspectRequest
import org.octopusden.octopus.components.registry.server.dto.v4.MavenArtifactRequest
import org.octopusden.octopus.components.registry.server.dto.v4.PackageRequest
import org.octopusden.octopus.components.registry.server.dto.v4.VcsEntryRequest

class CreateRequestPathsTest {
    private val full =
        ComponentCreateRequest(
            name = "payments-solution",
            displayName = "Payments Solution",
            clientCode = "BRI",
            artifactIds = listOf(ArtifactIdRequest(groupPattern = "org.example.payments")),
            baseConfiguration =
                BaseConfigurationRequest(
                    build = BuildAspectRequest(buildTasks = "build publish"),
                    vcsEntries = listOf(VcsEntryRequest(vcsPath = "ssh://git@host/pay.git", branch = "main", tag = "v1")),
                    jira =
                        JiraAspectRequest(
                            projectKey = "PAY",
                            versionPrefix = "payments",
                            versionFormat = "full",
                            lineVersionFormat = "line",
                            minorVersionFormat = "minor",
                            releaseVersionFormat = "release",
                            buildVersionFormat = "build",
                        ),
                    mavenArtifacts = listOf(MavenArtifactRequest(groupPattern = "org.example.maven", artifactPattern = "pay-.*")),
                    dockerImages = listOf(DockerImageRequest(imageName = "payments/api", flavor = "alpine")),
                    packages = listOf(PackageRequest(packageType = "DEB", packageName = "payments-deb")),
                ),
        )

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "name, payments-solution",
        "displayName, Payments Solution",
        "clientCode, BRI",
        "artifactIds[0].groupPattern, org.example.payments",
        "baseConfiguration.build.buildTasks, build publish",
        "baseConfiguration.vcsEntries[0].vcsPath, ssh://git@host/pay.git",
        "baseConfiguration.vcsEntries[0].branch, main",
        "baseConfiguration.vcsEntries[0].tag, v1",
        "baseConfiguration.jira.projectKey, PAY",
        "baseConfiguration.jira.versionPrefix, payments",
        "baseConfiguration.jira.versionFormat, full",
        "baseConfiguration.jira.lineVersionFormat, line",
        "baseConfiguration.jira.minorVersionFormat, minor",
        "baseConfiguration.jira.releaseVersionFormat, release",
        "baseConfiguration.jira.buildVersionFormat, build",
        "baseConfiguration.mavenArtifacts[0].groupPattern, org.example.maven",
        "baseConfiguration.mavenArtifacts[0].artifactPattern, pay-.*",
        "baseConfiguration.dockerImages[0].imageName, payments/api",
        "baseConfiguration.dockerImages[0].flavor, alpine",
        "baseConfiguration.packages[0].packageName, payments-deb",
    )
    @DisplayName("every rule path reads its value from a create request")
    fun readsEveryPath(
        path: String,
        expected: String,
    ) {
        assertEquals(expected, CreateRequestPaths.read(full, path))
    }

    @Test
    @DisplayName("the path list is exactly the paths a rule may name")
    fun pathListMatchesReader() {
        assertEquals(20, CreateRequestPaths.PATHS.size)
        CreateRequestPaths.PATHS.forEach { CreateRequestPaths.read(full, it) }
    }

    @Test
    @DisplayName("a path whose value or parent is absent reads as null")
    fun absentValueIsNull() {
        val bare = ComponentCreateRequest(name = "payments")

        assertNull(CreateRequestPaths.read(bare, "displayName"))
        assertNull(CreateRequestPaths.read(bare, "artifactIds[0].groupPattern"))
        assertNull(CreateRequestPaths.read(bare, "baseConfiguration.jira.projectKey"))
        assertNull(CreateRequestPaths.read(bare, "baseConfiguration.vcsEntries[0].branch"))
    }

    @Test
    @DisplayName("a path outside the list is refused")
    fun unknownPathRefused() {
        val error = runCatching { CreateRequestPaths.read(full, "componentOwner") }.exceptionOrNull()

        requireNotNull(error) { "reading a path outside the list should fail" }
    }
}
