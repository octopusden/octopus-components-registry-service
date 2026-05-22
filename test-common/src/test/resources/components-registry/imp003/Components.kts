import org.octopusden.octopus.components.registry.dsl.*

component("TEST_BUILD_KTS_ONLY") {
    productType = "PT_K"
    build {
        tools {
            database {
                oracle {
                    version = "12.0"
                }
            }
        }
    }
}
