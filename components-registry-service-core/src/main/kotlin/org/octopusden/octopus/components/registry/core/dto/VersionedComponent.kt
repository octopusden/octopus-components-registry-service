package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
class VersionedComponent
@JsonCreator
constructor(
    @JsonProperty("id") id: String,
    @JsonProperty("name") name: String?,
    @JsonProperty("version") val version: String,
    @JsonProperty(value = "componentOwner") componentOwner: String
) : Component(id, name, componentOwner)
