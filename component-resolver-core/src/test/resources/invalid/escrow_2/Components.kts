package invalid.escrow

import org.octopusden.octopus.components.registry.dsl.component

component("dwh_db") {
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
