import org.octopusden.octopus.escrow.resolvers.ComposedConfigScript

class Aggregator extends ComposedConfigScript {
    def run() {
        include("Defaults.groovy")
        include("CopyrightConfig.groovy")
    }
    static final ANY_ARTIFACT = /[\w-\.]+/
}
