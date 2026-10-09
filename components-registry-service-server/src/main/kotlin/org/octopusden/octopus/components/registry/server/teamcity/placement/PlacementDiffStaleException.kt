package org.octopusden.octopus.components.registry.server.teamcity.placement

/** A Sync named a `diffId` that is not the last completed Diff (or no Diff has completed yet). */
class PlacementDiffStaleException : RuntimeException("diff replaced, re-run Diff") {
    companion object {
        const val ERROR_CODE = "placement-diff-stale"
    }
}
