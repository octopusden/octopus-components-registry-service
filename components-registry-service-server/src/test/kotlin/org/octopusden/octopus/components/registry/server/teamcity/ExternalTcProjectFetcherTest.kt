package org.octopusden.octopus.components.registry.server.teamcity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProperties
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProperty
import java.util.UUID
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProject as ExternalTeamcityProject

/**
 * Unit tests for the pure mapper inside [ExternalTcProjectFetcher]. The HTTP side
 * is exercised separately by `teamcity-client/TeamcityClassicClientTest` against a
 * real TC; here we cover only the mapping rules CRS layers on top.
 */
class ExternalTcProjectFetcherTest {

    private val fooUuid = UUID.randomUUID()
    private val barUuid = UUID.randomUUID()

    @Test
    @DisplayName("empty input -> empty output")
    fun emptyInputEmptyOutput() {
        val result = mapTcProjectsToComponentMatches(emptyList(), mapOf("foo" to fooUuid))
        assertTrue(result.isEmpty())
    }

    @Test
    @DisplayName("project with known COMPONENT_NAME maps to UUID")
    fun knownComponentNameMaps() {
        val projects = listOf(tcProject(id = "Tc_Foo", webUrl = "http://tc/foo", componentName = "foo"))
        val result = mapTcProjectsToComponentMatches(projects, mapOf("foo" to fooUuid))
        assertEquals(mapOf(fooUuid to listOf(TcProject(id = "Tc_Foo", webUrl = "http://tc/foo"))), result)
    }

    @Test
    @DisplayName("project whose COMPONENT_NAME is unknown to CRS is dropped")
    fun unknownComponentNameDropped() {
        val projects = listOf(tcProject(id = "Tc_Stranger", webUrl = "http://tc/x", componentName = "not-in-registry"))
        val result = mapTcProjectsToComponentMatches(projects, mapOf("foo" to fooUuid))
        assertTrue(result.isEmpty())
    }

    @Test
    @DisplayName("project without parameters field is dropped")
    fun missingParametersFieldDropped() {
        val projects = listOf(
            ExternalTeamcityProject(id = "Tc_Empty", name = "Empty", href = "/x", webUrl = "http://tc/empty"),
        )
        val result = mapTcProjectsToComponentMatches(projects, mapOf("foo" to fooUuid))
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
                parameters = TeamcityProperties(properties = mutableListOf(TeamcityProperty(name = "OTHER", value = "y"))),
            ),
        )
        val result = mapTcProjectsToComponentMatches(projects, mapOf("foo" to fooUuid))
        assertTrue(result.isEmpty())
    }

    @Test
    @DisplayName("project with whitespace-only COMPONENT_NAME value is dropped")
    fun whitespaceOnlyValueDropped() {
        val projects = listOf(tcProject(id = "Tc_Blank", webUrl = "http://tc/blank", componentName = "   "))
        val result = mapTcProjectsToComponentMatches(projects, mapOf("foo" to fooUuid))
        assertTrue(result.isEmpty())
    }

    @Test
    @DisplayName("two projects with the same COMPONENT_NAME are grouped under one UUID")
    fun twoProjectsSameNameGrouped() {
        val projects = listOf(
            tcProject(id = "Tc_Foo_A", webUrl = "http://tc/foo-a", componentName = "foo"),
            tcProject(id = "Tc_Foo_B", webUrl = "http://tc/foo-b", componentName = "foo"),
        )
        val result = mapTcProjectsToComponentMatches(projects, mapOf("foo" to fooUuid))
        assertEquals(1, result.size)
        assertEquals(2, result[fooUuid]!!.size)
        assertEquals(setOf("Tc_Foo_A", "Tc_Foo_B"), result[fooUuid]!!.map { it.id }.toSet())
    }

    @Test
    @DisplayName("value with surrounding whitespace is trimmed before matching")
    fun whitespaceTrimmedBeforeMatching() {
        val projects = listOf(tcProject(id = "Tc_Foo", webUrl = "http://tc/foo", componentName = "  foo  "))
        val result = mapTcProjectsToComponentMatches(projects, mapOf("foo" to fooUuid))
        assertEquals(mapOf(fooUuid to listOf(TcProject(id = "Tc_Foo", webUrl = "http://tc/foo"))), result)
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
                parameters = TeamcityProperties(
                    properties = mutableListOf(
                        TeamcityProperty(name = "COMPONENT_NAME", value = "foo"),
                        TeamcityProperty(name = "COMPONENT_NAME", value = "bar"),
                    ),
                ),
            ),
        )
        val result = mapTcProjectsToComponentMatches(projects, mapOf("foo" to fooUuid, "bar" to barUuid))
        assertEquals(mapOf(fooUuid to listOf(TcProject(id = "Tc_Dup", webUrl = "http://tc/dup"))), result)
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
        val result = mapTcProjectsToComponentMatches(projects, mapOf("foo" to fooUuid, "bar" to barUuid))
        assertEquals(2, result.size)
        assertEquals(listOf(TcProject(id = "Tc_Foo", webUrl = "http://tc/foo")), result[fooUuid])
        assertEquals(listOf(TcProject(id = "Tc_Bar", webUrl = "http://tc/bar")), result[barUuid])
    }

    private fun tcProject(id: String, webUrl: String, componentName: String) = ExternalTeamcityProject(
        id = id,
        name = id,
        href = "/$id",
        webUrl = webUrl,
        parameters = TeamcityProperties(
            properties = mutableListOf(TeamcityProperty(name = "COMPONENT_NAME", value = componentName)),
        ),
    )
}
