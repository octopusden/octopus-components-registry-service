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

// Reproduces the production-DSL shape of database-backed components where the
// KTS file carries the full `build { tools { database { oracle {...} } } }`
// block but the Groovy counterpart has NO `build` block (relies on
// `Defaults.groovy` for build defaults). PR-D+E's `attachBuildToolBeans` must
// still persist the Oracle bean for this shape — covered by IMP-003.
// `productType` mirrors `cards_db`/`dwh_db` shape (presence of any productType,
// not the specific value — the test environment registers PT_K-family keys only).
component("TEST_COMPONENT_BUILD_TOOLS_KTS_ONLY") {
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