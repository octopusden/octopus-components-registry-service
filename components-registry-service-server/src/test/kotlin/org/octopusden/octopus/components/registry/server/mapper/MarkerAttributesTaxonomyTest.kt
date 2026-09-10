package org.octopusden.octopus.components.registry.server.mapper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pins the three-way taxonomy: MarkerAttributes.ALL ↔ V8 MARKER IN (...) ↔ V8 SCALAR_OVERRIDE NOT IN (...).
 * Adding a new marker family requires updating both the Kotlin constant and the migration SQL.
 */
class MarkerAttributesTaxonomyTest {
    @Test
    @DisplayName("MarkerAttributes.ALL matches the taxonomy declared in V8 migration constraint")
    fun `MarkerAttributes ALL matches V8 taxonomy`() {
        val expectedV8Taxonomy = setOf(
            "vcs.settings",
            "distribution.maven",
            "distribution.fileUrl",
            "distribution.docker",
            "distribution.packages",
            "distribution.generic",
            "build.requiredTools",
            "build.buildTools",
        )
        assertEquals(
            expectedV8Taxonomy,
            MarkerAttributes.ALL,
            "MarkerAttributes.ALL diverged from the V8 taxonomy constraint. " +
                "Update MARKER IN (...) and SCALAR_OVERRIDE NOT IN (...) in the migration SQL as well.",
        )
    }
}
