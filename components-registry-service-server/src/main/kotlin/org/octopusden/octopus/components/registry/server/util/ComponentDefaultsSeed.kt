package org.octopusden.octopus.components.registry.server.util

import org.octopusden.octopus.components.registry.server.model.ComponentProfile

/**
 * The `component-defaults` paths a template's fields can take (Decision 6), and [applicable],
 * which narrows the defaults to one template by its classification and build system. The values
 * come from `templateDefaults()` in `config/`.
 */
object ComponentDefaultsSeed {
    const val COPYRIGHT = "copyright"
    const val VCS_TAG = "baseConfiguration.vcsEntries[0].tag"
    const val VCS_BRANCH = "baseConfiguration.vcsEntries[0].branch"

    /**
     * The defaults a template gets: copyright only for an explicit, external one, VCS tag and
     * branch only when its build system needs VCS. A `null` [buildSystem] counts as needing VCS.
     */
    fun applicable(
        defaults: Map<String, String>,
        classification: ComponentProfile.Classification,
        buildSystem: String?,
    ): Map<String, String> {
        val explicitExternal = classification.external && classification.explicit == ComponentProfile.Explicit.TRUE
        val needsVcs = buildSystem == null || buildSystem !in TemplateFields.NO_VCS_BUILD_SYSTEMS
        return defaults
            .filterKeys { it != COPYRIGHT || explicitExternal }
            .filterKeys { (it != VCS_TAG && it != VCS_BRANCH) || needsVcs }
    }
}
