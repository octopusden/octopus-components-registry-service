package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.*

data class VcsRootDateDTO @JsonCreator constructor(
    @JsonProperty("root") val root: VersionControlSystemRootDTO,
    @JsonProperty("date") val date: Date? = null
)
