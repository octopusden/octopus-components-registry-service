package org.octopusden.octopus.components.registry.server.dto.v4

import org.octopusden.octopus.components.registry.server.model.ProfileLoad

/** The `componentProfiles` part of a `POST /admin/reload-config` response. */
data class ComponentProfilesReloadResponse(
    /** `applied` when the profiles in use were replaced, `failed` when they were kept. */
    val status: String,
    val problems: List<String>,
    val entries: List<Entry>,
) {
    data class Entry(
        val id: String,
        val kind: String?,
        /** `live` or `failed`. */
        val status: String,
        val problems: List<String>,
    )

    companion object {
        fun from(load: ProfileLoad) =
            ComponentProfilesReloadResponse(
                status = if (load.usable) "applied" else "failed",
                problems = load.problems,
                entries = load.entries.map { Entry(it.id, it.kind, it.status.name.lowercase(), it.problems) },
            )
    }
}
