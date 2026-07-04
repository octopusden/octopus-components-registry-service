package org.octopusden.octopus.components.registry.server.util

import org.octopusden.octopus.components.registry.server.entity.ArtifactIdMode
import org.octopusden.octopus.components.registry.server.entity.ComponentArtifactMappingEntity

/**
 * Re-composes the NORMALIZED ownership model (one Maven groupId per stored row) back into
 * the legacy v1–v3 wire shape (a single `(groupIdPattern, artifactIdPattern)` pair, whose
 * groupId may be a comma-list).
 *
 * Storage canonicalization (import + v4 write) splits a comma group-list `"a,b"` into one
 * [ComponentArtifactMappingEntity] per group, sharing the SAME mode / tokens / range. The
 * forward render surfaces (`/maven-artifacts`, the escrow module view, view-as-code) must
 * stay byte-identical to the legacy wire, so they collapse those split rows back into one
 * legacy pair by RE-JOINING the group tokens.
 *
 * A "run" is a maximal CONTIGUOUS (in `sortOrder`) sequence of split-EQUIVALENT rows — the
 * fragments of ONE original legacy `(groupId, artifactId)` pair. Equivalence (the run key):
 *  - `EXPLICIT` → identical ordered literal token list (as stored, already trimmed).
 *  - `ALL`      → same mode (no tokens).
 *  - `ALL_EXCEPT_CLAIMED` → NEVER equivalent to anything, not even another ALL_EXCEPT row:
 *    its forward artifact pattern is per-group sibling-aware, so two ALL_EXCEPT groups are
 *    genuinely distinct pairs. Each stays its own run. (Single-group in prod anyway.)
 *
 * CONTIGUITY (not global grouping) preserves author/request order and never merges two
 * independent same-token mappings that a third, different mapping separates.
 */
internal object ArtifactOwnershipGrouping {

    /**
     * Collapse a version-range's EFFECTIVE ownership mappings into contiguous legacy-pair runs
     * (see class doc). Input order is irrelevant — rows are sorted by `sortOrder` first, so the
     * FIRST run always begins at the PRIMARY (lowest-`sortOrder`) mapping, matching the legacy
     * "primary pair per range" contract.
     */
    fun collapseRuns(mappings: List<ComponentArtifactMappingEntity>): List<List<ComponentArtifactMappingEntity>> {
        if (mappings.isEmpty()) return emptyList()
        val sorted = mappings.sortedBy { it.sortOrder }
        val runs = mutableListOf<MutableList<ComponentArtifactMappingEntity>>()
        var prevKey: RunKey? = null
        sorted.forEachIndexed { index, mapping ->
            val key = keyOf(mapping, index)
            if (runs.isEmpty() || key != prevKey) {
                runs.add(mutableListOf(mapping))
            } else {
                runs.last().add(mapping)
            }
            prevKey = key
        }
        return runs
    }

    /** Re-join a run's group patterns in `sortOrder` order with commas (the legacy CSV group form). */
    fun joinGroupPattern(run: List<ComponentArtifactMappingEntity>): String =
        run.sortedBy { it.sortOrder }.joinToString(",") { it.groupPattern }

    private fun tokensOf(mapping: ComponentArtifactMappingEntity): List<String> =
        mapping.tokens.sortedBy { it.sortOrder }.map { it.artifactPattern }

    private fun keyOf(mapping: ComponentArtifactMappingEntity, index: Int): RunKey =
        when (ArtifactIdMode.valueOf(mapping.artifactIdMode)) {
            // Per-row unique index ⇒ never equal to any neighbour ⇒ never merged.
            ArtifactIdMode.ALL_EXCEPT_CLAIMED -> RunKey(mapping.artifactIdMode, emptyList(), index)
            ArtifactIdMode.EXPLICIT -> RunKey(mapping.artifactIdMode, tokensOf(mapping), null)
            ArtifactIdMode.ALL -> RunKey(mapping.artifactIdMode, emptyList(), null)
        }

    /** Split-equivalence identity for a mapping; [uniq] is set only for the never-merge modes. */
    private data class RunKey(val mode: String, val tokens: List<String>, val uniq: Int?)
}
