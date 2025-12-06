package invalid.escrow

import org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode
import org.octopusden.octopus.components.registry.dsl.component

component("Component") {
    components {
        component("sub-component-one") {
            escrow {
                generation = EscrowGenerationMode.AUTO
            }
        }
    }
}
