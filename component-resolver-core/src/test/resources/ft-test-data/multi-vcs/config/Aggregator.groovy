import org.octopusden.octopus.escrow.resolvers.ComposedConfigScript

class Aggregator extends ComposedConfigScript {
    def run() {
        include("Defaults.groovy");
        include("Tools.groovy")
        include("TestModules.groovy");
    }

    static final ANY_ARTIFACT = /[\w-\.]+/
    static final ALL_VERSIONS = "(,0),[0,)"
}
