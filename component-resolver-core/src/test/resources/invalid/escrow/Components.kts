package invalid.escrow

import org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode
import org.octopusden.octopus.components.registry.dsl.component

component("Component") {
    escrow {
        generation = EscrowGenerationMode.AUTO
    }
}
