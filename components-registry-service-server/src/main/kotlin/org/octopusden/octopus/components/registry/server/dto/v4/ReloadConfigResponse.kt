package org.octopusden.octopus.components.registry.server.dto.v4

/** A `POST /admin/reload-config` that refreshed the configuration and applied the component profiles. */
data class ReloadConfigResponse(
    /** Always `reloaded`. */
    val status: String,
    val changedKeys: List<String>,
    val componentProfiles: ComponentProfilesReloadResponse,
)
