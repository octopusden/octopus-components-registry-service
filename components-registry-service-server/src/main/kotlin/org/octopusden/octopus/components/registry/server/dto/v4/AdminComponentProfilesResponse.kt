package org.octopusden.octopus.components.registry.server.dto.v4

import io.swagger.v3.oas.annotations.media.Schema

/**
 * `GET /rest/api/4/admin/component-profiles`: every configured profile and template in use, live or
 * failed, with what was read for it. When the last reload failed, [entries] are the ones still in
 * use, and [lastLoad] says why.
 */
data class AdminComponentProfilesResponse(
    @field:Schema(description = "The configuration version the config server reported; absent when not served by one.")
    val configVersion: String?,
    @field:Schema(description = "The outcome of the last load or reload, applied or not.")
    val lastLoad: ComponentProfilesReloadResponse,
    val entries: List<Entry>,
) {
    @Schema(name = "AdminComponentProfileEntry")
    data class Entry(
        val id: String,
        val kind: String?,
        @field:Schema(allowableValues = ["live", "failed"])
        val status: String,
        val problems: List<String>,
        @field:Schema(description = "The parsed regular profile, when live.")
        val profile: ComponentProfileResponse?,
        @field:Schema(description = "The parsed template, when live.")
        val template: ComponentTemplateResponse?,
        @field:Schema(description = "The entry's configuration as read, in YAML.")
        val configuration: String,
    )
}
