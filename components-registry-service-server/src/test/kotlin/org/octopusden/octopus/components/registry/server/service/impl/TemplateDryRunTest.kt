package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.service.impl.ActiveStatus
import org.octopusden.octopus.components.registry.server.support.EXAMPLE_DEFAULTS
import org.octopusden.octopus.components.registry.server.support.parsedTemplate
import org.octopusden.octopus.components.registry.server.util.TemplateFields
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import java.sql.SQLException

/** Begins, commits and rolls back nothing: the dry run's transaction handling is not under test here. */
private class NoTransactions : AbstractPlatformTransactionManager() {
    override fun doGetTransaction(): Any = Any()

    override fun doBegin(
        transaction: Any,
        definition: TransactionDefinition,
    ) = Unit

    override fun doCommit(status: DefaultTransactionStatus) = Unit

    override fun doRollback(status: DefaultTransactionStatus) = Unit
}

class TemplateDryRunTest {
    private fun dryRun(failure: DataIntegrityViolationException) =
        TemplateDryRun(
            parameters = ParameterChecker({ list -> TemplateFields.staticValues(list) ?: setOf("plugin") }) { ActiveStatus.ACTIVE },
            lists = { list -> TemplateFields.staticValues(list) ?: setOf("plugin") },
            defaults = { EXAMPLE_DEFAULTS },
            create = { throw failure },
            transactionManager = NoTransactions(),
        )

    private fun run(failure: DataIntegrityViolationException): TemplateRun {
        val input =
            TemplateInput(
                parameters =
                    mapOf(
                        "CLIENT_CODE" to listOf("ACME"),
                        "PLUGIN_CODE" to listOf("CORE"),
                        "PLUGIN_NAME" to listOf("Core API"),
                    ),
            )
        return dryRun(failure).run(parsedTemplate(), input, caller = "jdoe", commit = true)
    }

    @Test
    @DisplayName("Decision 8: a unique-key race in today's create is a problem on name, not an error response")
    fun uniqueKeyRaceIsAProblem() {
        val run = run(DataIntegrityViolationException("could not execute statement", SQLException("duplicate key", "23505")))

        assertFalse(run.valid)
        assertEquals(listOf("name"), run.problems.single().fields)
        assertEquals(setOf("CLIENT_CODE", "PLUGIN_CODE"), run.problems.single().parameters)
    }

    @Test
    @DisplayName("any other integrity failure, such as a value too long, is a problem on no field, worded as today's create words it")
    fun otherIntegrityFailureNamesNoField() {
        val run = run(DataIntegrityViolationException("could not execute statement", SQLException("value too long", "22001")))

        assertFalse(run.valid)
        val problem = run.problems.single()
        assertEquals(emptyList<String>(), problem.fields)
        assertEquals(emptySet<String>(), problem.parameters)
        assertFalse(problem.templateProblem)
        assertEquals("Data integrity violation: duplicate or invalid data", problem.message)
    }
}
