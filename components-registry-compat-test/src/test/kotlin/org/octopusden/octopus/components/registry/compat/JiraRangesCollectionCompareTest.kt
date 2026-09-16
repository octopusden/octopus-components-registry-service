package org.octopusden.octopus.components.registry.compat

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.core.dto.ComponentInfoDTO
import org.octopusden.octopus.components.registry.core.dto.ComponentVersionFormatDTO
import org.octopusden.octopus.components.registry.core.dto.DistributionDTO
import org.octopusden.octopus.components.registry.core.dto.JiraComponentDTO
import org.octopusden.octopus.components.registry.core.dto.JiraComponentVersionRangeDTO
import org.octopusden.octopus.components.registry.core.dto.SecurityGroupsDTO
import org.octopusden.octopus.components.registry.core.dto.VCSSettingsDTO
import java.time.Duration

/**
 * How the typed layer compares the `jira-component-version-ranges` collection.
 *
 * Handing AssertJ the whole collection with `ignoringCollectionOrder` costs a pairwise search, and
 * every element pair is a deep recursive comparison through three custom field comparators. On the
 * live stand (1372 elements, 518 of them carrying the ADR-021 name transition) that ran for over an
 * hour of CPU and the build was killed by its execution timeout — it had never surfaced before
 * because a size mismatch used to fail the comparison before the pairing started.
 *
 * It also reports badly: one record for the whole collection, naming every element, differing or not.
 */
@Tag("unit")
class JiraRangesCollectionCompareTest {
    private val endpoint = "GET /rest/api/2/common/jira-component-version-ranges"

    private fun range(
        name: String,
        displayName: String?,
        projectKey: String = "PRJ",
    ) = JiraComponentVersionRangeDTO(
        componentName = name,
        versionRange = "[1.0,)",
        component =
            JiraComponentDTO(
                projectKey = projectKey,
                displayName = displayName,
                componentVersionFormat = ComponentVersionFormatDTO("m", "r", "b", "l", "h"),
                componentInfo = ComponentInfoDTO("", "p"),
                technical = false,
            ),
        distribution = DistributionDTO(false, false, "g:a:$name", null, null, SecurityGroupsDTO(), null),
        vcsSettings = VCSSettingsDTO(emptyList(), null),
    )

    @BeforeEach
    fun reset() {
        DiffCollector.clear()
        Adr021DisplayName.gitMode = false
    }

    @Test
    @DisplayName("a single diverging element is reported BY ITSELF, not as a whole-collection dump")
    fun reportsThePerElementDifference() {
        val baseline = (1..5).map { range("comp-$it", null) }
        val candidate = (1..5).map { range("comp-$it", "Name $it", projectKey = if (it == 3) "MOVED" else "PRJ") }

        Comparators.compareDto(endpoint, emptyMap(), baseline, candidate)

        assertThat(DiffCollector.snapshot()).singleElement().satisfies({
            assertThat(it.message).contains("comp-3")
            // The four untouched elements have no business being in a record about comp-3.
            assertThat(it.message).doesNotContain("comp-1", "comp-2", "comp-4", "comp-5")
        })
    }

    @Test
    @DisplayName("a realistic collection compares in linear time, not by pairwise search")
    fun comparesWithoutAPairwiseSearch() {
        // Every element carries the ADR-021 null -> name transition, which is the live shape.
        val baseline = (1..400).map { range("comp-$it", null) }
        val candidate = (1..400).map { range("comp-$it", "Name $it") }

        assertTimeoutPreemptively(Duration.ofSeconds(20)) {
            Comparators.compareDto(endpoint, emptyMap(), baseline, candidate)
        }
        assertThat(DiffCollector.snapshot()).isEmpty()
    }
}
