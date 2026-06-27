package org.octopusden.octopus.components.registry.server.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.entity.ArtifactIdMode

/**
 * Wire vs export rendering of the ownership mode → legacy `artifactIdPattern` (#357). The two
 * targets diverge ONLY for ALL_EXCEPT_CLAIMED: the wire form is the plain catch-all (the v3 resolver
 * yields via specificity); the export/preview form is a negative-lookahead over the EXPLICIT siblings
 * so re-feeding the DSL to the legacy strict validator does not reintroduce the overlap.
 */
class ArtifactOwnershipRenderingTest {

    @Test
    @DisplayName("wire render: ALL and ALL_EXCEPT_CLAIMED are the plain catch-all")
    fun wireCatchAll() {
        assertEquals("[\\w-\\.]+", ArtifactOwnershipRendering.renderArtifactPattern(ArtifactIdMode.ALL, emptyList()))
        assertEquals(
            "[\\w-\\.]+",
            ArtifactOwnershipRendering.renderArtifactPattern(ArtifactIdMode.ALL_EXCEPT_CLAIMED, emptyList()),
        )
    }

    @Test
    @DisplayName("EXPLICIT render escapes dots and comma-joins (literal match, not regex)")
    fun explicitEscaped() {
        assertEquals(
            "foo\\.bar,baz",
            ArtifactOwnershipRendering.renderArtifactPattern(ArtifactIdMode.EXPLICIT, listOf("foo.bar", "baz")),
        )
    }

    @Test
    @DisplayName("export render: ALL_EXCEPT_CLAIMED becomes an anchored negative-lookahead over escaped siblings")
    fun exportLookahead() {
        assertEquals(
            "(?!(?:foo\\.bar|baz)\$)[\\w-\\.]+",
            ArtifactOwnershipRendering.renderExportPattern(
                ArtifactIdMode.ALL_EXCEPT_CLAIMED,
                emptyList(),
                listOf("foo.bar", "baz"),
            ),
        )
    }

    @Test
    @DisplayName("export render: ALL_EXCEPT_CLAIMED with no siblings degrades to the plain catch-all")
    fun exportNoSiblings() {
        assertEquals(
            "[\\w-\\.]+",
            ArtifactOwnershipRendering.renderExportPattern(ArtifactIdMode.ALL_EXCEPT_CLAIMED, emptyList(), emptyList()),
        )
    }

    @Test
    @DisplayName("export render: ALL and EXPLICIT match the wire render")
    fun exportMatchesWireForNonCatchAllYield() {
        assertEquals("[\\w-\\.]+", ArtifactOwnershipRendering.renderExportPattern(ArtifactIdMode.ALL, emptyList(), listOf("x")))
        assertEquals(
            "lib\\.core",
            ArtifactOwnershipRendering.renderExportPattern(ArtifactIdMode.EXPLICIT, listOf("lib.core"), listOf("x")),
        )
    }
}
