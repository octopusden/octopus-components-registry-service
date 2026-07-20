package org.octopusden.octopus.components.registry.server.architecture.fixtures.db

/**
 * Fixture for `ArchitectureFitnessRegressionTest`: a `..db..` class with no legacy Groovy
 * dependency — the rule must accept it (green case), so the rule is not vacuously failing.
 */
class DbCleanProbe {
    fun sink(value: String): String = value
}
