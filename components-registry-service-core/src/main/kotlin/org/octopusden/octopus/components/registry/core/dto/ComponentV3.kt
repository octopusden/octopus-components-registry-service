package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.octopusden.octopus.components.registry.api.VersionedComponentConfiguration

// TODO Discuss: It would be create to declare interface not class
@JsonIgnoreProperties(ignoreUnknown = true)
class ComponentV3(
    @JsonProperty("component") val component: ComponentV2,
    /**
     * Returns variants of the component mapped to version ranges.
     */
    @JsonProperty("variants") val variants: Map<String, VersionedComponentConfiguration>,
)
