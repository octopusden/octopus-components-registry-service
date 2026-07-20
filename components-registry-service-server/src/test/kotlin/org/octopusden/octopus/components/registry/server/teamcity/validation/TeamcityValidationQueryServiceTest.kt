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
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class TeamcityValidationQueryServiceTest {
    private val validationRepo = Mockito.mock(TeamcityValidationRepository::class.java)
    private val versionLineRepo = Mockito.mock(VersionLineRepository::class.java)
    private val service = TeamcityValidationQueryService(validationRepo, versionLineRepo)

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
    }

    @Test
    @DisplayName("list filters by validation type before the component join")
    fun `list filters by type`() {
        val rows = service.list(type = "OVERRIDES_DEFAULT_BUILD_STEP", status = null, component = null)
        assertEquals(2, rows.size) // only Foo(override) -> a, b
        assertEquals(setOf(a, b), rows.map { it.componentId }.toSet())
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
