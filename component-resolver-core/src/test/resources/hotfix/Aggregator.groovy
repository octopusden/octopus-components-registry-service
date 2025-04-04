/**
 * Production like configuration.
 */
import org.octopusden.octopus.escrow.resolvers.ComposedConfigScript

class Aggregator extends ComposedConfigScript {
    def run() {
        include("Defaults.groovy")
        include("MiddlewareComponents.groovy")
    }
}
