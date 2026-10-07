package org.octopusden.octopus.components.registry.server.template

import org.octopusden.octopus.components.registry.core.exceptions.CrossComponentConflictException
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentCreateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentDetailResponse
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException

/** What a creator sends: values by parameter, overrides by template path, and today's change metadata. */
data class TemplateInput(
    val parameters: Map<String, List<String>> = emptyMap(),
    val overrides: Map<String, List<String>> = emptyMap(),
    val jiraTaskKey: String? = null,
    val changeComment: String? = null,
)

/**
 * The outcome of a dry run or a create. [rendered] is absent when a parameter check failed;
 * [created] is present only for a create that committed.
 */
data class TemplateRun(
    val parameterProblems: List<ParameterProblem>,
    val rendered: RenderedTemplate?,
    val problems: List<TemplateProblem>,
    val created: ComponentDetailResponse?,
) {
    val valid: Boolean
        get() = parameterProblems.isEmpty() && problems.isEmpty()
}

/**
 * Runs a template through the dry-run steps (Decision 8): parameter checks, rendering, the
 * template's rules and fixed labels, then today's [create] on the rendered request. The create
 * runs in its own transaction, rolled back unless [run] is asked to commit and nothing failed
 * before it, so a dry run and a create cannot drift apart.
 *
 * A failure of today's create is reported as a problem whatever its status (400, 403, 409, 422);
 * any other exception propagates. An override on a path the template does not list is refused
 * with [IllegalArgumentException] before anything runs. Who may run or override a template is the
 * caller's check, not this one's.
 */
class TemplateDryRun(
    private val parameters: ParameterChecker,
    private val lists: ListValues,
    private val defaults: () -> Map<String, String>,
    private val create: (ComponentCreateRequest) -> ComponentDetailResponse,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)

    fun run(
        template: ComponentTemplate,
        input: TemplateInput,
        caller: String,
        commit: Boolean,
    ): TemplateRun {
        input.overrides.keys.firstOrNull { it !in template.overridable }?.let {
            throw IllegalArgumentException("$it: not overridable in template '${template.id}'")
        }
        val checked = parameters.check(template, input.parameters, caller)
        if (checked.problems.isNotEmpty()) return TemplateRun(checked.problems, null, emptyList(), null)
        val rendered =
            TemplateRenderer.render(template, checked.values, input.overrides, defaults(), input.jiraTaskKey, input.changeComment)
        val beforeCreate =
            TemplateProblems.rules(template, rendered) + TemplateProblems.fixedLabels(template, lists.values(TemplateList.LABELS))
        val commits = commit && beforeCreate.isEmpty()
        return when (val outcome = createStep(rendered.request, commits)) {
            is CreateOutcome.Created -> TemplateRun(emptyList(), rendered, beforeCreate, outcome.component.takeIf { commits })
            is CreateOutcome.Failed ->
                TemplateRun(emptyList(), rendered, beforeCreate + TemplateProblems.create(outcome.message, rendered), null)
        }
    }

    private sealed interface CreateOutcome {
        data class Created(
            val component: ComponentDetailResponse,
        ) : CreateOutcome

        data class Failed(
            val message: String,
        ) : CreateOutcome
    }

    private fun createStep(
        request: ComponentCreateRequest,
        commit: Boolean,
    ): CreateOutcome =
        try {
            // Rollback-only on the outer status rolls back silently; a failure thrown out of the
            // create rolls back through the template, so neither path leaves anything written.
            val component =
                transactions.execute { status ->
                    if (!commit) status.setRollbackOnly()
                    create(request)
                }
            CreateOutcome.Created(checkNotNull(component))
        } catch (e: IllegalArgumentException) {
            CreateOutcome.Failed(e.message.orEmpty())
        } catch (e: ResponseStatusException) {
            CreateOutcome.Failed(e.reason ?: e.message.orEmpty())
        } catch (e: CrossComponentConflictException) {
            CreateOutcome.Failed(e.message.orEmpty())
        } catch (e: NotFoundException) {
            CreateOutcome.Failed(e.message.orEmpty())
        }
}
