package org.octopusden.octopus.components.registry.compat

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
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

/**
 * ADR-021 suppression, exercised on the REAL `JiraComponentVersionRangeDTO` collection shape —
 * the one the `jira-component-version-ranges` endpoints return.
 *
 * Why a field comparator and not a known-delta entry: `compareDto` runs with
 * `ignoringCollectionOrder()`, so once an element's field differs AssertJ cannot pair elements and
 * reports `Top level actual and expected objects differ` over the whole collection. No per-field
 * `messagePattern` can match that, and matching it wholesale would suppress every collection
 * difference on the endpoint — including real regressions.
 *
 * The negative cases below are the point of this class: the suppression must be narrow enough that
 * a renamed component, a vanished name, a blank name, or a change to any OTHER field still fails.
 */
@Tag("unit")
class Adr021DisplayNameCompatTest {
    private val endpoint = "GET /rest/api/2/common/jira-component-version-ranges"

    private fun range(
        componentName: String,
        displayName: String?,
        projectKey: String = "PRJ",
        versionRange: String = "[1.0,)",
    ) = JiraComponentVersionRangeDTO(
        componentName = componentName,
        versionRange = versionRange,
        component =
            JiraComponentDTO(
                projectKey = projectKey,
                displayName = displayName,
                componentVersionFormat =
                    ComponentVersionFormatDTO("\$major", "\$major.\$minor", "\$major.\$minor.\$service", "\$major", null),
                componentInfo = ComponentInfoDTO("", "\$versionPrefix-\$baseVersionFormat"),
                technical = false,
            ),
        distribution = DistributionDTO(false, false, securityGroups = SecurityGroupsDTO()),
        vcsSettings = VCSSettingsDTO(versionControlSystemRoots = emptyList()),
    )

    private fun compare(
        baseline: Set<JiraComponentVersionRangeDTO>,
        candidate: Set<JiraComponentVersionRangeDTO>,
    ) = Comparators.compareDto(
        endpoint = endpoint,
        pathParams = emptyMap(),
        baseline = baseline,
        candidate = candidate,
    )

    @BeforeEach
    fun clearCollector() {
        DiffCollector.clear()
        Adr021DisplayName.gitMode = false
    }

    @AfterEach
    fun restoreMode() {
        Adr021DisplayName.gitMode = CompatConfig.load().gitMode
    }

    @Test
    @DisplayName("null -> name across a collection: suppressed (the ADR-021 fallback)")
    fun nullToNameIsSuppressed() {
        compare(
            setOf(range("comp-a", null), range("comp-b", null)),
            setOf(range("comp-a", "Component A"), range("comp-b", "Component B")),
        )
        assertThat(DiffCollector.snapshot()).isEmpty()
    }

    @Test
    @DisplayName("NEGATIVE: a name that CHANGES is still a VALUE_DIFF")
    fun renameStillSurfaces() {
        compare(setOf(range("comp-a", "Old Name")), setOf(range("comp-a", "New Name")))
        assertThat(DiffCollector.snapshot()).singleElement().satisfies({
            assertThat(it.category).isEqualTo(DiffClassifier.VALUE_DIFF)
        })
    }

    @Test
    @DisplayName("NEGATIVE: a name that DISAPPEARS is still a VALUE_DIFF")
    fun nameLossStillSurfaces() {
        compare(setOf(range("comp-a", "A Name")), setOf(range("comp-a", null)))
        assertThat(DiffCollector.snapshot()).hasSize(1)
    }

    @Test
    @DisplayName("NEGATIVE: null -> blank is not a name, still a VALUE_DIFF")
    fun blankIsNotAName() {
        compare(setOf(range("comp-a", null)), setOf(range("comp-a", "   ")))
        assertThat(DiffCollector.snapshot()).hasSize(1)
    }

    @Test
    @DisplayName("NEGATIVE: another field changing on the SAME element still surfaces")
    fun otherFieldChangeStillSurfaces() {
        compare(
            setOf(range("comp-a", null, projectKey = "PRJ")),
            setOf(range("comp-a", "Component A", projectKey = "OTHER")),
        )
        assertThat(DiffCollector.snapshot()).hasSize(1)
    }

    @Test
    @DisplayName("NEGATIVE: an element vanishing from the collection still surfaces")
    fun droppedElementStillSurfaces() {
        compare(
            setOf(range("comp-a", null), range("comp-b", null)),
            setOf(range("comp-a", "Component A")),
        )
        assertThat(DiffCollector.snapshot()).hasSize(1)
    }

    @Test
    @DisplayName("duplicate component names with different ranges are matched pairwise, not collapsed")
    fun duplicateKeysArePairedByRange() {
        compare(
            setOf(range("comp-a", null, versionRange = "[1.0,2.0)"), range("comp-a", null, versionRange = "[2.0,)")),
            setOf(
                range("comp-a", "Component A", versionRange = "[1.0,2.0)"),
                range("comp-a", "Component A", versionRange = "[2.0,)"),
            ),
        )
        assertThat(DiffCollector.snapshot()).isEmpty()
    }

    @Test
    @DisplayName("NEGATIVE (git-mode): the allowance does NOT apply — the no-op invariant stays strict")
    fun gitModeGetsNoAllowance() {
        // ADR-021 changes the DB resolver only. A git-routed candidate must stay byte-identical to
        // the baseline, and the empty known-deltas-git.json cannot catch a wrongly-gained name,
        // because a suppressed diff is never recorded in the first place.
        Adr021DisplayName.gitMode = true

        compare(setOf(range("comp-a", null)), setOf(range("comp-a", "Unexpected Name")))

        assertThat(DiffCollector.snapshot()).hasSize(1)
    }

    @Test
    @DisplayName("detailedComponentVersion.component is NOT covered by the comparator — it needs its known-delta entry")
    fun detailedComponentFieldIsNotSuppressedByTheComparator() {
        // The key -> label flip on this field is a string->string change, which the displayName
        // comparator deliberately does not touch. It is suppressed by an explicit known-delta entry
        // instead; this pins that the comparator alone leaves it visible, so removing that entry
        // cannot go unnoticed again.
        Comparators.compareDto(
            endpoint = "GET /rest/api/2/components/{c}/versions/{v}",
            pathParams = mapOf("c" to "comp-a", "v" to "1.0"),
            baseline = DetailedShape("comp-a"),
            candidate = DetailedShape("Component A"),
        )
        assertThat(DiffCollector.snapshot()).hasSize(1)
    }

    /** Minimal stand-in for the `detailedComponentVersion` nesting of `DetailedComponent`. */
    data class DetailedShape(
        val component: String,
    )
}
