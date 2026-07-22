package org.octopusden.octopus.components.registry.server.teamcity.validation

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.TeamcityProjectEntity
import org.octopusden.octopus.components.registry.server.entity.TeamcityValidationEntity
import org.octopusden.octopus.components.registry.server.entity.VersionLineEntity
import org.octopusden.octopus.components.registry.server.repository.TeamcityValidationRepository
import org.octopusden.octopus.components.registry.server.repository.VersionLineRepository
import org.octopusden.octopus.components.registry.server.teamcity.TeamcityProperties
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class TeamcityValidationQueryServiceTest {
    private val validationRepo = Mockito.mock(TeamcityValidationRepository::class.java)
    private val versionLineRepo = Mockito.mock(VersionLineRepository::class.java)
    private val teamcityProperties = TeamcityProperties(baseUrl = "https://tc.example.com")
    private val service = TeamcityValidationQueryService(validationRepo, versionLineRepo, teamcityProperties)

    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()

    // "Foo" is shared by components a + b; "Bar" only by a.
    private val versionLines =
        listOf(
            versionLine(a, "comp-a", "Foo"),
            versionLine(b, "comp-b", "Foo"),
            versionLine(a, "comp-a", "Bar"),
        )

    private val findings =
        listOf(
            finding("Foo", "USES_OLD_JAVA_VERSION"),
            finding("Bar", "USES_OLD_JAVA_VERSION"),
            finding("Foo", "OVERRIDES_DEFAULT_BUILD_STEP"),
        )

    init {
        Mockito.`when`(validationRepo.findAll()).thenReturn(findings)
        Mockito.`when`(versionLineRepo.findByProjectIdsWithComponent(Mockito.anyCollection())).thenAnswer { inv ->
            val ids = inv.getArgument<Collection<String>>(0)
            versionLines.filter { it.teamcityProject.projectId in ids }
        }
    }

    @Test
    @DisplayName("list joins each finding to its owning component(s); a shared project yields one row per component")
    fun `list fans out shared project to each component`() {
        val rows = service.list(type = null, status = null, component = null)
        // Foo(old-java)->a,b ; Bar(old-java)->a ; Foo(override)->a,b = 5 rows
        assertEquals(5, rows.size)
        assertEquals(setOf(a, b), rows.map { it.componentId }.toSet())
        assertEquals(1, rows.count { it.projectId == "Bar" }) // Bar owned only by comp-a
        assertEquals(4, rows.count { it.projectId == "Foo" }) // 2 findings x 2 components
        assertEquals("https://tc.example.com/project/Foo", rows.first { it.projectId == "Foo" }.projectUrl)
        assertEquals("https://tc.example.com/project/Bar", rows.first { it.projectId == "Bar" }.projectUrl)
    }

    @Test
    @DisplayName("list filters by validation type before the component join")
    fun `list filters by type`() {
        val rows = service.list(type = "OVERRIDES_DEFAULT_BUILD_STEP", status = null, component = null)
        assertEquals(2, rows.size) // only Foo(override) -> a, b
        assertEquals(setOf(a, b), rows.map { it.componentId }.toSet())
    }

    @Test
    @DisplayName("list dedupes a component reachable through multiple version lines to the same project")
    fun `list dedupes component reached via multiple version lines to same project`() {
        // comp-a reaches "Foo" via two distinct version lines (e.g. duplicate manual v4 curation).
        Mockito.`when`(versionLineRepo.findByProjectIdsWithComponent(Mockito.anyCollection())).thenAnswer { inv ->
            val ids = inv.getArgument<Collection<String>>(0)
            (versionLines + versionLine(a, "comp-a", "Foo")).filter { it.teamcityProject.projectId in ids }
        }

        val rows = service.list(type = "USES_OLD_JAVA_VERSION", status = null, component = null)
        // Foo(old-java) -> a,b ; Bar(old-java) -> a = 3 rows.
        // Without the projectId+componentId de-dupe, the duplicate Foo->a version line would add
        // a 4th row (a second "Foo" row for comp-a).
        assertEquals(setOf(a, b), rows.map { it.componentId }.toSet())
        assertEquals(3, rows.size, "expected exactly one row per distinct (project, component) pair")
    }

    @Test
    @DisplayName("summary counts DISTINCT components per type and overall")
    fun `summary distinct component counts`() {
        val summary = service.summary()
        assertEquals(2, summary.componentsWithIssues) // a, b
        assertEquals(5, summary.findings)
        assertEquals(2, summary.byType["USES_OLD_JAVA_VERSION"]) // a (Bar) + a,b (Foo) = {a,b}
        assertEquals(2, summary.byType["OVERRIDES_DEFAULT_BUILD_STEP"]) // a, b (Foo)
    }

    private fun finding(
        projectId: String,
        type: String,
    ) = TeamcityValidationEntity(
        projectId = projectId,
        type = type,
        status = "WARNING",
        message = null,
        updatedAt = Instant.now(),
    )

    private fun versionLine(
        componentId: UUID,
        componentKey: String,
        projectId: String,
    ) = VersionLineEntity(
        component = ComponentEntity(id = componentId, componentKey = componentKey),
        teamcityProject = TeamcityProjectEntity(projectId = projectId),
    )
}
