package org.octopusden.octopus.components.registry.server.dto.v4

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Pattern
import org.octopusden.octopus.components.registry.server.service.impl.TemplateInput

/** `POST /rest/api/4/component-templates/{id}/components`: the same body for a dry run and a create. */
data class TemplateComponentRequest(
    @field:Schema(description = "Values by parameter name; an absent parameter takes its default.")
    val parameters: Map<String, List<String>> = emptyMap(),
    @field:Schema(description = "Values by create-request path; only the template's overridable paths are accepted.")
    val overrides: Map<String, List<String>> = emptyMap(),
    @field:Pattern(regexp = JIRA_TASK_KEY_PATTERN, message = "must be a Jira task key like ABC-123")
    @field:Schema(description = "Optional Jira task key, as on any create; recorded on the audit row.")
    val jiraTaskKey: String? = null,
    @field:Schema(description = "Optional comment, as on any create; recorded on the audit row.")
    val changeComment: String? = null,
) {
    fun toInput() = TemplateInput(parameters, overrides, jiraTaskKey, changeComment)
}
