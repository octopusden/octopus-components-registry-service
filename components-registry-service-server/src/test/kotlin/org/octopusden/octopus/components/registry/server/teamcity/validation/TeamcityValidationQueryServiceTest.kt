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
    @DisplayName("SYS-092: list joins each finding to its owning component(s); a shared project yields one row per component")
    fun `SYS-092 list fans out shared project to each component`() {
        val rows = service.list(types = null, status = null, component = null)
        // Foo(old-java)->a,b ; Bar(old-java)->a ; Foo(override)->a,b = 5 rows
        assertEquals(5, rows.size)
        assertEquals(setOf(a, b), rows.map { it.componentId }.toSet())
        assertEquals(1, rows.count { it.projectId == "Bar" }) // Bar owned only by comp-a
        assertEquals(4, rows.count { it.projectId == "Foo" }) // 2 findings x 2 components
        assertEquals("https://tc.example.com/project/Foo", rows.first { it.projectId == "Foo" }.projectUrl)
        assertEquals("https://tc.example.com/project/Bar", rows.first { it.projectId == "Bar" }.projectUrl)
    }

    @Test
    @DisplayName("SYS-092: list filters by validation type before the component join")
    fun `SYS-092 list filters by type`() {
        val rows = service.list(types = listOf("OVERRIDES_DEFAULT_BUILD_STEP"), status = null, component = null)
        assertEquals(2, rows.size) // only Foo(override) -> a, b
        assertEquals(setOf(a, b), rows.map { it.componentId }.toSet())
    }

    @Test
    @DisplayName("SYS-092: list filters by ANY of multiple types (case-insensitive), de-duplicated")
    fun `SYS-092 list filters by multiple types`() {
        // Both types requested (one lower-cased to prove case-insensitivity, one blank to prove it's ignored).
        val rows =
            service.list(
                types = listOf("overrides_default_build_step", "USES_OLD_JAVA_VERSION", "  "),
                status = null,
                component = null,
            )
        // Foo(override)->a,b + Foo(old-java)->a,b + Bar(old-java)->a = 5 rows (all findings match one of the two).
        assertEquals(5, rows.size)
        assertEquals(
            setOf("OVERRIDES_DEFAULT_BUILD_STEP", "USES_OLD_JAVA_VERSION"),
            rows.map { it.type }.toSet(),
        )
    }

    @Test
    @DisplayName("SYS-092: list treats an empty type list as no type filter")
    fun `SYS-092 list empty type list is no filter`() {
        assertEquals(
            service.list(types = null, status = null, component = null).size,
            service.list(types = emptyList(), status = null, component = null).size,
        )
    }

    @Test
    @DisplayName("SYS-092: list filters by status (case-insensitive)")
    fun `SYS-092 list filters by status`() {
        // Mixed statuses: one ERROR on Bar, the rest WARNING.
        Mockito.`when`(validationRepo.findAll()).thenReturn(
            listOf(
                finding("Foo", "USES_OLD_JAVA_VERSION", status = "WARNING"),
                finding("Bar", "USES_OLD_JAVA_VERSION", status = "ERROR"),
                finding("Foo", "OVERRIDES_DEFAULT_BUILD_STEP", status = "WARNING"),
            ),
        )

        val errors = service.list(types = null, status = "error", component = null) // case-insensitive
        assertEquals(1, errors.size) // only Bar(ERROR) -> comp-a
        assertEquals("ERROR", errors.single().status)
        assertEquals("Bar", errors.single().projectId)

        val warnings = service.list(types = null, status = "WARNING", component = null)
        // Foo(old-java)->a,b + Foo(override)->a,b = 4 rows.
        assertEquals(4, warnings.size)
        assertEquals(setOf("WARNING"), warnings.map { it.status }.toSet())

        assertEquals(0, service.list(types = null, status = "NOPE", component = null).size)
    }

    @Test
    @DisplayName("SYS-092: list filters by component-name substring")
    fun `SYS-092 list filters by component`() {
        // comp-b only owns Foo, so filtering to comp-b drops all comp-a-only rows (e.g. Bar).
        val onlyB = service.list(types = null, status = null, component = "comp-b")
        assertEquals(setOf(b), onlyB.map { it.componentId }.toSet())
        assertEquals(setOf("Foo"), onlyB.map { it.projectId }.toSet())

        // Substring match, case-insensitive.
        val onlyA = service.list(types = null, status = null, component = "COMP-A")
        assertEquals(setOf(a), onlyA.map { it.componentId }.toSet())

        assertEquals(0, service.list(types = null, status = null, component = "no-such-component").size)
    }

    @Test
    @DisplayName("SYS-092: list dedupes a component reachable through multiple version lines to the same project")
    fun `SYS-092 list dedupes component reached via multiple version lines to same project`() {
        // comp-a reaches "Foo" via two distinct version lines (e.g. duplicate manual v4 curation).
        Mockito.`when`(versionLineRepo.findByProjectIdsWithComponent(Mockito.anyCollection())).thenAnswer { inv ->
            val ids = inv.getArgument<Collection<String>>(0)
            (versionLines + versionLine(a, "comp-a", "Foo")).filter { it.teamcityProject.projectId in ids }
        }

        val rows = service.list(types = listOf("USES_OLD_JAVA_VERSION"), status = null, component = null)
        // Foo(old-java) -> a,b ; Bar(old-java) -> a = 3 rows.
        // Without the projectId+componentId de-dupe, the duplicate Foo->a version line would add
        // a 4th row (a second "Foo" row for comp-a).
        assertEquals(setOf(a, b), rows.map { it.componentId }.toSet())
        assertEquals(3, rows.size, "expected exactly one row per distinct (project, component) pair")
    }

    @Test
    @DisplayName("SYS-092: summary counts DISTINCT components per type and overall")
    fun `SYS-092 summary distinct component counts`() {
        val summary = service.summary()
        assertEquals(2, summary.componentsWithIssues) // a, b
        assertEquals(5, summary.findings)
        assertEquals(2, summary.byType["USES_OLD_JAVA_VERSION"]) // a (Bar) + a,b (Foo) = {a,b}
        assertEquals(2, summary.byType["OVERRIDES_DEFAULT_BUILD_STEP"]) // a, b (Foo)
    }

    private fun finding(
        projectId: String,
        type: String,
        status: String = "WARNING",
    ) = TeamcityValidationEntity(
        projectId = projectId,
        type = type,
        status = status,
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
