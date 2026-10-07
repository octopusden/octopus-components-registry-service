package org.octopusden.octopus.components.registry.server.dto.v4

import io.swagger.v3.oas.annotations.media.Schema
import org.octopusden.octopus.components.registry.server.profile.ComponentProfile
import org.octopusden.octopus.components.registry.server.profile.ProfileAvailability

/** `GET /rest/api/4/component-profiles`: the live profiles in order, for the current user. */
data class ComponentProfilesResponse(
    val profiles: List<ComponentProfileResponse>,
)

data class ComponentProfileResponse(
    val id: String,
    @field:Schema(description = "`regular`; templates are listed with their own kind once supported.")
    val kind: String,
    val title: String,
    val description: String,
    val classification: Classification,
    @field:Schema(description = "Rules a create naming this profile is checked against; empty when the profile has none.")
    val rules: List<FieldRule>,
    val usable: Boolean,
    @field:Schema(description = "Why the current user may not use the profile; absent when usable.")
    val unusableReason: String? = null,
) {
    data class Classification(
        val external: Boolean,
        @field:Schema(allowableValues = ["true", "false", "ask"])
        val explicit: String,
        val solution: Boolean,
    )

    data class FieldRule(
        @field:Schema(description = "Create-request path, e.g. `name` or `baseConfiguration.jira.projectKey`.")
        val path: String,
        @field:Schema(
            description =
                "Regular expression the whole value must match, in syntax both Java (the registry) and " +
                    "JavaScript accept. A client that cannot compile it skips its own check; the create decides.",
        )
        val pattern: String,
        val message: String,
    )

    companion object {
        fun from(
            profile: ComponentProfile,
            availability: ProfileAvailability.Availability,
        ) = ComponentProfileResponse(
            id = profile.id,
            kind = "regular",
            title = profile.title,
            description = profile.description,
            classification =
                Classification(
                    external = profile.classification.external,
                    explicit = profile.classification.explicit.name
                        .lowercase(),
                    solution = profile.classification.solution,
                ),
            rules = profile.rules.map { FieldRule(it.path, it.pattern, it.message) },
            usable = availability.usable,
            unusableReason = availability.reason,
        )
    }
}
