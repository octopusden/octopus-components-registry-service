package org.octopusden.octopus.components.registry.server.teamcity

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityBuildType as ExternalTeamcityBuildType
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityBuildTypes as ExternalTeamcityBuildTypes
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProject as ExternalTeamcityProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProjects as ExternalTeamcityProjects
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProperties as ExternalTeamcityProperties
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProperty as ExternalTeamcityProperty
import java.util.UUID

/**
 * Unit tests for the pure mapper inside [ExternalTcProjectFetcher]. The HTTP side
 * is exercised separately by `teamcity-client/TeamcityClassicClientTest` against a
 * real TC; here we cover only the mapping rules CRS layers on top.
 */
class ExternalTcProjectFetcherTest {

    private val fooUuid = UUID.randomUUID()
    private val barUuid = UUID.randomUUID()
    private val cdReleaseTemplateId = "CDRelease"

    @Test
    @DisplayName("empty input -> empty output")
    fun emptyInputEmptyOutput() {
        val result = mapTcProjectsToComponentMatches(emptyList(), mapOf("foo" to fooUuid), cdReleaseTemplateId)
        assertTrue(result.isEmpty())
    }

    @Test
    @DisplayName("project with known COMPONENT_NAME maps to UUID")
    fun knownComponentNameMaps() {
        val projects = listOf(tcProject(id = "Tc_Foo", webUrl = "http://tc/foo", componentName = "foo"))
        val result = mapTcProjectsToComponentMatches(projects, mapOf("foo" to fooUuid), cdReleaseTemplateId)
        assertEquals(
            mapOf(fooUuid to listOf(TcProject(id = "Tc_Foo", webUrl = "http://tc/foo", hasCdReleaseBuild = false))),
            result,
        )
    }

    @Test
    @DisplayName("project whose COMPONENT_NAME is unknown to CRS is dropped")
    fun unknownComponentNameDropped() {
        val projects = listOf(tcProject(id = "Tc_Stranger", webUrl = "http://tc/x", componentName = "not-in-registry"))
        val result = mapTcProjectsToComponentMatches(projects, mapOf("foo" to fooUuid), cdReleaseTemplateId)
        assertTrue(result.isEmpty())
    }

    @Test
    @DisplayName("project without parameters field is dropped")
    fun missingParametersFieldDropped() {
        val projects = listOf(
            ExternalTeamcityProject(id = "Tc_Empty", name = "Empty", href = "/x", webUrl = "http://tc/empty"),
        )
        val result = mapTcProjectsToComponentMatches(projects, mapOf("foo" to fooUuid), cdReleaseTemplateId)
        assertTrue(result.isEmpty())
    }

    @Test
    @DisplayName("project with parameters but no COMPONENT_NAME among them is dropped")
    fun parametersWithoutComponentNameDropped() {
        val projects = listOf(
            ExternalTeamcityProject(
                id = "Tc_Other",
                name = "Other",
                href = "/x",
                webUrl = "http://tc/other",
                parameters = ExternalTeamcityProperties(properties = mutableListOf(ExternalTeamcityProperty(name = "OTHER", value = "y"))),
            ),
        )
        val result = mapTcProjectsToComponentMatches(projects, mapOf("foo" to fooUuid), cdReleaseTemplateId)
        assertTrue(result.isEmpty())
    }

    @Test
    @DisplayName("project with whitespace-only COMPONENT_NAME value is dropped")
    fun whitespaceOnlyValueDropped() {
        val projects = listOf(tcProject(id = "Tc_Blank", webUrl = "http://tc/blank", componentName = "   "))
        val result = mapTcProjectsToComponentMatches(projects, mapOf("foo" to fooUuid), cdReleaseTemplateId)
        assertTrue(result.isEmpty())
    }

    @Test
    @DisplayName("two projects with the same COMPONENT_NAME are grouped under one UUID")
    fun twoProjectsSameNameGrouped() {
        val projects = listOf(
            tcProject(id = "Tc_Foo_A", webUrl = "http://tc/foo-a", componentName = "foo"),
            tcProject(id = "Tc_Foo_B", webUrl = "http://tc/foo-b", componentName = "foo"),
        )
        val result = mapTcProjectsToComponentMatches(projects, mapOf("foo" to fooUuid), cdReleaseTemplateId)
        assertEquals(1, result.size)
        assertEquals(2, result[fooUuid]!!.size)
        assertEquals(setOf("Tc_Foo_A", "Tc_Foo_B"), result[fooUuid]!!.map { it.id }.toSet())
    }

    @Test
    @DisplayName("value with surrounding whitespace is trimmed before matching")
    fun whitespaceTrimmedBeforeMatching() {
        val projects = listOf(tcProject(id = "Tc_Foo", webUrl = "http://tc/foo", componentName = "  foo  "))
        val result = mapTcProjectsToComponentMatches(projects, mapOf("foo" to fooUuid), cdReleaseTemplateId)
        assertEquals(
            mapOf(fooUuid to listOf(TcProject(id = "Tc_Foo", webUrl = "http://tc/foo", hasCdReleaseBuild = false))),
            result,
        )
    }

    @Test
    @DisplayName("when a project has multiple COMPONENT_NAME entries, the first one wins")
    fun multipleComponentNameEntriesFirstWins() {
        // TC shouldn't return more than one COMPONENT_NAME per project (parameter names are
        // unique on a TC project), but pin the first-wins semantics so any future change to
        // multi-value handling is caught.
        val projects = listOf(
            ExternalTeamcityProject(
                id = "Tc_Dup",
                name = "Dup",
                href = "/x",
                webUrl = "http://tc/dup",
                parameters = ExternalTeamcityProperties(
                    properties = mutableListOf(
                        ExternalTeamcityProperty(name = "COMPONENT_NAME", value = "foo"),
                        ExternalTeamcityProperty(name = "COMPONENT_NAME", value = "bar"),
                    ),
                ),
            ),
        )
        val result = mapTcProjectsToComponentMatches(
            projects,
            mapOf("foo" to fooUuid, "bar" to barUuid),
            cdReleaseTemplateId,
        )
        assertEquals(
            mapOf(fooUuid to listOf(TcProject(id = "Tc_Dup", webUrl = "http://tc/dup", hasCdReleaseBuild = false))),
            result,
        )
    }

    @Test
    @DisplayName("mixed input - known, unknown, blank, missing - only known survives")
    fun mixedInputOnlyKnownSurvives() {
        val projects = listOf(
            tcProject(id = "Tc_Foo", webUrl = "http://tc/foo", componentName = "foo"),
            tcProject(id = "Tc_Stranger", webUrl = "http://tc/x", componentName = "not-in-registry"),
            tcProject(id = "Tc_Bar", webUrl = "http://tc/bar", componentName = "bar"),
            tcProject(id = "Tc_Blank", webUrl = "http://tc/blank", componentName = ""),
            ExternalTeamcityProject(id = "Tc_NoParams", name = "NoParams", href = "/x", webUrl = "http://tc/np"),
        )
        val result = mapTcProjectsToComponentMatches(
            projects,
            mapOf("foo" to fooUuid, "bar" to barUuid),
            cdReleaseTemplateId,
        )
        assertEquals(2, result.size)
        assertEquals(listOf(TcProject(id = "Tc_Foo", webUrl = "http://tc/foo", hasCdReleaseBuild = false)), result[fooUuid])
        assertEquals(listOf(TcProject(id = "Tc_Bar", webUrl = "http://tc/bar", hasCdReleaseBuild = false)), result[barUuid])
    }

    @Test
    @DisplayName("CDRelease via legacy single template field is detected")
    fun cdReleaseDetectedViaLegacyTemplate() {
        val projects = listOf(
            tcProject(
                id = "Tc_Rel",
                webUrl = "http://tc/rel",
                componentName = "foo",
                buildTypes = listOf(buildType(id = "Build1", legacyTemplateId = "CDRelease")),
            ),
        )
        val result = mapTcProjectsToComponentMatches(projects, mapOf("foo" to fooUuid), cdReleaseTemplateId)
        assertEquals(true, result[fooUuid]!!.single().hasCdReleaseBuild)
    }

    @Test
    @DisplayName("CDRelease via plural templates link is detected")
    fun cdReleaseDetectedViaPluralTemplates() {
        val projects = listOf(
            tcProject(
                id = "Tc_Rel",
                webUrl = "http://tc/rel",
                componentName = "foo",
                buildTypes = listOf(buildType(id = "Build1", pluralTemplateIds = listOf("Other", "CDRelease"))),
            ),
        )
        val result = mapTcProjectsToComponentMatches(projects, mapOf("foo" to fooUuid), cdReleaseTemplateId)
        assertEquals(true, result[fooUuid]!!.single().hasCdReleaseBuild)
    }

    @Test
    @DisplayName("CDRelease present on any of multiple buildTypes flips the flag")
    fun cdReleaseAnyBuildType() {
        val projects = listOf(
            tcProject(
                id = "Tc_Rel",
                webUrl = "http://tc/rel",
                componentName = "foo",
                buildTypes = listOf(
                    buildType(id = "Build1", legacyTemplateId = "Unrelated"),
                    buildType(id = "Build2", legacyTemplateId = "CDRelease"),
                ),
            ),
        )
        val result = mapTcProjectsToComponentMatches(projects, mapOf("foo" to fooUuid), cdReleaseTemplateId)
        assertEquals(true, result[fooUuid]!!.single().hasCdReleaseBuild)
    }

    @Test
    @DisplayName("project with buildTypes but none inheriting from CDRelease has hasCdReleaseBuild=false")
    fun cdReleaseAbsentWhenUnrelatedTemplates() {
        val projects = listOf(
            tcProject(
                id = "Tc_Foo",
                webUrl = "http://tc/foo",
                componentName = "foo",
                buildTypes = listOf(
                    buildType(id = "Build1", legacyTemplateId = "Unrelated"),
                    buildType(id = "Build2", pluralTemplateIds = listOf("AnotherTemplate")),
                    buildType(id = "Build3"), // no template at all
                ),
            ),
        )
        val result = mapTcProjectsToComponentMatches(projects, mapOf("foo" to fooUuid), cdReleaseTemplateId)
        assertEquals(false, result[fooUuid]!!.single().hasCdReleaseBuild)
    }

    @Test
    @DisplayName("template id comparison honors a custom configured cdReleaseTemplateId")
    fun customTemplateIdHonored() {
        val projects = listOf(
            tcProject(
                id = "Tc_Rel",
                webUrl = "http://tc/rel",
                componentName = "foo",
                buildTypes = listOf(buildType(id = "Build1", legacyTemplateId = "CustomReleaseTpl")),
            ),
        )
        val result = mapTcProjectsToComponentMatches(projects, mapOf("foo" to fooUuid), cdReleaseTemplateId = "CustomReleaseTpl")
        assertEquals(true, result[fooUuid]!!.single().hasCdReleaseBuild)
    }

    @Test
    @DisplayName("Jackson round-trip: a TC response shaped exactly to PROJECT_FIELDS deserializes and maps cleanly")
    fun jacksonRoundTripWithFieldsSpec() {
        // Why this test:
        //   The other tests construct ExternalTeamcityProject via the Kotlin
        //   constructor (which fills in every required field). That bypasses
        //   Jackson and hides the failure mode where PROJECT_FIELDS forgets a
        //   required column on TeamcityBuildType (id/name/projectId/projectName/href
        //   are non-null on the library DTO). This test mimics what TC actually
        //   returns for our fields spec — only the listed columns are populated —
        //   and round-trips through the same ObjectMapper config the library
        //   itself uses, so a future spec narrowing surfaces here as a
        //   MissingKotlinParameterException-style failure rather than as a
        //   prod resync exploding with `0 returned`.
        //
        // The legacy `template` and plural `templates(buildType(...))` blocks
        // both populate all five required buildType columns, mirroring what
        // PROJECT_FIELDS asks TC for.
        val json = """
            {
              "project": [
                {
                  "id": "Tc_Foo",
                  "name": "Foo",
                  "webUrl": "http://tc/foo",
                  "href": "/app/rest/projects/Tc_Foo",
                  "parameters": {
                    "property": [{"name": "COMPONENT_NAME", "value": "foo"}]
                  },
                  "buildTypes": {
                    "buildType": [
                      {
                        "id": "Tc_Foo_Build",
                        "name": "Build",
                        "projectId": "Tc_Foo",
                        "projectName": "Foo",
                        "href": "/app/rest/buildTypes/id:Tc_Foo_Build",
                        "template": {
                          "id": "CDRelease",
                          "name": "CDRelease",
                          "projectId": "_Templates",
                          "projectName": "Templates",
                          "href": "/app/rest/buildTypes/id:CDRelease"
                        },
                        "templates": {
                          "buildType": [
                            {
                              "id": "CDRelease",
                              "name": "CDRelease",
                              "projectId": "_Templates",
                              "projectName": "Templates",
                              "href": "/app/rest/buildTypes/id:CDRelease"
                            }
                          ]
                        }
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
        // Mirror the library's getMapper() in TeamcityClassicClient.companion:
        // jacksonObjectMapper + FAIL_ON_UNKNOWN_PROPERTIES=false. That's what
        // hits the wire on real resyncs, so we deserialize through the same
        // configuration to make the test diagnostic of prod behaviour.
        val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val response = mapper.readValue(json, ExternalTeamcityProjects::class.java)
        val result = mapTcProjectsToComponentMatches(response.projects, mapOf("foo" to fooUuid), cdReleaseTemplateId)
        val tcProject = result[fooUuid]?.single() ?: error("expected exactly one match for foo")
        assertEquals("Tc_Foo", tcProject.id)
        assertEquals("http://tc/foo", tcProject.webUrl)
        assertEquals(true, tcProject.hasCdReleaseBuild)
    }

    @Test
    @DisplayName("PROJECT_VERSION parameter is parsed onto TcProject.projectVersion")
    fun projectVersionParsed() {
        val projects = listOf(
            tcProject(id = "Tc_Foo", webUrl = "http://tc/foo", componentName = "foo", projectVersion = "2.x"),
        )
        val result = mapTcProjectsToComponentMatches(projects, mapOf("foo" to fooUuid), cdReleaseTemplateId)
        assertEquals("2.x", result[fooUuid]!!.single().projectVersion)
    }

    @Test
    @DisplayName("project without PROJECT_VERSION has null projectVersion")
    fun projectVersionNullWhenAbsent() {
        val projects = listOf(tcProject(id = "Tc_Foo", webUrl = "http://tc/foo", componentName = "foo"))
        val result = mapTcProjectsToComponentMatches(projects, mapOf("foo" to fooUuid), cdReleaseTemplateId)
        assertEquals(null, result[fooUuid]!!.single().projectVersion)
    }

    @Test
    @DisplayName("PROJECT_VERSION falls back to the first non-paused buildType when absent on the project")
    fun projectVersionFallsBackToBuildType() {
        val projects = listOf(
            tcProject(
                id = "Tc_Foo",
                webUrl = "http://tc/foo",
                componentName = "foo",
                buildTypes = listOf(
                    // Paused buildType's version is ignored...
                    buildType(id = "Paused_1x", paused = true, projectVersion = "1.x"),
                    // ...first non-paused one wins.
                    buildType(id = "Active_2x", projectVersion = "2.x"),
                ),
            ),
        )
        val result = mapTcProjectsToComponentMatches(projects, mapOf("foo" to fooUuid), cdReleaseTemplateId)
        assertEquals("2.x", result[fooUuid]!!.single().projectVersion)
    }

    @Test
    @DisplayName("project-level PROJECT_VERSION takes precedence over any buildType value")
    fun projectVersionProjectLevelWins() {
        val projects = listOf(
            tcProject(
                id = "Tc_Foo",
                webUrl = "http://tc/foo",
                componentName = "foo",
                projectVersion = "1.x",
                buildTypes = listOf(buildType(id = "Active_2x", projectVersion = "2.x")),
            ),
        )
        val result = mapTcProjectsToComponentMatches(projects, mapOf("foo" to fooUuid), cdReleaseTemplateId)
        assertEquals("1.x", result[fooUuid]!!.single().projectVersion)
    }

    @Test
    @DisplayName("archived project is dropped even with a valid COMPONENT_NAME")
    fun archivedProjectDropped() {
        val projects = listOf(
            tcProject(id = "Tc_Foo", webUrl = "http://tc/foo", componentName = "foo", archived = true),
        )
        val result = mapTcProjectsToComponentMatches(projects, mapOf("foo" to fooUuid), cdReleaseTemplateId)
        assertTrue(result.isEmpty())
    }

    @Test
    @DisplayName("paused CDRelease build does not count as a release build")
    fun pausedReleaseBuildNotCounted() {
        val projects = listOf(
            tcProject(
                id = "Tc_Foo",
                webUrl = "http://tc/foo",
                componentName = "foo",
                buildTypes = listOf(buildType(id = "Build1", legacyTemplateId = "CDRelease", paused = true)),
            ),
        )
        val result = mapTcProjectsToComponentMatches(projects, mapOf("foo" to fooUuid), cdReleaseTemplateId)
        assertEquals(false, result[fooUuid]!!.single().hasCdReleaseBuild)
    }

    private fun tcProject(
        id: String,
        webUrl: String,
        componentName: String,
        buildTypes: List<ExternalTeamcityBuildType> = emptyList(),
        projectVersion: String? = null,
        archived: Boolean? = null,
    ) = ExternalTeamcityProject(
        id = id,
        name = id,
        href = "/$id",
        webUrl = webUrl,
        archived = archived,
        parameters = ExternalTeamcityProperties(
            properties = buildList {
                add(ExternalTeamcityProperty(name = "COMPONENT_NAME", value = componentName))
                if (projectVersion != null) {
                    add(ExternalTeamcityProperty(name = "PROJECT_VERSION", value = projectVersion))
                }
            }.toMutableList(),
        ),
        buildTypes = if (buildTypes.isEmpty()) {
            null
        } else {
            ExternalTeamcityBuildTypes(buildTypes = buildTypes)
        },
    )

    private fun buildType(
        id: String,
        legacyTemplateId: String? = null,
        pluralTemplateIds: List<String>? = null,
        paused: Boolean? = null,
        projectVersion: String? = null,
    ): ExternalTeamcityBuildType = ExternalTeamcityBuildType(
        id = id,
        name = id,
        projectId = "P",
        projectName = "P",
        href = "/$id",
        paused = paused,
        parameters = projectVersion?.let {
            ExternalTeamcityProperties(
                properties = mutableListOf(ExternalTeamcityProperty(name = "PROJECT_VERSION", value = it)),
            )
        },
        template = legacyTemplateId?.let {
            ExternalTeamcityBuildType(id = it, name = it, projectId = "P", projectName = "P", href = "/$it")
        },
        templates = pluralTemplateIds?.let { ids ->
            ExternalTeamcityBuildTypes(
                buildTypes = ids.map { ExternalTeamcityBuildType(id = it, name = it, projectId = "P", projectName = "P", href = "/$it") },
            )
        },
    )
}
