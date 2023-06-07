package newConfig

import org.octopusden.octopus.escrow.resolvers.ComposedConfigScript

class Aggregator extends ComposedConfigScript {
    def run() {
        include("componentConfig.groovy");
    }

    static DEFAULT_TAG = "zenit"

}