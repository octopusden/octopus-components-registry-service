package invalid.escrow

import org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode
import org.octopusden.octopus.components.registry.dsl.component

component("Component") {
    components {
        component("sub-component-one") {
            version("[1.0,1.0.336)") {
                escrow {
                    generation = EscrowGenerationMode.AUTO
                }
            }
        }
    }
}
