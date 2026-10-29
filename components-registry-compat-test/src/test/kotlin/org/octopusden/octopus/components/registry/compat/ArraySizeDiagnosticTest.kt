package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class ArraySizeDiagnosticTest {
    private val mapper = jacksonObjectMapper()

    private fun arr(vararg elements: String) = mapper.readTree("[${elements.joinToString(",")}]")

    private fun range(
        name: String,
        versionRange: String = "[1.0,)",
    ) = """{"componentName":"$name","versionRange":"$versionRange","component":{"projectKey":"PRJ"}}"""

    @Test
    @DisplayName("names the element the candidate gained")
    fun namesTheGainedElement() {
        val d = ArraySizeDiagnostic.describe(arr(range("comp-a")), arr(range("comp-a"), range("comp-b")))
        assertThat(d).contains("ONLY IN CANDIDATE", "componentName=comp-b", "versionRange=[1.0,)")
        assertThat(d).doesNotContain("ONLY IN BASELINE")
    }

    @Test
    @DisplayName("names the element the candidate lost — the regression direction")
    fun namesTheLostElement() {
        val d = ArraySizeDiagnostic.describe(arr(range("comp-a"), range("comp-b")), arr(range("comp-a")))
        assertThat(d).contains("ONLY IN BASELINE", "componentName=comp-b")
    }

    @Test
    @DisplayName("a swap at equal size reports BOTH directions, not silence")
    fun reportsSwaps() {
        val d = ArraySizeDiagnostic.describe(arr(range("comp-a")), arr(range("comp-b")))
        assertThat(d).contains("ONLY IN CANDIDATE", "comp-b")
        assertThat(d).contains("ONLY IN BASELINE", "comp-a")
    }

    @Test
    @DisplayName("multiplicity: a duplicate on one side is reported, not cancelled out")
    fun countsMultiplicity() {
        val d = ArraySizeDiagnostic.describe(arr(range("comp-a")), arr(range("comp-a"), range("comp-a")))
        assertThat(d).contains("ONLY IN CANDIDATE", "comp-a")
    }

    @Test
    @DisplayName("same component, different ranges are distinct elements")
    fun rangeIsPartOfTheKey() {
        val d =
            ArraySizeDiagnostic.describe(
                arr(range("comp-a", "[1.0,2.0)")),
                arr(range("comp-a", "[1.0,2.0)"), range("comp-a", "[2.0,)")),
            )
        assertThat(d).contains("ONLY IN CANDIDATE", "versionRange=[2.0,)")
    }

    @Test
    @DisplayName("identical membership says so explicitly — the difference is inside the elements")
    fun identicalMembershipIsStated() {
        val d = ArraySizeDiagnostic.describe(arr(range("comp-a")), arr(range("comp-a")))
        assertThat(d).contains("membership identical by key")
    }

    @Test
    @DisplayName("elements with no identifying field fall back to the whole element, never collapse")
    fun fallsBackToWholeElement() {
        val d = ArraySizeDiagnostic.describe(mapper.readTree("""[{"x":1}]"""), mapper.readTree("""[{"x":1},{"x":2}]"""))
        assertThat(d).contains("ONLY IN CANDIDATE", "\"x\":2")
    }

    @Test
    @DisplayName("non-array input yields no diagnostic rather than a misleading one")
    fun nonArrayIsSkipped() {
        assertThat(ArraySizeDiagnostic.describe(mapper.readTree("""{"a":1}"""), mapper.readTree("""{"a":2}"""))).isNull()
    }
}
