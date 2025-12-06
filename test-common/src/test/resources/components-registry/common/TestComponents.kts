import org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode
import org.octopusden.octopus.components.registry.dsl.*

component("TEST_PT_K_DB") {
    productType = "PT_K"
}

component("TESTONE") {
    escrow {
        buildTask = "clean build -x test"
        providedDependencies = listOf("test:test:1.1")
        additionalSources = listOf(
            "spa/.gradle",
            "spa/node_modules"
        )
        reusable = false
        generation = EscrowGenerationMode.UNSUPPORTED
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