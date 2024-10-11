import org.octopusden.octopus.escrow.resolvers.ComposedConfigScript

class Aggregator extends ComposedConfigScript {
    def run() {
        include("Defaults.groovy");
        include("Tools.groovy");
        include("TestComponents.groovy");
    }

    static final ANY_ARTIFACT = /[\w-\.]+/
    static final ALL_VERSIONS = "(,0),[0,)"
    static final pkgj_version = "3.38.30-0004"
}
