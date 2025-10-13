/**
 * Production like configuration.
 */
import org.octopusden.octopus.escrow.resolvers.ComposedConfigScript

class Aggregator extends ComposedConfigScript {
    def run() {
        include("Defaults.groovy")
        include("MiddlewareComponents.groovy")
        include("Archived.groovy")
    }

    static final ANY_ARTIFACT = /[\w-\.]+/
    static final ALL_VERSIONS = "(,0),[0,)"
    static final pkgj_version = "3.38.30-0004"

    // used in cproject opt
    static final TEST_COMPONENT2_LEFT_VERSION_RANGE_BOUND_FOR_DEFAULT_BRANCH = "3.50.20"
    static final TEST_COMPONENT2_DEFAULT_RANGE = "[3.50.20,)"

    static final C_VERSION_RANGE_BOUND_FOR_DEFAULT_BRANCH = "03.50.20"
    static final C_DEFAULT_RANGE = "[03.50.20,)"
}
