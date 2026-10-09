package org.octopusden.octopus.components.registry.server.dto.v4

import com.fasterxml.jackson.annotation.JsonInclude

/** A `POST /admin/reload-config` that failed, in part or whole. */
data class ReloadConfigFailureResponse(
    /** `config-validation`, `component-profiles` or `config-refresh`. */
    val error: String,
    val message: String,
    /** Absent only when `config-validation` is raised outside a reload. */
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val componentProfiles: ComponentProfilesReloadResponse?,
)
