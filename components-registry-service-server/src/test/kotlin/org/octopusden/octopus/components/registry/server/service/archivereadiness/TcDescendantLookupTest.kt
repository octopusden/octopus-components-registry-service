package org.octopusden.octopus.components.registry.server.service.archivereadiness

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import feign.FeignException
import feign.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.octopusden.octopus.components.registry.server.teamcity.TeamcityProperties
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProjects
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.ProjectLocator
import java.nio.charset.StandardCharsets

class TcDescendantLookupTest {

    private val client = mock(TeamcityClient::class.java)
    private val properties = TeamcityProperties(baseUrl = "http://tc.example.com")
    private val lookup = TcDescendantLookup(properties, clientOverride = client)

    /**
     * Workaround for Mockito's `any(Class)` returning null in Kotlin — the null-check
     * throws NPE before Mockito can intercept. We fall back to an empty [ProjectLocator]
     * as the non-null sentinel so stubbing still matches any locator argument.
     */
    private fun anyLocator(): ProjectLocator =
        ArgumentMatchers.any(ProjectLocator::class.java) ?: ProjectLocator()

    /** Matches the direct-by-id root lookup (`ProjectLocator(id = ...)`, no `affectedProject`). */
    private fun rootLocator(): ProjectLocator =
        ArgumentMatchers.argThat<ProjectLocator> { it != null && it.affectedProject == null } ?: ProjectLocator()

    /** Matches the subtree lookup (`ProjectLocator(affectedProject = ...)`). */
    private fun descendantLocator(): ProjectLocator =
        ArgumentMatchers.argThat<ProjectLocator> { it != null && it.affectedProject != null } ?: ProjectLocator()

    private fun project(id: String, archived: Boolean? = false) =
        TeamcityProject(id = id, name = id, archived = archived, webUrl = "", href = "")

    /** Stubs the direct-by-id root lookup to report [project] as found. */
    private fun stubRoot(project: TeamcityProject) {
        doReturn(TeamcityProjects(projects = listOf(project)))
            .`when`(client).getProjectsWithLocatorAndFields(rootLocator(), anyString())
    }

    /** Stubs the `affectedProject` subtree lookup to report [projects] as the descendants. */
    private fun stubDescendants(projects: List<TeamcityProject>) {
        doReturn(TeamcityProjects(projects = projects))
            .`when`(client).getProjectsWithLocatorAndFields(descendantLocator(), anyString())
    }

    @Test
    fun `includes the queried project itself in the result`() {
        stubRoot(project("TC_ROOT"))
        stubDescendants(emptyList())
        val result = lookup.findDescendantsAndSelf("TC_ROOT") as TcDescendantResult.Found
        assertThat(result.projectIds).contains("TC_ROOT")
    }

    @Test
    fun `includes direct descendants returned by affectedProject`() {
        stubRoot(project("TC_ROOT"))
        val child = project("TC_CHILD")
        stubDescendants(listOf(child))
        val result = lookup.findDescendantsAndSelf("TC_ROOT") as TcDescendantResult.Found
        assertThat(result.projectIds).containsExactlyInAnyOrder("TC_ROOT", "TC_CHILD")
    }

    @Test
    fun `archived flag requested and populated correctly for descendants`() {
        stubRoot(project("TC_ROOT"))
        val archivedChild = project("TC_ARCHIVED", archived = true)
        val liveChild = project("TC_LIVE", archived = false)
        stubDescendants(listOf(archivedChild, liveChild))
        val result = lookup.findDescendantsAndSelf("TC_ROOT") as TcDescendantResult.Found
        assertThat(result.archivedIds).contains("TC_ARCHIVED")
        assertThat(result.archivedIds).doesNotContain("TC_LIVE")
    }

    @Test
    fun `root project's own archived flag is merged into archivedIds`() {
        // affectedProject structurally never reports the root's own archived state (it excludes
        // the root project). Regression coverage for the bug where an archived-but-unshared root
        // project could never be reported as archived because nothing fetched its own flag.
        stubRoot(project("TC_ROOT", archived = true))
        stubDescendants(emptyList())
        val result = lookup.findDescendantsAndSelf("TC_ROOT") as TcDescendantResult.Found
        assertThat(result.archivedIds).contains("TC_ROOT")
    }

    @Test
    fun `unarchived root project is not reported as archived`() {
        stubRoot(project("TC_ROOT", archived = false))
        stubDescendants(listOf(project("TC_CHILD", archived = true)))
        val result = lookup.findDescendantsAndSelf("TC_ROOT") as TcDescendantResult.Found
        assertThat(result.archivedIds).doesNotContain("TC_ROOT")
        assertThat(result.archivedIds).contains("TC_CHILD")
    }

    @Test
    fun `TC failure returns SystemUnavailable not empty set`() {
        doThrow(RuntimeException("connection refused"))
            .`when`(client).getProjectsWithLocatorAndFields(anyLocator(), anyString())
        val result = lookup.findDescendantsAndSelf("TC_ROOT")
        assertThat(result).isInstanceOf(TcDescendantResult.SystemUnavailable::class.java)
    }

    @Test
    fun `root project not found via empty response yields ProjectAbsent`() {
        doReturn(TeamcityProjects(projects = emptyList()))
            .`when`(client).getProjectsWithLocatorAndFields(anyLocator(), anyString())
        val result = lookup.findDescendantsAndSelf("TC_ROOT")
        assertThat(result).isInstanceOf(TcDescendantResult.ProjectAbsent::class.java)
    }

    @Test
    fun `root project not found via FeignException NotFound yields ProjectAbsent, not SystemUnavailable`() {
        val request = Request.create(
            Request.HttpMethod.GET,
            "http://tc.example.com/app/rest/latest/projects",
            emptyMap(),
            null,
            StandardCharsets.UTF_8,
            null,
        )
        val notFound = FeignException.NotFound("Project not found", request, null, emptyMap())
        doThrow(notFound)
            .`when`(client).getProjectsWithLocatorAndFields(anyLocator(), anyString())
        val result = lookup.findDescendantsAndSelf("TC_ROOT")
        assertThat(result).isInstanceOf(TcDescendantResult.ProjectAbsent::class.java)
    }

    /** Extracts the flat column names from a `project(a,b,c)`-shaped fields spec. */
    private fun columnsOf(fields: String): List<String> =
        fields.substringAfter("project(").substringBeforeLast(")").split(",").map { it.trim() }

    /** Builds a JSON object containing ONLY the requested [columns], the way TC's `fields` locator would. */
    private fun projectJson(columns: List<String>, id: String, archived: Boolean): String {
        val sampleValues: Map<String, Any> = mapOf(
            "id" to id,
            "name" to "Name-$id",
            "webUrl" to "http://tc.example.com/project.html?projectId=$id",
            "href" to "/app/rest/projects/id:$id",
            "archived" to archived,
        )
        val entries = columns.joinToString(",") { column ->
            val value = sampleValues[column] ?: error("no sample value configured for requested column '$column'")
            val jsonValue = if (value is Boolean) value.toString() else "\"$value\""
            "\"$column\":$jsonValue"
        }
        return "{$entries}"
    }

    @Test
    fun `jacksonRoundTripWithFieldsSpec - real deserialization succeeds for whatever fields spec is actually requested`() {
        // Why this test:
        //   The other tests construct TeamcityProject via the Kotlin constructor (which fills in
        //   every required field regardless of what the production fields spec actually asks
        //   TC for). That bypasses Jackson and hides the exact failure mode of the Finding-1 bug:
        //   TeamcityProject's `name`, `href`, `webUrl` are non-nullable with no default, so if the
        //   fields spec omits any of them, TC's real response (which returns ONLY the requested
        //   columns) blows up deserialization with a MissingKotlinParameterException on every real
        //   descendant row.
        //
        //   Unlike a hand-authored fixture, this test captures the ACTUAL `fields` string the
        //   production code passes to the client at call time and builds synthetic TC JSON
        //   containing ONLY those columns, then round-trips it through the same ObjectMapper
        //   config the library itself uses (mirrors ExternalTcProjectFetcherTest's
        //   jacksonRoundTripWithFieldsSpec pattern). Against the pre-fix
        //   `DESCENDANT_FIELDS = "project(id,archived)"` this throws inside the client call,
        //   which the production catch-all turns into SystemUnavailable instead of Found —
        //   i.e. this is the test that would have caught the bug.
        val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        doAnswer { invocation ->
            val locator = invocation.getArgument<ProjectLocator>(0)
            val fields = invocation.getArgument<String>(1)
            val columns = columnsOf(fields)
            val json = if (locator.affectedProject != null) {
                """{"project":[${projectJson(columns, "TC_CHILD", archived = true)}]}"""
            } else {
                """{"project":[${projectJson(columns, "TC_ROOT", archived = false)}]}"""
            }
            mapper.readValue(json, TeamcityProjects::class.java)
        }.`when`(client).getProjectsWithLocatorAndFields(anyLocator(), anyString())

        val result = lookup.findDescendantsAndSelf("TC_ROOT") as TcDescendantResult.Found
        assertThat(result.projectIds).containsExactlyInAnyOrder("TC_ROOT", "TC_CHILD")
        assertThat(result.archivedIds).contains("TC_CHILD")
    }
}
