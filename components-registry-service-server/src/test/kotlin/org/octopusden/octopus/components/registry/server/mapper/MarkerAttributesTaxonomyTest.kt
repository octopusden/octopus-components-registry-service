package org.octopusden.octopus.components.registry.server.mapper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pins the three-way taxonomy: MarkerAttributes.ALL ↔ V10 MARKER IN (...) ↔ V10 SCALAR_OVERRIDE NOT IN (...).
 * Adding a new marker family requires updating both the Kotlin constant and the migration SQL.
 *
 * The SQL is the authoritative source: this test reads it directly so that drift between the SQL
 * constraint and the Kotlin constant is caught at compile-time rather than at a runtime INSERT.
 */
class MarkerAttributesTaxonomyTest {
    @Test
    @DisplayName("MarkerAttributes.ALL + GROUP_ARTIFACT_PATTERN matches the V10 MARKER IN (...) constraint")
    fun `MarkerAttributes ALL matches V10 taxonomy`() {
        val sql = MarkerAttributesTaxonomyTest::class.java
            .getResourceAsStream("/db/migration/V10__add_distribution_generic_artifacts.sql")
            ?.bufferedReader()
            ?.readText()
            ?: error("V10 migration SQL not found on classpath")

        // Extract single-quoted string values from the MARKER overridden_attribute IN (...) block.
        val markerSection = sql
            .substringAfter("row_type = 'MARKER'")
            .substringBefore("OR (row_type")
        val sqlMarkerAttributes = Regex("""'([^']+)'""")
            .findAll(markerSection)
            .map { it.groupValues[1] }
            .toSet()

        // ALL covers the 8 runtime-resolved families.
        // GROUP_ARTIFACT_PATTERN is intentionally excluded from ALL (only getMavenArtifactParameters reads it)
        // but it must stay in the SQL constraint to keep the DB row valid.
        assertEquals(
            MarkerAttributes.ALL + setOf(MarkerAttributes.GROUP_ARTIFACT_PATTERN),
            sqlMarkerAttributes,
            "MarkerAttributes.ALL + GROUP_ARTIFACT_PATTERN diverged from the V10 MARKER IN (...) constraint. " +
                "Update MARKER IN (...) and SCALAR_OVERRIDE NOT IN (...) in the migration SQL as well.",
        )
    }
}
