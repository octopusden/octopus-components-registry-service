package org.octopusden.octopus.components.registry.server.util

import org.octopusden.octopus.components.registry.server.model.ComponentTemplate
import org.octopusden.octopus.components.registry.server.util.CreateRequestPaths

/**
 * One problem a dry run found after rendering. [fields] are the template paths it concerns, and
 * [parameters] the ones they were built from — what a creator changes to fix it. A
 * [templateProblem] concerns only fields the template fixes, defaults or leaves unset, so only
 * the template's author can fix it.
 */
data class TemplateProblem(
    val fields: List<String>,
    val parameters: Set<String>,
    val templateProblem: Boolean,
    val message: String,
)

/**
 * The problems a dry run reports after rendering (Decisions 7 and 9), attributed through the
 * rendered template's sources. Pure.
 */
object TemplateProblems {
    private const val LABELS = "labels"

    /** Every rule of [template] checked on the rendered request; every failure kept. */
    fun rules(
        template: ComponentTemplate,
        rendered: RenderedTemplate,
    ): List<TemplateProblem> =
        template.rules
            .filterNot { it.regex.matches(CreateRequestPaths.read(rendered.request, it.path).orEmpty()) }
            .map { attribute(template, listOf(it.path), rendered, "${it.path}: ${it.message}") }

    /** A failure of today's create, attributed by the field its message names. */
    fun create(
        template: ComponentTemplate,
        message: String,
        rendered: RenderedTemplate,
    ): TemplateProblem {
        val paths = CreateFailureFields.of(message)
        return attribute(template, paths.filter { it in rendered.fields }.ifEmpty { paths }, rendered, message)
    }

    /** Each VCS entry whose `vcsPath` rendered empty, which today's create would store as a VCS root without a repository. */
    fun blankVcsPaths(
        template: ComponentTemplate,
        rendered: RenderedTemplate,
    ): List<TemplateProblem> =
        rendered.request.baseConfiguration
            ?.vcsEntries
            .orEmpty()
            .withIndex()
            .filter { it.value.vcsPath.isBlank() }
            .map {
                val path = "baseConfiguration.vcsEntries[${it.index}].vcsPath"
                attribute(template, listOf(path), rendered, "$path: required, and it rendered empty")
            }

    /**
     * The template's own labels missing from the labels dictionary. Checked here because today's
     * create adds an unknown label to the dictionary rather than rejecting it. A blank label is
     * skipped: rendering drops it (R4).
     */
    fun fixedLabels(
        template: ComponentTemplate,
        dictionary: Set<String>,
    ): List<TemplateProblem> =
        template.fields[LABELS]
            ?.expressions
            .orEmpty()
            .filter { it.parameters.isEmpty() }
            .map { it.literal() }
            .filter { it.isNotBlank() && it !in dictionary }
            .map { TemplateProblem(listOf(LABELS), emptySet(), templateProblem = true, "$LABELS: '$it' is not in the labels dictionary") }

    /** A field that rendered empty has no source; the parameters it is built from are what the creator changes. */
    private fun attribute(
        template: ComponentTemplate,
        fields: List<String>,
        rendered: RenderedTemplate,
        message: String,
    ): TemplateProblem {
        val parameters =
            fields.filterNot { it in rendered.overridden }.flatMapTo(linkedSetOf()) {
                rendered.sources[it] ?: template.fields[it]?.parameters.orEmpty()
            }
        val templateProblem = fields.isNotEmpty() && parameters.isEmpty() && fields.none { it in rendered.overridden }
        return TemplateProblem(fields, parameters, templateProblem, message)
    }
}
