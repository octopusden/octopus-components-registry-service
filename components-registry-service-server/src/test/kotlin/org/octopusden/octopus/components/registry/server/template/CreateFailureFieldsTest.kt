package org.octopusden.octopus.components.registry.server.template

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

private const val MAVEN_GROUP = "baseConfiguration.mavenArtifacts[0].groupPattern"
private const val MAVEN_ARTIFACT = "baseConfiguration.mavenArtifacts[0].artifactPattern"
private const val ARTIFACT_GROUP = "artifactIds[0].groupPattern"
private val ARTIFACT_IDS = listOf(ARTIFACT_GROUP, "artifactIds[0].mode", "artifactIds[0].artifactTokens")

class CreateFailureFieldsTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("messages")
    @DisplayName("Decision 9: each message today's create emits is attributed to the template paths it concerns")
    fun attributed(
        message: String,
        paths: List<String>,
    ) {
        assertEquals(paths, CreateFailureFields.of(message))
    }

    @Test
    @DisplayName("Decision 9: a message no rule matches is attributed to no field")
    fun unmatched() {
        assertEquals(emptyList<String>(), CreateFailureFields.of("Invalid version range: '[1,'"))
        assertEquals(emptyList<String>(), CreateFailureFields.of("docs: referenced doc component 'x' does not exist (component 'y')"))
        assertEquals(emptyList<String>(), CreateFailureFields.of(""))
    }

    companion object {
        @JvmStatic
        fun messages(): List<Arguments> =
            listOf(
                Arguments.of("name: a component with name 'acme-plugin-core' already exists", listOf("name")),
                Arguments.of("name must not be blank", listOf("name")),
                Arguments.of(
                    "name: 'Acme' must match '^[a-z][a-z0-9-]*\$', or be the lowercased clientCode followed by end-of-name or '-'",
                    listOf("name"),
                ),
                Arguments.of("displayName: a component with display name 'CORE for ACME' already exists", listOf("displayName")),
                Arguments.of(
                    "displayName: componentDisplayName is required for an explicit+external component (component 'x')",
                    listOf("displayName"),
                ),
                Arguments.of("componentOwner is required and must not be blank", listOf("componentOwner")),
                Arguments.of("componentOwner 'jdoe' is not an active employee", listOf("componentOwner")),
                Arguments.of("releaseManager is required when distribution is explicit and external", listOf("releaseManager")),
                Arguments.of("securityChampion 'x-y' is not a valid username (must match ^\\w+\$)", listOf("securityChampion")),
                Arguments.of("clientCode 'acme' does not match the required pattern '[A-Z_0-9]+'", listOf("clientCode")),
                Arguments.of("copyright 'other' is not supported. Available values are [standard]", listOf("copyright")),
                Arguments.of(
                    "copyright must not be blank for an explicit+external component when copyright-path is configured",
                    listOf("copyright"),
                ),
                Arguments.of("labels: cannot consist entirely of blank entries", listOf("labels")),
                Arguments.of("artifactIds: invalid group 'org.*' — letters, digits, . _ - only (no wildcards or regex)", ARTIFACT_IDS),
                Arguments.of(
                    "artifactIds: 'Specific artifacts' (EXPLICIT) requires at least one artifact token",
                    ARTIFACT_IDS,
                ),
                Arguments.of("distribution.mavenArtifacts: groupPattern must not be blank", listOf(MAVEN_GROUP, MAVEN_ARTIFACT)),
                Arguments.of(
                    "distribution: an explicit+external component must define at least one distribution coordinate " +
                        "(maven GAV, docker image, package, or generic artifact) (component 'x')",
                    listOf(
                        MAVEN_GROUP,
                        MAVEN_ARTIFACT,
                        "baseConfiguration.dockerImages[0].imageName",
                        "baseConfiguration.packages[0].packageName",
                        ARTIFACT_GROUP,
                    ),
                ),
                Arguments.of(
                    "groupId: 'net.example' does not start with a supported prefix (org.example) (component 'x')",
                    listOf(MAVEN_GROUP, ARTIFACT_GROUP),
                ),
                Arguments.of(
                    "baseConfiguration.build.buildSystem is required and must not be blank",
                    listOf("baseConfiguration.build.buildSystem"),
                ),
                Arguments.of(
                    "Invalid build.buildSystem: 'ANT'. Allowed: [GRADLE, MAVEN]",
                    listOf("baseConfiguration.build.buildSystem"),
                ),
                Arguments.of(
                    "Invalid escrow.generation: 'NEVER'. Allowed: [AUTO, MANUAL]",
                    listOf("baseConfiguration.escrow.generation"),
                ),
                Arguments.of(
                    "Invalid package.packageType: 'ZIP'. Allowed: [RPM, DEB]",
                    listOf("baseConfiguration.packages[0].packageType"),
                ),
                Arguments.of(
                    "uniqueness violation: jira project 'PLUGINS' with version prefix 'acme-plugin-core' is already used by " +
                        "non-archived component(s) acme-old (conflicts with 'acme-plugin-core')",
                    listOf("baseConfiguration.jira.projectKey", "baseConfiguration.jira.versionPrefix"),
                ),
                Arguments.of(
                    "uniqueness violation: docker image name 'acme/core' of component 'x' is already used by component(s) y " +
                        "— image names must be globally unique",
                    listOf("baseConfiguration.dockerImages[0].imageName"),
                ),
                Arguments.of(
                    "uniqueness violation: maven artifact 'org.example:core' of component 'x' duplicates the maven artifact " +
                        "of component 'y' in intersecting version ranges",
                    listOf(MAVEN_GROUP, MAVEN_ARTIFACT, ARTIFACT_GROUP),
                ),
                Arguments.of("Field 'component.clientCode' is not editable", listOf("clientCode")),
                Arguments.of("Field 'jira.projectKey' is editable by administrators only", listOf("baseConfiguration.jira.projectKey")),
                Arguments.of("Field 'build.buildSystem' is not editable", listOf("baseConfiguration.build.buildSystem")),
                Arguments.of("Field 'escrow.generation' is not editable", listOf("baseConfiguration.escrow.generation")),
            )
    }
}
