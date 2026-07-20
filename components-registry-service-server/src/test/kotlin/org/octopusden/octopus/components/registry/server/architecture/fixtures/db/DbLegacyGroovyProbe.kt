package org.octopusden.octopus.components.registry.server.architecture.fixtures.db

import org.octopusden.octopus.escrow.model.Distribution

/**
 * Fixture for `ArchitectureFitnessRegressionTest`: a class in a `..db..` package depending on a
 * Groovy-AUTHORED legacy class (`org.octopusden.octopus.escrow.model.Distribution`) whose compiled
 * name carries no "Groovy" token. Proves the rule denies by legacy PACKAGE boundary — the old
 * name-only match (`.*[Gg]roovy.*`) would have missed this dependency.
 */
class DbLegacyGroovyProbe {
    fun sink(distribution: Distribution?): Distribution? = distribution
}
