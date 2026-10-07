package org.octopusden.octopus.components.registry.server.config

import org.octopusden.octopus.components.registry.server.profile.ComponentProfileCatalog
import org.octopusden.octopus.components.registry.server.profile.ProfileLoad
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cloud.context.refresh.ContextRefresher
import org.springframework.stereotype.Component

/**
 * One reload of the admin configuration: the Spring Cloud refresh, then the component profiles,
 * whatever the refresh's outcome (design Decision 4). Returns both outcomes; it throws nothing.
 *
 * The whole pair is one critical section: the profile source reads the environment a refresh
 * replaces, so a second refresh between this refresh and its profile load could hand that load a
 * mix of two configuration revisions.
 */
@Component
class AdminConfigReloader(
    private val refresh: () -> Set<String>,
    private val catalog: ComponentProfileCatalog,
) {
    @Autowired
    constructor(contextRefresher: ContextRefresher, catalog: ComponentProfileCatalog) : this(contextRefresher::refresh, catalog)

    @Synchronized
    fun reload(): Outcome {
        val refreshed = runCatching { refresh() }
        return Outcome(refreshed, catalog.reload())
    }

    data class Outcome(
        val refresh: Result<Set<String>>,
        val profiles: ProfileLoad,
    )
}
