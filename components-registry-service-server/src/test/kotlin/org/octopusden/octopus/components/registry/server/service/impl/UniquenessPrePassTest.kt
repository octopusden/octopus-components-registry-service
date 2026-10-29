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

    // ── legacy MavenArtifactMatcher group semantics (prod-DSL shapes) ─────────
    // Legacy groupIdMatches(groupId, pattern) tests the WHOLE first string
    // against the pattern's comma-split items — a CSV group never matches
    // another CSV's items, so the production model/api component-family pairs
    // (a Defaults-inherited catch-all artifact regex vs literal placeholder
    // artifacts under shared CSV group elements) are LEGAL and pass the daily
    // legacy validation. The pre-pass must not be stricter than the contract
    // the DSL was authored against.

    @Test
    @DisplayName("GAV legacy parity: single-group wildcard vs CSV-group literal is NOT a collision")
    fun gav_legacyParity_singleGroupWildcardVsCsvLiteral_noCollision() {
        // model side: single group, no artifactId in DSL → catch-all wildcard.
        val model = gav("model-comp", group = "org.example.model2", artifact = "[\\w-\\.]+")
        // api side: CSV group containing the model group, literal placeholder artifact.
        val api = gav(
            "model-api-comp",
            group = "org.example.model2,org.example.extra.model,org.example.model_x",
            artifact = "placeholder_artifact_a",
        )
        assertTrue(computeDistributionGavCollisions(listOf(api), listOf(model), alwaysIntersect).isEmpty())
        assertTrue(computeDistributionGavCollisions(listOf(model), listOf(api), alwaysIntersect).isEmpty())
    }

    @Test
    @DisplayName("GAV legacy parity: CSV-group wildcard vs CSV-group literal is NOT a collision")
    fun gav_legacyParity_csvGroupWildcardVsCsvLiteral_noCollision() {
        val model = gav(
            "model-comp-b",
            group = "org.example.model2,org.example.model1",
            artifact = "[\\w-\\.]+",
        )
        val api = gav(
            "packages-api-comp",
            group = "org.example.model_x,org.example.extra.packages,org.example.model1,org.example.model2",
            artifact = "placeholder_artifact_b",
        )
        assertTrue(computeDistributionGavCollisions(listOf(api), listOf(model), alwaysIntersect).isEmpty())
        assertTrue(computeDistributionGavCollisions(listOf(model), listOf(api), alwaysIntersect).isEmpty())
    }

    @Test
    @DisplayName("GAV legacy parity: single group ∈ rival's CSV with matching artifact IS still a collision")
    fun gav_legacyParity_singleInCsv_collision() {
        val violations = computeDistributionGavCollisions(
            listOf(gav("comp-a", group = "org.example.shared", artifact = "lib")),
            listOf(gav("comp-b", group = "org.example.shared,org.example.other", artifact = "lib")),
            alwaysIntersect,
        )
        assertEquals(1, violations.size)
    }

    @Test
    @DisplayName("GAV legacy #24: identical CSV groups with the same artifact token ARE a collision (exact token-pair)")
    fun gav_legacy24_identicalCsv_sameArtifact_collision() {
        // #25 whole-vs-items never matches CSV-vs-CSV, but legacy #24 keys exact
        // (groupItem, artifactToken) pairs — identical CSVs share every item.
        val violations = computeDistributionGavCollisions(
            listOf(gav("comp-a", group = "org.example.x,org.example.y", artifact = "lib")),
            listOf(gav("comp-b", group = "org.example.x,org.example.y", artifact = "lib")),
            alwaysIntersect,
        )
        assertEquals(1, violations.size)
    }

    @Test
    @DisplayName("GAV legacy #24: CSVs sharing ONE group element with the same artifact token ARE a collision")
    fun gav_legacy24_csvSharedElement_sameArtifact_collision() {
        val violations = computeDistributionGavCollisions(
            listOf(gav("comp-a", group = "org.example.x,org.example.y", artifact = "lib")),
            listOf(gav("comp-b", group = "org.example.y,org.example.z", artifact = "other,lib")),
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

    @Test
    @DisplayName("jira (prod shape): no-prefix owner + prefixed claimants of the same project do not collide")
    fun jira_sharedProjectDistinctPrefixBuckets_noCollision() {
        val violations = computeJiraPairCollisions(
            listOf(
                UniquenessJiraPair("editor-model-comp", "PROJW", "EditorModel"),
                UniquenessJiraPair("editor-comp", "PROJW", "Editor"),
            ),
            listOf(UniquenessJiraPair("web-comp", "PROJW", null)),
        )
        assertTrue(violations.isEmpty(), "$violations")
    }

    @Test
    @DisplayName("jira: same component claiming one bucket from several ranges does not self-collide")
    fun jira_multiOccurrenceInNewPairs_noSelfCollision() {
        val violations = computeJiraPairCollisions(
            listOf(
                UniquenessJiraPair("comp-a", "PROJ", "p1"),
                UniquenessJiraPair("comp-a", "PROJ", "p1"),
            ),
            emptyList(),
        )
        assertTrue(violations.isEmpty(), "$violations")
    }

    // ───────────────────────── docker image names ─────────────────────────

    @Test
    @DisplayName("docker: same component claiming one image from several ranges does not self-collide")
    fun docker_multiOccurrenceInNewRows_noSelfCollision() {
        val violations = computeDockerImageCollisions(
            listOf(
                UniquenessDockerRow("comp-a", "registry/image"),
                UniquenessDockerRow("comp-a", "registry/image"),
            ),
            emptyList(),
        )
        assertTrue(violations.isEmpty(), "$violations")
    }

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

    // ───────────────────────── effective jira pairs from persisted rows ──────

    @Test
    @DisplayName("effective jira: projectKey-only override range claims (overrideKey, INHERITED base prefix)")
    fun effectiveJira_projectKeyOnlyOverride_inheritsBasePrefix() {
        val pairs = org.octopusden.octopus.components.registry.server.util.computeEffectiveJiraPairs(
            listOf(
                org.octopusden.octopus.components.registry.server.util.JiraRowView(
                    "editor-model-comp",
                    "(,0),[0,)",
                    "BASE",
                    null,
                    "PROJM",
                    "EditorModel",
                ),
                org.octopusden.octopus.components.registry.server.util.JiraRowView(
                    "editor-model-comp",
                    "(52.0.1-6,52.0.1-21]",
                    "SCALAR_OVERRIDE",
                    "jira.projectKey",
                    "PROJW",
                    null,
                ),
            ),
        )
        assertEquals(
            setOf("PROJM" to "EditorModel", "PROJW" to "EditorModel"),
            pairs["editor-model-comp"],
        )
    }

    @Test
    @DisplayName(
        "effective jira: same-range projectKey + prefix override rows merge into one claim; prefix-clear override claims (basePk, null)",
    )
    fun effectiveJira_sameRangeRowsMerge_andNullClear() {
        val pairs = org.octopusden.octopus.components.registry.server.util.computeEffectiveJiraPairs(
            listOf(
                org.octopusden.octopus.components.registry.server.util.JiraRowView(
                    "comp",
                    "(,0),[0,)",
                    "BASE",
                    null,
                    "PROJ",
                    "base",
                ),
                // one range overriding BOTH scalars → single merged claim
                org.octopusden.octopus.components.registry.server.util.JiraRowView(
                    "comp",
                    "[2,3)",
                    "SCALAR_OVERRIDE",
                    "jira.projectKey",
                    "OTHER",
                    null,
                ),
                org.octopusden.octopus.components.registry.server.util.JiraRowView(
                    "comp",
                    "[2,3)",
                    "SCALAR_OVERRIDE",
                    "jira.versionPrefix",
                    null,
                    "ov",
                ),
                // a range CLEARING the prefix (override row with null value) → (PROJ, null)
                org.octopusden.octopus.components.registry.server.util.JiraRowView(
                    "comp",
                    "[3,)",
                    "SCALAR_OVERRIDE",
                    "jira.versionPrefix",
                    null,
                    null,
                ),
            ),
        )
        assertEquals(
            setOf("PROJ" to "base", "OTHER" to "ov", "PROJ" to null),
            pairs["comp"],
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
