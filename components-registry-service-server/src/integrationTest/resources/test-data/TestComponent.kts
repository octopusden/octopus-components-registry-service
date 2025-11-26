import org.octopusden.octopus.components.registry.dsl.*

component("test-component") {
    escrow {
        additionalSources = listOf(
            "console-web-ui/src/main/react/node_modules"
        )
    }
}
