package org.octopusden.octopus.components.registry.compat

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.core.dto.ComponentInfoDTO
import org.octopusden.octopus.components.registry.core.dto.ComponentRegistryVersion
import org.octopusden.octopus.components.registry.core.dto.ComponentVersionFormatDTO
import org.octopusden.octopus.components.registry.core.dto.ComponentVersionType
import org.octopusden.octopus.components.registry.core.dto.DetailedComponentVersion
import org.octopusden.octopus.components.registry.core.dto.DetailedComponentVersions
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
        // A TD-022 recovery is only accepted for a component the baseline inventory knows; without
        // this the rule fails closed and nothing is removed (which is its own test, over in
        // Adr021RangeRecoveryTest).
        BaselineInventory.seed(setOf("comp-a", "comp-b", "comp-c"))
    }

    @AfterEach
    fun restoreMode() {
        Adr021DisplayName.gitMode = CompatConfig.load().gitMode
        BaselineInventory.seed(null)
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
    @DisplayName("ENDPOINT GATE: a bare `component` field outside the detailed-version family stays compared")
    fun bareComponentFieldOutsideTheDetailedFamilyStaysCompared() {
        // The root-level allowance is registered per endpoint. On any other endpoint a field simply
        // named `component` is an ordinary field — a string->string change there is a real diff, and
        // the displayName comparator deliberately does not touch it.
        Comparators.compareDto(
            endpoint = "GET /rest/api/2/components/{c}/versions/{v}",
            pathParams = mapOf("c" to "comp-a", "v" to "1.0"),
            baseline = DetailedShape("comp-a"),
            candidate = DetailedShape("Component A"),
        )
        assertThat(DiffCollector.snapshot()).hasSize(1)
    }

    @Test
    @DisplayName("detailedComponentVersion.component: the key -> label flip alone is neutralised")
    fun nestedDetailedComponentFlipIsNeutralised() {
        Comparators.compareDto(
            endpoint = "GET /rest/api/2/components/{c}/versions/{v}",
            pathParams = mapOf("c" to "comp-a", "v" to "1.0"),
            baseline = DetailedComponentShape(DetailedShape("comp-a"), archived = false),
            candidate = DetailedComponentShape(DetailedShape("Component A"), archived = false),
        )
        assertThat(DiffCollector.snapshot()).isEmpty()
    }

    @Test
    @DisplayName("NEGATIVE: a co-occurring `archived` flip on the SAME record still surfaces")
    fun coOccurringArchivedFlipStillSurfaces() {
        // The whole point of neutralising the FIELD rather than suppressing the RECORD: one typed
        // record is one whole AssertJ comparison, so a known-delta entry keyed on the component
        // path would have taken this regression down with it.
        Comparators.compareDto(
            endpoint = "GET /rest/api/2/components/{c}/versions/{v}",
            pathParams = mapOf("c" to "comp-a", "v" to "1.0"),
            baseline = DetailedComponentShape(DetailedShape("comp-a"), archived = false),
            candidate = DetailedComponentShape(DetailedShape("Component A"), archived = true),
        )
        assertThat(DiffCollector.snapshot()).hasSize(1)
    }

    @Test
    @DisplayName("NEGATIVE: detailedComponentVersion.component losing its value still surfaces")
    fun nestedDetailedComponentBlankStillSurfaces() {
        Comparators.compareDto(
            endpoint = "GET /rest/api/2/components/{c}/versions/{v}",
            pathParams = mapOf("c" to "comp-a", "v" to "1.0"),
            baseline = DetailedComponentShape(DetailedShape("comp-a"), archived = false),
            candidate = DetailedComponentShape(DetailedShape("   "), archived = false),
        )
        assertThat(DiffCollector.snapshot()).hasSize(1)
    }

    @Test
    @DisplayName("TD-022 recovery: a component that reappears is removed from the typed compare, not suppressed")
    fun confirmedRecoveryIsRemovedFromTheTypedCompare() {
        // Baseline collapsed comp-a into comp-c (identical payload, displayName null on both, and
        // JiraComponentVersionRange.equals ignores componentName). Both now carry their own name.
        compare(
            setOf(range("comp-c", null)),
            setOf(range("comp-c", "Component C"), range("comp-a", "Component A")),
        )
        assertThat(DiffCollector.snapshot()).isEmpty()
    }

    @Test
    @DisplayName("NEGATIVE: a rename alongside a confirmed recovery still surfaces")
    fun regressionAlongsideARecoveryStillSurfaces() {
        // Only the recovered element is removed; everything else is compared as usual, so the rename
        // on a DIFFERENT element still fails. (That the removal is what makes the sizes match is
        // pinned by `confirmedRecoveryIsRemovedFromTheTypedCompare`, which goes red without it — the
        // message cannot distinguish the two, because AssertJ dumps both collections either way.)
        compare(
            setOf(range("comp-c", null), range("comp-b", "Old Name")),
            setOf(
                range("comp-c", "Component C"),
                range("comp-a", "Component A"),
                range("comp-b", "New Name"),
            ),
        )
        assertThat(DiffCollector.snapshot()).hasSize(1)
    }

    @Test
    @DisplayName("NEGATIVE: an addition the recovery rule refuses is NOT removed — the compare still fails")
    fun refusedAdditionStillFails() {
        compare(
            setOf(range("comp-c", null)),
            setOf(range("comp-c", "Component C"), range("comp-a", "Component A", projectKey = "OTHER")),
        )
        assertThat(DiffCollector.snapshot()).hasSize(1)
    }

    private fun ver(v: String) = ComponentRegistryVersion(ComponentVersionType.RELEASE, v, v)

    /** The REAL `/detailed-version` payload, so the field paths under test are the production ones. */
    private fun detailed(
        component: String,
        release: String = "1.0.0",
    ) = DetailedComponentVersion(
        component = component,
        minorVersion = ver("1.0"),
        lineVersion = ver("1.0"),
        buildVersion = ver(release),
        rcVersion = ver(release),
        releaseVersion = ver(release),
        hotfixVersion = null,
    )

    private fun compareDetailed(
        baseline: Any,
        candidate: Any,
        endpoint: String = "GET /rest/api/2/components/{component}/versions/{version}/detailed-version",
    ) = Comparators.compareDto(
        endpoint = endpoint,
        pathParams = mapOf("component" to "comp-a", "version" to "1.0"),
        baseline = baseline,
        candidate = candidate,
    )

    @Test
    @DisplayName("GET /detailed-version: the key -> label flip on the ROOT `component` is neutralised")
    fun detailedVersionRootFlipIsNeutralised() {
        compareDetailed(detailed("comp-a"), detailed("Component A"))
        assertThat(DiffCollector.snapshot()).isEmpty()
    }

    @Test
    @DisplayName("NEGATIVE: a co-occurring version change on the SAME record still surfaces")
    fun detailedVersionSiblingChangeStillSurfaces() {
        // This is what the record-level known-delta could not do: the `component` flip and a version
        // regression arrive in ONE AssertJ message, so a messagePattern keyed on `component` took
        // the regression down with it.
        compareDetailed(detailed("comp-a", release = "1.0.0"), detailed("Component A", release = "2.0.0"))
        assertThat(DiffCollector.snapshot()).hasSize(1)
    }

    @Test
    @DisplayName("NEGATIVE: the root `component` losing its value still surfaces")
    fun detailedVersionBlankStillSurfaces() {
        compareDetailed(detailed("comp-a"), detailed("   "))
        assertThat(DiffCollector.snapshot()).hasSize(1)
    }

    @Test
    @DisplayName("POST /detailed-versions: the flip inside the versions map is neutralised")
    fun detailedVersionsBatchFlipIsNeutralised() {
        compareDetailed(
            DetailedComponentVersions(mapOf("1.0.0" to detailed("comp-a"))),
            DetailedComponentVersions(mapOf("1.0.0" to detailed("Component A"))),
            endpoint = "POST /rest/api/2/components/{component}/detailed-versions",
        )
        assertThat(DiffCollector.snapshot()).isEmpty()
    }

    @Test
    @DisplayName("NEGATIVE: a version vanishing from the batch map still surfaces")
    fun detailedVersionsBatchLossStillSurfaces() {
        compareDetailed(
            DetailedComponentVersions(mapOf("1.0.0" to detailed("comp-a"), "2.0.0" to detailed("comp-a"))),
            DetailedComponentVersions(mapOf("1.0.0" to detailed("Component A"))),
            endpoint = "POST /rest/api/2/components/{component}/detailed-versions",
        )
        assertThat(DiffCollector.snapshot()).hasSize(1)
    }

    @Test
    @DisplayName("NEGATIVE (git-mode): /detailed-version gets no allowance either")
    fun detailedVersionGitModeGetsNoAllowance() {
        Adr021DisplayName.gitMode = true
        compareDetailed(detailed("comp-a"), detailed("Component A"))
        assertThat(DiffCollector.snapshot()).hasSize(1)
    }

    /** Minimal stand-in for the `detailedComponentVersion` nesting of `DetailedComponent`. */
    data class DetailedShape(
        val component: String,
    )

    /** `DetailedComponent`-shaped root: the nested payload plus a sibling field that must stay compared. */
    data class DetailedComponentShape(
        val detailedComponentVersion: DetailedShape,
        val archived: Boolean,
    )
}
