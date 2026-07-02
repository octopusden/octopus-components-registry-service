package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.mapper.NOT_AVAILABLE_EXTERNAL_REGISTRY

/**
 * CRS-C import bridge for the per-component external registry ⟷ skipCommitCheck fold.
 *
 * Sets BOTH `skipCommitCheck` and `vcsExternalRegistry` authoritatively from the DSL value — the
 * import is the source of truth, so a re-import/resync that changed the sentinel to a real registry
 * (or dropped it) must not leave a stale flag behind:
 *  - `"NOT_AVAILABLE"` → `skipCommitCheck = true`, `vcsExternalRegistry = null` (the sentinel is
 *    never stored);
 *  - any other value (including `null`) → `skipCommitCheck = false`, `vcsExternalRegistry = value`.
 *
 * The WHISKEY + NOT_AVAILABLE data-contradiction warning stays at the call site (it needs the build
 * system and the component key for the log line).
 */
internal fun applyImportedExternalRegistry(
    entity: ComponentEntity,
    importedExternalRegistry: String?,
) {
    if (importedExternalRegistry == NOT_AVAILABLE_EXTERNAL_REGISTRY) {
        entity.skipCommitCheck = true
        entity.vcsExternalRegistry = null
    } else {
        entity.skipCommitCheck = false
        entity.vcsExternalRegistry = importedExternalRegistry
    }
}
