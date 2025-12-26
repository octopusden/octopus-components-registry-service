package labels.valid

import org.octopusden.octopus.escrow.resolvers.ComposedConfigScript

class Aggregator extends ComposedConfigScript {
    def run() {
        include("Defaults.groovy")
    }

    static final ANY_ARTIFACT = /[\w-\.]+/
}
