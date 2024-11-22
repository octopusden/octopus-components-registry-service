/**
 * Production like configuration.
 */
import org.octopusden.octopus.escrow.resolvers.ComposedConfigScript

class Aggregator extends ComposedConfigScript {
    def run() {
        include("Defaults.groovy")
        include("Tools.groovy")
        include("Components.groovy")
    }

    static final ANY_ARTIFACT = /[\w-\.]+/
}
