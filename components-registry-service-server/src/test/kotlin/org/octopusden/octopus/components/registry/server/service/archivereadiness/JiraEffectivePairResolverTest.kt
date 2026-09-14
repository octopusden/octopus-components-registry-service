package org.octopusden.octopus.components.registry.server.service.archivereadiness

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.JiraRowProjection

class JiraEffectivePairResolverTest {
    private val configRepo = mock<ComponentConfigurationRepository>()
    private val componentRepo = mock<ComponentRepository>()
    private val resolver = JiraEffectivePairResolver(configRepo, componentRepo)

    private data class FakeJiraRow(
        override val componentKey: String,
        override val versionRange: String,
        override val rowType: String,
        override val overriddenAttribute: String?,
        override val projectKey: String?,
        override val versionPrefix: String?,
    ) : JiraRowProjection

    @Test
    fun `no conflict when only one pair on a project key has a null prefix`() {
        val rows = listOf(
            FakeJiraRow("comp-a", "ALL_VERSIONS", "BASE", null, "PROJ", null),
            FakeJiraRow("comp-b", "ALL_VERSIONS", "BASE", null, "PROJ", "REL-"),
        )
        whenever(configRepo.findAllNonArchivedJiraRows()).thenReturn(rows)
        assertThat(resolver.hasNullPrefixConflict("PROJ")).isFalse()
    }

    @Test
    fun `conflict when two different pairs both claim a null prefix on the same project key`() {
        val rows = listOf(
            FakeJiraRow("comp-a", "ALL_VERSIONS", "BASE", null, "PROJ", null),
            FakeJiraRow("comp-b", "ALL_VERSIONS", "BASE", null, "PROJ", null),
        )
        whenever(configRepo.findAllNonArchivedJiraRows()).thenReturn(rows)
        assertThat(resolver.hasNullPrefixConflict("PROJ")).isTrue()
    }

    @Test
    fun `no other pair when this pair is the only one claiming the project key`() {
        val rows = listOf(
            FakeJiraRow("comp-a", "ALL_VERSIONS", "BASE", null, "PROJ", null),
            FakeJiraRow("comp-b", "ALL_VERSIONS", "BASE", null, "OTHER", "REL-"),
        )
        whenever(configRepo.findAllNonArchivedJiraRows()).thenReturn(rows)
        assertThat(resolver.hasOtherPairOnProjectKey("PROJ")).isFalse()
    }

    @Test
    fun `other pair exists when another pair with its own prefix also claims the project key`() {
        val rows = listOf(
            FakeJiraRow("comp-a", "ALL_VERSIONS", "BASE", null, "PROJ", null),
            FakeJiraRow("comp-b", "ALL_VERSIONS", "BASE", null, "PROJ", "REL-"),
        )
        whenever(configRepo.findAllNonArchivedJiraRows()).thenReturn(rows)
        assertThat(resolver.hasOtherPairOnProjectKey("PROJ")).isTrue()
    }
}
