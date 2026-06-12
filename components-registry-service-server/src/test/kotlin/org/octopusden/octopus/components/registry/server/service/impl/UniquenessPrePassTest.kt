package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pure-function tests for the §6.0 migration uniqueness pre-pass (no Spring).
 * The functions must enforce the SAME invariants the v4 API enforces on save —
 * including the full distribution-GAV identity (group, artifact, extension,
 * classifier) — and must stay silent on idempotent reruns (same componentKey)
 * and on pre-existing DB-vs-DB conflicts.
 */
class UniquenessPrePassTest {

    private val alwaysIntersect: (String, String) -> Boolean = { _, _ -> true }

    private fun gav(
        key: String,
        group: String = "org.example",
        artifact: String = "artifact",
        extension: String? = null,
        classifier: String? = null,
        range: String = "(,0),[0,)",
    ) = UniquenessGavRow(key, range, group, artifact, extension, classifier)

    // ───────────────────────── distribution GAV ─────────────────────────

    @Test
    @DisplayName("GAV: same group:artifact but different extension (zip vs apk) is NOT a collision")
    fun gav_differentExtension_noCollision() {
        val violations = computeDistributionGavCollisions(
            listOf(gav("comp-a", extension = "zip")),
            listOf(gav("comp-b", extension = "apk")),
            alwaysIntersect,
        )
        assertTrue(violations.isEmpty(), "zip vs apk must not collide: $violations")
    }

    @Test
    @DisplayName("GAV: null extension vs explicit extension is NOT a collision")
    fun gav_nullVsExplicitExtension_noCollision() {
        val violations = computeDistributionGavCollisions(
            listOf(gav("comp-a", extension = null)),
            listOf(gav("comp-b", extension = "zip")),
            alwaysIntersect,
        )
        assertTrue(violations.isEmpty())
    }

    @Test
    @DisplayName("GAV: same group:artifact:extension but different classifier is NOT a collision")
    fun gav_differentClassifier_noCollision() {
        val violations = computeDistributionGavCollisions(
            listOf(gav("comp-a", extension = "zip", classifier = "sources")),
            listOf(gav("comp-b", extension = "zip", classifier = "javadoc")),
            alwaysIntersect,
        )
        assertTrue(violations.isEmpty())
    }

    @Test
    @DisplayName("GAV: identical full identity across two components IS a collision (new-vs-existing)")
    fun gav_fullDuplicate_collision() {
        val violations = computeDistributionGavCollisions(
            listOf(gav("comp-a", extension = "zip")),
            listOf(gav("comp-b", extension = "zip")),
            alwaysIntersect,
        )
        assertEquals(1, violations.size)
        assertTrue(violations[0].startsWith("uniqueness violation:"), violations[0])
        assertTrue("comp-a" in violations[0] && "comp-b" in violations[0])
    }

    @Test
    @DisplayName("GAV: synthetic mapping rows (null ext/cls) collide on pattern overlap")
    fun gav_mappingRows_patternOverlap_collision() {
        val violations = computeDistributionGavCollisions(
            listOf(gav("comp-a", artifact = "match-.*")),
            listOf(gav("comp-b", artifact = "match-artifact")),
            alwaysIntersect,
        )
        assertEquals(1, violations.size)
    }

    @Test
    @DisplayName("GAV: same componentKey never collides (idempotent rerun / multi-range component)")
    fun gav_sameComponent_noCollision() {
        val violations = computeDistributionGavCollisions(
            listOf(gav("comp-a", extension = "zip"), gav("comp-a", extension = "zip", range = "[1,)")),
            listOf(gav("comp-a", extension = "zip")),
            alwaysIntersect,
        )
        assertTrue(violations.isEmpty())
    }

    @Test
    @DisplayName("GAV: existing-vs-existing pairs are NOT flagged (pre-existing DB state is the API's to fix)")
    fun gav_existingVsExisting_notFlagged() {
        val violations = computeDistributionGavCollisions(
            emptyList(),
            listOf(gav("comp-a", extension = "zip"), gav("comp-b", extension = "zip")),
            alwaysIntersect,
        )
        assertTrue(violations.isEmpty())
    }

    @Test
    @DisplayName("GAV: non-intersecting version ranges do not collide")
    fun gav_nonIntersectingRanges_noCollision() {
        val violations = computeDistributionGavCollisions(
            listOf(gav("comp-a", extension = "zip", range = "(,1.0)")),
            listOf(gav("comp-b", extension = "zip", range = "[2.0,)")),
            { r1, r2 -> !(r1 == "(,1.0)" && r2 == "[2.0,)") },
        )
        assertTrue(violations.isEmpty())
    }

    // ───────────────────────── jira (projectKey, versionPrefix) ─────────────────────────

    @Test
    @DisplayName("jira: same (projectKey, prefix) on two components is a collision; different prefix is not")
    fun jira_pairCollision() {
        val violations = computeJiraPairCollisions(
            listOf(UniquenessJiraPair("comp-a", "PROJ", "p1"), UniquenessJiraPair("comp-c", "PROJ", "p2")),
            listOf(UniquenessJiraPair("comp-b", "PROJ", "p1")),
        )
        assertEquals(1, violations.size)
        assertTrue("comp-a" in violations[0] && "comp-b" in violations[0])
        assertTrue(violations[0].startsWith("uniqueness violation:"))
    }

    @Test
    @DisplayName("jira: null prefix is its own bucket; same componentKey does not self-collide")
    fun jira_nullPrefixBucket_andSelf() {
        val violations = computeJiraPairCollisions(
            listOf(UniquenessJiraPair("comp-a", "PROJ", null)),
            listOf(
                UniquenessJiraPair("comp-a", "PROJ", null), // self (rerun)
                UniquenessJiraPair("comp-b", "PROJ", "p1"), // different bucket
            ),
        )
        assertTrue(violations.isEmpty(), "$violations")
    }

    // ───────────────────────── docker image names ─────────────────────────

    @Test
    @DisplayName("docker: same image name on two components is a collision; self/rerun is not")
    fun docker_imageCollision() {
        assertEquals(
            1,
            computeDockerImageCollisions(
                listOf(UniquenessDockerRow("comp-a", "registry/image")),
                listOf(UniquenessDockerRow("comp-b", "registry/image")),
            ).size,
        )
        assertTrue(
            computeDockerImageCollisions(
                listOf(UniquenessDockerRow("comp-a", "registry/image")),
                listOf(UniquenessDockerRow("comp-a", "registry/image")),
            ).isEmpty(),
        )
    }

    // ───────────────────────── displayName DSL-vs-DB ─────────────────────────

    @Test
    @DisplayName("displayName: incoming name held by a DIFFERENT persisted component is a collision; self/null are not")
    fun displayName_dbCollision() {
        val violations = computeDisplayNameDbCollisions(
            listOf("comp-a" to "Shared Name", "comp-c" to null, "comp-d" to " "),
            listOf("comp-b" to "Shared Name", "comp-a" to "Other"),
        )
        assertEquals(1, violations.size)
        assertTrue("comp-a" in violations[0] && "comp-b" in violations[0])

        assertTrue(
            computeDisplayNameDbCollisions(
                listOf("comp-a" to "Own Name"),
                listOf("comp-a" to "Own Name"),
            ).isEmpty(),
            "rerun against own persisted name must not collide",
        )
    }
}
