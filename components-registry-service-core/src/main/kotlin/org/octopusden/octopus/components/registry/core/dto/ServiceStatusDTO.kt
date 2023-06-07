package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.*

@JsonIgnoreProperties(ignoreUnknown = true)
data class ServiceStatusDTO @JsonCreator constructor(
    @JsonProperty("cacheUpdatedAt") val cacheUpdatedAt: Date,
    @JsonProperty("serviceMode") val serviceMode: ServiceMode,
    @JsonProperty("versionControlRevision") val versionControlRevision: String?
)
