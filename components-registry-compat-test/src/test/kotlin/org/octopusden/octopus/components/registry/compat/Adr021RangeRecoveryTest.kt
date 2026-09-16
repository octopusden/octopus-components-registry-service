package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The TD-022 recovery rule. The positive case is one line; the negatives are the class — an
 * arbitrary superset must NOT pass, or the gate stops being a gate.
 */
@Tag("unit")
class Adr021RangeRecoveryTest {
    private val mapper = jacksonObjectMapper()

    /** One `JiraComponentVersionRangeDTO`-shaped element. */
    private fun element(
        componentName: String,
        displayName: String? = null,
        projectKey: String = "PRJ",
        versionRange: String = "(,)",
        vcsUrl: String = "ssh://repo",
        gav: String? = null,
    ) = """
        {"componentName":"$componentName","versionRange":"$versionRange",
         "component":{"projectKey":"$projectKey","displayName":${displayName?.let { "\"$it\"" } ?: "null"},
                      "componentVersionFormat":{"majorVersionFormat":"fmt"}},
         "distribution":{"explicit":false,"external":false,"GAV":${gav?.let { "\"$it\"" } ?: "null"}},
         "vcsSettings":{"versionControlSystemRoots":[{"vcsPath":"$vcsUrl"}]}}
        """.trimIndent()

    private fun arrayOf(vararg elements: String) = mapper.readTree("[${elements.joinToString(",")}]")

    @BeforeEach
    fun dbMode() {
        Adr021DisplayName.gitMode = false
    }

    @AfterEach
    fun restoreMode() {
        Adr021DisplayName.gitMode = CompatConfig.load().gitMode
    }

    @Test
    @DisplayName("the observed case: a component that was collapsing into an identical sibling reappears")
    fun collapsedSiblingReappears() {
        val verdict =
            Adr021RangeRecovery.analyse(
                baseline = arrayOf(element("comp-c")),
                candidate = arrayOf(element("comp-c", "Component C"), element("comp-a", "Component A")),
            )
        assertThat(verdict).isInstanceOf(Adr021RangeRecovery.Verdict.Confirmed::class.java)
        assertThat((verdict as Adr021RangeRecovery.Verdict.Confirmed).keys)
            .containsExactly("componentName=comp-a, versionRange=(,)")
    }

    @Test
    @DisplayName("REJECT: an element the candidate LOST — a loss is never a recovery")
    fun lossIsRejected() {
        val verdict =
            Adr021RangeRecovery.analyse(
                baseline = arrayOf(element("comp-c"), element("comp-x", projectKey = "OTHER")),
                candidate = arrayOf(element("comp-c", "Component C"), element("comp-a", "Component A")),
            )
        assertThat(verdict).isInstanceOf(Adr021RangeRecovery.Verdict.Rejected::class.java)
        assertThat((verdict as Adr021RangeRecovery.Verdict.Rejected).reason).contains("unmatched")
    }

    @Test
    @DisplayName("REJECT: an addition with no baseline twin was not collapsing — it is a new element")
    fun additionWithoutTwinIsRejected() {
        val verdict =
            Adr021RangeRecovery.analyse(
                baseline = arrayOf(element("comp-c")),
                candidate = arrayOf(element("comp-c"), element("comp-a", "Component A", projectKey = "OTHER")),
            )
        assertThat((verdict as Adr021RangeRecovery.Verdict.Rejected).reason).contains("no baseline twin")
    }

    @Test
    @DisplayName("REJECT: an addition whose twin ALREADY had a name — ADR-021 cannot explain it")
    fun additionWhoseTwinWasNamedIsRejected() {
        val verdict =
            Adr021RangeRecovery.analyse(
                baseline = arrayOf(element("comp-c", "Component C")),
                candidate = arrayOf(element("comp-c", "Component C"), element("comp-a", "Component A")),
            )
        assertThat((verdict as Adr021RangeRecovery.Verdict.Rejected).reason).contains("no baseline twin")
    }

    @Test
    @DisplayName("REJECT: an addition that merely duplicates a component already visible")
    fun duplicateOfVisibleComponentIsRejected() {
        val verdict =
            Adr021RangeRecovery.analyse(
                baseline = arrayOf(element("comp-c")),
                candidate = arrayOf(element("comp-c"), element("comp-c", "Component C", versionRange = "[1.0,)")),
            )
        assertThat((verdict as Adr021RangeRecovery.Verdict.Rejected).reason).contains("duplicates a component")
    }

    @Test
    @DisplayName("REJECT: an addition carrying no display name at all")
    fun namelessAdditionIsRejected() {
        val verdict =
            Adr021RangeRecovery.analyse(
                baseline = arrayOf(element("comp-c")),
                candidate = arrayOf(element("comp-c"), element("comp-a")),
            )
        assertThat((verdict as Adr021RangeRecovery.Verdict.Rejected).reason).contains("no display name")
    }

    @Test
    @DisplayName("REJECT (git-mode): the no-migration candidate gets no allowance")
    fun gitModeIsRejected() {
        Adr021DisplayName.gitMode = true
        val verdict =
            Adr021RangeRecovery.analyse(
                baseline = arrayOf(element("comp-c")),
                candidate = arrayOf(element("comp-c", "Component C"), element("comp-a", "Component A")),
            )
        assertThat((verdict as Adr021RangeRecovery.Verdict.Rejected).reason).contains("git-mode")
    }

    @Test
    @DisplayName("equal membership is none of this rule's business")
    fun equalMembershipIsNotApplicable() {
        assertThat(Adr021RangeRecovery.analyse(arrayOf(element("comp-c")), arrayOf(element("comp-c"))))
            .isEqualTo(Adr021RangeRecovery.Verdict.NotApplicable)
    }

    @Test
    @DisplayName("non-array input is none of this rule's business either")
    fun nonArrayIsNotApplicable() {
        assertThat(Adr021RangeRecovery.analyse(mapper.readTree("""{"a":1}"""), mapper.readTree("""{"a":2}""")))
            .isEqualTo(Adr021RangeRecovery.Verdict.NotApplicable)
    }

    @Test
    @DisplayName("REJECT: the refusal names WHICH field breaks the twin — vcsSettings here")
    fun refusalNamesTheDivergingField() {
        val verdict =
            Adr021RangeRecovery.analyse(
                baseline = arrayOf(element("comp-c")),
                candidate = arrayOf(element("comp-c"), element("comp-a", "Component A", vcsUrl = "ssh://other")),
            )
        val reason = (verdict as Adr021RangeRecovery.Verdict.Rejected).reason
        assertThat(reason).contains("no baseline twin", "differs in vcsSettings", "componentName=comp-c")
        assertThat(reason).doesNotContain("differs in component+")
    }

    @Test
    @DisplayName("REJECT: when nothing shares the versionRange, the refusal says exactly that")
    fun refusalReportsNoSharedRange() {
        val verdict =
            Adr021RangeRecovery.analyse(
                baseline = arrayOf(element("comp-c")),
                candidate = arrayOf(element("comp-c"), element("comp-a", "Component A", versionRange = "[9.0,)")),
            )
        assertThat((verdict as Adr021RangeRecovery.Verdict.Rejected).reason)
            .contains("no baseline element shares its versionRange")
    }

    @Test
    @DisplayName("the REAL case: the twin differs only in distribution, which the old equals never compared")
    fun twinDifferingOnlyInDistributionStillCounts() {
        // Distribution carries Groovy @EqualsAndHashCode over private FINAL FIELDS, which that
        // transform does not compare — so any two Distributions are equal and the pair collapsed
        // regardless of their GAVs. Requiring them to match modelled a contract production does not
        // run, and refused a genuine recovery.
        val verdict =
            Adr021RangeRecovery.analyse(
                baseline = arrayOf(element("comp-c", gav = "g:c:1")),
                candidate = arrayOf(element("comp-c", "Component C", gav = "g:c:1"), element("comp-a", "Component A", gav = "g:a:1")),
            )
        assertThat(verdict).isInstanceOf(Adr021RangeRecovery.Verdict.Confirmed::class.java)
        assertThat((verdict as Adr021RangeRecovery.Verdict.Confirmed).notes)
            .singleElement()
            .satisfies({ assertThat(it).contains("carries a distribution its baseline twin did not", "comp-a") })
    }

    @Test
    @DisplayName("REJECT: vcsSettings ARE part of the contract, so a divergence there is not a recovery")
    fun vcsSettingsDivergenceIsStillRejected() {
        val verdict =
            Adr021RangeRecovery.analyse(
                baseline = arrayOf(element("comp-c")),
                candidate = arrayOf(element("comp-c", "Component C"), element("comp-a", "Component A", vcsUrl = "ssh://other")),
            )
        assertThat(verdict).isInstanceOf(Adr021RangeRecovery.Verdict.Rejected::class.java)
    }

    // ----- Wiring: the raw layer carries the verdict, and only the marker is suppressible -----

    private fun response(body: String) =
        RawResponse(
            status = 200,
            headers = mapOf("Content-Type" to "application/json"),
            bodyBytes = body.toByteArray(Charsets.UTF_8),
            json = mapper.readTree(body),
            durationMs = 0L,
        )

    private fun rawCompare(
        baseline: String,
        candidate: String,
        endpoint: String = "GET /rest/api/2/common/jira-component-version-ranges",
    ): String? {
        DiffCollector.clear()
        Comparators.compareRaw(endpoint, emptyMap(), response(baseline), response(candidate))
        return DiffCollector.snapshot().first { it.category == DiffClassifier.STRUCTURAL_DIFF }.message
    }

    @Test
    @DisplayName("raw: a confirmed recovery carries the marker the known-delta entry keys on")
    fun rawCarriesTheConfirmedMarker() {
        val message =
            rawCompare(
                "[${element("comp-c")}]",
                "[${element("comp-c", "Component C")},${element("comp-a", "Component A")}]",
            )
        assertThat(message).contains(Adr021RangeRecovery.CONFIRMED_MARKER, "componentName=comp-a")
    }

    @Test
    @DisplayName("raw: a refused size difference carries the REASON, never the marker")
    fun rawCarriesTheRefusalReason() {
        val message =
            rawCompare(
                "[${element("comp-c")}]",
                "[${element("comp-c")},${element("comp-a", "Component A", projectKey = "OTHER")}]",
            )
        assertThat(message).contains("NOT A TD-022 RECOVERY", "no baseline twin")
        assertThat(message).doesNotContain(Adr021RangeRecovery.CONFIRMED_MARKER)
    }

    @Test
    @DisplayName("raw: another endpoint gets no verdict at all — the rule is scoped to jira-ranges")
    fun rawVerdictIsScopedToJiraRanges() {
        val message =
            rawCompare(
                "[${element("comp-c")}]",
                "[${element("comp-c", "Component C")},${element("comp-a", "Component A")}]",
                endpoint = "GET /rest/api/2/components",
            )
        assertThat(message).doesNotContain(Adr021RangeRecovery.CONFIRMED_MARKER, "NOT A TD-022 RECOVERY")
    }
}
