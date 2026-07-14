package org.octopusden.octopus.components.registry.server.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.entity.ArtifactIdMode

/**
 * Strict, ordered migration classifier (NOT the resolver's broad probe): `(?!`→ALL_EXCEPT first;
 * exact-set catch-all forms→ALL; allowlist enumeration→EXPLICIT; anything else hard-fails.
 */
class ArtifactOwnershipModeClassifierTest {
    private fun classify(p: String?) = ArtifactOwnershipModeClassifier.classify(p)

    @Test
    @DisplayName("plain catch-all forms classify as ALL (exact-set, not probe)")
    fun catchAllForms_all() {
        listOf("*", ".*", "[\\w-\\.]+", "[\\w-]+", "\\w+").forEach {
            assertEquals(ArtifactIdMode.ALL, classify(it), "form '$it'")
        }
    }

    @Test
    @DisplayName("null / blank artifactId (inherited default) classifies as ALL")
    fun blank_all() {
        assertEquals(ArtifactIdMode.ALL, classify(null))
        assertEquals(ArtifactIdMode.ALL, classify("   "))
    }

    @Test
    @DisplayName("negative-lookahead exclusion classifies as ALL_EXCEPT_CLAIMED (checked first)")
    fun lookahead_allExcept() {
        assertEquals(ArtifactIdMode.ALL_EXCEPT_CLAIMED, classify("((?!claimed-model)[\\w-\\.]+)"))
        assertEquals(ArtifactIdMode.ALL_EXCEPT_CLAIMED, classify("(?!foo)bar"))
    }

    @Test
    @DisplayName("literal single token and comma/pipe enumerations classify as EXPLICIT")
    fun enumerations_explicit() {
        assertEquals(ArtifactIdMode.EXPLICIT, classify("foo-service"))
        assertEquals(ArtifactIdMode.EXPLICIT, classify("a,b,c"))
        assertEquals(ArtifactIdMode.EXPLICIT, classify("lib_one|lib_two|lib_three"))
        assertEquals(ArtifactIdMode.EXPLICIT, classify("foo.bar")) // dot is a legal literal char
    }

    @Test
    @DisplayName("any other regex hard-fails — no escape hatch")
    fun otherRegex_hardFail() {
        listOf("[a-z]+", ".*foo.*", "match-.*", "foo[0-9]+").forEach { p ->
            assertThrows(UnclassifiableArtifactPatternException::class.java, { classify(p) }, "pattern '$p'")
        }
    }

    @Test
    @DisplayName("splitTokens splits on comma AND pipe, trims, drops empties")
    fun splitTokens() {
        assertEquals(listOf("a", "b", "c"), ArtifactOwnershipModeClassifier.splitTokens("a, b | c"))
        assertEquals(listOf("x"), ArtifactOwnershipModeClassifier.splitTokens("x"))
    }

    @Test
    @DisplayName("splitGroups splits on comma only, trims, and keeps one groupId per segment")
    fun splitGroups_valid() {
        assertEquals(listOf("a", "b", "c"), ArtifactOwnershipModeClassifier.splitGroups("a, b,c"))
        assertEquals(listOf("x"), ArtifactOwnershipModeClassifier.splitGroups("x"))
        // pipe is NOT a group separator — it stays part of the (later allowlist-rejected) token
        assertEquals(listOf("a|b"), ArtifactOwnershipModeClassifier.splitGroups("a|b"))
    }

    @Test
    @DisplayName("splitGroups FAILS LOUD on an empty comma segment (no silent group drop)")
    fun splitGroups_rejectsEmptySegment() {
        listOf("a,", ",b", "a,,b", ",", "a, ,b").forEach { bad ->
            assertThrows(
                IllegalArgumentException::class.java,
                { ArtifactOwnershipModeClassifier.splitGroups(bad) },
                "malformed group-list '$bad' must be rejected, not silently normalized",
            )
        }
    }
}
