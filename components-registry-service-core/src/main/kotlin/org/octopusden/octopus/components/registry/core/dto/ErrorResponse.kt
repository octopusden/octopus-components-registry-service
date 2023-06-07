package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

data class ErrorResponse @JsonCreator constructor(
        @JsonProperty("errorMessage") val errorMessage: String
)
