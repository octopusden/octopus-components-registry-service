package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.mapper.ALL_VERSIONS
import org.octopusden.octopus.components.registry.server.util.parseMavenGavEntry
import org.octopusden.octopus.components.registry.server.util.splitCsv
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig

/**
 * Fail-loud guard for malformed Maven coordinates in `distribution.GAV`
 * (TD-011 / #349).
 *
 * The import path ([ImportServiceImpl.attachMavenArtifacts]) parses each
 * comma-separated `distribution.GAV` entry with `parseMavenGavEntry` and
 * **silently dropped** (`?: continue`) any entry that isn't a valid
 * `groupId:artifactId[:ext[:classifier]]` — a groupId-only value (no `:`) or a
 * blank group/artifact segment. That silent drop causes value-level divergence
 * on the distribution endpoints (a coordinate present in the DSL just vanishes
 * from the DB-sourced response), invisible to the compat baseline unless such a
 * component happens to be in the smoke/replay window.
 *
 * This guard converts that silent drop into a loud, actionable import failure
 * that names the component, the raw entry, and the reason — symmetric with the
 * v4 write-path check ([ComponentManagementServiceImpl.validateMavenArtifactCoordinate]).
 * `file://` / `http(s)://` entries are NOT Maven coordinates (they are handled by
 * [ImportServiceImpl.attachFileUrlArtifacts]) and are skipped here, matching the
 * import's own URL-first branching.
 *
 * NOTE (TD-011): the doc's alternative resolution is to *support* a groupId-only
 * `distribution.GAV` (nullable artifactId, round-tripped). This guard chooses the
 * fail-loud resolution instead. If groupId-only support is ever added, the
 * `parts.size < 2` case must be accepted here (and in `parseMavenGavEntry`)
 * rather than reported.
 */
internal fun validateDistributionCoordinates(
    componentKey: String,
    configs: List<EscrowModuleConfig>,
) {
    val violations = configs.flatMap { cfg ->
        val gavCsv = cfg.distribution?.GAV() ?: return@flatMap emptyList<String>()
        splitCsv(gavCsv)
            .filterNot { it.startsWith("file://") || it.startsWith("http://") || it.startsWith("https://") }
            .filter { parseMavenGavEntry(it) == null }
            .map { entry -> "'$entry' (range '${cfg.versionRangeString ?: ALL_VERSIONS}')" }
    }

    if (violations.isEmpty()) return

    throw IllegalStateException(
        "Component '$componentKey' has malformed distribution.GAV Maven coordinate(s): " +
            "${violations.joinToString("; ")}. Each entry must be " +
            "'groupId:artifactId[:extension[:classifier]]' (a groupId-only value with no ':' is " +
            "not supported); fix the DSL or use a file://|http(s):// URL for a fileUrl artifact. " +
            "See TD-011 / CRS #349.",
    )
}
