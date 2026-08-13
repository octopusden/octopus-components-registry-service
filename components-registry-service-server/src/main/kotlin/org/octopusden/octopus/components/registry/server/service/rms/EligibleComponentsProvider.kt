package org.octopusden.octopus.components.registry.server.service.rms

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.springframework.stereotype.Component

/** Component keys the RMS sweep considers — non-archived, build system `MAVEN` or `GRADLE`. */
fun interface EligibleComponentsProvider {
    fun listEligibleComponents(): List<String>
}

/** Injects a JPA repository — absent in no-db mode (SYS-047), so [RMSBuildParametersService] must be too. */
@Component
@ConditionalOnDatabaseEnabled
class JpaEligibleComponentsProvider(
    private val repository: ComponentConfigurationRepository,
) : EligibleComponentsProvider {
    override fun listEligibleComponents(): List<String> = repository.findNonArchivedMavenOrGradleComponentKeys()
}
