package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class ErrorResponse (
        @JsonProperty("errorMessage") val errorMessage: String
)
