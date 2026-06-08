package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.config.AdminConfigProperties
import org.octopusden.octopus.components.registry.server.entity.RegistryConfigEntity
import org.octopusden.octopus.components.registry.server.repository.RegistryConfigRepository
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

/**
 * Unit coverage for [ConfigSyncService] serialization + validation + no-clobber.
 * Uses a stubbed [RegistryConfigRepository] and a hand-populated
 * [AdminConfigProperties]; no Spring context.
 */
class ConfigSyncServiceTest {
    private val repo = mock(RegistryConfigRepository::class.java)

    private fun service(props: AdminConfigProperties) = ConfigSyncService(props, repo)

    private fun fieldEntry(
        visibility: String? = null,
        searchable: String? = null,
        required: Boolean? = null,
        defaultValue: String? = null,
    ) = AdminConfigProperties.FieldEntry().apply {
        this.visibility = visibility
        this.searchable = searchable
        this.required = required
        this.defaultValue = defaultValue
    }

    @Test
    fun `field-config serializes to the legacy nested-map shape and normalizes visibility`() {
        val props = AdminConfigProperties().apply {
            fieldConfig = mutableMapOf(
                "component" to mutableMapOf(
                    "displayName" to fieldEntry(visibility = " Hidden ", searchable = "Main", required = false),
                    "groupId" to fieldEntry(visibility = "editable", defaultValue = "com.acme"),
                ),
            )
        }

        val result = service(props).syncToCache()

        assertEquals(
            mapOf(
                "component" to mapOf(
                    "displayName" to mapOf("visibility" to "hidden", "searchable" to "Main", "required" to false),
                    "groupId" to mapOf("visibility" to "editable", "defaultValue" to "com.acme"),
                ),
            ),
            result.fieldConfig,
        )
    }

    @Test
    fun `invalid visibility aborts the sync`() {
        val props = AdminConfigProperties().apply {
            fieldConfig = mutableMapOf(
                "component" to mutableMapOf("displayName" to fieldEntry(visibility = "invisible")),
            )
        }
        assertThrows(ConfigValidationException::class.java) { service(props).syncToCache() }
        verify(repo, never()).save(any())
    }

    @Test
    fun `invalid searchable aborts the sync`() {
        val props = AdminConfigProperties().apply {
            fieldConfig = mutableMapOf(
                "component" to mutableMapOf("displayName" to fieldEntry(searchable = "Sometimes")),
            )
        }
        assertThrows(ConfigValidationException::class.java) { service(props).syncToCache() }
        verify(repo, never()).save(any())
    }

    @Test
    fun `empty subtrees are not written - cache is preserved (no-clobber)`() {
        service(AdminConfigProperties()).syncToCache()

        // Both blobs empty → nothing is written, so a missing profile section
        // never blanks out known-good production policy in the cache.
        verify(repo, never()).save(any())
    }

    @Test
    fun `component-defaults serialize with full key set and upsert`() {
        val props = AdminConfigProperties().apply {
            componentDefaults = AdminConfigProperties.ComponentDefaults().apply {
                buildSystem = "MAVEN"
                buildFilePath = "pom.xml"
                labels = mutableListOf("core", "lib")
                build = AdminConfigProperties.Build().apply {
                    javaVersion = "17"
                    requiredProject = true
                }
                jira = AdminConfigProperties.Jira().apply {
                    projectKey = "PROJ"
                    technical = false
                    componentVersionFormat = AdminConfigProperties.Jira.ComponentVersionFormat().apply {
                        majorVersionFormat = "\$major.\$minor"
                    }
                }
                distribution = AdminConfigProperties.Distribution().apply {
                    explicit = false
                    securityGroups = AdminConfigProperties.Distribution.SecurityGroups().apply { read = "Prod" }
                }
            }
        }

        val result = service(props).syncComponentDefaults()

        assertEquals(
            mapOf(
                "buildSystem" to "MAVEN",
                "buildFilePath" to "pom.xml",
                "labels" to listOf("core", "lib"),
                "build" to mapOf("javaVersion" to "17", "requiredProject" to true),
                "jira" to mapOf(
                    "projectKey" to "PROJ",
                    "technical" to false,
                    "componentVersionFormat" to mapOf("majorVersionFormat" to "\$major.\$minor"),
                ),
                "distribution" to mapOf("explicit" to false, "securityGroups" to mapOf("read" to "Prod")),
            ),
            result,
        )
        verify(repo).save(any(RegistryConfigEntity::class.java))
        verify(repo).findById(eq("component-defaults"))
    }

    /**
     * [P1-schema] Golden parity: the serializer must emit the FULL key set that the
     * legacy Groovy `migrateDefaults()` produced (top-level + build/jira/distribution/
     * vcs/escrow/doc). Populate every field and pin the complete expected map so a
     * future field drop (narrowing) goes red. Values mirror the components-registry
     * `Defaults.groovy` migration data (the system is not yet in prod).
     */
    @Test
    fun `component-defaults golden - every field of every section serializes`() {
        val props = AdminConfigProperties().apply {
            componentDefaults = AdminConfigProperties.ComponentDefaults().apply {
                buildSystem = "MAVEN"
                buildFilePath = "pom.xml"
                artifactIdPattern = "ANY"
                groupIdPattern = "com.example"
                componentDisplayName = "Example"
                system = "NONE"
                clientCode = "CC"
                parentComponent = "parent"
                releasesInDefaultBranch = true
                solution = "SOL"
                archived = false
                copyright = "Copyright (c) 2026 Example"
                labels = mutableListOf("core", "lib")
                deprecated = false
                octopusVersion = "1.2.3"
                build = AdminConfigProperties.Build().apply {
                    javaVersion = "17"
                    mavenVersion = "3.9.6"
                    gradleVersion = "LATEST"
                    requiredProject = true
                    projectVersion = "1.0"
                    systemProperties = mutableMapOf("k" to "v")
                    buildTasks = "build"
                }
                jira = AdminConfigProperties.Jira().apply {
                    projectKey = "PROJ"
                    displayName = "Proj"
                    technical = false
                    componentVersionFormat = AdminConfigProperties.Jira.ComponentVersionFormat().apply {
                        majorVersionFormat = "\$major.\$minor"
                        releaseVersionFormat = "\$major.\$minor.\$service"
                        buildVersionFormat = "\$major.\$minor.\$service-\$build"
                        lineVersionFormat = "\$major.\$minor"
                        hotfixVersionFormat = "\$major.\$minor.\$service.\$fix"
                    }
                }
                distribution = AdminConfigProperties.Distribution().apply {
                    explicit = true
                    external = true
                    GAV = "g:a:v"
                    DEB = "deb"
                    RPM = "rpm"
                    docker = "img"
                    securityGroups = AdminConfigProperties.Distribution.SecurityGroups().apply { read = "Prod" }
                }
                vcs = AdminConfigProperties.Vcs().apply {
                    externalRegistry = "ext"
                    vcsPath = "git@x:y.git"
                    repositoryType = "GIT"
                    tag = "\$module-\$version"
                    branch = "master"
                }
                escrow = AdminConfigProperties.Escrow().apply {
                    buildTask = "task"
                    generation = "FULL"
                    reusable = true
                    diskSpace = "10G"
                    providedDependencies = mutableListOf("dep1")
                    additionalSources = mutableListOf("src1")
                }
                doc = AdminConfigProperties.Doc().apply {
                    component = "doc-comp"
                    majorVersion = "1"
                }
            }
        }

        val result = service(props).componentDefaultsMap()

        assertEquals(
            mapOf(
                "buildSystem" to "MAVEN",
                "buildFilePath" to "pom.xml",
                "artifactIdPattern" to "ANY",
                "groupIdPattern" to "com.example",
                "componentDisplayName" to "Example",
                "system" to "NONE",
                "clientCode" to "CC",
                "parentComponent" to "parent",
                "releasesInDefaultBranch" to true,
                "solution" to "SOL",
                "archived" to false,
                "copyright" to "Copyright (c) 2026 Example",
                "labels" to listOf("core", "lib"),
                "deprecated" to false,
                "octopusVersion" to "1.2.3",
                "build" to mapOf(
                    "javaVersion" to "17",
                    "mavenVersion" to "3.9.6",
                    "gradleVersion" to "LATEST",
                    "requiredProject" to true,
                    "projectVersion" to "1.0",
                    "systemProperties" to mapOf("k" to "v"),
                    "buildTasks" to "build",
                ),
                "jira" to mapOf(
                    "projectKey" to "PROJ",
                    "displayName" to "Proj",
                    "technical" to false,
                    "componentVersionFormat" to mapOf(
                        "majorVersionFormat" to "\$major.\$minor",
                        "releaseVersionFormat" to "\$major.\$minor.\$service",
                        "buildVersionFormat" to "\$major.\$minor.\$service-\$build",
                        "lineVersionFormat" to "\$major.\$minor",
                        "hotfixVersionFormat" to "\$major.\$minor.\$service.\$fix",
                    ),
                ),
                "distribution" to mapOf(
                    "explicit" to true,
                    "external" to true,
                    "GAV" to "g:a:v",
                    "DEB" to "deb",
                    "RPM" to "rpm",
                    "docker" to "img",
                    "securityGroups" to mapOf("read" to "Prod"),
                ),
                "vcs" to mapOf(
                    "externalRegistry" to "ext",
                    "vcsPath" to "git@x:y.git",
                    "repositoryType" to "GIT",
                    "tag" to "\$module-\$version",
                    "branch" to "master",
                ),
                "escrow" to mapOf(
                    "buildTask" to "task",
                    "generation" to "FULL",
                    "reusable" to true,
                    "diskSpace" to "10G",
                    "providedDependencies" to listOf("dep1"),
                    "additionalSources" to listOf("src1"),
                ),
                "doc" to mapOf("component" to "doc-comp", "majorVersion" to "1"),
            ),
            result,
        )
    }
}
