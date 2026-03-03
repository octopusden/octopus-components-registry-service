package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.*

@JsonIgnoreProperties(ignoreUnknown = true)
data class ServiceStatusDTO (
    @JsonProperty("cacheUpdatedAt") val cacheUpdatedAt: Date,
    @JsonProperty("serviceMode") val serviceMode: ServiceMode,
    @JsonProperty("versionControlRevision") val versionControlRevision: String?
)
