package invalid.escrow

import org.octopusden.octopus.components.registry.dsl.component

component("test") {
    build {
        tools {
            database {
                oracle {
                    version = "[12,)"
                }
            }
        }
    }
}
