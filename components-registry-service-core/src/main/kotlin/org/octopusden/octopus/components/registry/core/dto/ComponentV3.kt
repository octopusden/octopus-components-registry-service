package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.octopusden.octopus.components.registry.api.VersionedComponentConfiguration

@JsonIgnoreProperties(ignoreUnknown = true)
class ComponentV3(
    @JsonProperty("component") val component: ComponentV2,
    /** Variants keyed by version range. */
    @JsonProperty("variants") val variants: Map<String, VersionedComponentConfiguration>,
)
