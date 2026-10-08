package org.octopusden.octopus.components.registry.server.dto.v4

import io.swagger.v3.oas.annotations.media.Schema
import org.octopusden.octopus.components.registry.server.template.TemplateRun

/**
 * A dry run's result, also the 422 body of a create that found a problem. [component],
 * [sources] and [overridden] are absent when a parameter check failed, since rendering stops there.
 */
data class TemplateRunResponse(
    val valid: Boolean,
    val parameterProblems: List<ParameterProblem>,
    @field:Schema(description = "The create request the template rendered.")
    val component: ComponentCreateRequest?,
    @field:Schema(
        description = "For each set create-request path, the parameters its value came from; empty for a fixed or defaulted value.",
    )
    val sources: Map<String, List<String>>?,
    @field:Schema(description = "The create-request paths an override set; their sources are empty.")
    val overridden: List<String>?,
    val problems: List<Problem>,
) {
    @Schema(name = "TemplateParameterProblem")
    data class ParameterProblem(
        val parameter: String,
        @field:Schema(description = "The check that failed, P1 to P9.")
        val check: String,
        val message: String,
    )

    @Schema(name = "TemplateProblem")
    data class Problem(
        @field:Schema(description = "The create-request paths the problem concerns; empty when it names none.")
        val fields: List<String>,
        @field:Schema(description = "The parameters to change to fix it.")
        val parameters: List<String>,
        @field:Schema(description = "True when only the template's author can fix it.")
        val templateProblem: Boolean,
        val message: String,
    )

    companion object {
        fun from(run: TemplateRun) =
            TemplateRunResponse(
                valid = run.valid,
                parameterProblems =
                    run.parameterProblems.map { ParameterProblem(it.parameter, it.check.name.substringBefore('_'), it.message) },
                component = run.rendered?.request,
                sources = run.rendered?.sources?.mapValues { it.value.toList() },
                overridden = run.rendered?.overridden?.toList(),
                problems = run.problems.map { Problem(it.fields, it.parameters.toList(), it.templateProblem, it.message) },
            )
    }
}
