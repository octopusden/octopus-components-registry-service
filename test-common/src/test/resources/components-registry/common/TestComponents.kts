import org.octopusden.octopus.components.registry.dsl.*

component("pt_k_db") {
    productType = "PT_K"
}

component("TESTONE") {
    escrow {
        providedDependencies = listOf("test:test:1.1")
        additionalSources = listOf(
            "spa/.gradle",
            "spa/node_modules"
        )
    }
}

component("TEST_COMPONENT_BUILD_TOOLS") {
    build {
        tools {
            database {
                oracle {
                    version = "11.2"
                }
            }
            product {
                type("PT_K") {
                    version = "03.49"
                }
            }
        }
    }
}