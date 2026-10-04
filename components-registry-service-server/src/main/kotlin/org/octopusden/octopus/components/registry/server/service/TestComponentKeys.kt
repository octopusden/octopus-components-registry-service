package org.octopusden.octopus.components.registry.server.service

import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service

/**
 * Keys of the components flagged `testComponent` (SYS-099), for the v1/v2/v3 read path. The flag
 * lives only in the DB (set through v4), not in the Groovy DSL, so it is read here whichever
 * resolver serves the component. Optional repository: the `no-db` boot mode has none, and then no
 * component is a test component.
 */
@Service
class TestComponentKeys(
    componentRepositoryProvider: ObjectProvider<ComponentRepository>,
) {
    private val componentRepository: ComponentRepository? by lazy { componentRepositoryProvider.getIfAvailable() }

    fun get(): Set<String> = componentRepository?.findTestComponentKeys()?.toSet() ?: emptySet()
}
