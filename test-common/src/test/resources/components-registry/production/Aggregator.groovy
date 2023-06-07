/**
 * Components registry cut copy.
 */
import org.octopusden.octopus.escrow.resolvers.ComposedConfigScript

class Aggregator extends ComposedConfigScript {
    def run() {
        include("Defaults.groovy")
        include("Archived.groovy")
        include("MiddlewareComponents.groovy")
    }

    static final ANY_ARTIFACT = /[\w-\.]+/
    static final ALL_VERSIONS = "(,0),[0,)"

    // used in cproject opt
    static final TEST_COMPONENT2_LEFT_VERSION_RANGE_BOUND_FOR_DEFAULT_BRANCH = "3.54.30"
    static final TEST_COMPONENT2_DEFAULT_RANGE = "[3.54.30,)"

    static final C_VERSION_RANGE_BOUND_FOR_DEFAULT_BRANCH = "03.54.30"
    static final C_DEFAULT_RANGE = "[03.54.30,)"
}
